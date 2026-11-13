
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
 * Monitor de temperatura del dispositivo para controlar el cooler
 */
class ThermalMonitor(private val context: Context) {
    companion object {
        private const val TAG = "ThermalMonitor"

        // Rangos de temperatura (°C) para calibración automática
        const val TEMP_SAFE = 40        // < 40°C: Sin preocupación
        const val TEMP_WARM = 50        // 40-50°C: Calentamiento
        const val TEMP_HOT = 55         // 50-55°C: Caliente
        const val TEMP_CRITICAL = 60    // > 60°C: Crítico
        
        // Sistema de rampa progresiva
        private const val SPEED_INCREMENT = 10   // Incremento normal por paso: 10%
        private const val EMERGENCY_INCREMENT = 20 // Incremento con riesgo sostenido: 20%
        private const val MIN_TIME_AT_SPEED = 30000L  // Tiempo mínimo en cada velocidad: 30 segundos
        private const val PROGRESSIVE_INCREASE_DELAY = 20000L // Espera entre subidas normales: 20 segundos
        private const val EMERGENCY_INCREASE_DELAY = 10000L // Espera entre subidas con riesgo sostenido
        private const val CRITICAL_SUSTAINED_MS = 45000L     // Tiempo mínimo de riesgo alto sostenido
    }

    private var monitoringJob: Job? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    
    // Variables para estabilidad
    private var lastRecommendedSpeed: Int = 0
    private var speedChangeCounter: Int = 0
    
    // Sistema de velocidad progresiva
    private var currentTargetSpeed: Int = 0
    private var lastSpeedIncreaseTime: Long = 0L
    private var timeAtCurrentSpeed: Long = 0L
    
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

    // Evita duplicar la escala en control y sondeo.
    private fun tempLevelForSignal(value: Float): TempLevel = when {
        value < TEMP_SAFE -> TempLevel.SAFE
        value < TEMP_WARM -> TempLevel.WARM
        value < TEMP_HOT -> TempLevel.HOT
        else -> TempLevel.CRITICAL
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
                    val adaptiveInterval = getAdaptiveInterval(thermalData.tempLevel, thermalData.hardwarePeakTemp)
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
     * Obtiene datos térmicos actuales sin avanzar el estado de riesgo.
     * El bucle de monitoreo usa [readCurrentThermalData] para actualizar la histéresis.
     */
    fun getCurrentThermalData(intervalCount: Int = 0): ThermalData =
        readCurrentThermalData(intervalCount, advanceCriticalState = false)

    /**
     * Lee sensores y opcionalmente avanza la histéresis de riesgo.
     */
    private fun readCurrentThermalData(intervalCount: Int, advanceCriticalState: Boolean): ThermalData {
        val batteryTemp = getBatteryTemperature()
        val thermalZoneTemps = readThermalZones()
        
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
            val currentTime = System.currentTimeMillis()
            if (hardwarePeakTemp >= TEMP_HOT) {
                if (hardwareRiskSinceMs == 0L) {
                    hardwareRiskSinceMs = currentTime
                }
            } else {
                hardwareRiskSinceMs = 0L
            }
        }
        val isRiskConfirmed = hardwareRiskSinceMs != 0L &&
            System.currentTimeMillis() - hardwareRiskSinceMs >= CRITICAL_SUSTAINED_MS
        val maxTemp = if (isRiskConfirmed) hardwarePeakTemp else controlBase
        val displayedSource = if (isRiskConfirmed) ControlSource.HARDWARE else controlSource
        
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getThermalStatus()
        } else {
            0 // THERMAL_STATUS_NONE para versiones anteriores
        }
        
        val tempLevel = tempLevelForSignal(maxTemp)
        
        val recommendedSpeed = calculateRecommendedSpeed(maxTemp, isRiskConfirmed)
        
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
    
    /**
     * Calcula la velocidad recomendada del cooler según la temperatura
     * Con sistema de rampa progresiva y estabilización
     * 
     * Lógica de calibración:
 * - < 40 °C: 0% (apagado, no necesario)
 * - 40-50 °C: 25-50% (enfriamiento suave)
 * - 50-55 °C: 50-75% (enfriamiento activo)
 * - 55-60 °C: 75-100% (enfriamiento máximo)
 * - >= 60 °C: 100% (emergencia)
     * 
     * Sistema progresivo:
 * - La velocidad aumenta gradualmente en incrementos de 10%
 * - La velocidad también baja gradualmente para evitar oscilaciones
 * - Se mantiene cada velocidad al menos 30 segundos para permitir enfriamiento
 * - El riesgo hardware sostenido acelera la rampa, sin salto directo
     */
    private fun calculateRecommendedSpeed(temp: Float, isRiskConfirmed: Boolean): Int {
        val currentTime = System.currentTimeMillis()
        
        // Calcular velocidad objetivo ideal según temperatura
        val targetSpeed = when {
            // Temperatura segura: cooler apagado o mínimo
            temp < TEMP_SAFE -> 0
            
            // Temperatura tibia: enfriamiento suave (25-50%)
            temp < TEMP_WARM -> {
                val ratio = (temp - TEMP_SAFE) / (TEMP_WARM - TEMP_SAFE)
                (25 + (ratio * 25)).toInt().coerceIn(0, 100)
            }
            
            // Temperatura caliente: enfriamiento activo (50-75%)
            temp < TEMP_HOT -> {
                val ratio = (temp - TEMP_WARM) / (TEMP_HOT - TEMP_WARM)
                (50 + (ratio * 25)).toInt().coerceIn(0, 100)
            }
            
            // Temperatura muy caliente: enfriamiento máximo (75-100%)
            temp < TEMP_CRITICAL -> {
                val ratio = (temp - TEMP_HOT) / (TEMP_CRITICAL - TEMP_HOT)
                (75 + (ratio * 25)).toInt().coerceIn(0, 100)
            }
            
            // Temperatura crítica: máximo absoluto
            else -> 100
        }
        
        // RAMPA PROGRESIVA: nunca saltar bruscamente; acelerar solo con riesgo confirmado.
        val increment = if (isRiskConfirmed) EMERGENCY_INCREMENT else SPEED_INCREMENT
        val increaseDelay = if (isRiskConfirmed) EMERGENCY_INCREASE_DELAY else PROGRESSIVE_INCREASE_DELAY
        
        // Sí necesitamos aumentar velocidad
        if (targetSpeed > lastRecommendedSpeed) {
            // Verificar si hemos esperado suficiente tiempo en la velocidad actual
            val timeAtSpeed = currentTime - timeAtCurrentSpeed
            val timeSinceLastIncrease = currentTime - lastSpeedIncreaseTime
            
            // Permitir incremento si:
            // 1. Hemos esperado el tiempo mínimo en esta velocidad (30 s)
            // 2. Ha pasado el delay entre incrementos (20 s normal, 10 s con riesgo sostenido)
            if (timeAtSpeed >= MIN_TIME_AT_SPEED && timeSinceLastIncrease >= increaseDelay) {
                // Incrementar gradualmente
                val nextSpeed = (lastRecommendedSpeed + increment).coerceAtMost(targetSpeed)
                
                if (nextSpeed != lastRecommendedSpeed) {
                    speedChangeCounter++
                    val mode = if (isRiskConfirmed) "RIESGO SOSTENIDO" else "PROGRESIVO"
                    Log.d(TAG, "📈 $mode: Temp: ${"%.1f".format(temp)}°C → $lastRecommendedSpeed% → $nextSpeed% (objetivo: $targetSpeed%, tiempo en velocidad: ${timeAtSpeed/1000}s)")
                    lastRecommendedSpeed = nextSpeed
                    lastSpeedIncreaseTime = currentTime
                    timeAtCurrentSpeed = currentTime
                    return nextSpeed
                } else {
                    return lastRecommendedSpeed
                }
            } else {
                // Aún no es tiempo de incrementar, mantener velocidad actual
                val remainingTime = maxOf(
                    (MIN_TIME_AT_SPEED - timeAtSpeed) / 1000,
                    (increaseDelay - timeSinceLastIncrease) / 1000
                )
                if (remainingTime > 0 && remainingTime % 10L == 0L) {
                    Log.d(TAG, "⏳ Esperando ${remainingTime}s antes del próximo incremento (actual: $lastRecommendedSpeed%, objetivo: $targetSpeed%)")
                }
                return lastRecommendedSpeed
            }
        }
        // Sí necesitamos reducir velocidad: bajar gradualmente para evitar oscilaciones.
        else if (targetSpeed < lastRecommendedSpeed) {
            val timeAtSpeed = currentTime - timeAtCurrentSpeed
            val timeSinceLastChange = currentTime - lastSpeedIncreaseTime

            if (timeAtSpeed >= MIN_TIME_AT_SPEED && timeSinceLastChange >= PROGRESSIVE_INCREASE_DELAY) {
                val nextSpeed = (lastRecommendedSpeed - SPEED_INCREMENT).coerceAtLeast(targetSpeed)
                if (nextSpeed != lastRecommendedSpeed) {
                    speedChangeCounter++
                    Log.d(TAG, "📉 REDUCCIÓN: Temp: ${"%.1f".format(temp)}°C → $lastRecommendedSpeed% → $nextSpeed% (objetivo: $targetSpeed%)")
                    lastRecommendedSpeed = nextSpeed
                    lastSpeedIncreaseTime = currentTime
                    timeAtCurrentSpeed = currentTime
                    return nextSpeed
                }
            }
            return lastRecommendedSpeed
        }
        
        // Mantener velocidad actual
        return lastRecommendedSpeed
    }
    
    /**
     * Calcula el intervalo adaptativo de monitoreo basado en temperatura de control
     * y, si existe, en el pico hardware detectado.
     */
    fun getAdaptiveInterval(tempLevel: TempLevel, hardwarePeakTemp: Float = 0f): Long {
        val signalLevel = tempLevelForSignal(hardwarePeakTemp)
        val effectiveLevel = if (signalLevel.ordinal > tempLevel.ordinal) signalLevel else tempLevel
        return when (effectiveLevel) {
            TempLevel.SAFE -> 45000L      // 45 segundos si seguro (reducido desde 60 s)
            TempLevel.WARM -> 20000L      // 20 segundos si tibio (reducido desde 30s)
            TempLevel.HOT -> 10000L       // 10 segundos si caliente (reducido desde 15 s)
            TempLevel.CRITICAL -> 5000L   // 5 segundos si crítico (sin cambio)
        }
    }
}
