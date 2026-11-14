# OpenCode Mobile（Android 外壳）

[![Release](https://github.com/alighhjj/opencode/actions/workflows/release.yml/badge.svg)](https://github.com/alighhjj/opencode/actions/workflows/release.yml)

把 [anomalyco/opencode](https://github.com/anomalyco/opencode) 的 Web 客户端装进一个原生 Android 壳里，
手机作为远程客户端连接电脑/服务器上的 `opencode serve`。

完整可行性分析与结论见 [`FEASIBILITY.md`](FEASIBILITY.md)。

> 非官方客户端，与 opencode 官方无关联；名称与图标仅用于本地自用构建，公开发布前请替换。

## 快速开始

```bash
# 电脑端（同一局域网）
OPENCODE_PASSWORD='<strong-secret>' opencode serve --hostname 0.0.0.0 --port 4096
opencode pair          # 可选：生成一次性配对链接/二维码

# 手机端（APK 由 CI 构建并挂到 Release，不入库）
adb install -r /path/to/OpenCode-Mobile-0.2.1-release.apk
```

打开 App → 填入 `电脑IP:4096` + 密码 → 连接。也可以直接粘贴 `opencode pair` 打印的链接。

## 发布（CI 自动构建）

打标签即发布，APK 由 GitHub Actions 从源码构建并挂到 Release：

```bash
git tag v0.2.1
git push origin v0.2.1        # 触发 .github/workflows/release.yml
```

也可以在 Actions 页手动 `Run workflow`：只跑测试 / lint / 构建，产物作为 workflow artifact 上传，不会创建 Release
（tag 名必须和 `app/build.gradle.kts` 里的 `versionName` 对齐）。

流水线做的事：JDK 17 → Android SDK 35 → `testDebugUnitTest` → `lintDebug` → `assembleRelease` → 生成 `SHA256SUMS` → 上传到 Release。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug           # 0 error 才提交
./gradlew testDebugUnitTest   # ServerInput 解析用例
```

- Kotlin 2.0.21 / Compose M3 / AGP 8.7.3 / Gradle 8.11.1
- compileSdk 35，minSdk 26，targetSdk 35
- release 目前复用 debug keystore 签名，仅用于内测；正式分发请换私有 keystore 并开启 R8

## 结构

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 边到边、Compose 入口 |
| `data/ServerInput.kt` | 地址 / 配对链接 / 二维码 JSON 解析 |
| `data/ServerStore.kt` | 最近连接持久化 |
| `ui/OpenCodeApp.kt` | 连接页 ↔ 浏览页状态机 |
| `ui/screens/ConnectScreen.kt` | 服务器地址、密码、最近列表、启动提示 |
| `ui/screens/BrowserScreen.kt` | WebView 宿主：鉴权、权限、文件选择、返回栈、错误页、状态栏/键盘安全区 |
| `web/ClipboardBridge.kt` | 为 http 源补上 `navigator.clipboard`（"Copy path" 等复制按钮） |

## 变更记录

### 0.2.6

- **修复顶部标题栏被状态栏遮挡**：activity 是 edge-to-edge（`enableEdgeToEdge`），WebView 的原点就在状态栏下面，
  Web 客户端自己的头部（会话标题、`会话 / 更改 / More…` 标签栏）被时间、电量图标压住，点不到按钮。
- 给 WebView 容器补上 `WindowInsets.safeDrawing.getTop()` 的顶部内边距（刘海屏取 cutout 的更高值），
  页面仍是 `100dvh`，会自己重排进剩下的盒子 —— 和键盘避让用同一套「缩小视口」思路，不注入任何 JS。
- 底部内边距逻辑不变（`max(ime, navigationBars, getWindowVisibleDisplayFrame 测量值)`）。
- 状态栏区域露出的是 `#080808` 主题底色，`SystemBarStyle.dark` 保证图标为浅色，深浅对比正常。


### 0.2.5

- **修复 0.2.4 的键盘遮挡（回归）**：0.2.4 的 JS polyfill 用「`position: fixed/absolute/sticky` 且
  `bottom: 0`」的启发式找 composer，但 opencode 的输入区其实是 `height:100dvh` 的 flex 列里最后一行
  （祖先全是 `static`），选择器永远匹配不到，`window.__opencodeKeyboard()` 直接 return —— 结果是
  WebView 保持全高、页面又不动，输入框被键盘完整遮住。
- 改回「浏览器行为」：不再改 DOM，直接让 WebView 少掉 IME 那块高度。页面根节点是 `h-dvh`，
  viewport meta 又是 `interactive-widget=resizes-content`，dvh 跟着缩小后布局自然重排，
  composer 落在键盘上方 —— 和手机 Chrome 里表现一致。
- bottom inset 取 `max(ime, navigationBars, getWindowVisibleDisplayFrame 测量值)`：IME 打开时取 IME，
  收起时取导航栏，ROM 不上报 inset 时用可见区域兜底（不再限定 Android 10 以下）。
- 键盘停稳 180 ms 后补一次 `scrollIntoView({block:'nearest'})`，只用于草稿页内容比可视区高的场景。
- 删除 `web/KeyboardBridge.kt`（失效的 polyfill）与三处注入调用。
- 版本号 0.2.4 → 0.2.5（versionCode 7）。

### 0.2.4

- **方案 B：浏览器式键盘交互**：WebView 保持全高（页面零 reflow），不再逐帧 resize；运行时注入
  `KeyboardBridge` 的 JS/CSS（仿 `ClipboardBridge`），把键盘高度经 `window.__opencodeKeyboard(hPx)`
  推给页面，令底部固定的 composer 跟随键盘上移（CSS `bottom` transition），消除键盘弹收时整页
  reflow 导致的压缩/伸展卡顿。
- 版本号 0.2.3 → 0.2.4（versionCode 6）。

### 0.2.3

- **修复 0.2.2 引入的键盘卡顿加剧**：回退 `animateIntAsState` 平滑方案——缓动会让 WebView 滞后于键盘、
  停稳后还继续 reflow，反而拉长动画、掉帧更多。改为让 `windowInsetsPadding` 在**布局阶段**直接消费
  IME inset（WebView 精确跟随键盘、reflow 次数最少），并移除组合阶段的冗余 inset 读取
  （原实现每帧键盘动画都让整屏重组）。
- 版本号 0.2.2 → 0.2.3（versionCode 5）。

### 0.2.2

- **修复键盘弹收时 UI 压缩/伸展卡顿**：`BrowserScreen` 不再把平台的原始 IME inset 直接贴给 WebView，
  改为用 `animateIntAsState`（spring 缓动）把 inset 平滑成连续曲线，WebView 的 resize 速率受控、
  reflow 均匀分布；Android 10 及以下同样用 `animateIntAsState` 平滑可见区域兜底，
  避免逐帧大幅 reflow 导致的掉帧。
- 版本号 0.2.1 → 0.2.2（versionCode 4）。

### 0.2.1

- **修复 "Copy path" 报 `Cannot read properties of undefined (reading 'writeText')`**：
  WebView 打开的是 `http://<局域网 IP>:4096`，属于非安全上下文，`navigator.clipboard` 为 `undefined`，
  Web 客户端的复制按钮直接解引用就会抛错。现在通过 `addJavascriptInterface` 暴露原生剪贴板，
  并在 `onPageStarted` / `onPageFinished` 注入 `navigator.clipboard` 垫片（带 `execCommand('copy')` 兜底），
  仅当页面仍指向已配置的服务器时才写入剪贴板。
- 版本号 0.2.0 → 0.2.1（versionCode 3）。

### 0.2.0

- **修复键盘遮挡输入框**：`targetSdk 35` 起系统不再因软键盘缩窗口，改为在 Compose 侧把
  `WindowInsets.systemBars` 与 `WindowInsets.ime` 取并集交给 WebView 容器，键盘弹起时 WebView 高度收缩，
  底部输入框（composer）始终位于键盘上方；Android 10 及以下用可见区域测量兜底。
- 连接页的地址 / 密码输入框同样避让键盘。
- 清理 lint：移除 API 27+ 才有的 `windowLightNavigationBar` 属性（运行时由 `enableEdgeToEdge` 设置）。
- 版本号 0.1.0 → 0.2.0（versionCode 2）。

![键盘避让前后对比](docs/keyboard-insets.png)

## 已知限制

- 未在真机/模拟器回归（本仓库构建环境无 KVM），仅完成编译、单测与静态校验
- 密码以明文存于 SharedPreferences，仅适用于可信局域网
- 终端（PTY）在手机上的快捷键体验尚未做原生增强
