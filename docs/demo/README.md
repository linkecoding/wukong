# Real-device demonstrations

## Recipe → timer, with floating takeover

[Full recording: 40 seconds, MP4](https://github.com/linkecoding/wukong/releases/download/v0.0.2/wukong-v0.0.2-cross-app-demo.mp4)

The homepage now features a 29-second highlights animation of a practical cross-app task on a Pixel 6 / Android 17 using Wukong 0.0.2 debug code, the saved DeepSeek Flash configuration and thinking disabled.

The user is reading an [air-fryer recipe page in Chrome, published by ReMember](https://xvcat.com/post/2471). They ask Wukong to find the honey chicken-wing temperature and duration and prepare the corresponding timer in Clock without starting it. The page is opened before recording; the model reads the real page and carries the duration into the other app. Chrome's connectivity warning is visible, and the task uses the content loaded for this run.

The recorded request is:

> 看下浏览器中这份空气炸锅食谱，找到蜜汁鸡翅的温度和时长，再去时钟按这个时长准备倒计时，检查小时、分钟和秒是否正确，先不要开始。告诉我查到的参数。

The recording shows:

1. Sending the request and opening the embedded execution view.
2. Leaving Wukong: the real preview becomes a floating window over the stock launcher.
3. Dragging the floating header, tapping Take over and physically scrolling inside the preview.
4. Handing control back; the agent continues, launches Clock and enters the timer.
5. The native idle timer showing **00 hours, 20 minutes, 00 seconds**, with Start untouched, then returning to the completed conversation.

The source lists the chosen dish at 180°C / 20 minutes. The model's result was checked against the recipe and the actual native timer fields, rather than trusting the summary alone. The host injects only demonstration input on the physical display: moving the float, takeover, scrolling, handoff and returning to Wukong. It does not supply the model's cross-app actions, timer values or completion response.

The GIF uses chronological cuts at original playback speed. The 40-second video preserves the successful run, with encoding normalization and the final recording-stop transition trimmed. Both are silent; all screens and results are real. This is a selected successful run, not a success-rate or latency benchmark. Another recording attempt failed to set the timer correctly; that failure is not edited into a successful execution. General limitations are documented in [real-app validation](../real-world-validation.md).

No credentials, pairing details, other conversations, private files or account screens are shown. The publisher's page remains its own content, credited above. Clock was temporarily enabled for recording and restored to its original disabled state afterward, together with the previous language, preview geometry, virtual-display preference and stay-awake setting. The timer was not started.

## Earlier calculator demonstration

[Full calculator recording: 48 seconds, MP4](https://github.com/linkecoding/wukong/releases/download/v0.0.2/wukong-v0.0.2-demo.mp4) · [28-second calculator highlights](wukong-demo.gif)

This earlier example checks a shopping budget in Calculator, pauses for takeover, resumes and displays the result plus execution details/token usage. The native answer is `500 − (128 × 3 + 59 × 2) × 0.85 = 73.3`. It remains available as a secondary example.

## 简体中文

首页新增约 29 秒的跨 App 真机剪辑，完整视频约 40 秒：从已打开的浏览器食谱读取参数，切到后台用悬浮窗观察，拖动浮窗、接管并实际滚动，再交还 Agent，继续进入时钟准备 20 分钟计时器，最后回到会话查看结果。计时器未启动，原生界面确认是 00 小时 20 分钟 00 秒。

画面、操作和模型结果均未伪造；完整视频保持实际速度。它是选取的一次成功运行，不代表所有任务都能成功或同样快。另一次录制中发生过计时器设置失败，没有把失败拼接成成功。录制后已恢复临时修改的设备设置。原计算器视频保留在上方链接中。
