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
- Final fixed-toolbar checks rerun history/back/menu cancellation, navigation/language and localized previews.
- English/Chinese home/model layouts checked at 320, 375, 414 and 768 dp. Public model preview images clear the key field without saving changes.
- 51 ADB screenshots cover home, options, history, task details, expanded events, settings, diagnostics, model configuration, connection guide, example dialog and embedded/manual/floating virtual display. Private review artifacts are under `.tooling/task-flow-review/`.
- Debug/release builds and app/shell lint passed. Production source keeps the current version number; this work does not rewrite an existing release tag.

Screenshots document the tested Pixel 6. Other Android/vendor behaviors and app-specific virtual-display restrictions still need broader coverage.
