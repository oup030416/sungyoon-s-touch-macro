package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.reservationRuntimeStore by preferencesDataStore(name = "sungyoon_helper_reservation_runtime")

object ReservationRuntimeStore {

    data class Snapshot(
        val active: Boolean,
        val paused: Boolean,
        val phase: Int,
        val phaseEndAtMs: Long,
        val pausedRemainingMs: Long,
        val nextPointOffset: Int,
        val cycleCurrent: Int,
        val cycleTotal: Int,
        val runSec: Int,
        val restSec: Int
    )



    private val KEY_TIME_UNIT = stringPreferencesKey("time_unit")
    private const val UNIT_MIN = "min"
    private const val UNIT_SEC = "sec"
    const val PHASE_RUN = 1
    const val PHASE_REST = 2

    private val KEY_ACTIVE = booleanPreferencesKey("active")
    private val KEY_PAUSED = booleanPreferencesKey("paused")
    private val KEY_PHASE = intPreferencesKey("phase")

    // paused=false일 때 유효: phase 종료 시각(벽시계 ms)
    private val KEY_PHASE_END_AT_MS = longPreferencesKey("phase_end_at_ms")

    // paused=true일 때 유효: 남은 시간(ms)
    private val KEY_PAUSED_REMAINING_MS = longPreferencesKey("paused_remaining_ms")

    private val KEY_CYCLE_CURRENT = intPreferencesKey("cycle_current") // 1-based
    private val KEY_CYCLE_TOTAL = intPreferencesKey("cycle_total")
    private val KEY_NEXT_POINT_OFFSET = intPreferencesKey("next_point_offset")

    // config snapshot (복원용)
    private val KEY_RUN_MIN = intPreferencesKey("run_min")
    private val KEY_REST_MIN = intPreferencesKey("rest_min")

    private val KEY_STATUS_TEXT = stringPreferencesKey("status_text")

    fun activeFlow(context: Context): Flow<Boolean> =
        context.reservationRuntimeStore.data.map { it[KEY_ACTIVE] ?: false }.flowOn(Dispatchers.IO)

    fun pausedFlow(context: Context): Flow<Boolean> =
        context.reservationRuntimeStore.data.map { it[KEY_PAUSED] ?: false }.flowOn(Dispatchers.IO)

    fun phaseFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { it[KEY_PHASE] ?: PHASE_RUN }.flowOn(Dispatchers.IO)

    fun phaseEndAtMsFlow(context: Context): Flow<Long> =
        context.reservationRuntimeStore.data.map { it[KEY_PHASE_END_AT_MS] ?: 0L }.flowOn(Dispatchers.IO)

    fun pausedRemainingMsFlow(context: Context): Flow<Long> =
        context.reservationRuntimeStore.data.map { it[KEY_PAUSED_REMAINING_MS] ?: 0L }.flowOn(Dispatchers.IO)

    fun cycleCurrentFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { it[KEY_CYCLE_CURRENT] ?: 1 }.flowOn(Dispatchers.IO)

    fun cycleTotalFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { it[KEY_CYCLE_TOTAL] ?: 1 }.flowOn(Dispatchers.IO)

    fun nextPointOffsetFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { (it[KEY_NEXT_POINT_OFFSET] ?: 0).coerceAtLeast(0) }
            .flowOn(Dispatchers.IO)

    fun runMinFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { it[KEY_RUN_MIN] ?: 1 }.flowOn(Dispatchers.IO)

    fun restMinFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { it[KEY_REST_MIN] ?: 1 }.flowOn(Dispatchers.IO)


    fun runSecFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { prefs ->
            val unit = prefs[KEY_TIME_UNIT] ?: UNIT_MIN
            val raw = prefs[KEY_RUN_MIN] ?: 1
            if (unit == UNIT_SEC) raw.coerceIn(1, 3600) else (raw.coerceIn(1, 60) * 60)
        }.flowOn(Dispatchers.IO)

    fun restSecFlow(context: Context): Flow<Int> =
        context.reservationRuntimeStore.data.map { prefs ->
            val unit = prefs[KEY_TIME_UNIT] ?: UNIT_MIN
            val raw = prefs[KEY_REST_MIN] ?: 1
            if (unit == UNIT_SEC) raw.coerceIn(1, 3600) else (raw.coerceIn(1, 60) * 60)
        }.flowOn(Dispatchers.IO)

    fun snapshotFlow(context: Context): Flow<Snapshot> =
        context.reservationRuntimeStore.data.map { prefs ->
            val unit = prefs[KEY_TIME_UNIT] ?: UNIT_MIN
            val rawRun = prefs[KEY_RUN_MIN] ?: 1
            val rawRest = prefs[KEY_REST_MIN] ?: 1

            val runSec = if (unit == UNIT_SEC) {
                rawRun.coerceIn(1, 3600)
            } else {
                (rawRun.coerceIn(1, 60) * 60)
            }

            val restSec = if (unit == UNIT_SEC) {
                rawRest.coerceIn(1, 3600)
            } else {
                (rawRest.coerceIn(1, 60) * 60)
            }

            Snapshot(
                active = prefs[KEY_ACTIVE] ?: false,
                paused = prefs[KEY_PAUSED] ?: false,
                phase = prefs[KEY_PHASE] ?: PHASE_RUN,
                phaseEndAtMs = prefs[KEY_PHASE_END_AT_MS] ?: 0L,
                pausedRemainingMs = prefs[KEY_PAUSED_REMAINING_MS] ?: 0L,
                nextPointOffset = (prefs[KEY_NEXT_POINT_OFFSET] ?: 0).coerceAtLeast(0),
                cycleCurrent = (prefs[KEY_CYCLE_CURRENT] ?: 1).coerceAtLeast(1),
                cycleTotal = (prefs[KEY_CYCLE_TOTAL] ?: 1).coerceIn(1, 9999),
                runSec = runSec,
                restSec = restSec
            )
        }.flowOn(Dispatchers.IO)

    suspend fun startNew(
        context: Context,
        nowMs: Long,
        runSec: Int,
        restSec: Int,
        repeatCount: Int,
        initialStatus: String
    ) {
        val rs = runSec.coerceIn(1, 3600)
        val ss = restSec.coerceIn(1, 3600)
        val rc = repeatCount.coerceIn(1, 9999)

        context.reservationRuntimeStore.edit { prefs ->
            prefs[KEY_TIME_UNIT] = UNIT_SEC
            prefs[KEY_ACTIVE] = true
            prefs[KEY_PAUSED] = false
            prefs[KEY_PHASE] = PHASE_RUN
            prefs[KEY_RUN_MIN] = rs
            prefs[KEY_REST_MIN] = ss
            prefs[KEY_CYCLE_CURRENT] = 1
            prefs[KEY_CYCLE_TOTAL] = rc
            prefs[KEY_NEXT_POINT_OFFSET] = 0
            prefs[KEY_PAUSED_REMAINING_MS] = 0L
            prefs[KEY_PHASE_END_AT_MS] = nowMs + (rs * 1000L)
            prefs[KEY_STATUS_TEXT] = initialStatus
        }
    }

    suspend fun setStatusText(context: Context, text: String) {
        context.reservationRuntimeStore.edit { it[KEY_STATUS_TEXT] = text }
    }

    suspend fun setPhase(context: Context, phase: Int, nowMs: Long, durationMs: Long) {
        context.reservationRuntimeStore.edit { prefs ->
            prefs[KEY_PHASE] = phase
            prefs[KEY_PAUSED] = false
            prefs[KEY_PAUSED_REMAINING_MS] = 0L
            prefs[KEY_PHASE_END_AT_MS] = nowMs + durationMs.coerceAtLeast(0L)
        }
    }

    suspend fun setCycleCurrent(context: Context, cycle: Int) {
        context.reservationRuntimeStore.edit { it[KEY_CYCLE_CURRENT] = cycle.coerceAtLeast(1) }
    }

    suspend fun setNextPointOffset(context: Context, offset: Int) {
        context.reservationRuntimeStore.edit { it[KEY_NEXT_POINT_OFFSET] = offset.coerceAtLeast(0) }
    }

    suspend fun pause(context: Context, remainingMs: Long) {
        context.reservationRuntimeStore.edit { prefs ->
            prefs[KEY_PAUSED] = true
            prefs[KEY_PAUSED_REMAINING_MS] = remainingMs.coerceAtLeast(0L)
        }
    }

    suspend fun resume(context: Context, nowMs: Long) {
        context.reservationRuntimeStore.edit { prefs ->
            val remaining = prefs[KEY_PAUSED_REMAINING_MS] ?: 0L
            prefs[KEY_PAUSED] = false
            prefs[KEY_PAUSED_REMAINING_MS] = 0L
            prefs[KEY_PHASE_END_AT_MS] = nowMs + remaining.coerceAtLeast(0L)
        }
    }

    suspend fun stop(context: Context, finalStatus: String) {
        context.reservationRuntimeStore.edit { prefs ->
            prefs[KEY_ACTIVE] = false
            prefs[KEY_PAUSED] = false
            prefs[KEY_PAUSED_REMAINING_MS] = 0L
            prefs[KEY_NEXT_POINT_OFFSET] = 0
            prefs[KEY_STATUS_TEXT] = finalStatus
        }
    }


    suspend fun reset(context: Context, statusText: String = "예약대기중") {
        context.reservationRuntimeStore.edit { prefs ->
            prefs[KEY_TIME_UNIT] = UNIT_SEC
            prefs[KEY_ACTIVE] = false
            prefs[KEY_PAUSED] = false
            prefs[KEY_PHASE] = PHASE_RUN
            prefs[KEY_PHASE_END_AT_MS] = 0L
            prefs[KEY_PAUSED_REMAINING_MS] = 0L
            prefs[KEY_CYCLE_CURRENT] = 1
            prefs[KEY_CYCLE_TOTAL] = 0
            prefs[KEY_NEXT_POINT_OFFSET] = 0
            prefs[KEY_RUN_MIN] = 1
            prefs[KEY_REST_MIN] = 1
            prefs[KEY_STATUS_TEXT] = statusText
        }
    }

}
