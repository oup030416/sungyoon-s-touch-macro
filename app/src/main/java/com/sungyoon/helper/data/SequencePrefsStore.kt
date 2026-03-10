package com.sungyoon.helper.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.runtimeStore by preferencesDataStore(name = "sungyoon_helper_runtime")

object SequencePrefsStore {
    private val KEY_POINTER_PANEL_VISIBLE = booleanPreferencesKey("pointer_panel_visible")
    private val KEY_RESERVATION_PANEL_VISIBLE = booleanPreferencesKey("reservation_panel_visible")
    private val KEY_PRESET_PANEL_VISIBLE = booleanPreferencesKey("preset_panel_visible")

    private val KEY_REPEAT_ENABLED = booleanPreferencesKey("repeat_enabled")
    private val KEY_SEQUENCE_RUNNING = booleanPreferencesKey("sequence_running")


    fun pointerPanelVisibleFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_POINTER_PANEL_VISIBLE] ?: true }
            .flowOn(Dispatchers.IO)
    }

    fun reservationPanelVisibleFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_RESERVATION_PANEL_VISIBLE] ?: false }
            .flowOn(Dispatchers.IO)
    }

    fun presetPanelVisibleFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_PRESET_PANEL_VISIBLE] ?: false }
            .flowOn(Dispatchers.IO)
    }

    // ✅ 추가 Setter
    suspend fun setPointerPanelVisible(context: Context, visible: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_POINTER_PANEL_VISIBLE] = visible
        }
    }

    suspend fun setReservationPanelVisible(context: Context, visible: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_RESERVATION_PANEL_VISIBLE] = visible
        }
    }

    suspend fun setPresetPanelVisible(context: Context, visible: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_PRESET_PANEL_VISIBLE] = visible
        }
    }

    // ✅ 추가: 터치 애니메이션 토글 (기본 ON)
    private val KEY_TOUCH_ANIMATION_ENABLED = booleanPreferencesKey("touch_animation_enabled")

    fun repeatEnabledFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_REPEAT_ENABLED] ?: true }
            .flowOn(Dispatchers.IO)
    }

    fun sequenceRunningFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_SEQUENCE_RUNNING] ?: false }
            .flowOn(Dispatchers.IO)
    }

    // ✅ 추가
    fun touchAnimationEnabledFlow(context: Context): Flow<Boolean> {
        return context.runtimeStore.data
            .map { prefs -> prefs[KEY_TOUCH_ANIMATION_ENABLED] ?: true } // default ON
            .flowOn(Dispatchers.IO)
    }

    suspend fun setRepeatEnabled(context: Context, enabled: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_REPEAT_ENABLED] = enabled
        }
    }

    suspend fun setSequenceRunning(context: Context, running: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_SEQUENCE_RUNNING] = running
        }
    }

    // ✅ 추가
    suspend fun setTouchAnimationEnabled(context: Context, enabled: Boolean) {
        context.runtimeStore.edit { prefs ->
            prefs[KEY_TOUCH_ANIMATION_ENABLED] = enabled
        }
    }
}
