package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dragDurationStore by preferencesDataStore(name = "sungyoon_helper_drag_duration")

object DragDurationStore {
    private val KEY_DRAG_DURATION_MS = longPreferencesKey("drag_duration_ms")
    private const val DEFAULT_DRAG_DURATION_MS = 300L

    fun dragDurationMsFlow(context: Context): Flow<Long> {
        return context.dragDurationStore.data
            .map { prefs -> prefs[KEY_DRAG_DURATION_MS] ?: DEFAULT_DRAG_DURATION_MS }
            .flowOn(Dispatchers.IO)
    }

    suspend fun setDragDurationMs(context: Context, ms: Long) {
        context.dragDurationStore.edit { prefs ->
            prefs[KEY_DRAG_DURATION_MS] = ms
        }
    }
}
