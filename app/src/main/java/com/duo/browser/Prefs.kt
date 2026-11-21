package com.duo.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val title: String, val url: String)

/** 所有使用者設定都存在 SharedPreferences。 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("duo_prefs", Context.MODE_PRIVATE)

    var home: String
        get() = sp.getString("home", DEFAULT_HOME) ?: DEFAULT_HOME
        set(v) = sp.edit().putString("home", v).apply()

    var lastUrl: String?
        get() = sp.getString("last", null)
        set(v) = sp.edit().putString("last", v).apply()

    /** 上下螢幕之間被「藏起來」的虛擬像素高度（模擬實體轉軸的間隙）。 */
    var gapPx: Int
        get() = sp.getInt("gap", 0)
        set(v) = sp.edit().putInt("gap", v).apply()

    /** 介面主題（對應 RP Duo 的五種機身配色，見 Theme.kt 的 Palette）。 */
    var theme: String
        get() = sp.getString("theme", "purple") ?: "purple"
        set(v) = sp.edit().putString("theme", v).apply()

    var textZoom: Int
        get() = sp.getInt("textZoom", 100)
        set(v) = sp.edit().putInt("textZoom", v).apply()

    var cursorSpeed: Float
        get() = sp.getFloat("cursorSpeed", 1f)
        set(v) = sp.edit().putFloat("cursorSpeed", v).apply()

    var scrollSpeed: Float
        get() = sp.getFloat("scrollSpeed", 1f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()

    /** 下屏顯示模式：false = 完整顯示（整個網頁寬度縮進下屏）；true = 放大裁切（和上屏同比例、置中、左右裁掉）。 */
    var bottomCrop: Boolean
        get() = sp.getBoolean("bottomCrop", false)
        set(v) = sp.edit().putBoolean("bottomCrop", v).apply()

    /** 放大裁切的比例（下屏 1 像素 = 上屏幾個像素）；0 = 自動（依面板實際規格）。 */
    var cropScale: Float
        get() = sp.getFloat("cropScale", 0f)
        set(v) = sp.edit().putFloat("cropScale", v).apply()

    /** 上屏左側的直式工具列（寬度 = 上下螢幕左緣的差距，網頁左邊在下屏就不會被裁掉）。 */
    var sideBar: Boolean
        get() = sp.getBoolean("sideBar", false)
        set(v) = sp.edit().putBoolean("sideBar", v).apply()

    /** 內建廣告阻擋。 */
    var adBlock: Boolean
        get() = sp.getBoolean("adBlock", false)
        set(v) = sp.edit().putBoolean("adBlock", v).apply()

    /** 廣告過濾清單上次下載成功的時間。 */
    var adBlockUpdated: Long
        get() = sp.getLong("adBlockUpdated", 0L)
        set(v) = sp.edit().putLong("adBlockUpdated", v).apply()

    /** 不擋廣告的網站（網域，包含子網域）。預設包含 B 站：擋了會跳出「瀏覽器外掛」警告、部分功能出錯。 */
    var adAllowHosts: Set<String>
        get() = sp.getStringSet("adAllowHosts", null)?.toSet() ?: setOf("bilibili.com")
        set(v) = sp.edit().putStringSet("adAllowHosts", HashSet(v)).apply()

    /** 介面語言：auto / en / zh-TW / zh-CN */
    var language: String
        get() = sp.getString("language", "auto") ?: "auto"
        set(v) = sp.edit().putString("language", v).apply()

    /** 第一次使用教學是否看過 */
    var tutorialDone: Boolean
        get() = sp.getBoolean("tutorialDone", false)
        set(v) = sp.edit().putBoolean("tutorialDone", v).apply()

    /** 上次檢查更新的時間與查到的最新版本 */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()

    var latestVersion: String
        get() = sp.getString("latestVersion", "") ?: ""
        set(v) = sp.edit().putString("latestVersion", v).apply()

    /** 左右搖桿功能交換（預設 左=游標、右=捲動）。 */
    var swapSticks: Boolean
        get() = sp.getBoolean("swapSticks", false)
        set(v) = sp.edit().putBoolean("swapSticks", v).apply()

    /** 電腦版網頁：電腦版 User-Agent + 強制版面寬度。 */
    var desktop: Boolean
        get() = sp.getBoolean("desktop", true)
        set(v) = sp.edit().putBoolean("desktop", v).apply()

    /** 每個網站（主網域）最後使用的版本：true = 電腦版、false = 手機版。沒記錄的網站用 [desktop]。 */
    fun siteDesktop(site: String): Boolean? {
        val o = try { JSONObject(sp.getString("siteDesktop", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return if (o.has(site)) o.optBoolean(site) else null
    }

    fun setSiteDesktop(site: String, desktop: Boolean) {
        val o = try { JSONObject(sp.getString("siteDesktop", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(site, desktop)
        sp.edit().putString("siteDesktop", o.toString()).apply()
    }

    /**
     * 出現過 Cloudflare 人機驗證的網站：改用 WebView 原本的身分（不偽裝成 Chrome），
     * 驗證才會過；驗證通過的 Cookie 綁定 UA，所以之後這個網站都固定用原本的身分。
     */
    fun siteCompat(site: String): Boolean = sp.getStringSet("siteCompat", emptySet())!!.contains(site)

    fun addSiteCompat(site: String) {
        val s = HashSet(sp.getStringSet("siteCompat", emptySet())!!)
        if (s.add(site)) sp.edit().putStringSet("siteCompat", s).apply()
    }

    /** 分頁、書籤頁用方塊（true）還是條列（false）顯示。 */
    var tabsGrid: Boolean
        get() = sp.getBoolean("tabsGrid", true)
        set(v) = sp.edit().putBoolean("tabsGrid", v).apply()
    var bookmarksGrid: Boolean
        get() = sp.getBoolean("bookmarksGrid", false)
        set(v) = sp.edit().putBoolean("bookmarksGrid", v).apply()

    var desktopWidth: Int
        get() = sp.getInt("desktopWidth", 1280)
        set(v) = sp.edit().putInt("desktopWidth", v).apply()

    // ---- 分頁（下次開啟時還原） ----
    var tabUrls: List<String>
        get() = try {
            val arr = JSONArray(sp.getString("tabs", "[]"))
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("tabs", JSONArray(v).toString()).apply()

    /** 和 tabUrls 一一對應的網頁標題（重開 App 時，還沒載入的分頁也能顯示名稱）。 */
    var tabTitles: List<String>
        get() = try {
            val arr = JSONArray(sp.getString("tabTitles", "[]"))
            List(arr.length()) { arr.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) = sp.edit().putString("tabTitles", JSONArray(v).toString()).apply()

    var tabIndex: Int
        get() = sp.getInt("tabIndex", 0)
        set(v) = sp.edit().putInt("tabIndex", v).apply()

    // ---- 按鍵對應 ----
    fun keyMap(): MutableMap<Int, PadAction> {
        val m = KeyMap.DEFAULTS.toMutableMap()
        val raw = sp.getString("keymap", null) ?: return m
        try {
            val o = JSONObject(raw)
            o.keys().forEach { k ->
                val a = runCatching { PadAction.valueOf(o.getString(k)) }.getOrNull()
                if (a != null) m[k.toInt()] = a
            }
        } catch (_: Exception) {
        }
        return m
    }

    fun saveKeyMap(m: Map<Int, PadAction>) {
        // 只存和預設不同的按鍵，之後預設值改變時沒有自訂過的按鍵會跟著更新
        val o = JSONObject()
        m.forEach { (k, v) -> if (KeyMap.DEFAULTS[k] ?: PadAction.NONE != v) o.put(k.toString(), v.name) }
        sp.edit().putString("keymap", o.toString()).apply()
    }

    fun resetKeyMap() = sp.edit().remove("keymap").apply()

    fun bookmarks(): MutableList<Bookmark> {
        val raw = sp.getString("bookmarks", null) ?: return DEFAULT_BOOKMARKS.toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Bookmark(o.optString("t"), o.optString("u"))
            }
        } catch (e: Exception) {
            DEFAULT_BOOKMARKS.toMutableList()
        }
    }

    fun saveBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url)) }
        sp.edit().putString("bookmarks", arr.toString()).apply()
    }

    companion object {
        const val DEFAULT_HOME = "https://www.google.com"
        /** 預設書籤依介面語言決定（維基百科用對應語言的版本） */
        val DEFAULT_BOOKMARKS: List<Bookmark>
            get() = listOf(
                Bookmark("Google", "https://www.google.com"),
                Bookmark("YouTube", "https://www.youtube.com"),
                Bookmark(
                    L.t("維基百科", "维基百科", "Wikipedia"),
                    L.t("https://zh.wikipedia.org/zh-tw/", "https://zh.wikipedia.org/zh-cn/", "https://en.wikipedia.org"),
                ),
            )
    }
}
