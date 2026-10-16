# 第三方声明

本项目沿用 [GNU AGPL v3](../LICENSE)，保留上游
[bggRGjQaUbCoE/c001apk-flutter](https://github.com/bggRGjQaUbCoE/c001apk-flutter) 的署名。
2.0 起为 Kotlin 原生重写，功能与交互沿袭上游及本项目 1.x（Flutter 版）。

## 协议实现

`core/` 中的 Token V3 实现与协议查找表（`protocol/TokenUtils.kt`、`protocol/TokenV3Data.kt`），
游客动态详情的公开分享页请求、网易易盾验证码配置解析与 `NEC` 凭证格式，以及点赞、回复的表单参数
（`net/` 下各文件），参考自 [daimiaopeng/coolapk-desktop](https://github.com/daimiaopeng/coolapk-desktop)
提交 `3ef2ad4da6d171e118d2b29b0f18d7ffa6da9086` 的 `src-tauri/src/coolapk/auth.rs`、`auth_blob.b64`、
`client.rs` 与 `src/utils/neteaseCaptcha.ts`（采用日期 2026-10-14）。原项目以 MIT 授权，
完整许可证与版权声明见 [coolapk-desktop MIT](licenses/coolapk-desktop-MIT.txt)。

MIT 许可适用于原项目的原创代码。查找表用于非公开协议兼容，其来源与权利不由原项目的 MIT 许可担保。
验证码界面在运行时加载网易易盾官方 Web SDK，源码中不打包该 SDK。
SDK 的接入回调另行核对了[网易易盾官方示例](https://github.com/yidun/captcha-php-demo/blob/master/index.php)。

`core/src/test/resources/token_v3.json` 是用合成设备与固定时间生成的测试签名，
不含真实设备、账号凭据或用户 Token。

## 界面参考

设置分类与文字层级、账号切换入口，以及 c001apk 自身的更新弹窗（更新说明、稍后提醒、跳过版本），
参考了 [bggRGjQaUbCoE/PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) 的设置页、账号管理与
`lib/utils/update.dart`（提交 `868580c3edbe13f874583a3139b1f4d116d4cc0f`、`d529e0a2d90a2a3a04ef9847e6c14187852ea6ee`）。
本项目独立实现，未引入其代码或图形素材。该项目采用 GPL-3.0，许可证见 [PiliPlus GPL-3.0](licenses/PiliPlus-GPL-3.0.txt)。

## 依赖与模型

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [jBCrypt](https://www.mindrot.org/projects/jBCrypt/) | Token V3 中的 bcrypt | ISC |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) 1.26.0 | 语义屏蔽推理。Java API 随应用分发，原生库由用户按需下载 | MIT |
| [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)（[Xenova ONNX int8 版](https://huggingface.co/Xenova/bge-small-zh-v1.5)） | 语义屏蔽模型，由用户按需下载，不随应用分发 | MIT |
| AndroidX、Jetpack Compose、Material 3、Kotlin、kotlinx | 应用框架 | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp)、[Coil](https://github.com/coil-kt/coil)、[Telephoto](https://github.com/saket/telephoto)、[MaterialKolor](https://github.com/jordond/MaterialKolor)、[jsoup](https://jsoup.org/) | 网络、图片、缩放、配色、HTML 解析 | Apache-2.0 / MIT |

酷安名称、商标、表情图片（含“牛牛”表情）及服务内容归相应权利人所有。
