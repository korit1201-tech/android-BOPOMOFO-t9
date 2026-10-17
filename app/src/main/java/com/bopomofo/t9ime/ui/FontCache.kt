package com.bopomofo.t9ime.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.bopomofo.t9ime.R

/**
 * 全域字型快取單例 (FontCache Singleton)
 * 徹底解決全專案在打字、遍歷 View、羅盤預覽時重複呼叫 ResourcesCompat.getFont 造成的 Native 記憶體膨脹與 GC 卡頓
 */
object FontCache {
    @Volatile
    private var bopomofoTypeface: Typeface? = null

    @Volatile
    private var bopomofoBoldTypeface: Typeface? = null

    /**
     * 取得快取的注音字型 (常態 Regular)
     */
    fun getBopomofoTypeface(context: Context): Typeface? {
        val cached = bopomofoTypeface
        if (cached != null) return cached

        return synchronized(this) {
            bopomofoTypeface ?: run {
                val tf = try {
                    ResourcesCompat.getFont(context.applicationContext, R.font.bopomofo_font)
                } catch (_: Exception) {
                    null
                }
                bopomofoTypeface = tf
                tf
            }
        }
    }

    /**
     * 取得快取的注音粗體字型 (Bold)
     */
    fun getBopomofoBoldTypeface(context: Context): Typeface? {
        val cached = bopomofoBoldTypeface
        if (cached != null) return cached

        return synchronized(this) {
            bopomofoBoldTypeface ?: run {
                val base = getBopomofoTypeface(context)
                val boldTf = if (base != null) {
                    try {
                        Typeface.create(base, Typeface.BOLD)
                    } catch (_: Exception) {
                        base
                    }
                } else null
                bopomofoBoldTypeface = boldTf
                boldTf
            }
        }
    }

    /**
     * 預先在 Application 或 Service 啟動時熱身載入
     */
    fun warmup(context: Context) {
        getBopomofoTypeface(context)
        getBopomofoBoldTypeface(context)
    }
}
