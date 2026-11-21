package com.duo.browser

import android.content.ComponentName
import android.content.pm.PackageManager

/**
 * 換主題時一起換桌面圖示：AndroidManifest 裡每種機身配色有一個 activity-alias（各自的圖示），
 * 只開啟目前主題的那一個，其他關掉。
 * 注意：有些桌面程式在換圖示時會把桌面上的捷徑拿掉，要從 App 清單再拖一次；圖示更新也可能要等幾秒。
 */
object IconSwitcher {
    private val ALIASES = mapOf(
        "purple" to "LauncherPurple",
        "black" to "LauncherBlack",
        "bit16" to "LauncherBit16",
        "gc" to "LauncherGc",
        "ice" to "LauncherIce",
    )

    fun apply(themeId: String) {
        val app = Session.app
        val pm = app.packageManager
        val want = ALIASES[themeId] ?: ALIASES.getValue("purple")
        // 先開新的再關舊的：中間任何時候至少有一個桌面入口
        val order = ALIASES.values.sortedBy { if (it == want) 0 else 1 }
        for (name in order) {
            val cn = ComponentName(app.packageName, "com.duo.browser.$name")
            val state = if (name == want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                if (pm.getComponentEnabledSetting(cn) != state || name == want)
                    pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                android.util.Log.w("IconSwitcher", "switch failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
