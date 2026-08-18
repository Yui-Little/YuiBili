# YuiBili 远程更新配置

把本目录的 `update.json` 上传到你的 **公开** GitHub 仓库根目录（或任意路径，但要和 App 里 URL 一致）。

## 1. 上传

仓库示例：`https://github.com/Yui-Little/YuiBili`

上传后确认浏览器能打开其一：

- `https://cdn.jsdelivr.net/gh/Yui-Little/YuiBili@main/update.json`
- `https://raw.githubusercontent.com/Yui-Little/YuiBili/main/update.json`

分支若是 `master`，把 URL 里的 `main` 改成 `master`。

## 2. 告诉开发者 / 改 App

把真实 URL 填进：

`app/src/main/kotlin/com/yuilittle/bili/UpdateChecker.kt` 的 `CONFIG_URLS`

把 `Yui-Little/YuiBili` 换成你的用户名和仓库名。

## 3. 发新版时只改 JSON

```json
{
  "latestVersionCode": 14,
  "latestVersionName": "0.1.6",
  "minVersionCode": 1,
  "forceUpdate": false,
  "apkUrl": "https://github.com/Yui-Little/YuiBili/releases/download/v0.1.6/yuibili.apk",
  "updateLog": "1. 修复 xxx\n2. 优化 yyy",
  "checkIntervalHours": 12
}
```

| 字段 | 说明 |
|------|------|
| latestVersionCode | 必须大于手机里的 versionCode 才会提示更新 |
| latestVersionName | 展示用版本名 |
| minVersionCode | 低于此值可强制更新 |
| forceUpdate | true = 有新版本必须更新 |
| apkUrl | APK 直链（GitHub Release 资产链接即可） |
| updateLog | 更新说明，支持换行 `\n` |
| checkIntervalHours | 启动自动检查的最小间隔（小时） |

## 4. App 行为

- 启动后后台检查：有新版本才弹窗；点「稍后」同版本不再自动打扰
- 关于页「检查更新」：始终反馈（已是最新 / 有新版本 / 失败）
- 点「立即更新」：用系统浏览器打开 `apkUrl`
