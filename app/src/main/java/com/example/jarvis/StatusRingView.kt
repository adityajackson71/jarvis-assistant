package com.example.jarvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class StatusRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private var state: State = State.IDLE
    private var rotation = 0f

    private val cyan = Color.parseColor("#3DDCFF")
    private val dimCyan = Color.parseColor("#1C4A5A")
    private val coreColor = Color.parseColor("#132430")

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = dimCyan
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = cyan
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = dimCyan
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = coreColor
    }
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = cyan
    }

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setState(newState: State) {
        state = newState
        arcPaint.color = when (state) {
            State.IDLE -> cyan
            State.LISTENING -> Color.parseColor("#3DFF8B")
            State.THINKING -> Color.parseColor("#FFC93D")
            State.SPEAKING -> Color.parseColor("#3DDCFF")
        }
        animator.duration = when (state) {
            State.IDLE -> 8000
            State.LISTENING -> 2500
            State.THINKING -> 1500
            State.SPEAKING -> 2000
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = minOf(width, height) / 2f - 20f

        val outerR = maxRadius
        val midR = maxRadius * 0.72f
        val coreR = maxRadius * 0.5f

        for (i in 0 until 36) {
            val angle = Math.toRadians((i * 10).toDouble())
            val inner = outerR + 8f
            val outer = outerR + (if (i % 9 == 0) 22f else 14f)
            val x1 = cx + (inner * Math.cos(angle)).toFloat()
            val y1 = cy + (inner * Math.sin(angle)).toFloat()
            val x2 = cx + (outer * Math.cos(angle)).toFloat()
            val y2 = cy + (outer * Math.sin(angle)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, midR, ringPaint)
        canvas.drawCircle(cx, cy, coreR, corePaint)

        val outerRect = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawArc(outerRect, rotation, 50f, false, arcPaint)
        canvas.drawArc(outerRect, rotation + 130f, 35f, false, arcPaint)
        canvas.drawArc(outerRect, rotation + 220f, 60f, false, arcPaint)

        val midRect = RectF(cx - midR, cy - midR, cx + midR, cy + midR)
        canvas.drawArc(midRect, -rotation * 1.4f, 70f, false, arcPaint)

        val plusLen = 18f
        canvas.drawLine(cx - plusLen, cy, cx + plusLen, cy, plusPaint)
        canvas.drawLine(cx, cy - plusLen, cx, cy + plusLen, plusPaint)
    }
}
