# 开发指南

[返回 README](../README.md)

## 项目结构

```text
.
├── app/                     # Android 应用：Compose 界面、数据存储、导航
│   ├── src/main/kotlin/…/
│   │   ├── data/            # DataStore 设置、账号、历史收藏与黑名单
│   │   ├── semantic/        # 语义屏蔽：模型下载、校验、加载
│   │   └── ui/              # 主题、导航、卡片、各页面
│   ├── src/semantic/java/   # ONNX Runtime 适配器，单独编译为资产，见下文
│   └── src/test/            # Robolectric 界面测试与 Roborazzi 截图
├── core/                    # 纯 Kotlin：协议签名、网络、模型、过滤、更新检查，可在 JVM 上测试
├── gradle/libs.versions.toml
└── gradle.properties        # 含应用版本号
```

## 环境

- JDK 21，Gradle 9.7.1（wrapper），AGP 9.4.1，Kotlin 2.3.21
- Android SDK：`platforms;android-37.0` 与 `build-tools;37.0.0`，在 `local.properties` 写入 `sdk.dir`
- compileSdk 37、targetSdk 36、minSdk 24（Android 7.0）
- 界面使用 Compose BOM 2026.09.00 与 material3 `1.5.0-beta01`（Material 3 Expressive）

依赖版本统一放在 `gradle/libs.versions.toml`。

## 常用命令

```bash
./gradlew :core:test                 # 协议、签名、请求、验证码、响应、过滤、语义匹配、更新检查
./gradlew :app:testDebugUnitTest     # Robolectric 渲染真实界面，使用合成数据，不联网
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assemblePreview       # 与 release 相同的 R8 优化，用调试密钥签名
./gradlew :app:assembleRelease       # 正式包，需要签名配置
```

- 判断滑动流畅度请用 `assemblePreview` 的包。debug 包可调试且未经 R8 优化，列表会明显卡顿。
- 界面测试加 `-Proborazzi.record=true`，会在 `app/build/outputs/roborazzi/` 写出各页面截图。
- 在线检查默认跳过：`COOLAPK_LIVE_PROBE=1 ./gradlew :core:test --tests '*LiveProbe*'`。
  它只以游客身份读取首页与一条动态详情，输出结构摘要，不输出内容或凭据。
- Robolectric 下载 Android 运行时受限时，可在 `~/.gradle/gradle.properties` 设置
  `robolectric.repo=<Maven 镜像地址>`。

## 版本与签名

版本在 `gradle.properties` 中维护：

```properties
c001apk.versionName=2.0.0
c001apk.versionCode=2026101501
```

`versionCode` 采用 `yyyyMMddNN`，必须持续递增。最后一个 Flutter 版本 1.2.0 用的是 `20261015`。

release 构建从环境变量或根目录的 `key.properties`（已被 Git 忽略）读取签名，缺少签名时直接失败：

| 环境变量 | `key.properties` 键 | 内容 |
| --- | --- | --- |
| `KEYSTORE` | `storeFile` | JKS 路径 |
| `KEY_ALIAS` | `keyAlias` | 签名 alias |
| `KEYSTORE_PASSWORD` | `storePassword` | keystore 密码 |
| `KEY_PASSWORD` | `keyPassword` | 私钥密码 |

`REVIA_KS_PASS` 可同时代替两个密码。只有正式签名的 release 包会自动检查 GitHub 更新，
debug 与 preview 包不会提示安装正式版。

## CI 与发布

**[Android workflow](../.github/workflows/android.yml)**：push 到 `main` 和 PR 时运行（只改文档时跳过）。
它执行 `core` 与界面测试，上传 debug APK（保留 7 天）。

**[Release workflow](../.github/workflows/release.yml)**：发布正式版本。

1. 在 `gradle.properties` 提升 `c001apk.versionName` 与 `versionCode`。
2. 在 [CHANGELOG.md](../CHANGELOG.md) 顶部新增 `## <版本>` 小节，写本次更新的功能。
   这一节就是 Release 页面的说明；缺少时发布直接失败。
3. 合入 `main` 后，选择下面任一方式发布：
   - 在 Actions → Release → Run workflow 从 `main` 运行，会自动创建 `<版本>-release` tag；
   - 或推送 tag：`git tag 2.0.1-release && git push origin 2.0.1-release`（也可用 `v2.0.1`），tag 必须与版本一致。

workflow 会检查同名 Release 不存在，用正式签名构建 release APK 并核对证书指纹。
然后只上传 `c001apk-<版本>-android-universal.apk`，创建正式 Release 并设为 Latest；SHA-256 校验值打印在运行日志中。
应用内更新按这个文件名匹配。构建失败时不会创建 Release，修复后重新运行即可。
需要不改版本号重新发布时，从 `main` 手动运行并勾选 `replace`，会先删除同名 Release 和 tag 再重新创建。删除 Release 后残留的同名 tag 也要用 `replace` 才能重新发布，否则会被拒绝。

正式签名使用仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 JKS 的单行 Base64 |
| `REVIA_KS_PASS` | store 与 key 共用的密码 |

正式证书的 alias 为 `c001apk`，SHA-256 指纹为
`7529ACEF89DD527EE11CB33F45D25827F04AA7057FB61798E24BE645FCC63408`。
workflow 先在临时目录恢复 JKS 并核对证书，构建后再用 `apksigner` 复核，最后删除临时材料。
使用自己密钥的 fork 需要同时修改 alias 与期望指纹。

## 内容过滤

过滤在 `core/…/model/ContentFilter.kt` 中对每页列表统一执行，顺序如下：

1. **黑名单**：用户黑名单和“我屏蔽的话题”（`TopicRule`，按话题名精确匹配或 `#话题#`；
   `contains` 规则匹配名称含有该词的话题）直接移除。分页游标仍按服务器原始 ID 推进，整页被移除时会继续加载下一页。
2. **敏感话题**（`SensitiveRules.kt`）：按分类匹配关键词、话题和句式正则。
   分类保存在本机（`LocalContent.filterCategories`），用户可以编辑、新建和删除，
   内置分类可以恢复默认。命中的条目打上折叠标记，列表显示为一行，点击展开；
   关闭“折叠”（`AppSettings.filterFold`）时则从列表中移除。
3. **语义折叠**（`core/…/semantic/`）：只处理前两步没有命中的动态和回复，折叠或隐藏同第 2 步。

`ContentFilter.apply` 把因规则移除的条目交给 `onHidden`，应用里记入 `HiddenLog`
（设置 → 内容过滤 → 最近隐藏，最多 200 条，仅本机）。

语义折叠使用 [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) 的 int8 ONNX 版本。
它对文本做均值池化，再和各分类的“语义例句”比较。只有和例句足够相似（默认阈值 0.55），
并且明显比日常酷安话题更像（差值 0.10）时才命中。日常话题列表在 `SemanticMatcher.kt` 中。

### 运行库为什么单独打包

安装包里不含 ONNX Runtime 的原生库，它们在用户点击下载时才获取：

- 模型从 hf-mirror / Hugging Face 下载。
- 原生库通过 HTTP Range 只从 Maven 上的 AAR 中读取当前 ABI 的两个 `.so`，
  依次尝试阿里云镜像、Maven Central、Google 镜像。
- 所有文件的大小和 SHA-256 都固定在 `SemanticSpec.kt` 中，任何镜像都只能提供完全相同的内容。

ONNX Runtime 在 Android 上用 `System.loadLibrary` 加载 JNI 库，只会在调用方的 ClassLoader 路径中查找。
因此构建时由 `semanticRuntimeDex` 任务（`app/build.gradle.kts`）把 ONNX Runtime 的 Java API
与 `app/src/semantic/java` 下的适配器单独编译为 `semantic-runtime.jar` 资产（约 50 KB）。
应用用一个库路径指向下载目录的 `DexClassLoader` 加载它，并通过反射调用适配器。
这样 R8 不会把这些类合并进应用自身的代码。

升级 ONNX Runtime 时需要同时更新 `libs.versions.toml` 中的版本，以及 `SemanticSpec.kt` 中的
URL、大小与哈希。更换文件时还要修改 `version`，安装目录随之更换。

## HDR 与实况图

`GalleryMedia` 把原图、预览、SDR 高清、HDR/实况标记和所属动态/回复 ID 一起传入查看器。
旧的 URL 列表路由仍可用。实况视频通过 `livePhoto/showVideo` 解析，API 不跟随视频重定向，
媒体客户端只携带 User-Agent，通过 HTTPS 下载，不向 CDN 发送账号 Cookie 或 API 签名。

查看器仅在 Android 14 及以上且屏幕支持 HDR 时启用 HDR window color mode，退出恢复 SDR。
按住 HDR 按钮只切换显示模式，不重新下载或解码。高清与预览切换复用缩放状态。
实况自动播放默认仅 WiFi（需通过系统网络验证），静音播放一次；手动播放可循环、暂停、开声音。
切页、进入后台或退出查看器会停止播放器并释放声音焦点。

`core/media/MotionPhoto.kt` 实现 Android Motion Photo 的 JPEG + XMP + MP4 封装，
保留 JPEG 编码、HDR 增益图和其他 XMP，并修正插入 XMP 后的 MPF 偏移。
仅保存照片时移除视频及实况标记，保持 HDR 数据；非 JPEG 图片合成实况时转换为 JPEG。
下载和转换有大小限制，写入采用临时文件；相册写入完成后才清除 `IS_PENDING`，失败删除记录。

离线测试覆盖格式往返、MPF 偏移、HDR/SDR 切换、长按对比、播放控制、自动播放关闭和导出选项。
Robolectric 不支持真实的增益图 JPEG 编解码，测试用两个可解码的 JPEG 流验证增益图数据及索引保留。
HDR 亮度、设备视频解码和各厂商相册的 Motion Photo 识别需在真机验收。
只读在线验证可用 `COOLAPK_MEDIA_PROBE=1 ./gradlew :core:test --tests '*MediaProbeTest'`，
它只打印媒体数量和状态，不记录内容、签名媒体地址或账号信息。

## 动态备份

功能规格参考 [#22](https://github.com/ZaiLinn/c001apk/issues/22) 附件
`c001apk-Cookie.-.md` 第 4、7 节，按当前 Compose/DataStore 架构独立实现。
用户操作与格式说明见 [FEED_BACKUP.md](FEED_BACKUP.md)。

- `core/backup/` 负责稳定动态 ID、原始 JSON 兼容解析、图片清单、离线 URL 改写，以及不经过 UI 过滤或摘要回退的回复分页。
- `backup/FeedBackupStore.kt` 负责 JSON/ZIP、大小与校验、备份索引及取消/失败清理。SAF 目录是索引的真实来源，本地收藏保持独立。每份新备份记录确切的 URL 与文件名映射，旧格式仅能按文档顺序恢复图片。
- `backup/BackupDirectory.kt` 用 DocumentsContract 访问目录；API 26 以上使用 Bundle 查询接口，较早版本使用旧接口。目录选择器持久保存读写授权。
- `backup/BackupScreens.kt` 处理全局备份请求、重复备份选择、进度和离线页面。发布时先写 ZIP、最后写 JSON，扫描不会把只写了一半的文件对当成成功备份；替换在发布成功后进行。
- `BackupImageFetcher` 只从应用自己的解包目录读取 `c001apk-backup://`，不回退到网络。离线渲染阻止未映射图片的网络加载，看大图和保存图片也复用本地文件。

回归测试覆盖文件往返、取消及失败回滚、旧格式、目录恢复、系统目录授权、重复备份对话框、离线文章/回复和本地大图，并断言离线过程没有 API/CDN 请求。

## 收藏归档索引与队列

实施方案见 [FAVORITES_ARCHIVE_PLAN.md](FAVORITES_ARCHIVE_PLAN.md)。备份格式版本 2 支持图片档位、可选回复和列表指纹，仍兼容版本 0/1。批量默认原图、包含回复、不限制 WiFi；自动收藏备份默认关闭。

`ArchiveIndex` 使用系统 SQLite，文件为 `filesDir/archive/index.db`，不依赖 Room 或 FTS。`backup` 缓存最新备份的元数据及归一化全文，`source` 保存账号与收藏夹关系，`queue` 和 `meta` 持久化任务及分页检查点。原 DataStore 列表仅用于迁移，完成扫描后移除旧键。初始列表读取、迁移和队列恢复在 IO 线程执行，目录操作等待初始化完成；图片加载器只持有备份存储的延迟供应函数，注册时不打开数据库。目录增量扫描比较 JSON 的修改时间和大小；全量重建会重新解析正文。数据库可以重建，SAF 目录里的 JSON/ZIP 是正文与图片的真实数据；私有索引丢失后云端来源关系需再次同步。

`ArchiveSync` 和手动备份共用目录写入约束，顺序执行并限制请求间隔。验证码异常只保存恢复所需的请求上下文，验证由用户主动触发；不会自动重试验证码。切换账号暂停旧账号队列。进程启动只恢复任务状态，不自动联网。`ArchiveExporter` 只读本地文件，HTML 清洗与索引文本提取在 core 中保持纯函数，导出 ZIP 里的图片校验 SHA-256。

归档测试全部使用合成数据、FixtureTransport 和 Robolectric，不索要、保存或使用测试账号凭据，不进行在线批量探测。覆盖档位、索引增量扫描、多词搜索、来源隔离、在线失败回退、队列恢复与节流、验证码暂停、导出清洗/分卷及导出不联网。

## 参与维护

修改 API 时请区分网络错误、HTTP 状态、业务拒绝、验证码和解析错误，并补充离线回归测试。
修改存储时保留用户的历史、收藏与设置。提交前运行 `:core:test` 与 `:app:testDebugUnitTest`。
