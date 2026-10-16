# shq 极光上号器

Android 应用源码（Java + Gradle），包含抓号、上号、挂机三种模式。

## 基本信息
- 包名: com.tencent.tim
- compileSdk 36 / minSdk 21 / targetSdk 34
- AGP 8.13.0 / Gradle 9.0.0
- JDK 17

## 目录结构
- app/src/main/java/com/tencent/tim/ 主界面、WebView 客户端、游戏列表解析
- app/src/main/java/com/tencent/open/agent/ 登录回调与令牌解析
- app/src/main/res/ 布局与资源
- gradle/ 版本目录与 wrapper

## 构建
GitHub Actions 工作流: .github/workflows/build.yml
push 到 main 或手动触发后，在 Actions 页面下载 release-apk 产物。

本地构建: ./gradlew assembleRelease

## 风险提示
本项目为账号登录工具源码，会将登录令牌明文写入 /sdcard/-极光/数据.txt，
并使用 root 执行 pm hide/unhide 隐藏 QQ。
请勿在主力设备或真实账号上运行，仅用于学习研究。
