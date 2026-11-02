package com.pelisld.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Capa transparente que dibuja un puntero de mouse. No recibe toques ni foco. */
class PointerView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var px = 0f
        private set
    var py = 0f
        private set

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4AF37"); style = Paint.Style.STROKE
        strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        // Flecha clásica; la punta queda en (0,0)
        moveTo(0f, 0f); lineTo(0f, 22f * d); lineTo(5.5f * d, 17f * d)
        lineTo(9.5f * d, 26f * d); lineTo(13f * d, 24.5f * d)
        lineTo(9f * d, 16f * d); lineTo(16f * d, 16f * d); close()
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setPos(x: Float, y: Float) { px = x; py = y; invalidate() }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(px, py)
        c.drawPath(arrow, fill)
        c.drawPath(arrow, edge)
        c.restore()
    }
}
