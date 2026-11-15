# Wukong 0.0.2 — X launch copy

Draft copy for manual posting. No post has been sent.

## 中文主帖

Attach [the Chinese architecture image](../images/architecture/wukong-architecture.zh-CN.png).

```text
做了个 Android Personal Agent：悟空 🐒

一句话跨 App 办事，悬浮窗看执行，随时接管、交还。

架构如图：模型决策，手机执行。不依赖 root / 无障碍服务，需自行配置模型 API Key。

v0.0.2 已发布：
https://github.com/linkecoding/wukong
```

## 中文首条回复

Attach [the 40-second real-device recording](https://github.com/linkecoding/wukong/releases/download/v0.0.2/wukong-v0.0.2-cross-app-demo.mp4).

```text
附一段真机跨 App 演示：

Chrome 读食谱 → 后台浮窗 → 接管滚动 → 交还 Agent → 时钟准备计时器。

模型负责决策，悟空通过工具调用或 JS 短流程执行，用户随时可以接管或停止。

目前仍是早期预览版，欢迎反馈机型兼容性和实际任务表现。你最想让它帮你做什么？
```

## English post

Attach [the English architecture image](../images/architecture/wukong-architecture.en.png).

```text
Wukong 🐒 — a Personal Agent for Android.

Cross-app tasks, a floating preview, and takeover + handoff.

Built-in ADB + shell engine. No root or AccessibilityService required. Use your own model API key.

v0.0.2 + source/APK:
https://github.com/linkecoding/wukong
```

## Assets and wording

- [Chinese PNG](../images/architecture/wukong-architecture.zh-CN.png) / [editable SVG](../images/architecture/wukong-architecture.zh-CN.svg)
- [English PNG](../images/architecture/wukong-architecture.en.png) / [editable SVG](../images/architecture/wukong-architecture.en.svg)
- [Real-device demo and provenance](../demo/README.md)
- Wukong does **not** provide a bundled model API key or hosted free trial. Users configure their own provider/model/key. OCR is local; model inference uses the configured endpoint.
- The source is public; an open-source license for Wukong's own code has not yet been selected. Use “代码已公开” / “public source”, rather than claiming an open-source license.
- The diagram describes current App functionality. Codex skill / MCP integration remains planned.
