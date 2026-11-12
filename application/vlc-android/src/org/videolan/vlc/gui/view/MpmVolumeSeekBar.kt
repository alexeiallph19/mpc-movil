/*
 * MPC-QT style triangular volume slider, adapted for the MPM VLC HUD.
 */
package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.SeekBar

class MpmVolumeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : SeekBar(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val groovePath = Path()
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private var grooveFillColor = 0xFF202020.toInt()
    private var grooveBorderColor = 0xFF777777.toInt()
    private var handleFillColor = 0xFFE0E0E0.toInt()
    private var handleBorderColor = 0xFF777777.toInt()

    fun setTrackColors(activeColor: Int, inactiveColor: Int, surfaceColor: Int) {
        grooveFillColor = surfaceColor
        grooveBorderColor = inactiveColor
        handleFillColor = if (activeColor == android.graphics.Color.WHITE) 0xFFBDBDBD.toInt() else 0xFFE5E5E5.toInt()
        handleBorderColor = inactiveColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Do not draw the Android SeekBar track or thumb: MPC-QT uses a triangular
        // groove and a small vertical rectangular handle that moves along its base.
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right - left < 12f * density || bottom - top < 12f * density) return

        val halfHandleWidth = 5f * density
        val grooveLeft = left + halfHandleWidth
        val grooveRight = right - halfHandleWidth
        groovePath.reset()
        groovePath.moveTo(grooveLeft, bottom)
        groovePath.lineTo(grooveRight, bottom)
        groovePath.lineTo(grooveRight, top)
        groovePath.close()

        groovePaint.color = grooveFillColor
        groovePaint.strokeColor = grooveBorderColor
        canvas.drawPath(groovePath, groovePaint)

        val fraction = if (max > 0) progress.toFloat() / max.toFloat() else 0f
        val centerX = grooveLeft + (grooveRight - grooveLeft) * fraction
        val handleWidth = 10f * density
        val handleHeight = 20f * density
        val centerY = (top + bottom) / 2f
        val handle = android.graphics.RectF(
            centerX - handleWidth / 2f,
            centerY - handleHeight / 2f,
            centerX + handleWidth / 2f,
            centerY + handleHeight / 2f
        )
        handlePaint.color = handleFillColor
        handleBorderPaint.color = handleBorderColor
        canvas.drawRect(handle, handlePaint)
        canvas.drawRect(handle, handleBorderPaint)
    }
}
