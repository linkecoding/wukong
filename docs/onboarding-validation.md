# Onboarding validation — 2026-11-15

Implemented and installed on Pixel 6 / Android 17. Updated for 0.0.1; no hosted trial service is included.

## Changes

- Focused templates with a required filename/keyword, web question, or app/search term. The earlier comparison/travel templates were replaced because their login and real-time data dependencies make poor first tasks.
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


## Conversation UI and native navigation — 2026-11-15

Validated on the connected Pixel 6 with the existing saved model configuration. The UI now uses a persistent conversation, native AndroidX DrawerLayout + Material NavigationView, separate Activity destinations for History/Settings/task details/diagnostics, and a Material execution-options bottom sheet. The shared WorkspaceActivity renderer does not own a synthetic cross-page back stack.

- `ConversationFlowTest` verifies isolated/bounded stored summaries and drafts, 320/375/414/768 dp content/composer measurements, drawer destination ordering, native Back closing the drawer, separate resumed History/Settings Activities and return to the original home instance, native bottom-sheet location controls, phone/virtual switching without starting a task, and the real IME keeping Send above the keyboard.
- Multi-display keyboard validation waits for display readiness and actually touches the physical input field before requesting IME focus. A programmatic-focus-only attempt can otherwise leave focus on the newly launched virtual display.
- Two live-model conversation rounds passed: start a harmless Settings-opening task, expand its preview, pause for takeover, background Wukong, close the floating preview without stopping the task, return and hand back control, receive the result, then ask a follow-up. The follow-up trace contains no automatic home/relaunch/tap/input action. Task details push a separate Activity and return to the same two-turn conversation.
- Existing onboarding, single-run log export and ordinary/high-risk approval fixtures passed after the navigation changes. Native layouts and English/Chinese views were inspected from captured device renders. Final JVM suite: 57 tests passed; debug App/test builds and lintDebug passed.

Screenshots in `docs/images/conversation/` show actual native Android views and harmless test conversations. No signing material, model keys, personal app content or external messages are included. The test APK is not part of the release. Full arbitrary-App task reliability and general long-term personal memory are outside this UI validation.


## Floating preview polish — 2026-11-15

The Pixel 6 debug App and test APK built successfully, and lintDebug passed. Device renders of idle and active/manual states were inspected: the 208dp portrait preview retains the entire display image, a transparent close icon, compact status, and one prominent takeover/handoff action. These local renders include the user's installed-app list and are intentionally excluded from public documentation.

- `PreviewAndApprovalTest#everyPhysicalWukongPageHidesPreviewAndCloseOnlyDismissesTheWindow` passed: all physical Wukong pages hide the preview, Close retains the same virtual display, its hit target remains 48dp, header dragging does not navigate, and header tapping returns to Wukong.
- Live-model `VirtualControlTest#embeddedBackgroundFloatingTakeoverAndHandoffContinueRealAgent` passed: background preview, manual takeover, closing during the task, returning to the App, handing control back, and model completion preserve the running task and associated history.
