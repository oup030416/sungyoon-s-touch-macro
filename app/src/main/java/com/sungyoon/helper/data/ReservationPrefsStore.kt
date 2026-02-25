package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.reservationPrefsDataStore by preferencesDataStore(name = "reservation_prefs")

object ReservationPrefsStore {

    // 기존 키 이름 유지 (구버전 데이터 보존)
    private val KEY_RUN_MIN = intPreferencesKey("run_min")
    private val KEY_REST_MIN = intPreferencesKey("rest_min")
    private val KEY_REPEAT = intPreferencesKey("repeat_count")

    // ✅ 단위 구분 키 추가 (구버전은 없으므로 기본 min)
    private val KEY_TIME_UNIT = stringPreferencesKey("time_unit")
    private const val UNIT_MIN = "min"
    private const val UNIT_SEC = "sec"

    // ✅ 새 API: 초 단위 Flow
    fun runSecondsFlow(context: Context): Flow<Int> =
        context.reservationPrefsDataStore.data.map { prefs ->
            val unit = prefs[KEY_TIME_UNIT] ?: UNIT_MIN
            val raw = prefs[KEY_RUN_MIN] ?: 1
            if (unit == UNIT_SEC) raw.coerceIn(1, 3600) else (raw.coerceIn(1, 60) * 60)
        }

    fun restSecondsFlow(context: Context): Flow<Int> =
        context.reservationPrefsDataStore.data.map { prefs ->
            val unit = prefs[KEY_TIME_UNIT] ?: UNIT_MIN
            val raw = prefs[KEY_REST_MIN] ?: 1
            if (unit == UNIT_SEC) raw.coerceIn(1, 3600) else (raw.coerceIn(1, 60) * 60)
        }

    fun repeatCountFlow(context: Context): Flow<Int> =
        context.reservationPrefsDataStore.data.map { it[KEY_REPEAT] ?: 1 }

    // ✅ 새 API: 초 단위 Set
    suspend fun setRunSeconds(context: Context, seconds: Int) {
        val v = seconds.coerceIn(1, 3600)
        context.reservationPrefsDataStore.edit {
            it[KEY_TIME_UNIT] = UNIT_SEC
            it[KEY_RUN_MIN] = v
        }
    }

    suspend fun setRestSeconds(context: Context, seconds: Int) {
        val v = seconds.coerceIn(1, 3600)
        context.reservationPrefsDataStore.edit {
            it[KEY_TIME_UNIT] = UNIT_SEC
            it[KEY_REST_MIN] = v
        }
    }

    suspend fun setRepeatCount(context: Context, count: Int) {
        val v = count.coerceIn(1, 9999)
        context.reservationPrefsDataStore.edit { it[KEY_REPEAT] = v }
    }
}
