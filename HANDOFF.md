# HANDOFF — shq 极光上号器

> 交接文档，给下一个接手的对话/开发者。最后更新：2026-10-17

## 一、项目概况

- **仓库**：https://github.com/wsw0803/shq （私有）
- **本地工作区**：/data/data/com.termux/files/home/agent_workspace/shq
- **原始源码位置**：/storage/emulated/0/Download/ShangHaoQi/ （只读参考，不要在这里改）
- **包名**：com.tencent.tim （伪装成 TIM，注意这不是真腾讯应用）
- **技术栈**：Java + Gradle，AGP 8.13.0 / Gradle 9.0.0 / JDK 17，compileSdk 36 / minSdk 21 / targetSdk 34

## 二、已完成的工作

1. 删除引流文件：源码里被撒了 374 个 使用前请看我.txt / 执行我加入频道.sh（含 Telegram 频道推广），已全部删除。
2. 安全加固：
   - NormalWebViewClient / HangUpWebViewClient 的 shouldOverrideUrlLoading 加域名白名单，只放行 https://xui.ptlogin2.qq.com/ 开头，其余拦截。
   - MainActivity.parseGameData() 对解析出的 URL 做前缀校验，不符合的记录直接丢弃。
3. 本地 appid + 双开关：
   - MainActivity 新增常量 PTLOGIN_BASE（登录 URL 模板）、LOCAL_GAME_DATA（22 款游戏硬编码，格式 名称+竖线+appid，分号分隔）。
   - 新增 buildLocalUrl(appid)、setupSourceToggles()、loadGameList()。
   - activity_main.xml 新增数据源区，含 cb_local_source（本地 appid）、cb_cloud_source（云端 appid）两个 CheckBox。
   - 默认：本地开、云端关。开关状态存 SharedPreferences（local_on / cloud_on）。
   - 两个都关则提示无可用数据源。
4. CI：.github/workflows/build.yml，push 到 main 或手动触发，构建 release APK 并上传 artifact。
5. README.md：项目说明。

## 三、关键文件

| 文件 | 作用 |
|---|---|
| app/src/main/java/com/tencent/tim/MainActivity.java | 主界面；游戏列表加载（本地/云端）、开关逻辑、Root 隐藏/恢复 QQ |
| app/src/main/java/com/tencent/open/agent/AgentActivity.java | 登录回调与 token 解析 |
| app/src/main/java/com/tencent/tim/NormalWebViewClient.java | 抓号模式 WebView，含白名单 |
| app/src/main/java/com/tencent/tim/HangUpWebViewClient.java | 挂机模式 WebView，含白名单 |
| .github/workflows/build.yml | GitHub Actions release 构建 |
| gradle/libs.versions.toml | 依赖版本 |

## 四、构建方式

方式 A（推荐）GitHub Actions：push 到 main 即自动构建，在 Actions 页面下载 release-apk artifact。

方式 B 本地：./gradlew assembleRelease（需要完整 Android SDK；Termux aarch64 上 aapt2 可能跑不了，建议用 CI）。

注意：workflow 里不要用 android-actions/setup-android（它内部 sdkmanager --install tools 会失败，因为新版 SDK 已移除该包）。当前 workflow 是手动 sdkmanager 装 platforms;android-36 + build-tools;35.0.0。

## 五、待办 / 可改进

- [ ] LOCAL_GAME_DATA 里的 appid 是从云端接口抓的，可能需要随游戏更新维护。
- [ ] release 签名目前用 CI 临时生成的 keystore（密码 123456），正式发布需换成固定签名。
- [ ] AgentActivity.saveAccountToFile() 会把 token 明文写入 /sdcard/-极光/数据.txt，这是本项目固有行为，未改动。
- [ ] 云端列表接口 https://iappht.sslqq.cn/user/getFile.php?fid=7487 仍保留为可选项（默认关闭）。

## 六、风险提示（重要）

- 本项目是账号登录/抓号工具：明文落盘 QQ token、用 root 执行 pm hide/unhide。不要在主设备或真实账号上运行。
- GitHub token 曾在对话中明文出现，应已吊销；如果 remote URL 里还带 token，记得换成不含 token 的地址。
- 仓库是私有的，但仍建议清楚自己在做什么。

## 七、提交历史（近期）

```
070e5e6 feat: local appid list with local/cloud source toggles
fe7e3de security: whitelist ptlogin domain and validate parsed game url
c84d248 ci: fix SDK setup step for release build
4b5465c docs: add README
1f88ca6 init: shq android project source
```

## 八、给下一个对话的建议

1. 改代码前先 git pull，在 agent_workspace/shq 里操作。
2. 用 shell 里的 su -c 读外部目录（/storage/emulated/0/... 普通模式读不了）。
3. 写含花括号的内容（Java/JSON/YAML）时，避免直接把花括号放进工具控制块表达式，用 file_write 或 printf 落盘。
4. 构建结果查 https://api.github.com/repos/wsw0803/shq/actions/runs。
