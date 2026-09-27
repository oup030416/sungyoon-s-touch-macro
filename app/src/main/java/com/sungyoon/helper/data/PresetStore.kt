package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sungyoon.helper.model.PresetEntry
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.presetDataStore by preferencesDataStore(name = "sungyoon_helper_presets")

object PresetStore {
    internal const val LEGACY_HOLD_PRESET_ID = "builtin_touch_hold"

    private val KEY_PRESETS = stringPreferencesKey("presets_json")
    private val KEY_NEXT_AUTO_NAME_ORDINAL = intPreferencesKey("next_auto_name_ordinal")
    private val KEY_ACTIVE_ID = stringPreferencesKey("active_preset_id")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val serializer = ListSerializer(PresetEntry.serializer())

    data class Snapshot(
        val entries: List<PresetEntry>,
        val activeId: String? = null,
        val nextOrdinal: Int = 0
    )

    // Decoding must fail before any edit. Corrupt input is never an empty preset list.
    internal fun decodeEntries(raw: String?): List<PresetEntry> {
        if (raw == null) return emptyList()
        val entries = json.decodeFromString(serializer, raw)
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate preset IDs" }
        require(entries.all { entry -> entry.points.all { p ->
            p.x.isFinite() && p.y.isFinite() && p.dragToX.isFinite() && p.dragToY.isFinite()
        } }) { "Invalid preset coordinates" }
        return entries
    }

    internal fun encodeEntries(entries: List<PresetEntry>): String = json.encodeToString(serializer, entries)

    suspend fun read(context: Context): Snapshot {
        val prefs = context.presetDataStore.data.first()
        return Snapshot(decodeEntries(prefs[KEY_PRESETS]), prefs[KEY_ACTIVE_ID], prefs[KEY_NEXT_AUTO_NAME_ORDINAL] ?: 0)
    }

    internal suspend fun update(context: Context, transform: (Snapshot) -> Snapshot): Snapshot {
        var result: Snapshot? = null
        context.presetDataStore.edit { prefs ->
            val old = Snapshot(decodeEntries(prefs[KEY_PRESETS]), prefs[KEY_ACTIVE_ID], prefs[KEY_NEXT_AUTO_NAME_ORDINAL] ?: 0)
            val next = transform(old)
            val encoded = encodeEntries(next.entries)
            decodeEntries(encoded)
            require(next.activeId == null || next.entries.any { it.id == next.activeId })
            if (old.entries != next.entries) prefs[KEY_PRESETS] = encoded
            if (next.activeId == null) prefs.remove(KEY_ACTIVE_ID) else prefs[KEY_ACTIVE_ID] = next.activeId
            if (old.nextOrdinal != next.nextOrdinal) prefs[KEY_NEXT_AUTO_NAME_ORDINAL] = next.nextOrdinal
            result = next
        }
        return checkNotNull(result)
    }

    suspend fun deletePreset(context: Context, presetId: String): Boolean {
        var deleted = false
        update(context) { old ->
            deleted = old.entries.any { it.id == presetId }
            old.copy(entries = old.entries.filterNot { it.id == presetId },
                activeId = old.activeId.takeUnless { it == presetId })
        }
        return deleted
    }

    /** Removes only the retired built-in entry, including when it was renamed. */
    internal fun withoutLegacyHold(snapshot: Snapshot) = snapshot.copy(
        entries = snapshot.entries.filterNot { it.id == LEGACY_HOLD_PRESET_ID },
        activeId = snapshot.activeId.takeUnless { it == LEGACY_HOLD_PRESET_ID }
    )

    internal fun sortEntries(entries: List<PresetEntry>): List<PresetEntry> = entries.sortedWith(
        compareByDescending<PresetEntry> { it.createdAtEpochMs }.thenByDescending { it.autoNameOrdinal }
    )

    internal fun autoNameForOrdinal(ordinal: Int): String {
        var value = ordinal.coerceAtLeast(0)
        val name = StringBuilder()
        do {
            name.append(('A'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return name.reverse().toString()
    }
}
