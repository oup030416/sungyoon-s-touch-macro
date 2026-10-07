package com.sungyoon.helper.overlay.set

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetWaitDurationInputTest {
    @Test
    fun validSecondsParseExactlyWithoutFloatingPointRounding() {
        assertEquals(100L, SetWaitDurationInput.parseSeconds("0.1"))
        assertEquals(100L, SetWaitDurationInput.parseSeconds(".1"))
        assertEquals(300L, SetWaitDurationInput.parseSeconds("0.3"))
        assertEquals(1000L, SetWaitDurationInput.parseSeconds("1"))
        assertEquals(1000L, SetWaitDurationInput.parseSeconds("1.00"))
        assertEquals(3_600_000L, SetWaitDurationInput.parseSeconds("3600.0"))
    }

    @Test
    fun incompleteAndInvalidValuesNeverProduceASave() {
        listOf(null, "", " ", ".", "0", "-0.1", "0.05", "1.01", "1.001", "3600.1", "abc", "1.2.3", "999999999999999999999")
            .forEach { assertNull("Unexpected valid input: $it", SetWaitDurationInput.parseSeconds(it)) }
    }

    @Test
    fun savedValuesRoundTripInTheEditorsDecimalFormat() {
        listOf(100L, 300L, 1000L, 3_600_000L).forEach { milliseconds ->
            assertEquals(milliseconds, SetWaitDurationInput.parseSeconds(SetWaitDurationInput.formatSeconds(milliseconds)))
        }
        assertEquals("1.0", SetWaitDurationInput.formatSeconds(1000))
    }
}
