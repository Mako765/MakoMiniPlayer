package com.mako.miniplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var outlineWidth = 0f
    var outlineColor = Color.BLACK

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            val textColor = currentTextColor
            // Crtaj crni outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth * 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            setTextColor(outlineColor)
            super.onDraw(canvas)
            // Crtaj fill u originalnoj boji
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            setTextColor(textColor)
            super.onDraw(canvas)
        } else {
            super.onDraw(canvas)
        }
    }
}