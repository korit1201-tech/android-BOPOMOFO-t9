package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 鍵盤容器層統一觸控分發佈局 (KeyboardTouchLayout)
 * 解決 Android 原生 View 體系下多按鍵獨立處理觸控時「手指不離屏滾動換鍵造成漏鍵」的根本問題。
 * 統一攔截觸控事件，維護多點觸控 Pointer 對照表，並透過坐標 Hit-Test 達成極速連打與滑鍵無縫轉移。
 */
class KeyboardTouchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    data class KeyHitInfo(val button: SwipeKeyButton, val rect: Rect)

    private val cachedKeys = mutableListOf<KeyHitInfo>()
    private val activePointers = mutableMapOf<Int, SwipeKeyButton>()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        rebuildKeyHitMap()
    }

    /**
     * 預先遍歷所有子 View，計算並快取所有 SwipeKeyButton 在本容器坐標系下的 Rect
     */
    private fun rebuildKeyHitMap() {
        cachedKeys.clear()
        collectSwipeKeys(this)
    }

    private fun collectSwipeKeys(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            if (child is SwipeKeyButton) {
                val rect = Rect()
                child.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(child, rect)
                cachedKeys.add(KeyHitInfo(child, rect))
            } else if (child is ViewGroup) {
                collectSwipeKeys(child)
            }
        }
    }

    private fun findKeyAt(x: Float, y: Float): SwipeKeyButton? {
        val ix = x.toInt()
        val iy = y.toInt()
        for (info in cachedKeys) {
            if (info.rect.contains(ix, iy)) {
                return info.button
            }
        }
        return null
    }

    private fun getKeyRect(button: SwipeKeyButton): Rect? {
        return cachedKeys.firstOrNull { it.button === button }?.rect
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 一律回傳 true，整個鍵盤觸控由容器統一生產與派發
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val px = event.getX(idx)
                val py = event.getY(idx)
                val key = findKeyAt(px, py)
                if (key != null) {
                    val rect = getKeyRect(key)
                    val localX = px - (rect?.left ?: 0)
                    val localY = py - (rect?.top ?: 0)
                    key.fingerDown(pid, localX, localY)
                    activePointers[pid] = key
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    val currentKey = activePointers[pid] ?: continue

                    // 關鍵鎖定防護（Argon + 羅盤雙手勢鎖定）：
                    // 若當前按鍵已進入羅盤狀態 (isCompassActive) 或已鎖定 Flick 方向 (activeDirection != null)，
                    // 該手指絕不轉移給相鄰按鍵，確保手勢完整性！
                    if (currentKey.isCompassActive || currentKey.activeDirection != null) {
                        val rect = getKeyRect(currentKey)
                        val localX = px - (rect?.left ?: 0)
                        val localY = py - (rect?.top ?: 0)
                        currentKey.fingerMove(localX, localY)
                        continue
                    }

                    val newKey = findKeyAt(px, py)
                    if (newKey == null) {
                        // 手指滑入按鍵縫隙（margin 死區），維持當前原鍵，不跳鍵、不中斷
                        val rect = getKeyRect(currentKey)
                        val localX = px - (rect?.left ?: 0)
                        val localY = py - (rect?.top ?: 0)
                        currentKey.fingerMove(localX, localY)
                    } else if (newKey === currentKey) {
                        // 仍停留在原按鍵範圍內
                        val rect = getKeyRect(currentKey)
                        val localX = px - (rect?.left ?: 0)
                        val localY = py - (rect?.top ?: 0)
                        currentKey.fingerMove(localX, localY)
                    } else {
                        // 手指平滑滾入相鄰新按鍵！
                        currentKey.fingerCancel()
                        val newRect = getKeyRect(newKey)
                        val localX = px - (newRect?.left ?: 0)
                        val localY = py - (newRect?.top ?: 0)
                        newKey.fingerDown(pid, localX, localY)
                        activePointers[pid] = newKey
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val key = activePointers.remove(pid)
                key?.fingerUp()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val key = activePointers.remove(pid)
                key?.fingerUp()
                // 安全清理任何可能殘留的 pointer
                for ((_, k) in activePointers) {
                    k.fingerCancel()
                }
                activePointers.clear()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                for ((_, k) in activePointers) {
                    k.fingerCancel()
                }
                activePointers.clear()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
