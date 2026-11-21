package com.duo.browser

import java.util.Locale

/**
 * 介面語言：英文 / 繁體中文 / 简体中文。
 * 每段文字直接寫三種版本：L.t("繁體", "简体", "English")，翻譯和使用處放在一起，好維護。
 */
object L {
    enum class Lang(val code: String) { AUTO("auto"), EN("en"), ZH_TW("zh-TW"), ZH_CN("zh-CN") }

    /** 設定裡選的語言（AUTO = 跟隨系統）。 */
    val chosen: Lang
        get() {
            val c = if (Session.isReady) Session.prefs.language else "auto"
            return Lang.entries.firstOrNull { it.code == c } ?: Lang.AUTO
        }

    /** 實際使用的語言。 */
    val current: Lang
        get() = when (val c = chosen) {
            Lang.AUTO -> fromSystem()
            else -> c
        }

    private fun fromSystem(): Lang {
        val loc = Locale.getDefault()
        if (loc.language != "zh") return Lang.EN
        val hant = loc.script == "Hant" || loc.country in setOf("TW", "HK", "MO")
        return if (hant) Lang.ZH_TW else Lang.ZH_CN
    }

    fun t(tw: String, cn: String, en: String): String = when (current) {
        Lang.ZH_TW -> tw
        Lang.ZH_CN -> cn
        else -> en
    }

    /** 語言選項在設定頁顯示的名稱（固定用該語言本身的寫法）。 */
    fun displayName(l: Lang): String = when (l) {
        Lang.AUTO -> t("自動（跟隨系統）", "自动（跟随系统）", "Auto (system)")
        Lang.EN -> "English"
        Lang.ZH_TW -> "繁體中文"
        Lang.ZH_CN -> "简体中文"
    }
}
