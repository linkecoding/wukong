# Design — Wukong

<!-- Hallmark · modern-minimal · Workbench · native Android adaptation -->

A shared design system for the Android app and project identity, approved on 2026-11-15. Extend these tokens when the app grows.

## Genre and structure

Modern-minimal. App pages use a task workbench: compact identity header, a conversation transcript and an IME-aware bottom composer. The native AndroidX DrawerLayout / Material NavigationView holds new conversations, recent conversations, History and Settings. Secondary destinations (History, Settings, task details and diagnostics) are independent Android Activities; Back/Up pops the framework stack and preserves the home conversation and draft. User requests, measured task status, optional live display preview and results appear in the conversation. Examples and secondary settings are flat icon/text rows; explanations are disclosed when needed. Task composition comes before display options; history uses status cards; task details combine results, measured token usage and expandable step groups. Settings has separate model, device, preferences and data cards. Model configuration uses a fixed compact header with a clear action in its overflow menu, a connection card and a model-selection card; advanced controls are disclosed. Connection tests remain in Settings diagnostics. Introduction assets use a short typographic statement with the actual logo. Do not invent statistics or endorsements.

## Theme

Android's `app/src/main/res/values/colors.xml` is the runtime source of truth. `tokens.css` is a portable web export, not an app dependency.

| Role | Token | Android value |
| --- | --- | --- |
| Background | paper | `#F7F5EF` |
| Card surface | surface | `#FFFDFC` |
| Text | ink | `#30392F` |
| Secondary text | muted | `#646E62` |
| Primary action | accent | `#425743` |
| Text on primary | accent_ink | `#FFFFFF` |
| Divider | line | `#E4E5DC` |
| Control outline | control | `#82907D` |
| Apricot accent | soft_accent | `#F0D9C4` |
| Completed state | success | `#3E5D3C` |
| Moss state surface | soft_success | `#E5EBDD` |
| Error | danger | `#A23F36` |

Approved refinement: cream, moss and pale apricot. Use light surfaces and spacing to group content. Accent color supports primary actions, status and the mark, with restrained saturation.

## Typography

System sans-serif: regular body, medium buttons, bold headings, upright throughout. Display 26 sp, page title 26 sp, nested title 19 sp, section title 18 sp, body 14–16 sp, helper text 12–13 sp, navigation 11 sp. Raw log JSON alone uses monospace. Headings may wrap; action labels remain one line. Use short localized labels rather than reducing text to unreadable sizes.

## Spacing and shape

4 dp scale. Page inset 24 dp, form inset 16 dp, related spacing 8–12 dp, section spacing 24 dp. Content is centered and capped at 680 dp. Inputs and buttons have 12 dp corners; composer panels 16 dp and grouped cards 18 dp. Dropdown panels use 20 dp corners, a surface fill, subtle line border, 6 dp inset and light elevation. Options stay leading-aligned with 12 dp rounded rows; the selected option has a soft moss fill and a check mark. Collapsed spinner values remain trailing-aligned; the editable model-name picker uses leading alignment for both typed and selected IDs. Minimum touch target 48 dp. Navigation target 64 dp with a consistent outline icon set.

## Interaction

Existing setup-page footers stay fixed at the physical viewport bottom. The conversation has no bottom navigation; its text composer avoids the IME via native window insets. Footer visibility is never inferred by keyboard-height heuristics. Native ripple feedback and immediate focus indication. No decorative motion; no custom animation dependent on motion preferences. Disabled actions have native disabled state plus distinct text and fill. Task phases, failure, manual takeover, and approval are expressed in text as well as color. The stop action appears during a task. Only financial/destructive actions require approval, following the user-selected policy. Execution options use a native Material BottomSheetDialog with phone/virtual display radio choices, current readiness and an optional starting-app picker. Floating previews use compact status, a live viewport and take-over/return/stop controls; manual back/home/text inputs appear only during takeover. Closing the preview leaves the task running.

## Language and voice

Follow the system on first launch: Chinese uses Simplified Chinese; other languages fall back to English. Settings offers Follow system, English, and Simplified Chinese. Existing explicit choices remain saved. Language changes require an idle task, pairing flow, and virtual display, so running controls do not mix languages. User content, model replies, and raw engine diagnostics keep their source language. Default project README is English; Chinese and Japanese introductions link back to it.

Speak directly: Run task, Stop task, Take over, Resume agent. Explain what happens to the device and its data. Label incomplete integrations as planned and current builds as Preview.

## Brand

The moss geometric monkey mask and headband arcs on pale apricot are original editable vectors in `docs/brand/`. Use a separate monochrome glyph for Android notifications. Launcher foreground fits within the adaptive icon safe area. The public wordmark stays “Wukong”; Chinese prose may use “悟空”.

## Shared implementation

`WukongUi.kt` owns button, focus, ripple, and shape styling. `UiLanguage.kt` wraps activity and service contexts with the saved language. Colors and translated strings stay in Android resources. Existing routes and execution components retain ownership.

## Exports

See root `tokens.css` for OKLCH colors and portable spacing/type tokens. Native Android consumes its own color resources. Web-only Tailwind/shadcn configuration is intentionally not installed in this Android project.

## Onboarding extension

Task-first setup: practical examples ask for one useful detail, then return to an editable saved task. Preparation proceeds through model → phone → controls, one primary action per stage. Standard API presets expose the provider and key; custom endpoints, model IDs, and thinking controls remain available under Advanced. Vendor platforms open through explicit links. Never transfer a key between providers. Task options and manual connection ports are progressively disclosed. Existing authorized connections may recover automatically, but task execution always requires the user's Run action.
