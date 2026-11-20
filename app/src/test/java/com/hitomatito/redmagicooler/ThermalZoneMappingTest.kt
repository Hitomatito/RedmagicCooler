package com.hitomatito.redmagicooler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalZoneMappingTest {

    @Test
    fun `clasifica las zonas reales del Xiaomi vili`() {
        val expected = mapOf(
            "pa_therm0" to ThermalZoneRole.MODEM,
            "modem-mmw0-usr" to ThermalZoneRole.MODEM,
            "modem-skin-usr" to ThermalZoneRole.MODEM,
            "mdmss-1-step" to ThermalZoneRole.MODEM,
            "cpu-0-0-step" to ThermalZoneRole.CPU,
            "cpuss-0-usr" to ThermalZoneRole.CPU,
            "gpuss-0-step" to ThermalZoneRole.GPU,
            "quiet_therm" to ThermalZoneRole.SKIN,
            "socd" to ThermalZoneRole.SOC,
            "battery" to ThermalZoneRole.BATTERY,
            "charger_therm0" to ThermalZoneRole.CHARGER,
            "usb" to ThermalZoneRole.CHARGER,
            "nspss-0-step" to ThermalZoneRole.OTHER,
            "aoss-0-usr" to ThermalZoneRole.OTHER,
            "vbat" to ThermalZoneRole.OTHER,
            "pm8350b-ibat-lvl0" to ThermalZoneRole.OTHER,
            "pm8350c-bcl-lvl0" to ThermalZoneRole.OTHER,
        )

        expected.forEach { (type, role) ->
            assertEquals("Rol inesperado para $type", role, classifyZoneRole(type))
        }
    }

    @Test
    fun `bateria y cargador no se confunden con el SoC`() {
        assertEquals(ThermalZoneRole.BATTERY, classifyZoneRole("bms_soc"))
        assertEquals(ThermalZoneRole.CHARGER, classifyZoneRole("soc_charger"))
        assertEquals(ThermalZoneRole.BATTERY, classifyZoneRole("battery"))
    }

    @Test
    fun `la piel del modem no es la piel del telefono`() {
        assertEquals(ThermalZoneRole.MODEM, classifyZoneRole("modem-skin-usr"))
        assertEquals(ThermalZoneRole.SKIN, classifyZoneRole("quiet_therm"))
    }

    @Test
    fun `filtra temperaturas no plausibles`() {
        assertFalse(isPlausibleZoneTemp(4.185f))
        assertFalse(isPlausibleZoneTemp(0f))
        assertFalse(isPlausibleZoneTemp(Float.NaN))
        assertFalse(isPlausibleZoneTemp(116f))
        assertTrue(isPlausibleZoneTemp(5f))
        assertTrue(isPlausibleZoneTemp(30f))
        assertTrue(isPlausibleZoneTemp(115f))
    }

    @Test
    fun `agrega por rol tomando el maximo`() {
        val readings = listOf(
            ThermalZoneReading(0, "cpu-0-0-usr", 45f),
            ThermalZoneReading(1, "cpu-0-1-usr", 52f),
            ThermalZoneReading(2, "gpuss-0-usr", 48f),
            ThermalZoneReading(3, "quiet_therm", 33f),
            ThermalZoneReading(4, "battery", 30f),
        )

        val roles = aggregateZoneRoles(readings)

        assertEquals(52f, roles[ThermalZoneRole.CPU]!!, 0.001f)
        assertEquals(48f, roles[ThermalZoneRole.GPU]!!, 0.001f)
        assertEquals(33f, roles[ThermalZoneRole.SKIN]!!, 0.001f)
        assertEquals(30f, roles[ThermalZoneRole.BATTERY]!!, 0.001f)
    }

    @Test
    fun `el override por indice gana sobre el tipo`() {
        val profile = ThermalZoneProfile(byIndex = mapOf(2 to ThermalZoneRole.CPU))
        val roles = aggregateZoneRoles(
            listOf(ThermalZoneReading(2, "gpuss-0-usr", 50f)),
            profile,
        )

        assertEquals(50f, roles[ThermalZoneRole.CPU]!!, 0.001f)
        assertNull(roles[ThermalZoneRole.GPU])
    }

    @Test
    fun `el override por tipo gana sobre el clasificador`() {
        val profile = ThermalZoneProfile(byType = mapOf("quiet_therm" to ThermalZoneRole.OTHER))
        val roles = aggregateZoneRoles(
            listOf(ThermalZoneReading(86, "quiet_therm", 33f)),
            profile,
        )

        assertEquals(33f, roles[ThermalZoneRole.OTHER]!!, 0.001f)
        assertNull(roles[ThermalZoneRole.SKIN])
    }

    @Test
    fun `el pico hardware ignora piel bateria carga y modem`() {
        val peak = hardwarePeakForRoles(
            mapOf(
                ThermalZoneRole.CPU to 50f,
                ThermalZoneRole.GPU to 58f,
                ThermalZoneRole.SKIN to 70f,
                ThermalZoneRole.BATTERY to 80f,
                ThermalZoneRole.MODEM to 75f,
                ThermalZoneRole.CHARGER to 65f,
            ),
        )

        assertEquals(58f, peak, 0.001f)
    }

    @Test
    fun `el inventario de zonas incluye el rol`() {
        val text = zoneInventoryText(
            mapOf(0 to "cpu-0-0-usr", 1 to "battery"),
            ThermalZoneProfile(),
        )

        assertEquals("0:cpu-0-0-usr:CPU,1:battery:BATTERY", text)
    }

    @Test
    fun `el perfil por modelo de vili se aplica`() {
        val profile = ThermalZoneDefaults.forModel("Xiaomi|2107113SG|vili")

        assertEquals(ThermalZoneRole.SKIN, profile.byType["quiet_therm"])
        assertEquals(ThermalZoneRole.SOC, profile.byType["socd"])
        assertTrue(ThermalZoneDefaults.forModel("otro|modelo|x").byType.isEmpty())
    }
}
