# 悟空 0.4：视觉工具与虚拟屏

原来的节点工具适合表单与常规导航，自绘页面或图片文字可能缺少节点。OCR（光学字符识别）从截图中提取文字和位置，为这些页面补充可操作的证据。

## 提供给模型的工具

| 工具 | 行为 |
| --- | --- |
| `phone_action` | 节点点击、文字输入、节点滚动、返回、桌面、打开应用、等待、结束 |
| `phone_screenshot` | 截图，返回画面与本地 OCR；下一轮将 JPEG 发给模型 |
| `phone_ocr` | 用 APK 内置的中文／拉丁文模型识别文字与边界，不向模型发送图片 |
| `phone_tap_at` | 按截图坐标点击 |
| `phone_swipe` | 按截图坐标滑动，时长 100–2000 ms |
| `phone_long_press` | 长按截图坐标，时长 400–2000 ms |

模型每轮只能返回一个工具调用，关闭思考时要求从上述工具中选择，开启时使用自动选择。已执行的工具结果按 assistant/tool 消息衔接，保留服务端要求的思考字段；不会把同一个任务每轮都当作新开始。模型没有任意 shell 命令工具。

截图和 OCR 结果都带 `captureId`，位置统一为整个画面的 0–1000 坐标，不是像素坐标。坐标手势必须引用当前捕获；服务端再次校验会话、显示、页面指纹、分辨率与 30 秒有效期，成功操作后立即作废捕获。过期且明确未执行的操作可重新观察规划，结果未知不重放。

坐标操作仍经过敏感操作检查，结合节点与 OCR 文字；未知目标需要确认，不能借坐标绕过支付、删除等确认。密码节点及可识别的受保护输入提示不会交给模型。识别属于启发式，不能覆盖所有自绘界面和未知文案。

OCR 模型和运行库随 APK 打包，不需要外部 App 或首次下载模型。OCR 本地执行，但识别结果会作为任务上下文交给配置的模型。截图工具会把画面发送给模型；若服务端明确拒绝图片输入，会回退到同次捕获的 OCR 文本。日志保存调用、耗时和用量，不保存图片或原始界面树。0.4.1 起改用 PP-OCRv6 tiny，使用本地 ONNX Runtime 与 OpenCV 推理；模型来源和许可证见第三方依赖文档。

## 虚拟屏与接管

任务页开启「在虚拟屏执行」，等待画面就绪后开始任务。当前按主屏逻辑分辨率与密度创建独立 VirtualDisplay（预览 JPEG 最长边不超过 1600 像素），使用内置虚拟桌面选择应用。读屏、截图、节点动作、按键和手势都绑定目标显示 ID，不从全局焦点猜测显示。

- 悟空前台：内嵌预览，可接管、触摸、返回桌面及向焦点输入框发送中文。
- 悟空后台：约 3 帧/秒的悬浮预览，提供接管／交还、返回、桌面和回悟空入口。
- 接管：暂停 Agent，服务端拒绝 Agent 输入后才开放手动控制。
- 交还：使接管前或接管中产生的模型决策失效，清理视觉证据，重新读屏继续原目标。
- 关闭虚拟屏：停止该屏任务并释放显示与 ImageReader。主屏模式仍可单独使用。

当前是 JPEG 预览，不是高帧率视频串流。部分应用不支持多屏或会复用已有单任务，不能保证同一应用同时在两块屏幕独立运行；受保护画面也受 Android 限制。虚拟屏需要 Android 11 以上与已激活的 shell 引擎。内部协议升级到 2，旧引擎需要重新激活。

## 真机验证

2026 年 11 月 14 日，Pixel 6 / Android 17：

- 20 项核心 JVM 测试、3 项地址测试和 App／shell Lint 通过。
- 联合设备测试 8 个用例全部通过：原有驱动、无语义节点画布上的 OCR／点击／滑动／长按、虚拟屏独立窗口与帧、手动触摸和中文输入、主屏隔离、前后台预览切换、接管暂停与交还、日志与模型协议。
- 真实 DeepSeek 模型分别完成 `OCR → 坐标点击 → OCR 验证 → finish` 和 `截图 → 坐标点击 → 截图验证 → finish`，校验画布实际变化。
- 真实虚拟屏任务在人工接管和交还后继续打开系统设置，实际抵达目标；主屏仍显示悟空。
- APK 包含中文和拉丁文 OCR 模型；6 个 64 位原生库的 ELF LOAD、RELRO 和未压缩 JNI 的 ZIP 16 KB 对齐检查通过（`python3 scripts/check-native-alignment.py app/build/outputs/apk/debug/app-debug.apk`）。真机当前为 4 KB 页模式，未把它当作 16 KB 设备运行验证。

测试不付款、不删除用户文件、不发送消息。参考 ShadowAuto 的虚拟显示、显示定向输入和本地 IME 技术方向；悟空仍通过自己的单 APK 内置配对启动，不依赖其控制 App。


2026 年 11 月 15 日补充验证：

- Android 15 模拟器：虚拟屏连续创建／捕获／释放 8 次、旧所有者销毁请求隔离、独立画面与手动输入、视觉工具，共 4 项用例通过。
- 最新 APK 在 Pixel 6 上通过独立虚拟屏触摸／中文输入，以及内嵌与后台悬浮切换、真实 DeepSeek 任务接管和交还，共 2 项用例。模型测试曾因 Wi-Fi 断开失败，恢复网络后重测通过。
- 较早的高频创建销毁测试触发过 Android 17 Beta 的 `AppCompatDisplayCompatPolicy` 系统崩溃并导致系统软重启。现按主屏几何参数创建显示，声明配置变化处理，在关闭前移除虚拟任务，并使用 `DESTROY_CONTENT_ON_REMOVAL`；高频压力只在模拟器运行。正常流程已重测通过，但尚不能证明所有 Android 17 Beta 兼容性问题均已消除。
- 修复了 ImageReader 帧复制与释放并发导致的 shell 原生崩溃；复制和释放使用同一锁，模拟器生命周期压力测试通过。


## 0.4.1：PP-OCRv6 tiny 初次接入（裁剪前）

2026-11-15，产品 OCR 改为随 APK 内置的 `PP-OCRv6_tiny_det` + `PP-OCRv6_tiny_rec`，ONNX Runtime 1.21.1、OpenCV 4.14.0，CPU 4 线程、检测最长边 960。原 ML Kit 依赖及其资源已从安装包移除。四边形文字框映射为原截图上的 0–1000 外接框；截图工具与 OCR 工具均使用同一个本地引擎。后台线程串行复用推理会话，调用结束后才释放其拥有的 Bitmap。模型 SHA-256 与基准模型一致。

- Pixel 6 真机：2 项适配器测试通过，覆盖 24 px 中英文 8 行连续识别 3 次、原图坐标映射、受保护输入提示拦截和 Bitmap 释放。
- Pixel 6 真机：2 项画布端到端测试通过，覆盖主屏和虚拟屏上的中文 OCR 文字定位、坐标点击、长按、滑动、操作后状态识别、旧捕获失效与截图。
- 真实 DeepSeek：OCR／截图两条路径均完成识别 → 坐标点击 → 再识别确认 `TAPPED` → 结束，共 2 项通过。
- ARM64 安装包重新安装后，本地 4 项测试再次通过。
- 20 项核心 JVM、3 项地址测试与 App／shell Lint 通过。通用包的 12 个 64 位原生库、ARM64 包的 6 个原生库和 R8 Release 包均通过 ELF LOAD／RELRO 与 ZIP 16 KB 对齐检查。

### 包体积

当前 arm64 Debug APK 64.27 MiB，尚未做 R8 裁剪的 DEX 占 12.57 MiB。主要内容为 OpenCV 23.51 MiB、ONNX Runtime 17.08 MiB、C++ 运行库 1.23 MiB、Conscrypt 2.66 MiB、压缩后的 tiny 模型及字典 5.46 MiB，其余为 App、资源、配对库及声明文件。

Release 已启用 R8 混淆、代码和资源裁剪，并为 ONNX/OpenCV/Conscrypt/SPAKE2 的 JNI 合约保留名称。arm64 未签名 Release 实测 52.94 MiB，DEX 降至 1.48 MiB；这部分目前是构建与体积验证，手机安装与端到端验证使用 Debug 包。R8 无法替代 OpenCV／ONNX 原生运行库的专项裁剪。

构建 arm64 包：`./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleDebug`。不指定 `targetAbi` 时默认保留全部 ABI 支持，通用调试包会大得多。


### 16 KB 运行验证

官方 Android 15 `google_apis_ps16k;arm64-v8a` 模拟器，`getconf PAGE_SIZE` 实测 `16384`。同一 arm64 Debug APK 完成 `PaddleOcrTest` 和 `VisualToolsTest`，共 4 项通过：本地推理、中文小字、原图坐标映射、受保护提示、主屏／虚拟屏坐标点击、长按、滑动与后续状态识别。

第一次启动原生 OpenCV 时出现 SIGILL，反汇编定位到 `rdvl`（SVE）指令。该 Apple Silicon 模拟器的 `/proc/cpuinfo` 报告 SVE2／SME；使用内核参数 `-qemu -append 'arm64.nosve arm64.nosme'` 后相同 APK 通过测试，页大小仍为 16384。由此判断问题与模拟器 CPU 特性有关，不能把首次失败归为 ELF 页对齐错误，也不能声称默认模拟器配置通过。产品未加入针对模拟器的推理绕过或禁用优化代码。

复现参数来自 [Android 内核参数文档](https://android.git.googlesource.com/kernel/common/+/2bea8c4527de36a871fe953dc2061a58994bc60a/Documentation/admin-guide/kernel-parameters.txt) 中的 `arm64.nosve`、`arm64.nosme`。Pixel 6 真机测试使用正常系统配置，未修改内核参数。


后续已完成 OpenCV／ONNX 裁剪，默认 CPU 配置的 16 KB 模拟器运行回归也已通过。当前包体积与验证以 [原生库裁剪报告](ocr-native-trimming.md) 为准。
