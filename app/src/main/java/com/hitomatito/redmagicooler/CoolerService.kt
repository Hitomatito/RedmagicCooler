package com.hitomatito.redmagicooler

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import com.hitomatito.redmagicooler.utils.BleConnectionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

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
        const val ACTION_RECONNECT = "com.hitomatito.redmagicooler.RECONNECT"
        const val ACTION_SET_DEVICE_TYPE = "com.hitomatito.redmagicooler.SET_DEVICE_TYPE"
        const val ACTION_USE_CONNECTED_DEVICE = "com.hitomatito.redmagicooler.ACTION_USE_CONNECTED_DEVICE"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_SCAN_ALL = "scan_all_types"
        
        // SharedPreferences keys
        private const val PREFS_NAME = "cooler_service_prefs"
        private const val KEY_LAST_TEMP = "last_temperature"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_DEAD_OBJECT_COUNT = "dead_object_count"
        private const val KEY_DEAD_OBJECT_DATE = "dead_object_date"
        private const val MAX_DEAD_OBJECT_PER_DAY = 10
        
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
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    private var lightCharacteristic: BluetoothGattCharacteristic? = null
    private var autoModeCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var lastAutoAdjustTime = 0L
    private var currentTemp = 0f
    private var currentSpeed = 0
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var isScanning = false
    private var lastNotificationTime = 0L
    private var lastNotifiedSpeed = -1
    private var lastNotifiedTemp = -1f
    private var currentSpeedFromRead = -1 // Para almacenar resultado de lecturas BLE
    private var reconnectBackoffMs = 2000L // Backoff inicial
    
    // Perfil específico para el modo automático
    private var activeProfile: CoolerProfile? = null
    private val discoveredDevices = CoolerDeviceList()
    private var connectedDevice: CoolerDevice? = null
    
    // Configuración RGB pendiente de aplicar
    private var pendingRGBConfig: RGBConfig? = null
    
    // Caché para evitar logs repetitivos de dispositivos no cooler
    private val loggedDevicesCache = mutableMapOf<String, Long>()
    private val LOG_CACHE_TIMEOUT_MS = 30000L // 30 segundos
    
    // Función para limpiar el caché de logs antiguos
    private fun cleanupLogCache() {
        val currentTime = System.currentTimeMillis()
        val maxAge = 600000L // 10 minutos
        
        loggedDevicesCache.entries.removeIf { (_, timestamp) ->
            (currentTime - timestamp) > maxAge
        }
        
        if (DEBUG) {
            Log.v(TAG, "Caché de logs limpiado: ${loggedDevicesCache.size} entradas restantes")
        }
    }
    
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
        
        // CRÍTICO: Solo conectar si hay un perfil configurado
        if (activeProfile != null) {
            logDebug("Perfil cargado: ${activeProfile?.displayName}, iniciando reconexión automática")
            connectToCooler()
            startThermalMonitoring()
        } else {
            logDebug("No hay perfil configurado, esperando configuracion desde MainActivity")
            updateNotification("Esperando configuración...", 0, 0f)
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

                        // Conectar directamente al dispositivo usando su MAC
                        logDebug("Conectando al dispositivo del perfil: $deviceMac")
                        connectDirectlyToDevice(deviceMac)
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
                
                // Detener escaneo si está activo
                if (isScanning) {
                    try {
                        BleConnectionHelper.safeStopScan(bluetoothLeScanner, scanCallback, TAG)
                        isScanning = false
                    } catch (e: Exception) {
                        Log.w(TAG, "Error deteniendo escaneo: ${e.message}")
                    }
                }
                
                // Desconectar BLE si está conectado
                if (isConnected && bluetoothGatt != null) {
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this)) {
                            Log.d(TAG, "Desconectando dispositivo BLE antes de detener")
                            bluetoothGatt?.disconnect()
                            Thread.sleep(300) // Dar tiempo para desconexión limpia
                            BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error desconectando BLE: ${e.message}", e)
                    }
                    isConnected = false
                }
                
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
            ACTION_RECONNECT -> {
                connectToCooler()
            }
            ACTION_USE_CONNECTED_DEVICE -> {
                // Conectar directamente usando la MAC del dispositivo ya conectado
                val deviceMac = intent.getStringExtra("DEVICE_MAC")
                if (deviceMac != null) {
                    Log.d(TAG, "Conectando directamente a dispositivo: $deviceMac (sin escaneo)")
                    connectDirectlyToDevice(deviceMac)
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

                        // Reiniciar escaneo con nuevo perfil
                        if (isScanning) {
                            try {
                                @SuppressLint("MissingPermission")
                                bluetoothLeScanner?.stopScan(scanCallback)
                                isScanning = false
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deteniendo escaneo previo: ${e.message}")
                            }
                        }

                        // Solo iniciar conexión si hay perfil configurado
                        if (activeProfile != null) {
                            connectToCooler()
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
        
        // CRÍTICO: Detener escaneo PRIMERO para evitar callbacks después de destruir
        if (isScanning) {
            try {
                BleConnectionHelper.safeStopScan(bluetoothLeScanner, scanCallback, TAG)
                isScanning = false
            } catch (e: Exception) {
                Log.w(TAG, "Error deteniendo escaneo: ${e.message}")
            }
        }
        
        // Detener monitoreo y cancelar coroutines
        thermalMonitor.stopMonitoring()
        serviceScope.cancel()
        
        // Cerrar GATT de forma segura
        try {
            @SuppressLint("MissingPermission")
            if (BlePermissionManager.hasBluetoothConnectPermission(this)) {
                BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando BLE: ${e.message}", e)
        }
        
        // Limpiar referencias
        bluetoothGatt = null
        fanCharacteristic = null
        lightCharacteristic = null
        autoModeCharacteristic = null
        bluetoothLeScanner = null
    }
    
    /**
     * Cambia el dispositivo a modo manual escribiendo a la característica de control automático
     * Según el logcat de la app original, se escribe 0x00 para cambiar el modo
     * Este método NO desconecta el dispositivo, solo cambia el modo de operación
     */
    private fun switchToManualMode() {
        if (!isConnected || bluetoothGatt == null) {
            Log.w(TAG, "No se puede cambiar a modo manual: dispositivo no conectado")
            updateNotification("No conectado", 0, currentTemp)
            return
        }
        
        if (autoModeCharacteristic == null) {
            Log.e(TAG, "Característica de modo automático no encontrada")
            updateNotification("Error: característica no disponible", currentSpeed, currentTemp)
            return
        }
        
        if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
            Log.e(TAG, "Permisos BLE faltantes para cambiar modo")
            return
        }
        
        try {
            @SuppressLint("MissingPermission")
            val success = autoModeCharacteristic?.let { characteristic ->
                // Escribir comando según app original: 0x00 para cambiar modo
                characteristic.value = byteArrayOf(CoolerBleConstants.AUTO_MODE_COMMAND)
                val result = bluetoothGatt?.writeCharacteristic(characteristic) ?: false
                Log.d(TAG, "Comando de cambio a modo manual enviado. Resultado: $result")
                result
            } ?: false
            
            if (success) {
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
            } else {
                Log.e(TAG, "Fallo al escribir comando de cambio a modo manual")
                updateNotification("Error cambiando a manual", currentSpeed, currentTemp)
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
        val statusEmoji: String
        val title: String
        val contentText: String
        
        // Si no está conectado o hay un error
        if (status.contains("Error") || status.contains("Esperando") || 
            status.contains("Buscando") || status.contains("Conectando") || 
            status.contains("Desconectado")) {
            statusEmoji = "⚪"
            title = "Modo Automático"
            contentText = status
        } else {
            // Conectado y funcionando
            statusEmoji = when (status) {
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
        val tempChangedSignificantly = kotlin.math.abs(temp - lastNotifiedTemp) >= 1.0f
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
     * Conecta directamente a un dispositivo por MAC sin escanear
     * Usado cuando ya tenemos la MAC del dispositivo conectado
     */
    private fun connectDirectlyToDevice(macAddress: String) {
        if (!BlePermissionManager.hasCriticalBlePermissions(this)) {
            Log.e(TAG, "Permisos BLE críticos faltantes")
            return
        }
        
        try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            
            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth desactivado")
                return
            }
            
            // Obtener dispositivo por MAC
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            if (device == null) {
                Log.e(TAG, "No se pudo obtener dispositivo con MAC: $macAddress")
                return
            }
            
            Log.d(TAG, "Conectando directamente a: $macAddress")
            updateNotification("Conectando...", 0, 0f)
            
            // CRÍTICO: Cerrar GATT anterior si existe
            bluetoothGatt?.let { oldGatt ->
                try {
                    BleConnectionHelper.safeCloseGatt(oldGatt, TAG)
                } catch (e: Exception) {
                    Log.w(TAG, "Error cerrando GATT anterior: ${e.message}")
                }
            }
            
            // Conectar directamente
            @SuppressLint("MissingPermission")
            bluetoothGatt = device.connectGatt(
                this,
                false,  // autoConnect=false para conexión rápida
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            
            reconnectAttempts = 0 // Resetear contador de reintentos
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException conectando directamente: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando directamente: ${e.message}")
        }
    }
    
    private fun connectToCooler() {
        // CRÍTICO: Verificar que se haya configurado un perfil
        if (activeProfile == null) {
            Log.e(TAG, "connectToCooler() llamado sin perfil configurado - Abortando")
            updateNotification("Esperando configuración...", 0, 0f)
            return
        }
        
        if (!BlePermissionManager.hasCriticalBlePermissions(this)) {
            Log.e(TAG, "Permisos BLE críticos faltantes")
            stopSelf()
            return
        }
        
        try {
            val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            
            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "Bluetooth desactivado")
                stopSelf()
                return
            }
            
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "BluetoothLeScanner no disponible")
                stopSelf()
                return
            }
            
            // Limpiar lista de dispositivos al iniciar nuevo escaneo
            discoveredDevices.clear()
            
            // CRÍTICO: Escanear SIN filtro de UUID para asegurar detección
            // El filtro de UUID puede causar problemas después de desconexiones
            Log.d(TAG, "Iniciando escaneo SIN filtro para ${activeProfile?.displayName ?: "todos"}")
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            
            isScanning = true
            updateNotification("Buscando dispositivos...", 0, currentTemp)
            
            // Escanear SIN filtros
            @SuppressLint("MissingPermission")
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            
            // Timeout de escaneo optimizado (20 segundos)
            serviceScope.launch {
                delay(20000L)
                if (isScanning && !isConnected) {
                    logDebug("Timeout de escaneo, deteniendo...")
                    try {
                        @SuppressLint("MissingPermission")
                        bluetoothLeScanner?.stopScan(scanCallback)
                        isScanning = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deteniendo escaneo por timeout: ${e.message}")
                    }
                    // Reintentar si no hemos excedido el límite
                    if (reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        Log.d(TAG, "Reintentando escaneo tras timeout... (intento $reconnectAttempts/$maxReconnectAttempts)")
                        delay(2000L)
                        connectToCooler()
                    } else {
                        Log.w(TAG, "Máximo número de reintentos de escaneo alcanzado")
                        updateNotification("Error de conexión", 0, currentTemp)
                    }
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escaneo: ${e.message}")
            stopSelf()
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0 // Resetear contador de reintentos
                    reconnectBackoffMs = 2000L // Resetear backoff
                    Log.d(TAG, "Conectado al cooler")
                    
                    // Actualizar estado del perfil en el repositorio
                    activeProfile?.let { profile ->
                        ProfileRepository.getInstance(this@CoolerService).updateConnectionState(profile.id, true)
                        Log.d(TAG, "Estado del perfil actualizado: Conectado")
                    }
                    
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                            // BLE sin emparejamiento - el cooler funciona sin bonding
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error descubriendo servicios: ${e.message}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    Log.d(TAG, "Desconectado del cooler, status: $status")
                    updateNotification("Desconectado", 0, currentTemp)
                    
                    // Actualizar estado del perfil en el repositorio
                    activeProfile?.let { profile ->
                        ProfileRepository.getInstance(this@CoolerService).updateConnectionState(profile.id, false)
                        Log.d(TAG, "Estado del perfil actualizado: Desconectado")
                    }
                    
                    // Diferenciar tipos de desconexión
                    val isIntentionalDisconnect = status == BluetoothGatt.GATT_SUCCESS
                    val isOutOfRange = status == 8 || status == 19 || status == 133 // Códigos típicos de fuera de rango
                    val isError = status != BluetoothGatt.GATT_SUCCESS && !isOutOfRange
                    
                    when {
                        isIntentionalDisconnect -> {
                            Log.d(TAG, "Desconexión intencional, no reconectar")
                            return
                        }
                        isOutOfRange -> {
                            Log.w(TAG, "Dispositivo fuera de rango, pausando reintentos por 1 minuto")
                            reconnectBackoffMs = 60000L // 1 minuto
                        }

                        else -> {
                            Log.e(TAG, "Error de conexión detectado")
                        }
                    }
                    
                    // Intentar reconectar automáticamente si no hemos excedido el límite
                    if (reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        Log.d(TAG, "Programando reconexión... (intento $reconnectAttempts/$maxReconnectAttempts, backoff: ${reconnectBackoffMs}ms)")
                        
                        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                        val intent = Intent(this@CoolerService, CoolerService::class.java).apply {
                            action = ACTION_RECONNECT
                        }
                        val pendingIntent = PendingIntent.getService(
                            this@CoolerService, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                            Log.w(TAG, "No se puede programar alarmas exactas, usando inexactas")
                            // Fallback a inexactas
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + reconnectBackoffMs,
                                pendingIntent
                            )
                        } else {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + reconnectBackoffMs,
                                pendingIntent
                            )
                        }
                        
                        reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30000L) // Máximo 30s
                    } else {
                        Log.w(TAG, "Máximo número de reintentos alcanzado, deteniendo servicio")
                        stopSelf()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Servicios descubiertos exitosamente")
                Log.d(TAG, "GATT conectado: ${gatt.device.address}, servicios count: ${gatt.services?.size ?: 0}")
                
                // Verificar que GATT tenga servicios antes de procesar
                val services = gatt.services
                if (services == null || services.isEmpty()) {
                    Log.e(TAG, "Lista de servicios es null o vacía, esperando...")
                    // Reintentar descubrimiento después de un breve delay
                    serviceScope.launch {
                        delay(1000L)
                        try {
                            @SuppressLint("MissingPermission")
                            if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService) && isConnected) {
                                Log.d(TAG, "Reintentando descubrimiento de servicios...")
                                gatt.discoverServices()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reintentando descubrimiento: ${e.message}")
                        }
                    }
                    return
                }
                
                // Listar todos los servicios disponibles
                for (service in services) {
                    Log.d(TAG, "Servicio encontrado: ${service.uuid}")
                    for (char in service.characteristics) {
                        Log.d(TAG, "  -> Characteristic: ${char.uuid}")
                    }
                }
                
                // Buscar primero en el servicio principal del fan
                var fanService = gatt.getService(CoolerBleConstants.FAN_SERVICE_UUID)
                if (fanService != null) {
                    Log.d(TAG, "Servicio del fan encontrado: ${CoolerBleConstants.FAN_SERVICE_UUID}")
                    fanCharacteristic = fanService.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)
                    lightCharacteristic = fanService.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)
                    autoModeCharacteristic = fanService.getCharacteristic(CoolerBleConstants.AUTO_MODE_CONTROL_UUID)
                } else {
                    // Fallback: buscar la característica en todos los servicios
                    Log.d(TAG, "Servicio del fan no encontrado, buscando en todos los servicios...")
                    fanCharacteristic = null
                    lightCharacteristic = null
                    autoModeCharacteristic = null
                    for (service in gatt.services ?: emptyList()) {
                        val fanChar = service.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)
                        if (fanChar != null) {
                            fanCharacteristic = fanChar
                            Log.d(TAG, "Fan characteristic encontrada en servicio: ${service.uuid}")
                        }
                        val lightChar = service.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)
                        if (lightChar != null) {
                            lightCharacteristic = lightChar
                            Log.d(TAG, "Light characteristic encontrada en servicio: ${service.uuid}")
                        }
                        val autoChar = service.getCharacteristic(CoolerBleConstants.AUTO_MODE_CONTROL_UUID)
                        if (autoChar != null) {
                            autoModeCharacteristic = autoChar
                            Log.d(TAG, "Auto mode characteristic encontrada en servicio: ${service.uuid}")
                        }
                        if (fanCharacteristic != null && lightCharacteristic != null && autoModeCharacteristic != null) break
                    }
                }
                
                if (fanCharacteristic != null) {
                    Log.d(TAG, "Fan characteristic encontrada: ${CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID}")
                    if (lightCharacteristic != null) {
                        Log.d(TAG, "Light characteristic encontrada: ${CoolerBleConstants.LIGHT_CONTROL_UUID}")
                    }
                    if (autoModeCharacteristic != null) {
                        Log.d(TAG, "Auto mode characteristic encontrada: ${CoolerBleConstants.AUTO_MODE_CONTROL_UUID}")
                    }
                    if (lightCharacteristic != null) {
                        Log.d(TAG, "Light characteristic encontrada: ${CoolerBleConstants.LIGHT_CONTROL_UUID}")
                    } else {
                        Log.w(TAG, "Light characteristic NO encontrada")
                    }
                    
                    // Mostrar información del perfil activo
                    connectedDevice?.let { device ->
                        Log.i(TAG, "═══════════════════════════════════════════════")
                        Log.i(TAG, "PERFIL ACTIVO: ${device.deviceType.deviceName}")
                        Log.i(TAG, "  Generación: ${device.deviceType.generation}")
                        Log.i(TAG, "  UUID: ${device.deviceType.advertisingUUID}")
                        Log.i(TAG, "  Señal: ${device.signalQuality}% (${device.rssi} dBm)")
                        Log.i(TAG, "  Descripción: ${device.deviceType.description}")
                        Log.i(TAG, "═══════════════════════════════════════════════")
                    }
                    
                    // Determinar estado térmico inicial
                    val initialTempStatus = when {
                        currentTemp >= ThermalMonitor.TEMP_CRITICAL -> "Critico"
                        currentTemp >= ThermalMonitor.TEMP_HOT -> "Caliente"
                        currentTemp >= ThermalMonitor.TEMP_WARM -> "Tibio"
                        else -> "Normal"
                    }
                    updateNotification(initialTempStatus, currentSpeed, currentTemp)
                    
                    // Aplicar configuración RGB pendiente si existe
                    pendingRGBConfig?.let { config ->
                        serviceScope.launch {
                            delay(1000) // Esperar 1 segundo para que la conexión se estabilice
                            setRGBLight(config.effect, config.red, config.green, config.blue)
                            Log.i(TAG, "Configuracion RGB aplicada: ${config.effect.name} R:${config.red} G:${config.green} B:${config.blue}")
                            pendingRGBConfig = null // Limpiar después de aplicar
                        }
                    }
                    
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                            // Habilitar notificaciones en la characteristic de notificaciones
                            val notifCharacteristic = fanService?.getCharacteristic(CoolerBleConstants.TEMPERATURE_NOTIFICATION_UUID)
                            if (notifCharacteristic != null) {
                                gatt.setCharacteristicNotification(notifCharacteristic, true)
                                Log.d(TAG, "Notificaciones habilitadas en: ${CoolerBleConstants.TEMPERATURE_NOTIFICATION_UUID}")
                            }
                            // También en la characteristic del fan
                            gatt.setCharacteristicNotification(fanCharacteristic, true)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error habilitando notificaciones: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "Fan characteristic NO encontrada en ningún servicio")
                    Log.d(TAG, "Servicios disponibles: ${gatt.services?.map { it.uuid } ?: emptyList()}")
                    updateNotification("Error: servicio no encontrado", 0, currentTemp)
                }
            } else {
                Log.e(TAG, "Error descubriendo servicios, status: $status")
                updateNotification("Error descubriendo servicios", 0, currentTemp)
            }
        }
        
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    val rawSpeed = data[0].toInt() and 0xFF
                    val speedPercent = MainActivity.mapRawToPercent(rawSpeed)
                    
                    // Si estamos esperando una lectura para calibración inicial
                    if (currentSpeedFromRead == -1) {
                        currentSpeedFromRead = speedPercent
                        logDebug("Velocidad leída del cooler: $speedPercent% (raw: $rawSpeed)")
                    } else {
                        // Lectura normal - actualizar velocidad actual
                        currentSpeed = speedPercent
                        logDebug("Velocidad actualizada: $speedPercent%")
                    }
                }
            } else {
                logDebug("Error leyendo characteristic: $status")
                currentSpeedFromRead = -1 // Indicar error
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Manejar cambios en características (notificaciones)
            onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceAddress = device.address
            val deviceName = device.name ?: "Unknown"
            val rssi = result.rssi
            val currentTime = System.currentTimeMillis()
            
            // Verificar si este dispositivo ya fue loggeado recientemente
            val lastLogTime = loggedDevicesCache[deviceAddress] ?: 0L
            val shouldLog = (currentTime - lastLogTime) > LOG_CACHE_TIMEOUT_MS
            
            if (shouldLog) {
                Log.d(TAG, "Dispositivo encontrado: $deviceName ($deviceAddress) RSSI: $rssi")
                loggedDevicesCache[deviceAddress] = currentTime
            }
            
            // Identificar tipo de dispositivo por UUID de servicio anunciado
            val scanRecord = result.scanRecord
            var detectedType: CoolerDeviceType? = null
            
            scanRecord?.serviceUuids?.forEach { parcelUuid ->
                val uuid = parcelUuid.uuid
                CoolerDeviceType.fromAdvertisingUUID(uuid)?.let { type ->
                    detectedType = type
                    Log.v(TAG, "Tipo detectado por UUID: ${type.deviceName}")
                    return@forEach
                }
            }
            
            // Fallback: identificar por nombre si no se detectó por UUID
            if (detectedType == null) {
                CoolerDeviceType.entries.forEach { type ->
                    if (type.matchesBleName(deviceName)) {
                        detectedType = type
                        Log.v(TAG, "Tipo detectado por nombre: ${type.deviceName}")
                        return@forEach
                    }
                }
            }
            
            if (detectedType != null) {
                val coolerDevice = CoolerDevice(
                    bluetoothDevice = device,
                    deviceType = detectedType,
                    rssi = rssi
                )
                
                // Agregar a la lista de dispositivos descubiertos
                discoveredDevices.addOrUpdate(coolerDevice)
                
                // Solo loggear la primera vez que se encuentra este cooler
                val coolerKey = "cooler_$deviceAddress"
                val lastCoolerLog = loggedDevicesCache[coolerKey] ?: 0L
                if ((currentTime - lastCoolerLog) > 60000L) { // 1 minuto
                    Log.d(TAG, "Dispositivo cooler encontrado: ${coolerDevice.displayName} - ${coolerDevice.deviceType.deviceName}")
                    loggedDevicesCache[coolerKey] = currentTime
                }
                
                // Verificar si este dispositivo coincide con el perfil activo
                val matchesProfile = activeProfile?.let { profile ->
                    device.address.equals(profile.macAddress, ignoreCase = true) &&
                    detectedType == profile.deviceType
                } ?: false
                
                if (matchesProfile) {
                    Log.d(TAG, "✅ Dispositivo coincide con perfil activo: ${coolerDevice.displayName}")
                    
                    // Detener escaneo
                    isScanning = false
                    try {
                        bluetoothLeScanner?.stopScan(this)
                        cleanupLogCache() // Limpiar caché cuando se detiene el escaneo exitoso
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error deteniendo escaneo: ${e.message}")
                    }
                    
                    // Conectar al dispositivo encontrado
                    Log.d(TAG, "Conectando al cooler: ${coolerDevice.displayName}...")
                    updateNotification("Conectando a ${coolerDevice.displayName}...", 0, currentTemp)
                    
                    connectedDevice = coolerDevice
                    bluetoothGatt = device.connectGatt(
                        this@CoolerService, 
                        true,  // autoConnect=true para reconexión automática
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    // Solo loggear dispositivos que no coinciden si pasaron más de 5 minutos
                    val mismatchKey = "mismatch_$deviceAddress"
                    val lastMismatchLog = loggedDevicesCache[mismatchKey] ?: 0L
                    if ((currentTime - lastMismatchLog) > 300000L) { // 5 minutos
                        Log.d(TAG, "Dispositivo cooler alternativo encontrado: ${coolerDevice.displayName} (no es el perfil activo)")
                        loggedDevicesCache[mismatchKey] = currentTime
                    }
                }
            } else {
                // Solo loggear dispositivos no cooler si pasaron más de 2 minutos desde el último log
                val lastNonCoolerLog = loggedDevicesCache["non_cooler_$deviceAddress"] ?: 0L
                if ((currentTime - lastNonCoolerLog) > 120000L) { // 2 minutos
                    Log.v(TAG, "Dispositivo no identificado como cooler: $deviceName ($deviceAddress)")
                    loggedDevicesCache["non_cooler_$deviceAddress"] = currentTime
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Escaneo ya iniciado"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Error de registro de app"
                SCAN_FAILED_INTERNAL_ERROR -> "Error interno"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Característica no soportada"
                else -> "Error desconocido ($errorCode)"
            }
            Log.e(TAG, "Escaneo falló: $errorMsg")
            
            if (reconnectAttempts < maxReconnectAttempts) {
                reconnectAttempts++
                Log.d(TAG, "Reintentando escaneo... (intento $reconnectAttempts/$maxReconnectAttempts)")
                serviceScope.launch {
                    delay(3000L)
                    connectToCooler()
                }
            } else {
                Log.w(TAG, "Máximo número de reintentos de escaneo alcanzado")
                updateNotification("Error: No se encontró cooler", 0, currentTemp)
                cleanupLogCache() // Limpiar caché cuando se agotan los reintentos
            }
        }
    }
    
    /**
     * Lee la velocidad actual del cooler desde el dispositivo BLE
     * @return velocidad en porcentaje (0-100) o -1 si no se pudo leer
     */
    private suspend fun readCurrentFanSpeed(): Int {
        if (!isConnected || bluetoothGatt == null || fanCharacteristic == null) {
            return -1
        }
        
        return try {
            if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                return -1
            }
            
            // Intentar leer la característica del fan
            @SuppressLint("MissingPermission")
            val success = bluetoothGatt?.readCharacteristic(fanCharacteristic)
            
            if (success == true) {
                // Esperar hasta 2 segundos por la respuesta
                var attempts = 0
                while (attempts < 20 && currentSpeedFromRead == -1) {
                    delay(100L)
                    attempts++
                }
                
                val speed = currentSpeedFromRead
                currentSpeedFromRead = -1 // Reset para próxima lectura
                speed
            } else {
                -1
            }
        } catch (e: Exception) {
            logDebug("Error leyendo velocidad del cooler: ${e.message}")
            -1
        }
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
                if (isConnected && fanCharacteristic != null) {
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
                            if (Math.abs(recommendedSpeed - currentSpeed) >= 15) {
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
            
            if (isConnected && fanCharacteristic != null) {
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
    
    private fun setFanSpeed(speed: Int) {
        if (!isConnected || bluetoothGatt == null) {
            logDebug("Saltando ajuste de velocidad: no conectado")
            return
        }
        
        fanCharacteristic?.let { characteristic ->
            serviceScope.launch {
                try {
                    if (!BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                        return@launch
                    }
                    
                    val rawValue = MainActivity.mapPercentToRaw(speed.coerceIn(0, 100))
                    val value = rawValue.toByte()
                    
                    @Suppress("DEPRECATION")
                    characteristic.value = byteArrayOf(value)
                    
                    @Suppress("DEPRECATION")
                    @SuppressLint("MissingPermission")
                    val result = bluetoothGatt?.writeCharacteristic(characteristic)
                    
                    if (result == true) {
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
                            withContext(Dispatchers.Main) {
                                updateNotification(tempStatus, currentSpeed, currentTemp)
                                lastNotifiedSpeed = currentSpeed
                                lastNotifiedTemp = currentTemp
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException escribiendo: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error escribiendo: ${e.message}")
                    if (e is android.os.DeadObjectException) {
                        // Verificar límite diario de DeadObjectException
                        if (checkAndIncrementDeadObjectCount()) {
                            Log.w(TAG, "DeadObjectException: Bluetooth desconectado inesperadamente, reconectando...")
                            isConnected = false
                            bluetoothGatt?.close()
                            bluetoothGatt = null
                            fanCharacteristic = null
                            connectToCooler()
                        } else {
                            Log.e(TAG, "Límite diario de DeadObjectException alcanzado, deteniendo servicio")
                            stopSelf()
                        }
                    }
                }
            }
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
    
    private fun checkAndIncrementDeadObjectCount(): Boolean {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000) // Día actual
            val lastDay = prefs.getLong(KEY_DEAD_OBJECT_DATE, 0)
            var count = prefs.getInt(KEY_DEAD_OBJECT_COUNT, 0)
            
            // Resetear contador si es un nuevo día
            if (today != lastDay) {
                count = 0
            }
            
            // Verificar si hemos alcanzado el límite
            if (count >= MAX_DEAD_OBJECT_PER_DAY) {
                Log.w(TAG, "Límite diario de DeadObjectException alcanzado: $count/$MAX_DEAD_OBJECT_PER_DAY")
                return false
            }
            
            // Incrementar contador
            count++
            prefs.edit().apply {
                putInt(KEY_DEAD_OBJECT_COUNT, count)
                putLong(KEY_DEAD_OBJECT_DATE, today)
                apply()
            }
            
            Log.d(TAG, "DeadObjectException count: $count/$MAX_DEAD_OBJECT_PER_DAY hoy")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando límite de DeadObjectException: ${e.message}")
            return true // En caso de error, permitir reconexión
        }
    }
    
    // Funciones de logging condicional
    private fun logDebug(message: String) {
        if (DEBUG) {
            Log.d(TAG, message)
        }
    }
    
    private fun logVerbose(message: String) {
        if (DEBUG) {
            Log.v(TAG, message)
        }
    }
    
    /**
     * Establece el color y efecto de luz RGB del cooler
     * @param effect Efecto de luz a aplicar
     * @param red Componente rojo (0-255)
     * @param green Componente verde (0-255)
     * @param blue Componente azul (0-255)
     */
    fun setRGBLight(effect: LightEffect, red: Int = 0, green: Int = 0, blue: Int = 0) {
        // CRÍTICO: Verificar estado completo antes de proceder
        if (!isConnected || lightCharacteristic == null || bluetoothGatt == null) {
            Log.w(TAG, "No se puede establecer luz: isConnected=$isConnected, lightChar=${lightCharacteristic != null}, gatt=${bluetoothGatt != null}")
            return
        }
        
        serviceScope.launch {
            try {
                if (!BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                    return@launch
                }
                
                // Formato: [modo][RR][GG][BB]
                val command = byteArrayOf(
                    effect.code,
                    red.toByte(),
                    green.toByte(),
                    blue.toByte()
                )
                
                @Suppress("DEPRECATION")
                lightCharacteristic?.value = command
                
                @Suppress("DEPRECATION")
                @SuppressLint("MissingPermission")
                val result = bluetoothGatt?.writeCharacteristic(lightCharacteristic)
                
                if (result == true) {
                    val hexColor = "%02x%02x%02x%02x".format(
                        effect.code.toInt() and 0xFF,
                        red and 0xFF,
                        green and 0xFF,
                        blue and 0xFF
                    )
                    Log.d(TAG, "Comando RGB enviado: $hexColor (efecto: ${effect.name}, R:$red G:$green B:$blue)")
                } else {
                    Log.e(TAG, "Error enviando comando RGB")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException en setRGBLight: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error en setRGBLight: ${e.message}")
            }
        }
    }
    
    /**
     * Atajos para efectos comunes
     */
    fun setColorful() = setRGBLight(LightEffect.COLORFUL)
    
    fun setBreathFullColor() = setRGBLight(LightEffect.BREATH_FULLCOLOR)
    
    fun setBreathSingleColor(red: Int, green: Int, blue: Int) = 
        setRGBLight(LightEffect.BREATH_SINGLE, red, green, blue)
    
    fun setAlwaysBright(red: Int, green: Int, blue: Int) = 
        setRGBLight(LightEffect.ALWAYS_BRIGHT, red, green, blue)
    
    fun turnOffLight() = setRGBLight(LightEffect.ALWAYS_BRIGHT, 0, 0, 0)
    
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
