package com.hitomatito.redmagicooler

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.hitomatito.redmagicooler.ble.CoolerBleManager
import com.hitomatito.redmagicooler.data.ProfileRepository
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig
import com.hitomatito.redmagicooler.ui.AddDeviceScreen
import com.hitomatito.redmagicooler.ui.BluetoothRequiredScreen
import com.hitomatito.redmagicooler.ui.HomeScreen
import com.hitomatito.redmagicooler.ui.ProfileConfigScreen
import com.hitomatito.redmagicooler.ui.RGBControlScreen
import com.hitomatito.redmagicooler.ui.theme.RedmagiCoolerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Suppress("OVERRIDE_DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    // La conexión BLE la posee CoolerBleManager (propietario único).
    // Esta actividad actúa como cliente: observa estado y emite comandos.
    private var isScanning by mutableStateOf(false)

    // Estados observables para la UI
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Listo")
    private var connectionError by mutableStateOf<String?>(null)
    private var currentCoolerSpeed by mutableIntStateOf(50)
    
    // Perfil actualmente conectado
    private var currentConnectedProfileId by mutableStateOf<String?>(null)
    private var pendingConnectionProfileId by mutableStateOf<String?>(null)
    
    // Para crear nuevo perfil después de conexión exitosa
    private var pendingNewDeviceType by mutableStateOf<CoolerDeviceType?>(null)
    private var pendingDeviceMac by mutableStateOf<String?>(null)
    private var pendingDeviceName by mutableStateOf<String?>(null)
    private var pendingDeviceRssi by mutableIntStateOf(0)

    // Perfil recién creado (para navegación automática)
    private var newlyCreatedProfileId by mutableStateOf<String?>(null)
    
    // Estado térmico compartido por esta UI y CoolerService
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var thermalDataCollectionJob: Job? = null
    private var isAutoMode by mutableStateOf(false)
    private var thermalData by mutableStateOf(ThermalMonitor.ThermalData())
    
    // Control de Bluetooth
    private var showBluetoothRequired by mutableStateOf(false)
    
    // Repositorio de perfiles
    private lateinit var profileRepository: ProfileRepository
    
    companion object {
        private const val TAG = "RedMagicCooler"
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
    
    /**
     * Verifica y limpia estados inconsistentes del servicio
     * Si el servicio no está corriendo, pero hay perfiles en modo auto, los limpia
     */
    private fun verifyAndCleanServiceState() {
        val isServiceRunning = isServiceRunning(CoolerService::class.java)
        
        if (!isServiceRunning) {
            // Si el servicio no está corriendo, verificar si hay perfiles en modo auto
            val profiles = profileRepository.profiles.value
            var cleanedCount = 0
            
            profiles.forEach { profile ->
                if (profile.isAutoMode) {
                    Log.w(TAG, "Estado inconsistente detectado: Perfil ${profile.displayName} en modo auto pero servicio no está corriendo")
                    // Limpiar estado
                    profileRepository.updateAutoMode(profile.id, false)
                    profileRepository.updateConnectionState(profile.id, false)
                    cleanedCount++
                }
            }
            
            if (cleanedCount > 0) {
                Log.i(TAG, "Se limpiaron $cleanedCount perfiles con estados inconsistentes")
            }
        } else {
            Log.d(TAG, "Servicio de cooler está corriendo correctamente")
        }
    }

    init {
        // El launcher de permisos vive centralizado en BlePermissionManager.
        // Debe registrarse antes de que la Activity llegue a STARTED, por eso va
        // en un init y no en onCreate. Asi mantiene el orden activity_rq#0.
        BlePermissionManager.register(this)
    }

    /**
     * Reacción unificada a la falta de un permiso BLE: registra el motivo,
     * informa al usuario y vuelve a pedir los permisos que falten.
     *
     * Antes de centralizar esto, cada call site hacia un "return" silencioso y
     * la app quedaba con la UI de "conectado" sin hacer nada.
     */
    private fun handleMissingBlePermission(reason: String) {
        Log.w(TAG, "Permiso BLE faltante: $reason")
        statusMessage = reason
        connectionError = "Se perdieron los permisos de Bluetooth.\n" +
            "Reactivá los permisos para controlar el cooler."

        // Evitar diálogos duplicados si el usuario sigue interactuando.
        if (!BlePermissionManager.isRequestInFlight) {
            BlePermissionManager.requestPermissions(this) { result ->
                if (!result.granted) {
                    Log.e(TAG, "Permisos siguen denegados: ${result.denied}")
                }
            }
        }
    }

    /**
     * Manejo unificado de SecurityException: el permiso BLUETOOTH_CONNECT se
     * revocó mientras la app estaba en uso.
     */
    private fun handleSecurityException(e: SecurityException, operation: String) {
        Log.e(TAG, "SecurityException en $operation: ${e.message}")
        handleMissingBlePermission("Permiso de Bluetooth revocado")
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
        // El tema Splash solo se usa durante el arranque; después pasamos al tema normal.
        setTheme(R.style.Theme_RedmagiCooler)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Inicializar repositorio de perfiles
        profileRepository = ProfileRepository.getInstance(this)
        
        // Verificar y limpiar estados inconsistentes del servicio
        verifyAndCleanServiceState()

        // Inicializar Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager no disponible")
            Toast.makeText(this, "Bluetooth no disponible en este dispositivo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        bluetoothAdapter = bluetoothManager.adapter
        showBluetoothRequired = !bluetoothAdapter.isEnabled
        
        // Solicitar permisos al inicio (centralizado en BlePermissionManager)
        BlePermissionManager.requestPermissions(this) { result ->
            if (result.granted) {
                Log.d(TAG, "Todos los permisos concedidos")
                statusMessage = "Listo para buscar dispositivos"
            } else {
                Log.e(TAG, "Permisos DENEGADOS: ${result.denied}")
                statusMessage = "Permisos denegados"
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
        
        // Inicializar monitor térmico único y alimentar la UI desde él
        ThermalMonitorManager.initialize(this)
        thermalDataCollectionJob?.cancel()
        thermalDataCollectionJob = monitoringScope.launch {
            ThermalMonitorManager.thermalData.collect { data -> thermalData = data }
        }

        // Motor BLE: propietario único de la conexión (compartido con CoolerService)
        CoolerBleManager.init(this)
        observeBleEvents()

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
                            connectionError = connectionError,
                            onDismissError = { connectionError = null },
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
                                Log.d(TAG, "Usuario selecciono dispositivo: ${deviceType.deviceName}")
                                connectionError = null
                                pendingNewDeviceType = deviceType
                                startScanForNewDevice(deviceType)
                            },
                            isScanning = isScanning,
                            isConnecting = isConnecting,
                            statusMessage = statusMessage,
                            connectionError = connectionError,
                            onDismissError = { connectionError = null },
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
                                },
                                connectionError = connectionError,
                                onDismissError = { connectionError = null }
                            )
                        }
                    }
                    
                    // Pantalla de control RGB
                    composable(
                        route = "rgb/{profileId}",
                        arguments = listOf(navArgument("profileId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
                        val rgbProfile = profiles.find { it.id == profileId }

                        RGBControlScreen(
                            isConnected = isConnected && currentConnectedProfileId == profileId,
                            isAutoMode = isAutoMode,
                            rgbConfig = rgbProfile?.rgbConfig,
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
    /**
     * Traduce los eventos del motor BLE al estado de la UI.
     */
    private fun observeBleEvents() {
        monitoringScope.launch {
            CoolerBleManager.state.collect { managerState ->
                isConnected = managerState == CoolerBleManager.ConnectionState.CONNECTED
                isScanning = managerState == CoolerBleManager.ConnectionState.SCANNING
            }
        }
        monitoringScope.launch {
            CoolerBleManager.fanSpeedPercent.collect { percent ->
                if (percent >= 0) {
                    currentCoolerSpeed = percent
                }
            }
        }
        monitoringScope.launch {
            CoolerBleManager.events.collect { event ->
                when (event) {
                    is CoolerBleManager.BleEvent.Connected -> {
                        Log.d(TAG, "Cooler conectado: ${event.device.deviceType.deviceName}")
                        pendingDeviceMac = event.device.address
                        pendingDeviceName = event.device.bleName
                        pendingDeviceRssi = event.device.rssi
                        pendingNewDeviceType = event.device.deviceType
                        isConnecting = false
                        statusMessage = "Conectado"
                        handleSuccessfulConnection()
                    }
                    is CoolerBleManager.BleEvent.Disconnected -> {
                        Log.d(TAG, "Cooler desconectado (status=${event.status})")
                        statusMessage = "Desconectado"
                        currentCoolerSpeed = 0
                        currentConnectedProfileId?.let { pid ->
                            profileRepository.updateConnectionState(pid, false)
                        }
                        currentConnectedProfileId = null
                        isConnecting = false
                    }
                    is CoolerBleManager.BleEvent.FanSpeedChanged -> Unit // gestionado por el flow de estado
                    is CoolerBleManager.BleEvent.TemperatureRaw ->
                        Log.d(TAG, "Temperatura cruda del cooler: ${event.raw}")
                    is CoolerBleManager.BleEvent.ConnectionFailed -> {
                        Log.w(TAG, "Error de conexión: ${event.reason}")
                        connectionError = event.reason
                        statusMessage = "Error: ${event.reason}"
                        isConnecting = false
                        isScanning = false
                    }
                    is CoolerBleManager.BleEvent.DiscoveryRetry ->
                        statusMessage = "Reintentando descubrimiento de servicios (${event.attempt}/${event.max})..."
                }
            }
        }
    }

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
        
        // Sí hay otra conexión activa, desconectar primero
        if (isConnected && currentConnectedProfileId != profile.id) {
            disconnectFromCooler()
        }
        
        pendingConnectionProfileId = profile.id
        pendingNewDeviceType = profile.deviceType
        connectionError = null

        // Escanear antes de conectar, como la app oficial: el dispositivo obtenido
        // por escaneo tiene el tipo de dirección BLE resuelto y el discovery responde.
        startScanForDevice(profile.deviceType, profile.macAddress.ifBlank { null })
    }

    /**
     * Inicia escaneo para un nuevo dispositivo (crear perfil)
     */
    private fun startScanForNewDevice(deviceType: CoolerDeviceType) {
        Log.d(TAG, "startScanForNewDevice: ${deviceType.deviceName}")
        
        // Pedir los permisos imprescindibles para escanear (centralizado en
        // BlePermissionManager). Si ya estan concedidos, el callback se invoca
        // de inmediato y el escaneo sigue sin pedir nada.
        BlePermissionManager.requestCriticalBlePermissions(this) { result ->
            if (result.granted) {
                Log.d(TAG, "Todos los permisos BLE criticos disponibles")
                pendingNewDeviceType = deviceType
                pendingConnectionProfileId = null // Es un nuevo dispositivo
                startScanForDevice(deviceType)
            } else {
                Log.e(TAG, "Permisos BLE criticos denegados: ${result.denied}")
                statusMessage = "Permisos denegados"
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Inicia escaneo para un tipo de dispositivo.
     * El motor BLE aplica la detección por protocolo real (service 00004a41 + manufacturer 2250)
     * y conecta automáticamente al primer cooler encontrado.
     */
    private fun startScanForDevice(deviceType: CoolerDeviceType, targetMac: String? = null) {
        Log.d(TAG, "startScanForDevice: ${deviceType.deviceName}")
        
        if (isScanning) {
            Log.w(TAG, "Ya hay un escaneo en curso")
            return
        }
        
        try {
            if (!BlePermissionManager.hasBluetoothScanPermission(this)) {
                handleMissingBlePermission("Permiso de escaneo Bluetooth requerido")
                return
            }
            
            isConnecting = true
            statusMessage = "Buscando ${deviceType.deviceName}..."
            Log.d(TAG, "Estado actualizado: isConnecting=true, statusMessage=$statusMessage")
            
            // El motor BLE cierra la conexión anterior si la hay
            CoolerBleManager.startScan(lowLatency = true, targetMac = targetMac, targetType = deviceType)
            
            Log.d(TAG, "Escaneo iniciado para: ${deviceType.deviceName}")
            
            // Timeout de 20 segundos
            monitoringScope.launch {
                delay(20000.milliseconds)
                if (isFinishing || isDestroyed) return@launch
                
                if (isScanning) {
                    runOnUiThread {
                        cancelScan()
                        statusMessage = "No se encontró el dispositivo"
                        Toast.makeText(this@MainActivity, 
                            "No se encontro ${deviceType.deviceName}. Asegurate de que este encendido y cerca.", 
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            
        } catch (e: SecurityException) {
            handleSecurityException(e, "inicio de escaneo")
            isConnecting = false
            isScanning = false
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
        CoolerBleManager.stopScan()
        isScanning = false
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
    
    private fun disconnectFromCooler() {
        try {
            CoolerBleManager.stopScan()
            isScanning = false
            
            // Actualizar estado del perfil antes de desconectar
            currentConnectedProfileId?.let { profileId ->
                profileRepository.updateConnectionState(profileId, false)
            }

            // El motor BLE cierra el cliente y notifica con Disconnected(willReconnect=false)
            CoolerBleManager.disconnect()
            isConnected = false
            isConnecting = false
            currentConnectedProfileId = null
            statusMessage = "Desconectado"
            
            // El monitor compartido sigue mostrando temperatura; solo el servicio controla el cooler.
            
            Log.d(TAG, "Desconectado del cooler")
        } catch (e: SecurityException) {
            handleSecurityException(e, "desconexión")
        } catch (e: Exception) {
            Log.e(TAG, "Error al desconectar: ${e.message}", e)
        }
    }

    /**
     * Maneja la conexión exitosa - crea o actualiza perfil
     */
    private fun handleSuccessfulConnection() {
        val mac = pendingDeviceMac ?: CoolerBleManager.connectedDevice?.address ?: return
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
            Toast.makeText(this, "Perfil creado: ${createdProfile.displayName}", Toast.LENGTH_SHORT).show()
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
        if (!isConnected) {
            Log.w(TAG, "setFanSpeed: no conectado")
            return
        }
        if (CoolerBleManager.setFanSpeed(speed)) {
            currentCoolerSpeed = speed.coerceIn(0, 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Liberar el launcher de permisos: la Activity ya no puede mostrar dialogos.
        BlePermissionManager.unregister(this)
        try {
            monitoringScope.cancel()
            
            CoolerBleManager.stopScan()
            
            // Si el servicio no está activo, liberar la conexión para ahorrar batería.
            // Si está activo (modo automático), el GATT compartido se mantiene.
            if (!isServiceRunning(CoolerService::class.java)) {
                CoolerBleManager.disconnect()
            }
            
            Log.d(TAG, "Recursos BLE liberados correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error liberando recursos: ${e.message}", e)
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
                Log.e(TAG, "No se encontró perfil con ID: $profileId")
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
            
            Log.d(TAG, "Iniciando modo automático para perfil: ${profile.displayName} [${profile.macAddress}]")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Toast.makeText(this, "Modo Automático Activado", Toast.LENGTH_SHORT).show()
            // No se toca el GATT: CoolerBleManager es el propietario único y el
            // servicio controlará el mismo cooler a través del motor BLE.
            
        } else {
            // Desactivar modo automático
            if (isServiceRunning(CoolerService::class.java)) {
                // En lugar de detener el servicio completamente, cambiar a modo manual
                // Esto mantiene la conexión activa y solo desactiva el ajuste automático
                val manualIntent = Intent(this, CoolerService::class.java).apply {
                    action = CoolerService.ACTION_SWITCH_TO_MANUAL
                }
                startService(manualIntent)
                
                Toast.makeText(this, "Cambiando a Modo Manual...", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Modo automático desactivado; el GATT compartido sigue activo")
            } else {
                Toast.makeText(this, "Modo Automático Desactivado", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setRGBLight(effect: LightEffect, red: Int = 0, green: Int = 0, blue: Int = 0) {
        // El motor BLE es el propietario del GATT (incluso con modo automático activo)
        if (!CoolerBleManager.setRGBLight(effect, red, green, blue)) {
            Toast.makeText(this, "No conectado", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun setColorful() = setRGBLight(LightEffect.COLORFUL)
    fun setBreathFullColor() = setRGBLight(LightEffect.BREATH_FULLCOLOR)
    fun setBreathSingleColor(red: Int, green: Int, blue: Int) = setRGBLight(LightEffect.BREATH_SINGLE, red, green, blue)
    fun setAlwaysBright(red: Int, green: Int, blue: Int) = setRGBLight(LightEffect.ALWAYS_BRIGHT, red, green, blue)
    fun turnOffLight() = setRGBLight(LightEffect.ALWAYS_BRIGHT, 0, 0, 0)
}
