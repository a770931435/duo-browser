# Duo Browser 雙屏瀏覽器

A dual-screen web browser for the **Retroid Pocket Duo**. One web page spreads across both screens — like reading a long page on a book that opens in the middle.

專為 **Retroid Pocket Duo** 打造的雙螢幕網頁瀏覽器：一個網頁從上螢幕一路延伸到下螢幕，就像把一本書攤開來讀。

**Download / 下載：** <https://cytworkshop.itch.io/duo-browser> (free, name your own price / 免費，自由定價)

---

## Features

- **One page across two screens** — one WebView, one scroll position; touch, scroll and zoom on either screen.
- **Video on top, comments below** — fullscreen video on YouTube, Shorts or Bilibili fills the top screen while the bottom screen keeps the page.
- **PDF reader** built on [pdf.js](https://mozilla.github.io/pdf.js/): text selection, search, table of contents, links, password-protected PDFs, sharp zoom, remembers your page.
- **Full gamepad control** — stick cursor, scrolling, tabs, remappable buttons on a controller diagram.
- **Tabs and bookmarks** with drag-to-reorder and grid/list layouts, **incognito tabs**, history, built-in ad blocker.
- **Themes** matching the RP Duo colours (the app icon changes too).
- **User scripts** (Tampermonkey-style `.user.js`), e.g. Immersive Translate.
- **English / 繁體中文 / 简体中文.** No ads, no tracking.

On a regular single-screen Android 10+ phone, the app simulates two screens by splitting one, which is handy for testing.

## 功能

- **一頁跨雙屏**：只有一個 WebView、一個捲動位置，兩個螢幕都能觸控、捲動、縮放。
- **上屏看影片，下屏刷留言**：YouTube、Shorts、B 站全螢幕時影片放上屏，下屏繼續顯示網頁。
- **PDF 閱讀器**（使用 [pdf.js](https://mozilla.github.io/pdf.js/)）：選取文字、搜尋、目錄、連結、有密碼的 PDF、清晰縮放、記住讀到哪一頁。
- **完整手把操作**：搖桿游標、捲動、分頁切換，按鍵可在手把圖上自訂。
- **分頁與書籤**（可拖動排序、方塊或條列顯示）、**無痕分頁**、歷史紀錄、內建廣告阻擋。
- **配合 RP Duo 機身配色的主題**，App 圖示也會一起換色。
- **使用者腳本**（油猴格式 `.user.js`），例如沉浸式翻譯。
- 介面支援 **English／繁體中文／简体中文**。沒有廣告、沒有追蹤。

---

## Building / 建置

Requirements: **JDK 17** and the **Android SDK** (platform 35).

```bash
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows
```

The debug APK uses the package name `com.CYT.duobrowser.debug`, so it can be installed next to the release version.

Notes:
- `gradlew.bat` does not work when the project path contains non-ASCII characters (for example Chinese folder names). `build.ps1` works around this by calling a local Gradle in `%LOCALAPPDATA%\devtools`.
- On Windows, build output goes to `%LOCALAPPDATA%\DuoBrowserBuild` (set `DUO_BUILD_DIR` to change it).
- Release builds are signed with `release/keystore.properties`, which is **not** in this repository. Without it, release builds fall back to the debug key.

需求：**JDK 17**、**Android SDK**（platform 35）。用上面的 `gradlew` 指令建置；Windows 上專案路徑含中文時請改用 `build.ps1`。正式版的簽章金鑰不在這個 repo 裡，沒有金鑰時正式版會改用 debug 金鑰簽章。

Developer notes (architecture, how dual-screen rendering works, input handling) are in [docs/DEVELOPMENT.zh-TW.md](docs/DEVELOPMENT.zh-TW.md) (Traditional Chinese).

開發筆記（架構、雙屏畫面原理、輸入處理）在 [docs/DEVELOPMENT.zh-TW.md](docs/DEVELOPMENT.zh-TW.md)。

---

## License / 授權

Duo Browser is free software, licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE). If you distribute a modified version, you must also release its source code under the GPL.

雙屏瀏覽器以 **GNU GPL v3.0** 授權開放原始碼，詳見 [LICENSE](LICENSE)。修改後再發佈，也必須以 GPL 公開原始碼。

### Name and logo / 名稱與標誌

The GPL covers the code, not the name or the brand. The name **"Duo Browser" / 「雙屏瀏覽器」** and the **CYT Workshop logo** (`app/src/main/res/drawable-nodpi/cyt_logo.png`) are not licensed for use in your own releases. If you publish a fork, please give it a different name, icon and package name (`applicationId`) so users can tell it apart from the official app.

GPL 授權的是程式碼，不包含名稱與品牌。「雙屏瀏覽器 / Duo Browser」這個名稱和 **CYT Workshop 標誌**不授權用在你自己發佈的版本。發佈修改版時，請換成不同的名稱、圖示和套件名稱（`applicationId`），讓使用者分得出來和官方版本的差別。

### Third-party components / 第三方元件

- **pdf.js** © Mozilla Foundation — Apache License 2.0 (`app/src/main/assets/pdfjs/LICENSE`), including OpenJPEG (BSD 2-Clause), qcms (MIT), the PDFium JBIG2 decoder (BSD 3-Clause), Foxit fonts (BSD-style) and Liberation fonts (SIL OFL 1.1). Their license files are in `app/src/main/assets/pdfjs/web/`.
- **AndroidX** and the **Kotlin standard library** — Apache License 2.0.

Retroid and Retroid Pocket are trademarks of their respective owners. This is an independent project by CYT Workshop and is not affiliated with Retroid.

Retroid 與 Retroid Pocket 為其所有者的商標。本專案為 CYT Workshop 的獨立作品，與 Retroid 無關。

## Contact / 聯絡

Bug reports and ideas: open an issue, or leave a comment on the [itch.io page](https://cytworkshop.itch.io/duo-browser).
問題回報與建議：請開 Issue，或在 [itch.io 頁面](https://cytworkshop.itch.io/duo-browser)留言。
