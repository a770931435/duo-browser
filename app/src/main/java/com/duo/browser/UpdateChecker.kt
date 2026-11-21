package com.duo.browser

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 檢查 itch.io 上有沒有新版本（App 不經過 Google Play，沒有自動更新）。
 * 使用 itch.io 的公開查詢：只送出商品名稱，不送出任何使用者資料。
 * 需要用 butler 上傳並加上 --userversion（見 AppInfo.ITCH_TARGET 的說明）。
 */
object UpdateChecker {
    private const val DAY = 24L * 60 * 60 * 1000

    val enabled: Boolean get() = AppInfo.ITCH_TARGET.isNotBlank()

    fun currentVersion(): String = try {
        Session.app.packageManager.getPackageInfo(Session.app.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    val updateAvailable: Boolean
        get() {
            val latest = Session.prefs.latestVersion
            return latest.isNotEmpty() && isNewer(latest, currentVersion())
        }

    /** a 是否比 b 新（逐段比較數字，例如 1.10.0 > 1.9.3） */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * @param force true = 不管上次什麼時候檢查都馬上查（「關於」頁的按鈕）
     * @param onDone 結果：true 有新版、false 已是最新、null 查詢失敗或未啟用
     */
    fun check(force: Boolean = false, onDone: ((Boolean?) -> Unit)? = null) {
        if (!enabled) { onDone?.invoke(null); return }
        val p = Session.prefs
        val now = System.currentTimeMillis()
        if (!force && now - p.lastUpdateCheck < DAY) { onDone?.invoke(updateAvailable); return }
        Thread {
            val result: Boolean? = try {
                val q = "target=" + URLEncoder.encode(AppInfo.ITCH_TARGET, "UTF-8") +
                    "&channel_name=" + URLEncoder.encode(AppInfo.ITCH_CHANNEL, "UTF-8")
                val conn = URL("https://itch.io/api/1/x/wharf/latest?$q").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val latest = JSONObject(text).optString("latest", "")
                p.lastUpdateCheck = now
                if (latest.isNotEmpty()) p.latestVersion = latest
                updateAvailable
            } catch (e: Exception) {
                null
            }
            Session.main.post {
                onDone?.invoke(result)
                if (result == true && !force) Session.ui?.flash(
                    L.t("有新版本 ", "有新版本 ", "New version ") + p.latestVersion +
                        L.t("，請到選單查看", "，请到菜单查看", " available — see the menu")
                )
            }
        }.start()
    }
}
