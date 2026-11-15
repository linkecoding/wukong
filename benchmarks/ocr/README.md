# OCR 对比测试

仅用于单独的 Android 测试工程，不加入悟空生产依赖。

- Android compile/target 35，min 29，JDK 17，Kotlin 2.1.10，AGP 8.9.2，arm64-v8a。
- ML Kit：`com.google.mlkit:text-recognition-chinese:16.0.1`。
- Paddle 官方仓库提交：`dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，使用 `deploy/ppocr-android/ppocr-sdk` 源码。
- 将 SDK 中 OpenCV 依赖改成 `org.opencv:opencv:4.14.0`；ONNX Runtime 保留 `1.21.1`，协程 `1.9.0`。
- 使用官方 PP-OCRv5_mobile 的 ONNX det／rec 模型；`inference.onnx` 分别放入 SDK 的 `assets/models/det`、`assets/models/rec`，识别 `inference.yml` 放在 rec 同目录。
- 添加此处的 `OcrComparisonTest.kt` 到独立 App 的 androidTest；依赖 AndroidX runner 1.6.2、JUnit extension 1.2.1。
- App assets 添加 `wukong-task.png`，使用 `samples/wukong-task.png` 中的悟空任务页真实截图（不含个人输入或密钥）。
- 构建 App 与 test APK 后安装，运行 `adb shell am instrument -w com.wukong.ocrbenchmark.test/androidx.test.runner.AndroidJUnitRunner`。
- 从目标 App 私有目录读取 `comparison.json`，例如调试包使用 `adb exec-out run-as com.wukong.ocrbenchmark cat files/comparison.json`；测试图也写入 files。

补测参数：instrument 命令加 `-e tuned true`，此时真实截图最长边缩至 1600，Paddle 检测参数设为 `detLimitSideLen=960, detLimitType="max"`。结果写入 `comparison-tuned.json`。

每张图每引擎 6 次，首轮单独记录，后 5 次中位数作为热运行指标。固定顺序 ML Kit 后 Paddle，非随机交叉；设备温度、调度和线程配置均会影响结果。Paddle 使用官方默认预处理和 4 个推理线程，未做裁剪量化或缩图优化。

报告见 [对比说明](../../docs/ocr-comparison.md)。


## PP-OCRv6 四组真机对比

使用 `V6ComparisonTest.kt`（独立测试工程），一次只初始化一个引擎，进程结束后运行下一组：

```
adb shell am instrument -w -e class com.wukong.ocrbenchmark.V6ComparisonTest -e engine v6-tiny com.wukong.ocrbenchmark.test/androidx.test.runner.AndroidJUnitRunner
```

`engine` 可选 `mlkit`、`v5-mobile`、`v6-tiny`、`v6-small`。Paddle 模型文件放入 SDK `assets/models/<engine>/det/inference.onnx` 与 `assets/models/<engine>/rec/{inference.onnx,inference.yml}`。模型下载地址、文件字节数与 SHA-256 见 `models-manifest.json`。ML Kit 使用相同依赖与样本；真实截图缩为 720×1600，Paddle 检测最长边 960、CPU 4 线程。

每组结果写入目标 App 私有 `files/comparison-<engine>.json`。本轮原始数据在 `v6-results/`，各次识别都保留文字、框和耗时，记录开始／结束温度和 Android thermal status。

识别行命中：对期望和识别行做 Unicode NFKC，去空白与 Unicode 标点后比较；不合并简繁字。每图采用最后一轮文字统计，并检查六轮结果是否一致；速度取后五轮中位数。20 行真实页面标注由样本图人工核对，状态栏不计入标注。命中数不会惩罚额外误检，额外图标误识别在报告单独说明。

体积工程使用 `size-app.gradle.kts` 和 `size-sdk.gradle.kts`，工具链／版本目录同上，空白 App 不含截图及测试代码；SDK `src/sizeAssets/<engine>/models/` 仅放该组模型。以 `-PsizeEngine=baseline/mlkit/v5-mobile/v6-tiny/v6-small :app:assembleDebug` 分别构建，保留各 APK，统计 APK 字节数、相对 baseline 的增量、模型资源和 arm64 原生库大小。baseline 不引入 ML Kit/Paddle；各组均为未裁剪 arm64 debug，不是最终悟空 release APK 预测。

完整本轮结论与限制见 [PP-OCRv6 真机报告](../../docs/ocr-v6-comparison.md)。
