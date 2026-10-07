package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.setDataStore by preferencesDataStore(name = "sungyoon_helper_set")

object SetStore {
    private val itemsKey = stringPreferencesKey("items_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "kind"
    }
    private val serializer = ListSerializer(SetItem.serializer())

    fun itemsFlow(context: Context): Flow<List<SetItem>> =
        context.applicationContext.setDataStore.data
            .map { decode(it[itemsKey].orEmpty()) }
            .flowOn(Dispatchers.IO)

    suspend fun addItem(context: Context, type: SetItemType): SetItem {
        var created: SetItem? = null
        context.applicationContext.setDataStore.edit { prefs ->
            val ordinalKey = intPreferencesKey("next_${type.name.lowercase()}_ordinal")
            val ordinal = (prefs[ordinalKey] ?: 1).coerceAtLeast(1)
            val nameResource = when (type) {
                SetItemType.TOUCH -> R.string.set_default_touch_name
                SetItemType.RESERVED -> R.string.set_default_reserved_name
                SetItemType.WAIT -> R.string.set_default_wait_name
            }
            val name = context.getString(nameResource, ordinal)
            val item = when (type) {
                SetItemType.TOUCH -> SetItem.Touch(name = name)
                SetItemType.RESERVED -> SetItem.Reserved(name = name)
                SetItemType.WAIT -> SetItem.Wait(name = name)
            }
            prefs[itemsKey] = encode(decode(prefs[itemsKey].orEmpty()) + item)
            prefs[ordinalKey] = if (ordinal == Int.MAX_VALUE) 1 else ordinal + 1
            created = item
        }
        return checkNotNull(created)
    }

    suspend fun renameItem(context: Context, id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        return updateItem(context, id) { it.renamed(trimmed) }
    }

    suspend fun deleteItem(context: Context, id: String): Boolean = mutate(context) { items ->
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) null else next
    }

    suspend fun duplicateItem(context: Context, id: String): SetItem? {
        var duplicate: SetItem? = null
        mutate(context) { items ->
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return@mutate null
            val original = items[index]
            val copy = original.duplicate(context.getString(R.string.set_duplicate_name, original.name))
            duplicate = copy
            items.toMutableList().apply { add(index + 1, copy) }
        }
        return duplicate
    }

    suspend fun moveItem(context: Context, id: String, toIndex: Int): Boolean = mutate(context) { items ->
        val fromIndex = items.indexOfFirst { it.id == id }
        if (fromIndex < 0) return@mutate null
        val target = toIndex.coerceIn(0, items.lastIndex)
        items.toMutableList().apply { add(target, removeAt(fromIndex)) }
    }

    suspend fun replacePoints(context: Context, id: String, points: List<HighlightingPoint>): Boolean =
        updateItem(context, id) { item ->
            if (item is SetItem.Wait) null else item.withPoints(points.take(MAX_POINTS))
        }

    /** The transform sees the latest saved points inside the same atomic transaction. */
    suspend fun updatePoints(
        context: Context,
        id: String,
        transform: (List<HighlightingPoint>) -> List<HighlightingPoint>,
    ): Boolean = updateItem(context, id) { item ->
        if (item is SetItem.Wait) null else item.withPoints(transform(item.points).take(MAX_POINTS))
    }

    suspend fun updateReservation(context: Context, id: String, config: ReservationConfig): Boolean =
        updateItem(context, id) { item ->
            val normalized = config.normalized()
            (item as? SetItem.Reserved)?.takeIf { it.reservation != normalized }?.copy(reservation = normalized)
        }

    suspend fun updateWait(context: Context, id: String, durationMs: Long): Boolean {
        if (!SetItem.Wait.isValidDuration(durationMs)) return false
        return updateItem(context, id) { item ->
            (item as? SetItem.Wait)?.takeIf { it.durationMs != durationMs }?.copy(durationMs = durationMs)
        }
    }

    private suspend fun updateItem(
        context: Context,
        id: String,
        transform: (SetItem) -> SetItem?,
    ): Boolean = mutate(context) { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val changed = transform(items[index]) ?: return@mutate null
        items.toMutableList().apply { this[index] = changed }
    }

    private suspend fun mutate(
        context: Context,
        transform: (List<SetItem>) -> List<SetItem>?,
    ): Boolean {
        var changed = false
        context.applicationContext.setDataStore.edit { prefs ->
            val next = transform(decode(prefs[itemsKey].orEmpty())) ?: return@edit
            prefs[itemsKey] = encode(next)
            changed = true
        }
        return changed
    }

    private fun encode(items: List<SetItem>): String = json.encodeToString(serializer, items)

    private fun decode(raw: String): List<SetItem> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .map { item ->
                when (item) {
                    is SetItem.Touch -> item.withPoints(item.points.take(MAX_POINTS))
                    is SetItem.Reserved -> item.copy(reservation = item.reservation.normalized())
                        .withPoints(item.points.take(MAX_POINTS))
                    is SetItem.Wait -> item.copy(
                        durationMs = item.durationMs.coerceIn(
                            SetItem.Wait.MIN_DURATION_MS,
                            SetItem.Wait.MAX_DURATION_MS,
                        ) / SetItem.Wait.DURATION_STEP_MS * SetItem.Wait.DURATION_STEP_MS,
                    )
                }
            }
    }

    private const val MAX_POINTS = 2000
}
