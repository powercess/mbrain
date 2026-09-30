<div align="center">

<img src="assets/brand/mbrain-logo.svg" width="88" height="88" alt="MBrain Logo" />

# MBrain

**讓 AI Agent 調用你的 Android 手機能力。**

設備資訊 · 應用管理 · 檔案操作 · Root / Shizuku · MCP 服務聚合

<p>
  <img src="https://img.shields.io/badge/Android-9.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 9.0 及以上" />
  <img src="https://img.shields.io/badge/MCP-HTTP-5E6AD2?style=flat-square" alt="HTTP MCP" />
  <a href="https://github.com/powercess/mbrain/releases/latest"><img src="https://img.shields.io/github/v/release/powercess/mbrain?style=flat-square&amp;color=14756A" alt="最新版本" /></a>
</p>

[简体中文](README.zh_CN.md) | **繁體中文** | [English](README.md) | [Français](README.fr.md) | [日本語](README.ja.md)

[快速上手](#快速上手) · [連接 Agent](#連接-agent) · [接入 MCP 服務](#接入-mcp-服務) · [常見問題](#常見問題) · [反饋問題](https://github.com/powercess/mbrain/issues)

</div>

---

MBrain 是執行在 Android 上的 Model Context Protocol（MCP）閘道。它把手機自身的能力和你接入的 MCP 服務匯集到一個入口，讓支持 MCP 的 AI 用戶端讀取設備狀態、管理應用、操作檔案或執行命令。你可以在手機上選擇開放哪些能力，并隨時停止服務。

## 界面預覽

<table>
  <tr>
    <th align="center">首頁</th>
    <th align="center">能力管理</th>
    <th align="center">設定</th>
  </tr>
  <tr>
    <td><img src="assets/screenshots/home.png" width="240" alt="首頁：工具與服務概覽，右下角懸浮啟停按鈕" /></td>
    <td><img src="assets/screenshots/capabilities.png" width="240" alt="能力管理：按需啟用 Root、Shizuku 和應用管理" /></td>
    <td><img src="assets/screenshots/settings.png" width="240" alt="設定：連接憑據、外觀和執行記錄" /></td>
  </tr>
</table>

<sub>應用實際執行截圖。可用工具數量取決于已啟用能力和接入服務；支持淺色、深色及跟隨系統。</sub>

## 可以做什么

| 能力 | 用途 |
| --- | --- |
| **讀取設備狀態** | 查看設備資訊、電量、儲存空間和網絡狀態 |
| **管理應用** | 查看應用列表與詳情、啟動應用；高權限通道還可安裝、卸載和停止應用 |
| **執行命令與操作檔案** | 通過 Root 或 Shizuku 執行命令，瀏覽、讀取、寫入、復制和移動檔案 |
| **接入其他 MCP 服務** | 添加手機本機的 HTTP MCP 服務，或由 MBrain 啟動和管理 stdio MCP 程序 |
| **統一管理與連接** | 一個 MCP 地址提供工具目錄，支持搜索工具、查看說明、復制連接資訊和查看近期執行記錄 |
| **內網穿透** | 內置 frpc，統一設定一臺伺服器，多條基礎 TCP 隧道轉發 MBrain 或手機其他服務 |

- **按需啟用**：Root 和 Shizuku 各用一個開關，打開時自動檢查并申請權限。
- **隨時停止**：首頁右下角一鍵啟停，也可從前臺通知停止閘道。
- **服務自由添加**：填寫自己的服務名稱與設定，不綁定特定第三方應用。

## 快速上手

### 1. 安裝 MBrain

需要 **Android 9.0 或更高版本**。

從 [GitHub Releases](https://github.com/powercess/mbrain/releases/latest) 下載最新版本的 `mbrain-v版本號-universal.apk`，在 Android 設備上打開并安裝。通用安裝包覆蓋 ARM64、ARM32、x86_64 和 x86，無需挑選架構。

發行頁面提供更新說明和 `SHA256SUMS.txt` 校驗檔案。自行構建及開發測試版說明見[開發指南](docs/development.md)。

### 2. 選擇需要開放的能力

打開底部的 **能力** 頁面：

- 設備資訊不需要 Root。
- 按需啟用應用管理。
- 需要高權限操作時，進入 **Root** 或 **Shizuku**，打開啟用開關，并完成授權。

使用 Root 需要設備具備 Root 能力，并允許 **MBrain 本身**獲取權限。使用 Shizuku 則需先安裝并啟動 [Shizuku](https://shizuku.rikka.app/)。兩種通道可以分別啟用，不需要同時開啟。

### 3. 啟動閘道

回到 **首頁**，點擊右下角的啟動按鈕。顯示“執行中”后，打開 **連接地址與憑據**，獲取 MCP 地址和 Bearer Token。

## 連接 Agent

在支持 **HTTP MCP 和 Bearer 認證**的用戶端中添加服務：

| 設定項 | 填寫內容 |
| --- | --- |
| 名稱 | `MBrain`，也可自行命名 |
| MCP 地址 | `http://127.0.0.1:8765/mcp` |
| 認證方式 | Bearer Token |
| Token | 從 MBrain 的“連接地址與憑據”頁面復制 |

如果用戶端通過請求頭設定認證，填寫：

```http
Authorization: Bearer <從 MBrain 復制的 Token>
```

**同一臺手機上的用戶端**可以直接使用上述地址。

**電腦上的用戶端**需要先通過 ADB 連接手機，再轉發連接埠：

```bash
adb forward tcp:8765 tcp:8765
```

默認連接埠被占用時，閘道會自動使用空閑連接埠。請以“連接地址與憑據”中的實際地址及“使用說明”中的 ADB 命令為準。Token 加密保存在設備上，重啟后保持不變；可在憑據頁重置。

連接后可以先讓 Agent 執行一個簡單的只讀請求：

> 查看這臺手機的電量和剩余儲存空間。

## 接入 MCP 服務

進入 **MCP → 添加服務**，選擇連接方式：

| 方式 | 適用情況 | 需要填寫 |
| --- | --- | --- |
| **HTTP 服務** | 服務已經由手機上的其他應用或程序啟動 | 名稱、本機服務地址、可選 Token |
| **托管程序** | 希望由 MBrain 啟動并管理 stdio MCP 服務 | 名稱、程序、逐項啟動參數、執行身份 |

保存設定后，在服務詳情中點擊 **連接服務**。成功連接后，該服務的工具會加入 MBrain 的工具目錄，Agent 可以通過同一個入口調用。

HTTP 服務目前只支持回環地址。托管程序所需的程序和執行環境需要事先在設備上準備好，MBrain 不內置 Node.js 或 Python。

## 遠程訪問

內置 **frpc** 用戶端，可通過自建 **frps** 伺服器轉發 MBrain 或手機上的其他服務。進入 **設定 → 內網穿透 → 設定伺服器**，填寫伺服器地址、連接埠和 Token，再添加 TCP 隧道。

當前僅支持基礎 TCP 轉發，所有隧道共用一臺伺服器。每條隧道獨立啟停與重試；MCP 隧道跟隨閘道，自定義服務隧道獨立執行。設定示例與執行規則見[內網穿透指南](docs/remote-access.md)。

## 權限與安全

- 閘道僅監聽手機回環地址。電腦通過 ADB 轉發連接，遠程訪問通過已設定的隧道連接。
- 持有 Bearer Token 的用戶端可以調用**全部已啟用工具**，尚不支持為不同用戶端單獨分配權限。
- Root / Shizuku 工具能夠執行實際修改，請只連接可信用戶端，不要分享 Token。
- 在手機上選擇開放的能力，不再需要時隨時停止閘道。

## 常見問題

<details>
<summary><strong>沒有 Root，能使用嗎？</strong></summary>

可以。設備資訊、普通應用查詢與啟動、本機 HTTP MCP 接入不依賴 Root。需要更高權限時，可以使用 Shizuku；實際操作范圍取決于它的啟動方式。

</details>

<details>
<summary><strong>為什么用戶端連接不上？</strong></summary>

先確認首頁顯示“執行中”，并使用憑據頁中的 Token。電腦連接時還需確認實際連接埠的 ADB 轉發成功。閘道僅監聽手機回環地址；遠程訪問通過已設定的 TCP 隧道連接。

</details>

<details>
<summary><strong>為什么找不到某個工具？</strong></summary>

工具數量隨能力開關、權限狀態和外部服務連接情況變化。先在能力頁檢查開關，再到 MCP 頁檢查服務狀態，最后在用戶端重新獲取工具列表。

</details>

<details>
<summary><strong>停止服務后會發生什么？</strong></summary>

閘道停止接收請求，斷開外部 MCP 連接，并清理直接托管的子程序。應用不會在開機后自動啟動閘道；執行記錄目前僅保留在記憶體中。

</details>

## 反饋與參與

遇到問題或有功能建議，歡迎通過 [Issue 模板](https://github.com/powercess/mbrain/issues/new/choose)反饋。描述問題時請附上版本和復現步驟；截圖或日志中請隱去 Token 與私人資訊。

歡迎提交問題報告、改進文檔或貢獻代碼。請閱讀[貢獻指南](CONTRIBUTING.md)（英文），了解本地環境、構建執行、驗證及拉取請求流程。基于最新 `dev` 創建獨立分支，并將拉取請求提交到 `dev`。詳細設備測試步驟見[開發指南](docs/development.md)。

## 致謝

- [droid-mcp](https://github.com/stixez/droid-mcp)：MBrain 使用的 Android MCP 基座，保留其 [Apache-2.0 許可證](vendor/droid-mcp/LICENSE)和[來源記錄](vendor/droid-mcp/UPSTREAM.md)。
- [Shizuku](https://github.com/RikkaApps/Shizuku) 與 [libsu](https://github.com/topjohnwu/libsu)：Android 高權限能力接入。
- [RikkaHub](https://github.com/rikkahub/rikkahub)：設定列表與交互設計參考。

## Star History

如果 MBrain 對你有幫助，歡迎點一個 Star。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date&amp;theme=dark" />
  <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
  <img alt="MBrain 的 GitHub Star 增長趨勢" src="https://api.star-history.com/svg?repos=powercess/mbrain&amp;type=Date" />
</picture>

<sub>Star 趨勢由 <a href="https://www.star-history.com/#powercess/mbrain&amp;Date">Star History</a> 提供。</sub>


