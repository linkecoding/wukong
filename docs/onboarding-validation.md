# Onboarding validation — 2026-11-15

Implemented locally and installed on Pixel 6 / Android 17. No GitHub upload or hosted trial deployment.

## Changes

- Practical task templates with a required product, location/preference, or trip detail.
- App-private draft persistence across model configuration, system settings, and app restarts; preparation never automatically runs a task.
- Guided model → phone → control permission → review flow. The primary preparation action stays visible at the bottom of the screen.
- Four domestic provider presets with explicit platform links, provider-specific request adapters, and editable advanced configuration. Switching providers clears the key field. Failed tests and cancellation preserve the saved model.
- Progressive disclosure of starting app, virtual display, and manual connection ports.
- Automatic recovery of an already-authorized engine when local wireless debugging is advertised. No pairing or task execution occurs automatically.
- Local action-validation failures are returned as failed tool results for re-observation and replanning, with at most three consecutive retries. No device action or approval occurs for these invalid calls. Unknown driver failures remain fatal; accepted/ambiguous actions are not replayed. Existing approval and protected-input checks remain intact.

## Checks passed

- Debug, instrumentation, and R8/resource-shrunk Release builds.
- App/shell lint: zero errors; existing warnings remain.
- 26 JVM tests: 23 core, including three new validation-recovery cases, and 3 model-settings cases.
- 9 on-device configuration, template, navigation, language, and preview checks (`ProviderCompatibilityTest`, `OnboardingFlowTest`, `BrandUiTest`, `OnboardingPreviewTest`).
- English and Chinese task/model pages measured at 320 / 375 / 414 / 768 dp, with action-label fit checks. Public images are native View renders on the physical test device, with the model key field cleared in the preview only.
- Actual configured DeepSeek connection/tool-call check passed.
- Stopped only the idle Wukong shell engine, then launched the app: authorized wireless recovery returned a UID 2000 connection without starting a task (`OnboardingRecoveryTest`).
- Existing real-model virtual-display foreground/background preview and manual takeover/handoff regression passed (`VirtualControlTest`).

## Research task result and limits

The first actual product-comparison run exposed a non-clickable-node selection that previously ended the task immediately. The bounded pre-injection validation feedback above was added and covered by unit tests. A subsequent real-model run returned a result normally and explicitly reported that Chrome could not connect to the internet. It distinguished cached/unverified price information and different product versions rather than claiming a completed real-time comparison. **Successful live price verification is not claimed.** The research test checks the agent lifecycle and returned result, not commercial-data accuracy or business-goal success.

Only the existing DeepSeek account was used for authenticated model requests. Qwen, GLM, and Kimi were validated against official documentation and request-shape tests; their authenticated calls require corresponding provider keys and model access. Their API platform buttons are normal explicit HTTPS links and never carry credentials.

The existing device was already paired. Recovery was tested; the redesigned fresh-pairing instructions wrap the existing notification pairing implementation. A fresh authorization flow was not forced by removing the user's pairing identity.

## Review

- [Task examples, English](images/onboarding/home-en.png) / [中文](images/onboarding/home-zh-CN.png)
- [Model setup, English](images/onboarding/model-en.png) / [中文](images/onboarding/model-zh-CN.png)
- [First-task guide](getting-started.md)
- [Provider presets and official sources](model-providers.md)

Private logs: `.tooling/onboarding-*.log`. Test model keys are dummy strings in UI-only tests; saved configuration comparisons avoid printing credential values. Original UI language, virtual-display preference, and saved model settings were preserved. Temporary example drafts are cleared before handing the phone back.
