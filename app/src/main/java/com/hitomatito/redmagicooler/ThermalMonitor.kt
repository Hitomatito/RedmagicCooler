
package com.hitomatito.redmagicooler

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
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
        private const val DEFAULT_UPDATE_INTERVAL_MS = 60000L // Intervalo predeterminado recomendado: 1 minuto
        
        // Rangos de temperatura (°C) para calibración automática
        const val TEMP_SAFE = 35        // < 35°C: Sin preocupación
        const val TEMP_WARM = 40        // 35-40°C: Calentamiento
        const val TEMP_HOT = 45         // 40-45°C: Caliente
        const val TEMP_CRITICAL = 50    // > 50°C: Crítico
        
        // Umbral de estabilidad para evitar cambios frecuentes
        private const val MIN_SPEED_CHANGE = 10  // Cambio mínimo de velocidad: 10%
    }
    
    // Intervalo configurable de actualización (en ms)
    var updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS
        set(value) {
            // Validar intervalo mínimo para evitar sobrecarga (mínimo 5 segundos)
            field = value.coerceAtLeast(5000L)
            Log.d(TAG, "Intervalo de actualización cambiado a ${field}ms")
        }
    
    private var monitoringJob: Job? = null
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var ambientTemp: Float = 0f
    
    // Variables para estabilidad
    private var lastRecommendedSpeed: Int = 0
    private var speedChangeCounter: Int = 0
    
    // Contador de intervalos de actualización
    private var intervalCounter: Int = 0
    
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
        val intervalCount: Int = 0            // Contador de intervalos de actualización
    )
    
    enum class TempLevel {
        SAFE,       // Verde: < 35°C
        WARM,       // Amarillo: 35-40°C
        HOT,        // Naranja: 40-45°C
        CRITICAL    // Rojo: > 45°C
    }
    
    /**
     * Inicia el monitoreo de temperatura
     */
    fun startMonitoring(
        scope: CoroutineScope,
        onUpdate: (ThermalData) -> Unit
    ) {
        stopMonitoring()
        
        monitoringJob = scope.launch(Dispatchers.Default) {
            Log.d(TAG, "Iniciando monitoreo de temperatura")
            
            while (isActive) {
                try {
                    intervalCounter++
                    val thermalData = getCurrentThermalData(intervalCounter)
                    onUpdate(thermalData)
                    delay(updateIntervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en monitoreo: ${e.message}", e)
                    delay(updateIntervalMs)
                }
            }
        }
    }
    
    /**
     * Resetea el contador de intervalos
     */
    fun resetIntervalCounter() {
        intervalCounter = 0
        Log.d(TAG, "Contador de intervalos reseteado")
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
     * Obtiene datos térmicos actuales del dispositivo
     */
    fun getCurrentThermalData(intervalCount: Int = 0): ThermalData {
        val batteryTemp = getBatteryTemperature()
        val thermalZoneTemps = readThermalZones()
        val sensorTemp = ambientTemp
        
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
        
        // Determinar temperatura máxima: priorizar GPU > CPU > otros > batería
        val maxTemp = when {
            gpuTemp > 0 -> gpuTemp
            cpuTemp > 0 -> cpuTemp
            thermalZoneTemps.isNotEmpty() -> thermalZoneTemps.values.maxOrNull() ?: batteryTemp
            else -> batteryTemp
        }
        
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getThermalStatus()
        } else {
            0 // THERMAL_STATUS_NONE para versiones anteriores
        }
        
        val tempLevel = when {
            maxTemp < TEMP_SAFE -> TempLevel.SAFE
            maxTemp < TEMP_WARM -> TempLevel.WARM
            maxTemp < TEMP_HOT -> TempLevel.HOT
            else -> TempLevel.CRITICAL
        }
        
        val recommendedSpeed = calculateRecommendedSpeed(maxTemp)
        
        // Debug info de fuentes
        val sources = buildString {
            append("Battery: ${"%.1f".format(batteryTemp)}°C")
            if (cpuTemp > 0) append(", CPU: ${"%.1f".format(cpuTemp)}°C")
            if (gpuTemp > 0) append(", GPU: ${"%.1f".format(gpuTemp)}°C")
            if (skinTemp > 0) append(", Skin: ${"%.1f".format(skinTemp)}°C")
            if (sensorTemp > 0) append(", Sensor: ${"%.1f".format(sensorTemp)}°C")
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
     * Lee temperaturas de las zonas térmicas del sistema
     * Estos archivos suelen contener la temperatura del CPU, GPU y otros componentes
     * Devuelve un mapa de tipo de zona a temperatura
     */
    private fun readThermalZones(): Map<String, Float> {
        val temps = mutableMapOf<String, Float>()
        
        for (i in 0..20) { // Probar más zonas térmicas
            val tempPath = "/sys/class/thermal/thermal_zone$i/temp"
            val typePath = "/sys/class/thermal/thermal_zone$i/type"
            
            try {
                val tempFile = File(tempPath)
                val typeFile = File(typePath)
                
                if (tempFile.exists() && tempFile.canRead() && typeFile.exists() && typeFile.canRead()) {
                    val tempStr = tempFile.readText().trim()
                    val typeStr = typeFile.readText().trim().lowercase()
                    
                    val temp = tempStr.toFloatOrNull()
                    
                    if (temp != null && temp > 0) {
                        // Los valores pueden estar en miligramos (dividir por 1000) o ya en grados
                        val normalizedTemp = if (temp > 200) temp / 1000f else temp
                        
                        // Filtrar valores absurdos (< 0 o > 100°C)
                        if (normalizedTemp in 0f..100f) {
                            temps[typeStr] = normalizedTemp
                            Log.v(TAG, "Temp de zona $i ($typeStr): ${"%.1f".format(normalizedTemp)}°C")
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignorar errores de lectura silenciosamente, muchos paths pueden no existir
            }
        }
        
        return temps
    }
    
    /**
     * Inicia el listener de sensor de temperatura ambiente (si disponible)
     */
    fun startAmbientSensor() {
        try {
            val tempSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
            if (tempSensor != null) {
                sensorManager.registerListener(object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        event?.let {
                            ambientTemp = it.values[0]
                        }
                    }
                    
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }, tempSensor, SensorManager.SENSOR_DELAY_NORMAL)
                
                Log.d(TAG, "Sensor de temperatura ambiente registrado")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar sensor de temperatura: ${e.message}")
        }
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
     * Con estabilización para evitar cambios frecuentes
     * 
     * Lógica de calibración:
     * - < 35°C: 0% (apagado, no necesario)
     * - 35-40°C: 25-50% (enfriamiento suave)
     * - 40-45°C: 50-75% (enfriamiento activo)
     * - 45-50°C: 75-100% (enfriamiento máximo)
     * - > 50°C: 100% (emergencia)
     */
    private fun calculateRecommendedSpeed(temp: Float): Int {
        val rawSpeed = when {
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
        
        // ESTABILIZACIÓN: Solo cambiar si la diferencia es significativa
        val speedDiff = kotlin.math.abs(rawSpeed - lastRecommendedSpeed)
        
        return if (speedDiff >= MIN_SPEED_CHANGE) {
            // Cambio significativo: actualizar
            speedChangeCounter++
            Log.d(TAG, "Temp: ${"%.1f".format(temp)}°C → Cambio de velocidad: $lastRecommendedSpeed% → $rawSpeed% (cambio #$speedChangeCounter)")
            lastRecommendedSpeed = rawSpeed
            rawSpeed
        } else {
            // Cambio pequeño: mantener velocidad anterior (estabilidad)
            Log.v(TAG, "Temp: ${"%.1f".format(temp)}°C → Velocidad estable: $lastRecommendedSpeed% (nuevo: $rawSpeed%, diff: ${speedDiff}%)")
            lastRecommendedSpeed
        }
    }
}
