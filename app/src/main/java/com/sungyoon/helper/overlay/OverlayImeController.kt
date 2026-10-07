package com.sungyoon.helper.overlay

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Window focus must arrive before an overlay editor can be served by the IME. */
internal class OverlayImeController(
    private val owner: View,
    private val setWindowFocusable: (Boolean) -> Unit
) {
    private val imm = owner.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var activeEditor: EditText? = null
    private var pendingEditor: EditText? = null
    private var focusObserver: ViewTreeObserver? = null
    private var selectAllOnShow = true
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused) postShowAfterWindowFocus()
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        pendingEditor?.let { if (!it.isAttachedToWindow || !it.isShown) cancelPending() }
    }
    private val editorAttachmentListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (pendingEditor === view) cancelPending()
            if (activeEditor === view) activeEditor = null
        }
    }
    private val releaseWindow = Runnable {
        if (owner.isAttachedToWindow && owner.isShown && owner.rootView.findFocus() !is EditText) hide()
    }
    private val showKeyboard = Runnable {
        val editor = pendingEditor ?: return@Runnable
        if (!editor.isAttachedToWindow || !editor.isShown || !editor.isFocused) {
            cancelPending()
            return@Runnable
        }
        if (!editor.hasWindowFocus()) return@Runnable
        if (selectAllOnShow) editor.selectAll()
        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        cancelPending()
    }

    init {
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = detach()
        })
    }

    fun show(editor: EditText, selectAll: Boolean = true) {
        cancelPending()
        owner.removeCallbacks(releaseWindow)
        if (!editor.isAttachedToWindow || !editor.isShown || !editor.isFocusable) return
        activeEditor = editor
        pendingEditor = editor
        selectAllOnShow = selectAll
        focusObserver = editor.viewTreeObserver.also {
            it.addOnWindowFocusChangeListener(windowFocusListener)
            it.addOnGlobalLayoutListener(layoutListener)
        }
        editor.addOnAttachStateChangeListener(editorAttachmentListener)
        setWindowFocusable(true)
        if (!editor.requestFocus()) {
            hide()
            return
        }
        postShowAfterWindowFocus()
    }

    fun onFocusChanged(editor: EditText, focused: Boolean) {
        if (focused) {
            if (pendingEditor === editor) postShowAfterWindowFocus()
        } else {
            if (pendingEditor === editor) cancelPending()
            owner.removeCallbacks(releaseWindow)
            // The next field gets focus before this callback checks the shared window.
            owner.post(releaseWindow)
        }
    }

    fun hide() {
        cancelPending()
        val editor = activeEditor
        activeEditor = null
        editor?.clearFocus()
        owner.removeCallbacks(releaseWindow)
        val token = editor?.windowToken ?: owner.windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
        setWindowFocusable(false)
    }

    /** Detached screens must not change flags on a newer controller window. */
    fun detach() {
        cancelPending()
        owner.removeCallbacks(releaseWindow)
        activeEditor = null
    }

    private fun postShowAfterWindowFocus() {
        val editor = pendingEditor ?: return
        if (!editor.hasWindowFocus()) return
        editor.removeCallbacks(showKeyboard)
        // Run after Android finishes delivering window focus and serving the editor.
        editor.post(showKeyboard)
    }

    private fun cancelPending() {
        pendingEditor?.let {
            it.removeCallbacks(showKeyboard)
            it.removeOnAttachStateChangeListener(editorAttachmentListener)
        }
        pendingEditor = null
        focusObserver?.takeIf { it.isAlive }?.let {
            it.removeOnWindowFocusChangeListener(windowFocusListener)
            it.removeOnGlobalLayoutListener(layoutListener)
        }
        focusObserver = null
    }
}
