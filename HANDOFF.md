
## 三、关键文件

| 文件 | 作用 |
|---|---|
| app/src/main/java/com/tencent/tim/MainActivity.java | 主界面；列表加载、开关、同步、Root 工具 |
| app/src/main/java/com/tencent/open/agent/AgentActivity.java | 登录回调与 token 解析 |
| app/src/main/java/com/tencent/tim/NormalWebViewClient.java | 抓号模式 WebView（白名单） |
| app/src/main/java/com/tencent/tim/HangUpWebViewClient.java | 挂机模式 WebView（白名单） |
| app/src/main/res/layout/activity_main.xml | 主界面布局（已重构） |
| app/src/main/res/layout/activity_agent.xml | 上号界面布局（已重构） |
| app/src/main/res/values/colors.xml | 配色（已重构） |
| app/src/main/res/values/styles.xml | 样式（已重构） |
| app/build.gradle | 含 signingConfigs.release |
| wei-release.jks | 固定签名（别名 wei，密码 wei123456） |
| .github/workflows/build.yml | CI release 构建 |

## 四、构建方式

方式 A（推荐）GitHub Actions：push 到 main 即自动构建，在 Actions 页面下载 release-apk artifact。
方式 B 本地：./gradlew assembleRelease（需完整 Android SDK；Termux aarch64 上 aapt2 跑不了，建议用 CI）。

注意：workflow 里不要用 android-actions/setup-android（内部 sdkmanager --install tools 会失败）。当前是手动 sdkmanager 装 platforms;android-36 + build-tools;35.0.0。

## 五、推送方式（重要）

remote 已改为不含 token 的 https://github.com/wsw0803/shq.git。
推送时用一次性 URL（token 不写入 config）：
git push https://wsw0803:TOKEN@github.com/wsw0803/shq.git main

GitHub token（ghp_fRa...Xht）曾在对话中明文出现多次，务必去 Settings > Developer settings > Personal access tokens 吊销并换新。

## 六、待办 / 可改进

- [ ] LOCAL_GAME_DATA 里的 appid 需要随游戏更新维护。
- [ ] wei-release.jks 密码在 build.gradle 明文，正式发布建议改环境变量。
- [ ] AgentActivity.saveAccountToFile() 把 token 明文写入 /storage/emulated/0/维/保存数据/数据.txt（本项目固有行为）。
- [ ] 云端接口 https://iappht.sslqq.cn/user/getFile.php?fid=7487 仍为可选项（默认关闭）。

## 七、待实现的用户需求

- 用户想要「输入数据号 -> 查看 等级/段位/在线状态」。
- 结论：用现有 QQ 互联 token 只能查 QQ 基础资料（昵称/头像/性别，graph.qq.com/user/get_user_info，需匹配 appid）。
- 段位/在线状态 QQ 互联没有接口，查不到。若用户另有查询 API 才能接。
- 方案 A/B/C 已向用户说明，等待用户选择。

## 八、风险提示

- 本项目是账号登录/抓号工具：明文落盘 QQ token、root 执行 pm hide/unhide。不要在主设备或真实账号运行。
- 仅用于学习研究。
