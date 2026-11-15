# 悟空 0.3 内置激活与 shell 驱动验证

验证日期：2026 年 11 月 14 日。当前执行底座为悟空内置无线 ADB 启动的 app_process；此前无障碍服务驱动已移除，旧驱动的测试结果不作为本版通过依据。

## 构建与核心测试

```sh
./scripts/gradle-local.sh :agent-core:test :app:assembleDebug :app:assembleDebugAndroidTest :shell-runtime:assembleDebug :app:lintDebug :shell-runtime:lintDebug
```

App APK、设备测试 APK 和 shell dex 载体构建成功。14 个 JVM 核心测试通过，0 失败。App、shell、内置 ADB 和 SPAKE2 模块 Lint 均为 0 错误。提示包括测试依赖更新、ADB 自签名 TLS 信任管理、上游旧版本兼容分支和 shell 隐藏 API；模型 HTTPS 不使用 ADB 的信任管理器。

## Pixel 6 Android 17 真机

使用悟空通知栏助手输入系统配对码，由悟空在手机内完成 SPAKE2 / TLS 配对、上传内置载体并启动独立 app_process；电脑只操作 UI，不供应执行凭据或启动引擎。新版本已安装到真机，未再出现此前原生库的 16 KB 对齐警告。手机当前运行 4 KB 页面，不能将此次测试称为 16 KB 运行验证。

`ps` 与状态接口均确认实际 UID 2000；App 在普通应用 UID 中运行。`enabled_accessibility_services` 测试前后均为空，系统没有已绑定的无障碍服务。UiAutomation 内部自动化连接由 shell 建立。

设备集成测试结果：**OK，1 个连续流程测试通过**，覆盖：

1. 错误令牌请求被拒绝。
2. 普通 App UID 无法调用激活入口取得新令牌。
3. 应用前台 Service 和悬浮控制条工作，读屏选中实际应用窗口。
4. 向测试输入框写入 `悟空 中文输入`，重新读取确认中文文本。
5. 使用输入前的旧快照提交动作，shell 拒绝。
6. 注入真实触摸点击搜索按钮，确认页面显示对应结果。
7. 执行滚动并确认界面指纹变化。
8. 本地已取消动作不会发往设备。
9. 注入桌面和返回按键，启动系统设置，再启动悟空，验证整机跨应用操作。
10. 向已取消的 shell 会话提交动作，shell 本身拒绝执行。

保存配对身份的回归测试也在此真机通过：新测试进程读取加密身份，100 次快速 ADB 流打开、两次重新激活均成功；每次关闭 ADB 后引擎继续运行。

测试不调用真实模型，不修改系统设置中的业务选项。脚本临时允许悬浮窗，结束后恢复其原有 AppOps 状态；不启用或改写无障碍服务列表。测试时手机已解锁，没有使用或保存锁屏密码。

停止 shell 后已检查 App 的「Shell 未激活」提示与禁用的开始按钮；重新激活后已检查「Shell 已连接 · UID 2000」状态。App 清单中没有无障碍服务声明，不存在失联后回退到旧驱动的路径。

## Android 15 模拟器与原生库

在 Android 15 / API 35 模拟器上执行系统配对码 → 悟空通知输入 → 自动连接与激活的完整流程。另行验证保存的加密身份可在新 App 进程中重新加载，连续两次重新激活，100 次快速 `exec:id -u` 请求均返回 2000，关闭 ADB 后后台引擎仍存活。这覆盖了本机快速响应的流确认竞态。

SPAKE2 由完整源码和固定子模块编译，使用 NDK 28.2、CMake 3.22.1；Conscrypt 更新为 2.9.0。APK 中 4 个 ABI 的 8 个 `.so` 的 ELF LOAD 段均至少 16 KB 对齐，APK 通过 `zipalign -c -P 16 -v 4` 检查。

## 复现

```sh
./scripts/gradle-local.sh :app:assembleDebug :app:assembleDebugAndroidTest :shell-runtime:assembleDebug
./scripts/test-device.sh SERIAL --allow-physical
```

脚本默认安装两个测试产物、使用电脑重新激活 shell 并执行测试。若先在悟空里完成手机内激活，添加 `--use-running-shell`，测试将使用已运行的内置启动引擎，不调用电脑激活脚本。通过 `ADB` 和 `ADB_SERVER_PORT` 可指定电脑端工具与服务端口。模拟器的内置配对 UI 测试：`python3 scripts/test-wireless-emulator.py emulator-5580`，该脚本明确禁止真机。保存身份与快速连接回归使用设备测试中的 `WirelessActivationTest`，传入当前无线连接端口：

```sh
adb -s SERIAL shell am instrument -w -e class com.wukong.phone.WirelessActivationTest -e wireless_port PORT com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner
```

该测试要求已在悟空完成配对，会重新激活引擎，不应与任务并行运行。

## 尚需验证

- 复杂业务任务的理解、跨应用执行结果和更多模型服务的工具调用兼容性。
- 其他厂商系统和版本的隐藏 API 行为；Android 10 不支持内置无线配对。
- 长时间运行、手机重启后的人工重新激活和系统回收后的恢复体验。
- 真实任务中确认、拒绝和模型网络故障的完整交互。
- 后台 VirtualDisplay、OCR 和截图视觉能力尚未实现。

早期没有真实模型凭据时，驱动测试未被称为 AI 端到端测试；新增的真实模型验证范围见下文。

## DeepSeek 真实接口回归

用户配置 DeepSeek 后，于 2026 年 11 月 14 日补做真实接口验证。先复现任务 HTTP 400，服务端明确返回 `Thinking mode does not support this tool_choice`；此前 Base URL 直接请求导致的 404 已由请求路径拼接修复。改为 `tool_choice: auto`，客户端仍严格校验唯一 `phone_action`，不执行缺少工具调用或多个调用的响应。没有关闭用户模型的思考模式。

真机使用原有加密配置完成一次真实 `phone_action / finish` 请求，`ModelConnectionTest` 通过；测试仅解析工具响应，没有执行手机动作。设置页「测试连接」现在走相同工具请求，失败时显示服务端的错误说明，并对配置密钥做脱敏。地址解析 3 项 JVM 测试、构建和 App Lint 通过。

此结果验证真实模型与工具协议，不代表任意业务任务均已验证。

## 思考模式开关

模型设置新增二态「思考模式」开关，默认关闭，随模型配置加密保存。请求显式发送 `thinking.type=enabled/disabled`。开启时 `tool_choice=auto`，关闭时强制 `phone_action`；合法工具列表和客户端动作校验不随开关变化。该参数适用于 DeepSeek 等支持 `thinking` 字段的服务。

Pixel 6 真机的 `ModelProtocolTest` 验证配置序列化、旧配置默认值及两种模式的请求组合；`ModelConnectionTest` 使用原有 DeepSeek 配置分别请求开启与关闭模式，两次真实工具调用均通过。测试只校验 `finish` 响应，不执行手机动作，也不改写用户保存的选择。3 项地址解析 JVM 测试、构建和 App Lint 通过。
