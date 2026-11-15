# PP-OCRv6 手机端与 ML Kit、PP-OCRv5 真机对比

2026-11-15，Pixel 6 / Android 17 Beta / arm64，直接使用 USB 连接的真机测试。四组全部重新运行，各 4 张图 × 6 次，共 **96 次识别调用**；四个设备测试均完成通过。

这批样本中，**PP-OCRv6 tiny 最值得作为悟空后续的中文 OCR 候选**：标注行全部命中，速度接近 ML Kit，模型资源显著小于 v5 mobile。small 在此次样本上未体现精度优势且更慢。此处记录独立测试程序的对比；0.4.1 已将 PP-OCRv6 tiny 接入产品，接入后的设备验证见 [视觉工具与虚拟屏](visual-virtual.md)。

## 配置与方法

- Google `com.google.mlkit:text-recognition-chinese:16.0.1` 捆绑模型。
- Paddle 官方 Android SDK 固定提交 `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`，ONNX Runtime 1.21.1、官方 OpenCV 4.14.0；三套模型均为官方 ONNX det + rec。v6 提供 tiny 和 small，参见 [官方 Android 模型列表](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html)。
- Paddle 均使用 CPU 4 线程、检测最长边 960，其他检测和识别参数保持 SDK 默认。每个进程仅初始化一组模型；顺序为 tiny、ML Kit、small、v5。
- 合成图 1080×1600：42 px 常规文字、24 px 小字、32 px 深色背景，各 8 行中英文混排。真实悟空任务页缩为 720×1600，共人工标注 20 行。样本与上轮相同。
- 命中规则：NFKC 后去空白和 Unicode 标点逐行比较，保留简繁差异。分开统计标注行命中与额外误识别，不把行命中率当整页精确率。下表使用最后一轮结果，已核对各组各图六轮命中数完全一致。
- 速度为每张图后 5 次的中位数，包含 SDK 识别调用和结果整理，不包含模型创建。首轮与创建耗时也保存在原始数据。
- 四组开始和结束均报告 Android thermal status 0（NONE），电池温度 29.7°C。这是系统报告与电池温度，不是 CPU 核心温度；顺序测试未做随机交叉，仍存在调度和温度影响。

## 识别效果与速度

| 方案 | 常规文字 | 小字 | 深色背景 | 真实任务页 | 总标注行命中 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ML Kit 16.0.1 | 7/8 | 6/8 | 7/8 | 13/20 | 33/44 |
| PP-OCRv5 mobile | 7/8 | 8/8 | 8/8 | 20/20 | 43/44 |
| PP-OCRv6 tiny | 8/8 | 8/8 | 8/8 | 20/20 | 44/44 |
| PP-OCRv6 small | 8/8 | 8/8 | 7/8 | 20/20 | 43/44 |

下表单位为毫秒，均为热运行中位数。

| 方案 | 常规文字 | 小字 | 深色背景 | 真实任务页 |
| --- | ---: | ---: | ---: | ---: |
| ML Kit 16.0.1 | 549 | 486 | 491 | 521 |
| PP-OCRv5 mobile | 1260 | 1167 | 1244 | 2148 |
| PP-OCRv6 tiny | 617 | 598 | 606 | 750 |
| PP-OCRv6 small | 1861 | 1777 | 1788 | 2850 |

具体差异：

- v6 tiny 正确识别「接管并继续操作」「网络连接已恢复」「手机内激活」「执行记录」等全部标注行，但将顶部状态栏图标误识别为「志》」。
- v6 small 在深色图中把「虚」识别成「虛」；真实页面 20 行全部命中，但额外识别出「老川」「4」「1」等图标／控件伪文字。
- v5 mobile 在常规图中把「虚」识别成「虛」；其余两张合成图及真实任务页标注行全部命中。真实页面仍有状态图标误检。
- ML Kit 的真实页面标注行命中 13/20，未命中包含「交给悟空」「手机内激活」「执行记录」等七行；这是整行要求完全匹配的统计，部分行只错一个字或漏一个字，并不表示七行全部没有识别。

## 体积

用相同 AGP/Kotlin/minSdk、arm64、未裁剪 debug 的空白 App 分别构建，排除截图和测试代码，每组仅加入所需 SDK 与一套模型。基线 APK 为 2.24 MiB。**APK 增量是实验空 App 的实测增量，不是最终悟空 release/AAB 大小预测**；模型资源与原生库为未压缩大小，不等同 APK 下载增量。

| 方案 | 模型／配套资源 MiB | arm64 原生库 MiB | 独立 APK MiB | 相对空 App 增量 MiB |
| --- | ---: | ---: | ---: | ---: |
| ML Kit 16.0.1 | 2.37 | 10.55 | 21.67 | 19.43 |
| PP-OCRv5 mobile | 20.51 | 41.92 | 67.55 | 65.32 |
| PP-OCRv6 tiny | 6.01 | 41.92 | 55.08 | 52.84 |
| PP-OCRv6 small | 29.75 | 41.92 | 74.86 | 72.62 |

Paddle 三组共享同样的 ONNX/OpenCV/C++ 运行库，所以 tiny 虽然模型只有约 6 MiB，当前通用依赖下 APK 增量仍较大。裁剪运行库或更换推理后端不在本次测量范围，不能用猜测的优化体积替代实测。

四组独立 APK 的全部 arm64 原生库均通过 16 KB ELF LOAD、RELRO 和未压缩 JNI ZIP 对齐静态检查。本机实际为 4 KB 页模式，未视为 16 KB 设备运行验证。

## 对悟空的判断

优先考虑 **v6 tiny** 作为后续 Paddle 集成候选：此处比 v5 更快、更小，标注行表现也更好。small 暂不值得为这批手机界面付出额外体积和延迟。若更重视最小体积，ML Kit 仍有明显优势。

这是 4 张图、44 个标注行实例的小样本，且合成图重复使用同一组 8 条短句，不能外推为通用 OCR 准确率。未测试旋转文本、手写、复杂表格、其他手机、NNAPI/GPU、量化模型，也尚未验证 v6 文本框与悟空坐标操作的端到端行为。

## 复现与数据

- [测试源码、依赖与运行说明](../benchmarks/ocr/README.md)
- [本轮测试代码](../benchmarks/ocr/V6ComparisonTest.kt)
- [逐图耗时和命中汇总](../benchmarks/ocr/v6-results/summary.json)
- [模型下载地址与 SHA-256](../benchmarks/ocr/models-manifest.json)
- [体积原始字节数](../benchmarks/ocr/v6-results/sizes.json)
- [ML Kit 原始输出](../benchmarks/ocr/v6-results/mlkit.json)、[v5 mobile 原始输出](../benchmarks/ocr/v6-results/v5-mobile.json)、[v6 tiny 原始输出](../benchmarks/ocr/v6-results/v6-tiny.json)、[v6 small 原始输出](../benchmarks/ocr/v6-results/v6-small.json)
