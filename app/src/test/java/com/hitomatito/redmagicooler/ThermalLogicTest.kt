package com.hitomatito.redmagicooler

import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.CRITICAL
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.HOT
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.SAFE
import com.hitomatito.redmagicooler.ThermalMonitor.TempLevel.WARM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalLogicTest {

    private fun sample(
        batteryTemp: Float = 0f,
        skinTemp: Float = 0f,
        hardwarePeakTemp: Float = 0f,
        thermalStatus: Int = 0,
        charging: Boolean = false,
        screenInteractive: Boolean = true,
        cpuTemp: Float = 0f,
        gpuTemp: Float = 0f,
    ) = ThermalSample(
        batteryTemp = batteryTemp,
        cpuTemp = cpuTemp,
        gpuTemp = gpuTemp,
        skinTemp = skinTemp,
        hardwarePeakTemp = hardwarePeakTemp,
        thermalStatus = thermalStatus,
        charging = charging,
        screenInteractive = screenInteractive,
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
        assertEquals(20, targetSpeedFor(40f))
        assertEquals(32, targetSpeedFor(45f))
        assertEquals(45, targetSpeedFor(50f))
        assertEquals(65, targetSpeedFor(55f))
        assertEquals(100, targetSpeedFor(60f))
        assertEquals(100, targetSpeedFor(80f))
    }

    @Test
    fun `guardias numericas en velocidad y nivel de senal`() {
        assertEquals(0, targetSpeedFor(Float.NaN))
        assertEquals(100, targetSpeedFor(Float.POSITIVE_INFINITY))
        assertEquals(0, targetSpeedFor(Float.NEGATIVE_INFINITY))
        assertEquals(0, targetSpeedFor(-10f))
        assertEquals(0, targetSpeedFor(-0.1f))

        assertEquals(SAFE, tempLevelForSignal(Float.NaN))
        assertEquals(SAFE, tempLevelForSignal(Float.NEGATIVE_INFINITY))
        assertEquals(CRITICAL, tempLevelForSignal(Float.POSITIVE_INFINITY))
        assertEquals(SAFE, tempLevelForSignal(-5f))
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
    fun `rampa normal espera 30 segundos y sube de diez`() {
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
    fun `intervalo adaptativo`() {
        assertEquals(45_000L, adaptiveInterval(SAFE))
        assertEquals(20_000L, adaptiveInterval(WARM))
        assertEquals(10_000L, adaptiveInterval(HOT))
        assertEquals(5_000L, adaptiveInterval(CRITICAL))
        assertEquals(10_000L, adaptiveInterval(SAFE, 50f))
    }

    @Test
    fun `fusion del soc reacciona antes sin usar el pico crudo`() {
        val decision = decide(
            state(speed = 40),
            sample(skinTemp = 40f, hardwarePeakTemp = 60f),
            30_000L,
            advance = true,
        )
        // SoC 60 -> equivalente 50 -> objetivo 45 (ni 20 de la piel ni 100 del crudo).
        assertEquals(45, decision.speed)
        assertEquals(60f, decision.maxTemp, 0.001f)
        assertTrue(!decision.isRiskConfirmed)
        assertEquals("soc", decision.controlSource)
    }

    @Test
    fun `estado termico severo exige suelo y emergencia`() {
        val decision = decide(
            state(speed = 20),
            sample(skinTemp = 30f, thermalStatus = 3),
            30_000L,
            advance = true,
        )
        assertTrue(decision.isRiskConfirmed)
        assertEquals(40, decision.speed)
    }

    @Test
    fun `estado termico moderado aplica suelo sin confirmar riesgo`() {
        val decision = decide(
            ControlState(),
            sample(skinTemp = 30f, thermalStatus = 2),
            60_000L,
            advance = true,
        )
        assertTrue(!decision.isRiskConfirmed)
        assertEquals(10, decision.speed)
        assertEquals("status", decision.controlSource)
    }

    @Test
    fun `en silencio el suelo del SO sube con tiempos normales`() {
        // 35% con status 2 (suelo 40) y preferencia 100: el salto al suelo de
        // seguridad NO espera los 45 s del modo silencio; usa el dwell normal.
        val st = state(speed = 35, speedChangeTimeMs = 20_000L)
        val decision = decide(
            st,
            sample(skinTemp = 30f, thermalStatus = 2),
            55_000L,
            advance = false,
            tuning = ThermalTuning(noisePreference = 100),
        )
        assertTrue(!decision.isRiskConfirmed)
        assertEquals(40, decision.speed)
    }

    @Test
    fun `tras caer a cero la subida respeta el dwell y no bombea`() {
        // Acaba de caer a 0 en t=20_000; una demanda transitoria 15 s después
        // no debe relanzar el ventilador (con la rodilla de la curva inestable
        // esto produciría on/off con periodo de un sample).
        val state = state(speed = 0, speedChangeTimeMs = 20_000L)
        val early = decide(state, sample(skinTemp = 42f), 35_000L, advance = false)
        assertEquals(0, early.speed)

        // Pasado INCREASE_DELAY_MS (30 s desde el último cambio) sí arranca.
        val late = decide(state, sample(skinTemp = 42f), 55_000L, advance = false)
        assertEquals(10, late.speed)
    }

    @Test
    fun `cargar con pantalla encendida sube el sesgo`() {
        val charging = decide(
            state(speed = 25),
            sample(skinTemp = 44f, charging = true),
            30_000L,
            advance = true,
        )
        val idle = decide(
            state(speed = 25),
            sample(skinTemp = 44f),
            30_000L,
            advance = true,
        )
        assertEquals(35, charging.speed)
        assertEquals(30, idle.speed)
    }

    @Test
    fun `pantalla apagada amortigua el soc`() {
        val off = decide(
            state(speed = 40),
            sample(skinTemp = 30f, hardwarePeakTemp = 70f, screenInteractive = false),
            30_000L,
            advance = true,
        )
        val on = decide(
            state(speed = 40),
            sample(skinTemp = 30f, hardwarePeakTemp = 70f, screenInteractive = true),
            30_000L,
            advance = true,
        )
        assertEquals(40, off.speed)
        assertEquals(50, on.speed)
    }

    @Test
    fun `no baja mientras la demanda sigue subiendo`() {
        val st = state(speed = 80, smoothed = 40f, hasSmoothed = true)
        val decision = decide(st, sample(skinTemp = 50f), 10_000L, advance = true)
        assertEquals(80, decision.speed)
    }

    @Test
    fun `cpu y gpu alimentan la senal del soc`() {
        val decision = decide(
            state(speed = 40),
            sample(cpuTemp = 52f, gpuTemp = 62f),
            30_000L,
            advance = true,
        )
        // GPU 62 -> equivalente 51.3 -> objetivo 50; el maximo crudo es 62.
        assertEquals(50, decision.speed)
        assertEquals(62f, decision.maxTemp, 0.001f)
    }

    @Test
    fun `la preferencia silenciosa sube mas lento`() {
        val st = state(speed = 20)
        val at30 = decide(
            st,
            sample(skinTemp = 55f),
            30_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 100),
        )
        assertEquals(20, at30.speed)

        val at45 = decide(
            st,
            sample(skinTemp = 55f),
            45_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 100),
        )
        assertEquals(25, at45.speed)
    }

    @Test
    fun `la preferencia de rendimiento sube antes`() {
        val st = state(speed = 20)
        val at10 = decide(
            st,
            sample(skinTemp = 55f),
            10_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 0),
        )
        assertEquals(20, at10.speed)

        val at20 = decide(
            st,
            sample(skinTemp = 55f),
            20_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 0),
        )
        assertEquals(35, at20.speed)
    }

    @Test
    fun `el silencio baja la demanda y el rendimiento la sube`() {
        val quiet = decide(
            state(speed = 50),
            sample(skinTemp = 50f),
            45_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 100),
        )
        val performance = decide(
            state(speed = 50),
            sample(skinTemp = 50f),
            20_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 0),
        )
        val emergency = decide(
            state(speed = 20, criticalActive = true),
            sample(skinTemp = 55f),
            60_000L,
            advance = true,
            tuning = ThermalTuning(noisePreference = 100),
        )

        assertEquals(40, quiet.speed)
        assertEquals(55, performance.speed)
        // La emergencia ignora la preferencia de ruido.
        assertEquals(40, emergency.speed)
    }

    @Test
    fun `el gate de escritura suprime el eco pero rearma tras desconexion`() {
        // Objetivo 40, eco reportando 45 (RPM transitorio del arranque), ultima
        // comandada 40: no se reescribe mientras el ventilador estabiliza.
        assertFalse(shouldWriteToCooler(recommended = 40, current = 45, lastCommanded = 40))
        // Objetivo igual a la velocidad actual: tampoco hace falta escribir.
        assertFalse(shouldWriteToCooler(recommended = 40, current = 40, lastCommanded = 40))
        // Objetivo nuevo del controlador: manda el comando.
        assertTrue(shouldWriteToCooler(recommended = 50, current = 40, lastCommanded = 40))
        // Tras el reconectado lastCommanded vuelve a -1: el cooler queda re-arma
        // aunque el eco ya informe la velocidad recomendada.
        assertTrue(shouldWriteToCooler(recommended = 50, current = 0, lastCommanded = -1))
    }
}
