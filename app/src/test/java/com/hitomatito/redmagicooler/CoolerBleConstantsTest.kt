package com.hitomatito.redmagicooler

import com.hitomatito.redmagicooler.model.CoolerBleConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class CoolerBleConstantsTest {

    @Test
    fun `percentageToRaw maps endpoints onto the raw hardware range`() {
        assertEquals(40, CoolerBleConstants.percentageToRaw(0))
        assertEquals(60, CoolerBleConstants.percentageToRaw(50))
        assertEquals(80, CoolerBleConstants.percentageToRaw(100))
    }

    @Test
    fun `percentageToRaw clamps out of range inputs`() {
        assertEquals(40, CoolerBleConstants.percentageToRaw(-10))
        assertEquals(80, CoolerBleConstants.percentageToRaw(150))
    }

    @Test
    fun `rawToPercentage maps endpoints and clamps`() {
        assertEquals(0, CoolerBleConstants.rawToPercentage(0))
        assertEquals(0, CoolerBleConstants.rawToPercentage(40))
        assertEquals(50, CoolerBleConstants.rawToPercentage(60))
        assertEquals(100, CoolerBleConstants.rawToPercentage(80))
        assertEquals(100, CoolerBleConstants.rawToPercentage(120))
    }
}
