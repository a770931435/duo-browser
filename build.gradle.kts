plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// 專案放在 OneDrive（且路徑含中文），把建置輸出移到本機 ASCII 路徑，
// 避免 OneDrive 同步大量暫存檔，也避開 Windows 上非 ASCII 路徑的工具問題。
// 若使用者資料夾本身含中文（LOCALAPPDATA 不是 ASCII），可用環境變數 DUO_BUILD_DIR 指定其他位置。
val outRoot = System.getenv("DUO_BUILD_DIR")?.let { file(it) }
    ?: System.getenv("LOCALAPPDATA")?.let { file("$it/DuoBrowserBuild") }
if (outRoot != null) {
    allprojects {
        layout.buildDirectory.set(File(outRoot, project.name))
    }
}
