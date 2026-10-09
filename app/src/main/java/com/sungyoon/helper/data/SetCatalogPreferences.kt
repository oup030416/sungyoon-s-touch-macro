package com.sungyoon.helper.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sungyoon.helper.model.SetDefinition
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetItemType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** The migration and every scoped change run inside one DataStore transaction. */
internal object SetCatalogPreferences {
    val legacyItems = stringPreferencesKey("items_json")
    val catalog = stringPreferencesKey("sets_json")
    val migrated = booleanPreferencesKey("catalog_migrated")
    val nextSetOrdinal = intPreferencesKey("next_set_ordinal")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
    private val setsSerializer = ListSerializer(SetDefinition.serializer())
    private val itemsSerializer = ListSerializer(SetItem.serializer())

    fun ordinalKey(setId: String, type: SetItemType) =
        intPreferencesKey("next_${setId}_${type.name.lowercase()}_ordinal")

    fun initialize(prefs: MutablePreferences, legacyName: String, id: () -> String = { UUID.randomUUID().toString() }) {
        if (prefs[migrated] == true) return
        // Decode before any write. Invalid source data is retained, never replaced by an empty list.
        val source = prefs[legacyItems]
        val existing = prefs[catalog]
        val sets = if (existing != null) decode(existing) else if (source != null) {
            listOf(SetDefinition(id(), legacyName, json.decodeFromString(itemsSerializer, source), true))
        } else emptyList()
        val encoded = encode(sets)
        decode(encoded) // Validate IDs before committing the migration marker or copied counters.
        if (source != null && existing == null) {
            SetItemType.entries.forEach { type ->
                prefs[ordinalKey(sets.single().id, type)] =
                    prefs[intPreferencesKey("next_${type.name.lowercase()}_ordinal")] ?: 1
            }
        }
        prefs[catalog] = encoded
        prefs[nextSetOrdinal] = prefs[nextSetOrdinal] ?: if (sets.isEmpty()) 1 else 2
        prefs[migrated] = true
        // Keep the legacy value as a recovery source; the marker prevents resurrection after deletion.
    }

    fun read(prefs: Preferences): List<SetDefinition> = decode(prefs[catalog] ?: "[]")
    fun write(prefs: MutablePreferences, sets: List<SetDefinition>) { prefs[catalog] = encode(sets) }

    fun move(prefs: MutablePreferences, setId: String, toIndex: Int): Boolean {
        val sets = read(prefs)
        val from = sets.indexOfFirst { it.id == setId }
        if (from < 0) return false
        val target = toIndex.coerceIn(0, sets.lastIndex)
        if (from == target) return false
        write(prefs, sets.toMutableList().apply { add(target, removeAt(from)) })
        return true
    }

    fun duplicate(prefs: MutablePreferences, setId: String, name: (String) -> String): SetDefinition? {
        val sets = read(prefs)
        val source = sets.firstOrNull { it.id == setId } ?: return null
        val copy = source.copy(id = UUID.randomUUID().toString(), name = name(source.name),
            items = source.items.map { it.duplicate(it.name) })
        write(prefs, sets + copy)
        // Continue each item's naming sequence independently from the same saved snapshot.
        SetItemType.entries.forEach { type ->
            prefs[ordinalKey(copy.id, type)] = prefs[ordinalKey(source.id, type)] ?: 1
        }
        return copy
    }

    fun update(prefs: MutablePreferences, setId: String, transform: (SetDefinition) -> SetDefinition?): Boolean {
        val sets = read(prefs)
        val index = sets.indexOfFirst { it.id == setId }
        if (index < 0) return false
        val changed = transform(sets[index]) ?: return false
        if (changed == sets[index]) return false
        require(changed.id == setId)
        write(prefs, sets.toMutableList().apply { this[index] = changed })
        return true
    }

    private fun encode(sets: List<SetDefinition>): String = json.encodeToString(setsSerializer, sets)
    private fun decode(raw: String): List<SetDefinition> = json.decodeFromString(setsSerializer, raw).also { sets ->
        require(sets.all { it.id.isNotBlank() } && sets.map { it.id }.distinct().size == sets.size)
        require(sets.all { set -> set.items.map { it.id }.distinct().size == set.items.size })
    }
}
