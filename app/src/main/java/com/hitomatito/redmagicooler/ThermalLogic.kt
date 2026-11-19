package com.hitomatito.redmagicooler

private const val EMA_ALPHA = 0.35f
private const val TREND_LOOKAHEAD_MS = 30_000L
private const val DOWN_DEADBAND_PERCENT = 5

/**
 * Lógica térmica pura: sin Android ni reloj. El tiempo se inyecta como [nowMs]
 * para que la rampa y la histéresis sean deterministas y testeables.
 */
internal object ThermalLogic {
    const val TEMP_SAFE = 40
    const val TEMP_WARM = 50
    const val TEMP_HOT = 55
    const val TEMP_CRITICAL = 60

    const val SPEED_INCREMENT = 10
    const val EMERGENCY_INCREMENT = 20
    const val INCREASE_DELAY_MS = 20_000L
    const val EMERGENCY_INCREASE_DELAY_MS = 10_000L
    const val DECREASE_DELAY_MS = 10_000L
    const val CRITICAL_SUSTAINED_MS = 45_000L
    const val CRITICAL_EXIT_TEMP = 50f
    const val CRITICAL_EXIT_MS = 30_000L
    const val THERMAL_STATUS_MODERATE = 2
}

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
    val adaptiveIntervalMs: Long,
    val state: ControlState,
)

internal fun tempLevelForSignal(value: Float): ThermalMonitor.TempLevel = when {
    value.isNaN() || value < ThermalLogic.TEMP_SAFE -> ThermalMonitor.TempLevel.SAFE
    value < ThermalLogic.TEMP_WARM -> ThermalMonitor.TempLevel.WARM
    value < ThermalLogic.TEMP_HOT -> ThermalMonitor.TempLevel.HOT
    else -> ThermalMonitor.TempLevel.CRITICAL
}

internal fun targetSpeedFor(temp: Float): Int {
    if (temp.isNaN() || temp < 0f) return 0
    if (temp.isInfinite()) return if (temp > 0f) 100 else 0
    return when {
        temp < ThermalLogic.TEMP_SAFE -> 0
        temp < ThermalLogic.TEMP_WARM -> {
            val ratio = (temp - ThermalLogic.TEMP_SAFE) / (ThermalLogic.TEMP_WARM - ThermalLogic.TEMP_SAFE)
            (25 + ratio * 25).toInt().coerceIn(0, 100)
        }
        temp < ThermalLogic.TEMP_HOT -> {
            val ratio = (temp - ThermalLogic.TEMP_WARM) / (ThermalLogic.TEMP_HOT - ThermalLogic.TEMP_WARM)
            (50 + ratio * 25).toInt().coerceIn(0, 100)
        }
        temp < ThermalLogic.TEMP_CRITICAL -> {
            val ratio = (temp - ThermalLogic.TEMP_HOT) / (ThermalLogic.TEMP_CRITICAL - ThermalLogic.TEMP_HOT)
            (75 + ratio * 25).toInt().coerceIn(0, 100)
        }
        else -> 100
    }
}

private fun ema(previous: Float, raw: Float): Float =
    EMA_ALPHA * raw + (1f - EMA_ALPHA) * previous

private fun trend(previous: Float, previousTimeMs: Long, current: Float, nowMs: Long): Float {
    val dtMs = nowMs - previousTimeMs
    return if (dtMs > 0L) (current - previous) / (dtMs / 1000f) else 0f
}

private fun controlBase(sample: ThermalSample): Float = when {
    sample.skinTemp > 0f -> sample.skinTemp
    sample.batteryTemp > 0f -> sample.batteryTemp
    else -> 0f
}

private fun smoothSignal(previous: Float, previousTimeMs: Long, raw: Float, nowMs: Long, initialized: Boolean): SmoothedSignal {
    val value = if (initialized) ema(previous, raw) else raw
    val rate = if (initialized) trend(previous, previousTimeMs, value, nowMs) else 0f
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
    )

/** Señal del SoC: se suaviza siempre, también antes de confirmar riesgo, para evitar saltos al activarlo. */
internal fun smoothHardware(state: ControlState, sample: ThermalSample, nowMs: Long): SmoothedSignal =
    smoothSignal(
        previous = state.smoothedHardwareTemp,
        previousTimeMs = state.lastHardwareSampleTimeMs,
        raw = sample.hardwarePeakTemp,
        nowMs = nowMs,
        initialized = state.hasHardwareValue,
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

/** Pico del SoC (CPU/GPU), nunca de batería, cargador o módem. */
internal fun hardwarePeak(zones: Map<String, Float>): Float =
    zones.asSequence().filter { (key, temp) ->
        val normalized = key.lowercase()
        temp in 0f..115f &&
            (normalized.contains("cpu") || normalized.contains("tsens") ||
                normalized.contains("gpu") || normalized.contains("kgsl") ||
                normalized.contains("soc")) &&
            !normalized.contains("skin") && !normalized.contains("case") &&
            !normalized.contains("battery") && !normalized.contains("bms") &&
            !normalized.contains("charger") && !normalized.contains("modem")
    }.maxOfOrNull { it.value } ?: 0f

internal fun aggregateThermalZones(readings: List<Pair<String, Float>>): Map<String, Float> {
    val result = LinkedHashMap<String, Float>()
    for ((type, temp) in readings) {
        val existing = result[type]
        if (existing == null || temp > existing) result[type] = temp
    }
    return result
}

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

    val socHot = sample.hardwarePeakTemp >= ThermalLogic.TEMP_HOT ||
        sample.thermalStatus >= ThermalLogic.THERMAL_STATUS_MODERATE

    if (state.criticalActive) {
        val cooling = !socHot && sample.hardwarePeakTemp < ThermalLogic.CRITICAL_EXIT_TEMP
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
): ThermalDecision {
    val critical = updateCritical(state, sample, nowMs, advance)
    val comfort = smooth(state, sample, nowMs)
    val hardware = smoothHardware(state, sample, nowMs)

    // Una sola fuente activa: SoC cuando hay riesgo, confort en caso contrario.
    // Ambas se han suavizado previamente, así que cambiar de fuente no produce un salto.
    val control = if (critical.active && sample.hardwarePeakTemp > 0f) hardware else comfort
    val maxTemp = control.value
    val targetSpeed = targetSpeedFor(control.predicted)

    val current = state.lastRecommendedSpeed
    val increment = if (critical.active) ThermalLogic.EMERGENCY_INCREMENT else ThermalLogic.SPEED_INCREMENT
    val delayMs = if (targetSpeed >= current) {
        if (critical.active) ThermalLogic.EMERGENCY_INCREASE_DELAY_MS else ThermalLogic.INCREASE_DELAY_MS
    } else {
        ThermalLogic.DECREASE_DELAY_MS
    }

    var speed = current
    val elapsed = nowMs - state.lastSpeedChangeTimeMs
    if (targetSpeed > current && elapsed >= delayMs) {
        speed = (current + increment).coerceAtMost(targetSpeed)
    } else if (targetSpeed < current &&
        (targetSpeed == 0 || current - targetSpeed > DOWN_DEADBAND_PERCENT) &&
        elapsed >= delayMs
    ) {
        speed = (current - ThermalLogic.SPEED_INCREMENT).coerceAtLeast(targetSpeed)
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

    val level = tempLevelForSignal(maxTemp)
    return ThermalDecision(
        speed = speed,
        maxTemp = maxTemp,
        tempLevel = level,
        hardwarePeakTemp = sample.hardwarePeakTemp,
        isRiskConfirmed = critical.active,
        adaptiveIntervalMs = adaptiveInterval(level, sample.hardwarePeakTemp),
        state = nextState,
    )
}
