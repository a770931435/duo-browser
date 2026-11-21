package com.duo.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File

/**
 * 網站圖示（favicon）快取：分頁卡片用。
 * WebView 載入網頁時給的圖示存在記憶體和 App 私有的 cache 資料夾（cacheDir/favicons/網域.png），
 * 重開 App、還沒載入的分頁也能顯示。只存在這台裝置；「清除網站資料」時一起刪掉。
 */
object Favicons {
    private val mem = object : LruCache<String, Bitmap>(64) {}
    /** 查過磁碟但沒有的網域，不要每次重畫都再讀一次 */
    private val missing = HashSet<String>()

    private fun dir(): File = File(Session.app.cacheDir, "favicons")

    /** 網址 → 快取用的鍵（小寫網域、去掉 www.）；不合法的網域回傳 null。 */
    fun keyOf(url: String?): String? {
        val h = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
        return h.takeIf { it.isNotEmpty() && it.length <= 200 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
    }

    fun get(url: String?): Bitmap? {
        val k = keyOf(url) ?: return null
        mem.get(k)?.let { return it }
        if (k in missing) return null
        val f = File(dir(), "$k.png")
        val b = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        if (b != null) mem.put(k, b) else missing.add(k)
        return b
    }

    fun put(url: String?, icon: Bitmap?) {
        val k = keyOf(url) ?: return
        if (icon == null || icon.width < 8 || icon.height < 8) return
        // 只留夠用的大小（卡片上約 26dp）
        val s = 64
        val b = if (icon.width > s || icon.height > s) Bitmap.createScaledBitmap(icon, s, s, true) else icon.copy(Bitmap.Config.ARGB_8888, false)
        val old = mem.get(k)
        mem.put(k, b)
        missing.remove(k)
        if (old != null && old.sameAs(b)) return
        Thread {
            runCatching {
                val d = dir(); d.mkdirs()
                File(d, "$k.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.start()
    }

    fun clear() {
        mem.evictAll()
        missing.clear()
        Thread { runCatching { dir().deleteRecursively() } }.start()
    }
}
