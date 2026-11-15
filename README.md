# 悟空 Wukong

悟空是一款 Android 手机任务助手：用自然语言描述目标，AI 在整台手机上跨应用操作，普通操作自主执行，敏感操作前等待确认。

当前是 **0.4 工程原型**。无线 ADB 配对、连接和执行引擎全部内置于悟空 APK，不依赖 Shizuku、LADB、Termux 或其他外部 App。执行底座采用 **内置 ADB → app_process → 独立 shell 进程（UID 2000）**，不需要启用无障碍服务或 root。Android App 保持普通应用权限，负责模型、任务调度、历史和悬浮控制。

## 手机内激活

免电脑激活需要 **Android 11 或以上、已连接 Wi-Fi、系统开发者选项中的无线调试**。系统配对授权需用户手动完成，普通 App 不能自行授予 shell 权限。

1. 安装悟空 APK，进入「手机内激活」，点击「启动配对助手」，允许通知。
2. 在系统开发者选项中开启「无线调试」，选择「使用配对码配对设备」。
3. 保持系统配对窗口打开，下拉通知栏，在悟空通知中点击「输入配对码」，提交系统显示的六位数字。
4. 悟空自动发现本机端口、配对并启动执行引擎。若系统限制自动发现，可在悟空激活页填写端口；配对端口与无线调试主页的连接端口不同。
5. 等待「激活成功」，回到悟空开始任务。手机重启或引擎退出后，开启无线调试并点击「激活执行引擎」；系统仍保留悟空的配对身份时无需重新输入配对码。

执行载体已打包在悟空 APK 中，激活时由悟空自行上传到本机 `/data/local/tmp/` 并启动，不安装第二个 App。Android 10 不支持这条免电脑配对路径，仍可使用下述开发脚本。

## 使用任务

1. 确认任务页显示「执行引擎已连接」。
2. 点击「允许悬浮控制条」，授权显示在其他应用上层。该权限只用于确认、停止和查看状态。
3. 在「设置」填写 HTTPS Base URL、模型 ID 和 API Key。Base URL 不限制路径结尾，默认追加 `/chat/completions`；若填写自定义完整请求路径，勾选「使用完整请求地址」即可原样请求。例如 DeepSeek 的 Base URL 填 `https://api.deepseek.com`，不勾选该选项。接口须兼容 Chat Completions 并支持 function/tool calling。模型设置的「思考模式」开关会显式发送 `thinking.type=enabled/disabled`，适用于 DeepSeek 等支持该参数的服务；默认关闭。开启时使用自动工具选择，关闭时要求从已提供的工具中选择一个。两种模式都只接受一个合法工具调用，敏感动作仍需确认。「测试连接」使用与任务相同的工具定义，请求模型返回结束动作，只校验响应，不执行设备操作。
4. 输入简短目标并开始任务。默认从桌面开始，可选起始应用；后续操作可跨应用、桌面和系统页面。
5. 普通点击、输入、滚动和跨应用导航自动执行；支付、删除、发送发布、安全授权等敏感操作在悬浮条确认或拒绝，可随时停止。输入动作替换整个输入框。审批期间界面变化会使该次审批失效。

支持 UI 节点读取、中文输入、导航，以及截图、内置中英文 OCR、坐标点击、滑动和长按。可在主屏或独立虚拟屏运行；虚拟屏在悟空前台内嵌显示、后台悬浮显示，可接管、触摸和输入，再交还 Agent 重新读屏继续。密码、验证码和自动解锁仍需手动处理。详见 [视觉工具与虚拟屏](docs/visual-virtual.md)。当前 OCR 使用内置 PP-OCRv6 tiny 检测／识别模型及裁剪后的 OpenCV／ONNX 运行库，端侧 PaddleOCR 对比见 [OCR 四组真机实测](docs/ocr-v6-comparison.md)。

## 使用虚拟屏

在任务页开启「在虚拟屏执行」，等待预览出现，再填写目标开始任务。把悟空放到后台后，预览自动变为悬浮窗。点「接管」暂停 Agent 后可触摸预览，内嵌视图还可向焦点输入框发送中文。点「交还 Agent」后丢弃接管前的决策，重新读屏继续。关闭虚拟屏会停止该屏上的任务。

虚拟屏预览当前采用约 3 帧/秒的 JPEG 更新，适合观察和轻量接管，不是高帧率视频。部分应用限制多屏、复用单任务或显示受保护内容，无法保证所有应用同时在主屏与虚拟屏独立运行。

## 工程与构建

使用 JDK 17、Android SDK 35、Build Tools 35.0.0、NDK 28.2.13676358 和 CMake 3.22.1。项目固定 AGP 8.9.2、Gradle 8.11.1、Kotlin 2.1.10。

```text
agent-core/      纯 Kotlin 任务循环、动作校验、审批和取消
app/             原生 Android App、模型、本机存储、shell 客户端与悬浮控制
shell-runtime/   app_process 入口、UiAutomation、输入注入和带鉴权的本机协议
third_party/     内置 ADB 传输源码及本机快速响应竞态修复
scripts/         构建辅助、开发用 USB 激活、真机和模拟器测试
```

在 Android Studio 中选择 JDK 17，或配置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```sh
./gradlew :agent-core:test :app:assembleDebug :shell-runtime:assembleDebug :app:lintDebug :shell-runtime:lintDebug
```

也可用 `./scripts/gradle-local.sh`，读取电脑共享的 `JAVA_HOME`、`ANDROID_HOME`；macOS 会自动寻找 JDK17 和 `~/Library/Android/sdk`。`.tooling/` 仅用于本地构建缓存，不保存项目必需的开发工具。App 产物是 `app/build/outputs/apk/debug/app-debug.apk`，shell 载体是 `shell-runtime/build/outputs/apk/debug/shell-runtime-debug.apk`。

## 权限和数据

shell 进程通过 UiAutomation 读取 Android UI 节点，并注入触摸和按键事件。UiAutomation 使用 Android 的内部 UI 自动化基础设施；这不等同于安装或启用一个 AccessibilityService。App 清单没有 `BIND_ACCESSIBILITY_SERVICE` 或无障碍服务注册。

连接只监听 `127.0.0.1:43219`，每次请求要求随机 256 位令牌。悟空内置激活直接在自己的进程中生成令牌；供开发脚本使用的外部激活入口需要 DUMP 权限且检查调用 UID 必须为 shell 2000，其他普通 App 不能取得或轮换令牌。ADB 身份私钥使用 Android Keystore 加密保存，配对码不落盘。令牌存在 App 私有目录，临时传递文件仅 shell 可读，并在启动时删除。不向模型发送令牌，也不开放任意 shell 命令接口。

任务目标、可启动应用列表和必要界面文字会发送给你配置的模型服务。模型密钥通过 Android Keystore 加密，只在 App 中使用，不交给 shell 进程。历史保留最近 30 次任务的描述、时间和结果，可在 App 中清空。备份和设备迁移排除了应用数据。

普通设备动作自主执行。App 根据目标节点和页面文字识别敏感动作并要求本机确认；识别为启发式，不能覆盖所有应用的自绘控件或未知文案。shell 端再次检查会话取消状态、当前包名与快照指纹，拒绝过期节点。密码节点和敏感输入识别是启发式保护，不能覆盖所有敏感内容。已被 Android 接受的动作无法撤销。

## 验证与后续接入

已在 Pixel 6 / Android 17 验证内置配对、手机内激活、shell 读屏、中文输入、触摸注入、滚动、跨应用导航、鉴权、过期快照和取消；已用用户配置的 DeepSeek 真机验证真实工具调用；完整业务任务仍需验证。反射访问的系统内部 API 仍需要逐机型、逐系统版本验证。

后续路径是 **Codex skill → MCP/本机桥接 → 悟空任务与设备接口**。skill 和 MCP 尚未实现，架构见 [Agent 接入规划](docs/agent-integration.md)、[执行架构](docs/architecture.md) 和 [验证记录](docs/validation.md)。

## 开发用电脑激活

这条路径仅供开发调试，手机内激活不需要执行它：

```sh
./scripts/gradle-local.sh :app:assembleDebug
python3 scripts/activate-shell.py SERIAL
# 停止引擎
python3 scripts/activate-shell.py SERIAL --stop
```

需要电脑 ADB、Python 3 和手机 USB 调试授权。`SERIAL` 必须明确指定；支持 `--skip-install`、`--adb` 和 `--server-port`。

第三方库版本、许可证和修改库后的重新构建说明见 [内置依赖](docs/third-party.md)。

## 参考

- [Pinky](https://github.com/DIYgod/pinky)：Android 支持、接管与执行记录思路。
- [Poki](https://github.com/josssphzhou/poki)：手机内的任务交互。
- [ShadowAuto](https://github.com/android-notes/ShadowAuto)：app_process、shell UiAutomation 和 VirtualDisplay 技术原型。

以上是设计和技术研究来源，当前代码与图形资产为本项目实现。

运行日志的开关、保留范围和验证结果见 [自主执行与运行日志](docs/autonomy-logs.md)。

只为当前 arm64 手机构建时可使用 `./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleDebug`，可减少多架构原生库的包体积；默认构建仍包含全部支持的 ABI。

原生库裁剪后的包体积、真机识别／坐标点击和 16 KB 运行验证见 [OCR 原生库裁剪](docs/ocr-native-trimming.md)。

共享工具安装与新电脑构建步骤见 [开发环境](docs/development.md)。
