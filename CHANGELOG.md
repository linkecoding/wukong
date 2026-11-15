# Changelog

Changes are grouped by source version. A section here does not mean its APK has been published; see [GitHub Releases](https://github.com/linkecoding/wukong/releases) for downloadable tags and publication dates.

## Unreleased

- Add a portable Wukong skill, dependency-free Python companion, and English/Chinese local setup page. External agents delegate complete tasks to the phone’s configured execution model.
- Add ADB-authorized task receipts, idempotent submission, virtual-screen preview, takeover/handoff, cancellation, and per-run log export. Existing phone approvals remain in force.
- Keep an already-awake phone screen on during active tasks and release the timed wake lock when execution ends.

## 0.0.2 — 2026-11-15

### Added

- Personal Agent conversations with task cards, execution previews, results and follow-up requests in one place.
- Native Android side drawer for conversations, History and Settings, with Activity back-stack navigation.
- Embedded QuickJS workflows under `wukong`: immutable selectors, named outcome waits, desired-state setters, UI-change listeners and row-context selectors.
- Task-linked execution details, provider-reported token usage and single-run ZIP export.
- Resizable, draggable floating virtual-display preview with remembered geometry, manual takeover and handoff, and a close control.
- Searchable starting-app picker inside the native execution-options sheet.
- User documentation, disclaimer, contribution/security reporting guidance and issue templates.

### Changed

- Cream, moss and pale-apricot interface. The Chinese app name is 悟空; English remains Wukong.
- Back, Home and Resize controls use equal-width columns during floating manual takeover.
- Routine actions run autonomously; local approval focuses on payments and destructive operations. Detection remains heuristic.
- Completion now combines fresh page conditions with semantic review, bounded native action/read evidence and a restricted check of explicit basic arithmetic equations.
- Android planner budget increased to 50 turns; existing script call/mutation limits remain.

### Fixed

- Empty virtual-display startup and manual Home navigation.
- Touch mapping across floating preview sizes and ignored letterbox regions.
- Fractional stopwatch changes unnecessarily rejecting otherwise unchanged ordinary controls.
- Label-to-row targeting and stale selector recovery after proven pre-injection rejection; accepted or unknown effects are not replayed.
- Lost intermediate readings and incorrect interpretation of app-generated stopwatch rows as separate button presses.

### Validation

Pixel 6 / Android 17 real-app tasks: calculator, file metadata, stopwatch and device information. Latest passes were obtained across aggregate and targeted reruns; model variability and limitations are documented in [the validation report](docs/real-world-validation.md). This is still an early preview.

## 0.0.1 — 2026-11-15

First public preview: built-in wireless ADB activation, cross-app phone tools, local PP-OCRv6 tiny OCR, main/virtual display execution, manual takeover, model-provider setup and optional local logs. Signed arm64/universal APKs and checksums published through GitHub Actions.

[Original release notes](docs/releases/0.0.1.md).
