package com.typeink.prototype

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class AudioWaveView(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.typeink_border)
    }
    private val barRect = RectF()

    private var amplitude = 0f
    private var active = false
    private var error = false
    private var processing = false
    private var phaseOffset = 0f

    private val animator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phaseOffset = (it.animatedValue as Float) * (2f * PI.toFloat())
                invalidate()
            }
        }

    fun setAmplitude(level: Float) {
        amplitude = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setVisualState(
        isActive: Boolean,
        isProcessing: Boolean = false,
        isError: Boolean = false,
    ) {
        active = isActive
        processing = isProcessing
        error = isError
        if (isActive || isProcessing || isError) {
            if (!animator.isStarted) {
                animator.start()
            }
        } else if (animator.isStarted) {
            animator.cancel()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        if (widthF <= 0f || heightF <= 0f) return

        val barCount = 16
        val gap = widthF * 0.012f
        val barWidth = (widthF - gap * (barCount - 1)) / barCount
        val centerY = heightF / 2f
        val minBarHeight = heightF * 0.18f
        val dynamicAmplitude =
            when {
                error -> 0.45f
                processing -> 0.32f
                active -> max(0.18f, amplitude)
                else -> 0.1f
            }

        val startColor =
            when {
                error -> ContextCompat.getColor(context, R.color.typeink_error)
                processing -> ContextCompat.getColor(context, R.color.typeink_accent_start)
                active -> ContextCompat.getColor(context, R.color.typeink_accent_start)
                else -> ContextCompat.getColor(context, R.color.typeink_border)
            }
        val endColor =
            when {
                error -> ContextCompat.getColor(context, R.color.typeink_warning)
                processing -> ContextCompat.getColor(context, R.color.typeink_accent_end)
                active -> ContextCompat.getColor(context, R.color.typeink_accent_end)
                else -> ContextCompat.getColor(context, R.color.typeink_border_soft)
            }

        barPaint.shader = LinearGradient(0f, 0f, widthF, 0f, startColor, endColor, Shader.TileMode.CLAMP)

        for (index in 0 until barCount) {
            val left = index * (barWidth + gap)
            val wave =
                abs(
                    sin(
                        phaseOffset + (index * 0.52f) + if (processing) 0.45f else 0f,
                    ),
                )
            val barHeight = minBarHeight + (heightF * 0.68f * dynamicAmplitude * wave)
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            barRect.set(left, top, left + barWidth, bottom)

            if (active || processing || error) {
                canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
            } else {
                canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, idlePaint)
            }
        }
    }
}
