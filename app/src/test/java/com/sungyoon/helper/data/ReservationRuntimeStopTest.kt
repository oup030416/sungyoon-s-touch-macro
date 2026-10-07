package com.sungyoon.helper.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationRuntimeStopTest {
    private val active = booleanPreferencesKey("active")
    private val paused = booleanPreferencesKey("paused")
    private val remaining = longPreferencesKey("paused_remaining_ms")
    private val nextPoint = intPreferencesKey("next_point_offset")
    private val status = stringPreferencesKey("status_text")

    @Test
    fun conditionalStopClearsCommittedActiveStateEvenWithoutServiceCache() {
        val prefs = mutablePreferencesOf(active to true, paused to true, remaining to 1200L,
            nextPoint to 5, status to "running")
        assertTrue(stop(prefs, "cancelled", onlyWhenActive = true))
        assertEquals(false, prefs[active])
        assertEquals(false, prefs[paused])
        assertEquals(0L, prefs[remaining])
        assertEquals(0, prefs[nextPoint])
        assertEquals("cancelled", prefs[status])
    }

    @Test
    fun conditionalStopPreservesIdleStatusAndDoesNotInventAnActiveRecord() {
        val prefs = mutablePreferencesOf(status to "waiting")
        val original = prefs.asMap().toMap()
        assertFalse(stop(prefs, "cancelled", onlyWhenActive = true))
        assertEquals(original, prefs.asMap())
    }

    @Test
    fun explicitStopRetainsItsExistingUnconditionalResetSemantics() {
        val prefs = mutablePreferencesOf(active to false, paused to true, remaining to 500L,
            nextPoint to 3, status to "waiting")
        assertTrue(stop(prefs, "interrupted", onlyWhenActive = false))
        assertEquals(false, prefs[active])
        assertEquals(false, prefs[paused])
        assertEquals(0L, prefs[remaining])
        assertEquals(0, prefs[nextPoint])
        assertEquals("interrupted", prefs[status])
    }

    private fun stop(prefs: MutablePreferences, finalStatus: String, onlyWhenActive: Boolean): Boolean {
        val method = ReservationRuntimeStore.javaClass.getDeclaredMethod("stopPreferences",
            MutablePreferences::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(ReservationRuntimeStore, prefs, finalStatus, onlyWhenActive) as Boolean
    }
}
