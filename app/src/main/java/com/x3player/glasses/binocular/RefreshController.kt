package com.x3player.glasses.binocular

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RefreshController(
    private val onRefresh: (dirtyRect: Rect?) -> Unit,
) {
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val currentMode = AtomicReference(RefreshMode.IDLE)
    private val dirtyRect = Rect()
    private val isDirty = AtomicBoolean(false)
    private val dirtyLock = Any()

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            val mode = currentMode.get()
            if (mode.needsContinuousRefresh()) {
                handler.removeCallbacks(refreshRunnable)
                handler.post(refreshRunnable)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning.get()) return
            val mode = currentMode.get()
            if (mode == RefreshMode.HIGH || mode == RefreshMode.REALTIME) {
                performRefresh()
                choreographer.postFrameCallback(this)
            }
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            val mode = currentMode.get()
            if (mode.needsContinuousRefresh() && mode != RefreshMode.HIGH && mode != RefreshMode.REALTIME) {
                performRefresh()
                handler.postDelayed(this, mode.getIntervalMs())
            }
        }
    }

    fun setMode(mode: RefreshMode) {
        val previous = currentMode.getAndSet(mode)
        if (previous == mode || !isRunning.get()) return
        stopScheduling()
        if (mode.needsContinuousRefresh()) {
            startScheduling(mode)
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        val mode = currentMode.get()
        if (mode.needsContinuousRefresh()) {
            startScheduling(mode)
        }
        handler.postDelayed(keepAliveRunnable, 1000L)
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        stopScheduling()
        handler.removeCallbacks(keepAliveRunnable)
    }

    fun markDirty() {
        synchronized(dirtyLock) {
            dirtyRect.set(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            isDirty.set(true)
        }
        if (isRunning.get()) {
            handler.post { performRefresh() }
        }
    }

    fun forceRefresh() {
        markDirty()
        handler.post { performRefresh() }
    }

    private fun startScheduling(mode: RefreshMode) {
        when (mode) {
            RefreshMode.HIGH, RefreshMode.REALTIME -> choreographer.postFrameCallback(frameCallback)
            RefreshMode.LOW, RefreshMode.NORMAL -> handler.post(refreshRunnable)
            RefreshMode.IDLE -> Unit
        }
    }

    private fun stopScheduling() {
        choreographer.removeFrameCallback(frameCallback)
        handler.removeCallbacks(refreshRunnable)
    }

    private fun performRefresh() {
        val dirty = synchronized(dirtyLock) {
            if (isDirty.getAndSet(false)) {
                Rect(dirtyRect).also { dirtyRect.setEmpty() }
            } else {
                null
            }
        }
        if (dirty != null || currentMode.get().needsContinuousRefresh()) {
            onRefresh(dirty)
        }
    }
}
