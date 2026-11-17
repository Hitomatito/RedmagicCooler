package com.hitomatito.redmagicooler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.hitomatito.redmagicooler.model.CoolerBleConstants
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.data.ProfileRepository
import com.hitomatito.redmagicooler.ble.CoolerBleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Servicio de primer plano para control automático del cooler
 * Permite monitoreo continuo sin mantener la app abierta
 */
class CoolerService : Service() {
    companion object {
        private const val TAG = "CoolerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cooler_service_channel"
        // Azul de la paleta clara de respaldo (los dispositivos con Material You ignoran el tint).
        private const val NOTIFICATION_COLOR = 0xFF415F91.toInt()
        // Niveles térmicos que se pasan a la notificación; cualquier otro texto es estado transitorio.
        private val THERMAL_STATUSES = setOf("Normal", "Tibio", "Caliente", "Critico")

        const val ACTION_START_AUTO = "com.hitomatito.redmagicooler.START_AUTO"
        const val ACTION_STOP_AUTO = "com.hitomatito.redmagicooler.STOP_AUTO"
        const val ACTION_SWITCH_TO_MANUAL = "com.hitomatito.redmagicooler.SWITCH_TO_MANUAL"
        
        // SharedPreferences keys
        private const val PREFS_NAME = "cooler_service_prefs"
        private const val KEY_LAST_TEMP = "last_temperature"
        private const val KEY_LAST_SPEED = "last_speed"
        
        // Logging condicional - CAMBIAR A FALSE PARA PRODUCCIÓN
        // En producción, solo se mostrarán logs de error/warning, ahorrando ~1-2MB de memoria
        private const val DEBUG = true
        
        // Métricas de batería
        private const val KEY_BATTERY_START = "battery_start_level"
        private const val KEY_BATTERY_START_TIME = "battery_start_time"
    }
    
    private var thermalDataCollectionJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // La conexión GATT y la detección viven en CoolerBleManager (propietario único).
    // El servicio actúa como cliente: observa eventos y emite comandos.
    private val isConnected: Boolean
        get() = CoolerBleManager.state.value == CoolerBleManager.ConnectionState.CONNECTED
    private var lastAutoAdjustTime = 0L
    private var currentTemp = 0f
    private var currentSpeed = 0
    private var lastNotificationTime = 0L
    private var lastNotifiedSpeed = -1
    private var lastNotifiedTemp = -1f
    private var currentSpeedFromRead = -1 // Para almacenar resultado de lecturas BLE
    private var lastLoggedRecommendedSpeed = -1

    // RGB solo en la activación del modo automático: consume el evento de forma única.
    private var applyRgbOnNextConnect = false
    
    // Perfil específico para el modo automático
    private var activeProfile: CoolerProfile? = null
    
    // Aplica la configuración RGB guardada en el perfil, si existe, cuando la conexión se estabiliza.
    private fun applyPerfilRgbWhenSettled() {
        val rgb = activeProfile?.let { p ->
            ProfileRepository.getInstance(this).getProfile(p.id)?.rgbConfig ?: p.rgbConfig
        } ?: return
        serviceScope.launch {
            // Pequeña espera: la característica RGB tarda en resolverse tras la conexión.
            delay(1000)
            CoolerBleManager.setRGBLight(rgb.effect, rgb.red, rgb.green, rgb.blue)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        logDebug("Servicio creado")
        
        // Inicializar métricas de batería
        initBatteryMetrics()
        
        createNotificationChannel()
        
        // Restaurar estado guardado
        loadState()
        
        startForeground(NOTIFICATION_ID, createNotification("Iniciando...", currentSpeed, currentTemp))
        
        // Monitor térmico único compartido con la actividad
        ThermalMonitorManager.initialize(this)
        CoolerBleManager.init(this)
        observeBleEvents()
        
        // CRÍTICO: Solo conectar si hay un perfil configurado
        if (activeProfile != null) {
            logDebug("Perfil cargado: ${activeProfile?.displayName}, iniciando reconexión automática")
            activeProfile?.let { p -> CoolerBleManager.requestReconnect(p.macAddress, p.deviceType) }
            startThermalMonitoring()
        } else {
            logDebug("No hay perfil configurado, esperando configuracion desde MainActivity")
            updateNotification("Esperando configuración...", 0, 0f)
        }
    }
    
    /** Traduce los eventos del motor BLE al estado/notificaciones del servicio. */
    private fun observeBleEvents() {
        serviceScope.launch {
            CoolerBleManager.events.collect { event ->
                when (event) {
                    is CoolerBleManager.BleEvent.Connected -> {
                        logDebug("Cooler conectado: ${event.device.deviceType.deviceName}")
                        activeProfile?.let { p ->
                            ProfileRepository.getInstance(this@CoolerService).updateConnectionState(p.id, true)
                        }
                        if (applyRgbOnNextConnect) {
                            applyRgbOnNextConnect = false
                            applyPerfilRgbWhenSettled()
                        }
                        // Conexión (re)tras auto: sembrar la rampa con la velocidad física si aún
                        // no se calibró funcionalmente. Es idempotente y cubre el caso en que
                        // activate-Auto se emitió con el cooler desconectado.
                        if (activeProfile != null) {
                            performInitialCalibration()
                        }
                        updateNotification("Conectado", currentSpeed, currentTemp)
                    }
                    is CoolerBleManager.BleEvent.Disconnected -> {
                        logDebug("Cooler desconectado (status=${event.status})")
                        // Solo reflejar la desconexión: el modo auto NO se desactiva aquí
                        // (el motor BLE reintenta solo); el modo se cambia vía ACTION_SWITCH_TO_MANUAL.
                        activeProfile?.let { p ->
                            ProfileRepository.getInstance(this@CoolerService).updateConnectionState(p.id, false)
                        }
                        updateNotification("Desconectado", currentSpeed, currentTemp)
                    }
                    is CoolerBleManager.BleEvent.FanSpeedChanged -> {
                        currentSpeed = event.percent
                        if (currentSpeedFromRead == -1) {
                            currentSpeedFromRead = event.percent
                            logDebug("Velocidad leída del cooler: ${event.percent}% (raw: ${event.raw})")
                        }
                    }
                    is CoolerBleManager.BleEvent.TemperatureRaw -> {
                        logDebug("Temperatura cruda del cooler: ${event.raw}")
                    }
                    is CoolerBleManager.BleEvent.ConnectionFailed -> {
                        Log.e(TAG, "Error de conexión BLE: ${event.reason}")
                        updateNotification("Error: ${event.reason}", currentSpeed, currentTemp)
                    }
                    is CoolerBleManager.BleEvent.DiscoveryRetry -> {
                        logDebug("Reintentando descubrimiento de servicios (${event.attempt}/${event.max})...")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO -> {
                // El perfil persistido es la fuente de verdad: el servicio no inventa un perfil.
                val profileId = intent.getStringExtra("PROFILE_ID")
                val profile = profileId?.let { ProfileRepository.getInstance(this).getProfile(it) }

                if (profile == null) {
                    Log.e(TAG, "ACTION_START_AUTO: no se encontró el perfil (id=$profileId)")
                    updateNotification("Error: Perfil no encontrado", 0, 0f)
                } else {
                    activeProfile = profile.copy(isAutoMode = true)
                    logDebug("Modo automatico iniciado con perfil: ${activeProfile?.displayName}")

                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit {
                            putString("ACTIVE_PROFILE_ID", activeProfile?.id)
                                .putString("ACTIVE_PROFILE_TYPE", activeProfile?.deviceType?.name)
                                .putString("ACTIVE_PROFILE_MAC", activeProfile?.macAddress)
                                .putString("ACTIVE_PROFILE_NAME", activeProfile?.name)
                        }

                    applyRgbOnNextConnect = true
                    // Reutilizar conexión si el motor BLE ya está conectado a ESTE cooler
                    // (evita el ciclo desconectar+volver a conectar+re-discovery al activar Auto)
                    val yaConectado = CoolerBleManager.isHealthy() &&
                        CoolerBleManager.currentConnectedAddress()?.equals(profile.macAddress, ignoreCase = true) == true
                    if (yaConectado) {
                        logDebug("Ya conectado al cooler. No hace falta reconectar (modo automático)")
                        applyRgbOnNextConnect = false
                        applyPerfilRgbWhenSettled()
                    } else {
                        logDebug("Conectando al dispositivo del perfil: ${profile.macAddress}")
                        CoolerBleManager.connectByMac(profile.macAddress, profile.deviceType)
                    }
                    // Realizar calibración inicial después de conectar
                    performInitialCalibration()
                    startThermalMonitoring()
                }
            }
            ACTION_STOP_AUTO -> {
                val targetProfileId = intent.getStringExtra("PROFILE_ID")
                val current = activeProfile?.id
                // Si piden parar un perfil concreto:
                // - con sesión activa de otro perfil: no tumbes la ajena
                // - sin sesión activa: no desconectes un cooler que la UI pueda tener manualmente
                if (targetProfileId != null && targetProfileId != current) {
                    Log.w(TAG, "ACTION_STOP_AUTO ignorado: el perfil pedido ($targetProfileId) no es el activo (${current ?: "ninguno"})")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "ACTION_STOP_AUTO: Deteniendo servicio y liberando recursos")

                 // Detener monitoreo térmico automático; el monitor compartido sigue alimentando la UI
                thermalDataCollectionJob?.cancel()
                thermalDataCollectionJob = null
                
                // Desconectar el motor BLE de forma intencional (cancela reintentos)
                CoolerBleManager.disconnect()
                
                // Actualizar estado del perfil en el repositorio
                activeProfile?.let { profile ->
                    ProfileRepository.getInstance(this).updateAutoMode(profile.id, false)
                    ProfileRepository.getInstance(this).updateConnectionState(profile.id, false)
                }
                
                // Limpiar estado guardado
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { clear() }
                
                // Detener servicio (NO mata el proceso de la app)
                activeProfile = null
                stopSelf()
            }
            ACTION_SWITCH_TO_MANUAL -> {
                val targetProfileId = intent.getStringExtra("PROFILE_ID")
                val current = activeProfile?.id
                if (targetProfileId != null && current != null && targetProfileId != current) {
                    Log.w(TAG, "ACTION_SWITCH_TO_MANUAL ignorado: el perfil pedido ($targetProfileId) no es el activo ($current)")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "ACTION_SWITCH_TO_MANUAL: Cambiando a modo manual sin desconectar")
                switchToManualMode()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        logDebug("Servicio destruido")
        
        // Mostrar métricas finales de batería
        logBatteryMetrics()
        
        // NO tocar ProfileRepository aquí: la muerte del proceso no debe borrar el flag de auto.
        // STOP_AUTO y SWITCH_TO_MANUAL son los únicos autorizados a desactivarlo.

        // Persistir estado coherente antes de destruir (sin perfil -> borra ACTIVE_PROFILE_*)
        saveState()
        
        // Detener monitoreo y cancelar coroutines.
        // La conexión GATT la mantiene CoolerBleManager (compartida con MainActivity):
        // no la cerramos aquí para que la app pueda seguir usándola tras salir del servicio.
        thermalDataCollectionJob?.cancel()
        thermalDataCollectionJob = null
        serviceScope.cancel()
    }
    
    /**
     * Cambia el dispositivo a modo manual escribiendo a la característica de control automático
     * Según el logcat de la app original, se escribe 0x00 para cambiar el modo
     * Este método NO desconecta el dispositivo, solo cambia el modo de operación
     */
    private fun switchToManualMode() {
        // La escritura 0x1018 es best-effort: el firmware la trata como toggle,
        // así que el software no puede verificarla. La fuente de verdad del
        // estado lógico sigue siendo el repositorio, con actualización siempre.
        // Sin perfil activo no hay nada que escribir: el toggle haría lo contrario de lo pedido.
        val writeOk = activeProfile?.let { CoolerBleManager.setAutoMode() } ?: true
        if (!writeOk) {
            Log.e(TAG, "No se pudo escribir el comando de cambio a modo manual; el perfil se marca igualmente en manual")
            updateNotification("Aviso: cooler quizás siga en auto", currentSpeed, currentTemp)
        }

        // Cancelar el bucle antes que nada para que ningún ajuste llegue tarde.
        thermalDataCollectionJob?.cancel()
        thermalDataCollectionJob = null

        activeProfile?.let { profile ->
            ProfileRepository.getInstance(this).updateAutoMode(profile.id, false)
            Log.d(TAG, "Perfil actualizado: modo manual activado")
        }

        activeProfile = null
        updateNotification("Modo Manual", currentSpeed, currentTemp)
        saveState() // activeProfile == null -> borra ACTIVE_PROFILE_* para que no resucite
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Control Automático del Cooler",
                NotificationManager.IMPORTANCE_MIN  // MIN para no mostrar en barra de estado
            ).apply {
                description = "Monitoreo térmico y control automático del RedMagic Cooler"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String, speed: Int, temp: Float): Notification {
        val stopIntent = Intent(this, CoolerService::class.java).apply {
            action = ACTION_STOP_AUTO
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val manualIntent = Intent(this, CoolerService::class.java).apply {
            action = ACTION_SWITCH_TO_MANUAL
            activeProfile?.id?.let { putExtra("PROFILE_ID", it) }
        }
        val manualPendingIntent = PendingIntent.getService(
            this, 1, manualIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Estado térmico con lectura válida: la notificación muestra temperatura y ventilador.
        val isThermal = status in THERMAL_STATUSES

        val title: String
        val contentText: String
        if (isThermal) {
            title = if (speed > 0) {
                "${"%.1f".format(temp)} °C · Ventilador $speed%"
            } else {
                "${"%.1f".format(temp)} °C · En reposo"
            }
            contentText = "Modo automático · $status"
        } else {
            title = "Modo automático"
            contentText = status
        }

        // Vista personalizada compacta; al expandir se reutiliza (sin panel grande).
        val collapsed = RemoteViews(packageName, R.layout.notification_cooler_collapsed)
        if (isThermal) {
            val tempText = "%.1f".format(temp)
            collapsed.setTextViewText(R.id.notif_main, "$tempText °C · Ventilador $speed% · $status")
            collapsed.setProgressBar(R.id.notif_progress, 100, speed, false)
            val (avatar, fan) = when (status) {
                "Normal" -> R.drawable.notif_avatar_ok to R.drawable.notif_fan_ok
                "Tibio" -> R.drawable.notif_avatar_warm to R.drawable.notif_fan_warm
                "Caliente" -> R.drawable.notif_avatar_hot to R.drawable.notif_fan_hot
                "Critico" -> R.drawable.notif_avatar_critical to R.drawable.notif_fan_critical
                else -> R.drawable.notif_avatar_idle to R.drawable.notif_fan_idle
            }
            collapsed.setImageViewResource(R.id.notif_avatar, fan)
            try {
                collapsed.setInt(R.id.notif_avatar, "setBackgroundResource", avatar)
            } catch (e: Exception) {
                logDebug("No se pudo cambiar el avatar de la notificación: ${e.message}")
            }
        } else {
            collapsed.setTextViewText(R.id.notif_main, "RedMagic Cooler · $status")
            collapsed.setProgressBar(R.id.notif_progress, 0, 0, true)
            collapsed.setImageViewResource(R.id.notif_avatar, R.drawable.notif_fan_idle)
            try {
                collapsed.setInt(R.id.notif_avatar, "setBackgroundResource", R.drawable.notif_avatar_idle)
            } catch (e: Exception) {
                logDebug("No se pudo cambiar el avatar de la notificación: ${e.message}")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("RedMagic Cooler")
            .setSmallIcon(R.drawable.ic_notification_fan)
            .setColor(NOTIFICATION_COLOR)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true) // Solo alertar una vez, updates silenciosos
            .setSilent(true) // Silenciosa para ahorrar recursos
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Manual", manualPendingIntent)
            .addAction(0, "Detener", stopPendingIntent)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setPriority(NotificationCompat.PRIORITY_MIN)  // MIN para no mostrar en status bar
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)  // Diferir mostrar en status bar

        return builder.build()
    }
    
    private fun updateNotification(status: String, speed: Int, temp: Float) {
        val notification = createNotification(status, speed, temp)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun updateNotificationIfNeeded(status: String, speed: Int, temp: Float, forceUpdate: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val speedChanged = speed != lastNotifiedSpeed
        val tempChangedSignificantly = abs(temp - lastNotifiedTemp) >= 1.0f
        val timeLimitReached = currentTime - lastNotificationTime > 30000L
        
        // Actualizar si: 1) cambió velocidad, 2) temp cambió >1°C, 3) pasaron 30s, o 4) forzado
        if (forceUpdate || speedChanged || tempChangedSignificantly || timeLimitReached) {
            updateNotification(status, speed, temp)
            lastNotificationTime = currentTime
            lastNotifiedSpeed = speed
            lastNotifiedTemp = temp
        }
    }
    
    /**
     * Lee la velocidad actual del cooler desde el dispositivo BLE
     * @return velocidad en porcentaje (0-100) o -1 si no se pudo leer
     */
    private suspend fun readCurrentFanSpeed(): Int {
        // La lectura la envia CoolerBleManager; la respuesta llega como evento FanSpeedChanged
        if (!CoolerBleManager.readFanSpeed()) {
            return -1
        }
        var attempts = 0
        while (attempts < 20 && currentSpeedFromRead == -1) {
            delay(100L)
            attempts++
        }
        val speed = currentSpeedFromRead
        currentSpeedFromRead = -1 // Reset para proxima lectura
        return speed
    }
    
    /**
     * Realiza calibración inicial al activar modo automático
     * Obtiene datos térmicos y velocidad actual del cooler inmediatamente
     */
    private fun performInitialCalibration() {
        serviceScope.launch {
            try {
                logDebug("Iniciando calibración inicial del modo automático...")
                
                // Esperar 2 segundos para que la conexión BLE se estabilice
                delay(2000L)
                
                // Obtener la instantánea térmica más reciente del monitor compartido
                val thermalData = ThermalMonitorManager.currentFreshData()
                currentTemp = thermalData.maxTemp
                
                logDebug("Calibración inicial - Temperatura: ${thermalData.maxTemp}°C, Nivel: ${thermalData.tempLevel}")
                
                // Leer velocidad actual del cooler desde el dispositivo
                if (CoolerBleManager.isHealthy()) {
                    try {
                        // Intentar leer la velocidad actual del cooler
                        val currentDeviceSpeed = readCurrentFanSpeed()
                        if (currentDeviceSpeed >= 0) {
                            currentSpeed = currentDeviceSpeed
                            // Sembrar la rampa compartida con la velocidad física:
                            // si no, arranca en 0 y el lazo inicial bajaría el fan indebidamente.
                            ThermalMonitorManager.seedRamp(currentDeviceSpeed)
                            logDebug("Calibración inicial - Velocidad actual del cooler: $currentSpeed%")

                            // Actualizar notificación con datos calibrados
                            updateNotificationIfNeeded(thermalData.tempLevel.toStatusLabel(), currentSpeed, currentTemp)

                            // El salto ideal de la curva es la referencia de catch-up al activar.
                            // Solo subir: la bajada la lleva la rampa con su histéresis.
                            val targetSpeed = ThermalPolicy.targetSpeed(thermalData.maxTemp)
                            if (targetSpeed - currentSpeed >= 15) {
                                logDebug("Aplicando ajuste inicial: $currentSpeed% → $targetSpeed%")
                                setFanSpeed(targetSpeed)
                            } else {
                                logDebug("Velocidad actual ($currentSpeed%) suficiente respecto a objetivo ($targetSpeed%); la bajada la llevará la rampa")
                            }
                        } else {
                            logDebug("No se pudo leer velocidad actual del cooler, usando objetivo de curva")
                            val targetSpeed = ThermalPolicy.targetSpeed(thermalData.maxTemp)
                            setFanSpeed(targetSpeed)
                            currentSpeed = targetSpeed
                        }
                    } catch (e: Exception) {
                        logDebug("Error leyendo velocidad actual: ${e.message}, usando objetivo de curva")
                        val targetSpeed = ThermalPolicy.targetSpeed(thermalData.maxTemp)
                        setFanSpeed(targetSpeed)
                        currentSpeed = targetSpeed
                    }
                } else {
                    logDebug("Cooler no conectado, esperando conexión para calibración")
                }
                
                logDebug("Calibración inicial completada")
                
            } catch (e: Exception) {
                logDebug("Error en calibración inicial: ${e.message}")
            }
        }
    }

    private fun startThermalMonitoring() {
        // Solo la controladora automática consume el estado compartido; la UI tiene su propio colector.
        thermalDataCollectionJob?.cancel()
        thermalDataCollectionJob = serviceScope.launch {
            ThermalMonitorManager.thermalData.collect { data ->
            if (data.recommendedSpeed != lastLoggedRecommendedSpeed) {
                logDebug("Monitor térmico: ${data.tempSources}")
                lastLoggedRecommendedSpeed = data.recommendedSpeed
            }
            currentTemp = data.maxTemp
            
            if (CoolerBleManager.isHealthy()) {
                val currentTime = System.currentTimeMillis()
                
                // Ajustar más frecuentemente para mejor respuesta del sistema progresivo
                // Intervalo reducido a 10 segundos (desde 15s) para mejor progresión
                if (currentTime - lastAutoAdjustTime > 10000) {
                    val recommendedSpeed = data.recommendedSpeed
                    
                    // Solo aplicar el cambio si la velocidad recomendada es diferente
                    // Esto permite que el sistema progresivo controle el ritmo de incremento
                    if (recommendedSpeed != currentSpeed) {
                        setFanSpeed(recommendedSpeed)
                        lastAutoAdjustTime = currentTime
                        
                        // Log informativo para temperaturas elevadas
                        if (data.tempLevel == ThermalMonitor.TempLevel.HOT || 
                            data.tempLevel == ThermalMonitor.TempLevel.CRITICAL) {
                            Log.i(TAG, "Ajuste automático: ${data.maxTemp}°C → $recommendedSpeed%")
                        }
                    }
                }
                
                val tempStatus = data.tempLevel.toStatusLabel()

                // Actualizar notificación (se actualizará automáticamente si hay cambios significativos)
                updateNotificationIfNeeded(tempStatus, currentSpeed, currentTemp)
            } else {
                updateNotificationIfNeeded("Esperando conexión", 0, currentTemp)
            }
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun setFanSpeed(speed: Int) {
        if (!CoolerBleManager.setFanSpeed(speed)) {
            logDebug("Saltando ajuste de velocidad: no conectado")
            return
        }
        val rawValue = CoolerBleConstants.percentageToRaw(speed.coerceIn(0, 100))
        val previousSpeed = currentSpeed
        currentSpeed = speed
        logDebug("Ajustando velocidad automáticamente: $speed% (raw: $rawValue)")
        
        // Actualizar notificación inmediatamente al cambiar velocidad
        if (previousSpeed != speed) {
            val tempStatus = ThermalPolicy.tempLevel(currentTemp).toStatusLabel()
            updateNotification(tempStatus, currentSpeed, currentTemp)
            lastNotifiedSpeed = currentSpeed
            lastNotifiedTemp = currentTemp
        }
    }
    
    private fun saveState() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().apply {
                putFloat(KEY_LAST_TEMP, currentTemp)
                putInt(KEY_LAST_SPEED, currentSpeed)
                
                // Guardar perfil activo
                activeProfile?.let { profile ->
                    putString("ACTIVE_PROFILE_ID", profile.id)
                    putString("ACTIVE_PROFILE_TYPE", profile.deviceType.name)
                    putString("ACTIVE_PROFILE_MAC", profile.macAddress)
                    putString("ACTIVE_PROFILE_NAME", profile.name)
                } ?: run {
                    // Limpiar si no hay perfil activo
                    remove("ACTIVE_PROFILE_ID")
                    remove("ACTIVE_PROFILE_TYPE")
                    remove("ACTIVE_PROFILE_MAC")
                    remove("ACTIVE_PROFILE_NAME")
                }
                
                apply()
            }
            Log.d(TAG, "Estado guardado: temp=$currentTemp, speed=$currentSpeed, profile=${activeProfile?.displayName ?: "ninguno"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando estado: ${e.message}")
        }
    }
    
    private fun loadState() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            currentTemp = prefs.getFloat(KEY_LAST_TEMP, 0f)
            currentSpeed = prefs.getInt(KEY_LAST_SPEED, 0)

            // Cargar perfil activo; el repositorio manda: si allí el auto no está activo,
            // las prefs del servicio son restos de un ciclo anterior y se descartan.
            val profileId = prefs.getString("ACTIVE_PROFILE_ID", null)
            val repoProfile = profileId?.let { ProfileRepository.getInstance(this).getProfile(it) }
            if (repoProfile != null && repoProfile.isAutoMode) {
                activeProfile = repoProfile.copy(isConnected = false, isAutoMode = true)
            } else if (profileId != null) {
                Log.w(TAG, "Estado guardado obsoleto (id=$profileId); se descarta")
                prefs.edit {
                    remove("ACTIVE_PROFILE_ID")
                    remove("ACTIVE_PROFILE_TYPE")
                    remove("ACTIVE_PROFILE_MAC")
                    remove("ACTIVE_PROFILE_NAME")
                }
            }

            Log.d(TAG, "Estado restaurado: temp=$currentTemp, speed=$currentSpeed, profile=${activeProfile?.displayName ?: "ninguno"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando estado: ${e.message}")
        }
    }
    
    // Funciones de logging condicional
    private fun logDebug(message: String) {
        if (DEBUG) {
            Log.d(TAG, message)
        }
    }
    
    // Funciones de métricas de batería
    private fun initBatteryMetrics() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val batteryLevel = getCurrentBatteryLevel()
            val currentTime = System.currentTimeMillis()
            
            prefs.edit().apply {
                putInt(KEY_BATTERY_START, batteryLevel)
                putLong(KEY_BATTERY_START_TIME, currentTime)
                apply()
            }
            
            logDebug("Métricas iniciales: Batería=$batteryLevel%")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando métricas de batería: ${e.message}")
        }
    }
    
    private fun logBatteryMetrics() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val startLevel = prefs.getInt(KEY_BATTERY_START, -1)
            val startTime = prefs.getLong(KEY_BATTERY_START_TIME, 0)
            
            if (startLevel >= 0 && startTime > 0) {
                val endLevel = getCurrentBatteryLevel()
                val endTime = System.currentTimeMillis()
                val batteryUsed = startLevel - endLevel
                val durationMinutes = (endTime - startTime) / 60000
                
                if (durationMinutes > 0) {
                    val batteryPerHour = (batteryUsed.toFloat() / durationMinutes) * 60
                    Log.i(TAG, "=== MÉTRICAS DE BATERÍA ===")
                    Log.i(TAG, "Duración: ${durationMinutes}min")
                    Log.i(TAG, "Batería inicial: $startLevel%")
                    Log.i(TAG, "Batería final: $endLevel%")
                    Log.i(TAG, "Batería usada: $batteryUsed%")
                    Log.i(TAG, "Consumo estimado: ${"%.2f".format(batteryPerHour)}%/hora")
                    Log.i(TAG, "=========================")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculando métricas de batería: ${e.message}")
        }
    }
    
    private fun getCurrentBatteryLevel(): Int {
        return try {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            
            if (level >= 0 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo nivel de batería: ${e.message}")
            -1
        }
    }
}
