# Wukong interface direction — approved proposal

Approved on 2026-11-15. The implemented shared system is recorded in [design.md](../../design.md).

Hallmark adaptation for native Android Views. Existing activities, task execution, and settings ownership are retained.

## Current implementation

- Native Android Views; no Compose or web stack (`app/build.gradle.kts`).
- Cobalt A cool white, navy and blue tokens already live in `app/src/main/res/values/colors.xml`.
- System sans-serif type, native focus and touch feedback.
- Task / History / Settings / Logs navigation lives in `MainActivity.kt`.
- Pairing setup, virtual display, overlay controls, and notifications have separate components.

## Proposed shared system

Modern-minimal, task-oriented workbench. Cool paper (`#F3F6FC`), navy ink (`#14213B`), cobalt (`#2457D6`) for primary actions, light blue (`#79A4FF`) in the geometric brand mark. The native resource palette remains the source of truth.

A compact wordmark header and persistent bottom navigation frame the workspace. The task composer comes first: goal, optional starting app, run control. Connection setup and display controls have clearly separated secondary placement. History uses chronological dividers; logs emphasize tool calls and measured usage; settings separate model, language, retention, and data permissions.

Use a 4 dp spacing scale, 48 dp minimum interactive targets, 12 dp input/button corners, and restrained borders. System sans-serif regular for body, medium/bold for headings; monospace only for raw logs. No decorative motion or gradients. Focus is immediately visible; disabled, running, failed, and approval states are explicit.

The geometric monkey mask and twin blue headband arcs are provided as editable SVGs. The Android version should use vector drawables, an adaptive launcher icon, and a separate monochrome notification glyph.

## Implementation files

Modify `MainActivity.kt`, `WirelessSetupActivity.kt`, `VirtualScreenPanel.kt`, `VirtualHomeActivity.kt`, notification components, `AndroidManifest.xml`, and native resource files. Add shared UI helpers, localization resources, launcher assets, brand exports, and real-device screenshots. No production files need deletion.

## Language scope

Follow the system by default, with English as fallback and manual English / Simplified Chinese choices in Settings. Three project introductions: English, Simplified Chinese, Japanese. User tasks, provider responses, and raw engine diagnostics retain their original language. UI language must not overwrite model settings, logs, or task history.

## Verification

Build, JVM checks and lint; physical Pixel 6 navigation, English and Chinese rendering, language persistence, and retained model settings. Inspect layouts at 320 / 375 / 414 / 768 dp and increased font size. Exercise virtual preview and manual takeover once after styling; preserve existing sensitive-action approval semantics.
