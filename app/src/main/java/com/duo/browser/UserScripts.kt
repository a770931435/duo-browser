package com.duo.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 使用者腳本（油猴腳本 / Tampermonkey 格式的 .user.js）。
 *
 * - 腳本存在 files/userscripts/：index.json（清單與設定）、{id}.js（本體）、{id}.req.js（@require）、{id}.res.json（@resource）
 * - 每個啟用的腳本各自用 addDocumentStartJavaScript 註冊到每個分頁（一個腳本有語法錯誤不會影響其他腳本），
 *   網址比對（@match/@include/@exclude）與執行時機（@run-at）在 JS 裡判斷。
 * - GM_* 功能透過 DuoGM 這個 JavascriptInterface 由原生實作。DuoGM 對所有網頁都看得到，
 *   所以每次呼叫都要帶只有注入腳本才知道的 token（每次啟動 App 隨機產生），網頁本身無法冒用。
 *   token 不能讓網頁攔到：腳本只透過 document-start 時凍結的 window.__duoGM 呼叫（[CORE_JS]），
 *   晚注入的大型腳本原始碼裡也不放 token，改用一次性兌換碼（[evalLazy]）。
 */
object UserScripts {
    data class Script(
        val id: String,
        val name: String,
        val namespace: String,
        val version: String,
        val description: String,
        val author: String,
        val matches: List<String>,
        val includes: List<String>,
        val excludes: List<String>,
        val runAt: String,
        val noframes: Boolean,
        val grants: List<String>,
        val requires: List<String>,
        val resources: Map<String, String>,
        val source: String?,
        var enabled: Boolean = true,
    )

    /** 網頁腳本註冊的選單指令（GM_registerMenuCommand）。 */
    data class GmMenu(val scriptId: String, val cmdId: String, val scriptName: String, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val rng = SecureRandom()
    private fun randomHex(bytes: Int) = ByteArray(bytes).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val token: String = randomHex(16)
    /** 回呼函式名稱用的亂數（和 token 無關：window 上的屬性名稱網頁看得到）。 */
    private val cbKey: String = randomHex(6)
    /** 大型腳本晚注入時用的一次性兌換碼（見 [evalLazy]）。 */
    private val nonces = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private const val NONCE_MARK = "__DUO_NONCE_MARK__"

    /**
     * 每個框架在網頁自己的腳本執行之前（document-start）先把 DuoGM 的方法 bind 起來，
     * 放進凍結、不可改寫的 window.__duoGM。之後腳本都透過它呼叫，不經過網頁可以改寫的
     * window.DuoGM / Function.prototype.apply，token 就不會被網頁攔截。
     */
    const val CORE_JS = """
(function(){
  var B = window.DuoGM;
  if (!B || window.__duoGM) return;
  var n = ['load','claim','getValue','setValue','deleteValue','listValues','registerMenu','unregisterMenu',
           'openInTab','setClipboard','notify','resource','abort','xhr'];
  var K = {};
  for (var i = 0; i < n.length; i++) K[n[i]] = B[n[i]].bind(B);
  try { Object.defineProperty(window, '__duoGM', { value: Object.freeze(K) }); } catch (e) {}
})();
"""
    private val dir: File get() = File(Session.app.filesDir, "userscripts").apply { mkdirs() }
    private val values by lazy { Session.app.getSharedPreferences("gm_values", Context.MODE_PRIVATE) }
    private val ua: String by lazy { WebSettings.getDefaultUserAgent(Session.app) }

    private var cache: MutableList<Script>? = null
    private var bundleCache: List<String>? = null

    // ---------------- 清單 ----------------

    @Synchronized
    fun all(): List<Script> {
        cache?.let { return it }
        val list = mutableListOf<Script>()
        try {
            val f = File(dir, "index.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
        } catch (_: Exception) {}
        cache = list
        return list
    }

    @Synchronized
    private fun save(list: List<Script>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        File(dir, "index.json").writeText(arr.toString())
        cache = list.toMutableList()
        bundleCache = null
    }

    fun setEnabled(id: String, on: Boolean) {
        val list = all().toMutableList()
        list.find { it.id == id }?.enabled = on
        save(list)
        Session.browser?.applyUserScripts()
    }

    fun remove(id: String) {
        save(all().filter { it.id != id })
        listOf("$id.js", "$id.req.js", "$id.res.json").forEach { File(dir, it).delete() }
        val ed = values.edit()
        values.all.keys.filter { it.startsWith("$id/") }.forEach { ed.remove(it) }
        ed.apply()
        Session.browser?.applyUserScripts()
    }

    // ---------------- 解析 ----------------

    fun isScriptUrl(url: String?): Boolean {
        val u = Uri.parse(url ?: return false)
        val s = u.scheme?.lowercase()
        return (s == "http" || s == "https") && u.path?.lowercase()?.endsWith(".user.js") == true
    }

    /** 讀出 ==UserScript== 區塊；不是使用者腳本就回傳 null。 */
    fun parse(code: String, source: String?): Script? {
        val start = code.indexOf("==UserScript==")
        val end = code.indexOf("==/UserScript==")
        if (start < 0 || end < start) return null
        val meta = LinkedHashMap<String, MutableList<String>>()
        code.substring(start, end).lines().forEach { line ->
            val m = Regex("^\\s*//\\s*@(\\S+)(?:\\s+(.*))?$").find(line) ?: return@forEach
            meta.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].trim())
        }
        fun one(k: String) = meta[k]?.firstOrNull().orEmpty()
        val loc = L.t("zh-TW", "zh-CN", "en")
        fun localized(k: String) = listOf("$k:$loc", "$k:${loc.substringBefore('-')}", if (loc == "zh-TW") "$k:zh-HK" else "", k)
            .firstNotNullOfOrNull { key -> meta[key]?.firstOrNull()?.takeIf { it.isNotBlank() } }.orEmpty()
        val name = localized("name").ifBlank { return null }
        val namespace = one("namespace")
        val resources = LinkedHashMap<String, String>()
        meta["resource"]?.forEach { r ->
            val parts = r.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) resources[parts[0]] = parts[1]
        }
        return Script(
            id = sha1("$namespace\n${one("name")}").take(16),
            name = name,
            namespace = namespace,
            version = one("version"),
            description = localized("description"),
            author = one("author"),
            matches = meta["match"].orEmpty(),
            includes = meta["include"].orEmpty(),
            excludes = meta["exclude"].orEmpty() + meta["exclude-match"].orEmpty(),
            runAt = one("run-at").ifBlank { "document-idle" },
            noframes = meta.containsKey("noframes"),
            grants = meta["grant"].orEmpty(),
            requires = meta["require"].orEmpty(),
            resources = resources,
            source = source ?: one("downloadURL").ifBlank { null },
        )
    }

    // ---------------- 下載 / 安裝 ----------------

    /** 在背景下載腳本原始碼。 */
    fun fetchAsync(url: String, onDone: (String?) -> Unit) {
        pool.execute {
            val r = try { fetch(url).toString(Charsets.UTF_8) } catch (_: Exception) { null }
            main.post { onDone(r) }
        }
    }

    /** 安裝（或更新）：一併下載 @require 與 @resource。完成後在主執行緒回報錯誤訊息（null = 成功）。 */
    fun install(s: Script, code: String, onDone: (String?) -> Unit) {
        pool.execute {
            val err = try {
                val req = StringBuilder()
                s.requires.forEach { u -> req.append(fetch(u).toString(Charsets.UTF_8)).append("\n;\n") }
                val res = JSONObject()
                s.resources.forEach { (n, u) ->
                    val conn = open(u)
                    val bytes = conn.inputStream.use { it.readBytes() }
                    res.put(n, JSONObject().put("mime", conn.contentType ?: "application/octet-stream")
                        .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    conn.disconnect()
                }
                File(dir, "${s.id}.js").writeText(code)
                File(dir, "${s.id}.req.js").writeText(req.toString())
                File(dir, "${s.id}.res.json").writeText(res.toString())
                val list = all().toMutableList()
                val old = list.indexOfFirst { it.id == s.id }
                if (old >= 0) { s.enabled = list[old].enabled; list[old] = s } else list.add(s)
                save(list)
                null
            } catch (e: Exception) {
                L.t("下載腳本需要的檔案失敗：", "下载脚本需要的文件失败：", "Couldn't download a file the script needs: ") + (e.message ?: e.javaClass.simpleName)
            }
            main.post {
                if (err == null) Session.browser?.applyUserScripts()
                onDone(err)
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", ua)
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} $url")
        return c
    }

    private fun fetch(url: String): ByteArray {
        val c = open(url)
        return try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    // ---------------- 注入 ----------------

    /** 每個啟用的腳本各一段 JS（給 addDocumentStartJavaScript 用）。 */
    @Synchronized
    fun bundle(): List<String> {
        bundleCache?.let { return it }
        lazyCode.clear()
        lazyMatch.clear()
        val out =all().filter { it.enabled && it.runAt != "context-menu" }.mapNotNull { s ->
            try {
                val code = File(dir, "${s.id}.js").readText()
                val req = File(dir, "${s.id}.req.js").takeIf { it.exists() }?.readText().orEmpty()
                val body = req + "\n" + code
                if (body.length > LAZY_SIZE) {
                    // 大型腳本（例如沉浸式翻譯約 6MB）：每個網頁、每個 iframe 都解析一次太慢。
                    // 改成只在主框架、網址符合時，由原生用 evaluateJavascript 載入（不受網頁 CSP 限制）。
                    lazyCode[s.id] = wrap(s, body, lazy = true)
                    lazyMatch[s.id] = includeRegexes(s) to excludeRegexes(s)
                    stub(s)
                } else wrap(s, body, lazy = false)
            } catch (_: Exception) { null }
        }
        bundleCache = out
        return out
    }

    private const val LAZY_SIZE = 512 * 1024
    private val lazyCode = ConcurrentHashMap<String, String>()
    /** 大型腳本的網址比對（include / exclude 正規表示式），給原生判斷用。 */
    private val lazyMatch = ConcurrentHashMap<String, Pair<List<String>, List<String>>>()

    private fun includeRegexes(s: Script) =
        (s.matches.mapNotNull { matchToRegex(it) } + s.includes.map { includeToRegex(it) }).ifEmpty { listOf(".*") }

    private fun excludeRegexes(s: Script) =
        s.excludes.map { if (it.contains("://") && !it.startsWith("/")) matchToRegex(it) ?: includeToRegex(it) else includeToRegex(it) }

    private fun urlMatches(url: String, inc: List<String>, exc: List<String>): Boolean {
        fun any(l: List<String>) = l.any { p -> try { Regex(p).containsMatchIn(url) } catch (_: Exception) { false } }
        return any(inc) && !any(exc)
    }

    /**
     * 網頁載入完成時檢查：大型腳本如果網址符合卻還沒在執行（看 __duoUS_{id} 旗標），就再載入一次。
     * 網址由原生判斷，不在網頁裡執行含 token 的程式碼（網頁的腳本這時已經在跑，可能攔截）。
     */
    fun ensureLazyLoaded(t: Tab) {
        bundle()
        val url = t.web.url ?: return
        lazyMatch.forEach { (id, m) ->
            // 已經在載入（網頁開頭的 stub 排了）就不要再送一次：6MB 的腳本重複解析很慢
            if (id in t.lazyLoaded || !urlMatches(url, m.first, m.second)) return@forEach
            t.web.evaluateJavascript("!!window['__duoUS_$id']") { r -> if (r != "true") evalLazy(t, id) }
        }
    }

    /**
     * 注入大型腳本（主執行緒）。這時網頁自己的腳本可能已經在執行，所以程式碼裡不放 token，
     * 只放一次性兌換碼：腳本第一行就用凍結的 window.__duoGM.claim 換回 token（存在變數裡），兌換碼隨即失效。
     */
    private fun evalLazy(t: Tab, id: String) {
        val js = lazyCode[id] ?: return
        if (t.incognito) return // 無痕分頁不執行使用者腳本
        if (!Session.browser?.tabs.orEmpty().contains(t)) return
        val n = randomHex(16)
        nonces.add(n)
        t.lazyLoaded.add(id)
        t.web.evaluateJavascript(js.replaceFirst(NONCE_MARK, n), null)
    }

    /** 只在 document-start 註冊（網頁的腳本還沒執行），所以可以帶 token。 */
    private fun stub(s: Script): String {
        return "(function(){if(window.top!==window)return;var G=window.__duoGM;if(!G)return;" +
            "var h=String(location.href),I=${JSONArray(includeRegexes(s))},E=${JSONArray(excludeRegexes(s))};" +
            "function t(l){for(var i=0;i<l.length;i++){try{if(new RegExp(l[i]).test(h))return true;}catch(e){}}return false;}" +
            "if(!t(I)||t(E))return;" +
            // 大型腳本等網頁畫面出來、而且有空檔時才載入，不要跟網頁本身搶資源（網頁會開得比較快）
            "function go(){try{G.load('$token','${s.id}');}catch(e){}}" +
            "function idle(){if(window.requestIdleCallback)requestIdleCallback(go,{timeout:1500});else setTimeout(go,300);}" +
            "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',idle,{once:true});else idle();})();"
    }

    private fun cbName(id: String) = "__duo_" + cbKey + "_" + id

    /** @param lazy true = 由 [evalLazy] 晚注入（用一次性兌換碼取得 token）；false = document-start 註冊。 */
    private fun wrap(s: Script, code: String, lazy: Boolean): String {
        val inc = includeRegexes(s)
        val exc = excludeRegexes(s)
        val tokenExpr = if (lazy) "(window.__duoGM&&window.__duoGM.claim('$NONCE_MARK'))" else "'$token'"
        val info = JSONObject()
            .put("script", JSONObject()
                .put("name", s.name).put("namespace", s.namespace).put("version", s.version)
                .put("description", s.description).put("author", s.author)
                .put("matches", JSONArray(s.matches)).put("includes", JSONArray(s.includes))
                .put("excludes", JSONArray(s.excludes)).put("grant", JSONArray(s.grants))
                .put("run-at", s.runAt).put("resources", JSONArray(s.resources.keys.toList())))
            .put("scriptHandler", "Duo Browser")
            .put("version", UpdateChecker.currentVersion())
        return RUNTIME
            .replace("%ID%", s.id)
            .replace("%TOKEXPR%", tokenExpr)
            .replace("%CB%", cbName(s.id))
            .replace("%INFO%", info.toString())
            .replace("%INC%", JSONArray(inc).toString())
            .replace("%EXC%", JSONArray(exc).toString())
            .replace("%NOFRAMES%", if (s.noframes) "true" else "false")
            .replace("%RUNAT%", s.runAt)
            .replace("%CODE%", code) // 最後才換，避免腳本內容裡剛好有 %XXX% 被誤換
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if ("\\.[]{}()+?^$|/".indexOf(c) >= 0) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private fun glob(s: String) = esc(s).replace("*", ".*")

    private fun includeToRegex(p: String): String = when {
        p.length > 2 && p.startsWith("/") && p.endsWith("/") -> p.substring(1, p.length - 1)
        p == "*" -> ".*"
        else -> "^" + glob(p) + "$"
    }

    private fun matchToRegex(p: String): String? {
        if (p == "<all_urls>" || p == "*://*/*") return "^(https?|file|ftp)://.*$"
        val m = Regex("^(\\*|https?|file|ftp|wss?)://([^/]*)(/.*)?$").find(p) ?: return "^" + glob(p) + "$"
        val scheme = m.groupValues[1].let { if (it == "*") "https?" else it }
        val host = m.groupValues[2]
        val hostRe = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "([^/]*\\.)?" + esc(host.substring(2))
            else -> esc(host).replace("*", "[^/]*")
        }
        val port = if (host.contains(':')) "" else "(:\\d+)?"
        val path = m.groupValues[3].ifEmpty { "/" }
        return "^" + scheme + "://" + hostRe + port + glob(path) + "$"
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 在這個時間之前，網頁的 window.open 即使不是使用者點擊觸發也允許（見 [runMenu]）。 */
    @Volatile var popupAllowedUntil = 0L

    /** 執行網頁腳本註冊的選單指令。 */
    fun runMenu(t: Tab, m: GmMenu) {
        // 指令常會用 window.open 開新頁（例如沉浸式翻譯的設定頁）；從原生執行不算使用者手勢，會被擋掉，所以短暫放行
        popupAllowedUntil = android.os.SystemClock.uptimeMillis() + 2000
        t.web.evaluateJavascript(
            "(function(){var f=window[${JSONObject.quote(cbName(m.scriptId))}];if(f)f('menu',${JSONObject.quote(m.cmdId)});})()", null)
    }

    // ---------------- GM_* 原生實作 ----------------

    private val running = ConcurrentHashMap<String, HttpURLConnection>()

    class Bridge(private val t: Tab) {
        private fun ok(tok: String?) = tok == token

        /** 大型腳本的載入要求（見 [bundle]）。 */
        @JavascriptInterface
        fun load(tok: String?, id: String?) {
            if (!ok(tok) || id == null || !lazyCode.containsKey(id)) return
            main.post {
                if (id in t.lazyLoaded) return@post // 這一頁已經載入過
                evalLazy(t, id)
            }
        }

        /** 晚注入的大型腳本用一次性兌換碼換回 token（見 [evalLazy]）。 */
        @JavascriptInterface
        fun claim(nonce: String?): String? = if (nonce != null && nonces.remove(nonce)) token else null

        @JavascriptInterface
        fun getValue(tok: String?, id: String?, key: String?): String? =
            if (ok(tok)) values.getString("$id/$key", null) else null

        @JavascriptInterface
        fun setValue(tok: String?, id: String?, key: String?, json: String?) {
            if (ok(tok)) values.edit().putString("$id/$key", json).apply()
        }

        @JavascriptInterface
        fun deleteValue(tok: String?, id: String?, key: String?) {
            if (ok(tok)) values.edit().remove("$id/$key").apply()
        }

        @JavascriptInterface
        fun listValues(tok: String?, id: String?): String {
            if (!ok(tok)) return "[]"
            val p = "$id/"
            return JSONArray(values.all.keys.filter { it.startsWith(p) }.map { it.removePrefix(p) }).toString()
        }

        @JavascriptInterface
        fun registerMenu(tok: String?, id: String?, cmdId: String?, scriptName: String?, label: String?) {
            if (!ok(tok) || id == null || cmdId == null) return
            main.post {
                // 有些腳本（例如沉浸式翻譯）會重複註冊同名指令：同一個腳本、同樣名稱只保留最新的
                val i = t.gmMenus.indexOfFirst { it.cmdId == cmdId || (it.scriptId == id && it.label == (label ?: "")) }
                val m = GmMenu(id, cmdId, scriptName ?: "", label ?: "")
                if (i >= 0) t.gmMenus[i] = m else t.gmMenus.add(m)
            }
        }

        @JavascriptInterface
        fun unregisterMenu(tok: String?, cmdId: String?) {
            if (ok(tok)) main.post { t.gmMenus.removeAll { it.cmdId == cmdId } }
        }

        @JavascriptInterface
        fun openInTab(tok: String?, url: String?, background: Boolean) {
            if (!ok(tok) || url == null) return
            val s = Uri.parse(url).scheme?.lowercase()
            if (s != "http" && s != "https") return
            main.post { Session.browser?.newTab(url, switch = !background) }
        }

        @JavascriptInterface
        fun setClipboard(tok: String?, text: String?) {
            if (!ok(tok)) return
            main.post {
                Session.app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("text", text ?: ""))
            }
        }

        @JavascriptInterface
        fun notify(tok: String?, text: String?) {
            if (ok(tok) && !text.isNullOrBlank()) main.post { Session.ui?.flash(text) }
        }

        @JavascriptInterface
        fun resource(tok: String?, id: String?, name: String?, kind: String?): String? {
            if (!ok(tok)) return null
            return try {
                val r = JSONObject(File(dir, "$id.res.json").readText()).optJSONObject(name ?: "") ?: return null
                val b64 = r.getString("b64")
                if (kind == "url") "data:${r.optString("mime")};base64,$b64"
                else String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { null }
        }

        @JavascriptInterface
        fun abort(tok: String?, reqId: String?) {
            if (!ok(tok) || reqId == null) return
            running.remove(reqId)?.let { c -> pool.execute { try { c.disconnect() } catch (_: Exception) {} } }
        }

        /** GM_xmlhttpRequest：不受網頁同源限制的連線（油猴腳本最常用的功能，翻譯腳本靠它呼叫翻譯服務）。 */
        @JavascriptInterface
        fun xhr(tok: String?, id: String?, reqId: String?, reqJson: String?) {
            if (!ok(tok) || id == null || reqId == null || reqJson == null) return
            pool.execute {
                val out = JSONObject()
                try {
                    val r = JSONObject(reqJson)
                    val url = r.getString("url")
                    val scheme = Uri.parse(url).scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") throw IllegalArgumentException("unsupported URL")
                    var method = r.optString("method", "GET").ifBlank { "GET" }
                    val c = URL(url).openConnection() as HttpURLConnection
                    running[reqId] = c
                    val timeout = r.optInt("timeout", 0).let { if (it > 0) it else 30000 }
                    c.connectTimeout = timeout; c.readTimeout = timeout
                    c.instanceFollowRedirects = true
                    if (method == "PATCH") { c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); method = "POST" }
                    c.requestMethod = method
                    var hasUa = false
                    r.optJSONObject("headers")?.let { h ->
                        h.keys().forEach { k ->
                            if (k.equals("user-agent", true)) hasUa = true
                            c.setRequestProperty(k, h.getString(k))
                        }
                    }
                    if (!hasUa) c.setRequestProperty("User-Agent", ua)
                    val anonymous = r.optBoolean("anonymous", false)
                    if (!anonymous && c.getRequestProperty("Cookie") == null) {
                        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                    }
                    if (!r.isNull("data") && method != "GET" && method != "HEAD") {
                        val body = r.getString("data").toByteArray(Charsets.UTF_8)
                        if (c.getRequestProperty("Content-Type") == null)
                            c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                        c.doOutput = true
                        c.setFixedLengthStreamingMode(body.size)
                        c.outputStream.use { it.write(body) }
                    }
                    val code = c.responseCode
                    val stream = if (code >= 400) c.errorStream else c.inputStream
                    val bytes = stream?.use { input ->
                        val bo = ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            bo.write(buf, 0, n)
                            if (bo.size() > 64 * 1024 * 1024) throw IllegalStateException("response too large")
                        }
                        bo.toByteArray()
                    } ?: ByteArray(0)
                    val headers = StringBuilder()
                    c.headerFields.forEach { (k, vs) -> if (k != null) vs.forEach { v -> headers.append(k.lowercase()).append(": ").append(v).append("\r\n") } }
                    if (!anonymous) c.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(c.url.toString(), it) }
                    val ctype = r.optString("overrideMimeType").ifBlank { c.contentType ?: "" }
                    out.put("status", code).put("statusText", c.responseMessage ?: "")
                        .put("headers", headers.toString()).put("finalUrl", c.url.toString()).put("contentType", ctype)
                    val rt = r.optString("responseType")
                    if (rt == "arraybuffer" || rt == "blob") out.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    else {
                        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ctype)?.groupValues?.get(1)
                        val charset = try { if (cs != null) charset(cs) else Charsets.UTF_8 } catch (_: Exception) { Charsets.UTF_8 }
                        out.put("text", String(bytes, charset))
                    }
                    c.disconnect()
                } catch (e: java.net.SocketTimeoutException) {
                    out.put("error", "timeout").put("timeout", true)
                } catch (e: Exception) {
                    out.put("error", e.message ?: e.javaClass.simpleName)
                }
                if (running.remove(reqId) == null && out.has("error")) return@execute // 已被 abort
                val js = "(function(){var f=window[${JSONObject.quote(cbName(id))}];if(f)f('xhr',${JSONObject.quote(reqId)},${JSONObject.quote(out.toString())});})()"
                main.post { t.web.evaluateJavascript(js, null) }
            }
        }
    }

    // ---------------- 序列化 ----------------

    private fun toJson(s: Script) = JSONObject()
        .put("id", s.id).put("name", s.name).put("namespace", s.namespace).put("version", s.version)
        .put("description", s.description).put("author", s.author)
        .put("matches", JSONArray(s.matches)).put("includes", JSONArray(s.includes)).put("excludes", JSONArray(s.excludes))
        .put("runAt", s.runAt).put("noframes", s.noframes).put("grants", JSONArray(s.grants))
        .put("requires", JSONArray(s.requires)).put("resources", JSONObject(s.resources as Map<*, *>))
        .put("source", s.source ?: JSONObject.NULL).put("enabled", s.enabled)

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun fromJson(o: JSONObject): Script? = try {
        val res = LinkedHashMap<String, String>()
        o.optJSONObject("resources")?.let { r -> r.keys().forEach { k -> res[k] = r.getString(k) } }
        Script(
            id = o.getString("id"), name = o.getString("name"), namespace = o.optString("namespace"),
            version = o.optString("version"), description = o.optString("description"), author = o.optString("author"),
            matches = o.optJSONArray("matches").strings(), includes = o.optJSONArray("includes").strings(),
            excludes = o.optJSONArray("excludes").strings(), runAt = o.optString("runAt", "document-idle"),
            noframes = o.optBoolean("noframes"), grants = o.optJSONArray("grants").strings(),
            requires = o.optJSONArray("requires").strings(), resources = res,
            source = if (o.isNull("source")) null else o.optString("source"), enabled = o.optBoolean("enabled", true),
        )
    } catch (_: Exception) { null }

    /**
     * 每個腳本外面包的執行環境。注意：外層不能用 'use strict'（會連帶讓腳本本身變成嚴格模式）。
     * 不能出現 Kotlin 字串樣板的錢字號，所以 JS 裡都不用它。
     */
    private const val RUNTIME = """
(function(){
var T=%TOKEXPR%; if (!T) return;
var ID='%ID%', CB='%CB%';
var INFO=%INFO%;
if (%NOFRAMES% && window.top !== window) return;
var href = String(location.href);
var INC=%INC%, EXC=%EXC%;
function test(list){ for (var i=0;i<list.length;i++){ try { if (new RegExp(list[i]).test(href)) return true; } catch(e){} } return false; }
if (!test(INC) || test(EXC)) return;
var flag='__duoUS_'+ID;
if (window[flag]) return;
try { Object.defineProperty(window, flag, {value: true}); } catch(e){}
// 一律用 document-start 時就 bind 好、凍結的 window.__duoGM（見 CORE_JS），直接呼叫，token 不經過網頁能改寫的函式
var B = window.__duoGM; if (!B) return;
var bGet=B.getValue, bSet=B.setValue, bDel=B.deleteValue, bList=B.listValues,
    bXhr=B.xhr, bAbort=B.abort, bMenu=B.registerMenu, bUnmenu=B.unregisterMenu,
    bTab=B.openInTab, bClip=B.setClipboard, bNote=B.notify, bRes=B.resource;
var isTop = window.top === window;
var pending = {}, menus = {}, listeners = {}, seq = 0;
function uid(){ return ID + '_' + (++seq) + '_' + Math.random().toString(36).slice(2, 8); }
function b64ToBuf(s){ var bin = atob(s), n = bin.length, u = new Uint8Array(n); for (var i=0;i<n;i++) u[i] = bin.charCodeAt(i); return u.buffer; }
try {
  Object.defineProperty(window, CB, { value: function(kind, id, payload){
    if (kind === 'menu') { var mf = menus[id]; if (mf) { try { mf(); } catch(e){ console.error(e); } } return; }
    if (kind !== 'xhr') return;
    var d = pending[id]; if (!d) return; delete pending[id];
    var r = JSON.parse(payload);
    var mime = r.contentType || '';
    var resp = { readyState: 4, status: r.status || 0, statusText: r.statusText || '', responseHeaders: r.headers || '',
                 finalUrl: r.finalUrl || d.url, context: d.context, responseText: '', response: null, responseXML: null };
    if (r.b64 != null) { var buf = b64ToBuf(r.b64); resp.response = d.responseType === 'blob' ? new Blob([buf], {type: mime}) : buf; }
    else {
      var txt = r.text || ''; resp.responseText = txt;
      if (d.responseType === 'json') { try { resp.response = JSON.parse(txt); } catch(e){ resp.response = null; } }
      else if (d.responseType === 'document') { try { resp.response = new DOMParser().parseFromString(txt, mime.indexOf('xml') >= 0 ? 'text/xml' : 'text/html'); } catch(e){} }
      else resp.response = txt;
      if (mime.indexOf('xml') >= 0) { try { resp.responseXML = new DOMParser().parseFromString(txt, 'text/xml'); } catch(e){} }
    }
    function call(n, a){ if (typeof d[n] === 'function') { try { d[n](a); } catch(e){ console.error(e); } } }
    if (r.error) { resp.error = r.error; call(r.timeout ? 'ontimeout' : 'onerror', resp); }
    else { call('onreadystatechange', resp); call('onload', resp); }
    call('onloadend', resp);
  }});
} catch(e){}
function getValue(k, def){ var r = bGet(T, ID, String(k)); if (r === null || r === undefined) return def; try { return JSON.parse(r); } catch(e){ return def; } }
function fire(k, o, n){ for (var lid in listeners) { var l = listeners[lid]; if (l.k === String(k)) { try { l.f(k, o, n, false); } catch(e){} } } }
function setValue(k, v){ var o = getValue(k); bSet(T, ID, String(k), JSON.stringify(v === undefined ? null : v)); fire(k, o, v); }
function deleteValue(k){ var o = getValue(k); bDel(T, ID, String(k)); fire(k, o, undefined); }
function listValues(){ try { return JSON.parse(bList(T, ID)); } catch(e){ return []; } }
function addListener(k, f){ var lid = uid(); listeners[lid] = {k: String(k), f: f}; return lid; }
function removeListener(lid){ delete listeners[lid]; }
function root(){ return document.head || document.documentElement; }
function addStyle(css){
  var s = document.createElement('style'); s.textContent = css;
  var r = root(); if (r) r.appendChild(s); else document.addEventListener('DOMContentLoaded', function(){ root().appendChild(s); });
  return s;
}
function addElement(p, tag, attrs){
  if (typeof p === 'string') { attrs = tag; tag = p; p = null; }
  var e = document.createElement(tag);
  if (attrs) for (var a in attrs) { if (a === 'textContent') e.textContent = attrs[a]; else e.setAttribute(a, attrs[a]); }
  (p || root()).appendChild(e); return e;
}
function xhr(d){
  d = d || {}; var id = uid(); pending[id] = d;
  var headers = {}; if (d.headers) for (var h in d.headers) headers[h] = String(d.headers[h]);
  var hasType = false; for (var hk in headers) if (hk.toLowerCase() === 'content-type') hasType = true;
  var data = d.data;
  if (data != null && typeof data !== 'string') {
    if (typeof URLSearchParams !== 'undefined' && data instanceof URLSearchParams) { data = data.toString(); if (!hasType) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
    else if (typeof FormData !== 'undefined' && data instanceof FormData) { var fp = new URLSearchParams(); data.forEach(function(v, k){ fp.append(k, v); }); data = fp.toString(); if (!hasType) headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8'; }
    else { try { data = JSON.stringify(data); } catch(e){ data = String(data); } }
  }
  var url; try { url = new URL(d.url, location.href).href; } catch(e){ url = String(d.url); }
  bXhr(T, ID, id, JSON.stringify({ method: String(d.method || 'GET').toUpperCase(), url: url, headers: headers,
    data: data == null ? null : String(data), responseType: d.responseType || '', timeout: d.timeout || 0,
    anonymous: !!d.anonymous, overrideMimeType: d.overrideMimeType || '' }));
  return { abort: function(){ if (pending[id]) { delete pending[id]; bAbort(T, id); if (typeof d.onabort === 'function') { try { d.onabort({}); } catch(e){} } } } };
}
function menu(name, fn){ var mid = uid(); menus[mid] = fn; if (isTop) bMenu(T, ID, mid, String(INFO.script.name), String(name)); return mid; }
function unmenu(mid){ delete menus[mid]; if (isTop) bUnmenu(T, mid); }
function openInTab(url, o){
  var bg = o === true || (o && typeof o === 'object' && o.active === false);
  var u; try { u = new URL(url, location.href).href; } catch(e){ u = String(url); }
  bTab(T, u, !!bg); return { close: function(){}, closed: false };
}
function setClipboard(t){ bClip(T, String(t)); }
function notification(d, title){ var text = typeof d === 'string' ? d : (d && (d.text || d.title)) || title || ''; bNote(T, String(text)); }
function resText(n){ return bRes(T, ID, String(n), 'text'); }
function resURL(n){ return bRes(T, ID, String(n), 'url'); }
function download(d){ var u = typeof d === 'string' ? d : d && d.url; if (u) openInTab(u); }
var GM = {
  info: INFO,
  getValue: function(k, d){ return Promise.resolve(getValue(k, d)); },
  setValue: function(k, v){ setValue(k, v); return Promise.resolve(); },
  deleteValue: function(k){ deleteValue(k); return Promise.resolve(); },
  listValues: function(){ return Promise.resolve(listValues()); },
  xmlHttpRequest: function(d){ return new Promise(function(res, rej){
    var o = Object.assign({}, d);
    o.onload = function(r){ if (d.onload) d.onload(r); res(r); };
    o.onerror = function(r){ if (d.onerror) d.onerror(r); rej(r); };
    o.ontimeout = function(r){ if (d.ontimeout) d.ontimeout(r); rej(r); };
    xhr(o); }); },
  addStyle: function(c){ return Promise.resolve(addStyle(c)); },
  addElement: function(p, t, a){ return Promise.resolve(addElement(p, t, a)); },
  registerMenuCommand: menu, unregisterMenuCommand: unmenu, openInTab: openInTab,
  setClipboard: function(t){ setClipboard(t); return Promise.resolve(); },
  notification: notification,
  getResourceText: function(n){ return Promise.resolve(resText(n)); },
  getResourceUrl: function(n){ return Promise.resolve(resURL(n)); },
  addValueChangeListener: addListener, removeValueChangeListener: removeListener,
  download: download, log: console.log.bind(console)
};
GM.xmlhttpRequest = GM.xmlHttpRequest; GM.getResourceURL = GM.getResourceUrl;
function run(){
  try {
    (function(unsafeWindow, GM, GM_info, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_addValueChangeListener,
              GM_removeValueChangeListener, GM_xmlhttpRequest, GM_addStyle, GM_addElement, GM_registerMenuCommand,
              GM_unregisterMenuCommand, GM_openInTab, GM_setClipboard, GM_notification, GM_getResourceText,
              GM_getResourceURL, GM_log, GM_download){
      (function(){
%CODE%
      }).call(window);
    }).call(window, window, GM, INFO, getValue, setValue, deleteValue, listValues, addListener, removeListener, xhr,
            addStyle, addElement, menu, unmenu, openInTab, setClipboard, notification, resText, resURL,
            console.log.bind(console), download);
  } catch (e) { console.error('[Duo userscript] ' + INFO.script.name, e); }
}
var ra = '%RUNAT%';
if (ra === 'document-start') run();
else if (ra === 'document-end' || ra === 'document-body') { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run, {once: true}); else run(); }
else { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(run, 0); }, {once: true}); else setTimeout(run, 0); }
})();
"""
}
