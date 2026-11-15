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
