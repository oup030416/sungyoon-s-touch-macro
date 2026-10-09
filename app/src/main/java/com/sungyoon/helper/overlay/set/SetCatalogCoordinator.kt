package com.sungyoon.helper.overlay.set

import android.content.Context
import android.util.Log
import com.sungyoon.helper.R
import com.sungyoon.helper.data.SetStore
import com.sungyoon.helper.model.SetDefinition
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

interface SetCatalogHost : SetEditorHost {
    fun renameSet(set: SetDefinition, onName: (String) -> Unit)
    fun confirmDeleteSet(set: SetDefinition, onConfirm: () -> Unit)
    fun rememberSetScreen(editor: Boolean, setId: String?)
}

/** Owns catalog selection and editor lifetimes; execution ownership lives in the service. */
class SetCatalogCoordinator(
    private val context: Context,
    parentScope: CoroutineScope,
    private val host: SetCatalogHost,
    initialSets: List<SetDefinition>,
    initialSelection: String?,
) {
    private val uiScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = mutableSetOf<Job>()
    private val editors = mutableListOf<SetEditorCoordinator>()
    private var disposed = false
    private var sets = initialSets
    private var selectedId = initialSelection?.takeIf { id -> sets.any { it.id == id } }
    private var viewportHeight = 0
    private var catalogScroll = 0
    private var failure: Exception? = null
    var editor: SetEditorCoordinator? = null
        private set
    var generation = 0L
        private set
    var isOpen = false
        private set
    private val panel = SetCatalogPanelView(context, ::close, ::openEditor, ::rename, ::duplicate, ::add, ::move)

    init {
        uiScope.launch {
            SetStore.setsFlow(context).collect { latest ->
                sets = latest
                if (sets.none { it.id == selectedId }) selectedId = null
                if (editor != null && sets.none { it.id == editor?.setId }) open()
                if (isOpen && editor == null) panel.render(sets, selectedId)
            }
        }
    }

    fun open() {
        if (disposed) return
        generation++
        retireEditor()
        isOpen = true
        host.setPointerEditTarget(null, null)
        panel.render(sets, selectedId)
        panel.setMaxViewportHeight(viewportHeight)
        host.showSetContent(panel)
        panel.restoreSavedScrollPosition(catalogScroll)
        host.rememberSetScreen(false, selectedId)
    }

    fun openEditor(id: String) {
        if (disposed) return
        val definition = sets.firstOrNull { it.id == id } ?: return open()
        selectedId = id
        if (SetRuntime.active && SetRuntime.state.value.setId != id) {
            panel.render(sets, selectedId)
            toast(context, context.getString(R.string.set_catalog_open_blocked))
            return
        }
        if (editor == null && isOpen) catalogScroll = panel.savedScrollPosition()
        generation++
        retireEditor()
        isOpen = true
        val next = SetEditorCoordinator(context, uiScope, host, definition, ::open) { delete(id) }
        editors += next
        editor = next
        next.setMaxViewportHeight(viewportHeight)
        next.open()
        host.rememberSetScreen(true, id)
    }

    fun close() {
        if (disposed) return
        generation++
        catalogScroll = panel.savedScrollPosition()
        retireEditor()
        isOpen = false
        host.setPointerEditTarget(null, null)
        host.showSetContent(null)
    }

    fun setMaxViewportHeight(px: Int) {
        viewportHeight = px
        panel.setMaxViewportHeight(px)
        editor?.setMaxViewportHeight(px)
    }

    suspend fun flushWrites() {
        while (writes.isNotEmpty()) writes.toList().joinAll()
        editors.toList().forEach { it.flushWrites() }
        failure?.let { failure = null; throw it }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        editors.forEach { it.dispose() }
        editor = null
        uiScope.cancel()
        if (writes.isEmpty()) writeScope.cancel()
    }

    private fun retireEditor() {
        val retired = editor ?: return
        retired.dispose()
        editor = null
        // Release retired views after their finite saves have settled.
        write { try { retired.flushWrites() } finally { editors.remove(retired) } }
    }

    private fun rename(id: String) {
        val set = sets.firstOrNull { it.id == id } ?: return
        selectedId = id
        panel.render(sets, selectedId)
        val source = generation
        host.renameSet(set) { name ->
            if (!disposed && generation == source) write { SetStore.renameSet(context, id, name) }
        }
    }

    private fun add() {
        if (SetRuntime.active) { toast(context, context.getString(R.string.set_catalog_add_blocked)); return }
        val source = generation
        write {
            if (SetRuntime.active) { toast(context, context.getString(R.string.set_catalog_add_blocked)); return@write }
            val set = SetStore.addSet(context)
            if (!disposed && generation == source && isOpen && editor == null) {
                // Store completion can precede the IO flow emission.
                sets = sets.filterNot { it.id == set.id } + set
                openEditor(set.id)
            }
        }
    }

    private fun duplicate(id: String) {
        if (SetRuntime.active) { toast(context, context.getString(R.string.set_catalog_add_blocked)); return }
        val source = generation
        write {
            if (SetRuntime.active) { toast(context, context.getString(R.string.set_catalog_add_blocked)); return@write }
            val copy = SetStore.duplicateSet(context, id) ?: return@write
            if (!disposed && generation == source && isOpen && editor == null) {
                sets = sets.filterNot { it.id == copy.id } + copy
                selectedId = copy.id
                panel.render(sets, selectedId)
                host.rememberSetScreen(false, selectedId)
            }
        }
    }

    private fun move(id: String, toIndex: Int) {
        write { SetStore.moveSet(context, id, toIndex) }
    }

    private fun delete(id: String) {
        val set = sets.firstOrNull { it.id == id } ?: return
        if (SetRuntime.active && SetRuntime.state.value.setId == set.id) {
            toast(context, context.getString(R.string.set_catalog_delete_blocked)); return
        }
        val source = generation
        val sourceEditor = editor?.takeIf { it.setId == id } ?: return
        host.confirmDeleteSet(set) {
            if (disposed || generation != source) return@confirmDeleteSet
            write {
                if (SetRuntime.active && SetRuntime.state.value.setId == set.id) {
                    toast(context, context.getString(R.string.set_catalog_delete_blocked)); return@write
                }
                // Settle pending editor saves before removing their target.
                sourceEditor.flushWrites()
                if (SetRuntime.active && SetRuntime.state.value.setId == set.id) {
                    toast(context, context.getString(R.string.set_catalog_delete_blocked)); return@write
                }
                SetStore.deleteSet(context, set.id)
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (disposed) return
        val preceding = writes.toList()
        val job = writeScope.launch(start = CoroutineStart.LAZY) {
            try { preceding.joinAll(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = error
                Log.e("SetCatalog", "Catalog edit failed", error)
                toast(context, context.getString(R.string.set_save_failed))
            }
        }
        writes += job
        job.invokeOnCompletion { writes -= job; if (disposed && writes.isEmpty()) writeScope.cancel() }
        job.start()
    }
}
