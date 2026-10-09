package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.ReservationConfig
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetDefinition
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import com.sungyoon.helper.model.SetItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.setDataStore by preferencesDataStore(name = "sungyoon_helper_set")

object SetStore {
    fun setsFlow(context: Context): Flow<List<SetDefinition>> = flow {
        context.applicationContext.setDataStore.edit { initialize(context, it) }
        emitAll(context.applicationContext.setDataStore.data.map(SetCatalogPreferences::read))
    }.flowOn(Dispatchers.IO)

    fun setFlow(context: Context, setId: String): Flow<SetDefinition?> =
        setsFlow(context).map { sets -> sets.firstOrNull { it.id == setId } }

    fun itemsFlow(context: Context, setId: String): Flow<List<SetItem>> =
        setFlow(context, setId).map { it?.items.orEmpty() }

    suspend fun addSet(context: Context): SetDefinition {
        var created: SetDefinition? = null
        context.applicationContext.setDataStore.edit { prefs ->
            initialize(context, prefs)
            val ordinal = (prefs[SetCatalogPreferences.nextSetOrdinal] ?: 1).coerceAtLeast(1)
            val set = SetDefinition(name = context.getString(R.string.set_default_name, ordinal))
            SetCatalogPreferences.write(prefs, SetCatalogPreferences.read(prefs) + set)
            prefs[SetCatalogPreferences.nextSetOrdinal] = if (ordinal == Int.MAX_VALUE) 1 else ordinal + 1
            created = set
        }
        return checkNotNull(created)
    }

    suspend fun renameSet(context: Context, setId: String, name: String): Boolean {
        if (name.isBlank()) return false
        return updateSet(context, setId) { it.copy(name = name.trim()) }
    }

    suspend fun setRepeatEnabled(context: Context, setId: String, enabled: Boolean): Boolean =
        updateSet(context, setId) { it.copy(repeatEnabled = enabled) }

    suspend fun deleteSet(context: Context, setId: String): Boolean {
        var changed = false
        context.applicationContext.setDataStore.edit { prefs ->
            initialize(context, prefs)
            val sets = SetCatalogPreferences.read(prefs)
            if (sets.none { it.id == setId }) return@edit
            SetCatalogPreferences.write(prefs, sets.filterNot { it.id == setId })
            SetItemType.entries.forEach { prefs.remove(SetCatalogPreferences.ordinalKey(setId, it)) }
            changed = true
        }
        return changed
    }

    suspend fun addItem(context: Context, setId: String, type: SetItemType): SetItem {
        var created: SetItem? = null
        context.applicationContext.setDataStore.edit { prefs ->
            initialize(context, prefs)
            val set = SetCatalogPreferences.read(prefs).firstOrNull { it.id == setId }
                ?: error("Set was deleted")
            val ordinalKey = SetCatalogPreferences.ordinalKey(setId, type)
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
            SetCatalogPreferences.update(prefs, setId) { set.copy(items = set.items + item) }
            prefs[ordinalKey] = if (ordinal == Int.MAX_VALUE) 1 else ordinal + 1
            created = item
        }
        return checkNotNull(created)
    }

    suspend fun renameItem(context: Context, setId: String, id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        return updateItem(context, setId, id) { it.renamed(trimmed) }
    }

    suspend fun deleteItem(context: Context, setId: String, id: String): Boolean = mutate(context, setId) { items ->
        val next = items.filterNot { it.id == id }
        if (next.size == items.size) null else next
    }

    suspend fun duplicateItem(context: Context, setId: String, id: String): SetItem? {
        var duplicate: SetItem? = null
        mutate(context, setId) { items ->
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return@mutate null
            val original = items[index]
            val copy = original.duplicate(context.getString(R.string.set_duplicate_name, original.name))
            duplicate = copy
            items.toMutableList().apply { add(index + 1, copy) }
        }
        return duplicate
    }

    suspend fun moveItem(context: Context, setId: String, id: String, toIndex: Int): Boolean = mutate(context, setId) { items ->
        val fromIndex = items.indexOfFirst { it.id == id }
        if (fromIndex < 0) return@mutate null
        val target = toIndex.coerceIn(0, items.lastIndex)
        items.toMutableList().apply { add(target, removeAt(fromIndex)) }
    }

    suspend fun replacePoints(context: Context, setId: String, id: String, points: List<HighlightingPoint>): Boolean =
        updateItem(context, setId, id) { item ->
            if (item is SetItem.Wait) null else item.withPoints(points.take(MAX_POINTS))
        }

    /** The transform sees the latest saved points inside the same atomic transaction. */
    suspend fun updatePoints(
        context: Context,
        setId: String,
        id: String,
        transform: (List<HighlightingPoint>) -> List<HighlightingPoint>,
    ): Boolean = updateItem(context, setId, id) { item ->
        if (item is SetItem.Wait) null else item.withPoints(transform(item.points).take(MAX_POINTS))
    }

    suspend fun updateReservation(context: Context, setId: String, id: String, config: ReservationConfig): Boolean =
        updateItem(context, setId, id) { item ->
            val normalized = config.normalized()
            (item as? SetItem.Reserved)?.takeIf { it.reservation != normalized }?.copy(reservation = normalized)
        }

    suspend fun updateWait(context: Context, setId: String, id: String, durationMs: Long): Boolean {
        if (!SetItem.Wait.isValidDuration(durationMs)) return false
        return updateItem(context, setId, id) { item ->
            (item as? SetItem.Wait)?.takeIf { it.durationMs != durationMs }?.copy(durationMs = durationMs)
        }
    }

    private suspend fun updateItem(
        context: Context,
        setId: String,
        id: String,
        transform: (SetItem) -> SetItem?,
    ): Boolean = mutate(context, setId) { items ->
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@mutate null
        val changed = transform(items[index]) ?: return@mutate null
        items.toMutableList().apply { this[index] = changed }
    }

    private suspend fun mutate(
        context: Context,
        setId: String,
        transform: (List<SetItem>) -> List<SetItem>?,
    ): Boolean = updateSet(context, setId) { set -> transform(set.items)?.let { set.copy(items = it) } }

    private suspend fun updateSet(
        context: Context,
        setId: String,
        transform: (SetDefinition) -> SetDefinition?,
    ): Boolean {
        var changed = false
        context.applicationContext.setDataStore.edit { prefs ->
            initialize(context, prefs)
            changed = SetCatalogPreferences.update(prefs, setId, transform)
        }
        return changed
    }

    private fun initialize(context: Context, prefs: androidx.datastore.preferences.core.MutablePreferences) {
        SetCatalogPreferences.initialize(prefs, context.getString(R.string.set_default_name, 1))
    }

    private const val MAX_POINTS = 2000
}
