# Real-app virtual-display validation — 2026-11-15

Pixel 6 / Android 17, shell-UID built-in engine, virtual display, saved DeepSeek Flash configuration with thinking disabled. Tasks used the standard AUTO planner and installed apps, without an app allowlist. No payment, external messaging or file deletion was performed.

## Actual tasks

| Scenario | Independently checked result | Latest passing task duration |
| --- | --- | --- |
| Calculator: three items at 128, two at 59, pay 85% of original price, change from 500 | Native result 426.7 observed; final native value and summary 73.3 | About 44 s including test setup |
| Files: find the largest of three controlled files in Download/Wukong-QA-20261115 and inspect its information | 16,384-byte meeting file selected; correct path and metadata. Trace showed navigation and file-information actions, without opening content, editing, sharing or deleting | 34.6 s |
| Clock: start stopwatch, wait at least 3 s, record one lap, wait at least 2 s, pause, report and reset | Native accepted-action receipts proved one lap press and minimum intervals; raw displayed readings retained; final stopwatch zero/idle | 22.0 s in targeted strict rerun |
| Settings: report Android version, security patch and storage use | Native pages showed Android 17, 2026-07-05 patch, 34 GB used / 128 GB; no settings changed | 38.3 s |

These are latest passing results across an aggregate run and targeted reruns, not a claim of one final clean aggregate benchmark or universal reliability. The last aggregate run passed Files, Clock and Settings but exposed a false calculator completion. After the independent arithmetic guard was added, the targeted calculator task passed. Earlier repeated runs also exposed model navigation and summarization variability.

## Problems found and fixes

- Continuously ticking fractional stopwatch text invalidated otherwise unchanged controls. A narrowly scoped interaction fingerprint tolerates only those digit changes for ordinary actions. Sensitive actions, coordinate actions and captures retain exact checks.
- Script taps could match a label rather than its actionable row. Labels now resolve to their nearest clickable ancestor without climbing through scrolling containers; `nearText` disambiguates controls in a row.
- Known pre-injection stale errors were indistinguishable from uncertain execution failures. Typed rejection permits bounded fresh selector resolution only when no input was injected. Accepted/unknown effects are never replayed.
- Reading a later page lost intermediate results. A bounded task-local native ledger keeps readings and accepted-action receipts independently of optional disk logging. Raw native text is exposed consistently; one accepted lap press is distinguished from two app-generated segment rows.
- Matching a final-page condition could approve an incorrect summary. The App now also reviews goal/summary with current and historical evidence. A separate restricted arithmetic checker rejects inconsistent explicit basic equations before model review. Same-provider reviews can still make correlated errors; the arithmetic checker does not prove natural-language task correctness.

The Android planner budget is 50 turns. Existing script API/mutation limits remain unchanged.

## Interface and input regression

- Native execution sheet: phone/virtual choice cards, same-sheet searchable app list, selected indicator, automatic fallback for unavailable saved apps, no-results state and keyboard resizing.
- English/Chinese locale checks, native measurements at 320/375/414/768 dp, and six host-injected physical Back events verified IME → picker → options navigation. Two tests passed.
- Chinese app/header/floating name is 悟空. English remains Wukong.
- Manual floating footer uses three equal columns for Back, Home and Resize.
- Real overlay coordinate test passed after five live resizes: 25 taps, 20 swipes and 10 ignored letterbox taps. Touch mapping remained correct at each tested size.
- Existing real-model conversation continuation, embedded preview, takeover/handoff and close regression passed.
- 67 core JVM tests passed; debug App/test builds and App lint passed. The additional device regression suite passed 22 tests covering task evidence, dynamic targeting, geometry, model protocol and JavaScript behavior.

Calculator and Clock were temporarily enabled for these tests and restored to their original disabled-user state. The original stay-awake setting was restored. The controlled Download test folder is retained. Private device screenshots, installed-app inventories and task logs are not included in this public repository.
