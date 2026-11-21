package com.duo.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 按鍵設定用的手把圖：照 RP Duo 下半部的按鍵位置畫出機身，
 * 每個按鍵位置放一個按鈕（按鍵名稱 + 目前功能），點它（或用十字鍵選到再按確定）就能改功能。
 *
 * 位置都用「寬、高的比例」描述，所以在不同大小的螢幕上都能正確排列。
 */
class PadDiagram(
    ctx: Context,
    /** 每個按鍵目前的功能名稱 */
    private val actionLabel: (Int) -> String,
    /** 左右搖桿目前的功能（顯示用，搖桿本身另外用開關切換） */
    private val leftStickLabel: String,
    private val rightStickLabel: String,
    /** 建立按鈕（沿用瀏覽器介面的按鈕樣式） */
    makeButton: (String, () -> Unit) -> View,
    /** 點了某個按鍵 */
    onPick: (code: Int, name: String) -> Unit,
) : ViewGroup(ctx) {

    private data class Spot(val code: Int, val name: String, val fx: Float, val fy: Float)

    /** 按鍵位置（中心點，佔寬/高的比例）：左邊十字鍵、右邊 ABXY（X 上、Y 左、A 右、B 下），上方肩鍵 */
    private val spots = listOf(
        Spot(KeyEvent.KEYCODE_BUTTON_L2, "L2", 0.10f, 0.07f),
        Spot(KeyEvent.KEYCODE_BUTTON_L1, "L1", 0.26f, 0.07f),
        Spot(KeyEvent.KEYCODE_BUTTON_R1, "R1", 0.74f, 0.07f),
        Spot(KeyEvent.KEYCODE_BUTTON_R2, "R2", 0.90f, 0.07f),

        Spot(KeyEvent.KEYCODE_DPAD_UP, "↑", 0.18f, 0.27f),
        Spot(KeyEvent.KEYCODE_DPAD_LEFT, "←", 0.07f, 0.41f),
        Spot(KeyEvent.KEYCODE_DPAD_RIGHT, "→", 0.29f, 0.41f),
        Spot(KeyEvent.KEYCODE_DPAD_DOWN, "↓", 0.18f, 0.55f),

        Spot(KeyEvent.KEYCODE_BUTTON_X, "X", 0.82f, 0.27f),
        Spot(KeyEvent.KEYCODE_BUTTON_Y, "Y", 0.71f, 0.41f),
        Spot(KeyEvent.KEYCODE_BUTTON_A, "A", 0.93f, 0.41f),
        Spot(KeyEvent.KEYCODE_BUTTON_B, "B", 0.82f, 0.55f),

        Spot(KeyEvent.KEYCODE_BUTTON_SELECT, "Select", 0.40f, 0.56f),
        Spot(KeyEvent.KEYCODE_BUTTON_START, "Start", 0.60f, 0.56f),

        // 按下搖桿 = L3 / R3，按鈕放在搖桿圓心
        Spot(KeyEvent.KEYCODE_BUTTON_THUMBL, "L3", 0.20f, 0.83f),
        Spot(KeyEvent.KEYCODE_BUTTON_THUMBR, "R3", 0.80f, 0.83f),
    )

    private val density = ctx.resources.displayMetrics.density
    // 機身用紫透色機殼的顏色（Theme.kt）
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.SURFACE }
    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = T.SURFACE_HI }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = T.EDGE_STRONG; style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = T.TEXT3; textSize = 12f * density; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        spots.forEach { s ->
            val b = makeButton("${s.name}\n${actionLabel(s.code)}") { onPick(s.code, s.name) }
            (b as? android.widget.TextView)?.apply {
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                val p = (4 * density).roundToInt()
                setPadding(p, p / 2, p, p / 2)
            }
            addView(b)
        }
    }

    private fun bw() = (width * 0.15f).roundToInt()
    private fun bh() = (height * 0.12f).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.58f).roundToInt()
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec((w * 0.15f).roundToInt(), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec((h * 0.12f).roundToInt(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        spots.forEachIndexed { i, s ->
            val c = getChildAt(i)
            val cx = (s.fx * w).roundToInt()
            val cy = (s.fy * h).roundToInt()
            val left = (cx - c.measuredWidth / 2).coerceIn(0, w - c.measuredWidth)
            val top = (cy - c.measuredHeight / 2).coerceIn(0, h - c.measuredHeight)
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 機身
        rect.set(w * 0.01f, h * 0.15f, w * 0.99f, h * 0.99f)
        canvas.drawRoundRect(rect, h * 0.12f, h * 0.12f, bodyPaint)
        // 肩鍵區
        rect.set(w * 0.03f, h * 0.0f, w * 0.34f, h * 0.15f)
        canvas.drawRoundRect(rect, h * 0.05f, h * 0.05f, partPaint)
        rect.set(w * 0.66f, h * 0.0f, w * 0.97f, h * 0.15f)
        canvas.drawRoundRect(rect, h * 0.05f, h * 0.05f, partPaint)
        // 十字鍵
        val dx = w * 0.18f; val dy = h * 0.41f; val arm = min(w * 0.035f, h * 0.06f); val len = h * 0.17f
        rect.set(dx - arm, dy - len, dx + arm, dy + len); canvas.drawRoundRect(rect, arm / 2, arm / 2, partPaint)
        rect.set(dx - len * 1.2f, dy - arm, dx + len * 1.2f, dy + arm); canvas.drawRoundRect(rect, arm / 2, arm / 2, partPaint)
        // ABXY 底座
        canvas.drawCircle(w * 0.82f, h * 0.41f, h * 0.2f, linePaint)
        // 中間的螢幕位置示意
        rect.set(w * 0.36f, h * 0.2f, w * 0.64f, h * 0.48f)
        canvas.drawRoundRect(rect, h * 0.02f, h * 0.02f, linePaint)
        canvas.drawText(L.t("下螢幕", "下屏幕", "Bottom screen"), w * 0.5f, h * 0.36f, textPaint)
        // 搖桿
        canvas.drawCircle(w * 0.20f, h * 0.83f, h * 0.13f, partPaint)
        canvas.drawCircle(w * 0.80f, h * 0.83f, h * 0.13f, partPaint)
        canvas.drawText(L.t("左搖桿：", "左摇杆：", "Left stick: ") + leftStickLabel, w * 0.20f, h * 0.67f, textPaint)
        canvas.drawText(L.t("右搖桿：", "右摇杆：", "Right stick: ") + rightStickLabel, w * 0.80f, h * 0.67f, textPaint)
    }
}
