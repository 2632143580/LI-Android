# LI-Android 构建与踩坑经验

> 独立于 `LI/经验.md`，专门记录安卓子工程的真实教训。
> 每条都有"事实位置 + 根因 + 修法"，不是泛泛建议。

---

## 一、架构决策记录

### 1.1 为什么选原生 App 而非 PWA/Web Push（2026-08-16 决策）

**约束**：用户要求（1）AI 主动推送（2）手机本地运行（3）零服务器（4）不部署到云。

**结论**：PWA/Web Push 物理上做不到"零服务器+真后台推送"。Web Push 需要 VAPID 密钥 + 推送服务 + 触发接口 = 必须有服务端。浏览器对后台 JS 杀得比原生还狠。唯一满足全部约束的路径是 **原生 App + WorkManager 周期唤醒 + NotificationManager 本地通知 + 直连 LLM**。

**代价（已告知用户并接受）**：
- "主动"= 定时器到点问 LLM，不是 AI 自主觉醒
- 最短 15 分钟一次（安卓硬限制），且 Doze 会延迟
- 国产手机需手动开"自启动/电池无限制"

### 1.2 为什么用 GitHub Actions 而非 Codespaces 构建

**事实**：Codespaces 用 `.devcontainer` 初始化时卡死（容器建不起来，终端一直"正在打开远程..."）。根因：devcontainer 叠了重镜像 + 重复装 SDK，在 Codespaces 免费资源下超时。

**决策**：换 GitHub Actions（`.github/workflows/build.yml`），用户零操作——推送自动触发、产物从 Artifacts 下载。

---

## 二、CI/CD 踩坑实录（每条都是真金白银换来的）

### 2.1 `$GITHUB_PATH` 不在同一步骤内生效

**现象**：CI 第 4 步「安装 Android SDK」报 `sdkmanager: command not found`。
**根因**：把 sdkmanager 路径写入了 `$GITHUB_PATH`，但这个变量只对**后续步骤**生效。同一步骤内调用 sdkmanager 时 PATH 还没更新。
**修法**：在调用前加 `export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"`（同步骤内立即生效）。
**通用规则**：`$GITHUB_PATH` / `$GITHUB_ENV` 都是"下一步才生效"。同步骤内要用的变量必须同时 `export`。

### 2.2 Runner 预装 SDK 导致双路径冲突

**现象**：Gradle 报 `Several environment variables and/or system properties contain different paths to the SDK`。
**根因**：GitHub Actions `ubuntu-latest` runner 预装了 Android SDK（在 `/usr/local/lib/android/sdk`），我们的 workflow 又设了 `ANDROID_HOME=$HOME/android-sdk`。Gradle 检测到两个不同路径直接拒绝。
**修法**：在安装步骤开头 `unset ANDROID_SDK_ROOT ANDROID_HOME` 清掉 runner 自带的，再设我们自己的；并且通过 `$GITHUB_ENV` **持久写入** `ANDROID_SDK_ROOT=$ANDROID_HOME`（不只是 `export`，因为 export 只管当前步骤 shell）。
**关键认知**：runner 的环境变量跨步骤存在，单步 `unset` 不够，必须用 `$GITHUB_ENV` 覆盖。

### 2.3 `continue-on-error: true` 会伪装成功

**现象**：编译步骤实际失败但 job 显示绿色，导致错误评论机制没触发（评论只在 `if: failure()` 时发）。
**根因**：`continue-on-error: true` 让步骤失败不被传播，后续的 `if: failure()` 判断为 false。
**修法**：删掉 `continue-on-error`，让真实失败暴露出来。
**教训**：调试 CI 时永远不要用 `continue-on-error`，它会掩盖真正的问题。

### 2.4 layout XML 的 namespace 写错导致资源链接失败

**现象**：aapt2 报 `attribute android:layout_constraintEnd_toEndOf not found`。
**根因**：`activity_main.xml` 里 `xmlns:app="http://schemas.android.com/apk/res/android"` 写错了。ConstraintLayout 的自定义属性（`app:layout_constraint*`）需要 `res-auto` 命名空间才能解析。写成 `res/android` 就只能在框架属性里找，找不到库自定义属性。
**修法**：改为 `xmlns:app="http://schemas.android.com/apk/res-auto"`。
**通用规则**：用到任何 `app:` 前缀的自定义属性，namespace 必须是 `res-auto`。

### 2.5 本机沙箱网络限制影响诊断

**事实**：本机沙箱（WorkBuddy 执行环境）无法连接 GitHub 的 Azure 日志存储（`*.blob.core.windows.net`）。`curl` 不加 `-k` 会 SSL 报错（exit 35），加了 `-k` 能通 GitHub API 但 Azure blob 还是连不上。
**影响**：GitHub Actions 的完整日志（`/actions/jobs/{id}/logs`）是一个 302 跳转到 Azure 签名 URL 的 blob，沙箱读不到。
**解法**：CI 失败时把关键错误行写成「提交评论」（commit comment），提交评论走 GitHub API（`/repos/{owner}/{repo}/commits/{sha}/comments`），沙箱能正常读取。这已成为标准错误通道。
**沉淀脚本**：`ci-helpers/fetch_ci_error.sh` 封装了这个流程。

---

## 三、Git 操作教训

### 3.1 远端已被网页操作修改时本地会分叉

**现象**：用户通过 GitHub 网页建分支+PR 合并上传了 index.html，本地不知道，再次提交同一文件后推送被拒（"fetch first"）。
**教训**：多人/多端操作同一仓库时，推送前必须先 `git fetch` + 比对远端 HEAD。不能假设远端还是上次看到的状态。

### 3.2 `git show origin/main:path` 可能返回报错文本而非文件内容

**现象**：`git show origin/main:app/src/main/assets/index.html` 输出 42 字节，误判为"远端文件只有 42B 是坏的"。
**根因**：`origin/main` 引用在沙箱里未正确建立（`refs/remotes/origin/` 目录不存在），`git show` 返回的是 `fatal: invalid object name` 报错文本本身（恰好约 42 字符），不是文件内容。
**修正**：最终用 SHA 直接操作（`git ls-tree -r --name-only 77c9a7b`）绕开了引用问题。
**教训**：当 `git show` 输出异常短时，先确认它是不是报错文本而不是文件内容。

### 3.3 Git Credential Manager 在无 TTY 环境下失败

**现象**：`git push` 偶发 `Invalid username or token`，但 `ls-remote` 同一 token 能读。
**根因**：Git Credential Manager（GCM）需要弹交互窗口认证，沙箱无 TTY 就失败。但 `.git-credentials` 文件里的 token 对读操作有效（可能走了不同的代码路径）。
**修法**：`git -c credential.helper=store push ...` 强制用 store 方式（直接读文件），绕开 GCM。

---

## 四、Android 平台真相

### 4.1 WebView 内核 = 手机系统自带 Chromium

**事实**：Android WebView 不是独立浏览器，是系统级组件（`android.webkit.WebView`），底层跟你手机 Chrome 用同一个 Chromium 内核。不同手机的 WebView 版本不同（小米/华为/三星各有一套），但都支持现代 Web 标准。
**含义**：LI 的 HTML 在 App 里的渲染行为跟你在手机 Chrome 里打开基本一致。CSS/JS 兼容性不需要额外处理。

### 4.2 WorkManager 最短间隔与延迟

**事实**：`PeriodicWorkRequest.Builder(..., 15, TimeUnit.MINUTES)` 的 15 分钟是安卓硬性最小值，设更小会被强制提升到 15 分钟。且 Doze 模式下实际执行时间会往后拖（可能延到 30 分钟+）。
**行业实践**：所有本地推送 App 都接受这个"尽力而为"语义，没有 workaround。

### 4.3 国产 ROM 杀后台是推送失灵头号原因

**事实**：小米 MIUI、华为 EMUI、三星 OneUI 都有激进的省电策略，会杀掉后台进程包括 WorkManager 的 worker。
**工程应对**：内置"申请电池优化豁免"弹窗（`PowerManager.isIgnoringBatteryOptimizations`）+ 通知权限请求。但仍需用户手动去系统设置开"自启动"。
**行业现实**：没有任何 App 能替用户点这个开关。这是安卓权限模型决定的，不是 bug。

---

## 五、工程结构速查

```
LI-Android/
├── .github/workflows/build.yml   # CI：自动构建 debug APK
├── ci-helpers/fetch_ci_error.sh  # 从提交评论通道抓构建错误
├── app/src/main/
│   ├── AndroidManifest.xml       # 权限（通知/网络/电池/开机自启）
│   ├── java/com/li/android/
│   │   ├── MainActivity.kt       # 入口：WebView 加载 assets/index.html
│   │   ├── SettingsActivity.kt    # 设置页：LLM Key/URL/Model/定时/闲置
│   │   ├── AppPreferences.kt      # SharedPreferences 封装
│   │   ├── LlmClient.kt           # OkHttp 直连 LLM（推送调度器用）
│   │   ├── NotificationHelper.kt  # 系统原生本地通知
│   │   ├── CompanionWorker.kt     # WorkManager Worker：A+B 判断逻辑
│   │   └── PushScheduler.kt       # 注册/取消周期任务
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml     # 主界面（WebView 全屏）
│       │   └── activity_settings.xml # 设置表单
│       └── values/
│           ├── strings.xml
│           └── themes.xml
└── app/src/main/assets/index.html  # LI 构建物（无 key 版，离线加载）
```

## 六、待办（按优先级）

1. **[已完成] JS 桥**：HTML → App 原生层回传"用户发消息"事件（MainActivity 注入 MutationObserver + fetch 拦截，AndroidBridge.onUserMessage 写 lastChatEpochMs），B 功能 lastChatTime 已精准（commit e1b7fdc）
2. **[已完成] 设置页去重（填一次注入两边）**：见 7.6
3. **[低] UI 打磨**：主界面加一个浮层入口进设置（目前只能从 Intent 跳转，没有可见按钮）

---

## 七、版本同步自动化（2026-08-17）

### 7.1 背景与决策

**问题**：LI 是纯前端多文件工程（`E:/xMe/aifront/LI`，ES Module + Vite 构建为单文件 `dist/index.html`）。手机版 LI-Android 把 `dist/index.html` 拷进 `app/src/main/assets/`。之前每次 LI 改完，要手动 build → 拷 → 提交 LI-Android → 构建 → 下载 APK，步骤多且易漏。

**决策（用户拍板）**：把 LI 源码推上 GitHub（`github.com/2632143580/li`，public），让 LI-Android 的 CI 自动拉 LI 源码、自己 `npm run build`、把产物覆盖进 `assets/`。实现"LI 一更新，手机版自动跟"。

### 7.2 CI 改造要点（build.yml）

- 在 checkout 本仓库后，再用 `actions/checkout` 拉 `2632143580/li` 到 `li-src/` 子目录（public 仓库无需 token）。
- `setup-node` 装 Node 22 → `npm install` → `npm run build`（LI 的 build 含 lint + vite-plugin-singlefile，产出 `li-src/dist/index.html`）。
- `cp li-src/dist/index.html ../app/src/main/assets/index.html` 覆盖。
- `node -p "require('./li-src/package.json').version"` 读 LI 版本号，写入 `$GITHUB_OUTPUT` 并 `echo -n "$VERSION" > app/src/main/assets/li_version.txt`（APK 内可读，设置页显示）。
- artifact 命名：`li-android-li-v${{ steps.li-version.outputs.version }}-run${{ github.run_number }}`，一眼区分 LI 版本与构建序号。

### 7.3 本地一键同步脚本（ci-helpers/sync-li.bat）

- 作用：构建 LI → 拷 `dist/index.html` 到 LI-Android/assets → `git add/commit/push`（用本机 Git 凭据）。
- 适用：不想走 CI、想本地即时同步时用。双击即跑（需本机 Node + LI-Android push 权限）。
- 注意：首次 push 因含 workflow 改动，本机凭据或所用 PAT 都需 `workflow` scope。

### 7.4 设置页版本可见（#6）

- `activity_settings.xml` 底部「关于」区：两个 TextView（`tvAppVersion`/`tvLiVersion`）。
- `SettingsActivity.kt`：`getAppVersion()` 读 `PackageManager` 的 versionName；`getLiVersion()` 读 `assets/li_version.txt`（CI 写入），本地未构建时回退"开发版（本地运行）"。
- 效果：用户在手机设置页直接看到"本机应用版本 1.0 + 内置 LI 内核版本 1.0.0"，不用翻沙盒。

### 7.5 PAT 推送铁律

- 沙箱无 GitHub 登录态，`git push` 必须带 PAT。
- classic PAT 改 `.github/workflows/*.yml` 需要 **`workflow` scope**（否则 403 `refusing to allow ... without 'workflow' scope`）。
- 用法：`git remote set-url origin https://<PAT>@github.com/...` → push → 立刻 `set-url` 还原为无 token 地址。本地不留明文。
- 用户的 LI 仓库（`2632143580/li`）为 public，CI 拉取无需 token。

### 7.6 设置页全面补全 + 数据面板 + 填一次注入两边（commit 6e29101）

**用户诉求**：设置页"信息给足、该有的都要有" + 对数据的掌控感（知道数据在哪、能管理）。

**数据地图（两套存储都在 `/data/data/com.li.android/` 私有沙盒，文件管理器翻不到）**：
| 数据 | 存储位置 | 谁读写 |
|---|---|---|
| 聊天记录 / 网页聊天 LLM Key / mimo 语音 Key / 配色等全部 LI 设置 | LI 的 localStorage（键 `liChatData_v2`） | LI 网页（原生读不到内容） |
| 推送 LLM Key / 推送总开关 / A·B 开关 / 阈值 / 时刻 / 上次时间 | App 原生 `shared_prefs/li_companion.xml` | App 原生 |

**三个 Key 真相（用户曾混淆）**：
- `llm-li` = LI 网页聊天 LLM Key（`state.settings.apiKey`，多服务商各存 `keys[provider]`），存 localStorage。
- `llm-推送` = App 原生推送 LLM（`AppPreferences.apiKey/baseUrl/model`），存 shared_prefs。
- `key-语音mimo` = LI 的云端 TTS Key（`state.settings.ttsCloud.apiKey`，默认 `https://api.xiaomimimo.com/v1` / `mimo-v2.5-tts`），仅 `ttsSource='cloud'` 时生效，存 localStorage。**这是语音服务 Key，与 LLM Key 不是一回事，不能"填一次通用"。**

**填一次注入两边（syncToWeb）实现**：App 存推送 LLM 后，勾选 `syncToWeb`，MainActivity 在 LI 加载后注入脚本，把推送 Key 写进 LI 的 `liChatData_v2.settings.apiKey/apiUrl/model` 并 `location.reload()` 让 LI 生效。靠"内容是否一致"做幂等防死循环。**只能合并"同为 LLM 的聊天+推送"，mimo 语音另算。**

**数据面板实现**：注入脚本在 LI 加载时统计 localStorage 占用/聊天节点数/语音源，经 `AndroidBridge.onStorageStats(json)` 存回 `AppPreferences.storageStatsJson`，设置页读取展示。清空/重置/导出通过设置页写 `pendingWebAction`，由 MainActivity 在下次 LI 加载时落地（清空聊天树 / removeItem+清原生 / 写导出文件到 `getExternalFilesDir`，PC 经 USB 可存取），执行完 `onWebActionDone()` 清除标记。

**A·B 独立开关**：`AppPreferences.enableA/enableB`，`CompanionWorker.shouldTrigger` 分别判。

**注意点（易踩）**：
- 设置页与 WebView 不在同一 Activity，故网页侧动作只能"写标记→主界面加载时执行"，操作后需提示用户"返回主界面生效"。
- 注入脚本用 `var NATIVE = <JSON>` 直接内联原生配置，避免字符串转义坑；JSON 来自 `org.json.JSONObject`，安全无注入风险。
- `AndroidBridge` 构造函数加了 `Context`（导出写文件需要），MainActivity 改为 `AndroidBridge(this) { AppPreferences(this) }`。
- 本次改动**未触碰 `.github/workflows/`**，push 用 `git -c credential.helper=store`（store 里有 LI-Android 凭据），无需再嵌 PAT、无需 workflow scope。

## 2026-08-16 打磨轮：编译连续翻车 + CI 排错通道（重要！）

用户验收"闪烁/设置页/图标/改名"打磨提交（56d8ab9）后**构建直接红叉**。连踩三个编译错误，逐个定位修掉（最终 9a300a3 转绿）：

1. **aapt2 不认矢量图 `rect`/`circle` 属性**（最隐蔽）：手绘图标 `ic_launcher_foreground.xml` / `ic_notification.xml` 用了 `<rect android:rx>`、`<circle android:cx/cy/r>`，aapt2 报 `attribute android:rx not found` / `attribute android:cx not found`，导致 `processDebugResources FAILED`。
   - **根因**：本工程 aapt2 对该写法不买账（可能与 build-tools 34.0.0 的 schema 解析有关）。
   - **修法**：图标全部改用 `<path>` + `android:strokeColor/strokeWidth/strokeLineCap`（"li" 字形用描边竖线 + 弧线画圆点），绕开 rect/circle 的坑。矢量图里 path 写法永远被 aapt2 认。
   - **教训**：自己画 Android 矢量图标，优先用 `<path>`，别用 `<rect rx>`/`<circle>`。

2. **`WebView.isDestroyed` 不存在**：MainActivity 三处写 `webView.isDestroyed` 做销毁守卫，Kotlin 报 `Unresolved reference: isDestroyed`。`isDestroyed` 是 `Activity` 的方法，不是 `View`/`WebView` 的。
   - **修法**：MainActivity 加 `private var webViewDestroyed = false`，`onDestroy` 里置 `true`，三处改用该标志位。

3. **CI 把真实错误吞了**（比 bug 更坑）：原 `build.yml` 用 `gradle ... > build.log 2>&1` 且 `set -e`，gradle 失败即中止，build.log 内容不在步骤日志里；而 GitHub 的 job 日志 / artifacts 都存 Azure blob，**沙箱网络访问被拦，curl 下载 302 跳转到 `*.blob.core.windows.net` 全部拿不到**（这印证了之前"Azure 日志走评论通道"的判断）。
   - **修法（关键）**：
     - 编译步骤改为 `set +e; gradle ... > build.log 2>&1; CODE=$?; set -e; tail -n 220 build.log; exit $CODE`，把错误末尾打进步骤日志。
     - "上报编译错误"步骤 `if` 从 `steps.build.outcome == 'failure'`（对 `exit $CODE` 退出的 multi-line 脚本判定失效，步骤被 skip）改为 **`if: failure()`**（只要前面有任一步失败即触发）。
     - 该步骤把 `build.log` 关键行 POST 成**提交评论**（走 `api.github.com`，沙箱可达！），远程 `curl` 读 `commits/{sha}/comments` 即可拿到真实错误。
     - 顺带 `if: always()` 上传 `build.log` 产物（虽然沙箱下不了，但用户网页端能看）。
   - **教训**：在沙箱里排 CI，错误出口必须走 `api.github.com`（评论/status），别指望 Azure 日志/产物。

**排错流程（已验证可用）**：poll `actions/runs?per_page=1` 拿状态 → 失败则 `commits/{sha}/comments` 读提交评论里的真实错误 → 本地改 → 再推再跑。PAT 用完立刻 `git remote set-url` 抹除，本地无明文残留。

**当前可下载产物**：`li-android-li-v1.0.0-run20`（9a300a3 成功，约 3.87MB）。
