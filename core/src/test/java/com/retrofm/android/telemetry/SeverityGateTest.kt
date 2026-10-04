package com.retrofm.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeverityGateTest {

    @Test
    fun `before the edge answers, everything is recorded and nothing may leave`() {
        val gate = SeverityGate()
        assertFalse(gate.known)
        assertNull(gate.levelName)
        listOf(1, 5, 9, 13, 17, 21).forEach {
            assertTrue(gate.admitsAtEmit(it))
            assertFalse(gate.admitsForSend(it))
        }
    }

    @Test
    fun `the edge's floors — DEBUG 5, INFO 9, WARN 13, ERROR 17`() {
        val gate = SeverityGate()
        assertTrue(gate.apply("WARN"))
        assertFalse(gate.admitsForSend(9))
        assertFalse(gate.admitsForSend(12))
        assertTrue(gate.admitsForSend(13))
        assertTrue(gate.admitsForSend(17))
        assertFalse(gate.admitsAtEmit(5))

        assertTrue(gate.apply("debug"))
        assertTrue(gate.admitsForSend(5))
        assertFalse(gate.admitsForSend(4))
        assertEquals("DEBUG", gate.levelName)
    }

    @Test
    fun `an unspecified severity counts as INFO, as the edge counts it`() {
        val gate = SeverityGate()
        gate.apply("INFO")
        assertTrue(gate.admitsForSend(0))
        gate.apply("WARN")
        assertFalse(gate.admitsForSend(0))
    }

    @Test
    fun `an unknown level name changes nothing`() {
        val gate = SeverityGate()
        gate.apply("ERROR")
        assertFalse(gate.apply("VERBOSE"))
        assertFalse(gate.apply(null))
        assertEquals("ERROR", gate.levelName)
        assertFalse(gate.admitsForSend(13))
    }
}
