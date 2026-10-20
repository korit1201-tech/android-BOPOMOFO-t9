package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/**
 * 特殊符號盤與語音輸入複合功能按鈕
 * - 點擊：開啟/關閉特殊符號與方向鍵盤
 * - 長按：啟動語音輸入
 * - 外觀：主字顯示「✛」，右下角常駐微型「🎙」角標提示使用者長按可語音輸入
 */
class SymbolDrawerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }

    var showVoiceSubscript: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        contentDescription = "特殊符號盤，長按可啟動語音輸入"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 當處於預設狀態 "✛" 時，在右下角繪製微型 "🎙" 角標提示
        if (showVoiceSubscript && text == "✛") {
            subPaint.color = currentTextColor
            subPaint.alpha = 150 // 半透明與主題色自然融合
            val paddingEnd = 4f * resources.displayMetrics.density
            val paddingBottom = 3f * resources.displayMetrics.density
            val x = width - paddingEnd
            val y = height - paddingBottom
            canvas.drawText("🎙", x, y, subPaint)
        }
    }
}
