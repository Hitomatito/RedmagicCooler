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
import androidx.core.app.NotificationCompat
import com.hitomatito.redmagicooler.model.CoolerBleConstants
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig
import com.hitomatito.redmagicooler.data.ProfileRepository
import com.hitomatito.redmagicooler.ble.CoolerBleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

        const val ACTION_START_AUTO = "com.hitomatito.redmagicooler.START_AUTO"
        const val ACTION_STOP_AUTO = "com.hitomatito.redmagicooler.STOP_AUTO"
        const val ACTION_SWITCH_TO_MANUAL = "com.hitomatito.redmagicooler.SWITCH_TO_MANUAL"
        const val ACTION_SET_DEVICE_TYPE = "com.hitomatito.redmagicooler.SET_DEVICE_TYPE"
        const val ACTION_USE_CONNECTED_DEVICE = "com.hitomatito.redmagicooler.ACTION_USE_CONNECTED_DEVICE"
        const val EXTRA_DEVICE_TYPE = "device_type"
        
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
        
        // Instancia estática para acceso desde MainActivity (control RGB en modo auto)
        @Volatile
        private var instance: CoolerService? = null
        
        fun getInstance(): CoolerService? = instance
    }
    
    private lateinit var thermalMonitor: ThermalMonitor
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
    
    // Perfil específico para el modo automático
    private var activeProfile: CoolerProfile? = null
    
    // Configuración RGB pendiente de aplicar
    private var pendingRGBConfig: RGBConfig? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this // Guardar instancia para acceso externo
        logDebug("Servicio creado")
        
        // Inicializar métricas de batería
        initBatteryMetrics()
        
        createNotificationChannel()
        
        // Restaurar estado guardado
        loadState()
        
        startForeground(NOTIFICATION_ID, createNotification("Iniciando...", currentSpeed, currentTemp))
        
        thermalMonitor = ThermalMonitor(this)
        // No iniciar sensor ambiental en background para ahorrar batería
        // thermalMonitor.startAmbientSensor()
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
                        pendingRGBConfig?.let { cfg ->
                            CoolerBleManager.applyPendingRGB(cfg)
                            pendingRGBConfig = null
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
                // Al iniciar modo automático, cargar perfil desde el intent
                val profileId = intent.getStringExtra("PROFILE_ID")
                val deviceTypeName = intent.getStringExtra(EXTRA_DEVICE_TYPE)
                val deviceMac = intent.getStringExtra("DEVICE_MAC")
                val deviceName = intent.getStringExtra("DEVICE_NAME")

                if (deviceTypeName != null && deviceMac != null) {
                    val deviceType = try {
                        CoolerDeviceType.valueOf(deviceTypeName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }

                    if (deviceType != null) {
                        // Crear perfil activo para el servicio
                        activeProfile = CoolerProfile(
                            id = profileId ?: "service_profile",
                            name = deviceName ?: deviceType.deviceName,
                            deviceType = deviceType,
                            macAddress = deviceMac,
                            isConnected = false,
                            isAutoMode = true
                        )

                        logDebug("Modo automatico iniciado con perfil: ${activeProfile?.displayName}")

                        // Guardar preferencia
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit {
                                putString("ACTIVE_PROFILE_ID", activeProfile?.id)
                                    .putString(
                                        "ACTIVE_PROFILE_TYPE",
                                        activeProfile?.deviceType?.name
                                    )
                                    .putString("ACTIVE_PROFILE_MAC", activeProfile?.macAddress)
                                    .putString("ACTIVE_PROFILE_NAME", activeProfile?.name)
                            }

                        // Cargar configuración RGB si viene en el Intent
                        if (intent.hasExtra("RGB_EFFECT")) {
                            val effectCode = intent.getIntExtra("RGB_EFFECT", LightEffect.ALWAYS_BRIGHT.code.toInt()).toByte()
                            val effect = LightEffect.entries.find { it.code == effectCode } ?: LightEffect.ALWAYS_BRIGHT
                            val red = intent.getIntExtra("RGB_RED", 0)
                            val green = intent.getIntExtra("RGB_GREEN", 0)
                            val blue = intent.getIntExtra("RGB_BLUE", 0)

                            // Guardar para aplicar después de conectar
                            pendingRGBConfig = RGBConfig(effect, red, green, blue)
                            logDebug("Configuracion RGB cargada: ${effect.name} R:$red G:$green B:$blue")
                        }

                        // Reutilizar conexión si el motor BLE ya está conectado a ESTE cooler
                        // (evita el ciclo desconectar+volver a conectar+re-discovery al activar Auto)
                        val yaConectado = CoolerBleManager.isHealthy() &&
                            CoolerBleManager.currentConnectedAddress()?.equals(deviceMac, ignoreCase = true) == true
                        if (yaConectado) {
                            logDebug("Ya conectado al cooler. No hace falta reconectar (modo automático)")
                            pendingRGBConfig?.let { cfg ->
                                CoolerBleManager.applyPendingRGB(cfg)
                                pendingRGBConfig = null
                            }
                        } else {
                            // Conectar directamente al dispositivo usando su MAC
                            logDebug("Conectando al dispositivo del perfil: $deviceMac")
                            activeProfile?.let { p -> CoolerBleManager.connectByMac(p.macAddress, p.deviceType) }
                        }
                        // Realizar calibración inicial después de conectar
                        performInitialCalibration()
                        startThermalMonitoring()
                    } else {
                        Log.e(TAG, "Tipo de dispositivo invalido recibido: $deviceTypeName")
                        updateNotification("Error: Perfil inválido", 0, 0f)
                    }
                } else {
                    Log.e(TAG, "Informacion de perfil incompleta en ACTION_START_AUTO")
                    updateNotification("Error: Sin perfil", 0, 0f)
                }
            }
            ACTION_STOP_AUTO -> {
                Log.d(TAG, "ACTION_STOP_AUTO: Deteniendo servicio y liberando recursos")
                
                // Detener monitoreo térmico primero
                thermalMonitor.stopMonitoring()
                
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
                stopSelf()
            }
            ACTION_SWITCH_TO_MANUAL -> {
                Log.d(TAG, "ACTION_SWITCH_TO_MANUAL: Cambiando a modo manual sin desconectar")
                switchToManualMode()
            }
            ACTION_USE_CONNECTED_DEVICE -> {
                // Conectar directamente usando la MAC del dispositivo ya conectado
                val deviceMac = intent.getStringExtra("DEVICE_MAC")
                if (deviceMac != null) {
                    Log.d(TAG, "Conectando directamente a dispositivo: $deviceMac (sin escaneo)")
                    activeProfile?.let { p -> CoolerBleManager.connectByMac(deviceMac, p.deviceType) }
                } else {
                    Log.e(TAG, "No se recibio MAC del dispositivo")
                }
            }
            ACTION_SET_DEVICE_TYPE -> {
                // Cambiar perfil activo
                val profileId = intent.getStringExtra("PROFILE_ID")
                val deviceTypeName = intent.getStringExtra(EXTRA_DEVICE_TYPE)
                val deviceMac = intent.getStringExtra("DEVICE_MAC")
                val deviceName = intent.getStringExtra("DEVICE_NAME")

                if (deviceTypeName != null && deviceMac != null) {
                    val deviceType = try {
                        CoolerDeviceType.valueOf(deviceTypeName)
                    } catch (_: IllegalArgumentException) {
                        null
                    }

                    if (deviceType != null) {
                        activeProfile = CoolerProfile(
                            id = profileId ?: "service_profile",
                            name = deviceName ?: deviceType.deviceName,
                            deviceType = deviceType,
                            macAddress = deviceMac,
                            isConnected = false,
                            isAutoMode = true
                        )

                        logDebug("Perfil activo configurado: ${activeProfile?.displayName}")

                        // Guardar preferencia
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit {
                                putString("ACTIVE_PROFILE_ID", activeProfile?.id)
                                putString("ACTIVE_PROFILE_TYPE", activeProfile?.deviceType?.name)
                                putString("ACTIVE_PROFILE_MAC", activeProfile?.macAddress)
                                putString("ACTIVE_PROFILE_NAME", activeProfile?.name)
                            }

                        // El motor BLE decide el estado real de escaneo/conexión
                        CoolerBleManager.stopScan()

                        // Solo iniciar conexión si hay perfil configurado
                        if (activeProfile != null) {
                            activeProfile?.let { p -> CoolerBleManager.requestReconnect(p.macAddress, p.deviceType) }
                            startThermalMonitoring()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null // Limpiar instancia
        logDebug("Servicio destruido")
        
        // Mostrar métricas finales de batería
        logBatteryMetrics()
        
        // CRÍTICO: Limpiar estado del perfil SIEMPRE al destruir
        activeProfile?.let { profile ->
            try {
                ProfileRepository.getInstance(this).updateAutoMode(profile.id, false)
                ProfileRepository.getInstance(this).updateConnectionState(profile.id, false)
                Log.d(TAG, "Estado del perfil ${profile.name} limpiado en onDestroy")
            } catch (e: Exception) {
                Log.e(TAG, "Error limpiando estado del perfil: ${e.message}")
            }
        }
        
        // Guardar estado antes de destruir
        saveState()
        
        // Detener monitoreo y cancelar coroutines.
        // La conexión GATT la mantiene CoolerBleManager (compartida con MainActivity):
        // no la cerramos aquí para que la app pueda seguir usándola tras salir del servicio.
        thermalMonitor.stopMonitoring()
        serviceScope.cancel()
    }
    
    /**
     * Cambia el dispositivo a modo manual escribiendo a la característica de control automático
     * Según el logcat de la app original, se escribe 0x00 para cambiar el modo
     * Este método NO desconecta el dispositivo, solo cambia el modo de operación
     */
    private fun switchToManualMode() {
        if (!CoolerBleManager.setAutoMode()) {
            Log.e(TAG, "No se pudo escribir el comando de cambio a modo manual")
            updateNotification("Error cambiando a manual", currentSpeed, currentTemp)
            return
        }
        
        try {
            run {
                // Actualizar perfil en el repositorio
                activeProfile?.let { profile ->
                    ProfileRepository.getInstance(this).updateAutoMode(profile.id, false)
                    Log.d(TAG, "Perfil actualizado: modo manual activado")
                }
                
                // Actualizar notificación
                updateNotification("Modo Manual", currentSpeed, currentTemp)
                
                // Detener monitoreo térmico automático
                thermalMonitor.stopMonitoring()
                
                // Opcional: detener el servicio después de cambiar a manual
                // El servicio puede seguir activo para mantener la conexión
                serviceScope.launch {
                    delay(2000) // Dar tiempo para que el comando se procese
                    Log.d(TAG, "Deteniendo servicio después de cambiar a modo manual")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción al cambiar a modo manual: ${e.message}", e)
            updateNotification("Error: ${e.message}", currentSpeed, currentTemp)
        }
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
        
        // Determinar emoji y mensaje según el contexto
        val title: String
        val contentText: String
        
        // Si no está conectado o hay un error
        if (status.contains("Error") || status.contains("Esperando") || 
            status.contains("Buscando") || status.contains("Conectando") || 
            status.contains("Desconectado")) {
            title = "Modo Automático"
            contentText = status
        } else {
            // Conectado y funcionando
            val statusEmoji = when (status) {
                "Critico" -> "🔴"
                "Caliente" -> "🟠"
                "Tibio" -> "🟡"
                "Normal" -> "🟢"
                else -> "🟢"
            }
            
            title = "$statusEmoji ${"%.1f".format(temp)}°C • Modo Auto"
            
            contentText = if (speed > 0) {
                "Enfriando $speed% • $status"
            } else {
                "En reposo • Temp. $status"
            }
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true) // Solo alertar una vez, updates silenciosos
            .setSilent(true) // Silenciosa para ahorrar recursos
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_input_get,
                "Manual",
                manualPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                stopPendingIntent
            )
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_MIN)  // MIN para no mostrar en status bar
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)  // Diferir mostrar en status bar
            .build()
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
                
                // Obtener datos térmicos actuales inmediatamente
                val thermalData = thermalMonitor.getCurrentThermalData(0)
                currentTemp = thermalData.maxTemp
                
                logDebug("Calibración inicial - Temperatura: ${thermalData.maxTemp}°C, Nivel: ${thermalData.tempLevel}")
                
                // Leer velocidad actual del cooler desde el dispositivo
                if (CoolerBleManager.isHealthy()) {
                    try {
                        // Intentar leer la velocidad actual del cooler
                        val currentDeviceSpeed = readCurrentFanSpeed()
                        if (currentDeviceSpeed >= 0) {
                            currentSpeed = currentDeviceSpeed
                            logDebug("Calibración inicial - Velocidad actual del cooler: $currentSpeed%")
                            
                            // Actualizar notificación con datos calibrados
                            val tempStatus = when (thermalData.tempLevel) {
                                ThermalMonitor.TempLevel.SAFE -> "Normal"
                                ThermalMonitor.TempLevel.WARM -> "Tibio" 
                                ThermalMonitor.TempLevel.HOT -> "Caliente"
                                ThermalMonitor.TempLevel.CRITICAL -> "Crítico"
                            }
                            updateNotificationIfNeeded(tempStatus, currentSpeed, currentTemp)
                            
                            // Si la velocidad recomendada es significativamente diferente, aplicar ajuste inicial
                            val recommendedSpeed = thermalData.recommendedSpeed
                            if (abs(recommendedSpeed - currentSpeed) >= 15) {
                                logDebug("Aplicando ajuste inicial: $currentSpeed% → $recommendedSpeed%")
                                setFanSpeed(recommendedSpeed)
                            } else {
                                logDebug("Velocidad actual ($currentSpeed%) está cerca de la recomendada ($recommendedSpeed%), manteniendo")
                            }
                        } else {
                            logDebug("No se pudo leer velocidad actual del cooler, usando velocidad recomendada")
                            // Usar velocidad recomendada como inicial
                            val recommendedSpeed = thermalData.recommendedSpeed
                            setFanSpeed(recommendedSpeed)
                            currentSpeed = recommendedSpeed
                        }
                    } catch (e: Exception) {
                        logDebug("Error leyendo velocidad actual: ${e.message}, usando velocidad recomendada")
                        val recommendedSpeed = thermalData.recommendedSpeed
                        setFanSpeed(recommendedSpeed)
                        currentSpeed = recommendedSpeed
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
        thermalMonitor.startMonitoring(serviceScope) { data ->
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
                
                val tempStatus = when (data.tempLevel) {
                    ThermalMonitor.TempLevel.SAFE -> "Normal"
                    ThermalMonitor.TempLevel.WARM -> "Tibio"
                    ThermalMonitor.TempLevel.HOT -> "Caliente"
                    ThermalMonitor.TempLevel.CRITICAL -> "Critico"
                }
                
                // Actualizar notificación (se actualizará automáticamente si hay cambios significativos)
                updateNotificationIfNeeded(tempStatus, currentSpeed, currentTemp)
            } else {
                updateNotificationIfNeeded("Esperando conexión", 0, currentTemp)
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
            val tempStatus = when {
                currentTemp >= ThermalMonitor.TEMP_CRITICAL -> "Critico"
                currentTemp >= ThermalMonitor.TEMP_HOT -> "Caliente"
                currentTemp >= ThermalMonitor.TEMP_WARM -> "Tibio"
                else -> "Normal"
            }
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

            // Cargar perfil activo
            val profileId = prefs.getString("ACTIVE_PROFILE_ID", null)
            val deviceTypeName = prefs.getString("ACTIVE_PROFILE_TYPE", null)
            val deviceMac = prefs.getString("ACTIVE_PROFILE_MAC", null)
            val deviceName = prefs.getString("ACTIVE_PROFILE_NAME", null)

            if (deviceTypeName != null && deviceMac != null) {
                val deviceType = try {
                    CoolerDeviceType.valueOf(deviceTypeName)
                } catch (_: IllegalArgumentException) {
                    null
                }

                if (deviceType != null) {
                    activeProfile = CoolerProfile(
                        id = profileId ?: "service_profile",
                        name = deviceName ?: deviceType.deviceName,
                        deviceType = deviceType,
                        macAddress = deviceMac,
                        isConnected = false,
                        isAutoMode = true
                    )
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
    
    /**
     * Establece el color y efecto de luz RGB del cooler
     * @param effect Efecto de luz a aplicar
     * @param red Componente rojo (0-255)
     * @param green Componente verde (0-255)
     * @param blue Componente azul (0-255)
     */
    @Suppress("DEPRECATION")
    fun setRGBLight(effect: LightEffect, red: Int = 0, green: Int = 0, blue: Int = 0) {
        // Formato: [modo][RR][GG][BB] - gestionado por el motor BLE
        if (!CoolerBleManager.setRGBLight(effect, red, green, blue)) {
            Log.e(TAG, "Error enviando comando RGB (no conectado o write fallido)")
            return
        }
        val hexColor = "%02x%02x%02x%02x".format(
            effect.code.toInt() and 0xFF,
            red and 0xFF,
            green and 0xFF,
            blue and 0xFF
        )
        Log.d(TAG, "Comando RGB enviado: $hexColor (efecto: ${effect.name}, R:$red G:$green B:$blue)")
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
