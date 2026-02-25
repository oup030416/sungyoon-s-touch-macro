package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_DRAG
import com.sungyoon.helper.model.HighlightingPoint.Companion.ACTION_TYPE_TAP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "sungyoon_helper_store")

object PointsStore {
    private val KEY_POINTS = stringPreferencesKey("points_json")
    private val KEY_SCHEMA = intPreferencesKey("points_schema")
    private const val SCHEMA_LOCAL = 1
    private const val SCHEMA_SCREEN = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val listSer = ListSerializer(HighlightingPoint.serializer())

    @Volatile
    private var cachedRaw: String = ""

    @Volatile
    private var cachedPoints: List<HighlightingPoint> = emptyList()

    private fun decodePoints(raw: String): List<HighlightingPoint> {
        if (raw.isBlank()) {
            cachedRaw = ""
            cachedPoints = emptyList()
            return emptyList()
        }
        if (cachedRaw == raw) return cachedPoints

        val decoded = runCatching { json.decodeFromString(listSer, raw) }.getOrDefault(emptyList())
        cachedRaw = raw
        cachedPoints = decoded
        return decoded
    }

    private fun updateCache(raw: String, points: List<HighlightingPoint>) {
        cachedRaw = raw
        cachedPoints = points
    }

    fun pointsFlow(context: Context): Flow<List<HighlightingPoint>> {
        return context.dataStore.data
            .map { prefs -> decodePoints(prefs[KEY_POINTS].orEmpty()) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun addPoint(context: Context, point: HighlightingPoint) {
        context.dataStore.edit { prefs ->
            val list = decodePoints(prefs[KEY_POINTS].orEmpty())
            val next = (list + point).take(2000)
            val encoded = json.encodeToString(listSer, next)

            prefs[KEY_POINTS] = encoded
            prefs[KEY_SCHEMA] = SCHEMA_SCREEN
            updateCache(encoded, next)
        }
    }

    suspend fun updatePointPosition(context: Context, id: String, x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            val list = decodePoints(prefs[KEY_POINTS].orEmpty())
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@edit

            val old = list[index]
            if (old.x == x && old.y == y) return@edit

            val next = list.toMutableList()
            next[index] = if (old.actionType == ACTION_TYPE_DRAG) {
                old.copy(x = x, y = y)
            } else {
                old.copy(
                    x = x,
                    y = y,
                    actionType = ACTION_TYPE_TAP,
                    dragToX = x,
                    dragToY = y
                )
            }
            val encoded = json.encodeToString(listSer, next)

            prefs[KEY_POINTS] = encoded
            prefs[KEY_SCHEMA] = SCHEMA_SCREEN
            updateCache(encoded, next)
        }
    }

    suspend fun updateDragEndPosition(context: Context, id: String, x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            val list = decodePoints(prefs[KEY_POINTS].orEmpty())
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@edit

            val old = list[index]
            if (old.dragToX == x && old.dragToY == y) return@edit

            val next = list.toMutableList()
            next[index] = old.copy(
                actionType = ACTION_TYPE_DRAG,
                dragToX = x,
                dragToY = y
            )
            val encoded = json.encodeToString(listSer, next)

            prefs[KEY_POINTS] = encoded
            prefs[KEY_SCHEMA] = SCHEMA_SCREEN
            updateCache(encoded, next)
        }
    }

    suspend fun deletePoint(context: Context, id: String) {
        context.dataStore.edit { prefs ->
            val list = decodePoints(prefs[KEY_POINTS].orEmpty())
            val next = list.filterNot { it.id == id }
            val encoded = json.encodeToString(listSer, next)

            prefs[KEY_POINTS] = encoded
            updateCache(encoded, next)
        }
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_POINTS)
            prefs.remove(KEY_SCHEMA)
            updateCache("", emptyList())
        }
    }

    suspend fun migrateToScreenCoordsIfNeeded(context: Context, dx: Float, dy: Float) {
        context.dataStore.edit { prefs ->
            val schema = prefs[KEY_SCHEMA] ?: SCHEMA_LOCAL
            if (schema >= SCHEMA_SCREEN) return@edit

            val list = decodePoints(prefs[KEY_POINTS].orEmpty())
            if (list.isEmpty()) {
                prefs[KEY_SCHEMA] = SCHEMA_SCREEN
                updateCache("", emptyList())
                return@edit
            }

            val migrated = list.map { p ->
                val nx = p.x + dx
                val ny = p.y + dy
                val ndx = p.dragToX + dx
                val ndy = p.dragToY + dy
                if (p.actionType == ACTION_TYPE_DRAG) {
                    p.copy(x = nx, y = ny, dragToX = ndx, dragToY = ndy)
                } else {
                    p.copy(x = nx, y = ny, actionType = ACTION_TYPE_TAP, dragToX = nx, dragToY = ny)
                }
            }
            val encoded = json.encodeToString(listSer, migrated)

            prefs[KEY_POINTS] = encoded
            prefs[KEY_SCHEMA] = SCHEMA_SCREEN
            updateCache(encoded, migrated)
        }
    }
}
