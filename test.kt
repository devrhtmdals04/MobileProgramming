package com.example.helloworld

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.pow

class MainActivity : Activity() {
    private lateinit var greeting: InfiniteHelloView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        greeting = InfiniteHelloView(this, savedInstanceState?.getInt("depth") ?: 0)
        setContentView(greeting)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("depth", greeting.depth)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        greeting.stopZoom()
        super.onPause()
    }
}

/** Each word contains another word inside the counter of the first 'o'. */
class InfiniteHelloView(context: Context, initialDepth: Int) : View(context) {
    var depth = initialDepth
        private set

    private val word = context.getString(R.string.hello_world)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 120f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val colors = intArrayOf(
        Color.rgb(119, 231, 255), Color.rgb(189, 164, 255),
        Color.rgb(255, 171, 207), Color.rgb(255, 217, 140),
        Color.rgb(142, 244, 194),
    )
    private val wordWidth = ink.measureText(word)
    private val wordBounds = Rect().also { ink.getTextBounds(word, 0, word.length, it) }
    private val oBounds = Rect().also { ink.getTextBounds("o", 0, 1, it) }
    private val baseline = -wordBounds.exactCenterY()
    private val anchorX = -wordWidth / 2f + ink.measureText("Hell") + oBounds.exactCenterX()
    private val anchorY = baseline + oBounds.exactCenterY()
    private val childScale = 0.035f
    private var progress = 0f
    private var ignoreGesture = false
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 17, 29))

        val baseScale = width * 0.84f / wordWidth
        val zoom = childScale.toDouble().pow(-progress.toDouble()).toFloat()
        // Move the camera toward the nested word while magnifying it.
        val cameraFraction = (1f - 1f / zoom) / (1f - childScale)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(baseScale * zoom, baseScale * zoom)
        canvas.translate(-anchorX * cameraFraction, -anchorY * cameraFraction)

        if (depth > 0) {
            canvas.save()

            canvas.scale(1f / childScale, 1f / childScale)
            canvas.translate(-anchorX, -anchorY)

            ink.color = colors[(depth - 1) % colors.size]
            canvas.drawText(word, -wordWidth / 2f, baseline, ink)

            canvas.restore()
        }

        repeat(5) { generation ->
            ink.color = colors[((depth.toLong() + generation) % colors.size).toInt()]
            canvas.drawText(word, -wordWidth / 2f, baseline, ink)
            canvas.translate(anchorX, anchorY)
            canvas.scale(childScale, childScale)
        }
        canvas.restore()

    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoreGesture = animator != null
        }
        // Ignore the entire gesture if it began during a zoom, including a late release.
        if (ignoreGesture) return true
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (animator != null) return true
        super.performClick()
        zoomIntoNextWord()
        return true
    }

    private fun zoomIntoNextWord() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Rebase each step to avoid accumulating huge floating-point scales.
                    depth++
                    progress = 0f
                    animator = null
                    updateDescription()
                    invalidate()
                }
            })
        }
        animator?.start()
    }

    private fun updateDescription() {
        contentDescription = "TAB!"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = "깊이 $depth"
        }
    }

    fun stopZoom() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopZoom()
        super.onDetachedFromWindow()
    }
}
