package com.hitomatito.redmagicooler.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

import android.util.Log
import com.hitomatito.redmagicooler.utils.BleConnectionHelper
import com.hitomatito.redmagicooler.CoolerDevice
import com.hitomatito.redmagicooler.CoolerDeviceList
import com.hitomatito.redmagicooler.CoolerDeviceType
import com.hitomatito.redmagicooler.BlePermissionManager
import com.hitomatito.redmagicooler.model.CoolerBleConstants
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Motor BLE único para la comunicación con coolers RedMagic/Nubia.
 *
 * Propietario único de la conexión GATT: MainActivity y CoolerService actúan
 * como clientes (observan estado y emiten comandos). Esto elimina los dos GATT
 * paralelos del diseño anterior y la necesidad de desconectarse mutuamente.
 *
 * Protocolo (extraído de la app oficial cn.nubia.externdevice):
 * - Advertising: service 00004a41-... y/o manufacturer data (compañía 2250 / 0x08CA),
 *   bytes [mainType=5][subType=generación]
 * - Escaneo Android: se usa SCAN_MODE_LOW_LATENCY (interactivo) o LOW_POWER (segundo plano)
 * - Tras conectar: discoverServices → escritura CCCD (00002902) en 00001015 (temperatura)
 *   y 00001012 (fan speed) → notificaciones enrutadas por characteristic.uuid
 */
object CoolerBleManager {

    private const val TAG = "CoolerBleManager"
    private const val SCAN_TIMEOUT_MS = 20000L
    private const val MAX_RECONNECT_ATTEMPTS = 5
    private const val DISCOVERY_TIMEOUT_MS = 10000L
    private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    private const val PREFS_DEAD_OBJECT = "cooler_ble_prefs"
    private const val KEY_DEAD_OBJECT_COUNT = "dead_object_count"
    private const val KEY_DEAD_OBJECT_DATE = "dead_object_date"
    private const val MAX_DEAD_OBJECT_PER_DAY = 10

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    sealed interface BleEvent {
        data class Connected(val device: CoolerDevice) : BleEvent
        data class Disconnected(val status: Int, val willReconnect: Boolean) : BleEvent
        data class FanSpeedChanged(val raw: Int, val percent: Int) : BleEvent
        data class TemperatureRaw(val raw: Int) : BleEvent
        data class ConnectionFailed(val reason: String) : BleEvent
        data class DiscoveryRetry(val attempt: Int, val max: Int) : BleEvent
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<CoolerDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<CoolerDevice>> = _discoveredDevices.asStateFlow()

    // -1 = desconocido todavía
    private val _fanSpeedPercent = MutableStateFlow(-1)
    val fanSpeedPercent: StateFlow<Int> = _fanSpeedPercent.asStateFlow()

    // Byte crudo de la característica de temperatura (00001015). La escala exacta
    // no está verificada aún; se expone cruda a propósito.
    private val _temperatureRaw = MutableStateFlow(-1)
    val temperatureRaw: StateFlow<Int> = _temperatureRaw.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BleEvent> = _events.asSharedFlow()

    @Volatile
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanTimeoutJob: Job? = null
    private var discoveryTimeoutJob: Job? = null

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile
    private var connectedCoolerDevice: CoolerDevice? = null
    @Volatile
    private var isScanning = false
    @Volatile
    private var isConnected = false
    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private val deviceList = CoolerDeviceList()

    @Volatile
    private var fanCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var lightCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var autoModeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var temperatureCharacteristic: BluetoothGattCharacteristic? = null

    // ------------------------------------------------ cola de operaciones GATT
    // Un solo op en vuelo: Android descarta escrituras si se lanzan en ráfaga
    // (síntoma visto en vivo: writeCharacteristic=false tras CCCD/read).
    private sealed interface GattOp {
        val label: String
        fun execute(gatt: BluetoothGatt): Boolean
    }

    private data class WriteCharacteristicOp(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        override val label: String
    ) : GattOp {
        @Suppress("DEPRECATION")
        override fun execute(gatt: BluetoothGatt): Boolean {
            characteristic.value = value
            return gatt.writeCharacteristic(characteristic)
        }
    }

    private data class WriteDescriptorOp(
        val descriptor: BluetoothGattDescriptor,
        val value: ByteArray,
        override val label: String
    ) : GattOp {
        @Suppress("DEPRECATION")
        override fun execute(gatt: BluetoothGatt): Boolean {
            descriptor.value = value
            return gatt.writeDescriptor(descriptor)
        }
    }

    private data class ReadCharacteristicOp(
        val characteristic: BluetoothGattCharacteristic,
        override val label: String
    ) : GattOp {
        override fun execute(gatt: BluetoothGatt): Boolean =
            gatt.readCharacteristic(characteristic)
    }

    private val opQueue = ArrayDeque<GattOp>()
    private var opInProgress = false
    private var opTimeoutJob: Job? = null
    private val opQueueLock = Any()
    private const val OP_TIMEOUT_MS = 3000L

    @Volatile
    private var scanTargetMac: String? = null
    @Volatile
    private var scanTargetType: CoolerDeviceType? = null
    @Volatile
    private var discoveryRetryCount = 0
    private val maxDiscoveryRetries = 2
    @Volatile
    private var reconnectAttempts = 0
    @Volatile
    private var reconnectBackoffMs = 2000L

    val connectedDevice: CoolerDevice? get() = connectedCoolerDevice
    val discoveryTimedOut: Boolean get() = discoveryTimedOutFlag
    @Volatile private var discoveryTimedOutFlag = false

    /** Inicialización idempotente; llamar desde Application/MainActivity/CoolerService. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    // ------------------------------------------------ escaneo y conexión

    /**
     * Inicia el escaneo BLE (sin filtros de sistema; la detección se hace en el callback).
     * @param lowLatency true para UI interactiva (LOW_LATENCY), false para segundo plano (LOW_POWER)
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        lowLatency: Boolean = true,
        targetMac: String? = null,
        targetType: CoolerDeviceType? = null,
        resetAttempts: Boolean = true
    ) {
        val context = appContext
            ?: run {
                Log.e(TAG, "init() no llamado: falta contexto")
                return
            }
        if (isScanning) {
            Log.w(TAG, "Escaneo ya en curso")
            return
        }
        if (!BlePermissionManager.hasCriticalBlePermissions(context)) {
            Log.e(TAG, "Permisos BLE críticos faltantes")
            return
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth desactivado o no disponible")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BluetoothLeScanner no disponible")
            return
        }

        bluetoothLeScanner = scanner
        if (resetAttempts) {
            reconnectAttempts = 0
            reconnectBackoffMs = 2000L
        }
        scanTargetMac = targetMac
        scanTargetType = targetType
        deviceList.clear()
        _discoveredDevices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        _state.value = ConnectionState.SCANNING
        isScanning = true

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando escaneo: ${e.message}", e)
            isScanning = false
            _state.value = ConnectionState.DISCONNECTED
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning && !isConnected) {
                Log.d(TAG, "Timeout de escaneo ($SCAN_TIMEOUT_MS ms), deteniendo")
                stopScan()
                // Si este escaneo era un intento de reconexión (target concreto),
                // seguir con los siguientes en vez de rendirse aquí
                if (scanTargetMac != null && scanTargetType != null) {
                    // scheduleReconnect decide por contador (o emite ConnectionFailed al agotarse)
                    scheduleReconnect()
                }
            }
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            @SuppressLint("MissingPermission")
            bluetoothLeScanner?.let { scanner ->
                BleConnectionHelper.safeStopScan(scanner, scanCallback, TAG)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo escaneo: ${e.message}")
        } finally {
            isScanning = false
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            if (_state.value == ConnectionState.SCANNING) {
                _state.value = ConnectionState.DISCONNECTED
            }
        }
    }

    /** Conecta con un dispositivo concreto (resultado de escaneo). */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, deviceType: CoolerDeviceType, rssi: Int = -1) {
        val context = appContext ?: return
        connectGatt(
            device = device,
            deviceType = deviceType,
            rssi = rssi,
            context = context
        )
    }

    /** Conecta directamente por MAC (p. ej. tras relanzar el servicio). La MAC resuelve el tipo de dirección BLE. */
    @SuppressLint("MissingPermission")
    fun connectByMac(macAddress: String, deviceType: CoolerDeviceType) {
        val context = appContext ?: return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bluetoothManager.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(macAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Dirección MAC inválida: $macAddress")
            return
        }
        connectGatt(device = device, deviceType = deviceType, rssi = -1, context = context)
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(
        device: BluetoothDevice,
        deviceType: CoolerDeviceType,
        rssi: Int,
        context: Context
    ) {
        stopScan()
        closeGattInternal()

        _state.value = ConnectionState.CONNECTING
        connectedCoolerDevice = CoolerDevice(
            bluetoothDevice = device,
            deviceType = deviceType,
            rssi = rssi
        )
        discoveryRetryCount = 0
        discoveryTimedOutFlag = false

        bluetoothGatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Error connectGatt: ${e.message}", e)
            connectedCoolerDevice = null
            _state.value = ConnectionState.DISCONNECTED
            _events.tryEmit(BleEvent.ConnectionFailed("No se pudo iniciar la conexión"))
            null
        }
    }

    /** Desconexión intencional. Cancela reintentos. */
    fun disconnect() {
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // bloquea reintentos
        closeGattInternal()
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun closeGattInternal() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        val device = connectedCoolerDevice
        connectedCoolerDevice = null
        isConnected = false
        drainOpQueue()
        clearCharacteristics()
        try {
            gatt?.let { BleConnectionHelper.safeCloseGatt(it, TAG) }
        } catch (e: Exception) {
            Log.w(TAG, "Error cerrando GATT: ${e.message}")
        }
        if (device != null) {
            Log.d(TAG, "GATT cerrado (${device.address})")
        }
    }

    private fun clearCharacteristics() {
        fanCharacteristic = null
        lightCharacteristic = null
        autoModeCharacteristic = null
        temperatureCharacteristic = null
        _fanSpeedPercent.value = -1
        _temperatureRaw.value = -1
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
    }

    /** Reconexión solicitada externamente (p.ej. al restaurar estado tras recrear el servicio). */
    fun requestReconnect(macAddress: String, deviceType: CoolerDeviceType) {
        Log.d(TAG, "Reconexión solicitada para $macAddress (${deviceType.deviceName})")
        reconnectAttempts = 0
        reconnectBackoffMs = 2000L
        startScan(lowLatency = false, targetMac = macAddress, targetType = deviceType)
    }

    // ------------------------------------------------ escritura de comandos

    /** Escribe la velocidad (0-100 %). Devuelve true si se encoló la escritura. */
    fun setFanSpeed(percent: Int): Boolean {
        val characteristic = fanCharacteristic ?: return false
        val raw = com.hitomatito.redmagicooler.model.CoolerBleConstants
            .percentageToRaw(percent.coerceIn(0, 100))
        return enqueueOp(
            WriteCharacteristicOp(characteristic, byteArrayOf(raw.toByte()), "fan speed $percent%")
        )
    }

    /** Escribe un comando RGB [efecto][R][G][B]. Devuelve true si se encoló. */
    fun setRGBLight(effect: LightEffect, red: Int = 0, green: Int = 0, blue: Int = 0): Boolean {
        val characteristic = lightCharacteristic ?: return false
        val command = byteArrayOf(
            effect.code,
            red.toByte(),
            green.toByte(),
            blue.toByte()
        )
        return enqueueOp(
            WriteCharacteristicOp(characteristic, command, "RGB ${effect.name}")
        )
    }

    /**
     * Escribe el comando de modo automático (0x00 según la app oficial).
     * Devuelve true si se encoló.
     */
    fun setAutoMode(command: Byte = CoolerBleConstants.AUTO_MODE_COMMAND): Boolean {
        val characteristic = autoModeCharacteristic ?: return false
        return enqueueOp(
            WriteCharacteristicOp(characteristic, byteArrayOf(command), "auto mode 0x%02x".format(command))
        )
    }

    fun isAutoModeAvailable(): Boolean = autoModeCharacteristic != null

    /** Encola una operación en la cola FIFO del GATT. Devuelve false si no hay conexión. */
    private fun enqueueOp(op: GattOp): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (appContext?.let { BlePermissionManager.hasBluetoothConnectPermission(it) } != true) {
            Log.w(TAG, "Sin permiso BLUETOOTH_CONNECT para ${op.label}")
            return false
        }
        synchronized(opQueueLock) {
            opQueue.addLast(op)
            Log.d(TAG, "Op encolada: ${op.label} (pendientes=${opQueue.size})")
        }
        pumpOpQueue()
        return true
    }

    private fun pumpOpQueue() {
        val gatt = bluetoothGatt ?: return
        val op: GattOp? = synchronized(opQueueLock) {
            if (opInProgress) return
            val next = opQueue.firstOrNull()
            if (next != null) {
                opInProgress = true
            }
            next
        }
        if (op == null) return

        val ok = try {
            op.execute(gatt)
        } catch (e: android.os.DeadObjectException) {
            Log.e(TAG, "DeadObjectException en ${op.label}")
            // La op actual se descarta; el estado se limpia en handleDeadObjectException()
            handleDeadObjectException()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException en ${op.label}: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error en op GATT ${op.label}: ${e.message}")
            false
        }

        if (!ok) {
            Log.w(TAG, "Op GATT descartada o fallida: ${op.label} (avanzando cola)")
            completeOp()
        } else {
            // Respaldo: si el callback no llega, avanzar igualmente
            opTimeoutJob?.cancel()
            opTimeoutJob = scope.launch {
                delay(OP_TIMEOUT_MS)
                if (opInProgress) {
                    Log.w(TAG, "Timeout de op GATT (${op.label}); forzando avance")
                    completeOp()
                }
            }
        }
    }

    private fun completeOp() {
        synchronized(opQueueLock) {
            if (opQueue.isNotEmpty()) {
                opQueue.removeFirst()
            }
            opInProgress = false
        }
        opTimeoutJob?.cancel()
        pumpOpQueue()
    }

    private fun drainOpQueue() {
        synchronized(opQueueLock) {
            opQueue.clear()
            opInProgress = false
        }
        opTimeoutJob?.cancel()
    }

    /** Aplica una configuración RGB pendiente una vez conectado (uso interno de CoolerService). */
    fun applyPendingRGB(config: RGBConfig) {
        scope.launch {
            delay(1000)
            setRGBLight(config.effect, config.red, config.green, config.blue)
        }
    }

    /** Lee la velocidad actual del ventilador. La respuesta llega como evento FanSpeedChanged. */
    fun readFanSpeed(): Boolean {
        val characteristic = fanCharacteristic ?: return false
        return enqueueOp(ReadCharacteristicOp(characteristic, "leer fan speed"))
    }

    // ------------------------------------------------ callbacks GATT

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    reconnectAttempts = 0
                    reconnectBackoffMs = 2000L
                    _state.value = ConnectionState.CONNECTED
                    Log.d(TAG, "Conectado a ${connectedCoolerDevice?.address}")
                    try {
                        @SuppressLint("MissingPermission")
                        if (appContext?.let { BlePermissionManager.hasBluetoothConnectPermission(it) } == true) {
                            // Sin bonding: el cooler funciona sin emparejamiento (como la app oficial)
                            gatt.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Error discoverServices: ${e.message}")
                    }
                    scheduleDiscoveryTimeout(gatt)
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasIntentional = status == BluetoothGatt.GATT_SUCCESS
                    Log.d(TAG, "Desconectado (status=$status)")
                    clearCharacteristics()
                    isConnected = false
                    _state.value = ConnectionState.DISCONNECTED
                    bluetoothGatt = null
                    connectedCoolerDevice = null
                    _events.tryEmit(BleEvent.Disconnected(status, willReconnect = !wasIntentional))
                    if (!wasIntentional) {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = null
            if (discoveryTimedOutFlag) {
                Log.w(TAG, "onServicesDiscovered ignorado - discovery con timeout ya gestionado")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Error descubriendo servicios: $status")
                _events.tryEmit(BleEvent.ConnectionFailed("Error descubriendo servicios ($status)"))
                return
            }
            val services = gatt.services ?: emptyList()
            if (services.isEmpty() && discoveryRetryCount < maxDiscoveryRetries) {
                discoveryRetryCount++
                _events.tryEmit(BleEvent.DiscoveryRetry(discoveryRetryCount, maxDiscoveryRetries))
                scope.launch {
                    delay(1500L)
                    if (!isConnected) return@launch
                    try {
                        @SuppressLint("MissingPermission")
                        if (appContext?.let { BlePermissionManager.hasBluetoothConnectPermission(it) } == true) {
                            gatt.discoverServices()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error en reintento de discovery: ${e.message}")
                    }
                }
                return
            }
            discoveryRetryCount = 0
            handleServicesDiscovered(gatt, services)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic status=$status")
            } else {
                @Suppress("DEPRECATION")
                val data = characteristic.value
                val hex = data?.joinToString(" ") { "%02x".format(it) } ?: ""
                Log.d(TAG, "writeCharacteristic OK: ${characteristic.uuid} [$hex]")
            }
            completeOp()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeDescriptor status=$status")
            } else {
                Log.d(TAG, "writeDescriptor OK: ${descriptor.uuid}")
            }
            completeOp()
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completeOp()
            handleCharacteristicValue(characteristic)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            @Suppress("DEPRECATION")
            characteristic.value = value
            handleCharacteristicValue(characteristic)
        }
    }

    private fun scheduleDiscoveryTimeout(gatt: BluetoothGatt) {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (!isConnected || fanCharacteristic != null) return@launch
            discoveryTimedOutFlag = true
            Log.w(TAG, "Timeout de discovery de servicios ($DISCOVERY_TIMEOUT_MS ms)")
            try {
                @Suppress("DEPRECATION")
                BleConnectionHelper.safeCloseGatt(gatt, TAG)
            } catch (e: Exception) {
                Log.w(TAG, "Error cerrando GATT tras timeout: ${e.message}")
            }
            bluetoothGatt = null
            isConnected = false
            _state.value = ConnectionState.DISCONNECTED
            _events.tryEmit(BleEvent.Disconnected(-1, willReconnect = true))
            _events.tryEmit(BleEvent.ConnectionFailed("El cooler no responde al conectar"))
            scheduleReconnect()
        }
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt, services: List<BluetoothGattService>) {
        Log.d(TAG, "Servicios descubiertos: ${services.size}")
        services.forEach { service ->
            Log.d(TAG, "Servicio: ${service.uuid}")
            service.characteristics.forEach { c -> Log.d(TAG, "    -> ${c.uuid}") }
        }

        val fanService = gatt.getService(CoolerBleConstants.FAN_SERVICE_UUID)
        if (fanService != null) {
            fanCharacteristic = fanService.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)
            lightCharacteristic = fanService.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)
            autoModeCharacteristic = fanService.getCharacteristic(CoolerBleConstants.AUTO_MODE_CONTROL_UUID)
            temperatureCharacteristic = fanService.getCharacteristic(CoolerBleConstants.TEMPERATURE_NOTIFICATION_UUID)
        } else {
            // Fallback: buscar en todos los servicios
            for (service in services) {
                service.getCharacteristic(CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID)?.let {
                    fanCharacteristic = it
                }
                service.getCharacteristic(CoolerBleConstants.LIGHT_CONTROL_UUID)?.let {
                    lightCharacteristic = it
                }
                service.getCharacteristic(CoolerBleConstants.AUTO_MODE_CONTROL_UUID)?.let {
                    autoModeCharacteristic = it
                }
                service.getCharacteristic(CoolerBleConstants.TEMPERATURE_NOTIFICATION_UUID)?.let {
                    temperatureCharacteristic = it
                }
                if (fanCharacteristic != null && lightCharacteristic != null) break
            }
        }

        if (fanCharacteristic == null) {
            Log.e(TAG, "Fan characteristic NO encontrada en ningún servicio")
            _events.tryEmit(BleEvent.ConnectionFailed("El cooler no expuso el servicio del ventilador"))
            closeGattInternal()
            _state.value = ConnectionState.DISCONNECTED
            return
        }

        Log.i(TAG, "═══════════════════════════════════════════════")
        connectedCoolerDevice?.let { device ->
            Log.i(TAG, "PERFIL ACTIVO: ${device.deviceType.deviceName}")
            Log.i(TAG, "  Generación: ${device.deviceType.generation}")
            Log.i(TAG, "  Manufacturer subTypes: ${device.deviceType.manufacturerSubTypes}")
            Log.i(TAG, "  Señal: ${device.signalQuality}% (${device.rssi} dBm)")
        }
        Log.i(TAG, "═══════════════════════════════════════════════")

        // Habilitar notificaciones con escritura CCCD (esto es lo que faltaba:
        // setCharacteristicNotification NO suscribe en el periférico).
        // Los CCCDs y la lectura inicial se encolan: la cola FIFO garantiza
        // una operación GATT a la vez sin necesidad de delays artificiales.
        enableNotifications(gatt, temperatureCharacteristic)
        enableNotifications(gatt, fanCharacteristic)
        enqueueOp(ReadCharacteristicOp(fanCharacteristic!!, "leer fan speed inicial"))
        connectedCoolerDevice?.let { device ->
            _events.tryEmit(BleEvent.Connected(device))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        try {
            val ok = gatt.setCharacteristicNotification(characteristic, true)
            Log.d(TAG, "Notificaciones activadas en ${characteristic.uuid}: $ok")
            val cccd = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
            if (cccd != null) {
                // La escritura del CCCD entra en la cola FIFO (respeta el op en vuelo)
                enqueueOp(
                    WriteDescriptorOp(
                        cccd,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        "CCCD ${characteristic.uuid}"
                    )
                )
            } else {
                Log.w(TAG, "CCCD no encontrado en ${characteristic.uuid}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException habilitando notificaciones: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error habilitando notificaciones: ${e.message}")
        }
    }

    private fun handleCharacteristicValue(characteristic: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        val data = characteristic.value ?: return
        if (data.isEmpty()) return
        when (characteristic.uuid) {
            CoolerBleConstants.FAN_SPEED_CHARACTERISTIC_UUID -> {
                val raw = data[0].toInt() and 0xFF
                val percent = com.hitomatito.redmagicooler.model.CoolerBleConstants.rawToPercentage(raw)
                _fanSpeedPercent.value = percent
                _events.tryEmit(BleEvent.FanSpeedChanged(raw, percent))
                Log.d(TAG, "Fan speed: raw=$raw → $percent%")
            }
            CoolerBleConstants.TEMPERATURE_NOTIFICATION_UUID -> {
                val raw = data[0].toInt() and 0xFF
                _temperatureRaw.value = raw
                _events.tryEmit(BleEvent.TemperatureRaw(raw))
                Log.d(TAG, "Temperature raw: $raw")
            }
            else -> {
                Log.d(TAG, "Notification en ${characteristic.uuid}: ${data.joinToString(" ") { "%02x".format(it) }}")
            }
        }
    }

    // ------------------------------------------------ escaneo (callback)

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name
            val rssi = result.rssi

            val targetMac = scanTargetMac
            if (targetMac != null && !device.address.equals(targetMac, ignoreCase = true)) {
                return
            }

            // Detección por protocolo real: service 00004a41 + manufacturer 2250
            // (mainType=5, subType=generación); nombre como arbitro final.
            val type = CoolerDeviceType.fromScanRecord(result.scanRecord)
                ?: scanTargetType?.takeIf { it.matchesBleName(deviceName) }
                ?: return

            val coolerDevice = CoolerDevice(
                bluetoothDevice = device,
                deviceType = type,
                rssi = rssi
            )
            deviceList.addOrUpdate(coolerDevice)
            _discoveredDevices.value = deviceList.getAll()

            // Conectar al primer cooler encontrado (comportamiento de la app original:
            // si la UI no pidió MAC concreta, acepta cualquier cooler válido)
            if (_state.value == ConnectionState.SCANNING) {
                Log.d(TAG, "Cooler encontrado: conectando a ${device.address}")
                stopScan()
                connect(device, type, rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _state.value = ConnectionState.DISCONNECTED
            Log.e(TAG, "Escaneo falló: código $errorCode")
            _events.tryEmit(BleEvent.ConnectionFailed("Error de escaneo ($errorCode)"))
        }
    }

    /** Lista actual de coolers descubiertos (por señal descendente). */
    fun getDiscoveredDevices(): List<CoolerDevice> = deviceList.getAll()

    // ------------------------------------------------ reconexión

    private fun scheduleReconnect() {
        val targetMac = scanTargetMac
        val targetType = scanTargetType
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Máximo de reintentos de reconexión alcanzado")
            _events.tryEmit(BleEvent.ConnectionFailed("No se pudo reconectar tras $MAX_RECONNECT_ATTEMPTS intentos"))
            return
        }
        if (targetMac != null && targetType != null) {
            reconnectAttempts++
            val backoff = reconnectBackoffMs
            Log.d(TAG, "Programando reconexión $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS en ${backoff}ms")
            scope.launch {
                delay(backoff)
                startScan(lowLatency = false, targetMac = targetMac, targetType = targetType, resetAttempts = false)
            }
            reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30000L)
            return
        }
        // Sin destino conocido: permitir un único escaneo genérico de reintento
        reconnectAttempts++
        scope.launch {
            delay(reconnectBackoffMs)
            startScan(lowLatency = false, resetAttempts = false)
        }
        reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(30000L)
    }

    private fun handleDeadObjectException() {
        val context = appContext
        if (context == null) {
            closeGattInternal()
            scheduleReconnect()
            return
        }
        val prefs = context.getSharedPreferences(PREFS_DEAD_OBJECT, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastDate = prefs.getString(KEY_DEAD_OBJECT_DATE, "")
        val count = if (lastDate == today) prefs.getInt(KEY_DEAD_OBJECT_COUNT, 0) else 0

        if (count >= MAX_DEAD_OBJECT_PER_DAY) {
            Log.e(TAG, "Límite diario de DeadObjectException alcanzado ($MAX_DEAD_OBJECT_PER_DAY)")
            _events.tryEmit(BleEvent.ConnectionFailed("El cooler se desconecta frecuentemente. Reinícialo o el Bluetooth."))
            closeGattInternal()
            return
        }

        prefs.edit()
            .putInt(KEY_DEAD_OBJECT_COUNT, count + 1)
            .putString(KEY_DEAD_OBJECT_DATE, today)
            .apply()

        Log.w(TAG, "DeadObjectException (${count + 1}/$MAX_DEAD_OBJECT_PER_DAY hoy): cerrando GATT y reconectando")
        closeGattInternal()
        scheduleReconnect()
    }

    fun isHealthy(): Boolean = isConnected && fanCharacteristic != null

    fun currentConnectedAddress(): String? = connectedCoolerDevice?.address

    /** Vuelve a detectar un dispositivo antes de conectar. */
    fun setScanTarget(mac: String?, type: CoolerDeviceType?) {
        scanTargetMac = mac
        scanTargetType = type
    }
}
