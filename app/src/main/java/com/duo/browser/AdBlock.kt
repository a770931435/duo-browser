package com.duo.browser

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 內建廣告阻擋（讀取 uBlock Origin / Adblock Plus 用的公開過濾清單）。
 *
 * - 網路規則：每個請求送出前（shouldInterceptRequest）比對，符合就回傳空內容，不會連到廣告伺服器。
 *   支援最常見的語法：||網域^、| 開頭結尾、* 與 ^、/正規表示式/、@@ 例外、
 *   $third-party、$domain=。其他進階語法（$redirect、$removeparam、+js() 等）略過。
 * - 元素隱藏：##選擇器 轉成 CSS（display:none）在網頁開頭注入，蓋掉空出來的廣告框；
 *   支援 網域##、#@# 例外、$elemhide / $generichide。
 * - 主框架（網頁本身）永遠不擋；白名單網站整個不擋。
 * - 清單存在 files/adblock/，每 7 天在背景更新一次。
 */
object AdBlock {
    data class FilterList(val id: String, val name: String, val url: String)

    val LISTS = listOf(
        FilterList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt"),
        FilterList("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterList("easylistchina", "EasyList China", "https://easylist-downloads.adblockplus.org/easylistchina.txt"),
    )

    private const val UPDATE_EVERY = 7L * 24 * 60 * 60 * 1000
    private val main = Handler(Looper.getMainLooper())
    private val dir: File get() = File(Session.app.filesDir, "adblock").apply { mkdirs() }
    private fun file(l: FilterList) = File(dir, "${l.id}.txt")

    @Volatile private var engine: Engine? = null
    private val busy = AtomicBoolean(false)

    val ruleCount: Int get() = engine?.ruleCount ?: 0
    val ready: Boolean get() = engine != null
    val updating: Boolean get() = busy.get()

    // ---------------- 載入 / 更新 ----------------

    /** App 啟動時呼叫：在背景讀取已下載的清單；沒有或太舊就下載。 */
    fun init() {
        if (!Session.prefs.adBlock) return
        val missing = LISTS.any { !file(it).exists() }
        val old = System.currentTimeMillis() - Session.prefs.adBlockUpdated > UPDATE_EVERY
        if (missing || old) update(force = false, onDone = null) else load()
    }

    private fun load() {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try { build() } finally { busy.set(false) }
        }.start()
    }

    private fun build() {
        val e = Engine()
        LISTS.forEach { l -> file(l).takeIf { it.exists() }?.forEachLine { e.add(it) } }
        e.finish()
        engine = e
        android.util.Log.i("DuoAdBlock", "loaded ${e.ruleCount} rules")
    }

    /**
     * 下載所有清單後重新載入。onDone 在主執行緒回報：null = 成功，否則是錯誤訊息。
     * force = false 時下載失敗會繼續用舊清單。
     */
    fun update(force: Boolean, onDone: ((String?) -> Unit)?) {
        if (!busy.compareAndSet(false, true)) { onDone?.invoke(null); return }
        Thread {
            var err: String? = null
            var ok = 0
            try {
                LISTS.forEach { l ->
                    try {
                        val c = URL(l.url).openConnection() as HttpURLConnection
                        c.connectTimeout = 15000; c.readTimeout = 30000
                        val code = c.responseCode
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")
                        val tmp = File(dir, "${l.id}.tmp")
                        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        c.disconnect()
                        if (tmp.length() < 1000) { tmp.delete(); throw IllegalStateException("empty") }
                        file(l).delete(); tmp.renameTo(file(l))
                        ok++
                    } catch (e: Exception) {
                        err = "${l.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (ok > 0) Session.prefs.adBlockUpdated = System.currentTimeMillis()
                build()
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            } finally {
                busy.set(false)
            }
            main.post { onDone?.invoke(if (ok == LISTS.size) null else err) }
        }.start()
    }

    fun setEnabled(on: Boolean) {
        Session.prefs.adBlock = on
        if (on && engine == null) init()
    }

    // ---------------- 白名單 ----------------

    fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val set = Session.prefs.adAllowHosts
        var h: String = host
        while (true) {
            if (h in set) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun setAllowed(host: String, allow: Boolean) {
        val set = Session.prefs.adAllowHosts.toMutableSet()
        if (allow) set.add(host) else set.remove(host)
        Session.prefs.adAllowHosts = set
    }

    // ---------------- 網路請求 ----------------

    private val EMPTY = ByteArray(0)
    /** 測試版才記錄擋掉的網址（除錯用）。 */
    private val debug: Boolean by lazy {
        (Session.app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * WebViewClient.shouldInterceptRequest 呼叫（背景執行緒）。
     * pageUrl = 這個分頁目前的網頁網址（判斷第三方、$domain= 與白名單用）。
     * 回傳 null = 不擋；否則回傳空內容。
     */
    fun intercept(request: WebResourceRequest, pageUrl: String?, onBlocked: () -> Unit): WebResourceResponse? {
        if (request.isForMainFrame || !Session.prefs.adBlock) return null
        val e = engine ?: return null
        val url = request.url
        val scheme = url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = url.host?.lowercase() ?: return null
        // 人機驗證用到的資源一律放行（擋到就驗證不過）
        if (Browser.isCaptchaHost(host, url.path)) return null
        val pageHost = pageUrl?.let { Uri.parse(it).host?.lowercase() }
        if (isAllowed(pageHost)) return null
        val third = pageHost != null && baseDomain(pageHost) != baseDomain(host)
        if (!e.blocks(url.toString().lowercase(), host, pageHost ?: "", third)) return null
        onBlocked()
        if (debug) android.util.Log.e("DuoAdBlock", "blocked $url  (page $pageHost)")
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", null, ByteArrayInputStream(EMPTY))
    }

    /** 粗略的「主網域」：a.b.example.com → example.com；a.example.com.cn → example.com.cn。 */
    fun baseDomain(host: String): String {
        // IP 位址不能取後兩段（10.0.1.10 和 192.168.1.10 會被當成同一個網站）
        if (host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return host
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val sld = parts[parts.size - 2]
        val n = if (sld.length <= 3 && sld in setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go")) 3 else 2
        return parts.takeLast(n.coerceAtMost(parts.size)).joinToString(".")
    }

    // ---------------- 元素隱藏 ----------------

    /** 給網頁的 document-start 腳本用：取得這個網站要注入的 CSS。 */
    class Bridge {
        @JavascriptInterface
        fun css(host: String?): String {
            if (!Session.prefs.adBlock) return ""
            val h = host?.lowercase() ?: return ""
            if (isAllowed(h)) return ""
            return engine?.css(h) ?: ""
        }
    }

    /** 每個網頁一開始執行：向原生拿 CSS，插進 <head>（只在主框架）。 */
    const val INJECT_JS = """
(function(){
  if (window.top !== window || window.__duoAd) return; window.__duoAd = true;
  var css = ''; try { css = DuoAd.css(location.hostname); } catch (e) {}
  if (!css) return;
  var st = document.createElement('style'); st.id = 'duo-adblock'; st.textContent = css;
  var add = function(){ var r = document.head || document.documentElement; if (r) r.appendChild(st); else setTimeout(add, 10); };
  add();
})();
"""

    // ---------------- 規則引擎 ----------------

    private class Rule(
        val source: String,            // ABP 樣式（小寫），null 表示只比對網域
        val isRegex: Boolean,
        val hostOnly: Boolean,         // ||host^ 純網域規則
        val thirdParty: Boolean?,
        val include: List<String>?,    // $domain= 包含
        val exclude: List<String>?,    // $domain=~ 排除
    ) {
        @Volatile private var re: Regex? = null
        @Volatile private var bad = false

        fun matches(url: String, pageHost: String, third: Boolean): Boolean {
            if (thirdParty != null && thirdParty != third) return false
            if (include != null && include.none { domainMatch(pageHost, it) }) return false
            if (exclude != null && exclude.any { domainMatch(pageHost, it) }) return false
            if (hostOnly) return true
            if (bad) return false
            val r = re ?: try {
                Regex(if (isRegex) source else toRegex(source)).also { re = it }
            } catch (e: Exception) { bad = true; return false }
            return r.containsMatchIn(url)
        }
    }

    private fun domainMatch(host: String, d: String) = host == d || host.endsWith(".$d")

    /** ABP 樣式 → 正規表示式。 */
    private fun toRegex(p: String): String {
        val sb = StringBuilder()
        var s = p
        var i = 0
        if (s.startsWith("||")) { sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?"); i = 2 }
        else if (s.startsWith("|")) { sb.append('^'); i = 1 }
        var endAnchor = false
        if (s.endsWith("|") && s.length > i) { endAnchor = true; s = s.dropLast(1) }
        while (i < s.length) {
            when (val c = s[i]) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                else -> { if ("\\.+?()[]{}|$".indexOf(c) >= 0) sb.append('\\'); sb.append(c) }
            }
            i++
        }
        if (endAnchor) sb.append('$')
        return sb.toString()
    }

    private class Engine {
        // 網路規則：純網域規則用網域查；其他用網址裡的「關鍵字」索引，沒有關鍵字的放 generic
        private val blockHost = HashMap<String, MutableList<Rule>>()
        private val blockToken = HashMap<String, MutableList<Rule>>()
        private val blockGeneric = ArrayList<Rule>()
        private val allowHost = HashMap<String, MutableList<Rule>>()
        private val allowToken = HashMap<String, MutableList<Rule>>()
        private val allowGeneric = ArrayList<Rule>()

        // 元素隱藏
        private val genericSel = LinkedHashSet<String>()
        private val genericExcept = HashSet<String>()
        private val domainSel = HashMap<String, MutableSet<String>>()
        private val domainExcept = HashMap<String, MutableSet<String>>()
        private val noGeneric = HashSet<String>()   // $generichide
        private val noCosmetic = HashSet<String>()  // $elemhide
        private var genericCss: String = ""
        private val cssCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 64
        })

        var ruleCount = 0; private set

        fun add(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            val ci = cosmeticIndex(line)
            if (ci == SKIP) return
            if (ci >= 0) { addCosmetic(line, ci); return }
            addNetwork(line)
        }

        /** ## / #@# 的位置；-1 = 網路規則；SKIP = 不支援的延伸語法（#?# #$# #%# 等）。 */
        private fun cosmeticIndex(line: String): Int {
            val i = line.indexOf('#')
            if (i < 0) return -1
            if (line.startsWith("##", i) || line.startsWith("#@#", i)) return i
            if (line.startsWith("#?#", i) || line.startsWith("#$#", i) || line.startsWith("#@?#", i) ||
                line.startsWith("#@$#", i) || line.startsWith("#%#", i)) return SKIP
            return -1
        }

        private fun addCosmetic(line: String, i: Int) {
            val except = line.startsWith("#@#", i)
            val sel = line.substring(i + if (except) 3 else 2).trim()
            if (sel.isEmpty() || !supported(sel)) return
            val domains = line.substring(0, i).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val inc = domains.filter { !it.startsWith("~") }
            if (inc.isEmpty()) {
                // 只有 ~排除 的規則當成通用規則處理（略過排除，少數情況）
                if (domains.isNotEmpty() && domains.all { it.startsWith("~") }) return
                if (except) genericExcept.add(sel) else genericSel.add(sel)
            } else {
                val map = if (except) domainExcept else domainSel
                inc.forEach { d -> map.getOrPut(d) { HashSet() }.add(sel) }
            }
            ruleCount++
        }

        /** 瀏覽器原生 CSS 不支援的延伸語法（uBlock / ABP 專用）就略過。 */
        private fun supported(sel: String): Boolean {
            if (sel.startsWith("+js(") || sel.contains(":-abp-") || sel.contains(":has-text(") ||
                sel.contains(":contains(") || sel.contains(":matches-css") || sel.contains(":xpath(") ||
                sel.contains(":upward(") || sel.contains(":remove(") || sel.contains(":style(") ||
                sel.contains(":min-text-length(") || sel.contains(":watch-attr(") || sel.contains(":matches-path(") ||
                sel.contains(":matches-attr(") || sel.contains(":matches-prop(") || sel.contains(":others(") ||
                sel.contains(":remove-attr(") || sel.contains(":remove-class(") || sel.startsWith("^")) return false
            return !sel.contains('{') && !sel.contains('}')
        }

        private fun addNetwork(line0: String) {
            var line = line0
            val allow = line.startsWith("@@")
            if (allow) line = line.substring(2)
            var opts: List<String> = emptyList()
            val di = optionIndex(line)
            if (di >= 0) {
                opts = line.substring(di + 1).split(',').map { it.trim().lowercase() }
                line = line.substring(0, di)
            }
            var third: Boolean? = null
            var include: MutableList<String>? = null
            var exclude: MutableList<String>? = null
            var elemhide = false
            var generichide = false
            for (o in opts) {
                when {
                    o == "third-party" || o == "3p" -> third = true
                    o == "~third-party" || o == "first-party" || o == "1p" -> third = false
                    o.startsWith("domain=") || o.startsWith("from=") -> o.substringAfter('=').split('|').forEach { d ->
                        if (d.startsWith("~")) (exclude ?: mutableListOf<String>().also { exclude = it }).add(d.substring(1))
                        else if (d.isNotEmpty()) (include ?: mutableListOf<String>().also { include = it }).add(d)
                    }
                    o == "elemhide" || o == "ehide" -> elemhide = true
                    o == "generichide" || o == "ghide" -> generichide = true
                    o == "match-case" || o == "important" || o.startsWith("~") ||
                        o in setOf("script", "image", "stylesheet", "css", "xmlhttprequest", "xhr", "subdocument", "frame",
                            "media", "font", "object", "other", "ping", "websocket", "beacon", "all") -> {}
                    else -> return // 不支援的選項（redirect、removeparam、csp、popup、document、badfilter…）整條略過
                }
            }
            val pattern = line.lowercase()
            if (allow && (elemhide || generichide)) {
                // @@||example.com^$elemhide：這個網站不做元素隱藏
                val h = Regex("^\\|\\|([a-z0-9.-]+)\\^?$").find(pattern)?.groupValues?.get(1)
                if (h != null) (if (elemhide) noCosmetic else noGeneric).add(h)
                if (opts.all { it in setOf("elemhide", "ehide", "generichide", "ghide") }) { ruleCount++; return }
            }
            if (pattern.isEmpty() || pattern == "*" || pattern == "|" || pattern == "||") return
            val isRegex = pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")
            val hostOnly = !isRegex && Regex("^\\|\\|[a-z0-9.-]+\\^?$").matches(pattern)
            val rule = Rule(if (isRegex) pattern.substring(1, pattern.length - 1) else pattern, isRegex, hostOnly, third, include, exclude)
            if (hostOnly) {
                val h = pattern.removePrefix("||").removeSuffix("^")
                (if (allow) allowHost else blockHost).getOrPut(h) { ArrayList(1) }.add(rule)
            } else {
                val tok = if (isRegex) null else bestToken(pattern)
                if (tok == null) (if (allow) allowGeneric else blockGeneric).add(rule)
                else (if (allow) allowToken else blockToken).getOrPut(tok) { ArrayList(1) }.add(rule)
            }
            ruleCount++
        }

        /** $ 選項的位置（排除正規表示式裡的 $）。 */
        private fun optionIndex(line: String): Int {
            if (line.startsWith("/")) {
                val end = line.lastIndexOf('/')
                val d = line.indexOf('$', end.coerceAtLeast(1))
                return if (end > 0 && d > end) d else -1
            }
            return line.lastIndexOf('$')
        }

        /**
         * 樣式中最長的關鍵字（前後不是 * 的英數字串，至少 3 個字）。網址裡必須有完全相同的關鍵字才會比對這條規則
         * （例如 /banner 不會比對到 /banners）——少擋極少數情況，換來每個請求只需檢查少量規則。
         */
        private fun bestToken(p: String): String? {
            var best: String? = null
            for (m in TOKEN.findAll(p)) {
                val s = m.range.first; val e = m.range.last
                if (s > 0 && p[s - 1] == '*') continue
                if (e < p.length - 1 && p[e + 1] == '*') continue
                val t = m.value
                if (t.length >= 3 && (best == null || t.length > best.length) && t !in COMMON) best = t
            }
            return best
        }

        fun finish() {
            genericSel.removeAll(genericExcept)
            genericCss = cssOf(genericSel)
        }

        fun blocks(url: String, host: String, pageHost: String, third: Boolean): Boolean {
            val tokens = TOKEN.findAll(url).map { it.value }.toHashSet()
            fun hit(hostMap: Map<String, List<Rule>>, tokMap: Map<String, List<Rule>>, generic: List<Rule>): Boolean {
                var h = host
                while (true) {
                    hostMap[h]?.let { l -> if (l.any { it.matches(url, pageHost, third) }) return true }
                    val i = h.indexOf('.')
                    if (i < 0) break
                    h = h.substring(i + 1)
                }
                for (t in tokens) tokMap[t]?.let { l -> if (l.any { it.matches(url, pageHost, third) }) return true }
                return generic.any { it.matches(url, pageHost, third) }
            }
            if (!hit(blockHost, blockToken, blockGeneric)) return false
            return !hit(allowHost, allowToken, allowGeneric)
        }

        fun css(host: String): String {
            cssCache[host]?.let { return it }
            val chain = generateSequence(host) { h -> h.indexOf('.').takeIf { it >= 0 }?.let { h.substring(it + 1) } }.toList()
            if (chain.any { it in noCosmetic }) return "".also { cssCache[host] = it }
            val except = HashSet<String>()
            chain.forEach { domainExcept[it]?.let(except::addAll) }
            val specific = LinkedHashSet<String>()
            chain.forEach { domainSel[it]?.let(specific::addAll) }
            specific.removeAll(except)
            val generic = when {
                chain.any { it in noGeneric } -> ""
                except.isEmpty() -> genericCss
                else -> cssOf(genericSel.filter { it !in except })
            }
            return (generic + cssOf(specific)).also { cssCache[host] = it }
        }

        /** 每 200 個選擇器一組：一個選擇器無效只會讓同一組失效，不會全部失效。 */
        private fun cssOf(sels: Collection<String>): String {
            if (sels.isEmpty()) return ""
            val sb = StringBuilder()
            sels.chunked(200).forEach { c -> sb.append(c.joinToString(",")).append("{display:none!important}\n") }
            return sb.toString()
        }

        companion object {
            const val SKIP = Int.MAX_VALUE
            val TOKEN = Regex("[a-z0-9%]+")
            val COMMON = setOf("http", "https", "www", "com", "net", "org", "html", "js", "css", "php")
        }
    }
}
