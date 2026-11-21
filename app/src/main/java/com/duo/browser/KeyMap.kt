package com.duo.browser

import android.view.KeyEvent

/** 可以指定給實體按鍵的功能。held = 按住期間持續作用（點擊拖曳、捲動）。 */
enum class PadAction(private val tw: String, private val cn: String, private val en: String, val held: Boolean = false) {
    NONE("（無）", "（无）", "(None)"),
    CLICK("點擊 / 確定", "点击 / 确定", "Click / OK", held = true),
    BACK("返回 / 上一頁", "返回 / 上一页", "Back"),
    FORWARD("下一頁", "下一页", "Forward"),
    RELOAD("重新整理", "刷新", "Reload"),
    PREV_TAB("上一個分頁", "上一个标签页", "Previous tab"),
    NEXT_TAB("下一個分頁", "下一个标签页", "Next tab"),
    NEW_TAB("新分頁", "新标签页", "New tab"),
    CLOSE_TAB("關閉分頁", "关闭标签页", "Close tab"),
    MENU("選單", "菜单", "Menu"),
    URL_BAR("網址列", "地址栏", "Address bar"),
    ZOOM_OUT("文字縮小", "文字缩小", "Text smaller"),
    ZOOM_IN("文字放大", "文字放大", "Text larger"),
    SCROLL_UP("往上捲", "向上滚动", "Scroll up", held = true),
    SCROLL_DOWN("往下捲", "向下滚动", "Scroll down", held = true),
    SCROLL_LEFT("往左捲", "向左滚动", "Scroll left", held = true),
    SCROLL_RIGHT("往右捲", "向右滚动", "Scroll right", held = true),
    PAGE_UP("往上翻一整頁", "向上翻一整页", "Page up"),
    PAGE_DOWN("往下翻一整頁", "向下翻一整页", "Page down"),
    SCROLL_TOP("回到頁首", "回到页首", "Back to top"),
    TOGGLE_CURSOR("游標顯示/隱藏", "光标显示/隐藏", "Show/hide cursor"),
    CENTER_CURSOR("游標置中", "光标居中", "Center cursor"),
    TOGGLE_DESKTOP("電腦版/手機版切換", "电脑版/手机版切换", "Desktop/mobile site"),
    TOGGLE_BOTTOM_MODE("下屏模式切換", "下屏模式切换", "Bottom mode"),
    ;

    val label: String get() = L.t(tw, cn, en)
}

object KeyMap {
    /** 可設定的實體按鍵（keycode 到顯示名稱，名稱依介面語言）。 */
    val BUTTONS: List<Pair<Int, String>>
        get() {
            val dpad = L.t("十字鍵", "十字键", "D-pad")
            return listOf(
                KeyEvent.KEYCODE_BUTTON_A to "A",
                KeyEvent.KEYCODE_BUTTON_B to "B",
                KeyEvent.KEYCODE_BUTTON_X to "X",
                KeyEvent.KEYCODE_BUTTON_Y to "Y",
                KeyEvent.KEYCODE_BUTTON_L1 to "L1",
                KeyEvent.KEYCODE_BUTTON_R1 to "R1",
                KeyEvent.KEYCODE_BUTTON_L2 to "L2",
                KeyEvent.KEYCODE_BUTTON_R2 to "R2",
                KeyEvent.KEYCODE_BUTTON_START to "Start",
                KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
                KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
                KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
                KeyEvent.KEYCODE_DPAD_UP to "$dpad ↑",
                KeyEvent.KEYCODE_DPAD_DOWN to "$dpad ↓",
                KeyEvent.KEYCODE_DPAD_LEFT to "$dpad ←",
                KeyEvent.KEYCODE_DPAD_RIGHT to "$dpad →",
            )
        }

    val DEFAULTS: Map<Int, PadAction> = mapOf(
        // A 確定、B 上一頁
        KeyEvent.KEYCODE_BUTTON_A to PadAction.CLICK,
        KeyEvent.KEYCODE_BUTTON_B to PadAction.BACK,
        KeyEvent.KEYCODE_BUTTON_X to PadAction.URL_BAR,
        KeyEvent.KEYCODE_BUTTON_Y to PadAction.RELOAD,
        KeyEvent.KEYCODE_BUTTON_L1 to PadAction.PREV_TAB,
        KeyEvent.KEYCODE_BUTTON_R1 to PadAction.NEXT_TAB,
        KeyEvent.KEYCODE_BUTTON_L2 to PadAction.ZOOM_OUT,
        KeyEvent.KEYCODE_BUTTON_R2 to PadAction.ZOOM_IN,
        KeyEvent.KEYCODE_BUTTON_START to PadAction.MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to PadAction.TOGGLE_BOTTOM_MODE,
        KeyEvent.KEYCODE_BUTTON_THUMBL to PadAction.CENTER_CURSOR,
        KeyEvent.KEYCODE_BUTTON_THUMBR to PadAction.SCROLL_TOP,
        KeyEvent.KEYCODE_DPAD_UP to PadAction.SCROLL_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to PadAction.SCROLL_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to PadAction.BACK,
        KeyEvent.KEYCODE_DPAD_RIGHT to PadAction.FORWARD,
    )

    fun nameOf(code: Int): String = BUTTONS.firstOrNull { it.first == code }?.second ?: KeyEvent.keyCodeToString(code)
}
