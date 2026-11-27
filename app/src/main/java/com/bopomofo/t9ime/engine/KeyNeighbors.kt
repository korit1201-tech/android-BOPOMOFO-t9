package com.bopomofo.t9ime.engine

/**
 * 12 宮格的鄰鍵關係與觸控提示（按偏鄰鍵糾錯用）。
 *
 * 鍵位是固定的 4 列 × 3 欄網格（res/layout/keyboard_view.xml 的 key_k1..key_k12，每列 3 鍵）：
 *    1  2  3
 *    4  5  6
 *    7  8  9
 *   10 11 12
 * 11 是聲調鍵，不參與糾錯（不當來源也不當目標）：聲調本來就是軟性偏好。
 */
object KeyNeighbors {
    const val TONE_KEY = 11
    private const val COLUMNS = 3
    private const val ROWS = 4

    /** 落指點離某一邊的距離小於鍵寬／鍵高的這個比例，才視為「靠邊」。 */
    const val EDGE_FRACTION = 0.2f

    /** 手指落下時偏向哪一個鄰鍵；[confidence] 0..1，越靠近該邊越高。 */
    class SlipHint(val toward: Int, val confidence: Float)

    private val neighborTable: Map<Int, List<Int>> = (1..12).associateWith { key ->
        if (key == TONE_KEY) emptyList() else {
            val row = (key - 1) / COLUMNS
            val col = (key - 1) % COLUMNS
            buildList {
                if (col > 0) add(key - 1)
                if (col < COLUMNS - 1) add(key + 1)
                if (row > 0) add(key - COLUMNS)
                if (row < ROWS - 1) add(key + COLUMNS)
            }.filter { it != TONE_KEY }
        }
    }

    /** 上下左右相鄰且可糾錯的鍵；聲調鍵與不存在的鍵回傳空清單。 */
    fun neighbors(keyId: Int): List<Int> = neighborTable[keyId] ?: emptyList()

    /**
     * 由落指點推算偏向哪個鄰鍵。座標是鍵內座標（0..[width]、0..[height]）。
     * 不靠邊、沒有該側鄰鍵（例如最左欄偏左）或鍵本身不可糾錯時回傳 null。
     */
    fun hintFromTouch(keyId: Int, x: Float, y: Float, width: Float, height: Float): SlipHint? {
        if (width <= 0f || height <= 0f) return null
        val near = neighborTable[keyId] ?: return null
        if (near.isEmpty()) return null
        val row = (keyId - 1) / COLUMNS
        val col = (keyId - 1) % COLUMNS
        // 到四邊的相對距離；落在鍵外（座標超出）視為 0，由 coerce 處理
        val dLeft = (x / width).coerceAtLeast(0f)
        val dRight = ((width - x) / width).coerceAtLeast(0f)
        val dTop = (y / height).coerceAtLeast(0f)
        val dBottom = ((height - y) / height).coerceAtLeast(0f)
        val nearest = minOf(dLeft, dRight, dTop, dBottom)
        if (nearest >= EDGE_FRACTION) return null
        val target = when (nearest) {
            dLeft -> if (col > 0) keyId - 1 else null
            dRight -> if (col < COLUMNS - 1) keyId + 1 else null
            dTop -> if (row > 0) keyId - COLUMNS else null
            else -> if (row < ROWS - 1) keyId + COLUMNS else null
        } ?: return null
        if (target == TONE_KEY) return null
        return SlipHint(target, (1f - nearest / EDGE_FRACTION).coerceIn(0f, 1f))
    }
}
