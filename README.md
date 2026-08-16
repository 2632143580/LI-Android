# li（安卓 App）

把 LI 聊天界面装进手机，并让 AI **主动**给你发推送（不依赖任何服务器、不依赖 FCM、不上云）。
这是给「个人自用」做的：你手机上装一个 App，AI 到点或看你久没聊就主动发一句。

应用名就叫 **li**（启动图标是蓝色底白色「li」字样）。

---

## 先读：行业真相（小白必看，别跳过）

这些不是吓你，是安卓这个系统的**物理规律**。设计前不知道，后面必兜圈子。

1. **后台任务最短 15 分钟一次，且会延迟。**
   安卓从 Android 6 起就限制后台：WorkManager 两次执行最短间隔是 15 分钟；手机休眠（Doze）时还会往后拖。
   所以「定时 9:00」实际可能在 9:00–9:15 之间触发，**不是精确到秒**。这是全行业都接受的「尽力而为」。

2. **「主动」= 定时器到点问 LLM，不是 AI 自己觉醒。**
   没有服务器常驻，就没人替你「随时盯着」。真相是：App 每隔 15 分钟被系统唤醒一次，
   自己判断「现在该不该说话」（A 到点了？B 你闲置太久？），该说就去调一次 LLM 拿一句话。
   想「AI 真正自主决定何时找你」必须有个常驻服务端——那正是你不要的，所以接受这个边界。

3. **国产手机（小米/华为/OPPO/三星）会杀后台，这是推送失灵的头号原因。**
   不处理，你的推送会「有时有有时没有」。本工程已内置两道保险：
   - 首次打开弹窗申请「忽略电池优化」（必须点允许）；
   - 你还要去手机「设置 → 应用管理 → li → 自启动/省电策略 → 无限制」手动打开。
   这一步任何本地推送 App 都躲不掉，App 没法代你点。

4. **通知权限（Android 13+）必须用户点允许。**
   本工程首次打开会弹「允许通知」，不点就收不到。

5. **本地通知不靠 FCM。** 我们用系统原生 NotificationManager 直接弹，不需要谷歌推送服务器，也不需你搭服务器。

---

## 架构（A 定时 + B 久未互动）

```
WorkManager 每 15 分钟唤醒
        │
        ▼
CompanionWorker（判断 A 或 B 是否触发）
        │
        ├─ 读 AppPreferences（上次聊天时间 / 定时表 / 闲置阈值）
        │
        ├─ 触发 → LlmClient 直连你的 LLM 接口拿一句话
        │
        └─ NotificationHelper 弹本地通知（点开进 App 看 LI）
```

- **A 定时陪伴**：你在设置里填「09:00,20:00」，Worker 每次醒来检查「当前是否落在某时刻 ±7 分钟内、且今天该时刻没发过」→ 发。
- **B 久未互动**：距你上次聊天超过「闲置阈值」（默认 3 小时）且距上次主动推送也超过阈值（防刷屏）→ 发。

LI 的网页本体通过 WebView 加载，放在 `app/src/main/assets/index.html`（由 CI 自动从 li 仓库构建嵌入，见下「版本同步」）。

---

## 两套独立的存储（你的数据在哪、怎么管）

这是你最在意的「对信息的掌控」——两套数据**互相隔离**，存的位置不同：

| 数据 | 存在哪 | 谁能读 | 怎么管 |
|---|---|---|---|
| 聊天记录 / 网页侧 LLM Key / 语音 Key / 配色 | LI 网页的 localStorage（键 `liChatData_v2`） | 仅 App 内 WebView | 在 LI 里「设置」填；App 设置页「数据管理」可导出/清空/重置 |
| 推送用 LLM Key / 推送开关 / 定时 / 闲置阈值 | App 原生 `shared_prefs/li_companion.xml` | 仅本 App 沙盒 | 在 App「设置」页填 |

两者都在 `/data/data/com.li.android/` 私有沙盒，**文件管理器翻不到**（Android 系统隔离，这是安全设计不是 bug）。
你能做的「掌控」：打开 App → 设置 → **数据管理区**会显示占用大小、聊天节点数、语音源；并支持**导出聊天记录 / 清空聊天 / 重置全部**（危险操作带二次确认，重置还需勾选「已知不可恢复」）。

### 三个 Key 的关系（别填混）

- **网页聊天 LLM Key**（llm-li）：聊天时调的 LLM，存网页 localStorage。
- **推送 LLM Key**（llm-推送）：App 主动推送时调的 LLM，存 App 沙盒。
- **语音 MiMo Key**（key-语音mimo）：云端 TTS 语音合成 Key，存网页 localStorage（仅选择「云端语音」时生效）。

前两者都是 LLM Key，可在各自设置页分别填（当然可以填同一个 Key，省事）。**语音 Key 是另一类服务，不能和 LLM Key 合并。**
所谓「填一次注入两边」指的是：网页侧填了聊天 LLM Key 后，App 会通过桥接把它也写进网页环境，省去在网页里重复输入——但推送 Key 仍在 App 设置里单独维护。

---

## 版本同步：LI 网页更新后，怎么装到手机？（你最关心的）

LI 网页是**另一个仓库**（`github.com/2632143580/li`），跟本 App 仓库独立。你改了 LI 网页（多文件更新）之后：

**好消息：你几乎什么都不用做。** 本工程的 GitHub Actions 在每次构建时，会**自动**去拉取 li 仓库最新源码、跑 `npm run build` 生成单文件、嵌入进 App。所以 LI 网页一更新，你只需触发一次 App 重建，新 APK 里就含最新 LI。

### 流程对比（哪些被缩减了）

| 方案 | 步骤 | 是否需要手动拷贝 |
|---|---|---|
| ❌ 旧流程（手动） | 本地构建 li → 拷贝 index.html → 提交 LI-Android → 等云端构建（4 步） | 需要，且易漏 |
| ✅ 现在（云端一键） | GitHub Actions 点「Run workflow」→ 等 10–15 分钟 → 下载 APK 覆盖安装（1 步） | 不需要，CI 自动拉取 |

### 触发重建的两种方式（任选其一）

- **方式 A（最简，推荐）**：打开 LI-Android 仓库 → **Actions** → 点「Build LI Android APK」→ **Run workflow** → 等绿色对勾 → 下载新 APK。全程不用开终端。
- **方式 B**：往 LI-Android 的 `main` 推一个提交（哪怕 `git commit --allow-empty -m "rebuild"`）也会自动触发。

### 本地离线备选

`ci-helpers/sync-li.bat`：双击即可在本机把 li 构建、拷贝、提交、推送（前提是你本机有 Android SDK 能自己编）。
但既然云端 Actions 已经能自动拉取 li 源码，**绝大多数情况直接用云端 Run workflow 最省事**，不用碰这个脚本。

> 装到手机后，打开 App → 设置 → **关于** 会显示「本机应用版本」和「内置 LI 内核版本」，你能一眼确认手机里跑的是哪个 LI 版本。

---

## 怎么构建出 APK（本机零安装）

代码已自带 `.github/workflows/build.yml`：推送到 `main` 后，GitHub 会在云端自动编译并产出 APK 供下载。**你全程不用开任何终端。**

### 主路径：GitHub Actions（推荐，已验证可行）

1. 把代码推到 `main`（本工程已包含 workflow，推上去即自动触发构建；或直接在 Actions 点 Run workflow）。
2. 打开仓库页面 → 点顶部 **Actions** 标签。
3. 你会看到一条名为 **「Build LI Android APK」** 的运行记录：
   - 黄色圆点 = 正在编译（首次约 10–15 分钟，要下载 SDK+依赖）；
   - 绿色对勾 = 成功。
4. 点进这条记录 → 底部 **Artifacts（产物）** 里有一个形如 **`li-android-li-v1.0.0-run20`** 的压缩包（版本号=内置 LI 版本，run 号=构建次数，逐次递增）→ 下载。
5. 解压得到 `app-debug.apk`，传到手机安装（手机设置里允许「未知来源」安装包）。
6. 首次打开：允许通知、允许忽略电池优化；点「设置」填 LLM 的 Key / 模型 / 定时 / 闲置阈值。

> 想重新构建（比如 LI 网页更新了）？在 Actions 页面点 **「Build LI Android APK」→ Run workflow** 即可手动触发，不必再推代码。

### 备选：GitHub Codespaces（浏览器开终端编译）

> 实测 Codespaces 拉取 Android 镜像偶尔会卡在「正在打开远程……」长时间不动。若遇到，
> 直接用上面的 Actions 路径，不必死磕。

1. 仓库点「Code → Codespaces → Create codespace」。
2. 等编辑器加载完（底部出现可用终端，不再是「正在打开远程」）。
3. 终端执行（Dev 容器已带 Android SDK + Gradle）：
   ```bash
   yes | sdkmanager --licenses
   gradle assembleDebug
   ```
4. 产物在 `app/build/outputs/apk/debug/app-debug.apk`，下载到手机安装。

> 版本说明：本工程用的 AGP 8.5.2 / Kotlin 1.9.24 / WorkManager 2.9.0 / targetSdk 34 是编写时的较新稳定版。
> 若未来构建报版本错，按报错提示把版本号往上提一档即可（这是安卓生态常态，不是 bug）。

> 构建状态：本工程已通过 GitHub Actions 实际编译验证，**构建为绿色（success）**，产物可正常下载安装。
> 若你首次改动后构建报红，把 Actions 页面里「上报编译错误」步骤发到提交评论的红字发我，我帮你改（错误已通过「提交评论」通道回传，远程可读到真实报错，不依赖被沙箱拦截的日志下载）。

---

## 目录结构

```
LI-Android/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ assets/index.html        ← 由 CI 自动从 li 仓库构建嵌入（无需手动放）
│  ├─ assets/li_version.txt    ← 由 CI 写入，记录内置 LI 版本号
│  ├─ java/com/li/android/
│  │  ├─ MainActivity.kt        WebView 宿主 + 权限申请 + 网页统计注入
│  │  ├─ SettingsActivity.kt    填 Key/模型/定时/闲置 + 数据管理（危险区二次确认）
│  │  ├─ AndroidBridge.kt       JS↔原生桥（统计/导出/重置）
│  │  ├─ PushScheduler.kt       注册 WorkManager 周期任务
│  │  ├─ CompanionWorker.kt     A/B 触发判断 + 调 LLM
│  │  ├─ LlmClient.kt           直连 LLM（OpenAI 兼容接口）
│  │  ├─ NotificationHelper.kt  本地通知
│  │  └─ AppPreferences.kt      本地存储
│  └─ res/...                   界面与主题（含自绘「li」图标）
├─ build.gradle.kts / settings.gradle.kts / gradle.properties
├─ ci-helpers/sync-li.bat       本地一键同步脚本（离线备选）
└─ .devcontainer/devcontainer.json   Codespaces 一键带 Android 环境
```
