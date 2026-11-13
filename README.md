# shq 上号器

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
push 到 main 或手动触发后，在 Releases 页面下载 APK。仅修改 Markdown 文档或 release/ 发布文件时不自动构建，仍可手动触发。

本地构建: ./gradlew assembleRelease

CI 使用 `testReleaseUnitTest assembleRelease`，单元测试和打包共用 release 编译结果。`gradle/actions/setup-gradle` 保存 Gradle 发行版、依赖与任务输出缓存，首次运行生成缓存，后续运行复用；变更源码、依赖或版本号时由 Gradle 自动判定哪些任务需要重做。克隆只拉取当前提交，不再下载包含旧 APK 的完整历史。

比较构建速度时，同时查看 checkout、Build release、缓存恢复和保存的耗时；首次缓存写入时间不代表后续构建速度。

## 风险提示
本项目为账号登录工具源码，会将登录令牌明文写入
并使用 root 执行 pm hide/unhide 隐藏 QQ。
请勿在主力设备或真实账号上运行，仅用于学习研究。
