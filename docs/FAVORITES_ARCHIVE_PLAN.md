# 收藏归档：云端收藏同步、本地搜索与导出（实施方案）

本文是交给实现者的方案。实现时以本文和仓库现状为准；与现有代码冲突时，以不破坏现有行为为先，并在 PR 中说明。

## 1. 目标与结论

用户需求：把云端收藏（收藏夹）中的帖子文字和图片缓存到本地，能搜索，能导出 txt/html。

结论：**不新建第三套存储**。仓库已有“动态备份”（`docs/FEED_BACKUP.md`，`backup/FeedBackupStore.kt`、`core/backup/*`）：

- 备份写入用户选择的 SAF 目录，每条动态一对 `feed_<id>.json` + `feed_<id>.zip`；
- 已支持离线阅读、去重、替换、从目录重建列表。

本方案在它之上增加：

1. **批量队列**：把云端收藏夹、本地收藏中的动态排队备份，可暂停、可续传；
2. **备份档位**：仅文字 / 文字和缩略图 / 文字和原图，可选是否包含回复；
3. **同步**：云端收藏 → 本地备份的单向同步，按指纹跳过未变化的动态；
4. **搜索索引**：应用私有目录中的 SQLite 索引，可随时从备份目录重建；
5. **搜索界面**：搜索本地收藏和已备份的云端收藏，失效动态回退到本地备份；
6. **导出**：从备份生成 TXT、HTML 或 HTML+图片 ZIP。

原提案的两种方案在此合并：方案 1 的“本地收藏”和方案 2 的“索引文件”都由“备份目录 + 私有索引”承担。备份目录是唯一真实数据，索引只是可重建的缓存。

### 非目标

- 不做云端收藏的双向同步，不修改云端收藏夹内容。
- 不做后台常驻同步：不用 WorkManager，不用前台服务。同步只在应用运行时进行，进程结束后下次打开可续传。
- 不备份独立视频和分离的实况视频（与现有备份一致）。
- 不改变单条“备份”菜单的现有行为（仍是完整备份：原图 + 全部回复）。

## 2. 阶段 0：在线探测（可选，不阻塞实现）

维护者没有可用于测试的账号，也不愿用主账号冒封禁风险。因此：

- **实现不得依赖探测结论。** 第 5 节所有规则都按下面的保守默认值实现，探测只用于以后放宽限制。
- **实现者（包括 AI 助手）不得索要、保存或使用任何账号凭据。** 测试全部离线，使用 `FixtureTransport`/`Harness` 中的合成数据。
- 不做验证码阈值探测，不做任何批量或高频的在线请求。

### 保守默认值

| 未确认的问题 | 默认处理 |
|---|---|
| 默认收藏夹 | 同时请求 `showDefault=0` 和 `showDefault=1`，按收藏夹 id 合并去重；读不到的收藏夹在同步页显示“无法读取”，不影响其他收藏夹 |
| itemList 分页 | 按 `page` 递增并带上 `lastItem`；空页、`noMoreDataCard` 或连续两页内容完全重复时结束；最多 500 页 |
| 条目类型 | 只处理 `entityType == "feed"` 且 id 为纯数字的条目，其余计入“已跳过” |
| 编辑判定 | 指纹用 `message` + `picArr` + `message_title`（存在时）；另在同步页提供“全部重新备份”选项，供怀疑漏更新时使用 |
| 已删除动态 | 按 6.3 的规则：业务错误视为失效，网络错误不视为失效 |
| 请求间隔 | 两次详情请求之间随机等待 3–5 s；每 50 条额外暂停 30 s；单次同步最多 500 条，超出部分留在队列，下次继续 |

### 维护者自行验证（可选）

如果以后愿意小范围验证，**不需要探测脚本，也不需要导出凭据**：在自己手机上安装 debug 版，正常登录，新建一个只放 3–5 条动态的测试收藏夹，执行一次同步，确认结果正确即可。这和平时浏览收藏夹的请求量相当。

## 3. 数据模型

### 3.1 备份文件（`core/backup/FeedBackup.kt`）

`formatVersion` 升到 `2`。新增字段，全部有默认值，旧文件可照常解析：

```kotlin
@Serializable
enum class BackupImageQuality { None, Preview, Original }

data class FeedBackup(
    // 现有字段不变
    val imageQuality: BackupImageQuality = BackupImageQuality.Original,
    val includesReplies: Boolean = true,
    /** 列表条目指纹，见 5.3；单条手动备份时为空。 */
    val listFingerprint: String = "",
)

data class BackupImage(
    // 现有字段不变
    /** 实际保存的地址；缩略图档位下与 url 不同。 */
    val savedUrl: String = "",
)
```

- `images[].url` 仍记录原始地址，离线阅读按原始地址映射，所以缩略图档位下离线页显示缩略图而不缺图。
- `includesReplies = false` 时，离线页回复区显示“此备份未包含回复”，不能显示成“0 条回复”。
- `imageQuality = None` 时不写 ZIP，或写一个空 ZIP。实现者二选一，但扫描、打开、删除都要兼容“只有 JSON 没有 ZIP”（`FEED_BACKUP.md` 已说明没有 ZIP 时可阅读文字）。删除时 ZIP 不存在不应报错。

### 3.2 备份选项

```kotlin
data class BackupOptions(
    val images: BackupImageQuality = BackupImageQuality.Original,
    val replies: Boolean = true,
)
```

- `FeedBackupCollector.collect(id, options)`：`replies = false` 时只读详情，不分页读回复和楼中楼，但保留详情自带的 `topReplyRows`/`replyRows` 预览。
- `FeedBackupStore.save(...)` 接收 `BackupOptions`：
  - `Preview`：对 `image.coolapk.com` 的图片下载 `GalleryMedia.preview`（`.s.jpg`）；其他域名下载原图，单张超过 2 MiB 则跳过并写入 warning。
  - `None`：不下载图片。
- 头像在 `Preview`/`Original` 档位都保存缩略图即可。

### 3.3 归档索引（新增，应用私有）

文件 `filesDir/archive/index.db`，使用 `android.database.sqlite.SQLiteOpenHelper`，不引入 Room。索引可删除重建，数据库版本升级时允许直接清空并标记“需要重建”。

```sql
-- 每条已备份的动态一行（最新版本）
CREATE TABLE backup (
  feed_id TEXT PRIMARY KEY,
  base TEXT NOT NULL,              -- 备份文件名（不含扩展名）
  doc_modified INTEGER NOT NULL,   -- SAF 文档修改时间，增量扫描用
  doc_size INTEGER NOT NULL,
  saved_at INTEGER NOT NULL,
  uid TEXT, username TEXT,
  title TEXT,                      -- 文章标题或正文前 60 字
  body TEXT NOT NULL,              -- 归一化后的可搜索全文，见 3.4
  dateline INTEGER,
  image_quality TEXT NOT NULL,
  includes_replies INTEGER NOT NULL,
  list_fingerprint TEXT NOT NULL DEFAULT '',
  warnings INTEGER NOT NULL DEFAULT 0,
  online_state TEXT NOT NULL DEFAULT 'unknown'  -- unknown|ok|gone
);

-- 动态属于哪些收藏来源；一条动态可属于多个来源
CREATE TABLE source (
  feed_id TEXT NOT NULL,
  kind TEXT NOT NULL,              -- 'local' | 'cloud'
  account_uid TEXT NOT NULL DEFAULT '',   -- local 为空
  collection_id TEXT NOT NULL DEFAULT '', -- local 为空
  collection_title TEXT NOT NULL DEFAULT '',
  added_at INTEGER NOT NULL,
  removed_at INTEGER,              -- 云端已取消收藏时记录，不删行
  PRIMARY KEY (feed_id, kind, account_uid, collection_id)
);

-- 同步队列，进程重启后续传
CREATE TABLE queue (
  feed_id TEXT PRIMARY KEY,
  options TEXT NOT NULL,           -- BackupOptions 的 JSON
  list_fingerprint TEXT NOT NULL DEFAULT '',
  state TEXT NOT NULL,             -- pending|running|done|failed|skipped
  attempts INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  enqueued_at INTEGER NOT NULL
);
```

- 现有 `FeedBackupStore` 的 DataStore 索引（`INDEX`，`List<BackupRecord>`）在动态多达几千条时会成为瓶颈：每次保存都要重写整个列表。`records` 改为从 `backup` 表读取；DataStore 中的旧索引只用于首次迁移，迁移完成后删除该键。
- `scan()` 改为增量：列出目录后，只解析 `doc_modified` 或 `doc_size` 变化、以及新增的 JSON；目录中已不存在的行删除。全量重建作为“同步备份列表”按钮的后备选项保留。
- 本地收藏 `favFeedV1` 不迁移数据，只在启动时把其中的 id 同步写入 `source(kind='local')`。本地收藏的增删由 `LocalContent.toggleFavorite/removeFavorite/clearFavorites` 同步更新 `source` 表。

### 3.4 可搜索文本

`body` 由备份 JSON 生成，放在 `core` 中实现纯函数，便于单元测试：

- 来源字段：`message`（HTML 转纯文本，复用 `htmlToPlain` 的逻辑或在 core 中用 Jsoup 实现等价版本）、文章正文（`message_raw_output` 中的文本段落）、`message_title`、作者名、`tags`/`ttitle`、话题名；`includesReplies` 时附加回复正文，单条备份最多取前 200 KB，避免索引过大。
- 归一化：`Normalizer.Form.NFKC`（统一全角/半角）、转小写、连续空白合并。
- 查询时同样归一化关键词，按空格拆成多个词，每个词都要命中（`body LIKE ? ESCAPE '\'`，转义 `%` `_` `\`）。几千条数据下 LIKE 足够快；**不要用 FTS**：FTS4 默认分词不切中文，FTS5 在部分设备的系统 SQLite 中不可用。

## 4. 架构与文件

```text
core/backup/FeedBackup.kt          formatVersion 2、BackupImageQuality、BackupOptions
core/backup/FeedBackupCollector.kt collect(id, options)
core/backup/BackupText.kt          新增：可搜索文本、标题、导出用纯文本（纯函数）
core/backup/ArchiveExport.kt       新增：TXT/HTML 渲染（纯函数，输入 FeedBackup，输出字符串/流）
core/net/CoolapkRepository.kt      collectionItems(id, page)：收藏夹条目分页
app/backup/ArchiveIndex.kt         新增：SQLite 索引（backup/source/queue）
app/backup/FeedBackupStore.kt      接收 BackupOptions；records 改读 ArchiveIndex；增量扫描
app/backup/ArchiveSync.kt          新增：云端收藏对比、入队、顺序执行、暂停/续传
app/backup/ArchiveExporter.kt      新增：SAF 写出 TXT/HTML/ZIP
app/backup/ArchiveScreens.kt       新增：同步页、搜索页、导出页
```

`AppContainer` 中创建 `ArchiveIndex`、`ArchiveSync`、`ArchiveExporter`，与 `FeedBackupStore` 共用同一个 `scope` 和 mutex 规则：同一时间只有一个写备份目录的任务。单条手动备份进行中时同步队列暂停，反之亦然；提示“请先完成当前备份”。

## 5. 同步云端收藏

### 5.1 入口

- “我的 → 我的收藏”顶栏菜单新增“同步到本地备份”；
- “设置 → 我的备份”新增同一入口；
- 未登录时不显示；未选择备份目录时先走现有的选择目录流程，选择后自动继续。

### 5.2 同步页（`ArchiveSyncScreen`）

- 选项：备份档位（仅文字 / 文字和缩略图 / 文字和原图，默认“文字和原图”）；“包含回复”开关（默认开）；“仅在 WiFi 下下载图片”开关（默认关；`ImageViewerScreen.kt` 中的 `rememberWifi` 目前是 private，提取到 `ui/common` 后复用，判定规则不变；在非 WiFi 下图片档位视为“仅文字”并在完成后提示可补全）。
- 收藏夹多选列表，默认全选，显示每个收藏夹的条目数（来自 `collection/list`）。
- 开始后显示：阶段（读取收藏列表 / 备份动态）、`已完成/总数`、当前失败数、预计剩余时间（按最近 10 条平均耗时）；按钮：暂停 / 继续 / 取消。
- 完成后汇总：新增 N、更新 N、未变化跳过 N、失败 N（可展开查看原因，可“重试失败项”）、云端已取消收藏 N。

### 5.3 对比规则

1. 分页读取选中收藏夹的全部条目（只保留 `entityType == "feed"` 且 id 为纯数字的条目；其他类型计入“已跳过”并在汇总中说明）。
2. 每个条目计算 `listFingerprint = sha256(id + message + picArr + message_title)`（见第 2 节）。
3. 对每条动态：
   - 索引中没有 → 入队；
   - 已有，但 `list_fingerprint` 不同 → 入队（替换旧版本）；
   - 已有，但档位低于本次选择（例如已有“仅文字”，本次选“原图”；或本次要求回复而旧备份没有）→ 入队；
   - 其他 → 跳过。
4. 写入或更新 `source(kind='cloud', account_uid, collection_id)`。本次读到的收藏夹中，`source` 里存在但列表中已不存在的行设置 `removed_at`，**不删除备份**。
5. 某个收藏夹的列表读取中途失败：保留已读取部分入队，但该收藏夹**不做**“已取消收藏”标记，避免误判。

### 5.4 执行

- 队列顺序执行，一次一条，调用 `FeedBackupStore.save(id, replace = true, options)`；替换语义与现有一致：新版本写成功后才删除旧版本。
- 请求间隔按第 2 节默认值：两条之间随机 3–5 s，每 50 条暂停 30 s，单次最多 500 条。图片下载走 CDN，不受此间隔限制。
- 失败处理：
  - `ApiFailureKind.Captcha`：**暂停整个队列**，界面提示“需要完成验证后继续”，按钮触发现有验证码流程（`CaptchaCoordinator`）；不自动重试。
  - 网络错误：该条 `attempts + 1`，最多 3 次，指数退避（5 s、20 s、60 s）；仍失败则标记 `failed`，继续下一条。
  - 业务错误（动态不存在、无权限）：标记 `failed`，并把 `backup.online_state` 置为 `gone`（若已有旧备份）。
- 每条完成后立即更新 `queue` 和索引，进程被杀后下次打开同步页显示“上次同步未完成，继续？”。
- 同步期间应用退到后台不主动停止；被系统结束后按续传处理。

### 5.5 收藏时自动备份

设置“备份与收藏”分组新增“收藏时自动备份”：关闭（默认）/ 仅文字 / 文字和缩略图 / 文字和原图；“包含回复”沿用 5.2 的开关值。

- 触发点：`CollectionPickerSheet` 中 `setCollected(..., true)` 成功后；`FeedViewModel.toggleFavorite()` 添加本地收藏成功后。
- 行为：把该动态入队（同 5.4 执行），不弹出现有的“取消 / 均保留 / 替换”对话框，已有备份时按 5.3 规则决定是否替换。
- 未选择备份目录时只提示一次“开启自动备份需要先选择备份目录”，并提供跳转，不阻塞收藏操作。
- 取消收藏不删除备份，只更新 `source.removed_at`。

## 6. 搜索

### 6.1 入口

- 本地收藏页（`HistoryScreen(favorites = true)`）顶栏新增搜索图标；
- “我的收藏”顶栏新增搜索图标；
- “我的备份”页顶栏新增搜索图标。

三处打开同一个 `ArchiveSearchScreen`，分别预设范围筛选为“本地收藏”、“云端收藏（当前账号）”、“全部备份”。

### 6.2 搜索页

- 搜索框（输入停止 300 ms 后查询，在 IO 线程执行）；范围筛选：全部 / 本地收藏 / 云端收藏 / 指定收藏夹；排序：相关（命中词数，再按时间）/ 发布时间 / 备份时间。
- 结果项：作者、发布时间、标题或命中片段（关键词前后各 40 字，关键词高亮）、标签（本地收藏 / 收藏夹名 / 已取消收藏 / 仅文字 / 有缺失 / 已失效）。
- 本地收藏中尚未备份的动态：只能按 `FeedRecord.message` 的 150 字预览搜索，结果标注“未备份全文”，列表底部提供“备份全部本地收藏”按钮（入队，选项同 5.2）。
- 空索引时提示去同步或选择备份目录。

### 6.3 点击结果

1. 打开在线动态 `FeedRoute(id)`，新增参数 `backupFallback = true`。
2. `FeedScreen` 在线加载失败时：
   - 验证码：照常走验证流程，不回退；
   - 业务错误（动态不存在、已删除、无权限）或网络错误，且存在本地备份：直接显示离线备份，顶部横幅“在线动态无法打开：<原因>。正在显示 <备份时间> 的本地备份”，提供“复制全文”和“重试在线”。业务错误时把 `online_state` 置为 `gone`；
   - 无备份：保持现有错误页。
3. 结果项长按菜单：打开本地备份、打开在线动态、复制全文、删除备份（需确认，与现有删除一致）。

## 7. 导出

入口：“设置 → 我的备份 → 导出”，以及搜索页顶栏菜单“导出当前结果”。

### 7.1 选项

- 范围：全部备份 / 本地收藏 / 云端收藏 / 指定收藏夹 / 当前搜索结果；
- 格式：TXT / HTML（仅文字）/ HTML 含图片（ZIP）；
- 是否包含回复（只对包含回复的备份生效）；
- 排序：发布时间升序或降序。

导出只读本地备份，**不发网络请求**。“HTML 含图片”使用备份中已保存的图片（缩略图档位导出的就是缩略图）；没有图片的备份照常导出文字，并在文末列出缺图的动态。

### 7.2 输出

用 `ActivityResultContracts.CreateDocument` 让用户选择文件，文件名默认 `c001apk_收藏_yyyyMMdd.txt|html|zip`。流式写出，显示进度，可取消，取消或失败时删除已创建的文档。

**TXT**：UTF-8，每条之间用分隔线：

```text
==============================
合成作者 · 2026-10-28 14:00
https://www.coolapk.com/feed/123456
来源：收藏夹「数码」

<正文纯文本，文章保留段落>

[图片 3 张]
------------------------------
回复（12）
  某用户：……
==============================
```

**HTML**：单个文件，内联 CSS（跟随系统深色模式），顶部目录（标题 + 锚点），每条动态一个 `<article>`。

- 正文 HTML 必须先用 Jsoup `Safelist.basic()` 清洗，并额外允许 `img`（只允许 `src` 指向 ZIP 内相对路径）。作者名、标题等纯文本一律转义。**不得**原样拼接服务器返回的 HTML。
- 外链统一改为 `https://www.coolapk.com/...` 的绝对地址，加 `rel="noopener noreferrer"`。

**HTML 含图片**：ZIP，包含 `index.html` 和 `images/<feedId>_<n>.<ext>`。图片从各备份 ZIP 中读取，校验 SHA-256 后写入，STORED 模式。**不要**把图片用 base64 嵌入单个 HTML。

### 7.3 上限

- 单次导出最多 20000 条动态；
- HTML 单文件超过 64 MiB 时自动改为分卷：`index.html`、`index_2.html`……，目录页链接各分卷；
- ZIP 总大小不设硬上限，但开始前估算大小并提示，超过存储剩余空间时拒绝开始。

## 8. 设置项

“设置”新增分组“备份与收藏”（放在现有“我的备份”入口附近）：

| 设置 | 键 | 默认 |
|---|---|---|
| 收藏时自动备份 | `archiveAutoBackup`（关闭/仅文字/缩略图/原图） | 关闭 |
| 批量备份包含回复 | `archiveIncludeReplies` | 开 |
| 仅在 WiFi 下下载图片 | `archiveImagesWifiOnly` | 关 |

键加到 `SettingKeys`，读取写在 `SettingsRepository.read()`，风格与现有设置一致。

## 9. 已确认的接口信息

暂无。维护者小范围验证（第 2 节）后在此补充；补充前保持保守默认值。

## 10. 实施顺序与验收

每个阶段单独提交，阶段内测试全部通过后再进入下一阶段。

1. **阶段 1：备份格式与选项**（3.1、3.2）。验收：旧格式 JSON 和旧 ZIP 照常打开；三种档位各自生成正确的 JSON/ZIP；`includesReplies=false` 的离线页显示“未包含回复”；只有 JSON 时可打开、可删除。
2. **阶段 2：索引**（3.3、3.4）。验收：从 DataStore 旧索引迁移；增量扫描只解析变化的文件（测试中计数 `read` 调用次数）；删除索引文件后可重建；`body` 提取覆盖普通动态、文章、带话题和带回复的样例；NFKC 和大小写归一化生效。
3. **阶段 3：搜索与失效回退**（第 6 节）。验收：多词 AND 查询、`%`/`_` 转义、范围筛选正确；在线业务错误时回退离线并显示横幅；验证码不回退。
4. **阶段 4：同步与自动备份**（第 5 节）。验收见下方测试清单。
5. **阶段 5：导出**（第 7 节）。验收：TXT/HTML/ZIP 三种输出与样例快照一致；HTML 中注入的 `<script>`、`onerror=`、`javascript:` 链接被清除；ZIP 内图片与备份 SHA-256 一致；导出过程无网络请求（测试 transport 计数为 0）。

### 测试清单（离线，沿用 `FixtureTransport`、`Harness`、Robolectric 现有写法）

- `BackupTextTest`：可搜索文本、标题、导出纯文本。
- `ArchiveIndexTest`：建表、迁移、增量扫描、查询转义、多来源。
- `ArchiveSyncTest`：
  - 新增、指纹变化、档位升级时入队，未变化时跳过；
  - 收藏夹中已移除的条目标记 `removed_at`，备份保留；
  - 收藏夹列表读取中途失败时不标记移除；
  - 验证码暂停整个队列，确认后继续，不重复备份已完成项；
  - 网络错误重试 3 次后标记失败并继续下一条；
  - 进程重建后从 `queue` 表续传；
  - 两次详情请求之间有 3–5 s 间隔、每 50 条暂停、单次 500 条上限（注入可控时钟，不在测试中真实等待）；
  - 同步中触发单条手动备份时提示“请先完成当前备份”。
- `ArchiveExportTest`：三种格式快照、HTML 清洗、分卷、无网络。
- UI 测试：同步页进度和暂停；搜索结果高亮与点击回退；收藏后自动入队。

### 文档

完成后更新 `docs/FEED_BACKUP.md`（新增档位、同步、搜索、导出说明，以及格式版本 2 的字段）、`docs/DEVELOPMENT.md`（索引可重建）和 `CHANGELOG.md` 的“未发布”部分。
