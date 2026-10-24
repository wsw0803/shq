# 更新下载走本地代理（方案 A）

## 作用范围

**只有 `Updater` 的更新下载走代理**，抓号 / 上号 / 挂机等业务流量完全不经过它。
不申请 VPN 权限，不接管全局流量。

## 实现方式

`XrayCore` 启动一个只开 SOCKS 入站的 xray-core：

```
127.0.0.1:10808  (socks5, 无认证, 不转发 UDP)
        ↓
VLESS + Reality + TCP + xtls-rprx-vision
        ↓
6abba132-...a01.interface.routely.moe:11001
```

`Updater.open()` 在 `useProxy` 为真时用 `openConnection(Proxy(SOCKS, ...))`，
代理不可用就自动直连，不影响原有可用性。

进度提示会显示当前走的是「代理」还是「直连」。

## ⚠️ 你需要上传两个文件

代码已写好，但**二进制我编译不出来**，需要你放到仓库的 `core/` 目录：

| 文件 | 说明 |
| --- | --- |
| `core/xray` | Linux arm64 的 xray-core 可执行文件（不是 Windows/PC 版）| 
| `core/xray.sha256` | 上面那个文件的 SHA-256，小写十六进制，仅一行 |

生成校验值：

```bash
sha256sum xray | awk '{print $1}' > xray.sha256
```

xray-core 官方 Release：
https://github.com/XTLS/Xray-core/releases

注意选 **`Xray-linux-arm64-v8a.zip`**，解压出的 `xray` 才是 Android 能跑的那个。

## 二进制落地位置

Android 10 起应用私有目录是 `noexec`，直接执行会被拒。
本应用有 root，所以走 `su` 安装到：

```
/data/local/tmp/shq/xray
/data/local/tmp/shq/config.json
```

该目录可执行，且在 APK 升级后依然保留。

## 首次使用流程

1. 点更新
2. 检测到核心未安装 → 从仓库下载 `core/xray` 与 `core/xray.sha256`
3. 校验 SHA-256，不一致直接放弃（防止中间人替换）
4. `su` 复制到 `/data/local/tmp/shq/` 并 `chmod 755`
5. 写 `config.json`
6. 启动核心，等 1.2 秒探测进程是否存活
7. 启动成功 → 下载走代理；启动失败 → 自动直连

第二次起直接用已安装的核心，不再重复下载。

## 安全说明

这里有个需要你知情的取舍：

之前移除了 `gh-proxy.com` 等第三方代理并加了 APK 签名校验，
理由是第三方能替换安装包。现在更新流量会经过你自己的节点，
风险性质变了但没有消失——节点侧的中间人依然可能替换 APK。

目前的防护：
- 核心二进制下载有 SHA-256 校验
- APK 下载后仍会做签名比对，**但只提示、不阻断**（因为你有 root 且安装器已去校验）

如果要把这个口子补上，建议给 Release 额外附一个 `apk.sha256`，
让 App 下载后核对哈希，不一致就拒绝安装。需要的话我可以加。

## 换节点

改 `XrayCore` 顶部的 `NODE_*` 常量即可，无需改动下载逻辑。
