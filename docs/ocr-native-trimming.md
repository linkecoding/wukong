# 悟空 OCR 原生运行库裁剪

2026-11-15。产品已接入内置 PP-OCRv6 tiny，并将 OpenCV 和 ONNX Runtime 从完整 Maven 运行库替换为本地裁剪构建。模型仍是原始 FP32 ONNX，模型文件 SHA-256 未变。

## 体积结果

以下为 arm64 独立 APK，按同样工具链测量；体积单位 MiB。打包前清除旧 APK，避免增量 ZIP 空洞影响比较。

| 内容 | 裁剪前 | 裁剪后 |
| --- | ---: | ---: |
| OpenCV 原生库 | 23.51 | 7.20 |
| ONNX Runtime 主库 | 17.08 | 7.55 |
| Debug APK | 64.27 | 38.27 |
| R8 Release APK | 52.94 | 26.66 |

Release 已启用混淆、代码／资源裁剪，JNI 类名和字段合约按实际依赖保留。Release 文件目前为未签名构建，体积与对齐已检查；手机安装和端到端验证使用 Debug APK。默认多 ABI 通用 Debug 包约 97.46 MiB，包含 arm64-v8a、armeabi-v7a、x86、x86_64，不能与单 ABI 体积混为一谈。

## 裁剪范围

OpenCV 4.14.0 只构建 `core`、`imgproc` 和 Java/JNI 接口，去掉 DNN、视频、相机、imgcodecs 等模块。字节图片改由 Android BitmapFactory 解码。关闭未使用的 OpenCL、TBB、ITT、protobuf 和 KleidiCV，保留支持的 NEON／CPU 分派路径。

ONNX Runtime 1.21.1 按 tiny 检测／识别模型的原始图与 CPU 优化图生成算子清单，保留所需标准算子及 FusedConv、FusedMatMul、Gelu 等优化内核；关闭传统 ML 算子、RTTI、遥测并启用 LTO。仍支持原始 ONNX 文件和图优化，没有转换模型精度，也没有依赖新后端或外部 App。

构建支持四个 ABI。原生源码固定 Git 提交，使用 NDK28.2.13676358、API29 和 16 KB max/common page 链接参数，去除多余符号。源码、配置、库和 Java API 的 SHA-256 记录在 [原生模块](../third_party/ocr-native/README.md) 及其 manifest 中；对应 [构建脚本](../scripts/build-ocr-native.py) 已在本工作区执行验证。

裁剪运行库只为当前 tiny 模型提供已验证的内核。以后更换 OCR 模型，应重新生成算子配置、重编译并做识别／坐标回归。

## 真机结果

Pixel 6 / Android 17，CPU4线程、检测最长边960。产品内适配器使用相同固定图片，分别测量完整运行库和裁剪版，每图6次，取后5次中位数。标注行命中忽略空格与标点，保留简繁差异；不是整个图片的字符准确率。

| 图片 | 裁剪前 ms | 裁剪后 ms | 裁剪后标注行命中 |
| --- | ---: | ---: | ---: |
| 常规中英文 | 616 | 623 | 8/8 |
| 24 px 小字 | 591 | 599 | 8/8 |
| 深色背景 | 597 | 611 | 8/8 |
| 真实悟空任务页 | 749 | 751 | 20/20 |

共44个标注行实例全部命中，属于4张图的小样本；每组中位数相差约0.3%–2.3%，这轮未看到明显性能退步，但未做随机交叉或严格温度控制，不能据此认定裁剪一定等速。

- 真机5项本地测试通过：固定图基准、小字连续识别、原图坐标映射、受保护输入提示与 Bitmap 生命周期、主屏和虚拟屏中文坐标点击、长按、滑动、旧捕获失效和后续状态识别。
- 真实 DeepSeek 两项测试通过：分别通过 OCR／截图读取 → 坐标点击 → 再次识别确认 `TAPPED` → 结束。
- 20项核心 JVM、3项地址测试和 App／shell Lint 通过。

原始结果：[完整库基线](../benchmarks/ocr/trim-results/baseline.json)、[裁剪版](../benchmarks/ocr/trim-results/trimmed.json)、[汇总](../benchmarks/ocr/trim-results/summary.json)。

## 16 KB 验证

- 默认多 ABI 包的12个64位原生库，以及 arm64 Debug／Release 包的6个原生库，均通过 ELF LOAD、RELRO 与未压缩 JNI ZIP 16 KB 静态对齐检查。
- 官方 Android15 `google_apis_ps16k;arm64-v8a` 模拟器实测 `getconf PAGE_SIZE = 16384`。**裁剪版在默认 CPU 配置下通过上述5项本地测试**，无需额外内核参数。
- 完整 OpenCV 的首次模拟器测试曾在 SVE `rdvl` 指令上 SIGILL；裁剪版关闭 KleidiCV 后，模拟器仍报告 SVE2／SME 特性，同一套测试已通过。Pixel6真机采用正常系统配置。
- 其他三个 ABI 已交叉编译；x86_64 完成静态对齐检查，未宣称在这些架构上做过运行验证。

## 构建

```
# arm64 开发安装包
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleDebug
# arm64 R8 Release（尚需正式发布签名）
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleRelease
# 校验实际 APK
python3 scripts/check-native-alignment.py app/build/outputs/apk/debug/app-debug.apk
```

对比包体积时使用干净的打包输出，避免已删除／替换的 ZIP 条目留下空洞。复现原生构建的前提与完整命令见原生模块 README。
