# Disclaimer and data handling

Wukong is experimental phone-automation software. Its model can misread a page, choose the wrong control, repeat a step or report an incorrect result. Tests on one device or task do not establish reliability across apps and devices. There is no promise of uninterrupted operation, task success or compatibility. This document describes current limitations; it is not a software license.

## Device control and responsibility

Use Wukong only on devices, accounts and content you are authorized to operate, and follow applicable requirements and the services' rules. Do not use it to bypass access controls or spam other people. Avoid unattended tasks where a wrong action would cause a loss you cannot accept.

Routine actions, including sending, publishing, sharing and permission changes, may proceed autonomously when the agent considers them part of your task. Payments/transfers and destructive actions such as file deletion or clearing app data require confirmation when recognized. Recognition is heuristic and is not a guarantee that every risky action will be caught. Takeover and Stop interrupt subsequent work; they cannot undo an action already accepted by Android or an external service.

Wireless debugging grants a shell execution engine powerful control of your device. Authorize pairing yourself, protect your phone, and disable wireless debugging/revoke the pairing when no longer needed. Wukong cannot bypass secure/protected screens or guarantee all apps work in virtual displays.

## Model costs and data

You supply your own model account, endpoint, key and balance. Requests may incur charges under your provider's terms. Wukong has no hosted free trial and no affiliation with a model provider is implied.

OCR runs locally. Task goals, bounded conversation context, launchable app lists, relevant UI text and task evidence are sent to your configured model endpoint. A requested screenshot can also be sent. The provider's data-handling rules apply; Wukong cannot control a custom endpoint's retention. Avoid opening private content during a task unless you accept sending the needed context to that endpoint.

API keys and ADB identities are encrypted using Android Keystore. History and optional logs are stored on-device. Exported logs exclude model configuration credentials and screenshots, but task text, arguments and results can contain private information. Inspect exports before sharing. Redaction and protected-input detection are not comprehensive privacy guarantees. See [autonomy and logs](docs/autonomy-logs.md) for implementation details.

## 简体中文

悟空是实验性手机自动化软件，模型可能误读页面、点错控件、重复操作或错误报告结果。单一机型和任务的验证不能保证所有应用兼容或任务成功。仅在你有权操作的设备、账号和内容上使用，并遵守相关要求及平台规则。不要把无法承担错误后果的任务交给无人值守执行。

普通操作可能自主执行，包括任务需要的发送、发布、分享和权限变更。支付转账、删除文件、清除数据等操作在被识别时需要确认，但识别是启发式的，不能保证拦截所有风险。接管和停止不能撤销已被系统或外部服务接受的操作。无线调试赋予执行引擎较强的设备控制能力，不再使用时可关闭无线调试并撤销配对。

需要自备模型服务账号和 API Key，请求可能计费。OCR 在本机执行，但任务、有限会话上下文、应用列表、相关界面文本和执行证据会发给所配置的模型接口；调用截图工具时还可能发送截图。服务商的数据处理规则同样适用。日志导出虽不含模型配置密钥或截图，仍可能包含私人任务、参数和结果，分享前请检查。本文件说明当前限制，不替代项目许可证。

## 日本語

Wukong は実験的な端末自動操作ソフトウェアです。モデルの誤認識、誤操作、誤った完了報告が起こり得ます。利用を許可された端末・アカウントでのみ使用し、失敗の影響を受け入れられないタスクは無人で実行しないでください。通常の操作や送信・共有は自律的に行われる場合があります。高リスク操作の確認はヒューリスティックで、すべての危険を検出する保証はありません。停止しても実行済み操作は取り消せません。

自分の API キーを使用し、プロバイダーの料金が発生する場合があります。OCR は端末内で実行しますが、関連するタスク・画面テキスト・会話の一部、および要求されたスクリーンショットは設定したモデルサービスへ送られます。ログ共有前に個人情報を確認してください。本書は制限事項の説明であり、ソフトウェアライセンスではありません。
