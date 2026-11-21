package com.duo.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 一個實體螢幕上的畫面：從虛擬顯示器的畫格中裁出自己負責的區域來畫，
 * 並把觸控座標換算回虛擬顯示器座標交給 SpanEngine。
 */
class ScreenView(ctx: Context) : View(ctx) {
    var engine: SpanEngine? = null
    /** 這個螢幕在虛擬顯示器上對應的區域。 */
    val region = RectF()

    private val src = Rect()
    private val dst = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path().apply {
        val s = density * 1.3f
        moveTo(0f, 0f); lineTo(0f, 17f * s); lineTo(4.5f * s, 13f * s); lineTo(7.5f * s, 20f * s)
        lineTo(10.5f * s, 18.8f * s); lineTo(7.5f * s, 12f * s); lineTo(13f * s, 12f * s); close()
    }

    fun attach(e: SpanEngine, r: RectF) {
        engine = e
        region.set(r)
        invalidate()
    }

    fun detach() {
        engine = null
        region.setEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val e = engine
        val frame = e?.frame
        if (e == null || frame == null) {
            // 啟動中（網頁畫面還沒出來）：顯示品牌 logo
            drawLogo(canvas)
            return
        }
        if (region.width() > 0f && width > 0) {
            region.roundOut(src)
            dst.set(0, 0, width, height)
            canvas.drawBitmap(frame, src, dst, paint)
        }
        if (e.cursorVisible && region.contains(e.cursorX, e.cursorY)) {
            val x = (e.cursorX - region.left) * width / region.width()
            val y = (e.cursorY - region.top) * height / region.height()
            canvas.save()
            canvas.translate(x, y)
            canvas.drawPath(arrow, cursorFill)
            canvas.drawPath(arrow, cursorStroke)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        engine?.onScreenTouch(this, event)
        return true
    }

    private val logoDst = RectF()

    private fun drawLogo(canvas: Canvas) {
        val logo = logo(context) ?: return
        val s = minOf(width, height) * 0.42f
        logoDst.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
        canvas.drawBitmap(logo, null, logoDst, paint)
    }

    companion object {
        private var logoBmp: android.graphics.Bitmap? = null
        fun logo(ctx: Context): android.graphics.Bitmap? {
            if (logoBmp == null) logoBmp = runCatching {
                android.graphics.BitmapFactory.decodeResource(ctx.applicationContext.resources, R.drawable.cyt_logo)
            }.getOrNull()
            return logoBmp
        }
    }
}

/** 螢幕容器：底下是 ScreenView，上面是給瀏覽器 UI 用的覆蓋層。 */
class ScreenHost(ctx: Context) : FrameLayout(ctx) {
    val screen = ScreenView(ctx)
    val overlay = FrameLayout(ctx)
    /** 量到實際大小時通知（用來決定虛擬顯示器的比例）。 */
    var onSize: ((Int, Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSize?.invoke(w, h)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(screen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/**
 * 雙屏模式時下屏用的 Presentation（用 Application context 建立，不屬於任何 Activity）。
 *
 * 為什麼雙屏時不用 Activity：RP Duo 系統只要看到 App 在下屏有 Activity，
 * 就把它的聲音當成「下屏聲音」，乘上另一組下屏音量（secondary_screen_volume_level），
 * 造成音量條和實際音量對不起來。用 Presentation 就不會被當成下屏 App。
 * 上屏回到桌面時，Session 會改用下屏的 ScreenActivity（單屏模式）。
 */
class ScreenPresentation(ctx: Context, display: Display) :
    Presentation(ctx, display, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    lateinit var host: ScreenHost
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = ScreenHost(context)
        val id = display.displayId
        val dpi = context.resources.displayMetrics.densityDpi
        host.onSize = { w, h -> Session.reportSize(id, w, h, dpi) }
        setContentView(host)
        setCancelable(false)
        window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let { hideSystemBars(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Session.inputFromPresentation = true
        if (Session.handleVolumeKey(event)) return true
        return Session.gamepad.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        Session.inputFromPresentation = true
        return Session.gamepad.onMotion(ev) || super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Session.inputFromPresentation = true
        return super.dispatchTouchEvent(ev)
    }
}

fun hideSystemBars(w: Window) {
    if (Build.VERSION.SDK_INT >= 30) {
        w.setDecorFitsSystemWindows(false)
        w.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
    w.attributes = w.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
