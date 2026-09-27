package com.sungyoon.helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.TouchInteractionController
import android.os.IBinder
import android.os.Parcel
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import com.sungyoon.helper.model.HighlightingPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Main-thread contact ownership; only the Binder writer runs on IO. No persistent state. */
internal class MergedTouchSession(
    private val service: AccessibilityService,
    private val stateChanged: (Boolean) -> Unit,
    private val failed: () -> Unit
) {
    private data class Contact(val id: Int, var x: Float, var y: Float)
    private data class Frame(val action: Int, val downTime: Long, val contacts: List<Contact>,
                             val done: CompletableDeferred<Unit>? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val contacts = linkedMapOf<Int, Contact>()
    private val fingers = mutableMapOf<Int, Int>()
    private var queue: Channel<Frame>? = null
    private var bridge: IBinder? = null
    private var bridgeSession = 0L
    private var writer: Job? = null
    private var heartbeat: Job? = null
    private var actionJob: Job? = null
    private var previousFlags = 0
    private var stopCapture: (() -> Unit)? = null
    private var downTime = 0L
    private var epoch = 0L
    var active = false
        private set
    var holding = false
        private set

    /** Null means the optional adb bridge is absent; keep the original execution path. */
    suspend fun start(points: List<HighlightingPoint>): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        if (active) {
            if (!holding) {
                holding = true
                stateChanged(true)
                points.forEachIndexed { id, point -> add(Contact(id, point.x, point.y)) }
                flush()
            }
            return holding
        }
        val connection = ShellTouchProvider.getBridge() ?: return null
        var connectionSession = 0L
        try {
            withContext(Dispatchers.IO) {
                transact(connection, ShellTouchBridge.OPEN) { reply ->
                    check(reply.readInt() == ShellTouchBridge.VERSION)
                    connectionSession = reply.readLong()
                }
            }
        } catch (cancelled: CancellationException) {
            closeBridge(connection, connectionSession)
            throw cancelled
        } catch (_: Exception) { closeBridge(connection, connectionSession); failed(); return false }
        val accessibility = service.getSystemService(AccessibilityManager::class.java)
        // Off restores this flag asynchronously. Wait for our previous session to detach;
        // a different service's persistent touch exploration remains unsupported.
        val captureAvailable = try {
            withTimeoutOrNull(2000) {
                while (accessibility.isTouchExplorationEnabled) delay(25)
                true
            } == true
        } catch (cancelled: CancellationException) {
            closeBridge(connection, connectionSession)
            throw cancelled
        }
        if (!captureAvailable) {
            closeBridge(connection, connectionSession)
            failed()
            return false
        }
        val generation = ++epoch
        val channel = Channel<Frame>(128)
        bridge = connection
        bridgeSession = connectionSession
        queue = channel
        active = true
        try {
            val info = checkNotNull(service.serviceInfo)
            previousFlags = info.flags
            val controller = service.getTouchInteractionController(android.view.Display.DEFAULT_DISPLAY)
            val callback = object : TouchInteractionController.Callback {
                override fun onMotionEvent(event: MotionEvent) = this@MergedTouchSession.onMotionEvent(event)
                override fun onStateChanged(state: Int) = Unit
            }
            controller.registerCallback(null, callback)
            stopCapture = { controller.unregisterCallback(callback) }
            info.flags = previousFlags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            service.serviceInfo = info
        } catch (_: Exception) { disconnect(); failed(); return false }
        writer = scope.launch(Dispatchers.IO) {
            try {
                for (frame in channel) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeLong(connectionSession)
                        data.writeInt(frame.action)
                        if (frame.action != -1) {
                            data.writeLong(frame.downTime)
                            data.writeInt(frame.contacts.size)
                            for (contact in frame.contacts) {
                                data.writeInt(contact.id)
                                data.writeFloat(contact.x)
                                data.writeFloat(contact.y)
                            }
                        }
                        check(connection.transact(ShellTouchBridge.FRAME, data, reply, 0))
                        reply.readException()
                        check(reply.readInt() == 1) { "Merged input rejected" }
                    } finally { data.recycle(); reply.recycle() }
                    frame.done?.complete(Unit)
                }
            } catch (error: Exception) {
                channel.cancel(CancellationException("Touch bridge disconnected", error))
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (epoch == generation && active) { disconnect(); failed() }
                }
            } finally { closeBridge(connection, connectionSession) }
        }
        heartbeat = scope.launch {
            while (active && epoch == generation) { delay(500); enqueue(-1) }
        }
        try {
            // Wait for touch capture to take effect before injecting the first DOWN.
            withTimeout(2000) {
                while (!accessibility.isTouchExplorationEnabled) delay(25)
            }
            delay(100)
            currentCoroutineContext().ensureActive()
            if (!active || epoch != generation) return false
            holding = true
            stateChanged(true)
            points.forEachIndexed { id, point -> add(Contact(id, point.x, point.y)) }
            flush()
            return active && holding
        } catch (error: CancellationException) {
            disconnect()
            throw error
        } catch (_: Exception) { disconnect(); failed(); return false }
    }

    fun onMotionEvent(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 33 || !active || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        for (i in 0 until event.pointerCount) {
            fingers[event.getPointerId(i)]?.let { id ->
                contacts[id]?.let { it.x = event.getRawX(i); it.y = event.getRawY(i) }
            }
        }
        val i = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = (10..31).firstOrNull { it !in contacts }
                if (id == null || contacts.size >= 16) { disconnect(); failed(); return }
                fingers[event.getPointerId(i)] = id
                add(Contact(id, event.getRawX(i), event.getRawY(i)))
            }
            MotionEvent.ACTION_MOVE -> if (fingers.isNotEmpty()) enqueue(MotionEvent.ACTION_MOVE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                fingers.remove(event.getPointerId(i))?.let { remove(it) }
                closeWhenIdle()
            }
            MotionEvent.ACTION_CANCEL -> {
                fingers.values.toList().forEach(::remove)
                fingers.clear()
                closeWhenIdle()
            }
        }
    }

    suspend fun action(x: Float, y: Float, toX: Float, toY: Float, duration: Long): Boolean {
        if (!active) return false
        val generation = epoch
        actionJob = currentCoroutineContext()[Job]
        try {
            add(Contact(9, x, y))
            val start = SystemClock.uptimeMillis()
            do {
                delay(minOf(16, duration))
                if (!active || epoch != generation) return false
                val progress = ((SystemClock.uptimeMillis() - start).toFloat() / duration).coerceAtMost(1f)
                contacts[9]?.let { it.x = x + (toX - x) * progress; it.y = y + (toY - y) * progress }
                enqueue(MotionEvent.ACTION_MOVE)
            } while (SystemClock.uptimeMillis() - start < duration)
            remove(9)
            flush()
            return active
        } finally {
            if (epoch == generation) { remove(9); actionJob = null; closeWhenIdle() }
        }
    }

    fun stopHolds() {
        holding = false
        contacts.keys.filter { it < 9 }.forEach(::remove)
        stateChanged(false)
        closeWhenIdle()
    }

    fun cancelAction() { actionJob?.cancel(); remove(9) }

    suspend fun flush() {
        if (!active) return
        val done = CompletableDeferred<Unit>()
        val channel = queue ?: return
        check(channel.trySend(Frame(-1, 0, emptyList(), done)).isSuccess)
        withTimeout(2500) { done.await() }
    }

    private fun add(contact: Contact) {
        check(contact.id !in contacts && contacts.size < 16)
        if (contacts.isEmpty()) downTime = SystemClock.uptimeMillis()
        contacts[contact.id] = contact
        enqueue(if (contacts.size == 1) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or ((contacts.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun remove(id: Int) {
        val index = contacts.keys.indexOf(id)
        if (index < 0) return
        enqueue(if (contacts.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        contacts.remove(id)
    }

    private fun enqueue(action: Int) {
        if (!active) return
        if (action != -1 && contacts.isEmpty()) return
        if (queue?.trySend(Frame(action, downTime, contacts.values.map { it.copy() }))?.isSuccess != true) {
            disconnect()
            failed()
        }
    }

    private fun closeWhenIdle() {
        if (!active || holding || contacts.isNotEmpty()) return
        val generation = epoch
        scope.launch {
            try { flush() } catch (_: Exception) { /* Teardown may cancel a queued barrier. */ } finally {
                if (epoch == generation && !holding && contacts.isEmpty()) disconnect()
            }
        }
    }

    fun disconnect() {
        if (!active) return
        active = false
        holding = false
        epoch++
        heartbeat?.cancel()
        actionJob?.cancel()
        if (Build.VERSION.SDK_INT >= 33) {
            // The framework may already have removed the connection during onDestroy.
            runCatching {
                service.serviceInfo?.let { info ->
                    info.flags = previousFlags
                    service.serviceInfo = info
                }
            }
            runCatching { stopCapture?.invoke() }
            stopCapture = null
        }
        contacts.clear()
        fingers.clear()
        queue?.close()
        // Explicit teardown releases the shell's last accepted frame; its watchdog covers app death.
        bridge?.let { closeBridge(it, bridgeSession) }
        writer?.cancel()
        bridge = null
        queue = null
        stateChanged(false)
    }

    private inline fun transact(connection: IBinder, code: Int, session: Long? = null, result: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            if (session != null) data.writeLong(session)
            check(connection.transact(code, data, reply, 0))
            reply.readException()
            result(reply)
        } finally { data.recycle(); reply.recycle() }
    }

    private fun closeBridge(connection: IBinder, session: Long) {
        runCatching { transact(connection, ShellTouchBridge.CLOSE, session) { } }
    }

    fun dispose() { disconnect(); scope.cancel() }
}
