package com.hitomatito.redmagicooler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    // ---------- Curva objetivo ----------
    @Test
    fun `targetSpeed below safe returns 0`() {
        assertEquals(0, ThermalPolicy.targetSpeed(0f))
        assertEquals(0, ThermalPolicy.targetSpeed(39.9f))
    }

    @Test
    fun `targetSpeed warm band interpolates 25 to 50`() {
        assertEquals(25, ThermalPolicy.targetSpeed(40f))
        assertEquals(37, ThermalPolicy.targetSpeed(45f))
        assertEquals(49, ThermalPolicy.targetSpeed(49.9f))
    }

    @Test
    fun `targetSpeed hot band interpolates 50 to 75`() {
        assertEquals(50, ThermalPolicy.targetSpeed(50f))
        assertEquals(62, ThermalPolicy.targetSpeed(52.5f))
        assertEquals(74, ThermalPolicy.targetSpeed(54.9f))
    }

    @Test
    fun `targetSpeed critical bands`() {
        assertEquals(75, ThermalPolicy.targetSpeed(55f))
        assertEquals(87, ThermalPolicy.targetSpeed(57.5f))
        assertEquals(100, ThermalPolicy.targetSpeed(60f))
        assertEquals(100, ThermalPolicy.targetSpeed(80f))
    }

    // ---------- Niveles ----------
    @Test
    fun `tempLevel thresholds`() {
        assertEquals(ThermalMonitor.TempLevel.SAFE, ThermalPolicy.tempLevel(39.9f))
        assertEquals(ThermalMonitor.TempLevel.WARM, ThermalPolicy.tempLevel(40f))
        assertEquals(ThermalMonitor.TempLevel.WARM, ThermalPolicy.tempLevel(49.9f))
        assertEquals(ThermalMonitor.TempLevel.HOT, ThermalPolicy.tempLevel(50f))
        assertEquals(ThermalMonitor.TempLevel.HOT, ThermalPolicy.tempLevel(54.9f))
        assertEquals(ThermalMonitor.TempLevel.CRITICAL, ThermalPolicy.tempLevel(55f))
        assertEquals(ThermalMonitor.TempLevel.CRITICAL, ThermalPolicy.tempLevel(100f))
    }

    // ---------- Intervalos adaptativos ----------
    @Test
    fun `adaptiveIntervalMs picks the worst level between control and hardware peak`() {
        assertEquals(45_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.SAFE))
        assertEquals(20_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.WARM))
        assertEquals(10_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.HOT))
        assertEquals(5_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.CRITICAL))
        // Pico hardware alto con nivel de control bajo -> 5 s
        assertEquals(5_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.SAFE, 60f))
        // Pico hardware menor que control -> mantiene control
        assertEquals(20_000L, ThermalPolicy.adaptiveIntervalMs(ThermalMonitor.TempLevel.WARM, 30f))
    }

    // ---------- Rampa progresiva (tiempo desde 0 para determinismo) ----------
    @Test
    fun `ramp does not rise before the dwell time`() {
        val ramp = ThermalPolicy.RampState()
        // Con menos de 30 s transcurridos desde la creación, no hay incremento
        assertEquals(0, ramp.advance(80, isRiskConfirmed = false, nowMs = 25_000L))
        assertEquals(0, ramp.advance(80, isRiskConfirmed = false, nowMs = 29_999L))
    }

    @Test
    fun `ramp rises by the normal increment after dwell`() {
        val ramp = ThermalPolicy.RampState()
        assertEquals(0, ramp.advance(80, isRiskConfirmed = false, nowMs = 20_000L))
        assertEquals(10, ramp.advance(80, isRiskConfirmed = false, nowMs = 35_000L))
    }

    @Test
    fun `ramp rises by the emergency increment once risk is confirmed`() {
        val ramp = ThermalPolicy.RampState()
        assertEquals(0, ramp.advance(100, isRiskConfirmed = true, nowMs = 20_000L))
        assertEquals(20, ramp.advance(100, isRiskConfirmed = true, nowMs = 35_000L))
    }

    @Test
    fun `ramp never exceeds target speed`() {
        val ramp = ThermalPolicy.RampState()
        var t = 0L
        var speed = 0
        repeat(40) {
            t += ThermalPolicy.MIN_TIME_AT_SPEED_MS + ThermalPolicy.PROGRESSIVE_INCREASE_DELAY_MS
            speed = ramp.advance(25, isRiskConfirmed = false, nowMs = t)
        }
        assertEquals(25, speed)
    }

    @Test
    fun `ramp decreases gradually and respects the dwell guard`() {
        val ramp = ThermalPolicy.RampState()
        var t = 0L
        var speed = 0
        // Elevar a 30 %: 25s holgura inicial + pasos de 10 % (30 y 50 s por paso)
        t = 31_000L
        speed = ramp.advance(30, isRiskConfirmed = false, nowMs = t) // 0 -> 10 (dwell cumplido)
        assertEquals(10, speed)
        t += 30_000L
        speed = ramp.advance(30, isRiskConfirmed = false, nowMs = t) // 10 -> 20
        assertEquals(20, speed)
        t += 30_000L
        speed = ramp.advance(30, isRiskConfirmed = false, nowMs = t) // 20 -> 30
        assertEquals(30, speed)
        // Bajada: sin dwell suficiente desde el último cambio, mantiene
        assertEquals(30, ramp.advance(0, isRiskConfirmed = false, nowMs = t + 10_000L))
        // Tras dwell y delay, baja un paso
        val down = ramp.advance(0, isRiskConfirmed = false, nowMs = t + ThermalPolicy.MIN_TIME_AT_SPEED_MS + ThermalPolicy.PROGRESSIVE_INCREASE_DELAY_MS)
        assertEquals(20, down)
    }

    @Test
    fun `seed preserves the dwell guard on the first tick`() {
        val ramp = ThermalPolicy.RampState()
        ramp.seed(80, nowMs = 100_000L)
        // Sembrada hace 1 s: aun no hay permanencia de 30 s, no debe subir
        assertEquals(80, ramp.advance(100, isRiskConfirmed = false, nowMs = 101_000L))
        // Tras el dwell, sube un paso normal
        assertEquals(90, ramp.advance(100, isRiskConfirmed = false, nowMs = 100_000L + ThermalPolicy.MIN_TIME_AT_SPEED_MS + 1_000L))
    }

    @Test
    fun `ramp holds speed when target equals current`() {
        val ramp = ThermalPolicy.RampState()
        assertEquals(0, ramp.advance(0, isRiskConfirmed = false, nowMs = 0L))
        assertEquals(0, ramp.advance(0, isRiskConfirmed = false, nowMs = 5_000L))
    }
}
