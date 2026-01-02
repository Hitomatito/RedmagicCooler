package com.hitomatito.redmagicooler

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    }
    
    private lateinit var thermalMonitor: ThermalMonitor
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bluetoothGatt: BluetoothGatt? = null
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var lastAutoAdjustTime = 0L
    private var currentTemp = 0f
    private var currentSpeed = 0
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    
    private val fanCharacteristicUUID = UUID.fromString("00001012-0000-1000-8000-00805f9b34fb")
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio creado")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Iniciando...", 0, 0f))
        
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startAmbientSensor()
        
        connectToCooler()
        startThermalMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AUTO -> {
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Servicio destruido")
        
        thermalMonitor.stopMonitoring()
        serviceScope.cancel()
        
        try {
            @SuppressLint("MissingPermission")
            if (BlePermissionManager.hasBluetoothConnectPermission(this)) {
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
            
            val notificationManager = getSystemService(NotificationManager::class.java)
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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun connectToCooler() {
        if (!BlePermissionManager.hasAllPermissions(this)) {
            Log.e(TAG, "Permisos BLE faltantes")
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
            
            val device = bluetoothAdapter.getRemoteDevice(COOLER_MAC_ADDRESS)
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
            
            Log.d(TAG, "Conectando al cooler...")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando: ${e.message}")
            stopSelf()
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0 // Resetear contador de reintentos
                    Log.d(TAG, "Conectado al cooler")
                    
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
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
                    
                    // Intentar reconectar automáticamente si no hemos excedido el límite
                    if (reconnectAttempts < maxReconnectAttempts) {
                        reconnectAttempts++
                        Log.d(TAG, "Intentando reconectar... (intento $reconnectAttempts/$maxReconnectAttempts)")
                        serviceScope.launch {
                            delay(5000L) // Esperar 5 segundos antes de reconectar
                            connectToCooler()
                        }
                    } else {
                        Log.w(TAG, "Máximo número de reintentos alcanzado, deteniendo servicio")
                        stopSelf()
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Buscar la característica del fan en todos los servicios disponibles
                fanCharacteristic = null
                for (service in gatt.services ?: emptyList()) {
                    val characteristic = service.getCharacteristic(fanCharacteristicUUID)
                    if (characteristic != null) {
                        fanCharacteristic = characteristic
                        Log.d(TAG, "Fan characteristic encontrada en servicio: ${service.uuid}")
                        break
                    }
                }
                
                if (fanCharacteristic != null) {
                    Log.d(TAG, "Servicio del cooler encontrado")
                    updateNotification("Conectado", currentSpeed, currentTemp)
                    
                    try {
                        @SuppressLint("MissingPermission")
                        if (BlePermissionManager.hasBluetoothConnectPermission(this@CoolerService)) {
                            gatt.setCharacteristicNotification(fanCharacteristic, true)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error habilitando notificaciones: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "Fan characteristic no encontrada en ningún servicio")
                    Log.d(TAG, "Servicios disponibles: ${gatt.services?.map { it.uuid } ?: emptyList()}")
                }
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
                
                updateNotification(tempStatus, currentSpeed, currentTemp)
            } else {
                updateNotification("Esperando conexión", 0, currentTemp)
            }
        }
    }
    
    private fun setFanSpeed(speed: Int) {
        fanCharacteristic?.let { characteristic ->
            try {
                if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                    return
                }
                
                val rawValue = MainActivity.mapPercentToRaw(speed.coerceIn(0, 100))
                val value = rawValue.toByte()
                
                @Suppress("DEPRECATION")
                characteristic.value = byteArrayOf(value)
                
                @Suppress("DEPRECATION")
                @SuppressLint("MissingPermission")
                val result = bluetoothGatt?.writeCharacteristic(characteristic)
                
                if (result == true) {
                    Log.d(TAG, "Ajustando velocidad automáticamente: $speed% (raw: $rawValue)")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException escribiendo: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error escribiendo: ${e.message}")
                if (e is android.os.DeadObjectException) {
                    // Bluetooth se reinició, forzar reconexión
                    Log.w(TAG, "DeadObjectException: Bluetooth desconectado inesperadamente, reconectando...")
                    isConnected = false
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    fanCharacteristic = null
                    connectToCooler()
                }
            }
        }
    }
}
