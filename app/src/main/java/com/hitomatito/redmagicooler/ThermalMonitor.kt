package com.hitomatito.redmagicooler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
 * Lee sensores y delega la política (curva, rampa, niveles) en [ThermalPolicy].
 */
class ThermalMonitor(private val context: Context) {
    companion object {
        private const val TAG = "ThermalMonitor"

        // Rangos de temperatura (°C) para calibración automática
        const val TEMP_SAFE = ThermalPolicy.TEMP_SAFE
        const val TEMP_WARM = ThermalPolicy.TEMP_WARM
        const val TEMP_HOT = ThermalPolicy.TEMP_HOT
        const val TEMP_CRITICAL = ThermalPolicy.TEMP_CRITICAL
    }

    private var monitoringJob: Job? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // Estado mutable de la rampa progresiva (lo avanza únicamente el bucle de monitoreo)
    private val rampState = ThermalPolicy.RampState()

    // Contador de intervalos de actualización
    private var intervalCounter: Int = 0

    // Inicio de un episodio de riesgo hardware sostenido
    private var hardwareRiskSinceMs: Long = 0L

    // Caché de rutas térmicas para optimización
    private val thermalZoneCache = mutableMapOf<Int, Pair<File, File>>()
    private var thermalCacheInitialized = false

    data class ThermalData(
        val batteryTemp: Float = 0f,          // Temperatura de batería
        val cpuTemp: Float = 0f,              // Temperatura de CPU (si disponible)
        val gpuTemp: Float = 0f,              // Temperatura de GPU (si disponible)
        val skinTemp: Float = 0f,             // Temperatura de la superficie del dispositivo
        val maxTemp: Float = 0f,              // Temperatura máxima detectada
        val thermalStatus: Int = 0,           // Estado térmico del sistema
        val recommendedSpeed: Int = 0,        // Velocidad recomendada del cooler (0-100%)
        val tempLevel: TempLevel = TempLevel.SAFE,
        val tempSources: String = "",         // Fuentes de temperatura detectadas (para debug)
        val hardwarePeakTemp: Float = 0f,     // Máximo detectado en sensores hardware
        val intervalCount: Int = 0            // Contador de intervalos de actualización
    )

    private enum class ControlSource(val label: String) {
        NONE("none"),
        SKIN("skin"),
        BATTERY("battery"),
        HARDWARE("hardware")
    }

    enum class TempLevel {
        SAFE,       // Verde: < 40°C
        WARM,       // Amarillo: 40-50°C
        HOT,        // Naranja: 50-55°C
        CRITICAL    // Rojo: >= 55°C
    }

    /**
     * Inicia el monitoreo de temperatura
     */
    fun startMonitoring(
        scope: CoroutineScope,
        onUpdate: (ThermalData) -> Unit
    ) {
        stopMonitoring()

        monitoringJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Iniciando monitoreo de temperatura")

            while (isActive) {
                try {
                    // Verificar si el dispositivo está en Doze mode o App Standby
                    val isIdle = powerManager?.isDeviceIdleMode == true || powerManager?.isPowerSaveMode == true
                    if (isIdle) {
                        Log.d(TAG, "Dispositivo en modo idle (Doze/Standby), pausando monitoreo por 5 minutos")
                        delay(300000L) // Pausar 5 minutos
                        continue
                    }

                    intervalCounter++
                    val thermalData = readCurrentThermalData(intervalCounter, advanceCriticalState = true)
                    onUpdate(thermalData)
                    val adaptiveInterval = ThermalPolicy.adaptiveIntervalMs(
                        thermalData.tempLevel,
                        thermalData.hardwarePeakTemp
                    )
                    delay(adaptiveInterval)
                } catch (e: CancellationException) {
                    Log.d(TAG, "Monitoreo cancelado: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo: ${e.message}", e)
                    delay(30000L) // Delay fijo en caso de error
                }
            }
        }
    }

    /**
     * Detiene el monitoreo de temperatura
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.d(TAG, "Monitoreo de temperatura detenido")
    }

    /**
     * Siembra la velocidad inicial de la rampa desde el dispositivo (p.ej. al calibrar).
     * Sin sembrado, la curva arranca en 0 % y el lazo automático bajaría bruscamente
     * aunque el cooler estuviera gobernando a otra velocidad.
     */
    fun seedRamp(speedPercent: Int) = rampState.seed(speedPercent, System.currentTimeMillis())

    /**
     * Obtiene datos térmicos actuales sin avanzar la rampa ni el estado de riesgo.
     * Solo lectura: ideal para instantáneas y calibración.
     */
    fun getCurrentThermalData(intervalCount: Int = 0): ThermalData =
        readCurrentThermalData(intervalCount, advanceCriticalState = false)

    /**
     * Lee sensores y opcionalmente avanza la histéresis de riesgo y la rampa.
     */
    private fun readCurrentThermalData(intervalCount: Int, advanceCriticalState: Boolean): ThermalData {
        val batteryTemp = getBatteryTemperature()
        val thermalZoneTemps = readThermalZones()
        val nowMs = System.currentTimeMillis()

        // Extraer temperaturas específicas
        val cpuTemp = thermalZoneTemps["cpu"] ?: thermalZoneTemps.entries
            .filter { it.key.contains("cpu") || it.key.contains("tsens") }
            .maxByOrNull { it.value }?.value ?: 0f

        val gpuTemp = thermalZoneTemps["gpu"] ?: thermalZoneTemps.entries
            .filter { it.key.contains("gpu") || it.key.contains("kgsl") }
            .maxByOrNull { it.value }?.value ?: 0f

        val skinTemp = thermalZoneTemps["skin"] ?: thermalZoneTemps["case"] ?:
            thermalZoneTemps.entries.filter { it.key.contains("skin") || it.key.contains("case") }
                .maxByOrNull { it.value }?.value ?: 0f

        val hardwareZoneTemps = thermalZoneTemps.filter { (key, _) ->
            val normalizedKey = key.lowercase()
            !normalizedKey.contains("skin") && !normalizedKey.contains("case")
        }
        val hardwarePeakTemp = hardwareZoneTemps.values.maxOrNull() ?: 0f

        // Temperatura principal: superficie; si no está disponible, batería.
        // Los picos hardware solo se usan tras riesgo sostenido.
        val controlBase = when {
            skinTemp > 0f -> skinTemp
            batteryTemp > 0f -> batteryTemp
            else -> 0f
        }
        val controlSource = when {
            skinTemp > 0f -> ControlSource.SKIN
            batteryTemp > 0f -> ControlSource.BATTERY
            else -> ControlSource.NONE
        }

        // Riesgo hardware: solo una señal alta y sostenida durante 45 s
        // justifica elevar la estrategia de emergencia.
        if (advanceCriticalState) {
            if (hardwarePeakTemp >= TEMP_HOT) {
                if (hardwareRiskSinceMs == 0L) {
                    hardwareRiskSinceMs = nowMs
                }
            } else {
                hardwareRiskSinceMs = 0L
            }
        }
        val isRiskConfirmed = hardwareRiskSinceMs != 0L &&
            nowMs - hardwareRiskSinceMs >= ThermalPolicy.CRITICAL_SUSTAINED_MS
        val maxTemp = if (isRiskConfirmed) hardwarePeakTemp else controlBase
        val displayedSource = if (isRiskConfirmed) ControlSource.HARDWARE else controlSource

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getThermalStatus()
        } else {
            0 // THERMAL_STATUS_NONE para versiones anteriores
        }

        val tempLevel = ThermalPolicy.tempLevel(maxTemp)

        val targetSpeed = ThermalPolicy.targetSpeed(maxTemp)
        val recommendedSpeed = if (advanceCriticalState) {
            rampState.advance(targetSpeed, isRiskConfirmed, nowMs)
        } else {
            rampState.currentSpeed // Instantánea sin efectos sobre la rampa
        }

        // Debug info de fuentes
        val sources = buildString {
            append("Battery: ${"%.1f".format(batteryTemp)}°C")
            if (cpuTemp > 0) append(", CPU: ${"%.1f".format(cpuTemp)}°C")
            if (gpuTemp > 0) append(", GPU: ${"%.1f".format(gpuTemp)}°C")
            if (skinTemp > 0) append(", Skin: ${"%.1f".format(skinTemp)}°C")
            val riskLabel = if (isRiskConfirmed) ", risk" else ""
            append(", Control: ${"%.1f".format(maxTemp)}°C (${displayedSource.label}$riskLabel)")
            if (thermalZoneTemps.isNotEmpty()) {
                val otherTemps = thermalZoneTemps.filter { it.key !in listOf("cpu", "gpu", "skin", "case") && !it.key.contains("cpu") && !it.key.contains("gpu") && !it.key.contains("skin") && !it.key.contains("case") }
                if (otherTemps.isNotEmpty()) {
                    append(", Otros: ${otherTemps.entries.joinToString { "${it.key}=${"%.1f".format(it.value)}" }}")
                }
            }
        }

        return ThermalData(
            batteryTemp = batteryTemp,
            cpuTemp = cpuTemp,
            gpuTemp = gpuTemp,
            skinTemp = skinTemp,
            maxTemp = maxTemp,
            thermalStatus = thermalStatus,
            recommendedSpeed = recommendedSpeed,
            tempLevel = tempLevel,
            tempSources = sources,
            hardwarePeakTemp = hardwarePeakTemp,
            intervalCount = intervalCount
        )
    }

    /**
     * Obtiene la temperatura de la batería
     */
    private fun getBatteryTemperature(): Float {
        return try {
            // Método usando Intent (funciona en todas las versiones de Android)
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f // Convertir de décimas a grados
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo temperatura de batería: ${e.message}")
            0f
        }
    }

    /**
     * Lee temperaturas de las zonas térmicas del sistema (optimizado con caché)
     * Estos archivos suelen contener la temperatura del CPU, GPU y otros componentes
     * Devuelve un mapa de tipo de zona a temperatura
     */
    private fun readThermalZones(): Map<String, Float> {
        val temps = mutableMapOf<String, Float>()

        // Inicializar caché en el primer uso
        if (!thermalCacheInitialized) {
            initThermalCache()
        }

        // Leer solo de zonas térmicas válidas en caché
        for ((_, filePair) in thermalZoneCache) {
            try {
                val (tempFile, typeFile) = filePair

                val tempStr = tempFile.readText().trim()
                val typeStr = typeFile.readText().trim().lowercase()

                val temp = tempStr.toFloatOrNull()

                if (temp != null && temp > 0) {
                    // Los valores pueden estar en miligramos (dividir por 1000) o ya en grados
                    val normalizedTemp = if (temp > 200) temp / 1000f else temp

                    // Filtrar valores absurdos (< 0 o > 100°C)
                    if (normalizedTemp in 0f..100f) {
                        temps[typeStr] = normalizedTemp
                    }
                }
            } catch (_: Exception) {
                // Ignorar errores de lectura silenciosamente
            }
        }

        return temps
    }

    /**
     * Inicializa el caché de zonas térmicas válidas (ejecutado una sola vez)
     */
    private fun initThermalCache() {
        for (i in 0..30) { // Buscar hasta 30 zonas térmicas
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            val typePath = "/sys/class/thermal/thermal_zone$i/type"

            try {
                val tempFile = File(tempPath)
                val typeFile = File(typePath)

                if (tempFile.exists() && tempFile.canRead() && typeFile.exists() && typeFile.canRead()) {
                    thermalZoneCache[i] = Pair(tempFile, typeFile)
                }
            } catch (_: Exception) {
                // Ignorar
            }
        }

        thermalCacheInitialized = true
        Log.d(TAG, "Caché térmico inicializado: ${thermalZoneCache.size} zonas válidas encontradas")
    }

    /**
     * Obtiene el estado térmico del sistema
     */
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
