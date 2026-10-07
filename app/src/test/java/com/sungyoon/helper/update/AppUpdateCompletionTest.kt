package com.sungyoon.helper.update

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCompletionTest {
    @Test
    fun clearsAllPendingInstallStateAfterTheTargetVersionRuns() {
        val record = pendingRecord(target = 18)
        record["unrelated"] = "keep"
        var removedArtifacts: Pair<Long, String?>? = null

        assertTrue(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { id, file ->
            removedArtifacts = id to file
        })

        assertEquals(123L to "update.apk", removedArtifacts)
        assertEquals(mapOf("unrelated" to "keep"), record)
        assertFalse(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { _, _ ->
            error("A completed record must not be cleaned twice")
        })
    }

    @Test
    fun retainsDownloadAndPermissionStateUntilANewerTargetIsInstalled() {
        val record = pendingRecord(target = 19)
        val before = record.toMap()

        assertFalse(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { _, _ ->
            error("An unfinished or canceled installation must retain its APK")
        })
        assertEquals(before, record)
    }

    @Test
    fun clearsOlderCompletedTargetsEvenAfterTheReceiverClearedTheDownloadId() {
        val record = pendingRecord(target = 17).apply { this["download_id"] = -1L }

        assertTrue(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { id, file ->
            assertEquals(-1L, id)
            assertEquals("update.apk", file)
        })
        assertTrue(record.isEmpty())
    }

    @Test
    fun doesNotTreatAMissingOrInvalidTargetAsSuccessfulInstallation() {
        listOf(null, -1, 0).forEach { target ->
            val record = pendingRecord(target = 18)
            if (target == null) record.remove("version_code") else record["version_code"] = target
            val before = record.toMap()

            assertFalse(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { _, _ ->
                error("An unknown target does not prove installation succeeded")
            })
            assertEquals(before, record)
        }
    }

    @Test
    fun clearsCompletedStateEvenWhenDownloadArtifactRemovalFails() {
        val record = pendingRecord(target = 18)

        assertTrue(AppUpdateManager.clearInstalledUpdate(preferences(record), 18) { _, _ ->
            throw SecurityException("The download record is no longer accessible")
        })
        assertTrue(record.isEmpty())
    }

    private fun pendingRecord(target: Int): MutableMap<String, Any> = mutableMapOf(
        "version_code" to target,
        "download_id" to 123L,
        "file_name" to "update.apk",
        "asset_size_bytes" to 9000L,
        "awaiting_install_permission" to true,
    )

    /** Exercise the real preference-removal code without an Android runtime or filesystem. */
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val removals = mutableSetOf<String>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "remove" -> { removals += args!![0] as String; proxy }
                "apply" -> { removals.forEach(values::remove); removals.clear(); null }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt", "getLong", "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
    }
}
