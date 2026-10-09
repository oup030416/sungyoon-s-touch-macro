package com.sungyoon.helper.overlay.set

import android.content.Context
import android.util.Log
import android.view.View
import com.sungyoon.helper.R
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.model.SetItem
import com.sungyoon.helper.model.SetDefinition
import com.sungyoon.helper.service.set.SetRunState
import kotlinx.coroutines.flow.first
import com.sungyoon.helper.model.SetItemType
import com.sungyoon.helper.service.set.SetRuntime
import com.sungyoon.helper.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

interface SetEditorHost {
    fun showSetContent(view: View?, showPointers: Boolean = false)
    fun minimizeSetContent()
    fun setPointerEditTarget(setId: String?, itemId: String?)
    fun addPointer(isDrag: Boolean)
    fun clearPointers()
    fun openItemPresets(onBack: () -> Unit)
    fun renameItem(item: SetItem, onName: (String) -> Unit)
    fun confirmDelete(item: SetItem, onConfirm: () -> Unit)
    fun startOrResumeSet(setId: String)
    fun cancelSetRun(setId: String)
    fun requestIme(show: Boolean)
}

/** Navigation and scoped mutations stay outside the window and gesture controllers. */
class SetEditorCoordinator(
    private val context: Context,
    scope: CoroutineScope,
    private val host: SetEditorHost,
    initialSet: SetDefinition,
    private val onBack: () -> Unit,
    private val onDeleteSet: () -> Unit,
) {

    private sealed interface Screen {
        data object ListScreen : Screen
        data object Add : Screen
        data class Menu(val id: String) : Screen
        data class Pointers(val id: String) : Screen
        data class Presets(val id: String) : Screen
        data class Reservation(val id: String) : Screen
        data class Wait(val id: String) : Screen
    }

    private val appContext = context.applicationContext
    private val uiJob = SupervisorJob(scope.coroutineContext[Job])
    private val uiScope = CoroutineScope(scope.coroutineContext + uiJob + Dispatchers.Main.immediate)
    // Finite saves survive UI teardown; flushWrites is awaited before playback resumes.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = mutableSetOf<Job>()
    private var disposed = false
    val setId = initialSet.id
    private var definition = initialSet
    private var items = initialSet.items
    private var localRepeat: Boolean? = null
    private var repeatRevision = 0L
    private var failure: Exception? = null
    private var screenGeneration = 0L
    val navigationGeneration: Long get() = screenGeneration
    private var selectedId: String? = null
    private var screen: Screen = Screen.ListScreen
    private var visibleView: SetPanelView? = null
    private var runtime = ownedRuntime(SetRuntime.state.value)
    private var viewportHeight = 0
    private var listScrollPosition = 0
    var isOpen: Boolean = false
        private set
    private val listPanel by lazy {
        SetListPanelView(context,
            onBack = ::close,
            onDeleteSet = onDeleteSet,
            onSelect = { selectedId = it },
            onToggleMenu = ::showMenu,
            onAdd = ::showAdd,
            onDelete = ::deleteItem,
            onDuplicate = ::duplicateItem,
            onStartOrResume = { host.startOrResumeSet(setId) },
            onCancel = { host.cancelSetRun(setId) },
            onRepeat = ::toggleRepeat,
            onMove = { id, target -> write { SetStore.moveItem(appContext, setId, id, target) } }
        )
    }

    init {
        uiScope.launch {
            SetStore.setFlow(appContext, setId).collect { savedSet ->
                if (savedSet == null) return@collect
                val itemsChanged = items != savedSet.items
                definition = savedSet
                items = savedSet.items
                listPanel.setDefinition(definition.name, localRepeat ?: definition.repeatEnabled)
                if (selectedId != null && items.none { it.id == selectedId }) selectedId = null
                if (isOpen && itemsChanged) updateVisibleItem()
            }
        }
        uiScope.launch {
            SetRuntime.state.collect { state ->
                runtime = ownedRuntime(state)
                if (isOpen) renderRuntime()
            }
        }
    }

    fun open() {
        if (disposed) return
        isOpen = true
        showList()
        listPanel.scrollCurrentItemToTop()
    }

    fun close() {
        if (!isOpen) return
        rememberListScroll()
        closeIme()
        isOpen = false
        visibleView = null
        screen = Screen.ListScreen
        host.setPointerEditTarget(null, null)
        onBack()
    }

    fun dispose() {
        if (disposed) return
        rememberListScroll()
        closeIme()
        isOpen = false
        visibleView = null
        disposed = true
        uiJob.cancel()
        if (writes.isEmpty()) writeScope.cancel()
    }

    suspend fun flushWrites() {
        while (writes.isNotEmpty()) writes.toList().joinAll()
        failure?.let { failure = null; throw it }
    }

    fun setMaxViewportHeight(px: Int) {
        viewportHeight = px.coerceAtLeast(0)
        visibleView?.setMaxViewportHeight(viewportHeight)
    }

    private fun showList() {
        if (!isOpen || disposed) return
        screenGeneration++
        screen = Screen.ListScreen
        listPanel.setDefinition(definition.name, localRepeat ?: definition.repeatEnabled)
        listPanel.clearInlineMenu()
        listPanel.setItems(items, selectedId)
        listPanel.renderRuntime(runtime)
        display(listPanel)
        listPanel.restoreSavedScrollPosition(listScrollPosition)
    }

    private fun showAdd() {
        if (screen == Screen.Add) return showList()
        rememberListScroll()
        screenGeneration++
        screen = Screen.Add
        listPanel.showInlineMenu(null,
            SetItemType.entries.map { type -> SetListPanelView.typeLabel(context, type) to {
                write {
                    val added = SetStore.addItem(appContext, setId, type)
                    selectedId = added.id
                    if (isOpen && !disposed) showList()
                }
            } }, ::showList)
    }

    private fun showMenu(id: String) {
        if (screen == Screen.Menu(id)) return showList()
        val item = findItem(id) ?: return showList()
        rememberListScroll()
        selectedId = id
        screen = Screen.Menu(id)
        val options = mutableListOf(context.getString(R.string.set_rename) to {
            val latest = findItem(id)
            val source = screenGeneration
            if (latest != null) host.renameItem(latest) { name ->
                if (!disposed && isOpen && screenGeneration == source) write { SetStore.renameItem(appContext, setId, id, name) }
            }
        })
        when (item) {
            is SetItem.Touch -> options += context.getString(R.string.set_pointer_manage) to { showPointers(id) }
            is SetItem.Reserved -> {
                options += context.getString(R.string.set_pointer_manage) to { showPointers(id) }
                options += context.getString(R.string.set_reservation_manage) to { showReservation(id) }
            }
            is SetItem.Wait -> options += context.getString(R.string.set_time) to { showWait(id) }
        }
        // Keep the parent list mounted while its item actions are expanded.
        listPanel.setItems(items, selectedId)
        listPanel.renderRuntime(runtime)
        display(listPanel)
        listPanel.showInlineMenu(id, options, ::showList)
    }

    private fun showPointers(id: String) {
        val item = findItem(id)?.takeUnless { it is SetItem.Wait } ?: return showList()
        screen = Screen.Pointers(id)
        display(SetPointerPanelView(context, item, { showMenu(id) },
            onMinimize = host::minimizeSetContent,
            onClear = host::clearPointers,
            onAddTap = { host.addPointer(false) },
            onAddDrag = { host.addPointer(true) },
            onPresets = {
                screenGeneration++
                screen = Screen.Presets(id)
                val source = screenGeneration
                host.openItemPresets { if (isOpen && !disposed && screenGeneration == source) showPointers(id) }
            }
        ), showPointers = true, targetId = id)
    }

    private fun showReservation(id: String) {
        val item = findItem(id) as? SetItem.Reserved ?: return showList()
        screen = Screen.Reservation(id)
        val panel = SetReservationPanelView(context, item, { showMenu(id) },
            onValue = { config -> saveOption { SetStore.updateReservation(appContext, setId, id, config) } },
            requestIme = host::requestIme)
        display(panel)
        panel.renderRuntime(runtime)
    }

    private fun showWait(id: String) {
        val item = findItem(id) as? SetItem.Wait ?: return showList()
        screen = Screen.Wait(id)
        display(SetWaitPanelView(context, item, { showMenu(id) },
            onValue = { duration -> saveOption { SetStore.updateWait(appContext, setId, id, duration) } },
            requestIme = host::requestIme))
    }

    private fun deleteItem(id: String) {
        val item = findItem(id) ?: return
        val source = screenGeneration
        host.confirmDelete(item) {
            if (disposed || !isOpen || screenGeneration != source) return@confirmDelete
            write {
                SetStore.deleteItem(appContext, setId, item.id)
                if (selectedId == item.id) selectedId = null
            }
        }
    }

    private fun duplicateItem(id: String) {
        if (findItem(id) == null) return
        write {
            val duplicate = SetStore.duplicateItem(appContext, setId, id) ?: return@write
            selectedId = duplicate.id
            if (isOpen && (screen == Screen.ListScreen || screen is Screen.Menu)) {
                listPanel.setSelectedItem(duplicate.id)
            }
        }
    }

    private fun updateVisibleItem() {
        val id = when (val current = screen) {
            is Screen.Menu -> current.id
            is Screen.Pointers -> current.id
            is Screen.Presets -> current.id
            is Screen.Reservation -> current.id
            is Screen.Wait -> current.id
            Screen.ListScreen, Screen.Add -> null
        }
        val item = id?.let(::findItem)
        if (id != null && item == null) {
            showList()
            return
        }
        when (screen) {
            Screen.ListScreen, Screen.Add, is Screen.Menu -> listPanel.setItems(items, selectedId)
            is Screen.Pointers -> (visibleView as? SetPointerPanelView)?.updateItem(item!!)
            is Screen.Reservation -> (visibleView as? SetReservationPanelView)?.updateItem(item as SetItem.Reserved)
            is Screen.Wait -> (visibleView as? SetWaitPanelView)?.updateItem(item as SetItem.Wait)
            is Screen.Presets -> Unit
        }
    }

    private fun renderRuntime() {
        when (screen) {
            Screen.ListScreen, Screen.Add, is Screen.Menu -> listPanel.renderRuntime(runtime)
            is Screen.Reservation -> (visibleView as? SetReservationPanelView)?.renderRuntime(runtime)
            else -> Unit
        }
    }

    private fun display(panel: SetPanelView, showPointers: Boolean = false, targetId: String? = null) {
        screenGeneration++
        val alreadyVisible = visibleView === panel
        closeIme()
        visibleView = panel
        panel.setMaxViewportHeight(viewportHeight)
        host.setPointerEditTarget(if (targetId != null) setId else null, targetId)
        if (!alreadyVisible) host.showSetContent(panel, showPointers)
    }

    private fun rememberListScroll() {
        (visibleView as? SetListPanelView)?.let { listScrollPosition = it.savedScrollPosition() }
    }

    private fun closeIme() {
        (visibleView as? SetReservationPanelView)?.closeIme()
        (visibleView as? SetWaitPanelView)?.closeIme()
        host.requestIme(false)
    }

    private fun findItem(id: String): SetItem? = items.firstOrNull { it.id == id }

    private fun ownedRuntime(state: SetRunState): SetRunState =
        if (state.setId == setId) state else SetRunState()

    private fun toggleRepeat() {
        val enabled = !(localRepeat ?: definition.repeatEnabled)
        val revision = ++repeatRevision
        localRepeat = enabled
        listPanel.setDefinition(definition.name, enabled)
        write {
            try {
                SetStore.setRepeatEnabled(appContext, setId, enabled)
            } catch (error: Exception) {
                if (repeatRevision == revision) {
                    localRepeat = SetStore.setFlow(appContext, setId).first()?.repeatEnabled
                    if (!disposed && isOpen) listPanel.setDefinition(definition.name, localRepeat ?: definition.repeatEnabled)
                }
                throw error
            }
            // Keep the mounted editor's choice authoritative over delayed older acknowledgements.
        }
    }

    private fun saveOption(block: suspend () -> Boolean) {
        val sourcePanel = visibleView ?: return
        val revision = sourcePanel.clearSaveStatus()
        write {
            val saved = block()
            // A late save must never mark a reopened editor as having saved its own changes.
            if (saved && !disposed && isOpen && visibleView === sourcePanel) sourcePanel.showChangesSaved(revision)
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (disposed) return
        val preceding = writes.toList()
        val job = writeScope.launch(start = CoroutineStart.LAZY) {
            try {
                preceding.joinAll()
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                this@SetEditorCoordinator.failure = failure
                Log.e("SetEditor", "Failed to persist a set edit", failure)
                toast(appContext, appContext.getString(R.string.set_save_failed))
            }
        }
        writes += job
        job.invokeOnCompletion {
            writes -= job
            if (disposed && writes.isEmpty()) writeScope.cancel()
        }
        job.start()
    }
}
