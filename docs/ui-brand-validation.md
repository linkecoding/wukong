# UI, identity, and language validation

2026-11-15. The redesign is local; no GitHub repository, upload, or release was created.

## Delivered

- Shared warm-paper / ink / vermilion design system, native task composer and bottom navigation.
- Consistent buttons, immediate keyboard focus, native touch feedback, disabled controls, and task status presentation.
- Original vector logo, adaptive launcher icon, monochrome notification glyph, wordmark, PNG exports, and 1280 × 640 social image.
- App follows the system language by default, with English as fallback and manual English / Simplified Chinese choices in Settings. Both languages are bundled, including in an app bundle.
- English, Simplified Chinese, and Japanese introductions. Japanese is documentation-only; it is not an app UI option.
- Existing task execution, sensitive-action approval, virtual display, OCR, model configuration, history, and logging remain in their existing components.

The shared specification is [design.md](../design.md). [Brand assets and introduction copy](brand/README.md) can be reused for an announcement.

## Build and checks

- Arm64 Debug APK and instrumentation APK: successful.
- Arm64 Release APK: successful, with existing R8 and resource shrinking enabled. The output is unsigned, not a published release.
- JVM tests: 20 core + 3 model settings tests, zero failures.
- App and shell lint: zero errors. Existing warnings remain; this is not a warning-free lint report.
- Release native alignment script: 6 arm64 libraries, zero failures. `zipalign -c -P 16 4` passed. This change does not replace native libraries; earlier 16 KB runtime verification is documented in [OCR native trimming](ocr-native-trimming.md).
- Relative links in all three READMEs and the brand kit checked for local targets.

## Pixel 6 / Android 17 checks

`BrandUiTest` — 3 tests passed:

- With no saved language, the app follows the phone language; manual English / Chinese choices override it, and selecting Follow system restores automatic resolution.
- Draft survives Task → History → Task navigation.
- Inactive navigation remains available and uses the normal surface after switching pages.
- Settings language selector recreates the activity in Chinese; the task draft survives, the choice persists, and encrypted model configuration remains unchanged. Original language restored afterwards.
- Native task layouts measured and rendered at 320 / 375 / 414 / 768 dp in English and Chinese. Visible action labels fit, and button touch targets remain at least 48 dp. These images are native View renders on the test device, not four separate emulators.

`VirtualControlTest` + `VisualToolsTest` — 3 tests passed:

- Actual configured-model task on a virtual display.
- Embedded → background floating → foreground embedded preview.
- Manual takeover pauses the agent; handoff observes again and continues the task.
- OCR-derived coordinate taps, long presses, swipes, screenshot output, and stale capture checks on the main and virtual displays.

Manual inspection:

- Task, History, Logs, Settings, and the on-device activation entry inspected on the physical phone.
- Task page visually checked at all four widths; physical system font scale 1.3 checked and original scale 1.0 restored.
- Readable text contrast: ink/paper 13.93:1; muted/paper 5.48:1; primary button 5.70:1; selected navigation 4.68:1; connected status 6.24:1.

## Scope and artifacts

The device build is the Debug APK. Release was built and statically inspected, not installed with a new signing identity. Broader device/vendor coverage is still needed. User tasks, model replies, historical results, and raw diagnostics retain their source language; known activation and validation statuses are translated at the presentation boundary without rewriting engine protocol messages.

Public images: [English task screen](images/home-en.png), [Chinese task render](images/home-zh-CN.png), [settings controls](images/settings-controls-en.png). Model-configuration, history, and log review captures remain in ignored local tooling storage and are not included in the public project assets.

Local build/test logs remain under ignored `.tooling/brand-*.log`.
