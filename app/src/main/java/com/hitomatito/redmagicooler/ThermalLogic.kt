package com.hitomatito.redmagicooler

import kotlin.math.roundToInt

private const val EMA_ALPHA_COMFORT = 0.35f
private const val EMA_ALPHA_HARDWARE = 0.25f
private const val TREND_LOOKAHEAD_MS = 30_000L
private const val UP_DEADBAND_PERCENT = 5
private const val DOWN_DEADBAND_PERCENT = 10
private const val SPEED_QUANTUM = 5

/**
 * Lógica térmica pura: sin Android ni reloj. El tiempo se inyecta como [nowMs]
 * para que la rampa y la histéresis sean deterministas y testeables.
 *
 * La velocidad no depende de una sola fuente: se fusionan el confort (piel o
 * batería), el SoC (CPU/GPU) reescalado a la escala de confort y el estado
 * térmico del sistema (0-6). La fusión reacciona antes que esperar al crítico,
 * pero con rampas lentas y bandas muertas para que el ventilador sea estable y
 * silencioso. El estado crítico confirmado sigue mandando sobre la fusión.
 */
internal object ThermalLogic {
    const val TEMP_SAFE = 40
    const val TEMP_WARM = 50
    const val TEMP_HOT = 55
    const val TEMP_CRITICAL = 60

    const val SPEED_INCREMENT = 10
    const val EMERGENCY_INCREMENT = 20
    const val INCREASE_DELAY_MS = 30_000L
    const val EMERGENCY_INCREASE_DELAY_MS = 10_000L
    const val DECREASE_DELAY_MS = 10_000L
    const val CRITICAL_SUSTAINED_MS = 45_000L
    const val CRITICAL_EXIT_TEMP = 50f
    const val CRITICAL_EXIT_MS = 30_000L

    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3

    /** Un SoC a 45 °C equivale a 40 °C de confort; uno a 75 °C, a 60 °C. */
    const val SOC_EQUIV_MIN_C = 45f
    const val SOC_EQUIV_SPAN_C = 30f
    const val SOC_EQUIV_FLOOR_C = 40f
    const val SOC_EQUIV_RANGE_C = 20f
    const val SOC_EQUIV_MAX_C = 70f

    /** Apagado de pantalla: el equipo suele estar ocioso, no dispares el ventilador. */
    const val SCREEN_OFF_SOC_FACTOR = 0.85f

    /** Cargar con pantalla encendida añade calor externo; de noche no despiertes el ventilador. */
    const val CHARGING_BIAS_C = 2f

    /** Preferencia de ruido: 0 = máximo rendimiento, 100 = máximo silencio. */
    const val NOISE_PERFORMANCE_THRESHOLD = 33
    const val NOISE_QUIET_THRESHOLD = 67
    const val NOISE_FACTOR_MIN = 0.75f
    const val NOISE_FACTOR_RANGE = 0.5f

    const val QUIET_INCREMENT = 5
    const val PERFORMANCE_INCREMENT = 15
    const val QUIET_INCREASE_DELAY_MS = 45_000L
    const val PERFORMANCE_INCREASE_DELAY_MS = 20_000L
    const val QUIET_DECREASE_DELAY_MS = 20_000L
    const val PERFORMANCE_DECREASE_DELAY_MS = 10_000L
}

/** Ajustes del usuario que modulan la curva sin cambiar la seguridad. */
internal data class ThermalTuning(
    val noisePreference: Int = 50,
)

internal data class ControlState(
    val lastRecommendedSpeed: Int = 0,
    val lastSpeedChangeTimeMs: Long = 0L,
    val hardwareRiskSinceMs: Long = 0L,
    val criticalActive: Boolean = false,
    val criticalClearSinceMs: Long = 0L,
    val smoothedControlTemp: Float = 0f,
    val hasSmoothedValue: Boolean = false,
    val lastSampleTimeMs: Long = 0L,
    val smoothedHardwareTemp: Float = 0f,
    val hasHardwareValue: Boolean = false,
    val lastHardwareSampleTimeMs: Long = 0L,
)

internal data class ThermalSample(
    val batteryTemp: Float = 0f,
    val cpuTemp: Float = 0f,
    val gpuTemp: Float = 0f,
    val skinTemp: Float = 0f,
    val hardwarePeakTemp: Float = 0f,
    val thermalStatus: Int = 0,
    val charging: Boolean = false,
    val screenInteractive: Boolean = true,
    val otherZonesText: String = "",
)

internal data class SmoothedSignal(
    val value: Float,
    val trendPerSecond: Float,
    val predicted: Float,
)

internal data class ThermalDecision(
    val speed: Int,
    val maxTemp: Float,
    val tempLevel: ThermalMonitor.TempLevel,
    val hardwarePeakTemp: Float,
    val isRiskConfirmed: Boolean,
    val controlSource: String,
    val adaptiveIntervalMs: Long,
    val state: ControlState,
)

internal fun tempLevelForSignal(value: Float): ThermalMonitor.TempLevel = when {
    value.isNaN() || value < ThermalLogic.TEMP_SAFE -> ThermalMonitor.TempLevel.SAFE
    value < ThermalLogic.TEMP_WARM -> ThermalMonitor.TempLevel.WARM
    value < ThermalLogic.TEMP_HOT -> ThermalMonitor.TempLevel.HOT
    else -> ThermalMonitor.TempLevel.CRITICAL
}

/** Curva más tendida que la anterior: el tramo medio baja y solo llega a 100 % al crítico. */
internal fun targetSpeedFor(temp: Float): Int {
    if (temp.isNaN() || temp < 0f) return 0
    if (temp.isInfinite()) return if (temp > 0f) 100 else 0
    return when {
        temp < ThermalLogic.TEMP_SAFE -> 0
        temp < ThermalLogic.TEMP_WARM -> {
            val ratio = (temp - ThermalLogic.TEMP_SAFE) / (ThermalLogic.TEMP_WARM - ThermalLogic.TEMP_SAFE)
            (20 + ratio * 25).toInt().coerceIn(0, 100)
        }
        temp < ThermalLogic.TEMP_HOT -> {
            val ratio = (temp - ThermalLogic.TEMP_WARM) / (ThermalLogic.TEMP_HOT - ThermalLogic.TEMP_WARM)
            (45 + ratio * 20).toInt().coerceIn(0, 100)
        }
        temp < ThermalLogic.TEMP_CRITICAL -> {
            val ratio = (temp - ThermalLogic.TEMP_HOT) / (ThermalLogic.TEMP_CRITICAL - ThermalLogic.TEMP_HOT)
            (65 + ratio * 25).toInt().coerceIn(0, 100)
        }
        else -> 100
    }
}

/** Lleva el SoC a la escala de confort: 45 °C -> 40 °C, 75 °C -> 60 °C. */
internal fun socEquivalentTemp(soc: Float): Float {
    if (!soc.isFinite() || soc <= 0f) return 0f
    if (soc <= ThermalLogic.SOC_EQUIV_MIN_C) {
        return soc * (ThermalLogic.SOC_EQUIV_FLOOR_C / ThermalLogic.SOC_EQUIV_MIN_C)
    }
    val scaled = ThermalLogic.SOC_EQUIV_FLOOR_C +
        (soc - ThermalLogic.SOC_EQUIV_MIN_C) * (ThermalLogic.SOC_EQUIV_RANGE_C / ThermalLogic.SOC_EQUIV_SPAN_C)
    return scaled.coerceAtMost(ThermalLogic.SOC_EQUIV_MAX_C)
}

/** Niveles 0-6 de Android expresados como temperatura equivalente de confort. */
internal fun statusEquivalentTemp(status: Int): Float = when (status.coerceIn(0, 6)) {
    0, 1 -> 0f
    2 -> 50f
    3 -> 55f
    4 -> 60f
    5 -> 65f
    else -> 70f
}

/** Suelo de velocidad que impone el sistema operativo cuando ya está limitando. */
internal fun statusFloorSpeed(status: Int): Int = when (status.coerceIn(0, 6)) {
    0, 1 -> 0
    2 -> 40
    3 -> 60
    4 -> 80
    5 -> 90
    else -> 100
}

private fun Float.sanitizedTemp(): Float = if (isFinite() && this > 0f) this else 0f

private fun ema(previous: Float, raw: Float, alpha: Float): Float =
    alpha * raw + (1f - alpha) * previous

private fun trend(previous: Float, previousTimeMs: Long, current: Float, nowMs: Long): Float {
    val dtMs = nowMs - previousTimeMs
    return if (dtMs > 0L) (current - previous) / (dtMs / 1000f) else 0f
}

private fun controlBase(sample: ThermalSample): Float {
    val skin = sample.skinTemp.sanitizedTemp()
    val battery = sample.batteryTemp.sanitizedTemp()
    return if (skin > 0f) skin else battery
}

private fun hardwareBase(sample: ThermalSample): Float = maxOf(
    sample.hardwarePeakTemp.sanitizedTemp(),
    sample.cpuTemp.sanitizedTemp(),
    sample.gpuTemp.sanitizedTemp(),
)

private fun smoothSignal(
    previous: Float,
    previousTimeMs: Long,
    raw: Float,
    nowMs: Long,
    initialized: Boolean,
    alpha: Float,
): SmoothedSignal {
    val safeRaw = raw.sanitizedTemp()
    val safePrevious = previous.sanitizedTemp()
    val value = if (initialized) ema(safePrevious, safeRaw, alpha) else safeRaw
    val rate = if (initialized) trend(safePrevious, previousTimeMs, value, nowMs) else 0f
    val predicted = (value + rate * (TREND_LOOKAHEAD_MS / 1000f)).coerceAtLeast(0f)
    return SmoothedSignal(value, rate, predicted)
}

/** Señal de confort: piel, o batería si el dispositivo no expone piel. */
internal fun smooth(state: ControlState, sample: ThermalSample, nowMs: Long): SmoothedSignal =
    smoothSignal(
        previous = state.smoothedControlTemp,
        previousTimeMs = state.lastSampleTimeMs,
        raw = controlBase(sample),
        nowMs = nowMs,
        initialized = state.hasSmoothedValue,
        alpha = EMA_ALPHA_COMFORT,
    )

/** Señal del SoC (CPU/GPU): más lenta que la de confort para no reaccionar a picos de un núcleo. */
internal fun smoothHardware(state: ControlState, sample: ThermalSample, nowMs: Long): SmoothedSignal =
    smoothSignal(
        previous = state.smoothedHardwareTemp,
        previousTimeMs = state.lastHardwareSampleTimeMs,
        raw = hardwareBase(sample),
        nowMs = nowMs,
        initialized = state.hasHardwareValue,
        alpha = EMA_ALPHA_HARDWARE,
    )

internal fun adaptiveInterval(
    level: ThermalMonitor.TempLevel,
    hardwarePeakTemp: Float = 0f,
): Long {
    val signalLevel = tempLevelForSignal(hardwarePeakTemp)
    val effective = if (signalLevel.ordinal > level.ordinal) signalLevel else level
    return when (effective) {
        ThermalMonitor.TempLevel.SAFE -> 45_000L
        ThermalMonitor.TempLevel.WARM -> 20_000L
        ThermalMonitor.TempLevel.HOT -> 10_000L
        ThermalMonitor.TempLevel.CRITICAL -> 5_000L
    }
}

private fun quantizeSpeed(speed: Int): Int =
    (speed.coerceIn(0, 100) / SPEED_QUANTUM) * SPEED_QUANTUM

/**
 * Gate de comandos del modo automatico: el servicio escribe al cooler solo cuando el
 * objetivo del controlador difiere a la vez de la velocidad reportada por el eco
 * ([current]) y de la ultima realmente comandada ([lastCommanded]). Durante el arranque
 * del ventilador el eco reporta RPM transitorios y el guard evita reescrituras por
 * sample; tras una desconexion [lastCommanded] se reinicia a -1, de modo que el
 * reconectado rearma siempre la velocidad fisica del aparato.
 */
internal fun shouldWriteToCooler(recommended: Int, current: Int, lastCommanded: Int): Boolean =
    recommended != current && recommended != lastCommanded

private data class CriticalState(
    val active: Boolean,
    val sinceMs: Long,
    val clearSinceMs: Long,
)

/**
 * Entrada: SoC caliente de forma sostenida. Salida: SoC frío por debajo de 50 °C
 * durante 30 s. La banda 50-55 °C mantiene el estado anterior (histéresis).
 */
private fun updateCritical(
    state: ControlState,
    sample: ThermalSample,
    nowMs: Long,
    advance: Boolean,
): CriticalState {
    if (!advance) {
        return CriticalState(state.criticalActive, state.hardwareRiskSinceMs, state.criticalClearSinceMs)
    }

    val socTemp = hardwareBase(sample)
    val socHot = socTemp >= ThermalLogic.TEMP_HOT ||
        sample.thermalStatus >= ThermalLogic.THERMAL_STATUS_MODERATE

    if (state.criticalActive) {
        val cooling = !socHot && socTemp < ThermalLogic.CRITICAL_EXIT_TEMP
        val clearSince = when {
            !cooling -> 0L
            state.criticalClearSinceMs == 0L -> nowMs
            else -> state.criticalClearSinceMs
        }
        val stillActive = !cooling || nowMs - clearSince < ThermalLogic.CRITICAL_EXIT_MS
        return CriticalState(
            active = stillActive,
            sinceMs = if (stillActive) state.hardwareRiskSinceMs else 0L,
            clearSinceMs = if (stillActive) clearSince else 0L,
        )
    }

    val since = if (socHot) {
        if (state.hardwareRiskSinceMs == 0L) nowMs else state.hardwareRiskSinceMs
    } else 0L
    return CriticalState(
        active = since != 0L && nowMs - since >= ThermalLogic.CRITICAL_SUSTAINED_MS,
        sinceMs = since,
        clearSinceMs = 0L,
    )
}

internal fun decide(
    state: ControlState,
    sample: ThermalSample,
    nowMs: Long,
    advance: Boolean,
    tuning: ThermalTuning = ThermalTuning(),
): ThermalDecision {
    val critical = updateCritical(state, sample, nowMs, advance)
    val comfort = smooth(state, sample, nowMs)
    val hardware = smoothHardware(state, sample, nowMs)

    val status = sample.thermalStatus.coerceIn(0, 6)
    val riskConfirmed = critical.active || status >= ThermalLogic.THERMAL_STATUS_SEVERE

    val chargingBias = if (sample.charging && sample.screenInteractive) ThermalLogic.CHARGING_BIAS_C else 0f
    val screenFactor = if (sample.screenInteractive) 1f else ThermalLogic.SCREEN_OFF_SOC_FACTOR
    val comfortValue = comfort.value + chargingBias
    val comfortPredicted = comfort.predicted + chargingBias
    val socValue = socEquivalentTemp(hardware.value) * screenFactor
    val socPredicted = socEquivalentTemp(hardware.predicted) * screenFactor
    val statusValue = statusEquivalentTemp(status)

    val fusedPredicted = maxOf(comfortPredicted, socPredicted, statusValue)

    // Con riesgo confirmado el SoC manda sin reescalar: es la señal de seguridad.
    val demandTemp = if (riskConfirmed) maxOf(fusedPredicted, hardware.predicted) else fusedPredicted
    val noisePreference = tuning.noisePreference.coerceIn(0, 100)
    // 0 = rendimiento -> factor máximo (1.25); 100 = silencio -> factor mínimo (0.75).
    val noiseFactor = ThermalLogic.NOISE_FACTOR_MIN +
        ThermalLogic.NOISE_FACTOR_RANGE * ((100 - noisePreference) / 100f)
    val curveDemand = targetSpeedFor(demandTemp)
    // En emergencia la preferencia de ruido no puede recortar la refrigeración.
    val adjustedDemand = if (riskConfirmed) {
        curveDemand
    } else {
        (curveDemand * noiseFactor).roundToInt().coerceIn(0, 100)
    }
    val statusFloor = statusFloorSpeed(status)
    val demand = maxOf(adjustedDemand, statusFloor)

    val current = state.lastRecommendedSpeed.coerceIn(0, 100)
    val emergency = riskConfirmed
    // El suelo que impone el SO es seguridad: no se modula con la preferencia de
    // ruido (en silencio +5/45s tardaría ~6 min en alcanzar un 40 % ya exigido).
    val floorRising = !emergency && statusFloor > current
    val quiet = noisePreference >= ThermalLogic.NOISE_QUIET_THRESHOLD
    val performance = noisePreference <= ThermalLogic.NOISE_PERFORMANCE_THRESHOLD
    val increment = when {
        emergency -> ThermalLogic.EMERGENCY_INCREMENT
        floorRising -> ThermalLogic.SPEED_INCREMENT
        quiet -> ThermalLogic.QUIET_INCREMENT
        performance -> ThermalLogic.PERFORMANCE_INCREMENT
        else -> ThermalLogic.SPEED_INCREMENT
    }
    val upDelay = when {
        emergency -> ThermalLogic.EMERGENCY_INCREASE_DELAY_MS
        floorRising -> ThermalLogic.INCREASE_DELAY_MS
        quiet -> ThermalLogic.QUIET_INCREASE_DELAY_MS
        performance -> ThermalLogic.PERFORMANCE_INCREASE_DELAY_MS
        else -> ThermalLogic.INCREASE_DELAY_MS
    }
    val downDelay = when {
        emergency -> ThermalLogic.DECREASE_DELAY_MS
        quiet -> ThermalLogic.QUIET_DECREASE_DELAY_MS
        performance -> ThermalLogic.PERFORMANCE_DECREASE_DELAY_MS
        else -> ThermalLogic.DECREASE_DELAY_MS
    }
    val elapsed = nowMs - state.lastSpeedChangeTimeMs
    val rising = comfort.trendPerSecond > 0.05f || hardware.trendPerSecond > 0.05f

    var speed = current
    val wantsUp = demand > current
    // Sin atajo cuando current == 0: tras una caída a 0, la subida respeta el dwell
    // (en el arranco real lastSpeedChangeTimeMs = 0 y elapsed ya es grande).
    val canUp = elapsed >= upDelay &&
        (emergency || demand >= current + UP_DEADBAND_PERCENT)
    if (wantsUp && canUp) {
        speed = quantizeSpeed((current + increment).coerceAtMost(demand))
    } else if (demand < current &&
        elapsed >= downDelay &&
        !rising &&
        (demand == 0 || current - demand > DOWN_DEADBAND_PERCENT)
    ) {
        // La bajada usa SIEMPRE SPEED_INCREMENT (no el increment por zona de ruido):
        // enfriar de más es seguro; recortarlo en silencio ahorraría poco y el riesgo
        // de que la piel siga caliente con el ventilador bajo no compensa.
        speed = quantizeSpeed((current - ThermalLogic.SPEED_INCREMENT).coerceAtLeast(demand))
    }

    val changed = speed != current
    val nextState = if (advance) {
        ControlState(
            lastRecommendedSpeed = speed,
            lastSpeedChangeTimeMs = if (changed) nowMs else state.lastSpeedChangeTimeMs,
            hardwareRiskSinceMs = critical.sinceMs,
            criticalActive = critical.active,
            criticalClearSinceMs = critical.clearSinceMs,
            smoothedControlTemp = comfort.value,
            hasSmoothedValue = true,
            lastSampleTimeMs = nowMs,
            smoothedHardwareTemp = hardware.value,
            hasHardwareValue = true,
            lastHardwareSampleTimeMs = nowMs,
        )
    } else {
        state
    }

    val maxTemp = maxOf(comfort.value, hardware.value, sample.batteryTemp.sanitizedTemp())
    val level = tempLevelForSignal(maxTemp)
    val source = when {
        riskConfirmed && hardware.predicted > 0f && hardware.predicted >= fusedPredicted -> "hardware"
        statusValue > 0f && statusValue >= socValue && statusValue >= comfortValue -> "status"
        socValue > 0f && socValue >= comfortValue -> "soc"
        sample.skinTemp.sanitizedTemp() > 0f -> "skin"
        sample.batteryTemp.sanitizedTemp() > 0f -> "battery"
        else -> "none"
    }

    return ThermalDecision(
        speed = speed,
        maxTemp = maxTemp,
        tempLevel = level,
        hardwarePeakTemp = sample.hardwarePeakTemp,
        isRiskConfirmed = riskConfirmed,
        controlSource = source,
        adaptiveIntervalMs = adaptiveInterval(level, sample.hardwarePeakTemp),
        state = nextState,
    )
}
