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
        typeface = FontCache.getBopomofoBoldTypeface(context) ?: android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
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
    var isCompassActive: Boolean = false
        internal set
    var activeDirection: Direction? = null
        internal set
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTapUptimeMs = 0L
    private var isHoldingForCompass = false

    companion object {
        private const val COMPASS_HOLD_DELAY_MS = 350L      // 由 200ms 拉長至 350ms，避免快打猶豫誤觸
        private const val RAPID_TAP_COOLDOWN_MS = 150L      // 連打抑制：距上次擊鍵 < 150ms 不啟動羅盤
        private const val STILL_RADIUS_DP = 6f              // 手指靜止半徑：移動超過 6dp 視為滑動，立即取消羅盤預備
        private const val SWIPE_THRESHOLD_RATIO = 0.35f     // 滑動判定門檻 = 按鍵寬度的 35%
        private const val MIN_SWIPE_THRESHOLD_DP = 22f      // 最小滑動像素防護
    }

    private val swipeThresholdPx: Float
        get() {
            val minPx = MIN_SWIPE_THRESHOLD_DP * resources.displayMetrics.density
            if (allowFastFlick) return minPx  // 空白鍵維持固定小門檻，不隨按鍵寬度放大
            val ratioPx = width * SWIPE_THRESHOLD_RATIO
            return if (ratioPx > minPx) ratioPx else minPx
        }

    private var previewPopup: PopupWindow? = null
    private var popupView: View? = null
    private var tvUp: TextView? = null
    private var tvDown: TextView? = null
    private var tvLeft: TextView? = null
    private var tvRight: TextView? = null

    private val showPopupRunnable = Runnable {
        if (isPressed && isHoldingForCompass) {
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
            val boldTf = FontCache.getBopomofoBoldTypeface(context) ?: android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
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
        isHoldingForCompass = false
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

    /**
     * 手指落下或平滑滾入此按鍵（由 KeyboardTouchLayout 容器或自身 onTouchEvent 呼叫）
     */
    fun fingerDown(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        startX = x
        startY = y
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
        // 連打抑制判定：若距離上次點擊小於 150ms，視為快速連打，不啟動羅盤計時
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUptimeMs >= RAPID_TAP_COOLDOWN_MS) {
            isHoldingForCompass = true
            postDelayed(showPopupRunnable, COMPASS_HOLD_DELAY_MS)
        } else {
            isHoldingForCompass = false
        }
        lastTapUptimeMs = now
    }

    /**
     * 手指在此按鍵內移動（由 KeyboardTouchLayout 容器或自身 onTouchEvent 呼叫）
     */
    fun fingerMove(x: Float, y: Float) {
        val dx = x - startX
        val dy = y - startY
        val distSq = dx * dx + dy * dy
        val stillRadiusPx = STILL_RADIUS_DP * resources.displayMetrics.density

        // 若手指移動距離已超過 6dp 且羅盤尚未展開，表示手指在滑移滾動而非長按，立即取消羅盤預備
        if (isHoldingForCompass && !isCompassActive && distSq > stillRadiusPx * stillRadiusPx) {
            removeCallbacks(showPopupRunnable)
            isHoldingForCompass = false
        }

        // 檢查是否展開羅盤或劃出方向
        if (isCompassActive || allowFastFlick) {
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
    }

    /**
     * 手指抬起（由 KeyboardTouchLayout 容器或自身 onTouchEvent 呼叫）
     */
    fun fingerUp() {
        removeCallbacks(showPopupRunnable)
        isHoldingForCompass = false
        isPressed = false
        activePointerId = MotionEvent.INVALID_POINTER_ID

        val selectedDir = activeDirection
        val wasCompass = isCompassActive
        dismissPreviewPopup()

        // 核心防護：只有在明確劃出方向 (selectedDir != null) 時才調用 onSwipeListener（回滾退格並上屏注音）
        // 若使用者只是停留猶豫後原地抬起 (selectedDir == null)，維持原本 0ms DOWN 的 tap，絕不觸發退格！
        if (selectedDir != null && (wasCompass || allowFastFlick)) {
            onSwipeListener?.invoke(selectedDir)
        } else if (allowFastFlick) {
            // 僅允許快速滑動的按鈕（如空白鍵）在 UP 結算 Tap
            performClick()
            onTapListener?.invoke()
        }
    }

    /**
     * 手指滾出此按鍵或觸控取消（由 KeyboardTouchLayout 容器或自身 onTouchEvent 呼叫）
     */
    fun fingerCancel() {
        removeCallbacks(showPopupRunnable)
        isHoldingForCompass = false
        isPressed = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dismissPreviewPopup()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown(event.getPointerId(0), event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!allowFastFlick) {
                    performClick()
                    onTapListener?.invoke()
                }
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    fingerDown(event.getPointerId(index), event.getX(index), event.getY(index))
                } else {
                    removeCallbacks(showPopupRunnable)
                    isHoldingForCompass = false
                    lastTapUptimeMs = SystemClock.uptimeMillis()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIdx = if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    event.findPointerIndex(activePointerId)
                } else 0
                if (pointerIdx < 0 || pointerIdx >= event.pointerCount) return true
                fingerMove(event.getX(pointerIdx), event.getY(pointerIdx))
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (pointerId == activePointerId) {
                    removeCallbacks(showPopupRunnable)
                    isHoldingForCompass = false
                    val remainingIndex = if (index == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(remainingIndex)
                        startX = event.getX(remainingIndex)
                        startY = event.getY(remainingIndex)
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                fingerUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                fingerCancel()
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
