package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.tapIntervalStore by preferencesDataStore(name = "sungyoon_helper_tap_interval")

object TapIntervalStore {
    private val KEY_TAP_INTERVAL_MS = longPreferencesKey("tap_interval_ms")

    // 기본값 1.0초 = 1000ms
    fun tapIntervalMsFlow(context: Context): Flow<Long> {
        return context.tapIntervalStore.data
            .map { prefs -> prefs[KEY_TAP_INTERVAL_MS] ?: 1000L }
            .flowOn(Dispatchers.IO)
    }

    suspend fun setTapIntervalMs(context: Context, ms: Long) {
        context.tapIntervalStore.edit { prefs ->
            prefs[KEY_TAP_INTERVAL_MS] = ms
        }
    }
}
