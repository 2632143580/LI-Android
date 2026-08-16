# LI 伴侣（安卓 App）

把 LI 聊天界面装进手机，并让 AI **主动**给你发推送（不依赖任何服务器、不依赖 FCM、不上云）。
这是给「个人自用」做的：你手机上装一个 App，AI 到点或看你久没聊就主动发一句。

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
   - 你还要去手机「设置 → 应用管理 → LI 伴侣 → 自启动/省电策略 → 无限制」手动打开。
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

LI 的网页本体通过 WebView 加载，放在 `app/src/main/assets/index.html`（见下「放 LI 进去」）。

---

## 放 LI 进去

本工程不含 LI 源码（那是另一个项目）。你需要：

1. 在 LI 项目里构建单文件：`启动LI-构建.bat`（或 `npm run build`），得到 `dist/index.html`。
2. 把 `dist/index.html` 复制到本工程 `app/src/main/assets/index.html`（没有 assets 目录就新建）。
   这样 LI 完全离线跑在手机里，不依赖任何服务器。

> LI 用到的 API Key 在 LI 自己的设置里填（存在 WebView 本地）。
> 而 App 侧主动推送调 LLM 用的 Key，在 App 的「设置」里单独填（存在手机 App 沙盒）。
> 两处 Key 独立，互不干扰。

---

## 怎么构建出 APK（本机零安装）

推荐用 **GitHub Codespaces**（浏览器里编译，电脑不装任何东西）：

1. 把这个 `LI-Android` 文件夹推到你的 GitHub 仓库。
2. 在仓库点「Code → Codespaces → Create codespace」。
3. 终端执行（Dev 容器已带 Android SDK + Gradle）：
   ```bash
   yes | sdkmanager --licenses
   gradle assembleDebug
   ```
4. 产物在 `app/build/outputs/apk/debug/app-debug.apk`，下载到手机安装。
5. 首次打开：允许通知、允许忽略电池优化；点「设置」填 LLM 的 Key / 模型 / 定时 / 闲置阈值。

> 版本说明：本工程用的 AGP 8.5.2 / Kotlin 1.9.24 / WorkManager 2.9.0 / targetSdk 34 是编写时的较新稳定版。
> 若未来构建报版本错，按报错提示把版本号往上提一档即可（这是安卓生态常态，不是 bug）。

> 本工程在编写环境的电脑上**无法编译验证**（那台机器没装 Android SDK）。代码经人工核对，
> 但首次构建请以上述步骤为准，遇到报错把红字发我，我帮你改。

---

## 目录结构

```
LI-Android/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ assets/index.html        ← 放 LI 的 dist/index.html
│  ├─ java/com/li/android/
│  │  ├─ MainActivity.kt        WebView 宿主 + 权限申请
│  │  ├─ SettingsActivity.kt    填 Key/模型/定时/闲置
│  │  ├─ PushScheduler.kt       注册 WorkManager 周期任务
│  │  ├─ CompanionWorker.kt     A/B 触发判断 + 调 LLM
│  │  ├─ LlmClient.kt           直连 LLM（OpenAI 兼容接口）
│  │  ├─ NotificationHelper.kt  本地通知
│  │  └─ AppPreferences.kt      本地存储
│  └─ res/...                   界面与主题
├─ build.gradle.kts / settings.gradle.kts / gradle.properties
└─ .devcontainer/devcontainer.json   Codespaces 一键带 Android 环境
```
