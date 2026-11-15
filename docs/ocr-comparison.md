# Android OCR 对比：ML Kit 与 PaddleOCR

补充：已完成 [PP-OCRv6 tiny／small 与两套旧方案的同轮真机对比](ocr-v6-comparison.md)。以下保留首次 v5 测量记录，选型判断以补充报告为准。

2026-11-15。悟空现用 `com.google.mlkit:text-recognition-chinese:16.0.1`，模型随 APK 内置。此次使用独立测试程序，不改变产品默认 OCR。

## 比较对象

- Google ML Kit Text Recognition v2 捆绑中文模型 16.0.1。
- PaddleOCR 官方 Android SDK + PP-OCRv5_mobile ONNX 检测／识别模型。当前官方 SDK 也支持 PP-OCRv6；此次先固定 v5 mobile 作为可复现的手机端基线，不把所有 Paddle 模型归为同一种性能。
- 两者均在手机内识别，不依赖外部 OCR App。ML Kit 捆绑模式不需要通过 Google Play Services 首次下载模型；Paddle 的模型也打包进测试 APK。

## 工程差异

| 项目 | ML Kit 捆绑版 | PaddleOCR Android SDK |
| --- | --- | --- |
| 集成 | Maven 依赖，直接处理 Bitmap | SDK 源码／AAR + ONNX Runtime + OpenCV + 模型 |
| 模型管理 | Google 提供固定模型 | 可指定检测／识别模型、调整阈值、线程与预处理 |
| 结果 | 文本块、行、元素及坐标 | 文本、四边形坐标、置信度及阶段耗时 |
| 授权 | ML Kit 专有使用条款 | SDK 源码 Apache-2.0；分发还需核查模型及运行库条款 |
| 16 KB | 悟空当前 64 位 ML Kit 库静态检查通过 | 必须逐个检查实际打包的 ONNX/OpenCV 等原生库，不能由模型格式推断 |

## 测试方法

Pixel 6 / Android 17 Beta、arm64。Paddle SDK 固定于提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，ONNX Runtime 1.21.1，OpenCV 改用官方 4.14.0。常规中文、小字、深色背景、中英混排合成图，以及悟空任务页真实截图。同一张 Bitmap 交给两套引擎；每图各运行 6 次，记录首轮和后 5 次耗时。固定文本可计算精确命中，真实界面结果另行检查。仅为小样本，不代表所有应用页面或手写文本能力；不使用各自官网不同硬件的速度直接比较。

## 官方资料

- [ML Kit Android 文本识别与捆绑依赖](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [PaddleOCR Android 部署与 SDK](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html)
- [Android 16 KB 页支持](https://developer.android.com/guide/practices/page-sizes)


## 默认配置实测

热运行取后 5 次中位数，单位毫秒；24 / 32 / 42 指绘制文字的像素字号，不是 sp。固定文本每图 8 行，精确命中忽略空格，保留简繁字差异。

| 图像 | ML Kit 热运行 | Paddle v5 mobile 热运行 | ML Kit 正确行 | Paddle 正确行 |
| --- | ---: | ---: | ---: | ---: |
| 常规中文与混排，42 px | 483 | 2069 | 7/8 | 7/8 |
| 小字，24 px | 499 | 2042 | 6/8 | 7/8 |
| 深色背景，32 px | 454 | 2098 | 7/8 | 8/8 |
| 悟空真实任务页，1080×2400 | 632 | 3733 | 未计全页准确率 | 未计全页准确率 |

Paddle 额外模型加载计时为 415 ms，此值不含所有 SDK 初始化成本，不能与 ML Kit 首次调用直接比较。完整首轮、各轮耗时、文字和框坐标保存在 [原始结果](../benchmarks/ocr/results-default.json)。

真实页面上，Paddle 识别出「手机内激活」「执行记录」「交给悟空」，ML Kit 将前两者识别成「手机內澈活」「林行记品」，漏掉「交给悟空」。Paddle 对顶部状态图标产生了额外的 `C`、`X` 等误识别。Paddle 在两张合成图中把「虚」识别成「虛」，因此即使人工能理解，也未计为精确命中。这些结果只描述当前样本。

## 体积与 16 KB

以下是测试 APK 中相关文件的**未压缩大小**，不是 APK 下载增量；不含 Java 类和共享依赖，不能拿两个完整 App APK 大小直接相减。

| 项目 | ML Kit | Paddle v5 mobile + 当前运行库 |
| --- | ---: | ---: |
| 模型／配套资源 | 2.37 MiB | 20.51 MiB |
| arm64 原生库 | 10.55 MiB | 41.92 MiB |
| 合计 | 12.92 MiB | 62.43 MiB |

官方示例的 QuickBird OpenCV 4.5.3 及 C++ 运行库未通过 16 KB ELF 检查；在该真机还遇到 `__sfp_handle_exceptions` 符号加载失败。改用 `org.opencv:opencv:4.14.0` 后实际识别可运行，测试 APK 的 ML Kit、ONNX Runtime、OpenCV、C++ 共 5 个 arm64 库均通过 ELF LOAD／RELRO 和 ZIP 16 KB 静态检查。仍未在真实 16 KB 页设备上运行。

复现的测试源码与依赖说明见 [benchmarks/ocr](../benchmarks/ocr/README.md)。测试程序和模型没有加入悟空的产品依赖。


## 缩图配置补测

真实任务页先缩至 720×1600，与悟空最长边 1600 的截图处理相近；合成图保持 1080×1600。Paddle 检测设 `detLimitSideLen=960, detLimitType="max"`，两套引擎仍接收同一张 Bitmap。

| 图像 | ML Kit 热运行 ms | Paddle 检测限 960 热运行 ms | ML Kit 正确行 | Paddle 正确行 |
| --- | ---: | ---: | ---: | ---: |
| 常规 42 px | 498 | 1252 | 7/8 | 7/8 |
| 小字 24 px | 476 | 1167 | 6/8 | 8/8 |
| 深色 32 px | 493 | 1241 | 7/8 | 8/8 |
| 真实任务页 720×1600 | 571 | 2162 | 未计全页准确率 | 未计全页准确率 |

缩图配置下，Paddle 仍正确识别真实页面的上述关键中文，也仍会把部分状态图标识别为文字。ML Kit 的部分中文错误随缩图变化，不能把一次截图上的表现当作稳定准确率。原始结果见 [缩图补测数据](../benchmarks/ocr/results-tuned.json)。两轮均运行完成，共 96 次识别调用。

## 对悟空的建议

目前保留 ML Kit 默认实现。它接入简单、体积更小、这批样本中更快；Paddle v5 mobile 更适合做中文识别的可选增强引擎，尤其用于漏字或错误识别后重新观察。Paddle 的检测分辨率可以调节，但本次测试即使限至 960，耗时仍较高。

若后续接入 Paddle，应针对实际应用截图继续验证文字框和坐标点击、低置信度／图标误识别、长期内存与温度，并核查最终安装包的 16 KB 实机运行。本次没有测 PP-OCRv6、量化模型或其他推理后端，不能推出所有 PaddleOCR 方案都具有相同体积或速度。
