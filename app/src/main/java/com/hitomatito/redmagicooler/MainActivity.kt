package com.hitomatito.redmagicooler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.ui.theme.RedmagiCoolerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

@Suppress("OVERRIDE_DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    // UUIDs descubiertos del log de la app original (cn.nubia.externdevice)
    private val coolerAdvertisingServiceUUID = UUID.fromString("00004a41-0000-1000-8000-00805f9b34fb")
    private val coolerFanServiceUUID = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6")
    private val fanSpeedCharacteristicUUID = UUID.fromString("00001012-0000-1000-8000-00805f9b34fb")
    private val notificationCharacteristicUUID = UUID.fromString("00001015-0000-1000-8000-00805f9b34fb")
    
    private var fanCharacteristic: BluetoothGattCharacteristic? = null

    // Estados observables para la UI
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var currentFanSpeed by mutableIntStateOf(0)
    private var currentTemp by mutableIntStateOf(0)
    private var statusMessage by mutableStateOf("Listo para conectar")
    private var useRawMode by mutableStateOf(false)
    
    // Monitor térmico y modo automático
    private lateinit var thermalMonitor: ThermalMonitor
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isAutoMode by mutableStateOf(false)
    private var thermalData by mutableStateOf(ThermalMonitor.ThermalData())
    private var lastAutoAdjustTime = 0L

    /**
     * Verifica si un servicio está ejecutándose
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "RedMagicCooler"
        private const val COOLER_MAC_ADDRESS = "24:04:09:00:BB:8D"
        
        // Rango real del hardware del cooler (descubierto del logcat)
        private const val COOLER_MIN_SPEED = 40
        private const val COOLER_MAX_SPEED = 80
        
        /**
         * Mapea velocidad de porcentaje (0-100) a rango del cooler (40-80)
         */
        fun mapPercentToRaw(percent: Int): Int {
            return COOLER_MIN_SPEED + (percent * (COOLER_MAX_SPEED - COOLER_MIN_SPEED) / 100)
        }
        
        /**
         * Mapea valor raw del cooler (40-80) a porcentaje (0-100)
         */
        fun mapRawToPercent(raw: Int): Int {
            return ((raw - COOLER_MIN_SPEED) * 100) / (COOLER_MAX_SPEED - COOLER_MIN_SPEED)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "Todos los permisos concedidos")
            statusMessage = "Permisos concedidos, conectando..."
            connectToCooler()
        } else {
            Log.e(TAG, "Permisos denegados: $permissions")
            statusMessage = "Permisos necesarios denegados"
            Toast.makeText(this, "Se requieren permisos BLE y ubicación", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inicializar Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager no disponible")
            Toast.makeText(this, "Bluetooth no disponible en este dispositivo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        bluetoothAdapter = bluetoothManager.adapter
        if (!bluetoothAdapter.isEnabled) {
            statusMessage = "Por favor, activa Bluetooth"
            Toast.makeText(this, "Por favor, activa Bluetooth", Toast.LENGTH_LONG).show()
        }
        
        // Inicializar monitor térmico
        thermalMonitor = ThermalMonitor(this)
        thermalMonitor.startAmbientSensor()
        startThermalMonitoring() // Iniciar monitoreo térmico para actualizar UI

        setContent {
            RedmagiCoolerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CoolerControlScreen(
                        isConnected = isConnected,
                        isConnecting = isConnecting,
                        currentFanSpeed = currentFanSpeed,
                        currentTemp = currentTemp,
                        statusMessage = statusMessage,
                        useRawMode = useRawMode,
                        isAutoMode = isAutoMode,
                        thermalData = thermalData,
                        onConnect = { checkPermissionsAndConnect() },
                        onDisconnect = { disconnectFromCooler() },
                        onSetFanSpeed = { speed -> setFanSpeed(speed) },
                        onReadStatus = { readStatus() },
                        onToggleRawMode = { useRawMode = !useRawMode },
                        onToggleAutoMode = { toggleAutoMode() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        if (!bluetoothAdapter.isEnabled) {
            statusMessage = "Bluetooth desactivado"
            Toast.makeText(this, "Por favor, activa Bluetooth primero", Toast.LENGTH_SHORT).show()
            return
        }

        if (BlePermissionManager.hasAllPermissions(this)) {
            Log.d(TAG, "Todos los permisos ya concedidos")
            connectToCooler()
        } else {
            val missing = BlePermissionManager.getMissingPermissions(this)
            Log.d(TAG, "Solicitando permisos: $missing")
            statusMessage = BlePermissionManager.getMissingPermissionsMessage(this)
            requestPermissionsLauncher.launch(BlePermissionManager.getRequiredPermissions())
        }
    }

    private fun connectToCooler() {
        if (isConnecting || isConnected) {
            Log.w(TAG, "Ya conectado o conectando")
            return
        }

        // Si el servicio automático está corriendo, no conectar desde la UI
        if (isServiceRunning(CoolerService::class.java)) {
            Log.w(TAG, "Servicio automático corriendo, no conectar desde UI")
            Toast.makeText(this, "Modo automático activo, usa el servicio en background", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Verificar permisos antes de cualquier operación BLE
            if (!BlePermissionManager.hasBluetoothConnectPermission(this) || 
                !BlePermissionManager.hasBluetoothScanPermission(this)) {
                statusMessage = "Permisos BLE requeridos"
                isConnecting = false
                Toast.makeText(this, "Permisos BLE requeridos", Toast.LENGTH_SHORT).show()
                return
            }
            
            isConnecting = true
            statusMessage = "Buscando cooler..."
            
            // Cerrar conexión anterior si existe
            try {
                if (isScanning) {
                    @SuppressLint("MissingPermission")
                    bluetoothLeScanner?.stopScan(bleScanCallback)
                    isScanning = false
                }
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException al cerrar: ${e.message}")
            }
            
            // Iniciar escaneo BLE para encontrar el cooler
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "BluetoothLeScanner no disponible")
                statusMessage = "Escáner BLE no disponible"
                isConnecting = false
                return
            }
            
            // Configurar filtro por Service UUID del cooler (como hace la app original)
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(coolerAdvertisingServiceUUID))
                .build()
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            
            Log.d(TAG, "Iniciando escaneo BLE con UUID: $coolerAdvertisingServiceUUID")
            isScanning = true
            
            @SuppressLint("MissingPermission")
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar: ${e.message}", e)
            statusMessage = "Error: ${e.message}"
            isConnecting = false
            isScanning = false
            Toast.makeText(this, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown"
            
            Log.d(TAG, "Dispositivo encontrado: $deviceName (${device.address}) RSSI: ${result.rssi}")
            
            // Verificar que sea un cooler RedMagic
            if (deviceName.contains("Magcooler", ignoreCase = true) || 
                deviceName.contains("RM ", ignoreCase = true) ||
                deviceName.contains("RedMagic", ignoreCase = true)) {
                
                isScanning = false
                bluetoothLeScanner?.stopScan(this)
                
                runOnUiThread {
                    statusMessage = "Conectando a $deviceName..."
                }
                
                Log.d(TAG, "Cooler encontrado, conectando...")
                
                // Conectar usando autoConnect=true como la app original
                bluetoothGatt = device.connectGatt(
                    this@MainActivity,
                    true,  // autoConnect=true para reconexión automática
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Escaneo ya iniciado"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Error de registro"
                SCAN_FAILED_INTERNAL_ERROR -> "Error interno"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "No soportado"
                else -> "Error ($errorCode)"
            }
            Log.e(TAG, "Escaneo falló: $errorMsg")
            runOnUiThread {
                isConnecting = false
                statusMessage = "Error: $errorMsg"
                Toast.makeText(this@MainActivity, "Error de escaneo: $errorMsg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun disconnectFromCooler() {
        try {
            if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                return
            }
            // Detener escaneo si está activo
            if (isScanning) {
                @SuppressLint("MissingPermission")
                bluetoothLeScanner?.stopScan(bleScanCallback)
                isScanning = false
            }
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            fanCharacteristic = null
            isConnecting = false
            statusMessage = "Desconectado"
            Log.d(TAG, "Desconectado del cooler")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al desconectar: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar: ${e.message}", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                isConnecting = false
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        isConnected = true
                        statusMessage = "Conectado, descubriendo servicios..."
                        Log.d(TAG, "Conectado al cooler, status: $status")
                        
                        if (!BlePermissionManager.hasBluetoothConnectPermission(this@MainActivity)) {
                            return@runOnUiThread
                        }
                        try {
                            @SuppressLint("MissingPermission")
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException al descubrir servicios: ${e.message}")
                            statusMessage = "Error de permisos al descubrir servicios"
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        isConnected = false
                        statusMessage = "Desconectado"
                        Log.d(TAG, "Desconectado del cooler, status: $status")
                        fanCharacteristic = null
                        
                        // Detener monitoreo térmico si no hay servicio automático corriendo
                        if (!isServiceRunning(CoolerService::class.java)) {
                            thermalMonitor.stopMonitoring()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Servicios descubiertos exitosamente")
                    
                    // Listar todos los servicios para debug
                    for (service in gatt.services ?: emptyList()) {
                        Log.d(TAG, "Servicio: ${service.uuid}")
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
                        // Fallback: buscar en todos los servicios
                        Log.d(TAG, "Servicio del fan no encontrado, buscando en todos...")
                        fanCharacteristic = null
                        for (service in gatt.services ?: emptyList()) {
                            val characteristic = service.getCharacteristic(fanSpeedCharacteristicUUID)
                            if (characteristic != null) {
                                fanCharacteristic = characteristic
                                fanService = service
                                Log.d(TAG, "Fan characteristic encontrada en servicio: ${service.uuid}")
                                break
                            }
                        }
                    }
                    
                    if (fanCharacteristic != null) {
                        statusMessage = "Listo para controlar"
                        Log.d(TAG, "Fan characteristic encontrada: $fanSpeedCharacteristicUUID")
                        Toast.makeText(this@MainActivity, "Conectado exitosamente", Toast.LENGTH_SHORT).show()
                        
                        // Habilitar notificaciones
                        try {
                            if (!BlePermissionManager.hasBluetoothConnectPermission(this@MainActivity)) {
                                return@runOnUiThread
                            }
                            // Habilitar notificaciones en characteristic de notificaciones
                            val notifChar = fanService?.getCharacteristic(notificationCharacteristicUUID)
                            if (notifChar != null) {
                                @SuppressLint("MissingPermission")
                                gatt.setCharacteristicNotification(notifChar, true)
                                Log.d(TAG, "Notificaciones habilitadas en: $notificationCharacteristicUUID")
                            }
                            @SuppressLint("MissingPermission")
                            gatt.setCharacteristicNotification(fanCharacteristic, true)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "SecurityException habilitando notificaciones: ${e.message}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error habilitando notificaciones: ${e.message}")
                        }
                    } else {
                        statusMessage = "Error: Characteristic no encontrada"
                        Log.e(TAG, "Fan characteristic NO encontrada")
                        Log.d(TAG, "Servicios disponibles: ${gatt.services?.map { it.uuid } ?: emptyList()}")
                    }
                } else {
                    statusMessage = "Error descubriendo servicios"
                    Log.e(TAG, "Error en descubrimiento de servicios: $status")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value
                    val value = if (data != null && data.isNotEmpty()) {
                        data[0].toInt() and 0xFF
                    } else 0
                    Log.d(TAG, "Velocidad escrita exitosamente: $value")
                    statusMessage = "Velocidad aplicada: $value"
                    // Toast.makeText(this@MainActivity, "Velocidad aplicada: $value", Toast.LENGTH_SHORT).show() // Removido: no mostrar en modo automático
                } else {
                    Log.e(TAG, "Error escribiendo characteristic: $status")
                    statusMessage = "Error aplicando velocidad"
                    // Toast.makeText(this@MainActivity, "Error aplicando velocidad", Toast.LENGTH_SHORT).show() // Removido: menos intrusivo
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        currentFanSpeed = data[0].toInt() and 0xFF
                        Log.d(TAG, "Velocidad leída: $currentFanSpeed")
                        statusMessage = "Velocidad actual: $currentFanSpeed"
                    }
                } else {
                    Log.e(TAG, "Error leyendo characteristic: $status")
                    statusMessage = "Error leyendo estado"
                }
            }
        }

        @Deprecated("Deprecated in API 33, use onCharacteristicChanged with ByteArray parameter")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Notificaciones en tiempo real del cooler (API < 33)
            onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Notificaciones en tiempo real del cooler (API 33+)
            // La nueva API ya no usa characteristic.value, sino que pasa el ByteArray directamente
            super.onCharacteristicChanged(gatt, characteristic, value)
            onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun setFanSpeed(speed: Int) {
        if (!isConnected) {
            // Toast.makeText(this, "No conectado al cooler", Toast.LENGTH_SHORT).show() // Removido
            return
        }

        fanCharacteristic?.let { characteristic ->
            try {
                if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                    // Toast.makeText(this, "Permiso BLUETOOTH_CONNECT requerido", Toast.LENGTH_SHORT).show() // Removido
                    return
                }
                
                // En modo raw: usar valor directo; en modo normal: mapear 0-100% a 40-80
                val rawValue = if (useRawMode) {
                    speed.coerceIn(0, 255)
                } else {
                    mapPercentToRaw(speed.coerceIn(0, 100))
                }
                
                val value = rawValue.toByte()
                Log.d(TAG, "Estableciendo velocidad del fan: $rawValue (0x${String.format("%02X", value)}) [Modo: ${if (useRawMode) "Raw" else "Normal"}]")
                
                @Suppress("DEPRECATION")
                characteristic.value = byteArrayOf(value)
                @Suppress("DEPRECATION")
                val result = try {
                    @SuppressLint("MissingPermission")
                    bluetoothGatt?.writeCharacteristic(characteristic)
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException al escribir: ${e.message}")
                    statusMessage = "Error de permisos al escribir"
                    // Toast.makeText(this, "Error de permisos", Toast.LENGTH_SHORT).show() // Removido
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Error al escribir characteristic: ${e.message}", e)
                    if (e is android.os.DeadObjectException) {
                        // Bluetooth se reinició, marcar como desconectado
                        Log.w(TAG, "DeadObjectException: Bluetooth desconectado inesperadamente")
                        isConnected = false
                        fanCharacteristic = null
                        statusMessage = "Desconectado - Bluetooth reiniciado"
                        thermalMonitor.stopMonitoring() // Detener monitoreo térmico
                    }
                    false
                }
                
                if (result == true) {
                    Log.d(TAG, "Comando de escritura enviado")
                    statusMessage = "Aplicando velocidad..."
                } else {
                    Log.e(TAG, "Fallo al enviar comando de escritura")
                    statusMessage = "Error al enviar comando"
                    // Toast.makeText(this, "Error al enviar comando", Toast.LENGTH_SHORT).show() // Removido
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException estableciendo velocidad: ${e.message}", e)
                statusMessage = "Error de permisos"
                // Toast.makeText(this, "Error de permisos", Toast.LENGTH_SHORT).show() // Removido
            } catch (e: Exception) {
                Log.e(TAG, "Error estableciendo velocidad: ${e.message}", e)
                statusMessage = "Error: ${e.message}"
                // Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() // Removido
            }
        } ?: run {
            Log.w(TAG, "Fan characteristic no disponible")
            // Toast.makeText(this, "Servicio no disponible", Toast.LENGTH_SHORT).show() // Removido
        }
    }

    private fun readStatus() {
        if (!isConnected) {
            // Toast.makeText(this, "No conectado al cooler", Toast.LENGTH_SHORT).show() // Removido
            return
        }

        fanCharacteristic?.let { characteristic ->
            try {
                if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                    // Toast.makeText(this, "Permiso BLUETOOTH_CONNECT requerido", Toast.LENGTH_SHORT).show() // Removido
                    return
                }
                
                Log.d(TAG, "Leyendo estado del cooler...")
                val result = try {
                    @SuppressLint("MissingPermission")
                    bluetoothGatt?.readCharacteristic(characteristic)
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException al leer: ${e.message}")
                    statusMessage = "Error de permisos al leer"
                    // Toast.makeText(this, "Error de permisos", Toast.LENGTH_SHORT).show() // Removido
                    false
                }
                
                if (result == true) {
                    statusMessage = "Leyendo estado..."
                } else {
                    statusMessage = "Error leyendo estado"
                    Toast.makeText(this, "Error al leer estado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException leyendo estado: ${e.message}", e)
                Toast.makeText(this, "Error de permisos", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo estado: ${e.message}", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "Servicio no disponible", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // Detener monitoreo térmico
            thermalMonitor.stopMonitoring()
            
            if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                try {
                    bluetoothGatt?.close()
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException al cerrar GATT: ${e.message}")
                }
                return
            }
            try {
                bluetoothGatt?.disconnect()
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException al desconectar: ${e.message}")
            }
            try {
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException al cerrar: ${e.message}")
            }
            bluetoothGatt = null
            Log.d(TAG, "Recursos BLE liberados")
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando recursos: ${e.message}")
        }
    }
    
    /**
     * Inicia el monitoreo térmico del dispositivo
     */
    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring(monitoringScope) { data ->
            thermalData = data
            currentTemp = data.maxTemp.toInt()
            
            // Si modo automático está activo, conectado, y no hay servicio corriendo, ajustar velocidad
            if (isAutoMode && isConnected && !isServiceRunning(CoolerService::class.java)) {
                val currentTime = System.currentTimeMillis()
                // Ajustar cada 5 segundos para evitar cambios muy frecuentes
                if (currentTime - lastAutoAdjustTime > 5000) {
                    setFanSpeed(data.recommendedSpeed)
                    lastAutoAdjustTime = currentTime
                }
            }
        }
    }
    
    /**
     * Activa/desactiva el modo automático de control de temperatura
     */
    private fun toggleAutoMode() {
        isAutoMode = !isAutoMode
        
        if (isAutoMode) {
            // Verificar permiso de notificaciones
            if (!BlePermissionManager.hasNotificationPermission(this)) {
                Toast.makeText(this, "Se requiere permiso de notificaciones para el modo automático", Toast.LENGTH_SHORT).show()
                isAutoMode = false
                return
            }
            
            // Iniciar servicio en segundo plano para modo automático
            val serviceIntent = Intent(this, CoolerService::class.java).apply {
                action = CoolerService.ACTION_START_AUTO
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            statusMessage = "Modo Automático: Servicio en segundo plano activo"
            Log.d(TAG, "Modo automático activado - Servicio iniciado")
            Toast.makeText(this, "Modo Automático Activado\nControl en background con notificaciones", Toast.LENGTH_LONG).show()
            
            // Desconectar GATT de la activity (el servicio manejará la conexión y notificaciones)
            if (isConnected) {
                try {
                    if (BlePermissionManager.hasBluetoothConnectPermission(this)) {
                        bluetoothGatt?.disconnect()
                        bluetoothGatt?.close()
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Error desconectando: ${e.message}")
                }
                bluetoothGatt = null
                isConnected = false
            }
            statusMessage = "Modo Automático Activo"
            
        } else {
            // Desactivar modo automático
            if (isServiceRunning(CoolerService::class.java)) {
                // Detener servicio
                val stopIntent = Intent(this, CoolerService::class.java).apply {
                    action = CoolerService.ACTION_STOP_AUTO
                }
                stopService(stopIntent)
                Log.d(TAG, "Servicio automático detenido")
            }
            
            statusMessage = "Modo Automático: Desactivado"
            Log.d(TAG, "Modo automático desactivado")
            Toast.makeText(this, "Modo Automático Desactivado", Toast.LENGTH_SHORT).show()
            
            // Intentar reconectar automáticamente para control manual
            if (BlePermissionManager.hasAllPermissions(this) && bluetoothAdapter.isEnabled && !isConnected) {
                Log.d(TAG, "Intentando reconectar para control manual después de desactivar modo automático")
                connectToCooler()
            }
        }
    }
}

@Composable
fun CoolerControlScreen(
    isConnected: Boolean,
    isConnecting: Boolean,
    currentFanSpeed: Int,
    currentTemp: Int,
    statusMessage: String,
    useRawMode: Boolean,
    isAutoMode: Boolean,
    thermalData: ThermalMonitor.ThermalData,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetFanSpeed: (Int) -> Unit,
    onReadStatus: () -> Unit,
    onToggleRawMode: () -> Unit,
    onToggleAutoMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fanSpeed by remember { mutableIntStateOf(50) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "RedMagic Cooler Control",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // Tarjeta de estado
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isAutoMode -> MaterialTheme.colorScheme.secondaryContainer
                    isConnected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Estado: ${when {
                        isAutoMode -> "🔄 Modo Automático Activo"
                        isConnecting -> "Conectando..."
                        isConnected -> "✓ Conectado"
                        else -> "✗ Desconectado"
                    }}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Botones de conexión
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onConnect,
                enabled = !isConnecting && !isConnected,
                modifier = Modifier.weight(1f)
            ) {
                Text("Conectar")
            }
            Button(
                onClick = onDisconnect,
                enabled = isConnected,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Desconectar")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Monitor de temperatura del teléfono
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (thermalData.tempLevel) {
                    ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.surfaceVariant
                    ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.tertiaryContainer
                    ThermalMonitor.TempLevel.HOT -> MaterialTheme.colorScheme.errorContainer
                    ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.error
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🌡️ Monitor Térmico",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = onToggleAutoMode,
                        enabled = !isConnecting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAutoMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(if (isAutoMode) "Modo Auto ON" else "Modo Auto OFF")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                // Temperatura del teléfono
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Temperatura:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${"%.1f".format(thermalData.maxTemp)}°C",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = when (thermalData.tempLevel) {
                                ThermalMonitor.TempLevel.SAFE -> "Normal"
                                ThermalMonitor.TempLevel.WARM -> "Calentamiento"
                                ThermalMonitor.TempLevel.HOT -> "Alta"
                                ThermalMonitor.TempLevel.CRITICAL -> "⚠️ Crítica"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (thermalData.tempLevel) {
                                ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.onSurfaceVariant
                                ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.onTertiaryContainer
                                ThermalMonitor.TempLevel.HOT -> MaterialTheme.colorScheme.onErrorContainer
                                ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.onError
                            }
                        )
                        // Mostrar desglose de temperaturas
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Batería: ${"%.1f".format(thermalData.batteryTemp)}°C" +
                                   if (thermalData.cpuTemp > 0) " | CPU: ${"%.1f".format(thermalData.cpuTemp)}°C" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Velocidad Sugerida:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "${thermalData.recommendedSpeed}%",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
                
                if (isAutoMode) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ Ajuste automático activo - El cooler se adapta a la temperatura",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Información del cooler
        if (isConnected) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Información del Cooler",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Velocidad Actual:")
                        Text("$currentFanSpeed%", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Temperatura:")
                        Text("$currentTemp°C", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onReadStatus,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Actualizar Estado")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Control de velocidad
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isAutoMode) "Control Manual (Deshabilitado)" else "Control de Velocidad",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(
                            onClick = onToggleRawMode,
                            enabled = !isAutoMode,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (useRawMode) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(if (useRawMode) "Modo Raw" else "Modo Normal")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (useRawMode) 
                            "Rango: 0-255 (Valor directo al hardware)"
                        else 
                            "Rango: 0-100% → Hardware: 40-80",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (useRawMode) {
                            "Valor Raw: $fanSpeed"
                        } else {
                            "Velocidad: $fanSpeed% (Raw: ${MainActivity.mapPercentToRaw(fanSpeed)})"
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Slider(
                        value = fanSpeed.toFloat(),
                        onValueChange = { fanSpeed = it.toInt() },
                        valueRange = if (useRawMode) 0f..255f else 0f..100f,
                        steps = if (useRawMode) 254 else 99,
                        enabled = !isAutoMode
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Botones rápidos de velocidad
                    Text("Velocidades Rápidas:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { fanSpeed = if (useRawMode) 40 else 0; onSetFanSpeed(fanSpeed) },
                            modifier = Modifier.weight(1f),
                            enabled = !isAutoMode,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(if (useRawMode) "40" else "0%")
                        }
                        Button(
                            onClick = { fanSpeed = if (useRawMode) 60 else 50; onSetFanSpeed(fanSpeed) },
                            modifier = Modifier.weight(1f),
                            enabled = !isAutoMode
                        ) {
                            Text(if (useRawMode) "60" else "50%")
                        }
                        Button(
                            onClick = { fanSpeed = if (useRawMode) 80 else 100; onSetFanSpeed(fanSpeed) },
                            modifier = Modifier.weight(1f),
                            enabled = !isAutoMode,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (useRawMode) "80" else "100%")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onSetFanSpeed(fanSpeed) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isAutoMode
                    ) {
                        Text("Aplicar Velocidad")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CoolerControlScreenPreview() {
    RedmagiCoolerTheme {
        CoolerControlScreen(
            isConnected = true,
            isConnecting = false,
            currentFanSpeed = 42,
            currentTemp = 32,
            statusMessage = "Listo para controlar",
            useRawMode = false,
            isAutoMode = false,
            thermalData = ThermalMonitor.ThermalData(
                batteryTemp = 38.5f,
                maxTemp = 38.5f,
                recommendedSpeed = 50,
                tempLevel = ThermalMonitor.TempLevel.WARM
            ),
            onConnect = {},
            onDisconnect = {},
            onSetFanSpeed = {},
            onReadStatus = {},
            onToggleRawMode = {},
            onToggleAutoMode = {}
        )
    }
}