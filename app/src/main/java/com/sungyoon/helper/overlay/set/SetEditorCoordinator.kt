package com.sungyoon.helper.overlay.set

import android.content.Context
import android.util.Log
import android.view.View
import com.sungyoon.helper.R
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.model.SetItem
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
    fun setPointerEditTarget(itemId: String?)
    fun addPointer(isDrag: Boolean)
    fun clearPointers()
    fun openItemPresets(onBack: () -> Unit)
    fun renameItem(item: SetItem, onName: (String) -> Unit)
    fun confirmDelete(item: SetItem, onConfirm: () -> Unit)
    fun toggleSetRun()
    fun requestIme(show: Boolean)
}

/** Navigation and scoped mutations stay outside the window and gesture controllers. */
class SetEditorCoordinator(
    private val context: Context,
    scope: CoroutineScope,
    private val host: SetEditorHost
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
    private var items = emptyList<SetItem>()
    private var selectedId: String? = null
    private var screen: Screen = Screen.ListScreen
    private var visibleView: SetPanelView? = null
    private var runtime = SetRuntime.state.value
    private var viewportHeight = 0
    private var listScrollPosition = 0
    var isOpen: Boolean = false
        private set
    private val listPanel by lazy {
        SetListPanelView(context,
            onBack = ::close,
            onSelect = { selectedId = it },
            onEdit = ::showMenu,
            onAdd = ::showAdd,
            onDelete = ::deleteSelected,
            onDuplicate = ::duplicateSelected,
            onToggleRun = host::toggleSetRun,
            onMove = { id, target -> write { SetStore.moveItem(appContext, id, target) } }
        )
    }

    init {
        uiScope.launch {
            SetStore.itemsFlow(appContext).collect { savedItems ->
                items = savedItems
                if (selectedId != null && items.none { it.id == selectedId }) selectedId = null
                if (isOpen) updateVisibleItem()
            }
        }
        uiScope.launch {
            SetRuntime.state.collect { state ->
                runtime = state
                if (isOpen) renderRuntime()
            }
        }
    }

    fun open() {
        if (disposed) return
        isOpen = true
        showList()
    }

    fun close() {
        if (!isOpen) return
        rememberListScroll()
        closeIme()
        isOpen = false
        visibleView = null
        screen = Screen.ListScreen
        host.setPointerEditTarget(null)
        host.showSetContent(null)
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
    }

    fun setMaxViewportHeight(px: Int) {
        viewportHeight = px.coerceAtLeast(0)
        visibleView?.setMaxViewportHeight(viewportHeight)
    }

    private fun showList() {
        if (!isOpen || disposed) return
        screen = Screen.ListScreen
        listPanel.clearInlineMenu()
        listPanel.setItems(items, selectedId)
        listPanel.renderRuntime(runtime)
        display(listPanel)
        listPanel.restoreSavedScrollPosition(listScrollPosition)
    }

    private fun showAdd() {
        if (screen == Screen.Add) return showList()
        rememberListScroll()
        screen = Screen.Add
        listPanel.showInlineMenu(null,
            SetItemType.entries.map { type -> SetListPanelView.typeLabel(context, type) to {
                write {
                    val added = SetStore.addItem(appContext, type)
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
            if (latest != null) host.renameItem(latest) { name -> write { SetStore.renameItem(appContext, id, name) } }
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
            onClear = host::clearPointers,
            onAddTap = { host.addPointer(false) },
            onAddDrag = { host.addPointer(true) },
            onPresets = {
                screen = Screen.Presets(id)
                host.openItemPresets { if (isOpen) showPointers(id) }
            }
        ), showPointers = true, targetId = id)
    }

    private fun showReservation(id: String) {
        val item = findItem(id) as? SetItem.Reserved ?: return showList()
        screen = Screen.Reservation(id)
        val panel = SetReservationPanelView(context, item, { showMenu(id) },
            onValue = { config -> write { SetStore.updateReservation(appContext, id, config) } },
            requestIme = host::requestIme)
        display(panel)
        panel.renderRuntime(runtime)
    }

    private fun showWait(id: String) {
        val item = findItem(id) as? SetItem.Wait ?: return showList()
        screen = Screen.Wait(id)
        display(SetWaitPanelView(context, item, { showMenu(id) },
            onValue = { duration -> write { SetStore.updateWait(appContext, id, duration) } },
            requestIme = host::requestIme))
    }

    private fun deleteSelected() {
        val item = selectedId?.let(::findItem) ?: return
        host.confirmDelete(item) {
            write {
                SetStore.deleteItem(appContext, item.id)
                if (selectedId == item.id) selectedId = null
            }
        }
    }

    private fun duplicateSelected() {
        val id = selectedId ?: return
        write {
            val duplicate = SetStore.duplicateItem(appContext, id) ?: return@write
            selectedId = duplicate.id
            if (isOpen && screen == Screen.ListScreen) listPanel.setSelectedItem(duplicate.id)
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
        val alreadyVisible = visibleView === panel
        closeIme()
        visibleView = panel
        panel.setMaxViewportHeight(viewportHeight)
        host.setPointerEditTarget(targetId)
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

    private fun write(block: suspend () -> Unit) {
        if (disposed) return
        val job = writeScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
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
