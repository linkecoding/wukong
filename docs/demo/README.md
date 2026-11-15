# Real-device demo

[Full recording: 48 seconds, MP4](https://github.com/linkecoding/wukong/releases/download/v0.0.2/wukong-v0.0.2-demo.mp4)

The README animation is a 28-second selection from a real Pixel 6 / Android 17 screen recording using Wukong 0.0.2 debug code, the saved DeepSeek Flash model and thinking disabled. It shows a shopping-budget request, virtual-display execution in Calculator, manual takeover, handoff, the visible answer, the conversation result and task details with provider-reported token usage.

The request was:

> 用计算器核对购物预算：我有500元，128元买3件、59元买2件，订单打八五折（支付原价85%）。算出剩余预算，停在答案页面。

In English: start with 500, buy three items at 128 and two at 59, pay 85% of the order total, and calculate the remaining budget in Calculator. The correct result is `500 − (128 × 3 + 59 × 2) × 0.85 = 73.3`.

The model performs the device actions. The capture driver only supplies the request/starting-app preference, opens the real preview, pauses/resumes for takeover, and opens details. The successful run was independently checked against the native Calculator package and its visible raw result text. No UI, model reply or result screen is generated or substituted.

The GIF uses chronological cuts at original playback speed. The full video preserves the successful run, including input corrections and waiting, with only encoding normalization and the final recording-stop transition trimmed. It is silent and uses the app's Chinese interface. This is one selected successful run, not a success-rate or latency benchmark. Earlier capture attempts included a failed task and an overly strict capture assertion; wider reliability limits are covered in [real-app validation](../real-world-validation.md).

No credentials, pairing details, other conversations, app inventory or private files are shown. Calculator was temporarily enabled for capture and restored afterward, together with the previous language, virtual-display preference and stay-awake setting.

## 简体中文

首页动图是一次真机成功运行的约 28 秒剪辑，完整录屏约 48 秒。包含真实计算器输入、接管/交还、结果及 Token/日志详情，画面和模型结果均未伪造。完整视频保留输入修正与等待；这是选取的一次成功演示，不代表所有任务都能成功或同样快。发布前已检查私人信息，录制后已恢复临时修改的设备设置。
