package com.hitomatito.redmagicooler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.hitomatito.redmagicooler.data.ThermalZoneProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Monitor de temperatura del dispositivo para controlar el cooler.
 *
 * Dueño de la lectura de sensores y del bucle; las decisiones puras viven en [ThermalLogic].
 */
class ThermalMonitor(
    private val context: Context,
    private val noisePreferenceProvider: () -> Int = { 50 },
) {
    companion object {
        private const val TAG = "ThermalMonitor"

        const val TEMP_SAFE = ThermalLogic.TEMP_SAFE
        const val TEMP_WARM = ThermalLogic.TEMP_WARM
        const val TEMP_HOT = ThermalLogic.TEMP_HOT
        const val TEMP_CRITICAL = ThermalLogic.TEMP_CRITICAL

        private const val DOZE_PAUSE_MS = 300_000L
        private const val ERROR_BACKOFF_MS = 30_000L
    }

    private var monitoringJob: Job? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // Un solo lock protege el estado de control compartido entre el bucle IO y las lecturas puntuales.
    private val stateLock = Any()
    private var controlState = ControlState()

    private data class ThermalZoneEntry(val tempFile: File, val type: String)

    private val thermalZoneLock = Any()
    private val thermalZoneCache = mutableMapOf<Int, ThermalZoneEntry>()
    private var thermalCacheInitialized = false

    @Volatile
    private var zoneInventory = ""

    private val zoneProfile: ThermalZoneProfile by lazy {
        // También el repositorio aquí: getSharedPreferences lee disco y el monitor
        // se construye en el onCreate del servicio (hilo principal).
        val zoneProfileRepository = ThermalZoneProfileRepository(context)
        val modelKey = ThermalZoneProfileRepository.currentModelKey()
        val defaults = ThermalZoneDefaults.forModel(modelKey)
        val saved = zoneProfileRepository.load(modelKey)
        ThermalZoneProfile(
            byIndex = defaults.byIndex + saved.byIndex,
            byType = defaults.byType + saved.byType,
        )
    }

    data class ThermalData(
        val batteryTemp: Float = 0f,
        val cpuTemp: Float = 0f,
        val gpuTemp: Float = 0f,
        val skinTemp: Float = 0f,
        val maxTemp: Float = 0f,
        val thermalStatus: Int = 0,
        val recommendedSpeed: Int = 0,
        val tempLevel: TempLevel = TempLevel.SAFE,
        val tempSources: String = "",
        val hardwarePeakTemp: Float = 0f,
        val intervalCount: Int = 0,
        val charging: Boolean = false,
        val screenInteractive: Boolean = true,
        val controlSource: String = "",
        val riskConfirmed: Boolean = false,
        val zoneInventory: String = "",
        val noisePreference: Int = 50,
    )

    enum class TempLevel {
        SAFE,
        WARM,
        HOT,
        CRITICAL,
    }

    /** Inicia el monitoreo de temperatura. */
    fun startMonitoring(
        scope: CoroutineScope,
        onUpdate: (ThermalData) -> Unit,
    ) {
        stopMonitoring()

        monitoringJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Iniciando monitoreo de temperatura")
            var intervalCounter = 0

            while (isActive) {
                try {
                    val isIdle = powerManager?.isDeviceIdleMode == true || powerManager?.isPowerSaveMode == true
                    if (isIdle) {
                        Log.d(TAG, "Dispositivo en modo idle (Doze/Standby), pausando monitoreo por 5 minutos")
                        delay(DOZE_PAUSE_MS)
                        continue
                    }

                    intervalCounter++
                    val sample = sampleSensors()
                    val decision = evaluate(sample, advance = true)
                    onUpdate(toThermalData(sample, decision, intervalCounter))
                    delay(decision.adaptiveIntervalMs)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Monitoreo cancelado: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo: ${e.message}", e)
                    delay(ERROR_BACKOFF_MS)
                }
            }
        }
    }

    /** Detiene el monitoreo de temperatura. */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "Monitoreo de temperatura detenido")
    }

    /**
     * Instantánea térmica sin avanzar la rampa ni la histéresis: calcula la decisión
     * sobre el estado actual pero no la conserva, así que es segura desde cualquier hilo.
     */
    fun getCurrentThermalData(intervalCount: Int = 0): ThermalData {
        val sample = sampleSensors()
        val decision = evaluate(sample, advance = false)
        return toThermalData(sample, decision, intervalCount)
    }

    private fun evaluate(sample: ThermalSample, advance: Boolean): ThermalDecision =
        synchronized(stateLock) {
            val nowMs = SystemClock.elapsedRealtime()
            val tuning = ThermalTuning(noisePreferenceProvider())
            val decision = decide(controlState, sample, nowMs, advance, tuning)
            if (advance) controlState = decision.state
            decision
        }

    private fun sampleSensors(): ThermalSample {
        val battery = readBatteryState()
        val screenInteractive = powerManager?.isInteractive ?: true
        val readings = readThermalZoneReadings()
        val profile = zoneProfile
        val roles = aggregateZoneRoles(readings, profile)

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getThermalStatus()
        } else {
            0
        }

        val otherZones = readings.filter { reading ->
            val role = resolveZoneRole(reading, profile)
            role != ThermalZoneRole.CPU &&
                role != ThermalZoneRole.GPU &&
                role != ThermalZoneRole.SKIN
        }
        val otherZonesText = if (otherZones.isEmpty()) {
            ""
        } else {
            ", Otros: ${otherZones.joinToString { "${it.type}=${"%.1f".format(it.tempC)}" }}"
        }

        return ThermalSample(
            batteryTemp = battery.temperature,
            cpuTemp = roles[ThermalZoneRole.CPU] ?: 0f,
            gpuTemp = roles[ThermalZoneRole.GPU] ?: 0f,
            skinTemp = roles[ThermalZoneRole.SKIN] ?: 0f,
            hardwarePeakTemp = hardwarePeakForRoles(roles),
            thermalStatus = thermalStatus,
            charging = battery.charging,
            screenInteractive = screenInteractive,
            otherZonesText = otherZonesText,
        )
    }

    private fun toThermalData(
        sample: ThermalSample,
        decision: ThermalDecision,
        intervalCount: Int,
    ): ThermalData {
        val sources = buildString {
            append("Battery: ${"%.1f".format(sample.batteryTemp)}°C")
            if (sample.cpuTemp > 0) append(", CPU: ${"%.1f".format(sample.cpuTemp)}°C")
            if (sample.gpuTemp > 0) append(", GPU: ${"%.1f".format(sample.gpuTemp)}°C")
            if (sample.skinTemp > 0) append(", Skin: ${"%.1f".format(sample.skinTemp)}°C")
            val riskLabel = if (decision.isRiskConfirmed) ", risk" else ""
            append(", Control: ${"%.1f".format(decision.maxTemp)}°C (${decision.controlSource}$riskLabel)")
            if (sample.charging) append(", Carga: sí")
            append(", Pantalla: ${if (sample.screenInteractive) "on" else "off"}")
            append(sample.otherZonesText)
        }

        return ThermalData(
            batteryTemp = sample.batteryTemp,
            cpuTemp = sample.cpuTemp,
            gpuTemp = sample.gpuTemp,
            skinTemp = sample.skinTemp,
            maxTemp = decision.maxTemp,
            thermalStatus = sample.thermalStatus,
            recommendedSpeed = decision.speed,
            tempLevel = decision.tempLevel,
            tempSources = sources,
            hardwarePeakTemp = sample.hardwarePeakTemp,
            intervalCount = intervalCount,
            charging = sample.charging,
            screenInteractive = sample.screenInteractive,
            controlSource = decision.controlSource,
            riskConfirmed = decision.isRiskConfirmed,
            zoneInventory = zoneInventory,
            noisePreference = noisePreferenceProvider().coerceIn(0, 100),
        )
    }

    private data class BatteryState(val temperature: Float, val charging: Boolean)

    /** Temperatura y estado de carga en una sola lectura del sticky broadcast. */
    private fun readBatteryState(): BatteryState {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0f
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = plugged != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BatteryState(temp, charging)
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo estado de batería: ${e.message}")
            BatteryState(0f, false)
        }
    }

    /** Lee las zonas térmicas del sistema (ruta y tipo cacheados en el primer uso). */
    private fun readThermalZoneReadings(): List<ThermalZoneReading> {
        val cached = synchronized(thermalZoneLock) {
            if (!thermalCacheInitialized) initThermalCache()
            thermalZoneCache.entries.map { it.key to it.value }
        }

        val readings = mutableListOf<ThermalZoneReading>()
        for ((index, entry) in cached) {
            try {
                val raw = entry.tempFile.readText().trim().toFloatOrNull() ?: continue
                if (raw <= 0f) continue
                val temp = if (raw > 200f) raw / 1000f else raw
                if (isPlausibleZoneTemp(temp)) {
                    readings.add(ThermalZoneReading(index, entry.type, temp))
                }
            } catch (_: Exception) {
                // Una zona ilegible no invalida las demás.
            }
        }

        return readings
    }

    /** Inicializa la caché de zonas térmicas válidas (una sola vez). */
    private fun initThermalCache() {
        // Se enumeran las zonas del directorio en lugar de sondear un rango fijo:
        // algunos SoC exponen más de 30 zonas y el rango las descartaba.
        val thermalRoot = File("/sys/class/thermal")
        val zoneName = Regex("thermal_zone(\\d+)")
        val listed = thermalRoot.listFiles()
        var candidates: List<Pair<Int, File>> = listed
            ?.mapNotNull { entry ->
                zoneName.matchEntire(entry.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to entry }
            }
            ?.sortedBy { (index, _) -> index }
            ?: emptyList()
        if (candidates.isEmpty()) {
            // SELinux puede negar el listado del directorio raiz permitiendo el acceso
            // a las zonas, y algunos kernels exoticos devuelven el listado vacio: en
            // ambos casos se sondea un rango amplio en lugar de quedarse sin zonas.
            Log.w(TAG, "Listado de /sys/class/thermal vacio o denegado; tanteando zonas 0..63")
            candidates = (0..63).map { it to File(thermalRoot, "thermal_zone$it") }
        }
        for ((index, zoneDir) in candidates) {
            val tempFile = File(zoneDir, "temp")
            val typeFile = File(zoneDir, "type")
            try {
                if (tempFile.exists() && tempFile.canRead() && typeFile.exists() && typeFile.canRead()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.isNotEmpty()) {
                        thermalZoneCache[index] = ThermalZoneEntry(tempFile, type)
                    }
                }
            } catch (_: Exception) {
                // Ignorar zonas inaccesibles.
            }
        }

        thermalCacheInitialized = true
        zoneInventory = zoneInventoryText(
            thermalZoneCache.mapValues { it.value.type },
            zoneProfile,
        )
        Log.d(TAG, "Caché térmico inicializado: ${thermalZoneCache.size} zonas válidas encontradas")
        Log.d(TAG, "Zonas: $zoneInventory")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getThermalStatus(): Int {
        return try {
            powerManager?.currentThermalStatus ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo estado térmico: ${e.message}")
            0
        }
    }
}
