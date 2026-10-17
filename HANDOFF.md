# WEI 终端 (shq) - 开发交接文档

## 📌 当前进度 (2026-10-17 14:24)

1. **编译错误修复完成**：
   - 修复了 `TokenStore.java` 中 `deleteEntry` 方法 `catch` 块引用未定义变量 `f` 和 `line` 导致的 build failed。
   - Run 41 已构建成功。

2. **写入失败修复 (多目录回退)**：
   - `TokenStore` 新增了多目录写入回退逻辑：`/sdcard/WEI` -> `App私有目录/WEI`。
   - 避免了因权限或路径问题导致的“静默失败”，并在 `lastError` 中记录真实错误信息。

3. **Token 弹窗重构 (标签选择)**：
   - 取消了原来的“自动识别游戏”，改为在 `TokenDialog` 顶部增加横向滚动的**游戏标签栏**。
   - 点击对应游戏标签，只加载并显示该游戏对应的 `游戏名.txt` 里的账号。
   - 列表项增加了点击登录、复制和删除的完整交互。

4. **玻璃效果重构**：
   - 设置页的控制背景透明度滑杆（`KEY_GLASS`），现在改为控制所有带有 `tag="glass"` 的控件（卡片、按钮、日志区等）的透明度，而非整层背景。
   - 为 `activity_main.xml`、`activity_settings.xml` 等布局中相关的 `glass_card`、`glass_btn` 增加了 `android:tag="glass"`。
   - 通过 `Ui.applyGlass()` 递归修改控件的 `GradientDrawable` 颜色透明度。

---

## ⚠️ 核心开发规范（强制遵守）

**严禁在终端/对话中直接使用长命令拼接并输出代码！**

- **问题原因**：使用 `printf`、`echo` 拼接大段 Java 代码或 XML 作为 `exec_shell` 的参数时，工具的回显会完整输出所有代码，导致：1) 聊天界面被垃圾信息淹没；2) App 严重卡顿甚至无响应；3) 无法看清真正的执行结果。
- **正确做法**：
  - 修改代码请使用 `python` 脚本（可借助 `python3 -c` 或本地写小文件执行）进行精确的字符串 `replace`。
  - 执行完后，**只 print 修改状态摘要**（例如：`print("TokenStore patched OK")` 或 `print("Failed: pattern not found")`）。
  - 或者使用 `sed` 命令单行替换。
  - 文件写入优先使用 `file_write` 工具直接覆盖，避免在 shell 命令中二次拼接。

---

## 🚀 下一步待办

1. **验证 Run 42**：确认推送 `67de0fd` 后，GitHub Actions 的 Run 42 是否构建成功。
2. **实际设备测试**：
   - 测试“抓号”或“上号”后，`WEI/游戏名.txt` 是否能正常生成并写入。
   - 测试 Token 弹窗的游戏标签切换是否流畅。
   - 测试设置界面的透明度滑杆，观察卡片和按钮的透明度是否跟随变化。

## 🛠️ 常用命令备忘

```bash
# 查看最新构建状态
curl -s -H "Authorization: Bearer <TOKEN>" "https://api.github.com/repos/wsw0803/shq/actions/runs?per_page=1" | jq ".workflow_runs[0] | {run_number, status, conclusion}"

# 拉取最新代码
git pull origin main
```
