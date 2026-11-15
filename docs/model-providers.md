# Model provider presets

Verified against official documentation on 2026-11-15. Presets use standard API billing, not Coding Plan or other subscription endpoints. All addresses and model IDs remain editable under Advanced configuration.

| Provider | Default model | Base URL | API platform |
| --- | --- | --- | --- |
| DeepSeek | `deepseek-flash` | `https://api.deepseek.com` | [API Keys](https://platform.deepseek.com/api_keys) |
| Alibaba Cloud / Qwen | `qwen-plus` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | [Beijing API Keys](https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key) |
| Zhipu / GLM | `glm-5.3-flash` | `https://open.bigmodel.cn/api/paas/v4` | [API Keys](https://bigmodel.cn/usercenter/proj-mgmt/apikeys) |
| Moonshot / Kimi | `kimi-k3.1-pro` | `https://api.moonshot.cn/v1` | [API Keys](https://platform.kimi.com/console/api-keys) |
| Custom | User supplied | User supplied | No external link |

Switching providers clears the key field. Keys are never appended to platform links. Following a link does not save or test a configuration. A candidate configuration is saved only after a successful tool-call connection test; cancellation and failed tests preserve the saved configuration. Logs use the existing secret-redaction rules.

## Request differences

- DeepSeek uses `thinking.type`; thinking is off by default. [Official models and API](https://api-docs.deepseek.com/quick_start/pricing/).
- Qwen uses `enable_thinking`, not DeepSeek's `thinking` object. The Beijing preset supports standard Beijing keys; other regions and subscription endpoints need their matching configuration. [Base URLs](https://help.aliyun.com/zh/model-studio/base-url), [thinking modes](https://help.aliyun.com/zh/model-studio/deep-thinking), [tool calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling).
- GLM-5.3 always thinks. The UI explains this and disables the unsupported off switch. Older editable model IDs retain their applicable switch behavior. [Thinking modes](https://docs.bigmodel.cn/cn/guide/capabilities/thinking-mode), [GLM-5.3 Flash](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash.md), [base URL](https://docs.bigmodel.cn/cn/guide/develop/openai/introduction).
- Kimi K3.1 Pro always thinks and uses `reasoning_effort`. Wukong uses `low` for interactive device tasks and does not send an unsupported off parameter. Full assistant messages, including reasoning content, remain in the tool history. [Quick start and parameter constraints](https://platform.kimi.com/docs/guide/kimi-k3-1-pro-quickstart.md), [current model list](https://platform.kimi.com/docs/models.md).

Thinking-on requests retain automatic tool choice; thinking-off requests require a provided tool. Wukong still validates exactly one recognized tool call before execution. New custom standard endpoints omit provider-specific thinking parameters when thinking is off. Existing configurations without provider metadata retain their previous behavior; recognized official hosts gain the appropriate provider adapter.

Only DeepSeek credentials are available on the connected device. The other presets are checked against official documentation and covered by request-shape tests; successful authenticated calls to those providers are not claimed.
