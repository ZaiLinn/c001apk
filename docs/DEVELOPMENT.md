# 开发指南

[返回项目首页](../README.md)

## 项目结构

```text
c001apk/
├── apps/
│   ├── android/             # Kotlin / Jetpack Compose 原生 Android（预览）
│   │   ├── core/            # 纯 Kotlin 协议、网络、模型与更新检查，可在 JVM 上测试
│   │   └── app/             # Compose 界面、数据存储与 Android 平台功能
│   └── flutter/             # 当前 Flutter 应用
│       ├── lib/             # 页面、组件、网络层、模型与工具
│       ├── assets/          # 应用资源
│       ├── test/            # 回归测试源码与合成夹具
│       ├── android/         # 现有 Flutter Android 工程
│       ├── windows/         # Windows 原生宿主
│       ├── ios/、linux/、macos/、web/
│       └── pubspec.yaml
├── docs/
│   ├── DEVELOPMENT.md       # 开发与验证说明
│   ├── media/               # README 横幅与界面截图
│   ├── THIRD_PARTY_NOTICES.md
│   └── licenses/            # 第三方许可原文
├── LICENSE
└── README.md
```

## 本地开发

### Kotlin / Compose 原生 Android（`apps/android`）

工具链：Gradle 9.7.1（wrapper）、AGP 9.4.1、Kotlin 2.3.21、compileSdk 37、minSdk 24、JDK 21。
依赖版本集中在 `apps/android/gradle/libs.versions.toml`。界面使用 Compose BOM 2026.09.00 与
material3 `1.5.0-beta01`（Material 3 Expressive 组件目前在该版本线提供）。
Android SDK 需安装 `platforms;android-37.0` 与 `build-tools;37.0.0`，并在
`apps/android/local.properties` 写入 `sdk.dir`（该文件已被忽略）。

```bash
cd apps/android
./gradlew :core:test                 # 协议、签名、请求头、验证码、响应、本地关注、更新检查
./gradlew :app:testDebugUnitTest     # Robolectric 渲染真实界面并走合成数据
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

- `core` 不依赖 Android，移植并扩充了 Flutter 的网络回归测试；`token_v3.json` 复用 Flutter 的同一组合成签名向量。
- `app` 的界面测试使用合成响应，不访问网络；加 `-Proborazzi.record=true` 会在
  `app/build/outputs/roborazzi/` 写出各页面截图，便于检查布局。
- 在线检查默认跳过：`COOLAPK_LIVE_PROBE=1 ./gradlew :core:test --tests '*LiveProbe*'`，
  只以游客身份读取首页与一条动态详情，输出结构摘要，不输出内容或凭据。
- 若 Robolectric 下载 Android 运行时受限，可在 `~/.gradle/gradle.properties` 设置
  `robolectric.repo=<Maven 镜像地址>`。
- 版本名与版本号读取 `apps/flutter/pubspec.yaml`，与 Flutter 发布线保持一致；只生成单个通用 APK（无原生库）。
- release 签名沿用下文 `KEYSTORE`、`KEY_ALIAS`、`KEYSTORE_PASSWORD`/`KEY_PASSWORD`/`REVIA_KS_PASS`
  环境变量或 `apps/android/key.properties`；缺少签名时 release 构建直接失败。release 开启 R8 压缩。
- 预览包名 `com.zailinn.c001apk.compose`，与 Flutter 包（`com.zailinn.c001apk`）并存；
  本地数据（DataStore）与 Flutter 的 Hive 数据互不迁移。预览版不自动提示 c001apk 更新，
  以免把 Flutter 正式包当作更新，手动检查仍可用。

### 应用更新检测

设置 → 存储与更新提供“自动检查 c001apk 更新”，默认开启，启动后后台检查，
每 24 小时最多请求一次；关闭后不再自动请求。该开关与 Android 的
“检查已安装应用更新”独立。存储与更新、关于页均可手动检查，手动操作不受
自动开关、跳过版本或稍后提醒影响。自动检查失败不打扰，手动失败显示中文原因。

更新来源为 `ZaiLinn/c001apk` 的 GitHub Latest 正式 Release；
不检测 Actions artifacts、草稿或预发布版本。按语义版本比较，兼容
`1.1.0-release` / `v1.2.0` 标签，不比较 Release 时间或 Android ABI 版本码。
同一语义版本重新构建不触发更新提示，发布修订版时应提升语义版本。

弹窗展示版本、发布日期、完整可滚动说明和发布页入口，底部只提供“稍后”
（24 小时）、“跳过”（当前版本）、“更新”。自动检测仅在设置中关闭。
关闭弹窗等同稍后。Android 按设备支持 ABI 匹配 APK，Windows/Linux
匹配现有 x86_64 ZIP/tar.gz；没有匹配资产时打开该 Release 页面。
下载通过系统浏览器进行，资产链接打开失败会尝试发布页，不自动覆盖安装。
iOS 当前没有可安装正式包，只打开发布页面。

实现位于 `lib/logic/app_update/` 和 `lib/components/update_*.dart`，
使用独立 Dio，不加入酷安拦截器、Cookie、账号 Token 或设备签名。
提醒状态保存在既有 Hive settings 中的独立键，保留原账号、历史及收藏。
`test/update/` 使用合成 Release、可控时间、临时 Hive 和浏览器通道验证，
普通测试不访问 GitHub，也不修改真实用户设置。

当前维护使用 **Flutter 3.24.5 / Dart 3.5.4**。依赖版本以 `apps/flutter/pubspec.lock` 为准，建议先使用相同工具链复现。

Windows 构建需要 Visual Studio 的 C++ 桌面开发组件及 Windows SDK。现有 Android 工程使用 Gradle 7.6.3、AGP 7.3.0、Kotlin 1.7.10 和 compileSdk 34，本地构建使用标准 JDK 17；请准备相应 Android SDK。这些要求适用于当前 Flutter 工程。

Windows 网页登录和人机验证使用 WebView2。构建前需将 `nuget.exe` 所在目录加入当前进程的 PATH；运行机器需安装 [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/)。WebView2 数据保存在应用支持目录的 `webview2/` 中，与 Hive 账号和设置分开。使用方式为登录页右上角“网页登录”，在酷安官方网页完成验证及登录后自动返回并保存账号；取消保留原账号。参考 [插件 Windows 配置](https://inappwebview.dev/docs/webview/in-app-webview/)。

`windows/cmake/webview_lifetime_fix.cmake` 修正 Windows WebView 插件0.6.0的退出资源释放顺序：配置时生成原生源文件的补丁副本，在平台消息队列仍运行时释放图形合成对象，避免退出时 `Unknown Hard Error`。不会改动全局 Pub 缓存；升级 WebView 插件时需复查并移除或更新此补丁。Windows 改动还应验证关闭窗口后进程正常结束：

```powershell
powershell -NoProfile -File tool/windows_close_smoke.ps1 `
  -ExePath build/windows/x64/runner/Release/c001apk.exe `
  -ReportPath windows-close-report.json
```

此脚本会实际启动及关闭桌面窗口，检查退出码和系统错误弹窗事件。运行前关闭已有实例；还需手动复核打开网页登录、取消后关闭，以及直接在网页登录页关闭窗口。

```powershell
git clone https://github.com/ZaiLinn/c001apk.git
cd c001apk/apps/flutter

flutter doctor -v
flutter pub get
flutter analyze
flutter test
```

所有 Flutter 命令均在 `apps/flutter/` 下执行。现有静态分析仍有待处理的历史提示，提交修改时请留意是否引入新问题。

运行与调试构建：

```powershell
flutter run -d windows
flutter build windows --debug
flutter build apk --debug
```

Windows 产物位于 `apps/flutter/build/windows/x64/runner/Debug/`，Android 调试 APK 位于 `apps/flutter/build/app/outputs/flutter-apk/app-debug.apk`。调试构建用于开发验证；正式发布时应配置自己的签名。

### Release 构建与 Android 签名

从 `apps/flutter/` 执行：

```powershell
flutter build windows --release
flutter build apk --release --split-per-abi
```

Windows 完整运行目录为 `build/windows/x64/runner/Release/`，分发时一起打包
EXE、DLL 和 `data/`；Android 分别生成 `build/app/outputs/flutter-apk/` 下的
`app-arm64-v8a-release.apk`、`app-armeabi-v7a-release.apk`、`app-x86_64-release.apk`。

后续发布和 CI 产物按平台、架构分别命名及分发：

| 平台 | 目标架构 | 本项目验证状态 |
| --- | --- | --- |
| Windows | `x86_64` | 本地及云端 release 已构建，下载包已复核 |
| Linux | `x86_64` | 云端 release 已构建，下载包已复核；运行体验待验收 |
| Android | `arm64-v8a`、`armeabi-v7a`、`x86_64` | 云端独立 APK 已构建、下载验签；结果见 Actions |
| iOS | `arm64`（真机） | 云端未签名 IPA 构建、架构与校验值检查已通过；Apple 签名与真机运行待实施 |

桌面 `x86_32` 和 ARM64 不列入本轮发布矩阵。
桌面 `x86_64` 通常也称 x64/amd64，`aarch64` 通常也称 ARM64；Android 使用其 ABI 命名。
当前固定 Flutter 3.24.5 的官方 Windows/Linux SDK 发布包仅有 x64，
Linux 还不支持 x64 到 ARM64 交叉构建，因此当前桌面 CI 使用 x86_64 runner。
工具链未来变化时重新核对这些限制，不据引擎支持声称本项目所有架构都能构建。
架构支持和 APK 拆分方式参见 [Flutter 平台支持](https://docs.flutter.dev/reference/supported-platforms)
及 [Android 发布指南](https://docs.flutter.dev/deployment/android#build-an-apk)。

Android release 必须配置正式签名，缺少 keystore、alias 或密码时构建失败。
debug 继续使用开发证书。以下环境变量在构建时读取：

| 环境变量 | 用途 |
| --- | --- |
| `KEYSTORE` | JKS 的路径，建议绝对路径 |
| `KEY_ALIAS` | 签名 alias |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_PASSWORD` | 私钥密码 |
| `REVIA_KS_PASS` | 两项专用密码变量未分别设置时的统一密码 |

本地也可以复制 [key.properties.example](../apps/flutter/android/key.properties.example)
为 `android/key.properties`，仅填写 `storeFile` 和 `keyAlias`；这个本地文件已被
Git 忽略。密码通过环境变量提供，勿写进该文件或提交到仓库。
含中文或日文的路径/alias 使用 Properties 的 `\uXXXX` 转义，或通过环境变量传入。
Windows 用户级变量需要注入当前进程，例如：

```powershell
$env:REVIA_KS_PASS = [Environment]::GetEnvironmentVariable('REVIA_KS_PASS', 'User')
flutter build apk --release --split-per-abi
```

GitHub Actions 配置见 [云端构建指南](CI.md) 与 [Build workflow](../.github/workflows/build.yml)。
普通 push/PR 执行离线检查、Windows/Linux x86_64 release 和 Android debug 构建；
手动运行可生成正式签名的 Android 三 ABI APK，并选择构建 iOS arm64 未签名 IPA（安装前需自行签名）。
Android 签名使用 Actions Secrets `ANDROID_KEYSTORE_BASE64` 和 `REVIA_KS_PASS`：
runner 临时恢复 JKS，并以 `KEYSTORE`、`KEY_ALIAS` 和密码环境变量传给构建。
JKS、密码及其 Base64 不进入 Git 历史、公开附件或构建 artifacts，临时 JKS 在结束时清理。
Base64 只负责二进制转文本，保密依靠 Secrets；具体方式见
[GitHub 官方 Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
Apple 签名另外配置，Android 密钥不用于 iOS。

Android 当前 applicationId 为 `com.zailinn.c001apk`（与上游区分，可与上游版本共存），namespace 仍为 `app.awavenue.c001apk`，仍使用 Flutter 宿主。
它与旧包 `com.example.c001apk.flutter` 独立安装；旧包的账号、历史、收藏和设置不会自动迁移，新包需要重新登录。
酷安协议中的 `X-App-Id: com.coolapk.market` 保持其协议用途。

模型发生变化后，在 Flutter 工程目录生成代码：

```powershell
dart run build_runner build --delete-conflicting-outputs
```

### 测试

普通 `flutter test` 使用离线回归与合成设备夹具，在线检查默认跳过。测试覆盖请求签名、头部隔离、存储迁移、响应错误、游客详情回退、中文界面及缓存清理等。

账号回归还覆盖不完整登录检查、失败资料读取、验证期间切换账号、验证码取消和单次重试、文本写请求编码、私密发布字段，以及网页登录 Cookie 清理。
登录后受限详情可读取公开正文，界面会说明账号点赞状态尚未取得；Android/iOS 提供手动人机验证入口，Windows 通过 WebView2 提供网页登录与人机验证页面。完成验证不保证服务端解除其他风控。

需要主动检查游客只读接口时，可单独执行：

```powershell
$env:COOLAPK_LIVE_PROBE = '1'
flutter test test/live/api_probe_test.dart
Remove-Item Env:COOLAPK_LIVE_PROBE
```

游客动态、文章详情检查使用 `COOLAPK_GUEST_DETAIL_PROBE=1` 和 `test/live/guest_detail_test.dart`。在线检查的脱敏结构结果写入 Flutter 工程下忽略的 `.local/reports/`；验证码或业务拒绝会如实导致检查失败。

若代理配置影响本机测试连接，可在当前 PowerShell 会话设置 `$env:NO_PROXY = 'localhost,127.0.0.1,::1'` 后重试。

## 账号管理与设置

主导航为“主页、关注、我的”，宽窗口侧栏同步显示。“关注”展示关注流，原主页顶部不再保留“关注”。各页右上角可打开独立设置页。设置按“外观与字号、关注与内容、浏览与隐私、存储与更新、协议与设备”分类；窄窗口进入分类子页，宽窗口并排显示分类与内容。

“我的”和设置页均提供“账号管理”。首次升级自动保存已有有效登录；添加账号成功后保留此前登录，点击已保存账号即可切换。“使用游客模式”保留已保存的账号，方便恢复；移除账号会清除该账号在本机保存的登录，移除当前账号后进入游客模式。登录失效的账号会提示重新登录。

账号切换会关闭旧账号的详情等页面，重新加载主页、关注和我的，并丢弃旧请求的晚到响应。历史、本地收藏、屏蔽设置和本地关注继续由本机共用。会话信息沿用本机 Hive 存储，账号密码不保存。

界面标题、设置标题与说明参考 PiliPlus 的文字层级；“外观与字号 → 字体大小”可调节应用字号，并与系统文字缩放叠加。

## 本地关注与悄悄关注

游客可在用户主页点击“本地关注”，在底栏“关注”查看名单中用户的动态。登录后，在“设置 → 关注与内容”中开启“悄悄关注”，长按用户主页的关注按钮添加或取消；短按仍操作账号关注。“关注”页可切换“账号关注”和“悄悄关注”。

设置提供名单管理入口。名单只保存在当前设备，不会自动同步到账号；登录、退出或关闭悄悄关注开关不会清空名单。当前支持用户，暂不包含话题、数码和应用。本地关注不需要登录，读取动态仍需要网络，并受接口权限、验证码或风控限制。

## 参与维护

欢迎提交 [Issue](https://github.com/ZaiLinn/c001apk/issues) 或 Pull Request。反馈问题时请提供平台、应用版本、复现步骤与脱敏错误信息，避免附带密码、Cookie、用户 Token 或私信内容。

API 修改请区分网络错误、HTTP 状态、业务拒绝、验证码和模型解析错误，并保留离线回归。修改存储时保留用户历史、收藏与设置；修改模型时使用生成器更新 `*.g.dart`。
