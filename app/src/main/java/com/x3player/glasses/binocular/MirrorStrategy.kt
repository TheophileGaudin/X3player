package com.x3player.glasses.binocular

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout

interface MirrorStrategy {
    fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity)
    fun mirror(dirtyRect: Rect? = null)
    fun release()
}

class DispatchDrawStrategy : MirrorStrategy {
    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) = Unit
    override fun mirror(dirtyRect: Rect?) = Unit
    override fun release() = Unit
}

class PixelCopyStrategy : MirrorStrategy {
    private var rightSurface: SurfaceView? = null
    private var activity: Activity? = null
    private val handler = Handler(Looper.getMainLooper())

    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var frontBuffer: Bitmap? = null
    private var backBuffer: Bitmap? = null
    private var lastCaptureTime = 0L
    private var isCapturing = false

    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) {
        this.rightSurface = rightSurface
        this.activity = activity
        rightSurface.setZOrderOnTop(false)
        rightSurface.holder.setFormat(PixelFormat.TRANSLUCENT)
        rightSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                setupBuffers(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                setupBuffers(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseBuffers()
            }
        })
    }

    override fun mirror(dirtyRect: Rect?) {
        if (isCapturing) return
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < DisplayConfig.MIN_PIXELCOPY_INTERVAL_MS) return
        val activeActivity = activity ?: return
        val back = synchronized(this) { backBuffer } ?: return
        val captureRect = dirtyRect ?: Rect(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
        isCapturing = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(
                activeActivity.window,
                captureRect,
                back,
                { result ->
                    isCapturing = false
                    if (result == PixelCopy.SUCCESS) {
                        lastCaptureTime = System.currentTimeMillis()
                        swapBuffers()
                        drawToSurface()
                    }
                },
                handler,
            )
        } else {
            isCapturing = false
        }
    }

    override fun release() {
        releaseBuffers()
        rightSurface = null
        activity = null
    }

    private fun setupBuffers(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(this) {
            releaseBuffers()
            bufferA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bufferB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frontBuffer = bufferA
            backBuffer = bufferB
        }
    }

    private fun releaseBuffers() {
        synchronized(this) {
            bufferA?.recycle()
            bufferB?.recycle()
            bufferA = null
            bufferB = null
            frontBuffer = null
            backBuffer = null
        }
    }

    private fun swapBuffers() {
        synchronized(this) {
            val temp = frontBuffer
            frontBuffer = backBuffer
            backBuffer = temp
        }
    }

    private fun drawToSurface() {
        val surface = rightSurface ?: return
        val bitmap = synchronized(this) { frontBuffer } ?: return
        var canvas: Canvas? = null
        try {
            canvas = surface.holder.lockCanvas()
            canvas?.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            canvas?.let { surface.holder.unlockCanvasAndPost(it) }
        }
    }
}

class CompositeStrategy : MirrorStrategy {
    private var leftContent: FrameLayout? = null
    private val dispatchStrategy = DispatchDrawStrategy()
    private val pixelCopyStrategy = PixelCopyStrategy()
    private var activeStrategy: MirrorStrategy = dispatchStrategy

    override fun initialize(leftContent: FrameLayout, rightSurface: SurfaceView, activity: Activity) {
        this.leftContent = leftContent
        dispatchStrategy.initialize(leftContent, rightSurface, activity)
        pixelCopyStrategy.initialize(leftContent, rightSurface, activity)
    }

    fun setHasComplexContent(hasComplexContent: Boolean) {
        activeStrategy = if (hasComplexContent) pixelCopyStrategy else dispatchStrategy
    }

    fun detectComplexContent() {
        val content = leftContent ?: return
        setHasComplexContent(containsComplexViews(content))
    }

    override fun mirror(dirtyRect: Rect?) {
        activeStrategy.mirror(dirtyRect)
    }

    override fun release() {
        dispatchStrategy.release()
        pixelCopyStrategy.release()
        leftContent = null
    }

    private fun containsComplexViews(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("SurfaceView") || name.contains("TextureView") || name.contains("VideoView")) {
            return true
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsComplexViews(view.getChildAt(index))) {
                    return true
                }
            }
        }
        return false
    }
}
