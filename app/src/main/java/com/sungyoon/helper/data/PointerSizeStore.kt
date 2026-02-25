package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.pointerSizeStore by preferencesDataStore(name = "sungyoon_helper_pointer_size")

object PointerSizeStore {
    private val KEY_POINTER_SIZE_LEVEL = intPreferencesKey("pointer_size_level")

    // 기존 기본 반지름 18(dp) == level 8
    private const val DEFAULT_LEVEL = 8

    fun pointerSizeLevelFlow(context: Context): Flow<Int> {
        return context.pointerSizeStore.data
            .map { prefs -> (prefs[KEY_POINTER_SIZE_LEVEL] ?: DEFAULT_LEVEL).coerceIn(1, 10) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun setPointerSizeLevel(context: Context, level: Int) {
        val v = level.coerceIn(1, 10)
        context.pointerSizeStore.edit { prefs ->
            prefs[KEY_POINTER_SIZE_LEVEL] = v
        }
    }
}
