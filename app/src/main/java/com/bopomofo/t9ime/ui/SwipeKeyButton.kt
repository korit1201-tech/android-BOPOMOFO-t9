package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.bopomofo.t9ime.R
import kotlin.math.abs

/**
 * 支援高速連打 Tap (點擊) 與長按 4 方向羅盤拖選 (Hold & Drag-to-select) 的自訂按鍵
 */
class SwipeKeyButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatButton(context, attrs, defStyleAttr) {

    init {
        isAllCaps = false
        includeFontPadding = false
        textLocale = java.util.Locale.TRADITIONAL_CHINESE
        try {
            val f = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.bopomofo_font)
            typeface = if (f != null) {
                android.graphics.Typeface.create(f, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
            }
        } catch (_: Exception) {
            typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        }
        paint.isFakeBoldText = true
        isHapticFeedbackEnabled = false
    }

    enum class Direction {
        LEFT, RIGHT, UP, DOWN
    }

    var onTapListener: (() -> Unit)? = null
    var onSwipeListener: ((Direction) -> Unit)? = null
    var onLongClickListenerCustom: (() -> Unit)? = null
    var onDownAction: (() -> Unit)? = null

    /**
     * 是否允許不經長按的「快速輕彈滑動 (Fast Flick)」：
     * - 12 鍵注音主鍵盤設為 false（防止快速連打移往下一鍵時誤觸滑動直出注音）
     * - 空白鍵設為 true（方便快速左右滑動切換中英模式）
     */
    var allowFastFlick: Boolean = false

    var swipeLabelsProvider: (() -> Map<Direction, String>)? = null

    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var isCompassActive = false
    private var activeDirection: Direction? = null

    companion object {
        private const val SWIPE_THRESHOLD_DP = 28f
        private const val COMPASS_HOLD_DELAY_MS = 200L
    }

    private val swipeThresholdPx: Float
        get() = SWIPE_THRESHOLD_DP * resources.displayMetrics.density

    private var previewPopup: PopupWindow? = null
    private var popupView: View? = null
    private var tvUp: TextView? = null
    private var tvDown: TextView? = null
    private var tvLeft: TextView? = null
    private var tvRight: TextView? = null

    private val showPopupRunnable = Runnable {
        if (isPressed) {
            isCompassActive = true
            showPreviewPopup()
        }
    }

    private fun initPopupView() {
        if (previewPopup != null) return
        try {
            val view = LayoutInflater.from(context).inflate(R.layout.layout_swipe_preview, null)
            popupView = view
            tvUp = view.findViewById(R.id.tv_swipe_up)
            tvDown = view.findViewById(R.id.tv_swipe_down)
            tvLeft = view.findViewById(R.id.tv_swipe_left)
            tvRight = view.findViewById(R.id.tv_swipe_right)

            val density = resources.displayMetrics.density
            val sizePx = (132 * density).toInt()
            val bopomofoTypeface = try {
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.bopomofo_font)
            } catch (_: Exception) {
                null
            }
            val boldTf = if (bopomofoTypeface != null) {
                android.graphics.Typeface.create(bopomofoTypeface, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
            }
            listOf(tvUp, tvDown, tvLeft, tvRight).forEach { tv ->
                tv?.typeface = boldTf
                tv?.paint?.isFakeBoldText = true
            }

            previewPopup = PopupWindow(view, sizePx, sizePx, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isClippingEnabled = false
                animationStyle = 0
            }
        } catch (_: Exception) {
        }
    }

    private fun showPreviewPopup() {
        val labels = swipeLabelsProvider?.invoke() ?: return
        if (labels.isEmpty()) return

        initPopupView()
        val popup = previewPopup ?: return
        val view = popupView ?: return

        tvUp?.text = labels[Direction.UP] ?: ""
        tvDown?.text = labels[Direction.DOWN] ?: ""
        tvLeft?.text = labels[Direction.LEFT] ?: ""
        tvRight?.text = labels[Direction.RIGHT] ?: ""

        resetPopupItemStates()

        try {
            if (!popup.isShowing && windowToken != null) {
                val loc = IntArray(2)
                getLocationInWindow(loc)
                val density = resources.displayMetrics.density
                val popupSize = (132 * density).toInt()
                val xOff = loc[0] + (width - popupSize) / 2
                val yOff = loc[1] - popupSize - (10 * density).toInt()

                view.scaleX = 0.85f
                view.scaleY = 0.85f
                view.alpha = 0f
                popup.showAtLocation(this, Gravity.NO_GRAVITY, xOff, yOff)
                view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(90).start()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateActiveDirection(newDir: Direction?) {
        if (activeDirection == newDir) return
        activeDirection = newDir

        if (newDir != null && isCompassActive) {
            try {
                performHapticFeedback(
                    android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                )
            } catch (_: Exception) {
            }
        }

        val applyState = { tv: TextView?, isTarget: Boolean ->
            if (tv == null || tv.text.isNullOrEmpty()) {
                tv?.visibility = View.INVISIBLE
            } else {
                tv.visibility = View.VISIBLE
                if (isTarget) {
                    tv.setBackgroundResource(R.drawable.bg_swipe_item_active)
                    tv.setTextColor(Color.WHITE)
                    tv.animate().scaleX(1.35f).scaleY(1.35f).alpha(1.0f).setDuration(80).start()
                } else {
                    tv.background = null
                    val normalColor = androidx.core.content.ContextCompat.getColor(context, R.color.kb_text_primary)
                    tv.setTextColor(normalColor)
                    tv.animate().scaleX(1.0f).scaleY(1.0f).alpha(if (newDir == null) 1.0f else 0.4f).setDuration(80).start()
                }
            }
        }

        applyState(tvUp, newDir == Direction.UP)
        applyState(tvDown, newDir == Direction.DOWN)
        applyState(tvLeft, newDir == Direction.LEFT)
        applyState(tvRight, newDir == Direction.RIGHT)
    }

    private fun resetPopupItemStates() {
        val normalColor = androidx.core.content.ContextCompat.getColor(context, R.color.kb_text_primary)
        val reset = { tv: TextView? ->
            if (tv != null) {
                tv.background = null
                tv.setTextColor(normalColor)
                tv.scaleX = 1.0f
                tv.scaleY = 1.0f
                tv.alpha = 1.0f
            }
        }
        reset(tvUp)
        reset(tvDown)
        reset(tvLeft)
        reset(tvRight)
    }

    private fun dismissPreviewPopup() {
        removeCallbacks(showPopupRunnable)
        val popup = previewPopup
        if (popup != null && popup.isShowing) {
            try {
                popup.dismiss()
            } catch (_: Exception) {
            }
        }
        isCompassActive = false
        activeDirection = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downTime = SystemClock.uptimeMillis()
                isCompassActive = false
                activeDirection = null
                isPressed = true

                onDownAction?.invoke()

                if (!allowFastFlick) {
                    // 12 鍵主鍵盤 (T9 Mode)：0ms 接觸即刻送出 Tap 結算，時序由物理接觸螢幕順序嚴格鎖定！
                    performClick()
                    onTapListener?.invoke()
                }

                removeCallbacks(showPopupRunnable)
                postDelayed(showPopupRunnable, COMPASS_HOLD_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                startX = event.getX(index)
                startY = event.getY(index)
                downTime = SystemClock.uptimeMillis()
                isCompassActive = false
                activeDirection = null
                isPressed = true

                onDownAction?.invoke()

                if (!allowFastFlick) {
                    // 同鍵多點觸控/雙指極速連打：第二指落在同鍵瞬間，0ms 立即觸發新一擊，絕不吞鍵！
                    performClick()
                    onTapListener?.invoke()
                }

                removeCallbacks(showPopupRunnable)
                postDelayed(showPopupRunnable, COMPASS_HOLD_DELAY_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val holdElapsed = SystemClock.uptimeMillis() - downTime
                if (holdElapsed >= COMPASS_HOLD_DELAY_MS && !isCompassActive) {
                    isCompassActive = true
                    showPreviewPopup()
                }

                if (isCompassActive || allowFastFlick) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val threshold = swipeThresholdPx

                    val isDominantX = absDx > absDy * 1.25f
                    val isDominantY = absDy > absDx * 1.25f
                    if ((absDx > threshold || absDy > threshold) && (isDominantX || isDominantY)) {
                        val dir = if (isDominantX) {
                            if (dx > 0) Direction.RIGHT else Direction.LEFT
                        } else {
                            if (dy > 0) Direction.DOWN else Direction.UP
                        }
                        updateActiveDirection(dir)
                    } else {
                        updateActiveDirection(null)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(showPopupRunnable)
                isPressed = false

                val selectedDir = activeDirection
                val wasCompass = isCompassActive
                dismissPreviewPopup()

                // 若羅盤已展開且選中方向，或為允許快速輕彈滑動的按鈕（如空白鍵）且已劃出方向，則觸發 Swipe
                if (selectedDir != null && (wasCompass || allowFastFlick)) {
                    onSwipeListener?.invoke(selectedDir)
                } else if (allowFastFlick) {
                    // 僅允許快速滑動的按鈕（如空白鍵）在 UP 結算 Tap
                    performClick()
                    onTapListener?.invoke()
                }
                // 注：若 allowFastFlick == false，Tap 已在 ACTION_DOWN / ACTION_POINTER_DOWN 即刻完成，UP 無需重複觸發
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                dismissPreviewPopup()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissPreviewPopup()
        previewPopup = null
        popupView = null
    }
}
