# Task-linked history and soft interface

<!-- Hallmark pre-emit critique: Philosophy 4, Hierarchy 4, Execution 4, Specificity 4, Restraint 5, Variety 4. -->

Selected direction A uses cream `#F7F5EF`, moss `#425743` and pale apricot `#F0D9C4`. Native resources, the in-app mark, adaptive icon and public brand exports share these roles. Text/paper contrast is 10.99:1, muted text/paper 4.88:1, primary text/button 7.85:1 and input outline/surface 3.32:1.

## Changes

- Model name is visible and editable before opening Advanced; provider selection still prefills it. Cancelled edits preserve saved credentials.
- Starting app and the virtual-display switch share a compact options card outside the task composer. Switches are horizontally aligned, with a 48 dp touch target.
- Three primary destinations: Tasks, History and Settings. History uses status cards and opens a full task detail page.
- Task details combine results, reported token totals and expandable event groups. Headers stay fixed, with top-left Back and top-right overflow actions. Finished runs do not need a refresh action.
- History's top-right menu clears logs or history; task detail can remove its own log. Confirmation is required. Removing a trace preserves its task result.
- Model, device, preferences and data controls occupy separate setting cards, with dividers between related rows.
- Connection-test diagnostics remain under Settings and exclude task traces.

## Data and compatibility

New history entries retain the exact trace filename and an explicit association-known flag. Logging-off tasks cannot accidentally adopt a different, similarly timed run. Missing or evicted traces show an unavailable state. Legacy entries match only one task trace with the same goal, terminal phase and finish time within five seconds; ambiguous matches remain unlinked. Rewriting history preserves these legacy semantics. Existing goals, results and timestamps are retained.

## Checks

- 26 JVM tests passed (23 core and 3 model settings).
- 14 physical Pixel 6 tests passed: exact/legacy log association, retention/redaction, task detail navigation, cancellation of clear-log confirmation, language/draft persistence, visible model editing, provider request compatibility, localized previews, and real-model virtual-display takeover/handoff. The real task stored an exact history-to-log association.
- Final fixed-toolbar checks reran history/back/menu cancellation, navigation/language and localized previews (5 checks). A separate Back-key regression covers the legacy callback and top-left action; physical ADB Back returned to History after the compatibility fix.
- English/Chinese home/model layouts checked at 320, 375, 414 and 768 dp. Public model preview images clear the key field without saving changes.
- 51 ADB screenshots cover home, options, history, task details, expanded events, settings, diagnostics, model configuration, connection guide, example dialog and embedded/manual/floating virtual display. Private review artifacts are under `.tooling/task-flow-review/`.
- Debug/release builds and app/shell lint passed. Production source keeps the current version number; this work does not rewrite an existing release tag.

Screenshots document the tested Pixel 6. Other Android/vendor behaviors and app-specific virtual-display restrictions still need broader coverage.

## Interaction polish and single-run export

- Selected spinner values align to the trailing edge; expanded dropdown items retain leading alignment. Setup/connection back actions use a 48 dp arrow-only button with an accessible label.
- A successful model test saves settings and stays on the model screen. A visible success message confirms it; edits invalidate the result. First-time setup requires an explicit Continue action after testing. Returning to Settings updates the model summary.
- Dialogs share a cream surface, 24 dp corners, a distinct title, muted body text, consistent spacing and 48 dp actions.
- Task and connection-run menus export one immutable ZIP snapshot containing manifest.json and events.jsonl. Export reads the complete bounded log beyond the 1,000-event UI preview. Known API keys and structured credential fields are redacted; arbitrary task text still needs user review.
- Save uses the system document picker; Share grants temporary read access through a narrow FileProvider cache path. Cached snapshots expire after seven days. Cancellation leaves source logs unchanged. Missing logs report an error. Pending save filenames survive activity recreation.

Validation: 12 initial physical Pixel 6 tests passed. After the final changes, 8 targeted tests passed, including a real provider test that remains on the configuration page, editing after success, an archive of 1,008 events, nested credential redaction, exact-run isolation, content-URI reading, missing-log behavior, canceling the export dialog, onboarding, history and bilingual width previews. An actual system document-picker save produced a readable ZIP with all 21 events of the selected task. Debug/release builds and lint passed.

The updated screen review passed with 53 fresh ADB captures, including both languages of the export dialog. The system share sheet opened with a readable ZIP attachment and was dismissed without selecting a recipient. Signed release verification, ZIP alignment and all six arm64 native-library 16 KB checks passed.

## Dropdown appearance

Shared native spinners now use the app surface token, a 20 dp rounded popup with a subtle border and 6 dp elevation, and inset 12 dp rounded rows. The current choice has a soft moss fill and a check mark; pressed, keyboard focus and hover use the existing feedback colors. Expanded labels remain leading-aligned and collapsed values trailing-aligned. Long labels ellipsize within the native popup bounds. Styling is scoped to spinner popups.

Debug build and lint passed. Physical Pixel 6 screenshots checked language menus at 320 / 375 / 414 / 768 dp and the provider menu at the original device width. The temporary display-size override was reset. Review captures are in the ignored .tooling/dropdown-*-expanded.png and .tooling/dropdown-styled-provider.png files.

## Model settings and discovery

Model configuration now has a fixed compact header, a connection card (provider, base URL and key), a model card with an editable dropdown, and disclosed advanced controls. Clear configuration lives in this page overflow menu with confirmation; Settings no longer duplicates it. A successful Test & save remains on the form. Both English and Chinese views were checked at 320, 375, 414 and 768 dp.

Discovery explicitly requests GET on the configured prefix plus /models, with Bearer authentication and redirects disabled. Standard complete chat URLs use the sibling models route; unknown custom complete URLs retain manual entry. Responses are capped at 2 MiB / 2,000 models, parsed as data[].id, deduplicated, and sorted. Changing provider, key, endpoint or exact-URL mode cancels and invalidates an older request. Leaving the page cancels discovery. Empty, malformed, unauthorized, unavailable and network responses leave the entered model available. Model selection and refresh do not save a configuration.

Validation includes 27 JVM tests; 12 physical Pixel 6 regression tests covering real DeepSeek model listing, the editable dropdown, prefix handling, HTTP/auth/redirect failures, empty and oversized responses, stale-response rejection, manual entry, clearing cancellation, onboarding, exports, model test/save and bilingual width previews. Selecting an actual dropdown row was also checked through its native list click handler. A 57-screen review covers both languages of the actual model list, clear confirmation and advanced form alongside the existing pages. Final popup-only styling uses the same 6 dp elevation as the other dropdowns. Release signing, ZIP alignment and all six arm64 library 16 KB checks passed.

## Keyboard and fixed footers

Main and setup windows use adjustNothing. Footers keep their original position and remain visible in the view hierarchy while the IME naturally covers them. System-bar padding stays stable; only the scroll region gains the covered-area inset and brings the focused cursor into view. No keyboard-dependent footer visibility changes are used.

A physical Pixel 6 test exercised the real keyboard on the task field and model input, asserted identical footer bounds before and during text entry, checked that the focused input remains above the keyboard, and preserved draft/model values. Four actual screenshots cover both open and closed states under .tooling/keyboard-review/. Debug/release builds and lint passed. Localized width previews refreshed the public model screenshots, including leading-aligned selected model IDs.

## Event-assisted observation and replanning

The fixed post-action 650 ms sleep is replaced by UI-event-assisted snapshot settling. See [the implementation and thread inventory](architecture/observation-and-threads.md) for timing policy, compatibility and observed test results. Changes before injection now enter model history as unexecuted tool results; unknown effects remain non-retriable. A changed approval is discarded and requires a new decision/confirmation.

[Browser-use and article review](architecture/browser-use-review.md) records recommendations separately from delivered features.

## Target conditions and completion evidence (2026-11-15)

Implemented `phone_query`, `phone_wait_until`, and `phone_verify`, compact/focused observations and paginated source queries. Successful finish checks fresh conditions or uses a separate screenshot/OCR review; incomplete tasks no longer receive COMPLETED. Failed finishes return evidence to the model without replaying device effects. Details, limits and current validation are in [condition tools](architecture/condition-tools.md).

## Programmable phone workflows (2026-11-15)

Added `phone_run_js` with the existing selectors/waits/actions and cooperative change listeners, executed in an isolated Android service. After comparing a local V8 build, the shipped implementation uses source-built QuickJS: arm64 production APK 27.45 MiB, versus 86.57 MiB with V8 and 26.82 MiB before scripts. The same trusted approvals, exact snapshot checks, cancellation and completion evidence remain outside JS. Runtime contracts, native limits, size measurements and device validation are in [JavaScript runtime](architecture/javascript-runtime.md).

## 纯工具与编程调用对比（2026-11-15）

在 Pixel 6 上，用相同 deepseek-flash 配置，分别强制纯工具与纯 JS 操作，测试批量查询回填、查询失败后局部重试、跨页表单三类流程。每种任务每种模式两次，配对交替顺序，共 12 次，均通过独立页面 oracle 与摘要核对。JS 平均规划轮数减少 55%、总 token 减少 62.4%，平均耗时仅减少 9.3%；存在 3 次被拦截并恢复的脚本错误与明显无效等待。样本小且使用受控原生控件，不能外推到所有 App。完整方法、原始用量与日志见 [评估结论](../benchmarks/execution/results/2026-11-15/analysis.md) 和 [复现说明](../benchmarks/execution/README.md)。App 默认仍为 AUTO，不新增用户设置项。
