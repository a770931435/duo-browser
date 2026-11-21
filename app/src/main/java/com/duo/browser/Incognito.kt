package com.duo.browser

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 無痕分頁：用 WebView 的獨立設定檔（Profile），Cookie、快取、網站儲存空間、登入狀態都和一般分頁分開。
 *
 * WebView 的限制：設定檔在 App 執行期間只要用過，就刪不掉（會一直回報「使用中」）。所以：
 *  - 每次「無痕工作階段」用一個新名字的設定檔；最後一個無痕分頁關掉時，立刻清掉它的 Cookie、網站儲存空間、
 *    定位權限，之後再開無痕分頁換用全新的設定檔（舊的登入不會被帶到新的無痕分頁）。
 *  - 用過的設定檔名字記在 App 設定裡（只有名字，沒有任何網站資料），下次 App 啟動時整個刪除（含快取檔案）。
 * 無痕分頁另外不記歷史紀錄、不存進「下次開啟的分頁」、不存網站圖示、不記網站的電腦版／手機版、不執行使用者腳本。
 * WebView 太舊（不支援多設定檔）時不提供無痕分頁，不會改用一般設定檔假裝無痕。
 */
object Incognito {
    private const val PREFIX = "duo-incognito"
    private const val SP = "duo_incognito"
    private const val KEY = "profiles"

    /** 目前這個無痕工作階段用的設定檔名字；null = 下一個無痕分頁要開新的 */
    private var current: String? = null

    val supported: Boolean
        get() = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Throwable) { false }

    private fun sp() = Session.app.getSharedPreferences(SP, Context.MODE_PRIVATE)
    private fun names(): MutableSet<String> = sp().getStringSet(KEY, null)?.toMutableSet() ?: mutableSetOf()

    /** 新建的 WebView 改用無痕設定檔（要在 WebView 做任何其他事之前呼叫）。 */
    fun attach(w: WebView) {
        val name = current ?: "$PREFIX-${System.currentTimeMillis()}".also { n ->
            current = n
            // 先記名字再建立：App 中途被關掉，下次啟動也找得到、刪得掉
            sp().edit().putStringSet(KEY, names().apply { add(n) }).commit()
        }
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(w, name)
    }

    /**
     * 最後一個無痕分頁關掉了：清掉這個設定檔裡的 Cookie、網站儲存空間、定位權限，
     * 下一個無痕分頁改用新的設定檔。設定檔的檔案在下次啟動時由 [cleanupOnStart] 刪除。
     */
    fun endSession() {
        PdfViewer.wipeIncognito() // 無痕分頁下載的 PDF
        val name = current ?: return
        current = null
        if (!supported) return
        try {
            ProfileStore.getInstance().getProfile(name)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.cookieManager.flush()
                p.webStorage.deleteAllData()
                p.geolocationPermissions.clearAll()
            }
        } catch (e: Exception) {
            android.util.Log.w("DuoIncognito", "clear failed: ${e.javaClass.simpleName}")
        }
        // 能刪就順便刪（大多會因為「使用中」失敗，留到下次啟動）
        try { if (ProfileStore.getInstance().deleteProfile(name)) forget(name) } catch (_: Exception) {}
    }

    /** App 啟動時（還沒有任何 WebView）：刪除上次留下的所有無痕設定檔。 */
    fun cleanupOnStart() {
        PdfViewer.wipeIncognito()
        // 先在 WebView 載入前刪掉殘留的設定檔資料夾：App 被強制關閉時還在用的無痕設定檔，
        // WebView 之後只會取消登記、不一定刪資料夾。這個 App 只有無痕會用到 Default 以外的設定檔
        try {
            listOf(java.io.File(Session.app.dataDir, "app_webview"), java.io.File(Session.app.cacheDir, "WebView")).forEach { root ->
                root.listFiles { f -> f.isDirectory && f.name.startsWith("Profile ") }?.forEach { it.deleteRecursively() }
            }
        } catch (_: Exception) {}
        if (!supported) return
        val store = try { ProfileStore.getInstance() } catch (e: Exception) { return }
        // 舊版用固定名字 duo-incognito，一起刪
        (names() + PREFIX + store.allProfileNames.filter { it.startsWith(PREFIX) }).forEach { n ->
            try { store.deleteProfile(n); forget(n) } catch (e: Exception) {
                android.util.Log.w("DuoIncognito", "startup delete failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun forget(name: String) {
        sp().edit().putStringSet(KEY, names().apply { remove(name) }).apply()
    }
}
