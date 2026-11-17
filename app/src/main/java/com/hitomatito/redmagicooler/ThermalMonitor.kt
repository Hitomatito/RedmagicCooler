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
class ThermalMonitor(private val context: Context) {
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

    private val thermalZoneLock = Any()
    private val thermalZoneCache = mutableMapOf<Int, Pair<File, File>>()
    private var thermalCacheInitialized = false

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

    /** Intervalo adaptativo de sondeo, delegado a la lógica pura. */
    fun getAdaptiveInterval(tempLevel: TempLevel, hardwarePeakTemp: Float = 0f): Long =
        adaptiveInterval(tempLevel, hardwarePeakTemp)

    private fun evaluate(sample: ThermalSample, advance: Boolean): ThermalDecision =
        synchronized(stateLock) {
            val nowMs = SystemClock.elapsedRealtime()
            val decision = decide(controlState, sample, nowMs, advance)
            if (advance) controlState = decision.state
            decision
        }

    private fun sampleSensors(): ThermalSample {
        val batteryTemp = getBatteryTemperature()
        val zoneTemps = readThermalZones()

        val cpuTemp = zoneTemps["cpu"] ?: zoneTemps.entries
            .filter { it.key.contains("cpu") || it.key.contains("tsens") }
            .maxByOrNull { it.value }?.value ?: 0f

        val gpuTemp = zoneTemps["gpu"] ?: zoneTemps.entries
            .filter { it.key.contains("gpu") || it.key.contains("kgsl") }
            .maxByOrNull { it.value }?.value ?: 0f

        val skinTemp = zoneTemps["skin"] ?: zoneTemps["case"] ?:
            zoneTemps.entries.filter { it.key.contains("skin") || it.key.contains("case") }
                .maxByOrNull { it.value }?.value ?: 0f

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getThermalStatus()
        } else {
            0
        }

        val otherZones = zoneTemps.filter { (key, _) ->
            val normalized = key.lowercase()
            !normalized.contains("cpu") && !normalized.contains("tsens") &&
                !normalized.contains("gpu") && !normalized.contains("kgsl") &&
                !normalized.contains("skin") && !normalized.contains("case")
        }
        val otherZonesText = if (otherZones.isEmpty()) {
            ""
        } else {
            ", Otros: ${otherZones.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}" }}"
        }

        return ThermalSample(
            batteryTemp = batteryTemp,
            cpuTemp = cpuTemp,
            gpuTemp = gpuTemp,
            skinTemp = skinTemp,
            hardwarePeakTemp = hardwarePeak(zoneTemps),
            thermalStatus = thermalStatus,
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
            val controlLabel = when {
                decision.isRiskConfirmed -> "hardware"
                sample.skinTemp > 0f -> "skin"
                sample.batteryTemp > 0f -> "battery"
                else -> "none"
            }
            val riskLabel = if (decision.isRiskConfirmed) ", risk" else ""
            append(", Control: ${"%.1f".format(decision.maxTemp)}°C ($controlLabel$riskLabel)")
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
        )
    }

    /** Obtiene la temperatura de la batería. */
    private fun getBatteryTemperature(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo temperatura de batería: ${e.message}")
            0f
        }
    }

    /** Lee las zonas térmicas del sistema (ruta cacheada en el primer uso). */
    private fun readThermalZones(): Map<String, Float> {
        val cachedFiles = synchronized(thermalZoneLock) {
            if (!thermalCacheInitialized) initThermalCache()
            thermalZoneCache.values.toList()
        }

        val readings = mutableListOf<Pair<String, Float>>()
        for ((tempFile, typeFile) in cachedFiles) {
            try {
                val temp = tempFile.readText().trim().toFloatOrNull()
                val type = typeFile.readText().trim().lowercase()

                if (temp != null && temp > 0) {
                    val normalizedTemp = if (temp > 200) temp / 1000f else temp
                    if (normalizedTemp in 0f..100f) {
                        readings.add(type to normalizedTemp)
                    }
                }
            } catch (_: Exception) {
                // Una zona ilegible no invalida las demás.
            }
        }

        return aggregateThermalZones(readings)
    }

    /** Inicializa la caché de zonas térmicas válidas (una sola vez). */
    private fun initThermalCache() {
        for (i in 0..30) {
            val tempFile = File("/sys/class/thermal/thermal_zone$i/temp")
            val typeFile = File("/sys/class/thermal/thermal_zone$i/type")
            try {
                if (tempFile.exists() && tempFile.canRead() && typeFile.exists() && typeFile.canRead()) {
                    thermalZoneCache[i] = tempFile to typeFile
                }
            } catch (_: Exception) {
                // Ignorar zonas inaccesibles.
            }
        }

        thermalCacheInitialized = true
        Log.d(TAG, "Caché térmico inicializado: ${thermalZoneCache.size} zonas válidas encontradas")
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
