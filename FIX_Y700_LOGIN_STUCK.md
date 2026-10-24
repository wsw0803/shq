# 修复：Y700 四代移植包（一加/氧OS·ColorOS ROM）登录后卡住

## 现象

在 Y700 四代上，用一加移植包（氧OS / ColorOS 系）跑本 App 的**抓号 / 挂机**模式时：
扫码或输密码完成 QQ 登录后，界面停在登录页 / 白屏，不提示成功、也不返回主页，只能手动退出。

## 根因

源码原来的登录判断只在 `WebViewClient` 的两个回调里做：

```java
if (low.startsWith("auth:") || low.contains("access_token=")) { activity.performLogin(url); }
```

这条逻辑默认「QQ 登录成功后一定会回调 `shouldOverrideUrlLoading`」。但移植包自带的是较新的 Chromium WebView，与源码假设有三处差异，**任意一处都会导致卡住**：

### 1）`auth://` 自定义 scheme 被静默丢弃（主因）

QQ 登录完成后是 302 跳到：

```
auth://tauth.qq.com/?#access_token=...&expires_in=...&openid=...&pay_token=...
```

新版 WebView 对非 `http(s)` 的导航会直接丢弃，**`shouldOverrideUrlLoading` 根本不会被调用**，`onPageStarted` 也不会收到。
Java 侧完全无感知 → 界面永远停在登录页。

`auth://` 是应用私有 scheme，普通 ROM 的旧 WebView 会宽容地交给应用处理，所以同一份代码在别的机器上是好的。

### 2）`window.open` 弹窗被吞

登录页用 `window.open` 弹小窗做二次验证。原来的 `setupCaptureMode / setupHangUpMode` 没有：

- `WebSettings.setSupportMultipleWindows(true)`
- `WebChromeClient.onCreateWindow()` 实现

结果点「登录」没有任何反应 —— 同样表现为卡住。

### 3）第三方 Cookie 被拦

移植 ROM 默认拦截第三方 Cookie，`CookieManager` 未显式开启，QQ 登录态写不进去，
登录成功后又被弹回登录页（**看起来也像卡住**）。

### 4）附带问题

- 挂机模式 `refreshPage()` 每次 `postDelayed` 都**不取消**旧任务，多次登录后叠加多个定时刷新，
  页面反复重载 → 实际表现为「一直在转圈」。
- WebView 渲染进程崩溃（移植 ROM 上更常见）没有 `onRenderProcessGone` 兜底 → 直接白屏卡死。

## 修复内容

### 新增

| 文件 | 作用 |
| --- | --- |
| `BaseLoginWebViewClient.java` | 登录模块通用基类，四层 token 捕获 + 90 秒看门狗 + 渲染进程崩溃兜底 |
| `LoginWebBridge.java` | 注入页面脚本：接管 `window.open`、挂 `pushState/replaceState`、监听 `hashchange`、500ms 轮询 `location.href` |
| `LoginChromeClient.java` | 接管 `onCreateWindow`，弹窗不再被吞 |

### 改写

- `NormalWebViewClient`（抓号）→ 继承基类
- `HangUpWebViewClient`（挂机）→ 继承基类，并修复定时刷新任务叠加

### 四层 token 捕获（任一层命中即视为登录成功）

1. `shouldOverrideUrlLoading(WebResourceRequest / String)` —— 常规跳转拦截
2. `onPageStarted` / `onPageFinished` —— 页面 URL 已带 token
3. `doUpdateVisitedHistory` —— 部分 ROM 只回调历史变化
4. JS 桥 + 500ms 轮询 —— **从页面侧发现跳转**，专治 `auth://` 被丢弃

第 4 层是本次的关键：即使 WebView 把导航吞掉，页面里的 `location.href` 已变成
`auth://tauth.qq.com/?#access_token=...`，脚本轮询到就直接回传给 Java。

### 其他加固

- `prepare()` 统一补齐 WebView 设置：`setSupportMultipleWindows(true)`、`setJavaScriptCanOpenWindowsAutomatically(true)`、`setDomStorageEnabled`、`setDatabaseEnabled`、`setMixedContentMode`
- `CookieManager.setAcceptThirdPartyCookies(view, true)` + 页面结束时 `flush()`
- token 投递做了 8 秒去重，避免多层兜底重复触发 `performLogin`
- 90 秒看门狗：仍未拿到 token 就自动重载一次并提示，不再无限等待
- `onRenderProcessGone` → 提示并 `recreate()` 自动恢复，不再白屏
- 加载失败、拦截到未知回调时给出 Toast，避免「无任何反馈」

## 未改动

`AgentActivity.java` 未修改（17KB，避免整文件重写引入编译风险）。
其 `performLogin()` 链路本身可用，本次修复集中在「token 到不了 `performLogin`」这一环。

## 验证方式

1. 推送后等 GitHub Actions 构建完成，下载 `release-apk`。
2. Y700 四代 + 一加移植包上测：抓号模式扫码 → 应 Toast「登录成功！」并在 2 秒内返回。
3. 若仍卡住，看 Toast / logcat：
   - 出现「拦截到登录回调: auth://…」但无 access_token → 说明回调参数丢了，需要另查 `client_id`。
   - 出现「登录页 90 秒内没有返回结果」 → 说明连页面都停在登录页，检查网络 / Cookie。
   - 出现「WebView 渲染进程崩溃」 → 已自动恢复，属已知移植包 WebView 不稳定。
