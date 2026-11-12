/*
 * Classic desktop-style diagonal volume slider for the MPM video HUD.
 */
package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import android.widget.SeekBar

class MpmVolumeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setTrackColors(activeColor: Int, inactiveColor: Int) {
        activePaint.color = activeColor
        trackPaint.color = inactiveColor
        thumbPaint.color = activeColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 5f * density
        val left = paddingLeft.toFloat() + inset
        val right = width.toFloat() - paddingRight.toFloat() - inset
        val top = paddingTop.toFloat() + inset
        val bottom = height.toFloat() - paddingBottom.toFloat() - inset
        if (right <= left || bottom <= top) return

        val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        val thumbX = left + (right - left) * fraction
        val thumbY = bottom - (bottom - top) * fraction

        canvas.drawLine(left, bottom, right, top, trackPaint)
        canvas.drawLine(left, bottom, thumbX, thumbY, activePaint)
        val half = 3.5f * density
        canvas.drawRect(thumbX - half, thumbY - half, thumbX + half, thumbY + half, thumbPaint)
    }
}
