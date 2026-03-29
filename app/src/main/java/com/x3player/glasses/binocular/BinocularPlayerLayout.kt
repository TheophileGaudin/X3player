package com.x3player.glasses.binocular

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

class BinocularPlayerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr), BinocularRenderer {

    private lateinit var contentContainer: FrameLayout
    private lateinit var rightEyeSurface: SurfaceView
    private var mirrorStrategy: CompositeStrategy? = null
    private var refreshController: RefreshController? = null
    private var useDispatchDrawMode = true
    private var activity: Activity? = null
    private var internalSetupComplete = false
    private var pendingInvalidation = false

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val xmlChildren = mutableListOf<View>()
        for (index in 0 until childCount) {
            xmlChildren += getChildAt(index)
        }
        removeAllViews()

        contentContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            setBackgroundColor(Color.BLACK)
        }
        rightEyeSurface = SurfaceView(context).apply {
            layoutParams = LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            visibility = View.GONE
        }

        super.addView(contentContainer, LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT))
        super.addView(rightEyeSurface, LayoutParams(DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT))

        xmlChildren.forEach { child -> contentContainer.addView(child) }

        refreshController = RefreshController { dirtyRect ->
            mirrorStrategy?.mirror(dirtyRect)
            if (useDispatchDrawMode && pendingInvalidation) {
                pendingInvalidation = false
                post {
                    invalidate()
                    rootView?.invalidate()
                }
            }
        }

        internalSetupComplete = true
    }

    fun initialize(activity: Activity) {
        this.activity = activity
        if (!internalSetupComplete) return
        mirrorStrategy = CompositeStrategy().also {
            it.initialize(contentContainer, rightEyeSurface, activity)
        }
        refreshController?.start()
    }

    override fun setMirrorMode(isComplexContent: Boolean) {
        useDispatchDrawMode = !isComplexContent
        rightEyeSurface.visibility = if (isComplexContent) View.VISIBLE else View.GONE
        mirrorStrategy?.setHasComplexContent(isComplexContent)
        refreshController?.setMode(if (isComplexContent) RefreshMode.REALTIME else RefreshMode.IDLE)
        notifyFrameChanged()
    }

    override fun notifyFrameChanged() {
        pendingInvalidation = true
        if (useDispatchDrawMode) {
            invalidate()
            postInvalidate()
            rootView?.postInvalidate()
        } else {
            refreshController?.forceRefresh()
        }
    }

    override fun release() {
        refreshController?.stop()
        mirrorStrategy?.release()
        activity = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshController?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        refreshController?.stop()
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (child == null) return
        if (!internalSetupComplete) {
            super.addView(child, index, params)
            return
        }
        if (child !== contentContainer && child !== rightEyeSurface) {
            contentContainer.addView(child, params)
            mirrorStrategy?.detectComplexContent()
        } else {
            super.addView(child, index, params)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (internalSetupComplete) {
            val eyeWidthSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.EYE_WIDTH, MeasureSpec.EXACTLY)
            val eyeHeightSpec = MeasureSpec.makeMeasureSpec(DisplayConfig.EYE_HEIGHT, MeasureSpec.EXACTLY)
            contentContainer.measure(eyeWidthSpec, eyeHeightSpec)
            rightEyeSurface.measure(eyeWidthSpec, eyeHeightSpec)
        }
        setMeasuredDimension(DisplayConfig.SCREEN_WIDTH, DisplayConfig.SCREEN_HEIGHT)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!internalSetupComplete) return
        contentContainer.layout(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
        rightEyeSurface.layout(DisplayConfig.EYE_WIDTH, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.EYE_HEIGHT)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!internalSetupComplete) {
            super.dispatchDraw(canvas)
            return
        }
        if (useDispatchDrawMode) {
            canvas.save()
            canvas.clipRect(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            contentContainer.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.clipRect(DisplayConfig.EYE_WIDTH, 0, DisplayConfig.SCREEN_WIDTH, DisplayConfig.EYE_HEIGHT)
            canvas.translate(DisplayConfig.EYE_WIDTH.toFloat(), 0f)
            contentContainer.draw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
