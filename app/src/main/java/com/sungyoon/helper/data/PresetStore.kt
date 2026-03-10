package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.PresetPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.presetDataStore by preferencesDataStore(name = "sungyoon_helper_presets")

object PresetStore {
    private val KEY_PRESETS = stringPreferencesKey("presets_json")
    private val KEY_NEXT_AUTO_NAME_ORDINAL = intPreferencesKey("next_auto_name_ordinal")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val listSer = ListSerializer(PresetEntry.serializer())

    @Volatile
    private var cachedRaw: String = ""

    @Volatile
    private var cachedEntries: List<PresetEntry> = emptyList()

    fun presetsFlow(context: Context): Flow<List<PresetEntry>> {
        return context.presetDataStore.data
            .map { prefs -> decodeEntries(prefs[KEY_PRESETS].orEmpty()) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun addPreset(context: Context, sourcePoints: List<HighlightingPoint>): PresetEntry {
        var created: PresetEntry? = null

        context.presetDataStore.edit { prefs ->
            val current = decodeEntries(prefs[KEY_PRESETS].orEmpty())
            val ordinal = (prefs[KEY_NEXT_AUTO_NAME_ORDINAL] ?: 0).coerceAtLeast(0)
            val now = System.currentTimeMillis()
            val entry = PresetEntry(
                name = autoNameForOrdinal(ordinal),
                createdAtEpochMs = now,
                points = sourcePoints
                    .sortedBy { it.index }
                    .map { point ->
                        PresetPoint(
                            index = point.index,
                            actionType = point.actionType,
                            x = point.x,
                            y = point.y,
                            dragToX = point.dragToX,
                            dragToY = point.dragToY
                        )
                    },
                autoNameOrdinal = ordinal
            )
            val next = sortEntries(listOf(entry) + current)
            val encoded = json.encodeToString(listSer, next)

            prefs[KEY_PRESETS] = encoded
            prefs[KEY_NEXT_AUTO_NAME_ORDINAL] = ordinal + 1
            updateCache(encoded, next)
            created = entry
        }

        return checkNotNull(created)
    }

    suspend fun renamePreset(context: Context, presetId: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false

        var renamed = false
        context.presetDataStore.edit { prefs ->
            val current = decodeEntries(prefs[KEY_PRESETS].orEmpty())
            val index = current.indexOfFirst { it.id == presetId }
            if (index < 0) return@edit

            val next = current.toMutableList()
            if (next[index].name == trimmed) {
                renamed = true
                return@edit
            }
            next[index] = next[index].copy(name = trimmed)
            val sorted = sortEntries(next)
            val encoded = json.encodeToString(listSer, sorted)
            prefs[KEY_PRESETS] = encoded
            updateCache(encoded, sorted)
            renamed = true
        }
        return renamed
    }

    suspend fun deletePreset(context: Context, presetId: String): Boolean {
        var deleted = false
        context.presetDataStore.edit { prefs ->
            val current = decodeEntries(prefs[KEY_PRESETS].orEmpty())
            val next = current.filterNot { it.id == presetId }
            if (next.size == current.size) return@edit

            val sorted = sortEntries(next)
            val encoded = json.encodeToString(listSer, sorted)
            prefs[KEY_PRESETS] = encoded
            updateCache(encoded, sorted)
            deleted = true
        }
        return deleted
    }

    private fun decodeEntries(raw: String): List<PresetEntry> {
        if (raw.isBlank()) {
            cachedRaw = ""
            cachedEntries = emptyList()
            return emptyList()
        }
        if (cachedRaw == raw) return cachedEntries

        val decoded = runCatching { json.decodeFromString(listSer, raw) }
            .getOrDefault(emptyList())
        val sorted = sortEntries(decoded)
        cachedRaw = raw
        cachedEntries = sorted
        return sorted
    }

    private fun updateCache(raw: String, entries: List<PresetEntry>) {
        cachedRaw = raw
        cachedEntries = entries
    }

    private fun sortEntries(entries: List<PresetEntry>): List<PresetEntry> {
        return entries.sortedWith(
            compareByDescending<PresetEntry> { it.createdAtEpochMs }
                .thenByDescending { it.autoNameOrdinal }
        )
    }

    private fun autoNameForOrdinal(ordinal: Int): String {
        var value = ordinal.coerceAtLeast(0)
        val sb = StringBuilder()
        do {
            val rem = value % 26
            sb.append(('A'.code + rem).toChar())
            value = (value / 26) - 1
        } while (value >= 0)
        return sb.reverse().toString()
    }
}
