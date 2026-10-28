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

    /**
     * 落指時的螢幕座標與按鍵內座標。版面在觸控期間可能被推動（例如第一個注音出現時頂端膠囊展開、整個鍵盤下移），
     * 這時事件座標（相對容器）會跟著位移，若直接拿來算滑動距離，手指沒動也會被判成滑走而取消羅盤。
     * 所以滑動量一律用「螢幕上的實際位移」換算。
     */
    private class PointerAnchor(val screenX: Float, val screenY: Float, val localX: Float, val localY: Float)
    private val anchors = mutableMapOf<Int, PointerAnchor>()
    private fun rawX(e: MotionEvent, i: Int) =
        if (android.os.Build.VERSION.SDK_INT >= 29) e.getRawX(i) else e.rawX + (e.getX(i) - e.getX(0))
    private fun rawY(e: MotionEvent, i: Int) =
        if (android.os.Build.VERSION.SDK_INT >= 29) e.getRawY(i) else e.rawY + (e.getY(i) - e.getY(0))

    // 用事件本身的螢幕原始座標：getLocationOnScreen 在視窗剛被推動的瞬間仍是舊位置，抵銷不掉位移
    private fun anchorAt(pid: Int, e: MotionEvent, i: Int, localX: Float, localY: Float) {
        anchors[pid] = PointerAnchor(rawX(e, i), rawY(e, i), localX, localY)
    }

    /** 以落指位置加上螢幕位移，得到手指目前在「落指那顆鍵」內的座標。 */
    private fun movedLocal(pid: Int, e: MotionEvent, i: Int, fallbackX: Float, fallbackY: Float): Pair<Float, Float> {
        val anc = anchors[pid] ?: return fallbackX to fallbackY
        return (anc.localX + (rawX(e, i) - anc.screenX)) to (anc.localY + (rawY(e, i) - anc.screenY))
    }

    private fun keyName(b: SwipeKeyButton) = try { resources.getResourceEntryName(b.id) } catch (_: Exception) { "?" }

    /** 除錯記錄：落指點在按鍵內的位置與離最近邊緣的距離（dp），用來分辨邊緣誤觸。 */
    private fun logTouch(tag: String, key: SwipeKeyButton, rect: Rect?, lx: Float, ly: Float, pressure: Float, size: Float) {
        val d = resources.displayMetrics.density
        val w = rect?.width() ?: 0
        val h = rect?.height() ?: 0
        val edge = minOf(lx, w - lx, ly, h - ly) / d
        android.util.Log.d("BopomofoIME_Touch", "$tag ${keyName(key)} at=%.1f,%.1fdp edge=%.1fdp key=%.0fx%.0fdp p=%.2f s=%.2f".format(lx / d, ly / d, edge, w / d, h / d, pressure, size))
    }

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
                    logTouch("down", key, rect, localX, localY, event.getPressure(idx), event.getSize(idx))
                    anchorAt(pid, event, idx, localX, localY) // 要在 fingerDown 之前：它會觸發輸入並可能讓版面位移
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
                        val (lx, ly) = movedLocal(pid, event, i, px - (rect?.left ?: 0), py - (rect?.top ?: 0))
                        currentKey.fingerMove(lx, ly)
                        continue
                    }

                    val newKey = findKeyAt(px, py)
                    if (newKey == null || newKey === currentKey || !currentKey.allowFastFlick) {
                        // 仍在原按鍵、滑入縫隙，或九鍵主鍵（0ms 落指即已輸入，不可再把抖到鄰鍵算成另一次輸入，
                        // 例如 ㄇ 與 ㄏ 相鄰，點一下變三個鍵）：維持原鍵，位移交給羅盤滑動判斷
                        val rect = getKeyRect(currentKey)
                        val (lx, ly) = movedLocal(pid, event, i, px - (rect?.left ?: 0), py - (rect?.top ?: 0))
                        currentKey.fingerMove(lx, ly)
                    } else {
                        // 手指平滑滾入相鄰新按鍵（僅限在 UP 才結算的按鍵，如空白鍵）
                        currentKey.fingerCancel()
                        val newRect = getKeyRect(newKey)
                        val localX = px - (newRect?.left ?: 0)
                        val localY = py - (newRect?.top ?: 0)
                        anchorAt(pid, event, i, localX, localY)
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
                anchors.remove(pid)
                key?.fingerUp()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val key = activePointers.remove(pid)
                if (key != null) {
                    val (ux, uy) = movedLocal(pid, event, idx, 0f, 0f)
                    val a = anchors[pid]
                    android.util.Log.d("BopomofoIME_Touch", "up ${keyName(key)} held=${event.eventTime - event.downTime}ms move=${if (a != null) "%.1f,%.1fdp".format((ux - a.localX) / resources.displayMetrics.density, (uy - a.localY) / resources.displayMetrics.density) else "?"}")
                }
                anchors.remove(pid)
                key?.fingerUp()
                // 安全清理任何可能殘留的 pointer
                for ((_, k) in activePointers) {
                    k.fingerCancel()
                }
                activePointers.clear()
                anchors.clear()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                for ((_, k) in activePointers) {
                    k.fingerCancel()
                }
                activePointers.clear()
                anchors.clear()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
