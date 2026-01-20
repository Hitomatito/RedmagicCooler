package com.hitomatito.redmagicooler

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hitomatito.redmagicooler.data.ProfileRepository
import com.hitomatito.redmagicooler.model.CoolerBleConstants
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig
import com.hitomatito.redmagicooler.ui.AddDeviceScreen
import com.hitomatito.redmagicooler.ui.BluetoothRequiredScreen
import com.hitomatito.redmagicooler.ui.HomeScreen
import com.hitomatito.redmagicooler.ui.ProfileConfigScreen
import com.hitomatito.redmagicooler.ui.RGBControlScreen
import com.hitomatito.redmagicooler.ui.theme.RedmagiCoolerTheme
import com.hitomatito.redmagicooler.utils.BleConnectionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("OVERRIDE_DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    private var lightCharacteristic: BluetoothGattCharacteristic? = null

    // Estados observables para la UI
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Listo")
    private var currentCoolerSpeed by mutableIntStateOf(50)
    
    // Perfil actualmente conectado
    private var currentConnectedProfileId by mutableStateOf<String?>(null)
    private var pendingConnectionProfileId by mutableStateOf<String?>(null)
    
    // Para crear nuevo perfil después de conexión exitosa
    private var pendingNewDeviceType by mutableStateOf<CoolerDeviceType?>(null)
    private var pendingDeviceMac by mutableStateOf<String?>(null)
    private var pendingDeviceName by mutableStateOf<String?>(null)
    private var pendingDeviceRssi by mutableStateOf(0)
    
    // Perfil recién creado (para navegación automática)
    private var newlyCreatedProfileId by mutableStateOf<String?>(null)
    
    // Monitor térmico y modo automático
    private lateinit var thermalMonitor: ThermalMonitor
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isAutoMode by mutableStateOf(false)
    private var thermalData by mutableStateOf(ThermalMonitor.ThermalData())
    private var lastAutoAdjustTime = 0L
    
    // Control de Bluetooth
    private var showBluetoothRequired by mutableStateOf(false)
    
    // Repositorio de perfiles
    private lateinit var profileRepository: ProfileRepository
    
    companion object {
        private const val TAG = "RedMagicCooler"
        
        // Rango real del hardware del cooler
        private const val COOLER_MIN_SPEED = 40
        private const val COOLER_MAX_SPEED = 80
        
        fun mapPercentToRaw(percent: Int): Int {
            return COOLER_MIN_SPEED + (percent * (COOLER_MAX_SPEED - COOLER_MIN_SPEED) / 100)
        }
        
        fun mapRawToPercent(raw: Int): Int {
            return ((raw - COOLER_MIN_SPEED) * 100) / (COOLER_MAX_SPEED - COOLER_MIN_SPEED)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        
        if (denied.isEmpty()) {
            Log.d(TAG, "Todos los permisos concedidos")
            statusMessage = "Listo para buscar dispositivos"
        } else {
            Log.e(TAG, "Permisos DENEGADOS: $denied")
            statusMessage = "Permisos denegados"
            Toast.makeText(this, "Se requieren todos los permisos para funcionar", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Bluetooth activado exitosamente")
            showBluetoothRequired = false
            Toast.makeText(this, "Bluetooth activado", Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "Usuario canceló activación de Bluetooth")
            showBluetoothRequired = true
            Toast.makeText(this, "Bluetooth es requerido para usar la app", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inicializar repositorio de perfiles
        profileRepository = ProfileRepository.getInstance(this)

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
            showBluetoothRequired = true
        } else {
            showBluetoothRequired = false
        }
        
        // Solicitar permisos al inicio
        if (!BlePermissionManager.hasAllPermissions(this)) {
            val missing = BlePermissionManager.getMissingPermissions(this)
            val requestable = missing.filter { it != android.Manifest.permission.SCHEDULE_EXACT_ALARM }
            if (requestable.isNotEmpty()) {
                requestPermissionsLauncher.launch(requestable.toTypedArray())
            }
        }
        
        // Inicializar monitor térmico
        thermalMonitor = ThermalMonitor(this)

        setContent {
            RedmagiCoolerTheme {
                val navController = rememberNavController()
                val profiles by profileRepository.profiles.collectAsState()
                
                NavHost(
                    navController = navController,
                    startDestination = if (showBluetoothRequired) "bluetooth" else "home"
                ) {
                    // Pantalla de activar Bluetooth
                    composable("bluetooth") {
                        BluetoothRequiredScreen(
                            onEnableBluetooth = { enableBluetooth() },
                            onRetryCheck = { checkBluetoothState() }
                        )
                    }
                    
                    // Pantalla principal - Lista de perfiles
                    composable("home") {
                        HomeScreen(
                            profiles = profiles,
                            onProfileClick = { profile ->
                                navController.navigate("profile/${profile.id}")
                            },
                            onAddDevice = {
                                navController.navigate("addDevice")
                            }
                        )
                    }
                    
                    // Pantalla para agregar nuevo dispositivo
                    composable("addDevice") {
                        AddDeviceScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onDeviceTypeSelected = { deviceType ->
                                // Iniciar búsqueda para este tipo
                                Log.d(TAG, "🎯 Usuario seleccionó dispositivo: ${deviceType.deviceName}")
                                pendingNewDeviceType = deviceType
                                startScanForNewDevice(deviceType)
                            },
                            isScanning = isScanning,
                            isConnecting = isConnecting,
                            statusMessage = statusMessage,
                            onCancelScan = { cancelScan() },
                            onProfileCreated = { profileId ->
                                // Limpiar el estado antes de navegar
                                newlyCreatedProfileId = null
                                navController.popBackStack()
                                navController.navigate("profile/$profileId")
                            },
                            newlyCreatedProfileId = newlyCreatedProfileId
                        )
                    }
                    
                    // Pantalla de configuración de perfil
                    composable(
                        route = "profile/{profileId}",
                        arguments = listOf(navArgument("profileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
                        
                        // Obtener el perfil actualizado desde la lista reactiva
                        val currentProfile = profiles.find { it.id == profileId }
                        
                        if (currentProfile != null) {
                            ProfileConfigScreen(
                                profile = currentProfile,
                                thermalData = thermalData,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToRGB = { navController.navigate("rgb/$profileId") },
                                onSetFanSpeed = { speed -> 
                                    setFanSpeed(speed)
                                    profileRepository.updateFanSpeed(profileId, speed)
                                },
                                onToggleAutoMode = { toggleAutoMode(profileId) },
                                onConnect = { connectToProfile(currentProfile) },
                                onDisconnect = { disconnectFromCooler() },
                                onDeleteProfile = { 
                                    if (currentConnectedProfileId == profileId) {
                                        disconnectFromCooler()
                                    }
                                    profileRepository.deleteProfile(profileId)
                                    navController.popBackStack()
                                },
                                onRenameProfile = { newName ->
                                    profileRepository.renameProfile(profileId, newName)
                                }
                            )
                        }
                    }
                    
                    // Pantalla de control RGB
                    composable(
                        route = "rgb/{profileId}",
                        arguments = listOf(navArgument("profileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
                        
                        RGBControlScreen(
                            isConnected = isConnected && currentConnectedProfileId == profileId,
                            isAutoMode = isAutoMode,
                            onNavigateBack = { navController.popBackStack() },
                            onSetColorful = { 
                                setColorful()
                                profileRepository.updateRGBConfig(profileId, RGBConfig(LightEffect.COLORFUL, 0, 0, 0))
                            },
                            onSetBreathFullColor = { 
                                setBreathFullColor()
                                profileRepository.updateRGBConfig(profileId, RGBConfig(LightEffect.BREATH_FULLCOLOR, 0, 0, 0))
                            },
                            onSetBreathSingleColor = { r, g, b -> 
                                setBreathSingleColor(r, g, b)
                                profileRepository.updateRGBConfig(profileId, RGBConfig(LightEffect.BREATH_SINGLE, r, g, b))
                            },
                            onSetAlwaysBright = { r, g, b -> 
                                setAlwaysBright(r, g, b)
                                profileRepository.updateRGBConfig(profileId, RGBConfig(LightEffect.ALWAYS_BRIGHT, r, g, b))
                            },
                            onTurnOffLight = { 
                                turnOffLight()
                                profileRepository.updateRGBConfig(profileId, null)
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Conecta a un perfil existente
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectToProfile(profile: CoolerProfile) {
        if (!bluetoothAdapter.isEnabled) {
            statusMessage = "Bluetooth desactivado"
            Toast.makeText(this, "Por favor, activa Bluetooth primero", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isConnecting || (isConnected && currentConnectedProfileId == profile.id)) {
            Log.w(TAG, "Ya conectado o conectando a este perfil")
            return
        }
        
        // Si hay otra conexión activa, desconectar primero
        if (isConnected && currentConnectedProfileId != profile.id) {
            disconnectFromCooler()
        }
        
        pendingConnectionProfileId = profile.id
        pendingNewDeviceType = profile.deviceType
        
        // Intentar conectar directamente por MAC si la conocemos
        if (profile.macAddress.isNotBlank()) {
            connectDirectlyByMac(profile.macAddress, profile.deviceType)
        } else {
            // Escanear para encontrar el dispositivo
            startScanForDevice(profile.deviceType)
        }
    }

    /**
     * Conecta directamente por dirección MAC conocida
     */
    @SuppressLint("MissingPermission")
    private fun connectDirectlyByMac(macAddress: String, deviceType: CoolerDeviceType) {
        try {
            if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                statusMessage = "Permisos BLE requeridos"
                return
            }
            
            isConnecting = true
            statusMessage = "Conectando a ${deviceType.deviceName}..."
            
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            
            // Limpiar conexión anterior
            BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
            bluetoothGatt = null
            fanCharacteristic = null
            lightCharacteristic = null
            
            bluetoothGatt = device.connectGatt(
                this,
                true,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
            
            Log.d(TAG, "Intentando conexión directa a: $macAddress")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error en conexión directa: ${e.message}", e)
            statusMessage = "Error: ${e.message}"
            isConnecting = false
            
            // Fallback: escanear para encontrar
            startScanForDevice(deviceType)
        }
    }

    /**
     * Inicia escaneo para un nuevo dispositivo (crear perfil)
     */
    private fun startScanForNewDevice(deviceType: CoolerDeviceType) {
        Log.d(TAG, "📱 startScanForNewDevice: ${deviceType.deviceName}")
        
        // Verificar solo permisos críticos de BLE (sin SCHEDULE_EXACT_ALARM)
        if (!BlePermissionManager.hasCriticalBlePermissions(this)) {
            Log.w(TAG, "⚠️ Faltan permisos BLE críticos")
            val missing = BlePermissionManager.getMissingPermissions(this)
            Log.d(TAG, "Permisos faltantes: ${missing.joinToString()}")
            val requestable = missing.filter { it != android.Manifest.permission.SCHEDULE_EXACT_ALARM }
            if (requestable.isNotEmpty()) {
                requestPermissionsLauncher.launch(requestable.toTypedArray())
            }
            return
        }
        
        Log.d(TAG, "✓ Todos los permisos BLE críticos disponibles")
        
        pendingNewDeviceType = deviceType
        pendingConnectionProfileId = null // Es un nuevo dispositivo
        startScanForDevice(deviceType)
    }

    /**
     * Inicia escaneo para un tipo de dispositivo
     */
    private fun startScanForDevice(deviceType: CoolerDeviceType) {
        Log.d(TAG, "🔍 startScanForDevice: ${deviceType.deviceName}")
        
        if (isScanning) {
            Log.w(TAG, "Ya hay un escaneo en curso")
            return
        }
        
        try {
            if (!BlePermissionManager.hasBluetoothScanPermission(this)) {
                Log.e(TAG, "❌ Permiso de escaneo BLE faltante")
                statusMessage = "Permiso de escaneo requerido"
                return
            }
            
            isConnecting = true
            statusMessage = "Buscando ${deviceType.deviceName}..."
            Log.d(TAG, "📡 Estado actualizado: isConnecting=true, statusMessage=$statusMessage")
            
            // Limpiar estado anterior
            if (bluetoothGatt != null) {
                BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
                bluetoothGatt = null
            }
            fanCharacteristic = null
            lightCharacteristic = null
            
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "❌ BluetoothLeScanner es null")
                statusMessage = "Escáner BLE no disponible"
                isConnecting = false
                return
            }
            Log.d(TAG, "✓ BluetoothLeScanner obtenido")
            
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            
            isScanning = true
            
            @SuppressLint("MissingPermission")
            bluetoothLeScanner?.startScan(null, scanSettings, bleScanCallback)
            
            Log.d(TAG, "Escaneo iniciado para: ${deviceType.deviceName}")
            
            // Timeout de 20 segundos
            monitoringScope.launch {
                delay(20000)
                if (isFinishing || isDestroyed) return@launch
                
                if (isScanning) {
                    runOnUiThread {
                        cancelScan()
                        statusMessage = "No se encontró el dispositivo"
                        Toast.makeText(this@MainActivity, 
                            "⚠️ No se encontró ${deviceType.deviceName}. Asegúrate de que esté encendido y cerca.", 
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escaneo: ${e.message}", e)
            statusMessage = "Error: ${e.message}"
            isConnecting = false
            isScanning = false
        }
    }

    /**
     * Cancela el escaneo actual
     */
    private fun cancelScan() {
        if (isScanning) {
            BleConnectionHelper.safeStopScan(bluetoothLeScanner, bleScanCallback, TAG)
            isScanning = false
        }
        isConnecting = false
        statusMessage = "Escaneo cancelado"
    }
    
    private fun enableBluetooth() {
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error al activar Bluetooth: ${e.message}", e)
            Toast.makeText(this, "Error al activar Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun checkBluetoothState() {
        val isEnabled = bluetoothAdapter.isEnabled
        showBluetoothRequired = !isEnabled
        
        if (!isEnabled) {
            Toast.makeText(this, "Bluetooth sigue desactivado", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name ?: "Unknown"
            
            val serviceUuids = result.scanRecord?.serviceUuids?.joinToString(", ") { it.toString() } ?: "ninguno"
            Log.d(TAG, "📱 BLE Device: $deviceName [${device.address}] RSSI: ${result.rssi} dBm")
            Log.d(TAG, "   UUIDs: $serviceUuids")
            
            val isNameMatch = deviceName.contains("Magcooler", ignoreCase = true) || 
                              deviceName.contains("RM ", ignoreCase = true) ||
                              deviceName.contains("RedMagic", ignoreCase = true) ||
                              deviceName.contains("Heat", ignoreCase = true) ||
                              deviceName.contains("Cooler", ignoreCase = true)
            
            if (isNameMatch) {
                isScanning = false
                bluetoothLeScanner?.stopScan(this)
                
                // Guardar info del dispositivo encontrado
                pendingDeviceMac = device.address
                pendingDeviceName = deviceName
                pendingDeviceRssi = result.rssi
                
                Log.d(TAG, "✓ Dispositivo Compatible Encontrado: $deviceName [${device.address}]")
                
                runOnUiThread {
                    statusMessage = "Conectando a $deviceName..."
                }
                
                // Cerrar conexión GATT anterior
                bluetoothGatt?.let { oldGatt ->
                    try {
                        BleConnectionHelper.safeCloseGatt(oldGatt, TAG)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error cerrando GATT anterior: ${e.message}")
                    }
                }
                
                bluetoothGatt = device.connectGatt(
                    this@MainActivity,
                    true,
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
            
            if (isScanning) {
                BleConnectionHelper.safeStopScan(bluetoothLeScanner, bleScanCallback, TAG)
                isScanning = false
            }
            
            // Actualizar estado del perfil antes de desconectar
            currentConnectedProfileId?.let { profileId ->
                profileRepository.updateConnectionState(profileId, false)
            }
            
            BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
            bluetoothGatt = null
            fanCharacteristic = null
            lightCharacteristic = null
            isConnected = false
            isConnecting = false
            currentConnectedProfileId = null
            statusMessage = "Desconectado"
            
            if (!isServiceRunning(CoolerService::class.java)) {
                thermalMonitor.stopMonitoring()
            }
            
            Log.d(TAG, "Desconectado del cooler")
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar: ${e.message}", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (isFinishing || isDestroyed) {
                Log.w(TAG, "Activity destruida, ignorando callback GATT")
                return
            }
            
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
                        lightCharacteristic = null
                        
                        // Actualizar estado del perfil
                        currentConnectedProfileId?.let { profileId ->
                            profileRepository.updateConnectionState(profileId, false)
                        }
                        currentConnectedProfileId = null
                        
                        if (!isServiceRunning(CoolerService::class.java)) {
                            thermalMonitor.stopMonitoring()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (isFinishing || isDestroyed) {
                return
            }
            
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Servicios descubiertos exitosamente")
                    
                    // Buscar características
                    var fanService = gatt.getService(CoolerBleConstants.FAN_SERVICE_UUID)
                    if (fanService != null) {
                        fanCharacteristic = fanService.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)
                        lightCharacteristic = fanService.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)
                    } else {
                        // Buscar en todos los servicios
                        for (service in gatt.services ?: emptyList()) {
                            val fanChar = service.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)
                            if (fanChar != null) {
                                fanCharacteristic = fanChar
                                fanService = service
                            }
                            val lightChar = service.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)
                            if (lightChar != null) {
                                lightCharacteristic = lightChar
                            }
                            if (fanCharacteristic != null && lightCharacteristic != null) break
                        }
                    }
                    
                    if (fanCharacteristic != null) {
                        statusMessage = "Listo para controlar"
                        Toast.makeText(this@MainActivity, "Conectado exitosamente", Toast.LENGTH_SHORT).show()
                        
                        // Crear o actualizar perfil
                        handleSuccessfulConnection()
                        
                        // Iniciar monitoreo térmico
                        thermalMonitor.startAmbientSensor()
                        startThermalMonitoring()
                        
                        // Habilitar notificaciones y leer velocidad
                        try {
                            if (!BlePermissionManager.hasBluetoothConnectPermission(this@MainActivity)) {
                                return@runOnUiThread
                            }
                            
                            @SuppressLint("MissingPermission")
                            gatt.setCharacteristicNotification(fanCharacteristic, true)
                            
                            @SuppressLint("MissingPermission")
                            gatt.readCharacteristic(fanCharacteristic)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error habilitando notificaciones: ${e.message}")
                        }
                    } else {
                        statusMessage = "Error: Servicio no encontrado"
                        Log.e(TAG, "Fan characteristic NO encontrada")
                    }
                } else {
                    statusMessage = "Error descubriendo servicios"
                    Log.e(TAG, "Error en descubrimiento de servicios: $status")
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (isFinishing || isDestroyed) return
            
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value
                    val value = if (data != null && data.isNotEmpty()) {
                        data[0].toInt() and 0xFF
                    } else 0
                    Log.d(TAG, "Velocidad escrita exitosamente: $value")
                } else {
                    Log.e(TAG, "Error escribiendo characteristic: $status")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (isFinishing || isDestroyed) return
            
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        val rawSpeed = data[0].toInt() and 0xFF
                        val speedPercent = mapRawToPercent(rawSpeed)
                        currentCoolerSpeed = speedPercent
                        Log.d(TAG, "Velocidad leída: $rawSpeed raw → $speedPercent%")
                    }
                } else {
                    Log.e(TAG, "Error leyendo characteristic: $status")
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            onCharacteristicRead(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Maneja la conexión exitosa - crea o actualiza perfil
     */
    private fun handleSuccessfulConnection() {
        val mac = pendingDeviceMac ?: bluetoothGatt?.device?.address ?: return
        val deviceType = pendingNewDeviceType ?: return
        
        // Verificar si ya existe un perfil con esta MAC
        val existingProfile = profileRepository.getProfileByMac(mac)
        
        if (existingProfile != null) {
            // Actualizar perfil existente
            currentConnectedProfileId = existingProfile.id
            profileRepository.updateConnectionState(existingProfile.id, true, pendingDeviceRssi)
            Log.d(TAG, "Perfil existente actualizado: ${existingProfile.displayName}")
        } else if (pendingConnectionProfileId == null) {
            // Crear nuevo perfil
            val newProfile = CoolerProfile.fromConnectedDevice(
                deviceType = deviceType,
                macAddress = mac,
                deviceName = pendingDeviceName,
                rssi = pendingDeviceRssi
            )
            val createdProfile = profileRepository.addProfile(newProfile)
            currentConnectedProfileId = createdProfile.id
            newlyCreatedProfileId = createdProfile.id  // Para navegación automática
            Log.d(TAG, "Nuevo perfil creado: ${createdProfile.displayName}")
            Toast.makeText(this, "✓ Perfil creado: ${createdProfile.displayName}", Toast.LENGTH_SHORT).show()
        } else {
            // Conexión a perfil existente por ID
            currentConnectedProfileId = pendingConnectionProfileId
            profileRepository.updateConnectionState(pendingConnectionProfileId!!, true, pendingDeviceRssi)
        }
        
        // Limpiar estado pendiente
        pendingNewDeviceType = null
        pendingDeviceMac = null
        pendingDeviceName = null
        pendingDeviceRssi = 0
        pendingConnectionProfileId = null
        // NO limpiar newlyCreatedProfileId aquí - se limpia después de navegar
    }

    private fun setFanSpeed(speed: Int) {
        if (!isConnected || bluetoothGatt == null) {
            Log.w(TAG, "setFanSpeed: No conectado o GATT es null")
            return
        }

        fanCharacteristic?.let { characteristic ->
            try {
                if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                    return
                }
                
                val rawValue = mapPercentToRaw(speed.coerceIn(0, 100))
                val value = rawValue.toByte()
                
                @Suppress("DEPRECATION")
                characteristic.value = byteArrayOf(value)
                @Suppress("DEPRECATION")
                @SuppressLint("MissingPermission")
                val result = bluetoothGatt?.writeCharacteristic(characteristic)
                
                if (result == true) {
                    Log.d(TAG, "Velocidad establecida: $speed% → $rawValue raw")
                    currentCoolerSpeed = speed
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error estableciendo velocidad: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            monitoringScope.cancel()
            
            if (isScanning) {
                try {
                    BleConnectionHelper.safeStopScan(bluetoothLeScanner, bleScanCallback, TAG)
                    isScanning = false
                } catch (e: Exception) {
                    Log.w(TAG, "Error deteniendo escaneo: ${e.message}")
                }
            }
            
            thermalMonitor.stopMonitoring()
            
            // Desconectar todos los perfiles
            profileRepository.disconnectAllProfiles()
            
            BleConnectionHelper.safeCloseGatt(bluetoothGatt, TAG)
            bluetoothGatt = null
            fanCharacteristic = null
            lightCharacteristic = null
            
            Log.d(TAG, "✓ Recursos BLE liberados correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando recursos: ${e.message}", e)
        }
    }
    
    private fun startThermalMonitoring() {
        thermalMonitor.startMonitoring(monitoringScope) { data ->
            thermalData = data
            
            if (isAutoMode && isConnected && !isServiceRunning(CoolerService::class.java)) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAutoAdjustTime > 5000) {
                    setFanSpeed(data.recommendedSpeed)
                    lastAutoAdjustTime = currentTime
                }
            }
        }
    }
    
    private fun toggleAutoMode(profileId: String) {
        isAutoMode = !isAutoMode
        profileRepository.updateAutoMode(profileId, isAutoMode)
        
        if (isAutoMode) {
            if (!BlePermissionManager.hasNotificationPermission(this)) {
                Toast.makeText(this, "Se requiere permiso de notificaciones para el modo automático", Toast.LENGTH_SHORT).show()
                isAutoMode = false
                return
            }
            
            val profile = profileRepository.getProfile(profileId)
            
            if (profile == null) {
                Log.e(TAG, "❌ No se encontró perfil con ID: $profileId")
                Toast.makeText(this, "Error: Perfil no encontrado", Toast.LENGTH_SHORT).show()
                isAutoMode = false
                return
            }
            
            // Iniciar servicio en segundo plano con información completa del perfil
            val serviceIntent = Intent(this, CoolerService::class.java).apply {
                action = CoolerService.ACTION_START_AUTO
                putExtra("PROFILE_ID", profile.id)
                putExtra(CoolerService.EXTRA_DEVICE_TYPE, profile.deviceType.name)
                putExtra("DEVICE_MAC", profile.macAddress)
                putExtra("DEVICE_NAME", profile.name)
                
                profile.rgbConfig?.let { config ->
                    putExtra("RGB_EFFECT", config.effect.code.toInt())
                    putExtra("RGB_RED", config.red)
                    putExtra("RGB_GREEN", config.green)
                    putExtra("RGB_BLUE", config.blue)
                }
            }
            
            Log.d(TAG, "🚀 Iniciando modo automático para perfil: ${profile.displayName} [${profile.macAddress}]")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Toast.makeText(this, "Modo Automático Activado", Toast.LENGTH_SHORT).show()
            
            // Si hay una conexión activa en MainActivity, desconectar para que el servicio tome el control
            if (isConnected && bluetoothGatt != null) {
                Log.d(TAG, "⚡ Desconectando GATT de MainActivity para que el servicio tome el control")
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
            
        } else {
            if (isServiceRunning(CoolerService::class.java)) {
                val stopIntent = Intent(this, CoolerService::class.java).apply {
                    action = CoolerService.ACTION_STOP_AUTO
                }
                stopService(stopIntent)
            }
            
            Toast.makeText(this, "Modo Automático Desactivado", Toast.LENGTH_SHORT).show()
            
            val profile = profileRepository.getProfile(profileId)
            monitoringScope.launch {
                delay(1000)
                if (profile != null && BlePermissionManager.hasCriticalBlePermissions(this@MainActivity) && 
                    bluetoothAdapter.isEnabled && !isConnected) {
                    runOnUiThread {
                        connectToProfile(profile)
                    }
                }
            }
        }
    }
    
    private fun setRGBLight(effect: LightEffect, red: Int = 0, green: Int = 0, blue: Int = 0) {
        if (isAutoMode) {
            val service = CoolerService.getInstance()
            if (service != null) {
                service.setRGBLight(effect, red, green, blue)
                Toast.makeText(this, "Luz RGB establecida: ${effect.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Servicio no disponible", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        if (!isConnected || lightCharacteristic == null || bluetoothGatt == null) {
            Toast.makeText(this, "No conectado", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            if (!BlePermissionManager.hasBluetoothConnectPermission(this)) {
                return
            }
            
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
                Log.d(TAG, "Comando RGB enviado: ${effect.name}, R:$red G:$green B:$blue")
                Toast.makeText(this, "Luz RGB establecida: ${effect.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en setRGBLight: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun setColorful() = setRGBLight(LightEffect.COLORFUL)
    fun setBreathFullColor() = setRGBLight(LightEffect.BREATH_FULLCOLOR)
    fun setBreathSingleColor(red: Int, green: Int, blue: Int) = setRGBLight(LightEffect.BREATH_SINGLE, red, green, blue)
    fun setAlwaysBright(red: Int, green: Int, blue: Int) = setRGBLight(LightEffect.ALWAYS_BRIGHT, red, green, blue)
    fun turnOffLight() = setRGBLight(LightEffect.ALWAYS_BRIGHT, 0, 0, 0)
}
