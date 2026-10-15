<div align="center">

<img src="docs/media/banner.png" alt="c001apk — 让酷安，轻一点。" width="100%">

# c001apk

**轻量、简体中文的非官方酷安 Android 客户端**

[![Android](https://github.com/ZaiLinn/c001apk/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/ZaiLinn/c001apk/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/ZaiLinn/c001apk?style=flat-square&color=486B78&label=Release)](https://github.com/ZaiLinn/c001apk/releases/latest)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-607D8B?style=flat-square)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack_Compose-6E8B74?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0+-6E8B74?style=flat-square&logo=android&logoColor=white)

[下载](https://github.com/ZaiLinn/c001apk/releases/latest) · [反馈问题](https://github.com/ZaiLinn/c001apk/issues) · [开发指南](docs/DEVELOPMENT.md)

</div>

## 关于

c001apk 是用 Kotlin 与 Jetpack Compose 原生编写的酷安第三方客户端，界面采用 Material 3 Expressive，
安装包约 4 MB。项目源自 [c001apk-flutter](https://github.com/bggRGjQaUbCoE/c001apk-flutter)，
2.0 起改为原生重写，只提供 Android 版本。

## 功能

| 浏览 | 账号 | 个性化 |
| --- | --- | --- |
| 首页动态、热门、话题、数码、酷图 | 账号密码与网页登录、人机验证 | 动态取色或固定配色、深色模式 |
| 动态、文章、评论与楼中楼 | 多账号保存与一键切换、游客模式 | 字体大小、表情显示 |
| 用户空间、话题与产品页、应用详情 | 点赞、回复、发布动态与图片上传 | 浏览历史、本地收藏 |
| 动态、应用、用户与话题搜索 | 私信、通知、关注与粉丝列表 | 用户与话题黑名单 |
| 大图查看、缩放与保存 | 本地关注与悄悄关注（仅存本机） | 已安装应用的酷安更新检查 |

### 敏感话题折叠

在 **设置 → 浏览与隐私 → 折叠敏感话题** 打开后，命中的动态和回复会折叠成一行，
例如“已折叠 · 晒对象 · 晒女友”，点击即可展开。所有判断都在本机完成。

- **关键词**：内置晒对象、相亲恋爱、性压抑/擦边、两性对立四类词表，可逐类开关；
  也可以添加自己的屏蔽词，写成 `#话题#` 时按话题匹配，例如 `#相亲#`、`#酷安夜话#`。
- **语义屏蔽（可选）**：在折叠规则页按需下载约 34 MB 的中文语义模型
  （bge-small-zh 与 ONNX Runtime）。它按意思判断，能抓到换了说法、不含关键词的帖子。
  模型不随安装包分发，不用时可以删除；只分析文字，不看图片，内容不会上传。
  页面提供阈值调节和“试一试”，也可以用一句话描述想折叠的内容。

## 下载安装

从 [Releases](https://github.com/ZaiLinn/c001apk/releases/latest) 下载
`c001apk-<版本>-android-universal.apk` 安装。支持 Android 7.0 及以上，所有 CPU 架构共用这一个安装包。
应用会自动检查 GitHub 上的新版本，可在 **设置 → 存储与更新** 关闭。

从 1.x（Flutter 版）升级时可直接覆盖安装。2.0 不读取旧版数据，需要重新登录，设置、历史和黑名单也需要重新配置。

> [!NOTE]
> 部分内容仍受登录、验证码和服务端风控限制。完成人机验证不保证服务端解除其他限制。

## 参与开发

欢迎提交 [Issue](https://github.com/ZaiLinn/c001apk/issues) 或 Pull Request。反馈问题请附上应用版本、
系统版本、复现步骤与脱敏后的错误信息，不要附带密码、Cookie、Token 或私信内容。

构建、测试与发版流程见 [开发指南](docs/DEVELOPMENT.md)。

## 致谢与许可

感谢 [bggRGjQaUbCoE/c001apk-flutter](https://github.com/bggRGjQaUbCoE/c001apk-flutter) 的开源，
以及 [daimiaopeng/coolapk-desktop](https://github.com/daimiaopeng/coolapk-desktop) 的协议调研参考。

本项目沿用 [GNU AGPL v3](LICENSE)，第三方来源与许可见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)。
本项目与酷安官方无隶属关系，酷安名称、商标及服务内容归相应权利人所有。
