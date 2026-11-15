# Design — Wukong

<!-- Hallmark · modern-minimal · Workbench · native Android adaptation -->

A shared design system for the Android app and project identity, approved on 2026-11-15. Extend these tokens when the app grows.

## Genre and structure

Modern-minimal. App pages use a task workbench: compact identity header, scrollable content, persistent bottom navigation. Task composition comes before display options; history is chronological; logs expose measured data; settings use a readable form. Introduction assets use a short typographic statement with the actual logo. Do not invent statistics or endorsements.

## Theme

Android's `app/src/main/res/values/colors.xml` is the runtime source of truth. `tokens.css` is a portable web export, not an app dependency.

| Role | Token | Android value |
| --- | --- | --- |
| Background | paper | `#F3F6FC` |
| Raised form | surface | `#FFFFFF` |
| Primary text | ink | `#14213B` |
| Secondary text | muted | `#52637B` |
| Primary action / focus | accent | `#2457D6` |
| Text on accent | accent_ink | `#FFFFFF` |
| Borders | line | `#CDD7E7` |
| Selected navigation | soft_accent | `#E7EEFF` |
| Connected state | success | `#226747` |
| Connected surface | soft_success | `#E6F2EB` |
| Error | danger | `#A82A2A` |

The light blue geometric eye and twin headband arcs belong to the Cobalt logo (selected concept A). Input outlines use control `#8193AE`. Keep functional accent areas small. No decorative gradients, fake phone frames, or ornamental dashboard metrics.

## Typography

System sans-serif: regular body, medium buttons, bold headings, upright throughout. Display 32 sp, page title 28 sp, section title 18 sp, body 14–16 sp, helper text 12–13 sp, navigation 11 sp. Raw log JSON alone uses monospace. Headings may wrap; action labels remain one line. Use short localized labels rather than reducing text to unreadable sizes.

## Spacing and shape

4 dp scale. Page inset 24 dp, form inset 16 dp, related spacing 8–12 dp, section spacing 24 dp. Content is centered and capped at 680 dp. Inputs and buttons have 12 dp corners; panels 16 dp. Minimum touch target 48 dp. Navigation target 64 dp with a consistent outline icon set.

## Interaction

Native ripple feedback and immediate focus indication. No decorative motion; no custom animation dependent on motion preferences. Disabled actions have native disabled state plus distinct text and fill. Task phases, failure, manual takeover, and approval are expressed in text as well as color. The stop action appears during a task. Sensitive approval semantics remain unchanged.

## Language and voice

Follow the system on first launch: Chinese uses Simplified Chinese; other languages fall back to English. Settings offers Follow system, English, and Simplified Chinese. Existing explicit choices remain saved. Language changes require an idle task, pairing flow, and virtual display, so running controls do not mix languages. User content, model replies, and raw engine diagnostics keep their source language. Default project README is English; Chinese and Japanese introductions link back to it.

Speak directly: Run task, Stop task, Take over, Resume agent. Explain what happens to the device and its data. Label incomplete integrations as planned and current builds as Preview.

## Brand

The geometric monkey mask and twin blue headband arcs are original editable vectors in `docs/brand/`. Use a separate monochrome glyph for Android notifications. Launcher foreground fits within the adaptive icon safe area. The public wordmark stays “Wukong”; Chinese prose may use “悟空”.

## Shared implementation

`WukongUi.kt` owns button, focus, ripple, and shape styling. `UiLanguage.kt` wraps activity and service contexts with the saved language. Colors and translated strings stay in Android resources. Existing routes and execution components retain ownership.

## Exports

See root `tokens.css` for OKLCH colors and portable spacing/type tokens. Native Android consumes its own color resources. Web-only Tailwind/shadcn configuration is intentionally not installed in this Android project.

## Onboarding extension

Task-first setup: practical examples ask for one useful detail, then return to an editable saved task. Preparation proceeds through model → phone → controls, one primary action per stage. Standard API presets expose the provider and key; custom endpoints, model IDs, and thinking controls remain available under Advanced. Vendor platforms open through explicit links. Never transfer a key between providers. Task options and manual connection ports are progressively disclosed. Existing authorized connections may recover automatically, but task execution always requires the user's Run action.
