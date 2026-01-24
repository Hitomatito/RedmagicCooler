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
    private var reconnectBackoffMs = 2000L // Backoff inicial
    
    // Perfil específico para el modo automático
    private var activeProfile: CoolerProfile? = null
    private val discoveredDevices = CoolerDeviceList()
    private var connectedDevice: CoolerDevice? = null
    
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
                            .edit()
                            .putString("ACTIVE_PROFILE_ID", activeProfile?.id)
                            .putString("ACTIVE_PROFILE_TYPE", activeProfile?.deviceType?.name)
                            .putString("ACTIVE_PROFILE_MAC", activeProfile?.macAddress)
                            .putString("ACTIVE_PROFILE_NAME", activeProfile?.name)
                            .apply()

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
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear().apply()
                
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoreo térmico y control automático del RedMagic Cooler"
                setShowBadge(false)
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CPU: ${"%.1f".format(temp)}°C")
            .setContentText("$speed%")
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(status: String, speed: Int, temp: Float) {
        val notification = createNotification(status, speed, temp)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun updateNotificationIfNeeded(status: String, speed: Int, temp: Float) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationTime > 30000L) { // 30 segundos
            updateNotification(status, speed, temp)
            lastNotificationTime = currentTime
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
                    
                    updateNotification("Conectado", currentSpeed, currentTemp)
                    
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
