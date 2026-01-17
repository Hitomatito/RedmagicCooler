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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Servicio de primer plano para control automático del cooler
 * Permite monitoreo continuo sin mantener la app abierta
 */
class CoolerService : Service() {
    companion object {
        private const val TAG = "CoolerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cooler_service_channel"
        private const val COOLER_MAC_ADDRESS = "24:04:09:00:BB:8D"
        
        const val ACTION_START_AUTO = "com.hitomatito.redmagicooler.START_AUTO"
        const val ACTION_STOP_AUTO = "com.hitomatito.redmagicooler.STOP_AUTO"
        const val ACTION_RECONNECT = "com.hitomatito.redmagicooler.RECONNECT"
        
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
    }
    
    private lateinit var thermalMonitor: ThermalMonitor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var lastAutoAdjustTime = 0L
    private var currentTemp = 0f
    private var currentSpeed = 0
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var isScanning = false
    private var lastNotificationTime = 0L
    private var reconnectBackoffMs = 2000L // Backoff inicial
    
    // UUIDs descubiertos del log de la app original (cn.nubia.externdevice)
    // UUID del servicio para filtrar en advertising/escaneo
    private val coolerAdvertisingServiceUUID = UUID.fromString("00004a41-0000-1000-8000-00805f9b34fb")
    // UUID del servicio principal del fan
    private val coolerFanServiceUUID = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6")
    // Characteristics del cooler
    private val fanSpeedCharacteristicUUID = UUID.fromString("00001012-0000-1000-8000-00805f9b34fb")  // Velocidad del fan
    private val temperatureCharacteristicUUID = UUID.fromString("00001014-0000-1000-8000-00805f9b34fb")  // Temperatura
    private val notificationCharacteristicUUID = UUID.fromString("00001015-0000-1000-8000-00805f9b34fb")  // Notificaciones
    private val modeCharacteristicUUID = UUID.fromString("00001011-0000-1000-8000-00805f9b34fb")  // Modo de operación
    private val statusCharacteristicUUID = UUID.fromString("00001013-0000-1000-8000-00805f9b34fb")  // Estado
    
    override fun onCreate() {
        super.onCreate()
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
        
        connectToCooler()
        startThermalMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUTO -> {
                stopSelf()
            }
            ACTION_RECONNECT -> {
                connectToCooler()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        logDebug("Servicio destruido")
        
        // Mostrar métricas finales de batería
        logBatteryMetrics()
        
        // Guardar estado antes de destruir
        saveState()
        
        thermalMonitor.stopMonitoring()
        serviceScope.cancel()
        
        try {
            @SuppressLint("MissingPermission")
            if (BlePermissionManager.hasBluetoothConnectPermission(this)) {
                if (isScanning) {
                    bluetoothLeScanner?.stopScan(scanCallback)
                    isScanning = false
                }
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando BLE: ${e.message}")
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
    
    private fun connectToCooler() {
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
            
            // Configurar filtros para escanear dispositivos con el servicio del cooler
            // Usamos el UUID de advertising que la app original usa para filtrar
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(coolerAdvertisingServiceUUID))
                .build()
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            
            isScanning = true
            Log.d(TAG, "Iniciando escaneo BLE para cooler con UUID: $coolerAdvertisingServiceUUID")
            @SuppressLint("MissingPermission")
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            
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
                        isError -> {
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
                
                // Buscar primero en el servicio principal del fan (d52082ad-...)
                var fanService = gatt.getService(coolerFanServiceUUID)
                if (fanService != null) {
                    Log.d(TAG, "Servicio del fan encontrado: $coolerFanServiceUUID")
                    fanCharacteristic = fanService.getCharacteristic(fanSpeedCharacteristicUUID)
                } else {
                    // Fallback: buscar la característica en todos los servicios
                    Log.d(TAG, "Servicio del fan no encontrado, buscando en todos los servicios...")
                    fanCharacteristic = null
                    for (service in gatt.services ?: emptyList()) {
                        val characteristic = service.getCharacteristic(fanSpeedCharacteristicUUID)
                        if (characteristic != null) {
                            fanCharacteristic = characteristic
                            Log.d(TAG, "Fan characteristic encontrada en servicio: ${service.uuid}")
                            break
                        }
                    }
                }
                
                if (fanCharacteristic != null) {
                    Log.d(TAG, "Fan characteristic encontrada: $fanSpeedCharacteristicUUID")
                    updateNotification("Conectado", currentSpeed, currentTemp)
                    
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                            // Habilitar notificaciones en la characteristic de notificaciones
                            val notifCharacteristic = fanService?.getCharacteristic(notificationCharacteristicUUID)
                            if (notifCharacteristic != null) {
                                gatt.setCharacteristicNotification(notifCharacteristic, true)
                                Log.d(TAG, "Notificaciones habilitadas en: $notificationCharacteristicUUID")
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
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                val value = if (data != null && data.isNotEmpty()) {
                    data[0].toInt() and 0xFF
                } else 0
                currentSpeed = MainActivity.mapRawToPercent(value)
                Log.d(TAG, "Velocidad aplicada: $value (${currentSpeed}%)")
            }
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown"
            val rssi = result.rssi
            
            Log.d(TAG, "Dispositivo encontrado: $deviceName (${device.address}) RSSI: $rssi")
            Log.d(TAG, "ScanRecord: ${result.scanRecord}")
            
            // Verificar que sea un cooler RedMagic por el nombre
            if (deviceName.contains("Magcooler", ignoreCase = true) || 
                deviceName.contains("RM ", ignoreCase = true) ||
                deviceName.contains("RedMagic", ignoreCase = true)) {
                
                isScanning = false
                
                // Detener escaneo una vez encontrado el dispositivo
                try {
                    bluetoothLeScanner?.stopScan(this)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Error deteniendo escaneo: ${e.message}")
                }
                
                // Conectar al dispositivo encontrado usando autoConnect=true para reconexiones automáticas
                // Como hace la app original: "no need bond, do connect" y "autoConnect=true"
                Log.d(TAG, "Conectando al cooler: $deviceName...")
                updateNotification("Conectando a $deviceName...", 0, currentTemp)
                
                bluetoothGatt = device.connectGatt(
                    this@CoolerService, 
                    true,  // autoConnect=true para reconexión automática como la app original
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE  // Forzar transporte BLE
                )
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
            }
        }
    }
    
    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring(serviceScope) { data ->
            currentTemp = data.maxTemp
            
            if (isConnected && fanCharacteristic != null) {
                val currentTime = System.currentTimeMillis()
                
                // Ajustar cada 15 segundos (más estable)
                if (currentTime - lastAutoAdjustTime > 15000) {
                    setFanSpeed(data.recommendedSpeed)
                    lastAutoAdjustTime = currentTime
                }
                
                val tempStatus = when (data.tempLevel) {
                    ThermalMonitor.TempLevel.SAFE -> "Normal"
                    ThermalMonitor.TempLevel.WARM -> "Tibio"
                    ThermalMonitor.TempLevel.HOT -> "Caliente"
                    ThermalMonitor.TempLevel.CRITICAL -> "⚠️ Crítico"
                }
                
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
                        currentSpeed = speed
                        logDebug("Ajustando velocidad automáticamente: $speed% (raw: $rawValue)")
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
                apply()
            }
            Log.d(TAG, "Estado guardado: temp=$currentTemp, speed=$currentSpeed")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando estado: ${e.message}")
        }
    }
    
    private fun loadState() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            currentTemp = prefs.getFloat(KEY_LAST_TEMP, 0f)
            currentSpeed = prefs.getInt(KEY_LAST_SPEED, 0)
            Log.d(TAG, "Estado restaurado: temp=$currentTemp, speed=$currentSpeed")
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
