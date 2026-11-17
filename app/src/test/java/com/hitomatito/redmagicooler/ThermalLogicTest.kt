package com.hitomatito.redmagicooler

import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.CRITICAL
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.HOT
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.SAFE
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.WARM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalLogicTest {

    private fun sample(
        batteryTemp: Float = 0f,
        skinTemp: Float = 0f,
        hardwarePeakTemp: Float = 0f,
        thermalStatus: Int = 0,
    ) = ThermalSample(
        batteryTemp = batteryTemp,
        skinTemp = skinTemp,
        hardwarePeakTemp = hardwarePeakTemp,
        thermalStatus = thermalStatus,
    )

    private fun state(
        speed: Int = 0,
        riskSince: Long = 0L,
        criticalActive: Boolean = false,
        clearSince: Long = 0L,
        smoothed: Float = 0f,
        hasSmoothed: Boolean = false,
        lastSampleTimeMs: Long = 0L,
        speedChangeTimeMs: Long = 0L,
        smoothedHardware: Float = 0f,
        hasHardware: Boolean = false,
        lastHardwareSampleTimeMs: Long = 0L,
    ) = ControlState(
        lastRecommendedSpeed = speed,
        lastSpeedChangeTimeMs = speedChangeTimeMs,
        hardwareRiskSinceMs = riskSince,
        criticalActive = criticalActive,
        criticalClearSinceMs = clearSince,
        smoothedControlTemp = smoothed,
        hasSmoothedValue = hasSmoothed,
        lastSampleTimeMs = lastSampleTimeMs,
        smoothedHardwareTemp = smoothedHardware,
        hasHardwareValue = hasHardware,
        lastHardwareSampleTimeMs = lastHardwareSampleTimeMs,
    )

    @Test
    fun `niveles en los limites`() {
        assertEquals(SAFE, tempLevelForSignal(39.9f))
        assertEquals(WARM, tempLevelForSignal(40f))
        assertEquals(WARM, tempLevelForSignal(49.9f))
        assertEquals(HOT, tempLevelForSignal(50f))
        assertEquals(HOT, tempLevelForSignal(54.9f))
        assertEquals(CRITICAL, tempLevelForSignal(55f))
    }

    @Test
    fun `interpolacion de velocidad objetivo`() {
        assertEquals(0, targetSpeedFor(39f))
        assertEquals(25, targetSpeedFor(40f))
        assertEquals(37, targetSpeedFor(45f))
        assertEquals(50, targetSpeedFor(50f))
        assertEquals(75, targetSpeedFor(55f))
        assertEquals(100, targetSpeedFor(60f))
        assertEquals(100, targetSpeedFor(80f))
    }

    @Test
    fun `primera muestra inicializa EMA`() {
        val decision = decide(ControlState(), sample(skinTemp = 50f), 1_000L, advance = true)
        assertEquals(50f, decision.maxTemp, 0.001f)
        assertEquals(50f, decision.state.smoothedControlTemp, 0.001f)
        assertEquals(0f, smooth(ControlState(), sample(skinTemp = 50f), 1_000L).trendPerSecond, 0.001f)
    }

    @Test
    fun `EMA suaviza y calcula tendencia`() {
        val signal = smooth(state(smoothed = 50f, hasSmoothed = true), sample(skinTemp = 60f), 10_000L)
        assertEquals(53.5f, signal.value, 0.001f)
        assertEquals(0.35f, signal.trendPerSecond, 0.001f)
        assertEquals(64f, signal.predicted, 0.001f)
    }

    @Test
    fun `tendencia anticipa y dt no positivo es seguro`() {
        val previous = state(smoothed = 40f, hasSmoothed = true)
        val rising = smooth(previous, sample(skinTemp = 50f), 10_000L)
        val stable = smooth(previous, sample(skinTemp = 40f), 10_000L)
        assertTrue(targetSpeedFor(rising.predicted) > targetSpeedFor(stable.predicted))

        val backwards = smooth(
            state(smoothed = 40f, hasSmoothed = true, lastSampleTimeMs = 10_000L),
            sample(skinTemp = 50f),
            5_000L,
        )
        assertEquals(0f, backwards.trendPerSecond, 0.001f)
        assertTrue(backwards.predicted.isFinite())
    }

    @Test
    fun `rampa normal espera 20 segundos y sube de diez`() {
        val st = state(speed = 20)
        assertEquals(20, decide(st, sample(skinTemp = 55f), 10_000L, advance = true).speed)

        val advanced = decide(st, sample(skinTemp = 55f), 30_000L, advance = true)
        assertEquals(30, advanced.speed)
        assertEquals(30_000L, advanced.state.lastSpeedChangeTimeMs)
    }

    @Test
    fun `riesgo confirmado usa incremento de emergencia`() {
        val decision = decide(
            state(speed = 20, criticalActive = true),
            sample(skinTemp = 55f, hardwarePeakTemp = 58f),
            60_000L,
            advance = true,
        )
        assertTrue(decision.isRiskConfirmed)
        assertEquals(40, decision.speed)
        assertEquals(58f, decision.maxTemp, 0.001f)
    }

    @Test
    fun `riesgo confirmado eleva el objetivo segun el pico hardware`() {
        val decision = decide(
            state(speed = 60, criticalActive = true),
            sample(skinTemp = 40f, hardwarePeakTemp = 60f),
            60_000L,
            advance = true,
        )
        assertTrue(decision.isRiskConfirmed)
        assertEquals(60f, decision.maxTemp, 0.001f)
        assertEquals(80, decision.speed)
    }

    @Test
    fun `emergencia sube cada diez segundos`() {
        val decision = decide(
            state(speed = 20, criticalActive = true, speedChangeTimeMs = 50_000L),
            sample(skinTemp = 40f, hardwarePeakTemp = 60f),
            60_000L,
            advance = true,
        )
        assertEquals(40, decision.speed)
    }

    @Test
    fun `riesgo no confirmado antes de 45 segundos`() {
        val decision = decide(
            state(speed = 20, riskSince = 30_000L),
            sample(skinTemp = 50f, hardwarePeakTemp = 58f),
            60_000L,
            advance = true,
        )
        assertTrue(!decision.isRiskConfirmed)
        assertEquals(30, decision.speed)
    }

    @Test
    fun `estado termico del sistema tambien confirma riesgo`() {
        val first = decide(
            state(speed = 20, riskSince = 30_000L),
            sample(skinTemp = 40f, thermalStatus = 2),
            60_000L,
            advance = true,
        )
        assertTrue(!first.isRiskConfirmed)
        val second = decide(
            first.state,
            sample(skinTemp = 40f, thermalStatus = 2),
            80_000L,
            advance = true,
        )
        assertTrue(second.isRiskConfirmed)
    }

    @Test
    fun `salida de riesgo requiere 30 segundos por debajo de 50`() {
        val enBanda = decide(
            state(speed = 100, criticalActive = true),
            sample(skinTemp = 40f, hardwarePeakTemp = 54f),
            60_000L,
            advance = true,
        )
        assertTrue(enBanda.isRiskConfirmed)
        assertEquals(0L, enBanda.state.criticalClearSinceMs)

        val esperando = decide(
            state(speed = 100, criticalActive = true),
            sample(skinTemp = 40f, hardwarePeakTemp = 48f),
            60_000L,
            advance = true,
        )
        assertTrue(esperando.isRiskConfirmed)
        assertEquals(60_000L, esperando.state.criticalClearSinceMs)

        val salido = decide(
            esperando.state,
            sample(skinTemp = 40f, hardwarePeakTemp = 48f),
            95_000L,
            advance = true,
        )
        assertTrue(!salido.isRiskConfirmed)
        assertEquals(0L, salido.state.criticalClearSinceMs)
    }

    @Test
    fun `reentrada exige otros 45 segundos`() {
        val esperando = decide(
            state(speed = 100, criticalActive = true, riskSince = 10_000L),
            sample(skinTemp = 40f, hardwarePeakTemp = 48f),
            125_000L,
            advance = true,
        )
        assertTrue(esperando.isRiskConfirmed)

        val salido = decide(
            esperando.state,
            sample(skinTemp = 40f, hardwarePeakTemp = 48f),
            155_000L,
            advance = true,
        )
        assertTrue(!salido.isRiskConfirmed)
        assertEquals(0L, salido.state.hardwareRiskSinceMs)

        val recalentado = decide(
            salido.state,
            sample(skinTemp = 40f, hardwarePeakTemp = 60f),
            160_000L,
            advance = true,
        )
        assertTrue(!recalentado.isRiskConfirmed)
        assertEquals(160_000L, recalentado.state.hardwareRiskSinceMs)

        val confirmado = decide(
            recalentado.state,
            sample(skinTemp = 40f, hardwarePeakTemp = 60f),
            205_000L,
            advance = true,
        )
        assertTrue(confirmado.isRiskConfirmed)
    }

    @Test
    fun `reduccion gradual con banda muerta`() {
        val st = state(speed = 80, speedChangeTimeMs = 50_000L)
        assertEquals(70, decide(st, sample(skinTemp = 41f), 60_000L, advance = true).speed)

        val nearTarget = state(speed = 30, speedChangeTimeMs = 50_000L)
        assertEquals(30, decide(nearTarget, sample(skinTemp = 41f), 60_000L, advance = true).speed)
    }

    @Test
    fun `velocidad acotada entre cero y cien`() {
        assertEquals(100, decide(state(speed = 95), sample(skinTemp = 60f), 60_000L, advance = true).speed)
        assertEquals(0, decide(state(speed = 5), sample(skinTemp = 39f), 60_000L, advance = true).speed)
    }

    @Test
    fun `advance falso no muta el estado`() {
        val st = state(speed = 20, smoothed = 45f, hasSmoothed = true, lastSampleTimeMs = 10_000L)
        val decision = decide(st, sample(skinTemp = 55f), 60_000L, advance = false)
        assertEquals(30, decision.speed)
        assertEquals(st, decision.state)
    }

    @Test
    fun `zonas con mismo tipo conservan el maximo`() {
        val zones = aggregateThermalZones(listOf("tsens" to 45f, "tsens" to 52f, "cpu" to 40f))
        assertEquals(52f, zones["tsens"]!!, 0.001f)
        assertEquals(40f, zones["cpu"]!!, 0.001f)
    }

    @Test
    fun `pico hardware solo considera zonas del SoC`() {
        val peak = hardwarePeak(
            mapOf(
                "cpu" to 50f,
                "gpu" to 58f,
                "skin" to 70f,
                "case" to 65f,
                "battery" to 80f,
                "pa_therm0" to 75f,
            ),
        )
        assertEquals(58f, peak, 0.001f)
    }

    @Test
    fun `intervalo adaptativo`() {
        assertEquals(45_000L, adaptiveInterval(SAFE))
        assertEquals(20_000L, adaptiveInterval(WARM))
        assertEquals(10_000L, adaptiveInterval(HOT))
        assertEquals(5_000L, adaptiveInterval(CRITICAL))
        assertEquals(10_000L, adaptiveInterval(SAFE, 50f))
    }
}
