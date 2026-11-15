# 开发环境与目录

项目根目录为 `wukong/`，独立 Git 仓库从这里开始。SDK、JDK 和 Gradle 安装在电脑共享位置，不提交到项目；本地编译与设备诊断缓存使用被忽略的 `.tooling`。

## macOS 共享工具

当前采用用户级标准目录，各项目可复用：

| 内容 | 路径 |
| --- | --- |
| Android SDK、NDK、CMake、模拟器及系统镜像 | `~/Library/Android/sdk` |
| Temurin JDK17 | `~/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |
| Gradle 8.11.1 命令 | `~/Library/Developer/Gradle/gradle-8.11.1/bin/gradle` |
| AVD 配置及虚拟设备数据 | `~/.android/avd` |
| 兼容版 Platform Tools 35.0.2 | `~/Library/Android/platform-tools/35.0.2` |

共享环境变量由 `~/.config/android/env.sh` 定义，`.zprofile` 和 `.zshrc` 引入。新终端会获得 `JAVA_HOME`、`ANDROID_HOME` 及工具 PATH；已有终端可执行 `source ~/.config/android/env.sh`。

此测试设备的 Android17 Beta 在 Platform Tools 37 的 USB 枚举下未显示，因此日常 `adb` 指向独立安装的35.0.2；SDK自带37.0.1仍保留，可通过 `$ANDROID_HOME/platform-tools/adb` 显式调用。ADB使用默认5037端口，不要求项目私有端口。其他项目可按设备兼容情况选择版本。

构建固定使用仓库中的 Gradle Wrapper，独立 `gradle` 命令仅作为电脑通用工具。

## 新电脑准备

安装 JDK17，并通过 Android Studio 或 sdkmanager 安装：

```
platform-tools
platforms;android-35
build-tools;35.0.0
ndk;28.2.13676358
cmake;3.22.1
```

设置 `JAVA_HOME`、`ANDROID_HOME`，或在未跟踪的 `local.properties` 中配置 `sdk.dir`。随后执行：

```bash
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleDebug
./scripts/gradle-local.sh :agent-core:test :app:testDebugUnitTest :app:lintDebug
```

`gradle-local.sh` 优先沿用环境变量，macOS 自动查找JDK17与默认SDK路径；Linux可用 `~/Android/Sdk`。原生库已随源码保存，普通 App 构建无需重编 OpenCV/ONNX。需要重建时见 `third_party/ocr-native/README.md`；其SDK/JDK同样读取电脑环境变量，`.tooling`仅保存下载的源码和构建缓存，可用 `WUKONG_BUILD_CACHE` 指向其他本地位置。

## 仓库内容

提交 App、Agent、shell源码、内置tiny模型、裁剪运行库、许可证、重建脚本、固定测试样本与验证结果。

忽略本地工具和缓存、IDE状态、生成的APK/AAB、诊断日志、`local.properties`、明文签名库及密码配置。原始签名密钥未加入仓库；若以后采用加密文件备份，`*.jks.gpg` 可提交，解密口令应单独保管。
