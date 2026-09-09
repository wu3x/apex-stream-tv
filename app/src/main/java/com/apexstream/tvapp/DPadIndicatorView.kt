package com.apexstream.tvapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A small, semi-transparent on-screen D-pad that flashes the pressed direction —
 * purely native, purely visual feedback so it's obvious the remote press registered,
 * independent of whatever the website itself is doing.
 */
class DPadIndicatorView(context: Context) : View(context) {

    private enum class Dir { UP, DOWN, LEFT, RIGHT, NONE }

    private var flashDir = Dir.NONE
    private var flashAlpha = 0

    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 200, 255)
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var fadeAnimator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 8f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // four pie slices: up, right, down, left (standard angle convention: 0° = 3 o'clock)
        drawSlice(canvas, rect, 225f, 90f, Dir.UP)
        drawSlice(canvas, rect, 315f, 90f, Dir.RIGHT)
        drawSlice(canvas, rect, 45f, 90f, Dir.DOWN)
        drawSlice(canvas, rect, 135f, 90f, Dir.LEFT)

        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.28f, centerPaint)
    }

    private fun drawSlice(canvas: Canvas, rect: RectF, startAngle: Float, sweep: Float, dir: Dir) {
        val paint = if (dir == flashDir && flashAlpha > 0) {
            Paint(flashPaint).apply { alpha = flashAlpha }
        } else {
            idlePaint
        }
        val inset = 10f
        val innerRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawArc(innerRect, startAngle, sweep, true, paint)
    }

    /** Call this from onKeyDown whenever a direction is pressed. */
    fun flash(direction: String) {
        flashDir = when (direction) {
            "up" -> Dir.UP
            "down" -> Dir.DOWN
            "left" -> Dir.LEFT
            "right" -> Dir.RIGHT
            else -> Dir.NONE
        }
        fadeAnimator?.cancel()
        flashAlpha = 255
        invalidate()
        fadeAnimator = ValueAnimator.ofInt(255, 0).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flashAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }
}
