package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.randomTouchRadiusStore by preferencesDataStore(name = "sungyoon_helper_random_touch_radius")

object RandomTouchRadiusStore {
    private val KEY_RANDOM_TOUCH_RADIUS_DP = intPreferencesKey("random_touch_radius_dp")
    private const val DEFAULT_RANDOM_TOUCH_RADIUS_DP = 0

    fun randomTouchRadiusDpFlow(context: Context): Flow<Int> {
        return context.randomTouchRadiusStore.data
            .map { prefs -> (prefs[KEY_RANDOM_TOUCH_RADIUS_DP] ?: DEFAULT_RANDOM_TOUCH_RADIUS_DP).coerceIn(0, 120) }
            .flowOn(Dispatchers.IO)
    }

    suspend fun setRandomTouchRadiusDp(context: Context, dp: Int) {
        context.randomTouchRadiusStore.edit { prefs ->
            prefs[KEY_RANDOM_TOUCH_RADIUS_DP] = dp.coerceIn(0, 120)
        }
    }
}
