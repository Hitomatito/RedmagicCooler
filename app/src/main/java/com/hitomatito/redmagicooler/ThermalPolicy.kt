package com.hitomatito.redmagicooler

/**
 * Política térmica pura: curva objetivo, niveles, rampa progresiva e
 * intervalo adaptativo. Sin dependencias de Android para poder probarla.
 *
 * El monitor ([ThermalMonitor]) sólo lee sensores y delega aquí. El estado
 * mutable de la rampa vive en [rampState] y se avanza únicamente dentro del
 * bucle de monitoreo; las instantáneas de solo lectura no lo modifican.
 */
object ThermalPolicy {

    const val TEMP_SAFE = 40f
    const val TEMP_WARM = 50f
    const val TEMP_HOT = 55f
    const val TEMP_CRITICAL = 60f

    const val SPEED_INCREMENT = 10
    const val EMERGENCY_INCREMENT = 20
    const val MIN_TIME_AT_SPEED_MS = 30_000L
    const val PROGRESSIVE_INCREASE_DELAY_MS = 20_000L
    const val EMERGENCY_INCREASE_DELAY_MS = 10_000L

    /** Tiempo mínimo de riesgo alto sostenido para habilitar la rampa de emergencia. */
    const val CRITICAL_SUSTAINED_MS = 45_000L

    /** Curva objetivo ideal según temperatura (aún sin rampa). */
    fun targetSpeed(temp: Float): Int = when {
        temp < TEMP_SAFE -> 0
        temp < TEMP_WARM -> {
            val ratio = (temp - TEMP_SAFE) / (TEMP_WARM - TEMP_SAFE)
            (25 + ratio * 25).toInt().coerceIn(0, 100)
        }
        temp < TEMP_HOT -> {
            val ratio = (temp - TEMP_WARM) / (TEMP_HOT - TEMP_WARM)
            (50 + ratio * 25).toInt().coerceIn(0, 100)
        }
        temp < TEMP_CRITICAL -> {
            val ratio = (temp - TEMP_HOT) / (TEMP_CRITICAL - TEMP_HOT)
            (75 + ratio * 25).toInt().coerceIn(0, 100)
        }
        else -> 100
    }

    /** Nivel de temperatura para pintar estados; crítico desde 55 °C. */
    fun tempLevel(value: Float): ThermalMonitor.TempLevel = when {
        value < TEMP_SAFE -> ThermalMonitor.TempLevel.SAFE
        value < TEMP_WARM -> ThermalMonitor.TempLevel.WARM
        value < TEMP_HOT -> ThermalMonitor.TempLevel.HOT
        else -> ThermalMonitor.TempLevel.CRITICAL
    }

    /** Cadencia de observación adaptada a la temperatura de control y al pico hardware. */
    fun adaptiveIntervalMs(
        tempLevel: ThermalMonitor.TempLevel,
        hardwarePeakTemp: Float = 0f
    ): Long {
        val hardwareLevel = tempLevel(hardwarePeakTemp)
        val effectiveLevel =
            if (hardwareLevel.ordinal > tempLevel.ordinal) hardwareLevel else tempLevel
        return when (effectiveLevel) {
            ThermalMonitor.TempLevel.SAFE -> 45_000L
            ThermalMonitor.TempLevel.WARM -> 20_000L
            ThermalMonitor.TempLevel.HOT -> 10_000L
            ThermalMonitor.TempLevel.CRITICAL -> 5_000L
        }
    }

    /** Estado mutable de la rampa; lo avanza únicamente el bucle de monitoreo. */
    class RampState {
        var currentSpeed: Int = 0
            private set
        var lastSpeedChangeTimeMs: Long = 0L
            private set
        var timeAtCurrentSpeedMs: Long = 0L
            private set

        private fun update(nextSpeed: Int, nowMs: Long) {
            currentSpeed = nextSpeed
            lastSpeedChangeTimeMs = nowMs
            timeAtCurrentSpeedMs = nowMs
        }

        /**
         * Inicializa la velocidad desde el dispositivo (p.ej. calibración) para no chocar con la curva.
         * Sella los timestamps para no saltar las guardas de permanencia en la primera lectura.
         */
        fun seed(speedPercent: Int, nowMs: Long) {
            currentSpeed = speedPercent.coerceIn(0, 100)
            timeAtCurrentSpeedMs = nowMs
            lastSpeedChangeTimeMs = nowMs
        }

        /** Avanza la rampa progresiva en función de la temperatura y devuelve la velocidad comandada. */
        fun advance(targetSpeed: Int, isRiskConfirmed: Boolean, nowMs: Long): Int {
            val increment = if (isRiskConfirmed) EMERGENCY_INCREMENT else SPEED_INCREMENT
            val increaseDelay =
                if (isRiskConfirmed) EMERGENCY_INCREASE_DELAY_MS else PROGRESSIVE_INCREASE_DELAY_MS

            return if (targetSpeed > currentSpeed) {
                val timeAtSpeed = nowMs - timeAtCurrentSpeedMs
                val sinceLastIncrease = nowMs - lastSpeedChangeTimeMs
                if (timeAtSpeed >= MIN_TIME_AT_SPEED_MS && sinceLastIncrease >= increaseDelay) {
                    val nextSpeed = (currentSpeed + increment).coerceAtMost(targetSpeed)
                    if (nextSpeed != currentSpeed) update(nextSpeed, nowMs)
                }
                currentSpeed
            } else if (targetSpeed < currentSpeed) {
                val timeAtSpeed = nowMs - timeAtCurrentSpeedMs
                val sinceLastChange = nowMs - lastSpeedChangeTimeMs
                if (timeAtSpeed >= MIN_TIME_AT_SPEED_MS && sinceLastChange >= PROGRESSIVE_INCREASE_DELAY_MS) {
                    val nextSpeed = (currentSpeed - SPEED_INCREMENT).coerceAtLeast(targetSpeed)
                    if (nextSpeed != currentSpeed) update(nextSpeed, nowMs)
                }
                currentSpeed
            } else {
                currentSpeed
            }
        }
    }
}

/** Etiqueta única usada en la notificación para cada nivel térmico. */
fun ThermalMonitor.TempLevel.toStatusLabel(): String = when (this) {
    ThermalMonitor.TempLevel.SAFE -> "Normal"
    ThermalMonitor.TempLevel.WARM -> "Tibio"
    ThermalMonitor.TempLevel.HOT -> "Caliente"
    ThermalMonitor.TempLevel.CRITICAL -> "Critico"
}
