package com.duo.browser

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * V2 介面的設計系統（見 docs/design-v2.md）：
 * 色票、動畫曲線都集中在這裡；畫面程式不寫死顏色。
 * 主題：紫透色 RP Duo 機殼——煙燻深紫底、半透明紫色層、亮邊、隱約的內部格線。
 */
/**
 * 一套主題色票，對應 RP Duo 的一種機身配色（Black / 16 Bit / GC / Ice Blue / Clear Purple）。
 * 介面一律是深色底（OLED、看影片不刺眼），機身的顏色用在半透明卡片、亮邊、主色上。
 * 色值是 RGB（不含透明度），透明度在 T 裡統一加。
 */
class Palette(
    val id: String,
    private val tw: String, private val cn: String, private val en: String,
    val bg: Int,          // 面板底色（熄燈時的機殼）
    val shell: Int,       // 機殼本體：卡片的半透明底
    val rim: Int,         // 機殼邊緣：卡片亮邊
    val grid: Int,        // 內部面板線
    val accent: Int,      // 主色（選到的頁籤、主要按鈕）
    val accentDeep: Int,  // 較飽和的主色（開關、目前分頁標記）
    val onAccent: Int,    // 主色上的文字
    val text: Int, val text2: Int, val text3: Int,
    val focus: Int,       // 手把焦點外框
    val solid: Int,       // 浮在網頁上的小元件
    /** 機身本體（主題選擇的小圖用）：亮面、暗面。卡片用的 shell 為了在深色底上看得到會比較亮，這裡是真正的機身顏色 */
    val bodyHi: Int, val bodyLo: Int,
    /** 機身按鍵的顏色（A/B/X/Y）：16 Bit、GC 這種彩色按鍵的機身，按鍵提示也用同樣的顏色 */
    val keyColors: Map<String, Int>? = null,
) {
    val label: String get() = L.t(tw, cn, en)

    companion object {
        private fun c(rgb: Long) = (rgb or 0xFF000000L).toInt()

        val PURPLE = Palette("purple", "透紫 Clear Purple", "透紫 Clear Purple", "Clear Purple",
            bg = c(0x15121D), shell = c(0xB89CF5), rim = c(0xA97CFF), grid = c(0xC3B9DE),
            accent = c(0xC9B8F2), accentDeep = c(0xA983E8), onAccent = c(0x1C1530),
            text = c(0xF3EFFF), text2 = c(0xB5ADD2), text3 = c(0x8C88A3), focus = c(0xD2B8FF), solid = c(0x221D2D), bodyHi = c(0xD9CCF5), bodyLo = c(0x9F7FE0))
        val BLACK = Palette("black", "曜黑 Black", "曜黑 Black", "Black",
            bg = c(0x101012), shell = c(0xA8A8B4), rim = c(0x8E8E9C), grid = c(0xC8C8D0),
            accent = c(0xE4E4EA), accentDeep = c(0x9C9CAA), onAccent = c(0x141416),
            text = c(0xF4F4F6), text2 = c(0xB8B8C0), text3 = c(0x85858E), focus = c(0xF2F2F6), solid = c(0x202024), bodyHi = c(0x3A3A40), bodyLo = c(0x141417))
        val BIT16 = Palette("bit16", "復古灰 16 Bit", "复古灰 16 Bit", "16 Bit",
            // 實機是偏白的淺灰機身：介面用淺灰底、更白的卡片、深灰字（五個主題裡唯一的淺色介面）
            bg = c(0xCCC8C1), shell = c(0xFFFFFF), rim = c(0x948D82), grid = c(0x6E675C),
            accent = c(0x5A554E), accentDeep = c(0x6E685F), onAccent = c(0xF6F3EE),
            text = c(0x24211D), text2 = c(0x4D4842), text3 = c(0x7A746B), focus = c(0x3A5FC8), solid = c(0xE4E0D9), bodyHi = c(0xEEEAE4), bodyLo = c(0xB8AFA2),
            // 超任配色：A 紅、B 黃、X 藍、Y 綠
            keyColors = mapOf("A" to c(0xD9453C), "B" to c(0xE8B33A), "X" to c(0x3A5FC8), "Y" to c(0x3E9E58)))
        val GC = Palette("gc", "靛紫 GC", "靛紫 GC", "GC",
            bg = c(0x12112A), shell = c(0x7C76E6), rim = c(0x6A60F0), grid = c(0x9C96F0),
            // 主色用 GC 的黃色（選到的頁籤、主要按鈕、開關）
            accent = c(0xF4C534), accentDeep = c(0xE2AE1C), onAccent = c(0x2A2006),
            text = c(0xF2F1FF), text2 = c(0xB0ACE6), text3 = c(0x8480B8), focus = c(0xFFE38A), solid = c(0x1E1C40), bodyHi = c(0x8E88F8), bodyLo = c(0x4A42C8),
            // GC 按鍵：A 綠、B 紅、X／Y 灰
            keyColors = mapOf("A" to c(0x3FAE5B), "B" to c(0xE0443C), "X" to c(0xA9A7B6), "Y" to c(0xA9A7B6)))
        val ICE = Palette("ice", "冰藍 Ice Blue", "冰蓝 Ice Blue", "Ice Blue",
            bg = c(0x0E1918), shell = c(0x7FC8BF), rim = c(0x4FB3A8), grid = c(0xA5D8D2),
            accent = c(0xA8E2DA), accentDeep = c(0x52B5AA), onAccent = c(0x0E2422),
            text = c(0xEEFBF9), text2 = c(0xA7CFCA), text3 = c(0x7A9E9A), focus = c(0xCDF5EF), solid = c(0x1A2A28), bodyHi = c(0xB8E6E0), bodyLo = c(0x4FA59C))

        /** 設定頁的順序和照片一樣 */
        val ALL = listOf(BLACK, BIT16, GC, ICE, PURPLE)
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: PURPLE

        /**
         * 無痕模式：和 Chrome 一樣整組換成中性灰（主色、亮邊、頁籤、開關都沒有機身的顏色），
         * 一眼就看得出現在是無痕。按鍵提示的 A/B/X/Y 顏色照舊（那是實體按鍵的顏色）。
         */
        fun incognitoOf(base: Palette) = Palette("incognito", "無痕", "无痕", "Incognito",
            bg = c(0x202124), shell = c(0xA4A8AE), rim = c(0x80868B), grid = c(0x9AA0A6),
            accent = c(0xE3E5E8), accentDeep = c(0xBDC1C6), onAccent = c(0x202124),
            text = c(0xF1F3F4), text2 = c(0xBDC1C6), text3 = c(0x8F9399), focus = c(0xFFFFFF), solid = c(0x2A2B2E),
            bodyHi = base.bodyHi, bodyLo = base.bodyLo, keyColors = base.keyColors)
    }
}

object T {
    /** 目前的主題（Session.init 依設定決定；換主題後整組介面重建） */
    var base: Palette = Palette.PURPLE
        private set
    private var incPal: Palette? = null
    /** 選單目前顯示無痕分頁：介面用中性灰（Palette.incognitoOf） */
    var incognito = false
    /** 目前畫面要用的色票；設定值 = 換主題（無痕時仍顯示灰色，離開無痕才看得到） */
    var cur: Palette
        get() = if (incognito) (incPal ?: Palette.incognitoOf(base).also { incPal = it }) else base
        set(v) { base = v; incPal = null }

    private fun a(rgb: Int, alpha: Int) = (rgb and 0x00FFFFFF) or (alpha shl 24)

    // ---- 色票（依目前主題） ----
    val BG: Int get() = cur.bg                    // 面板底色
    val BG_GLASS: Int get() = cur.bg              // 疊在網頁上的面板（不透明，網頁不會干擾文字）
    val GRID: Int get() = a(cur.grid, 0x20)       // 機殼內部一格一格的面板線
    val SURFACE: Int get() = a(cur.shell, 0x26)   // 卡片：半透明的機殼
    val SURFACE_HI: Int get() = a(cur.shell, 0x40) // 按下／選到
    val SURFACE_SOLID: Int get() = cur.solid      // 不透明的卡片（浮在網頁上的小元件）
    val EDGE: Int get() = a(cur.rim, 0x73)        // 卡片亮邊：殼邊緣
    val EDGE_STRONG: Int get() = a(cur.rim, 0xA6)
    val FOCUS: Int get() = cur.focus              // 手把焦點外框
    const val SHEEN = 0x14FFFFFF                  // 卡片頂端內側的一條反光
    val ACCENT: Int get() = cur.accent
    val ACCENT_DEEP: Int get() = cur.accentDeep
    val ON_ACCENT: Int get() = cur.onAccent
    val TEXT: Int get() = cur.text
    val TEXT2: Int get() = cur.text2
    val TEXT3: Int get() = cur.text3
    /** 浮在網頁上的小元件（提示、選單鈕）：不透明度 0xE0～0xF5 */
    fun solid(alpha: Int) = a(cur.solid, alpha)
    fun withAlpha(rgb: Int, alpha: Int) = a(rgb, alpha)
    /** 淺色介面（16 Bit）：狀態色要深一點才看得到 */
    val LIGHT: Boolean get() = lumaOf(cur.bg) > 0.5f
    val OK: Int get() = if (LIGHT) 0xFF2B7A4B.toInt() else 0xFF8EE3B4.toInt()
    val WARN: Int get() = if (LIGHT) 0xFFA4560A.toInt() else 0xFFFFCB8E.toInt()
    val DANGER: Int get() = if (LIGHT) 0xFFB8304A.toInt() else 0xFFFF8FA6.toInt()

    // ---- 動畫 ----
    /** 快出慢停（一般的移動、淡入） */
    val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** 稍微超過再回來（按下放開、開關） */
    val SPRING = PathInterpolator(0.34f, 1.4f, 0.64f, 1f)
    val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)
    const val DUR = 200L
    const val DUR_PANEL = 260L
    const val DUR_OUT = 150L
}

/** 圓角矩形底：填色、亮邊、可選的內部格線與頂端反光（機殼的質感）。 */
class ShellDrawable(
    private val density: Float,
    var fill: Int,
    var radiusDp: Float = 16f,
    var edge: Int = 0,
    var edgeDp: Float = 1f,
    private val grid: Boolean = false,
    private val sheen: Boolean = false,
    /** 只有上面兩個角是圓的（從下面滑出來的面板） */
    private val topOnly: Boolean = false,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridPaint = Paint().apply { color = T.GRID; strokeWidth = 1f }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var a = 255

    private fun shape(r: RectF, rad: Float) {
        path.reset()
        if (topOnly) path.addRoundRect(r, floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
        else path.addRoundRect(r, rad, rad, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val rad = radiusDp * density
        rect.set(b)
        shape(rect, rad)
        fillPaint.color = fill
        fillPaint.alpha = (android.graphics.Color.alpha(fill) * a / 255)
        canvas.drawPath(path, fillPaint)
        if (grid) {
            canvas.save()
            canvas.clipPath(path)
            // 像機殼裡一格一格的面板：大格子，寬高不一樣
            val sx = 132f * density; val sy = 96f * density
            gridPaint.alpha = android.graphics.Color.alpha(T.GRID) * a / 255
            var x = b.left + sx
            while (x < b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), gridPaint); x += sx }
            var y = b.top + sy
            while (y < b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, gridPaint); y += sy }
            canvas.restore()
        }
        if (sheen) {
            // 頂端一條淡淡的反光，像透明塑膠殼的邊緣
            sheenPaint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + 48f * density,
                0x22E9DDFF, 0x00E9DDFF, Shader.TileMode.CLAMP)
            sheenPaint.alpha = a
            canvas.save(); canvas.clipPath(path)
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + 48f * density, sheenPaint)
            canvas.restore()
        }
        if (edge != 0 && radiusDp < 100f && b.height() > 40 * density) {
            // 卡片頂端內側一條細反光（透明塑膠殼的厚度感）
            sheenPaint.shader = null
            sheenPaint.color = T.SHEEN
            sheenPaint.alpha = android.graphics.Color.alpha(T.SHEEN) * a / 255
            val inset = rad * 0.8f
            canvas.drawRect(b.left + inset, b.top + edgeDp * density, b.right - inset, b.top + edgeDp * density * 2, sheenPaint)
        }
        if (edge != 0) {
            val w = edgeDp * density
            edgePaint.strokeWidth = w
            edgePaint.color = edge
            edgePaint.alpha = android.graphics.Color.alpha(edge) * a / 255
            rect.inset(w / 2, w / 2)
            shape(rect, (rad - w / 2).coerceAtLeast(0f))
            canvas.drawPath(path, edgePaint)
        }
    }

    override fun setAlpha(alpha: Int) { a = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 單色線條圖示（24×24 的格子畫，線寬 1.8）。不用 emoji，風格一致。 */
class Icon(private val kind: String, color: Int, private val sizePx: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    init { setBounds(0, 0, sizePx, sizePx) }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    fun tint(c: Int) { p.color = c; fillP.color = c; invalidateSelf() }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        p.strokeWidth = 1.8f
        path.reset()
        fun m(x: Float, y: Float) = path.moveTo(x, y)
        fun l(x: Float, y: Float) = path.lineTo(x, y)
        fun circle(x: Float, y: Float, rad: Float) = c.drawCircle(x, y, rad, p)
        fun rr(l: Float, t: Float, rr: Float, bb: Float, rad: Float) { r.set(l, t, rr, bb); c.drawRoundRect(r, rad, rad, p) }
        when (kind) {
            "back" -> { m(15f, 5f); l(8f, 12f); l(15f, 19f) }
            "forward" -> { m(9f, 5f); l(16f, 12f); l(9f, 19f) }
            "chevron" -> { m(10f, 6f); l(16f, 12f); l(10f, 18f) }
            "up" -> { m(6f, 15f); l(12f, 9f); l(18f, 15f) }
            "down" -> { m(6f, 9f); l(12f, 15f); l(18f, 9f) }
            "close" -> { m(6.5f, 6.5f); l(17.5f, 17.5f); m(17.5f, 6.5f); l(6.5f, 17.5f) }
            "power" -> { r.set(5f, 5.5f, 19f, 19.5f); path.addArc(r, -60f, 300f); m(12f, 3.5f); l(12f, 11.5f) }
            "plus" -> { m(12f, 5f); l(12f, 19f); m(5f, 12f); l(19f, 12f) }
            "minus" -> { m(5f, 12f); l(19f, 12f) }
            "check" -> { m(5f, 12.5f); l(10f, 17.5f); l(19f, 7f) }
            "reload" -> {
                r.set(5f, 5f, 19f, 19f); path.addArc(r, -60f, 300f)
                m(17.2f, 3.6f); l(17.2f, 7.6f); l(13.2f, 7.6f)
            }
            "home" -> { m(4f, 11f); l(12f, 4f); l(20f, 11f); m(6.5f, 9.5f); l(6.5f, 19.5f); l(17.5f, 19.5f); l(17.5f, 9.5f); m(10f, 19.5f); l(10f, 14f); l(14f, 14f); l(14f, 19.5f) }
            "search" -> { circle(10.5f, 10.5f, 6f); m(15f, 15f); l(20f, 20f) }
            "star", "star_on" -> {
                val pts = FloatArray(20)
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) 8.5f else 3.6f
                    val ang = Math.toRadians((-90 + i * 36).toDouble())
                    pts[i * 2] = 12f + (rad * Math.cos(ang)).toFloat(); pts[i * 2 + 1] = 12.8f + (rad * Math.sin(ang)).toFloat()
                }
                m(pts[0], pts[1]); for (i in 1 until 10) l(pts[i * 2], pts[i * 2 + 1]); path.close()
                if (kind == "star_on") c.drawPath(path, fillP)
            }
            "tabs" -> { rr(4f, 7f, 16f, 20f, 2.5f); m(8f, 4f); l(18f, 4f); path.quadTo(20f, 4f, 20f, 6f); l(20f, 16f) }
            "bookmark" -> { m(7f, 4f); l(17f, 4f); l(17f, 20f); l(12f, 16f); l(7f, 20f); path.close() }
            "history" -> { circle(12f, 12f, 8f); m(12f, 7.5f); l(12f, 12f); l(15f, 14f) }
            "pdf" -> { m(6f, 3.5f); l(14f, 3.5f); l(18.5f, 8f); l(18.5f, 20.5f); l(6f, 20.5f); path.close(); m(14f, 3.5f); l(14f, 8f); l(18.5f, 8f); m(9f, 13f); l(15.5f, 13f); m(9f, 16.5f); l(13.5f, 16.5f) }
            "settings" -> {
                circle(12f, 12f, 3f)
                for (i in 0 until 8) {
                    val ang = Math.toRadians((i * 45).toDouble())
                    val x1 = 12f + (6.2f * Math.cos(ang)).toFloat(); val y1 = 12f + (6.2f * Math.sin(ang)).toFloat()
                    val x2 = 12f + (8.6f * Math.cos(ang)).toFloat(); val y2 = 12f + (8.6f * Math.sin(ang)).toFloat()
                    m(x1, y1); l(x2, y2)
                }
                circle(12f, 12f, 6.2f)
            }
            "grid" -> { rr(4f, 4f, 11f, 11f, 2f); rr(13f, 4f, 20f, 11f, 2f); rr(4f, 13f, 11f, 20f, 2f); rr(13f, 13f, 20f, 20f, 2f) }
            "list" -> { rr(3.5f, 5f, 7.5f, 9f, 1.2f); rr(3.5f, 15f, 7.5f, 19f, 1.2f); m(10.5f, 7f); l(20.5f, 7f); m(10.5f, 17f); l(20.5f, 17f) }
            "menu" -> { m(5f, 7f); l(19f, 7f); m(5f, 12f); l(19f, 12f); m(5f, 17f); l(19f, 17f) }
            "script" -> { m(9f, 7f); l(4f, 12f); l(9f, 17f); m(15f, 7f); l(20f, 12f); l(15f, 17f); m(13.5f, 5f); l(10.5f, 19f) }
            "gamepad" -> { rr(2.5f, 7f, 21.5f, 18f, 5.5f); m(7.5f, 10.5f); l(7.5f, 14.5f); m(5.5f, 12.5f); l(9.5f, 12.5f); c.drawCircle(15.5f, 11f, 1.1f, fillP); c.drawCircle(18f, 13.8f, 1.1f, fillP) }
            "keyboard" -> {
                rr(2.5f, 6f, 21.5f, 18f, 2.5f)
                for (y in listOf(9.5f, 12.5f)) for (x in listOf(6f, 9.5f, 13f, 16.5f)) c.drawCircle(x + .5f, y, .9f, fillP)
                m(8f, 15.5f); l(16f, 15.5f)
            }
            "info" -> { circle(12f, 12f, 8.5f); m(12f, 11f); l(12f, 16.5f); c.drawCircle(12f, 7.8f, 1.1f, fillP) }
            "book" -> { m(12f, 6.5f); path.quadTo(8f, 4f, 3.5f, 5f); l(3.5f, 18.5f); path.quadTo(8f, 17.5f, 12f, 20f); path.quadTo(16f, 17.5f, 20.5f, 18.5f); l(20.5f, 5f); path.quadTo(16f, 4f, 12f, 6.5f); l(12f, 20f) }
            "trash" -> { m(4.5f, 7f); l(19.5f, 7f); m(9.5f, 7f); l(9.5f, 4.5f); l(14.5f, 4.5f); l(14.5f, 7f); m(6.5f, 7f); l(7.5f, 20f); l(16.5f, 20f); l(17.5f, 7f) }
            "globe" -> { circle(12f, 12f, 8.5f); m(3.5f, 12f); l(20.5f, 12f); r.set(8f, 3.5f, 16f, 20.5f); c.drawOval(r, p) }
            "language" -> { m(4f, 6f); l(12f, 6f); m(8f, 4f); l(8f, 6f); m(5.5f, 6f); path.quadTo(7f, 12f, 12f, 14f); m(10.5f, 6f); path.quadTo(9f, 12f, 4f, 14.5f); m(12f, 20f); l(16f, 10f); l(20f, 20f); m(13.4f, 16.6f); l(18.6f, 16.6f) }
            "desktop" -> { rr(3f, 4.5f, 21f, 16f, 2f); m(9f, 20f); l(15f, 20f); m(12f, 16f); l(12f, 20f) }
            "phone" -> { rr(7f, 3f, 17f, 21f, 2.5f); m(11f, 18f); l(13f, 18f) }
            "shield" -> { m(12f, 3.5f); l(19.5f, 6.5f); l(19.5f, 11.5f); path.quadTo(19.5f, 17.5f, 12f, 20.5f); path.quadTo(4.5f, 17.5f, 4.5f, 11.5f); l(4.5f, 6.5f); path.close(); m(8.8f, 12f); l(11f, 14.2f); l(15.4f, 9.6f) }
            "layout" -> { rr(4f, 3f, 20f, 21f, 2.5f); m(4f, 12f); l(20f, 12f) }
            "swap" -> { m(7f, 4f); l(7f, 20f); m(4f, 7f); l(7f, 4f); l(10f, 7f); m(17f, 20f); l(17f, 4f); m(14f, 17f); l(17f, 20f); l(20f, 17f) }
            "hinge" -> { rr(4f, 3f, 20f, 10f, 2f); rr(4f, 14f, 20f, 21f, 2f); m(12f, 10f); l(12f, 14f) }
            "sidebar" -> { rr(3f, 4f, 21f, 20f, 2.5f); m(8.5f, 4f); l(8.5f, 20f) }
            "cursor" -> { m(6f, 4f); l(6f, 18f); l(10f, 14.5f); l(13f, 20.5f); l(15.5f, 19.3f); l(12.5f, 13.3f); l(17.5f, 13f); path.close() }
            "scroll" -> { m(12f, 3.5f); l(12f, 20.5f); m(8.5f, 7f); l(12f, 3.5f); l(15.5f, 7f); m(8.5f, 17f); l(12f, 20.5f); l(15.5f, 17f) }
            "text" -> { m(4f, 18f); l(8.5f, 6f); l(13f, 18f); m(5.5f, 14f); l(11.5f, 14f); m(15f, 18f); l(17.5f, 11f); l(20f, 18f); m(15.8f, 15.8f); l(19.2f, 15.8f) }
            "width" -> { m(4f, 12f); l(20f, 12f); m(7f, 9f); l(4f, 12f); l(7f, 15f); m(17f, 9f); l(20f, 12f); l(17f, 15f); m(4f, 5f); l(4f, 19f); m(20f, 5f); l(20f, 19f) }
            "zoom" -> { m(4f, 9f); l(4f, 4f); l(9f, 4f); m(15f, 4f); l(20f, 4f); l(20f, 9f); m(4f, 15f); l(4f, 20f); l(9f, 20f); m(15f, 20f); l(20f, 20f); l(20f, 15f) }
            "fit" -> { m(9f, 4f); l(9f, 9f); l(4f, 9f); m(20f, 9f); l(15f, 9f); l(15f, 4f); m(4f, 15f); l(9f, 15f); l(9f, 20f); m(15f, 20f); l(15f, 15f); l(20f, 15f) }
            "data" -> { r.set(5f, 3.5f, 19f, 8.5f); c.drawOval(r, p); m(5f, 6f); l(5f, 18f); path.quadTo(12f, 22f, 19f, 18f); l(19f, 6f); m(5f, 12f); path.quadTo(12f, 16f, 19f, 12f) }
            "mail" -> { rr(3f, 5.5f, 21f, 18.5f, 2f); m(3.5f, 7f); l(12f, 13f); l(20.5f, 7f) }
            "download" -> { m(12f, 4f); l(12f, 15f); m(7.5f, 10.5f); l(12f, 15f); l(16.5f, 10.5f); m(5f, 19.5f); l(19f, 19.5f) }
            "copy" -> { rr(8f, 8f, 20f, 20f, 2.5f); m(16f, 8f); l(16f, 5.5f); path.quadTo(16f, 4f, 14.5f, 4f); l(5.5f, 4f); path.quadTo(4f, 4f, 4f, 5.5f); l(4f, 14.5f); path.quadTo(4f, 16f, 5.5f, 16f); l(8f, 16f) }
            "share" -> { circle(17.5f, 6f, 2.5f); circle(6.5f, 12f, 2.5f); circle(17.5f, 18f, 2.5f); m(8.7f, 10.8f); l(15.3f, 7.2f); m(8.7f, 13.2f); l(15.3f, 16.8f) }
            "select" -> { rr(4f, 6f, 20f, 18f, 1.5f); m(8f, 10f); l(16f, 10f); m(8f, 14f); l(13f, 14f) }
            "play" -> { m(8f, 5f); l(19f, 12f); l(8f, 19f); path.close() }
            "warn" -> { m(12f, 4f); l(21f, 19.5f); l(3f, 19.5f); path.close(); m(12f, 10f); l(12f, 14f); c.drawCircle(12f, 16.8f, 1f, fillP) }
            "bell" -> { m(6f, 16.5f); l(6f, 11f); path.quadTo(6f, 5f, 12f, 5f); path.quadTo(18f, 5f, 18f, 11f); l(18f, 16.5f); l(19.5f, 18f); l(4.5f, 18f); path.close(); m(10f, 20.5f); l(14f, 20.5f) }
            "volume" -> { m(4f, 9.5f); l(7.5f, 9.5f); l(12f, 5.5f); l(12f, 18.5f); l(7.5f, 14.5f); l(4f, 14.5f); path.close(); m(15.5f, 9f); path.quadTo(17.5f, 12f, 15.5f, 15f); m(18f, 6.5f); path.quadTo(21.5f, 12f, 18f, 17.5f) }
            // 無痕：帽子＋眼鏡
            "incognito" -> {
                m(3f, 11.5f); l(21f, 11.5f)
                m(6f, 11.5f); l(7.6f, 5.5f); path.quadTo(8f, 4.2f, 9.3f, 4.6f); l(12f, 5.6f); l(14.7f, 4.6f); path.quadTo(16f, 4.2f, 16.4f, 5.5f); l(18f, 11.5f)
                circle(7.8f, 17f, 3f); circle(16.2f, 17f, 3f); m(10.8f, 16.6f); path.quadTo(12f, 15.8f, 13.2f, 16.6f)
            }
            "link" -> { m(10f, 14f); l(14f, 10f); m(11f, 7f); l(13f, 5f); path.quadTo(16f, 2.5f, 19f, 5f); path.quadTo(21.5f, 8f, 19f, 11f); l(17f, 13f); m(13f, 17f); l(11f, 19f); path.quadTo(8f, 21.5f, 5f, 19f); path.quadTo(2.5f, 16f, 5f, 13f); l(7f, 11f) }
            "image" -> { rr(3.5f, 5f, 20.5f, 19f, 2.5f); c.drawCircle(9f, 10f, 1.6f, fillP); m(4f, 17f); l(9.5f, 12.5f); l(13f, 15.5f); l(15.5f, 13.5f); l(20f, 17.5f) }
            "touch" -> { m(9f, 13f); l(9f, 5.5f); path.quadTo(9f, 4f, 10.5f, 4f); path.quadTo(12f, 4f, 12f, 5.5f); l(12f, 11f); l(17f, 12f); path.quadTo(19f, 12.5f, 18.5f, 14.5f); l(17.5f, 20f); l(10f, 20f); l(6f, 15.5f); path.quadTo(5f, 14f, 6.5f, 13f); l(9f, 14.5f) }
        }
        c.drawPath(path, p)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; fillP.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter; fillP.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 主題選擇用的小圖：一台迷你 RP Duo（上下兩塊機身，用該主題的機殼色，螢幕裡有主色的光）。 */
class DuoSwatch(private val p: Palette, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 機身外緣一圈淡亮邊：深色機身（曜黑）放在深色底上也看得出輪廓 */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density
        color = (p.rim and 0x00FFFFFF) or 0x99000000.toInt()
    }
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        // 比例照 RP Duo 實機：上下機身一樣寬；上螢幕 16:9 幾乎填滿上半部；下螢幕 4:3 在中間、兩側放按鍵和搖桿。
        // 所有尺寸都用機身寬度 u 的比例，整台機器的高寬比固定（約 1 : 1.10），在格子裡置中
        val topH0 = 0.55f; val gap0 = 0.02f; val botH0 = 0.53f      // 以 u 為單位
        val total = topH0 + gap0 + botH0
        val u = minOf(w * 0.94f, h / total)
        val x0 = b.left + (w - u) / 2
        val yTop = b.top + (h - u * total) / 2
        val topH = u * topH0
        val y1 = yTop + topH + u * gap0
        val corner = u * 0.07f
        // 螢幕（熄燈）：介面底色是深色時用它；淺色介面（16 Bit）用固定的深色
        val screen = if (lumaOf(p.bg) > 0.5f) 0xFF1C1A18.toInt() else p.bg
        // 上機身
        r.set(x0, yTop, x0 + u, yTop + topH)
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, p.bodyHi, p.bodyLo, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, corner, corner, paint)
        paint.shader = null
        c.drawRoundRect(r, corner, corner, rimPaint)
        // 上螢幕 16:9：寬 82%（高 = 寬 × 9/16），在上機身裡置中
        val tsw = u * 0.82f; val tsh = tsw * 9f / 16f
        val tsy = yTop + (topH - tsh) / 2
        r.set(x0 + (u - tsw) / 2, tsy, x0 + (u + tsw) / 2, tsy + tsh)
        paint.color = screen; c.drawRoundRect(r, u * 0.02f, u * 0.02f, paint)
        paint.shader = android.graphics.RadialGradient(r.centerX(), r.bottom, r.height(), p.accentDeep and 0x00FFFFFF or 0x99000000.toInt(),
            p.accentDeep and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, u * 0.02f, u * 0.02f, paint); paint.shader = null
        // 下機身（和上面一樣寬）
        val bh = u * botH0
        r.set(x0, y1, x0 + u, y1 + bh)
        paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom, p.bodyHi, p.bodyLo, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, corner, corner, paint)
        paint.shader = null
        c.drawRoundRect(r, corner, corner, rimPaint)
        // 下螢幕 4:3：寬 44%（高 = 寬 × 3/4），在中間、靠上
        val bsw = u * 0.56f; val bsh = bsw * 3f / 4f
        val bsy = y1 + u * 0.04f
        r.set(x0 + (u - bsw) / 2, bsy, x0 + (u + bsw) / 2, bsy + bsh)
        paint.color = screen; c.drawRoundRect(r, u * 0.015f, u * 0.015f, paint)
        val lx = x0 + u * 0.11f; val rx = x0 + u * 0.89f
        // 十字鍵／ABXY 和螢幕上半對齊，搖桿在螢幕下緣附近
        val upY = bsy + bsh * 0.28f; val lowY = bsy + bsh * 0.75f
        // 按鍵的基本顏色：淺色機身用深色、深色機身（曜黑）用淺灰，才看得到
        val dark = lumaOf(p.bodyLo) < 0.3f
        // 冰藍、透紫的實機：十字鍵和 ABXY 是比機身淺一點的同色系（外圍一圈機身深色，才看得出形狀）
        val pastel = p.id == "ice" || p.id == "purple"
        val ink = when {
            pastel -> mixColor(p.bodyHi, 0xFFFFFFFF.toInt(), 0.45f)
            dark -> p.text3
            else -> 0xFF3A3940.toInt()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.9f * density; color = mixColor(p.bodyLo, 0xFF000000.toInt(), 0.25f) }
        // 十字鍵（左上）
        paint.color = ink
        val arm = u * 0.065f; val th = u * 0.022f
        if (pastel) {
            c.drawRoundRect(lx - arm, upY - th, lx + arm, upY + th, th * 0.4f, th * 0.4f, outline)
            c.drawRoundRect(lx - th, upY - arm, lx + th, upY + arm, th * 0.4f, th * 0.4f, outline)
        }
        c.drawRoundRect(lx - arm, upY - th, lx + arm, upY + th, th * 0.4f, th * 0.4f, paint)
        c.drawRoundRect(lx - th, upY - arm, lx + th, upY + arm, th * 0.4f, th * 0.4f, paint)
        // ABXY（右上，菱形：X 上、A 右、B 下、Y 左），彩色機身用自己的按鍵顏色
        val kc = p.keyColors
        val off = u * 0.055f; val kr = u * 0.028f
        listOf("X" to (0f to -off), "A" to (off to 0f), "B" to (0f to off), "Y" to (-off to 0f)).forEach { (k, o) ->
            paint.color = kc?.get(k) ?: ink
            c.drawCircle(rx + o.first, upY + o.second, kr, paint)
            if (pastel && kc == null) c.drawCircle(rx + o.first, upY + o.second, kr, outline)
        }
        // 兩個類比搖桿（左下、右下）：實心圓。曜黑機身用淺灰才看得到；GC 的右搖桿是黃色
        val stickR = u * 0.07f
        val stick = if (dark) 0xFF8C8C96.toInt() else 0xFF45444C.toInt()
        paint.color = stick
        c.drawCircle(lx, lowY, stickR, paint)
        paint.color = if (p.id == "gc") 0xFFF4C534.toInt() else stick
        c.drawCircle(rx, lowY, stickR, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun lumaOf(c: Int) = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

/** 兩個顏色（含透明度）依 f 混合 */
internal fun mixColor(a: Int, b: Int, f: Float): Int {
    val ff = f.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - ff) + ((b shr s) and 0xFF) * ff).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/** 開關（只負責顯示；點擊由整列處理）。animateTo 會滑動圓鈕、漸變顏色。 */
class ToggleView(ctx: Context, on: Boolean) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var t = if (on) 1f else 0f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    var on = on; private set

    init { isFocusable = false; isClickable = false }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * d).toInt(), (30 * d).toInt())

    fun animateTo(v: Boolean) {
        on = v
        anim?.cancel()
        anim = ValueAnimator.ofFloat(t, if (v) 1f else 0f).apply {
            duration = 240; interpolator = T.SPRING
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - ff) + ((b shr s) and 0xFF) * ff).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        // 關：淡紫軌道；開：殼邊緣的飽和紫。圓鈕一律是亮色，不會開關時顏色反過來
        track.color = mix(0x26FFFFFF, T.ACCENT_DEEP, t)
        c.drawRoundRect(rect, h / 2, h / 2, track)
        ring.strokeWidth = d; ring.color = mix(T.EDGE_STRONG, 0x00000000, t)
        rect.inset(d / 2, d / 2); c.drawRoundRect(rect, h / 2, h / 2, ring)
        val r = h / 2 - 3.5f * d
        val x = h / 2 + (w - h) * t
        knob.color = mix(T.TEXT2, 0xFFFFFFFF.toInt(), t)
        c.drawCircle(x, h / 2, r, knob)
    }
}

/** 動畫小工具 */
object Motion {
    /** 按下縮小、放開彈回；手把焦點放大一點並淡淡浮起。不影響原本的點擊。 */
    @SuppressLint("ClickableViewAccessibility")
    fun polish(v: View, focusGrow: Boolean = true) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(110).setInterpolator(T.EASE).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val s = if (view.isFocused) grow(view) else 1f
                    view.animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(T.SPRING).start()
                }
            }
            false
        }
        if (focusGrow) v.setOnFocusChangeListener { view, has ->
            // 手把移動焦點用 requestFocusFromTouch，觸控模式下也會有焦點，所以不看 isInTouchMode
            val s = if (has) grow(view) else 1f
            view.animate().scaleX(s).scaleY(s).translationZ(if (has) 6f * view.resources.displayMetrics.density else 0f)
                .setDuration(T.DUR).setInterpolator(T.EASE).start()
        }
    }

    /** 放大比例：小按鈕 6%，寬的列只多出約 10dp，不會撐出畫面 */
    private fun grow(v: View): Float {
        val w = v.width.coerceAtLeast(1)
        return (1f + 10f * v.resources.displayMetrics.density / w).coerceAtMost(1.06f)
    }

    /** 頁面內容依序淡入、往上移一點（只做前幾個，長清單不會拖慢） */
    fun stagger(g: ViewGroup, max: Int = 14) {
        val d = g.resources.displayMetrics.density
        for (i in 0 until min(g.childCount, max)) {
            val c = g.getChildAt(i)
            c.alpha = 0f; c.translationY = 12f * d
            c.animate().alpha(1f).translationY(0f).setStartDelay(i * 22L).setDuration(T.DUR + 60).setInterpolator(T.EASE).start()
        }
    }

    /** 從下面淡入滑上來 */
    fun rise(v: View, distDp: Float = 20f, dur: Long = T.DUR_PANEL) {
        val d = v.resources.displayMetrics.density
        v.animate().cancel()
        v.alpha = 0f; v.translationY = distDp * d
        v.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(dur).setInterpolator(T.EASE).start()
    }

    /** 從上面淡入滑下來（上屏的提示） */
    fun drop(v: View, distDp: Float = 12f) = rise(v, -distDp, T.DUR)
}
