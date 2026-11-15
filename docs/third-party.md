# 内置无线调试依赖

悟空只要求用户安装一个 APK。无线 ADB、SPAKE2 和 TLS 库在 APK 内运行，不需要 Shizuku、LADB、Termux 或其他外部 App。

| 依赖 | 固定版本 | 用途 |
| --- | --- | --- |
| LibADB Android | 3.1.1 + 本地修复 | ADB 协议、连接、数据流及配对 |
| SPAKE2 Android | 2.2.1 + 本地构建 | 系统配对码交换 |
| Conscrypt Android | 2.9.0 | TLS 1.3 与密钥导出 |
| Bouncy Castle | 1.81 | 自签名身份凭据和配对算法依赖 |

许可证、声明和 SPAKE2 对应源代码随 APK 的 `assets/third_party/` 一同分发。LibADB 使用上游提供的 Apache-2.0 许可选项；SPAKE2 保留 LGPL-3.0 条款，不限制为调试其修改而进行的逆向工程。

## 修改并重新构建 SPAKE2

1. `third_party/spake2/` 包含完整 Android / C / JNI 源码和上游许可证；相同源码随 APK 的 `assets/third_party/spake2-2.2.1-source.zip` 分发，包含固定版本的 spake2-c 子模块。
2. 安装 NDK `28.2.13676358`、CMake `3.22.1`，使用项目的 Gradle 工具链构建。模块显式设置 16 KB ELF 对齐和静态 C++ 运行库。
3. 修改该目录源码后执行 `./gradlew :app:assembleDebug`，Gradle 自动重新构建本地库并链接进 APK。没有二进制签名检查阻止使用修改后的库。

配对码不写入应用偏好或日志。ADB 私钥与证书以 Android Keystore 的 AES-GCM 密钥加密保存，卸载会删除应用侧身份；系统配对列表中的旧设备可在无线调试设置里手动遗忘。

LibADB 完整源码保存在 `third_party/libadb/`。本地修改包括流打开确认竞态修复、连接超时和移除配对载荷日志，来源提交及修改说明见该目录的 `README.wukong.md`。

OCR 使用本地模块 `third_party/paddleocr`，来源、固定提交和修改见该目录 `README.wukong.md`。PP-OCRv6 tiny 的 ONNX 检测／识别模型与字典随 APK 内置，下载源与 SHA-256 见 `models-manifest.json`；SDK 与模型采用 Apache-2.0。推理使用本地裁剪的 ONNX Runtime Android 1.21.1（MIT）与 OpenCV 4.14.0（Apache-2.0 及上游第三方条款）；固定源码、重建步骤及二进制校验值见 `third_party/ocr-native`。许可证与 ONNX 第三方声明一并置于 App assets 的 `third_party`。模型无需下载，识别在手机内执行；文字结果会发给用户配置的模型服务，截图仅在使用截图工具时发送。

## Embedded QuickJS

QuickJS 2026-06-04 (MIT) is vendored in `third_party/quickjs/src/main/cpp/vendor` from the official source archive. Archive/file hashes, source URL and version are pinned in `source-manifest.json`; the engine source is unchanged. The local JNI/build layer compiles only the core engine and its Unicode/regexp/dtoa/utility sources, with no std/os modules, command-line interpreter or filesystem/network module loader. The MIT license is distributed as `assets/third_party/quickjs-LICENSE.txt`. No Auto.js code is copied. Native builds use the project NDK, strip symbols and link for 16KB pages; see [runtime contracts and size comparison](architecture/javascript-runtime.md).
