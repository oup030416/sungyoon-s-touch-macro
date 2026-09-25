package com.sungyoon.helper.data

import android.content.Context
import com.sungyoon.helper.R
import com.sungyoon.helper.model.HighlightingPoint
import com.sungyoon.helper.model.PresetEntry
import com.sungyoon.helper.model.PresetPoint
import com.sungyoon.helper.model.PresetSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes canonical preset writes before updating the ordinary working stores. */
object PresetSession {
    data class State(
        val ready: Boolean = false,
        val entries: List<PresetEntry> = emptyList(),
        val activeId: String? = null,
        val points: List<HighlightingPoint> = emptyList(),
        val settings: PresetSettings = PresetSettings(),
        val failed: Boolean = false
    ) {
        val isHold: Boolean get() = activeId == PresetEntry.HOLD_PRESET_ID
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    // Installed by the accessibility service; no running gesture survives a switch.
    var beforeSwitch: (suspend () -> Unit)? = null

    private suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(ready = false, failed = true)
            throw error
        }
    }

    suspend fun initialize(context: Context, refreshUnbound: Boolean = false) = serialized {
        if (state.value.ready) {
            if (refreshUnbound && state.value.activeId == null) {
                mutableState.value = state.value.copy(points = PointsStore.pointsFlow(context).first())
            }
            return@serialized
        }
        val working = PointsStore.pointsFlow(context).first()
        val settings = readSettings(context)
        val saved = PresetStore.update(context) { old ->
            val entries = if (old.entries.any { it.isHold }) old.entries else old.entries + PresetEntry(
                id = PresetEntry.HOLD_PRESET_ID, name = context.getString(R.string.hold_preset_name),
                createdAtEpochMs = 0L, points = emptyList(), autoNameOrdinal = -1
            )
            require(old.activeId == null || entries.any { it.id == old.activeId }) { "Missing active preset" }
            old.copy(entries = entries.map {
                if (it.id == old.activeId && !it.isHold && it.settings == null) it.copy(settings = settings) else it
            })
        }
        publish(context, saved, working, settings)
    }

    suspend fun activate(context: Context, id: String, centerX: Float, centerY: Float) = serialized {
        check(state.value.ready)
        if (state.value.activeId == id) return@serialized
        val previous = state.value
        mutableState.value = previous.copy(ready = false)
        beforeSwitch?.invoke()
        val saved = PresetStore.update(context) { old ->
            var entries = old.entries
            var ordinal = old.nextOrdinal
            if (previous.activeId == null && previous.points.isNotEmpty()) {
                entries = entries + newPreset(previous, context.getString(R.string.preset_previous_touches), ordinal++)
            }
            val target = entries.first { it.id == id }
            val applied = when {
                target.isHold && target.points.isEmpty() -> target.copy(points = listOf(
                    PresetPoint(index = 0, x = centerX, y = centerY)
                ))
                !target.isHold && target.settings == null -> target.copy(settings = previous.settings)
                else -> target
            }
            old.copy(entries = entries.map { if (it.id == id) applied else it }, activeId = id, nextOrdinal = ordinal)
        }
        publish(context, saved, previous.points.takeUnless { previous.isHold }
            ?: PointsStore.pointsFlow(context).first(), previous.settings)
    }

    suspend fun addCurrent(context: Context) = serialized {
        val current = state.value
        check(current.ready)
        mutableState.value = current.copy(ready = false)
        beforeSwitch?.invoke()
        val saved = PresetStore.update(context) { old ->
            val source = if (current.isHold) current.copy(points = emptyList()) else current
            val entry = newPreset(source, PresetStore.autoNameForOrdinal(old.nextOrdinal), old.nextOrdinal)
            old.copy(entries = old.entries + entry, activeId = entry.id, nextOrdinal = old.nextOrdinal + 1)
        }
        publish(context, saved, if (current.isHold) PointsStore.pointsFlow(context).first() else current.points, current.settings)
    }

    suspend fun rename(context: Context, id: String, name: String) = serialized {
        check(state.value.ready)
        if (name.isBlank()) return@serialized
        val saved = PresetStore.update(context) { old ->
            old.copy(entries = old.entries.map { if (it.id == id) it.copy(name = name.trim()) else it })
        }
        mutableState.value = state.value.copy(entries = PresetStore.sortEntries(saved.entries))
    }

    suspend fun delete(context: Context, id: String) = serialized {
        check(state.value.ready)
        if (id == PresetEntry.HOLD_PRESET_ID) return@serialized
        PresetStore.deletePreset(context, id)
        val saved = PresetStore.read(context)
        mutableState.value = state.value.copy(entries = PresetStore.sortEntries(saved.entries), activeId = saved.activeId)
    }

    suspend fun editPoints(context: Context, sourceId: String?, edit: (List<HighlightingPoint>) -> List<HighlightingPoint>) = serialized {
        val current = state.value
        check(current.ready)
        // An obsolete view must never edit another preset's working copy.
        if (sourceId != current.activeId) return@serialized
        val points = edit(current.points)
        if (points == current.points) return@serialized
        require(!current.isHold || points.size == 1)
        if (sourceId != null) {
            val saved = PresetStore.update(context) { old -> old.copy(entries = old.entries.map {
                if (it.id == sourceId) it.copy(points = toPresetPoints(points), settings = if (it.isHold) it.settings else current.settings) else it
            }) }
            if (!current.isHold) PointsStore.replaceAll(context, points)
            mutableState.value = current.copy(entries = PresetStore.sortEntries(saved.entries), points = points)
        } else {
            PointsStore.replaceAll(context, points)
            mutableState.value = current.copy(points = points)
        }
    }

    suspend fun editSettings(context: Context, sourceId: String?, settings: PresetSettings) = serialized {
        val current = state.value
        check(current.ready)
        if (sourceId != current.activeId || current.isHold) return@serialized
        if (sourceId != null) {
            val saved = PresetStore.update(context) { old -> old.copy(entries = old.entries.map {
                if (it.id == sourceId) it.copy(settings = settings) else it
            }) }
            writeSettings(context, settings)
            mutableState.value = current.copy(entries = PresetStore.sortEntries(saved.entries), settings = settings)
        } else {
            writeSettings(context, settings)
            mutableState.value = current.copy(settings = settings)
        }
    }

    private suspend fun publish(context: Context, saved: PresetStore.Snapshot, working: List<HighlightingPoint>, fallback: PresetSettings) {
        // The saved active ID is the recovery marker if any following working-store write fails.
        mutableState.value = state.value.copy(ready = false)
        val active = saved.entries.firstOrNull { it.id == saved.activeId }
        val settings = active?.settings ?: fallback
        val points = if (active == null) working else materialize(active, working, settings)
        if (active != null && !active.isHold) {
            if (working != points) PointsStore.replaceAll(context, points)
            writeSettings(context, settings)
        }
        mutableState.value = State(true, PresetStore.sortEntries(saved.entries), saved.activeId, points, settings)
    }

    internal fun toPresetPoints(points: List<HighlightingPoint>) = points.map {
        PresetPoint(it.index, it.actionType, it.x, it.y, it.dragToX, it.dragToY)
    }

    internal fun materialize(entry: PresetEntry, working: List<HighlightingPoint>, settings: PresetSettings): List<HighlightingPoint> {
        val unused = working.toMutableList()
        return entry.points.map { point ->
            val match = unused.indexOfFirst { it.index == point.index }
            val previous = if (match >= 0) unused.removeAt(match) else null
            val base = previous ?: HighlightingPoint(x = point.x, y = point.y, index = point.index,
                delayMs = settings.tapIntervalMs, dragDurationMs = settings.dragDurationMs)
            base.copy(id = if (entry.isHold) PresetEntry.HOLD_PRESET_ID else base.id,
                x = point.x, y = point.y, index = point.index, actionType = point.actionType,
                dragToX = point.dragToX, dragToY = point.dragToY)
        }
    }

    private fun newPreset(current: State, name: String, ordinal: Int) = PresetEntry(
        name = name, createdAtEpochMs = System.currentTimeMillis(), points = toPresetPoints(current.points),
        autoNameOrdinal = ordinal, settings = current.settings
    )

    private suspend fun readSettings(context: Context) = PresetSettings(
        TapIntervalStore.tapIntervalMsFlow(context).first(), DragDurationStore.dragDurationMsFlow(context).first(),
        RandomTouchRadiusStore.randomTouchRadiusDpFlow(context).first(), SequencePrefsStore.repeatEnabledFlow(context).first(),
        SequencePrefsStore.touchAnimationEnabledFlow(context).first()
    )

    private suspend fun writeSettings(context: Context, value: PresetSettings) {
        TapIntervalStore.setTapIntervalMs(context, value.tapIntervalMs)
        DragDurationStore.setDragDurationMs(context, value.dragDurationMs)
        RandomTouchRadiusStore.setRandomTouchRadiusDp(context, value.randomRadiusDp)
        SequencePrefsStore.setRepeatEnabled(context, value.repeatEnabled)
        SequencePrefsStore.setTouchAnimationEnabled(context, value.touchAnimationEnabled)
    }
}
