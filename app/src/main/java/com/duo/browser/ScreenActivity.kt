package com.duo.browser

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * 一個實體螢幕上的畫面。每個螢幕各自一個（各自的 task），
 * 所以其中一個回到桌面時，另一個仍是前景、可以繼續操作。
 * 只有一個實體螢幕時，這個畫面會上下分割成兩塊來模擬雙屏。
 */
class ScreenActivity : Activity() {
    var displayKey = 0
        private set
    /** 雙螢幕裝置：這個螢幕的唯一畫面 */
    var host: ScreenHost? = null
        private set
    /** 單螢幕裝置（分割模擬）：上下兩塊 */
    var topHost: ScreenHost? = null
        private set
    var bottomHost: ScreenHost? = null
        private set

    fun allHosts(): List<ScreenHost> = listOfNotNull(host, topHost, bottomHost)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        Session.ensureDetected()
        displayKey = displayCompat().displayId
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        volumeControlStream = AudioManager.STREAM_MUSIC
        val dpi = resources.displayMetrics.densityDpi

        if (Session.splitMode) {
            val t = ScreenHost(this)
            val b = ScreenHost(this)
            t.onSize = { w, h -> Session.reportSize(Session.SPLIT_TOP, w, h, dpi) }
            b.onSize = { w, h -> Session.reportSize(Session.SPLIT_BOTTOM, w, h, dpi) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 9f / 16f))
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f / 4f))
            setContentView(col)
            topHost = t; bottomHost = b
        } else {
            val h = ScreenHost(this)
            h.onSize = { w, hh -> Session.reportSize(displayKey, w, hh, dpi) }
            setContentView(h)
            host = h
        }
        hideSystemBars(window)
        Session.register(this)
    }

    override fun onStart() {
        super.onStart()
        Session.setVisible(this, true)
    }

    override fun onStop() {
        Session.setVisible(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        Session.unregister(this, removedByUser = isFinishing && !isChangingConfigurations && !finishedBySession)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(window)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Session.lastInput = this
        Session.inputFromPresentation = false
        if (Session.handleVolumeKey(event)) return true
        return Session.gamepad.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        Session.lastInput = this
        Session.inputFromPresentation = false
        return Session.gamepad.onMotion(ev) || super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Session.lastInput = this
        Session.inputFromPresentation = false
        return super.dispatchTouchEvent(ev)
    }

    /** 由程式自己關掉（例如回到雙屏時收掉下屏 Activity），不是使用者滑掉。 */
    var finishedBySession = false

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (Session.onActivityResult(requestCode, resultCode, data)) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }
}
