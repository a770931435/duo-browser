package com.duo.browser

import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * 手把輸入 → 功能（PadAction）。按鍵對應可在設定中自訂（見 KeyMap / Prefs.keyMap）。
 * 固定不可改的部分：左搖桿 = 游標、右搖桿 = 捲動、系統返回鍵 = 返回。
 * 十字鍵的 HAT 軸與 L2/R2 類比扳機都會轉成「虛擬按鍵」，一樣套用自訂對應。
 */
class GamepadController : Choreographer.FrameCallback {
    private var map: Map<Int, PadAction> = Session.prefs.keyMap()

    private var lx = 0f; private var ly = 0f
    private var rx = 0f; private var ry = 0f
    private var hatX = 0; private var hatY = 0
    private var ltOn = false; private var rtOn = false
    private val heldScroll = HashSet<PadAction>()
    private var clickHeld = false
    private val lastFire = HashMap<PadAction, Long>()
    private var looping = false
    private var lastFrameNs = 0L

    fun reloadMap() {
        releaseAll()
        map = Session.prefs.keyMap()
    }

    fun actionFor(code: Int): PadAction = when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER -> PadAction.CLICK
        KeyEvent.KEYCODE_MENU -> PadAction.MENU
        else -> map[code] ?: PadAction.NONE
    }

    /** 給說明文字用：目前哪些按鍵對應到某個功能。 */
    fun keysFor(a: PadAction): String =
        KeyMap.BUTTONS.filter { map[it.first] == a }.joinToString("/") { it.second }

    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) Session.onBackAction()
            return true
        }
        if (!isPadKey(e)) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount > 0) return true
        handleButton(e.keyCode, down)
        return true
    }

    private fun handleButton(code: Int, down: Boolean) {
        val action = actionFor(code)
        val ui = Session.ui
        if (ui != null && ui.panelOpen) {
            releaseAll()
            ui.onPadKey(code, down, action)
            return
        }
        when {
            action == PadAction.CLICK -> {
                if (down) {
                    clickHeld = true; Session.engine.padDown()
                } else if (clickHeld) {
                    clickHeld = false; Session.engine.padUp()
                }
            }
            action.held -> if (down) heldScroll.add(action) else heldScroll.remove(action)
            down -> fire(action)
        }
        ensureLoop()
    }

    private fun fire(a: PadAction) {
        // 有些裝置同一個按鍵會同時送出按鍵事件和類比軸（十字鍵/扳機），避免觸發兩次
        val now = SystemClock.uptimeMillis()
        if (now - (lastFire[a] ?: 0L) < 120) return
        lastFire[a] = now

        val b = Session.browser
        val eng = Session.engine
        when (a) {
            PadAction.NONE, PadAction.CLICK, PadAction.SCROLL_UP, PadAction.SCROLL_DOWN,
            PadAction.SCROLL_LEFT, PadAction.SCROLL_RIGHT -> {}
            PadAction.BACK -> Session.onBackAction()
            PadAction.FORWARD -> b?.forward()
            PadAction.RELOAD -> b?.reload()
            PadAction.PREV_TAB -> b?.switchTab(-1)
            PadAction.NEXT_TAB -> b?.switchTab(1)
            PadAction.NEW_TAB -> b?.newTab(null, switch = true)
            PadAction.CLOSE_TAB -> b?.let { it.closeTab(it.current) }
            PadAction.MENU -> Session.ui?.openMenu(fromPad = true, focusUrl = false)
            PadAction.URL_BAR -> Session.ui?.openMenu(fromPad = true, focusUrl = true)
            PadAction.ZOOM_OUT -> b?.zoomText(-10)
            PadAction.ZOOM_IN -> b?.zoomText(10)
            PadAction.PAGE_UP -> b?.web?.pageUp(false)
            PadAction.PAGE_DOWN -> b?.web?.pageDown(false)
            PadAction.SCROLL_TOP -> { eng.resetShift(); b?.web?.pageUp(true) }
            PadAction.TOGGLE_CURSOR -> {
                eng.cursorVisible = !eng.cursorVisible
                Session.invalidateScreens()
            }
            PadAction.CENTER_CURSOR -> eng.centerCursor()
            PadAction.TOGGLE_BOTTOM_MODE -> {
                Session.ui?.toggleBottomMode() ?: run { Session.prefs.bottomCrop = !Session.prefs.bottomCrop }
                Session.applyBottomMode()
            }
            PadAction.TOGGLE_DESKTOP -> b?.let { it.setDesktop(!it.isDesktop) }
        }
    }

    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            e.actionMasked != MotionEvent.ACTION_MOVE
        ) return false

        // lx/ly = 游標用的搖桿、rx/ry = 捲動用的搖桿；「左右搖桿交換」時把實體左右對調
        val swap = Session.prefs.swapSticks
        val cursorAxX = if (swap) MotionEvent.AXIS_Z else MotionEvent.AXIS_X
        val cursorAxY = if (swap) MotionEvent.AXIS_RZ else MotionEvent.AXIS_Y
        val scrollAxX = if (swap) MotionEvent.AXIS_X else MotionEvent.AXIS_Z
        val scrollAxY = if (swap) MotionEvent.AXIS_Y else MotionEvent.AXIS_RZ
        val nlx = dz(e.getAxisValue(cursorAxX))
        val nly = dz(e.getAxisValue(cursorAxY))
        val nhx = tri(e.getAxisValue(MotionEvent.AXIS_HAT_X))
        val nhy = tri(e.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val lt = max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))

        // 十字鍵 HAT → 虛擬方向鍵
        hatEdge(nhx, hatX, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT); hatX = nhx
        hatEdge(nhy, hatY, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN); hatY = nhy
        // 類比扳機 → 虛擬 L2/R2
        if (lt > 0.5f && !ltOn) { ltOn = true; handleButton(KeyEvent.KEYCODE_BUTTON_L2, true) }
        else if (lt < 0.3f && ltOn) { ltOn = false; handleButton(KeyEvent.KEYCODE_BUTTON_L2, false) }
        if (rt > 0.5f && !rtOn) { rtOn = true; handleButton(KeyEvent.KEYCODE_BUTTON_R2, true) }
        else if (rt < 0.3f && rtOn) { rtOn = false; handleButton(KeyEvent.KEYCODE_BUTTON_R2, false) }

        val ui = Session.ui
        if (ui != null && ui.panelOpen) {
            // 選單開著時，左搖桿推到底也能移動焦點
            stickNav(nlx, lx, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, ui)
            stickNav(nly, ly, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, ui)
            lx = nlx; ly = nly; rx = 0f; ry = 0f
            return true
        }

        lx = nlx; ly = nly
        rx = dz(e.getAxisValue(scrollAxX))
        ry = dz(e.getAxisValue(scrollAxY))
        ensureLoop()
        return true
    }

    private fun hatEdge(n: Int, o: Int, neg: Int, pos: Int) {
        if (n == o) return
        if (o == -1) handleButton(neg, false) else if (o == 1) handleButton(pos, false)
        if (n == -1) handleButton(neg, true) else if (n == 1) handleButton(pos, true)
    }

    private fun stickNav(n: Float, o: Float, neg: Int, pos: Int, ui: BrowserUi) {
        val nn = if (abs(n) > 0.6f) sign(n) else 0f
        val oo = if (abs(o) > 0.6f) sign(o) else 0f
        if (nn != 0f && nn != oo) ui.onPadKey(if (nn < 0) neg else pos, true, PadAction.NONE)
    }

    private fun releaseAll() {
        if (clickHeld) { clickHeld = false; Session.engine.padUp() }
        heldScroll.clear()
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
    }

    fun stop() {
        releaseAll()
        if (looping) Choreographer.getInstance().removeFrameCallback(this)
        looping = false
    }

    private fun ensureLoop() {
        if (!looping) {
            looping = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        looping = false
        val dt = if (lastFrameNs == 0L) 1f else ((frameTimeNanos - lastFrameNs) / 16_666_667f).coerceIn(0.2f, 3f)
        lastFrameNs = frameTimeNanos
        val eng = Session.engine
        if (!eng.started) return
        var active = false

        if (lx != 0f || ly != 0f) {
            val sp = Session.prefs.cursorSpeed * eng.vw / 1920f * 26f * dt
            eng.moveCursor(curve(lx) * sp, curve(ly) * sp)
            if (clickHeld) eng.padMove() else eng.hover()
            Session.invalidateScreens()
            active = true
        }

        fun h(a: PadAction) = if (a in heldScroll) 1f else 0f
        val sv = (curve(ry) + h(PadAction.SCROLL_DOWN) - h(PadAction.SCROLL_UP)).coerceIn(-1f, 1f)
        val sh = (curve(rx) + h(PadAction.SCROLL_RIGHT) - h(PadAction.SCROLL_LEFT)).coerceIn(-1f, 1f)
        if (sv != 0f || sh != 0f) {
            val k = 0.35f * Session.prefs.scrollSpeed * dt
            // AXIS_VSCROLL 正值 = 往上捲，所以往下要取負
            eng.wheel(sh * k, -sv * k)
            active = true
        }

        if (active || clickHeld) ensureLoop() else lastFrameNs = 0L
    }

    private fun dz(v: Float): Float {
        val a = abs(v)
        return if (a < 0.15f) 0f else sign(v) * (a - 0.15f) / 0.85f
    }

    private fun tri(v: Float) = if (v < -0.5f) -1 else if (v > 0.5f) 1 else 0

    private fun curve(v: Float) = v * abs(v)

    private fun isPadKey(e: KeyEvent): Boolean {
        if (e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        val code = e.keyCode
        if (KeyEvent.isGamepadButton(code)) return true
        val src = e.source
        val fromPad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        return fromPad && when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }
}
