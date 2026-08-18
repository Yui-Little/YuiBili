<p align="center">
  <img src="app/src/main/res/drawable-xxxhdpi/ic_logo.png" width="104" height="104" alt="YuiBili Logo"/>
</p>

<h1 align="center">🎀 YuiBili</h1>

<p align="center">
  <b>一个干净、克制、以公开内容为主的第三方哔哩哔哩客户端</b><br/>
  <sub>原生 Android View · Kotlin · 无 Compose / 无 AppCompat</sub>
</p>

<p align="center">
  <img alt="版本" src="https://img.shields.io/badge/%E7%89%88%E6%9C%AC-v0.1.33-ff8fb1?style=flat-square"/>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square"/>
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-23-6cc24a?style=flat-square"/>
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-34-4c9aff?style=flat-square"/>
  <img alt="构建" src="https://img.shields.io/badge/%E6%9E%84%E5%BB%BA-Gradle%208.7%20%2F%20JDK%2017-lightgrey?style=flat-square"/>
</p>

---

> ⚠️ 本项目与哔哩哔哩（bilibili）**无任何关联、合作、授权或背书**，仅供学习与技术交流，请勿用于商业用途。

## ✨ 功能特性

**🏠 首页**

- 推荐 / 热门双流，卡片复用双列网格，接近底部自动分页
- 顶部下拉刷新（网络完成才收起）、BVID 去重、滑动窗口控制内存（480 → 360 裁剪）
- CDN 缩略图封面 + 内存 / 磁盘 LRU，缓存命中不闪烁

**🔍 搜索**

- 输入防抖出建议、搜索历史、排序 / 时长 / 分区筛选
- 卡片共享元素转场

**▶️ 播放**

- Web / WBI DASH 链路（`fnval=4048`），Media3 播放本地 MPD，保留备用 CDN 与初始化 / 索引区间
- 播放前探测主备节点，减少 CDN 403 导致的失败；画质严格服从账号与大会员权限
- 多 P 列表、简介与数据、评论（热度 / 时间排序）

**👤 账号**

- 官方登录（二维码 / 网页），Cookie 仅存应用私有存储
- 点赞、收藏、关注、评论点赞，登录后同步到服务器

**⬇️ 下载**

- DASH 音视频分离下载、断点续传、任务队列 + 前台服务
- 合集 / 多 P 批量下载、本地播放、相册导出（MP4 合并）

**📦 其他**

- 历史记录 / 收藏夹搜索、深浅主题 + 圆形揭示转场、系统减弱动画支持
- 内置更新检查（读取 GitHub `update.json`），有新版本才提示

## 🎨 设计语言

- 简约、现代、白色；暖白 + 柔和粉的品牌配色，原创粉色 Y 形丝带 Logo
- 无文字图标化底部 Dock、克制的短动画（可打断、支持系统减弱动画）
- 不采用实时截图模糊，避免持续的 GPU / 内存开销

## 🛠️ 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| UI | 纯原生 Android View（无 Compose / 无 AppCompat / 无 Material） |
| 播放 | AndroidX Media3 (ExoPlayer) 1.4.1 + DASH |
| 网络 | OkHttp 4.12 · HttpURLConnection |
| 二维码 | ZXing core 3.5.3 |
| 构建 | Gradle 8.7 · AGP 8.6.0 · JDK 17 · Android SDK 34 |

## 📲 安装与更新

- 在 [Releases](https://github.com/Yui-Little/YuiBili/releases) 下载最新 APK 手动侧载安装
- 应用内「检查更新」读取仓库根目录 `update.json`，仅在远端版本更新时提示

## 🧱 从源码构建

```bash
# 需要 JDK 17 + Gradle 8.7 + Android SDK 34
gradle :app:assembleDebug     # 调试包
gradle :app:assembleRelease   # 发布包 → app/build/outputs/apk/release/app-release.apk
```

> 说明：项目未内置 `gradlew` wrapper，请使用固定版本 **Gradle 8.7** 构建；签名使用仓库内 `debug.keystore`（调试签名，仅用于本机测试与覆盖安装）。

## ⚖️ 免责声明与合规说明

- 本项目为个人学习项目，与哔哩哔哩官方无任何关联、合作、授权或背书
- 不绕过会员、版权、地区或账号权限；播放与下载内容严格受账号权限约束
- 登录 Cookie 仅保存在设备本地，仅用于 B 站官方接口请求，不上传任何第三方
- 使用本项目的任何后果由使用者自行承担

## 🙏 参考与致谢

- 下载功能设计参考 [BiliTools](https://github.com/happycola233/BiliTools)
- 感谢开源社区在 B 站接口研究上的公开资料

---

<p align="center"><sub>Made with 💗 by Yui-Little</sub></p>
