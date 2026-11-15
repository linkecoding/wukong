> Concept A (Cobalt) was selected and applied to the Android app. B and C remain explorations.

# Wukong — modern identity directions

Three reviewable proposals. **No installed Android UI, launcher icon, production palette, or approved design system has been replaced.** The UI images are HTML color concepts based on the current task and model flows, not screenshots of a themed APK. Every option still requires the user's own API key.

- [Logo comparison](logo-comparison.png) · [editable SVG board](logo-comparison.svg)
- [Home comparison](comparison-home.png)
- [Model setup comparison](comparison-model.png)
- [Interactive comparison](index.html): switch between the two pages. Open the file in a browser, or use the local preview at `http://127.0.0.1:8764/` while the preview server is running.

| Choice | Identity | Color direction | Best fit |
| --- | --- | --- | --- |
| A · 灵眸蓝白 / Cobalt | Twin headband arcs and a geometric watchful mask | Cool near-white, navy ink, cobalt blue | A clear everyday product with a recognizable Wukong reference |
| B · 玄甲深色 / Ion | An angular headband above a continuous W | Deep graphite, pale text, mint green | A focused agent tool with a strong dark appearance |
| C · 筋斗黑橙 / Signal | Open headband orbit around a W, ending in a directional marker | Cool white, graphite, signal orange | A crisp, energetic identity with motion implied in the symbol |

A is the suggested starting point for a broad consumer audience. The mark and palette can be selected separately; A's mark could also be adapted to a dark mode after selection.

## Editable assets

Each directory (`a/`, `b/`, `c/`) contains:

- `logo.svg` and `logo.png`: full-color icon proposal.
- `symbol.svg`: single-color mark, for testing small and monochrome applications.
- `ic_wukong.xml`: native Android VectorDrawable draft.
- `colors.xml` and `palette.json`: semantic palette draft and exact values.

These are proposal resources, not a complete drop-in Android theme. After selection, adaptive icons, notification glyphs, system bars, dialogs, native controls, error/disabled states, and documentation assets should be integrated and verified on the physical device.

## Verification

Rendered both full comparison pages in Chrome and visually inspected the logo board, desktop pages, and stacked mobile layout. Browser viewport checks used actual emulated CSS widths **320 / 375 / 414 / 768 / 1440**, with no horizontal overflow, clipped action labels, or missing logo images. The page-switch controller and its selected accessibility state were checked.

Contrast ratios against the candidate surfaces:

| Pair | A | B | C |
| --- | ---: | ---: | ---: |
| Main text / page | 14.80:1 | 15.80:1 | 14.24:1 |
| Secondary text / page | 5.66:1 | 8.32:1 | 5.72:1 |
| Primary button text / fill | 6.16:1 | 10.22:1 | 5.37:1 |
| Selected navigation text / fill | 5.30:1 | 7.67:1 | 4.57:1 |
| Input outline / field | 3.13:1 | 3.62:1 | 3.14:1 |

Local machine-readable checks are saved in ignored `.tooling/brand-option-renders/qa.json`. No application build or live-device theme test is claimed for these unselected proposals.
