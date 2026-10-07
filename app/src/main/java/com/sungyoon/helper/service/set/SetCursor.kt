package com.sungyoon.helper.service.set

import com.sungyoon.helper.model.SetItem

/** Tracks only this pass; item configuration is frozen separately by the runner. */
internal class SetCursor {
    private val completed = mutableSetOf<String>()
    private val deferred = mutableSetOf<String>()
    private var currentId: String? = null
    private var currentIndex = 0

    @Synchronized
    fun observe(items: List<SetItem>) {
        val id = currentId ?: return
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) currentIndex = index
        // Moving an upcoming item above the cursor defers it for the entire pass.
        items.take(currentIndex).forEach {
            if (it.id != id && it.id !in completed) deferred += it.id
        }
    }

    @Synchronized
    fun first(items: List<SetItem>): SetItem? {
        completed.clear()
        deferred.clear()
        currentId = null
        currentIndex = 0
        return select(items, 0)
    }

    @Synchronized
    fun next(items: List<SetItem>): SetItem? {
        observe(items)
        currentId?.let { completed += it }
        val index = items.indexOfFirst { it.id == currentId }
        // A deleted current item leaves the cursor at its previous, now vacant slot.
        val from = if (index >= 0) index + 1 else currentIndex
        return select(items, from)
    }

    @Synchronized
    fun position(items: List<SetItem>): Int {
        observe(items)
        return if (currentId == null) 0 else currentIndex + 1
    }

    private fun select(items: List<SetItem>, from: Int): SetItem? {
        val index = (from.coerceAtLeast(0) until items.size).firstOrNull {
            val item = items[it]
            item.isExecutable && item.id !in completed && item.id !in deferred
        } ?: return null
        val selected = items[index]
        currentIndex = index
        currentId = selected.id
        return selected
    }
}
