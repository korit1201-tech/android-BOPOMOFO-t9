package com.bopomofo.t9ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bopomofo.t9ime.R

/**
 * 全注音鍵盤專用按鍵：
 * - 支援主注音大字居中、右上角副標 (英文字母/數字)
 * - 支援長按水平 3 格羅盤 (左大寫英文、右小寫英文、中間原本注音)
 * - 快速點擊 (Tap) 直接輸入注音
 */
class ZhuyinFullCompassKey @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var primaryText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var subText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var leftOption: String = ""
    var rightOption: String = ""

    var onCommitZhuyin: ((Char) -> Unit)? = null
    var onCommitDirectText: ((String) -> Unit)? = null

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }

    private val textBounds = Rect()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var isHoldingCompass = false
    private var isCompassActive = false
    private var currentSelection = Selection.CENTER

    enum class Selection {
        LEFT, CENTER, RIGHT
    }

    companion object {
        private const val HOLD_DELAY_MS = 200L

        // 全局共享的單一 PopupWindow，避免頻繁建立 View
        private var sharedPopup: PopupWindow? = null
        private var sharedPopupView: View? = null
        private var tvLeft: TextView? = null
        private var tvCenter: TextView? = null
        private var tvRight: TextView? = null
    }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.bg_key)
        updatePaintTypefaces()
    }

    fun updatePaintTypefaces() {
        val boldTf = FontCache.getBopomofoBoldTypeface(context) ?: Typeface.defaultFromStyle(Typeface.BOLD)
        mainPaint.typeface = boldTf
        subPaint.typeface = Typeface.DEFAULT_BOLD
        mainPaint.color = ContextCompat.getColor(context, R.color.kb_text_primary)
        subPaint.color = ContextCompat.getColor(context, R.color.kb_text_secondary)
    }

    fun setTextColor(primaryColor: Int, secondaryColor: Int) {
        mainPaint.color = primaryColor
        subPaint.color = secondaryColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density

        // 1. 繪製右上角副文字 (subText, 例如 'q', '1', ':')
        if (subText.isNotEmpty()) {
            subPaint.textSize = (if (w > 120) 12f else 10f) * density
            val subX = w - 4f * density
            val subY = 12f * density
            canvas.drawText(subText, subX, subY, subPaint)
        }

        // 2. 繪製主注音 (primaryText, 例如 'ㄆ')
        if (primaryText.isNotEmpty()) {
            val mainSize = (if (w > 120) 19f else 17f) * density
            mainPaint.textSize = mainSize
            mainPaint.getTextBounds(primaryText, 0, primaryText.length, textBounds)
            // 置中偏左下一點點，避開右上角副標
            val centerX = if (subText.isNotEmpty()) (w * 0.44f) else (w * 0.5f)
            val centerY = (h * 0.5f) + (textBounds.height() * 0.38f)
            canvas.drawText(primaryText, centerX, centerY, mainPaint)
        }
    }

    private val holdRunnable = Runnable {
        if (!isHoldingCompass) return@Runnable
        isCompassActive = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showCompassPopup()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downTime = SystemClock.uptimeMillis()
                isHoldingCompass = true
                isCompassActive = false
                currentSelection = Selection.CENTER
                isPressed = true
                mainHandler.removeCallbacks(holdRunnable)
                mainHandler.postDelayed(holdRunnable, HOLD_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isCompassActive) {
                    val dx = event.x - startX
                    val density = resources.displayMetrics.density
                    val threshold = 16f * density
                    val newSel = when {
                        dx < -threshold && leftOption.isNotEmpty() -> Selection.LEFT
                        dx > threshold && rightOption.isNotEmpty() -> Selection.RIGHT
                        else -> Selection.CENTER
                    }
                    if (newSel != currentSelection) {
                        currentSelection = newSel
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        updatePopupSelectionUI()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(holdRunnable)
                isPressed = false
                if (isCompassActive) {
                    dismissCompassPopup()
                    when (currentSelection) {
                        Selection.LEFT -> {
                            if (leftOption.isNotEmpty()) onCommitDirectText?.invoke(leftOption)
                            else if (primaryText.isNotEmpty()) onCommitZhuyin?.invoke(primaryText[0])
                        }
                        Selection.RIGHT -> {
                            if (rightOption.isNotEmpty()) onCommitDirectText?.invoke(rightOption)
                            else if (primaryText.isNotEmpty()) onCommitZhuyin?.invoke(primaryText[0])
                        }
                        Selection.CENTER -> {
                            if (primaryText.isNotEmpty()) onCommitZhuyin?.invoke(primaryText[0])
                        }
                    }
                } else {
                    // Tap 快速點擊：直接輸出注音
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (primaryText.isNotEmpty()) {
                        onCommitZhuyin?.invoke(primaryText[0])
                    }
                }
                isHoldingCompass = false
                isCompassActive = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(holdRunnable)
                dismissCompassPopup()
                isPressed = false
                isHoldingCompass = false
                isCompassActive = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun showCompassPopup() {
        if (sharedPopup == null) {
            val v = LayoutInflater.from(context).inflate(R.layout.layout_compass_horizontal, null)
            sharedPopupView = v
            tvLeft = v.findViewById(R.id.tv_compass_left)
            tvCenter = v.findViewById(R.id.tv_compass_center)
            tvRight = v.findViewById(R.id.tv_compass_right)

            val density = resources.displayMetrics.density
            val popupWidth = (144 * density).toInt()
            val popupHeight = (48 * density).toInt()
            sharedPopup = PopupWindow(v, popupWidth, popupHeight, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isClippingEnabled = false
                animationStyle = 0
            }
        }

        tvLeft?.text = leftOption
        tvCenter?.text = primaryText
        tvRight?.text = rightOption

        currentSelection = Selection.CENTER
        updatePopupSelectionUI()

        try {
            val popup = sharedPopup ?: return
            if (!popup.isShowing && windowToken != null) {
                val loc = IntArray(2)
                getLocationInWindow(loc)
                val density = resources.displayMetrics.density
                val popupWidth = (144 * density).toInt()
                val popupHeight = (48 * density).toInt()
                val xOff = loc[0] + (width - popupWidth) / 2
                val yOff = loc[1] - popupHeight - (8 * density).toInt()

                popup.showAtLocation(this, Gravity.NO_GRAVITY, xOff, yOff)
            }
        } catch (_: Exception) {}
    }

    private fun updatePopupSelectionUI() {
        val applyItem = { tv: TextView?, isSel: Boolean ->
            if (tv != null) {
                if (isSel) {
                    tv.setBackgroundResource(R.drawable.bg_swipe_item_active)
                    tv.setTextColor(Color.WHITE)
                    tv.scaleX = 1.25f
                    tv.scaleY = 1.25f
                } else {
                    tv.background = null
                    tv.setTextColor(ContextCompat.getColor(context, R.color.kb_text_primary))
                    tv.scaleX = 1.0f
                    tv.scaleY = 1.0f
                }
            }
        }
        applyItem(tvLeft, currentSelection == Selection.LEFT)
        applyItem(tvCenter, currentSelection == Selection.CENTER)
        applyItem(tvRight, currentSelection == Selection.RIGHT)
    }

    private fun dismissCompassPopup() {
        try {
            sharedPopup?.dismiss()
        } catch (_: Exception) {}
    }
}
