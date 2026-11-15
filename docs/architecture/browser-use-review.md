# Browser-use lessons for Wukong

Reviewed 2026-11-15 after completing/testing the event-assisted observation changes. The initial recommendations below distinguish research from implementation. Target queries/condition waits, fresh completion evidence, and compact on-demand observations have since been implemented: see [condition tools](condition-tools.md). Bounded embedded QuickJS workflows have also been implemented: see [JavaScript runtime](javascript-runtime.md). Durable checkpoints and external adapters remain planned.

## Sources and version

- [The supplied article: 浏览器操控的苦涩教训](https://www.liaocaoxuezhe.com/article/the-bitter-lesson-of-browser-use/). Its central argument favors programmable actions, persistent state and mature automation interfaces over an expanding fixed menu. Its benchmark/product claims were not independently reproduced here.
- [browser-use migration guide at e717df1](https://github.com/browser-use/browser-use/blob/e717df1839a2d7368b108de6694f01e9c659b0de/MIGRATING.md): 1.0 replaces the old per-action loop with a persistent Python REPL using browser-harness/CDP. Thus old 0.x architectural descriptions should not be treated as current implementation details.
- [Current agent prompt](https://github.com/browser-use/browser-use/blob/e717df1839a2d7368b108de6694f01e9c659b0de/browser_use/agent/prompt.py): observation is chosen for the task, mutations are checked against outcomes, and uncertain effects must be inspected before any replay.
- [Current loop](https://github.com/browser-use/browser-use/blob/e717df1839a2d7368b108de6694f01e9c659b0de/browser_use/agent/loop.py): persistent execution, context compaction, bounded execution and partial-result delivery.
- [Current judge](https://github.com/browser-use/browser-use/blob/e717df1839a2d7368b108de6694f01e9c659b0de/browser_use/agent/judge.py): a separate post-run request to the agent's own model checks the trace and recent distinct images; this is an evaluation signal, not deterministic proof.
- [Browser Use: The Bitter Lesson of Agent Harnesses](https://browser-use.com/posts/bitter-lesson-agent-harnesses) and [The Bitter Lesson of Browser Agents](https://browser-use.com/posts/bitter-lesson-browser-agents): the authors argue for less fixed observation/action abstraction and more direct programmable access.

No repository code was copied into Wukong. No benchmark improvement is claimed from reading these sources.

## Wukong-specific conclusions

The existing one-observation / one-model-call / one-action loop is an effective baseline but makes a repeated task require many model round trips. A fixed eight-turn memory and a single filtered node-list representation can also discard useful task progress or hide necessary information. Fixing a 650 ms wait improves local interaction but does not by itself address model-call latency or long-task memory.

Android has a different substrate: ordinary cross-app automation has no universal DOM or page JavaScript evaluator. UID 2000 app_process is not an equivalent of an arbitrary site's DOM and does not make every app's internal data available. The useful transfer is a programmable, observable phone interface, not copying raw CDP calls or treating shell commands as safe UI actions.

## Recommended next work

| Priority | Candidate | Wukong adaptation and acceptance check |
| --- | --- | --- |
| P0 | Target/condition waits | Provide find/query and wait_until for a target's presence, enabled state, text or package/window. Condition waits should avoid penalizing a page because an unrelated counter keeps changing. Test with late results, scrolling, IME, and persistent animation. |
| P0 | Completion verification | Require fresh result evidence before marking success. Check objective UI assertions where possible, and optionally use a separate evaluation request for ambiguous outcomes. Track false-complete rate, not only tool-call success. |
| P1 | Programmable short workflows | A persistent, bounded execution session can express loops and conditionals. Start with thin phone SDK calls routed through the existing execution validator, confirmation and takeover controls. Compare against the current loop on the same tasks/model. |
| P1 | On-demand observations | Let the caller request a subtree, matching text, selected attributes, OCR or an image when needed. Return truncation explicitly and allow narrowing rather than making the full fixed dump the only source of truth. |
| P1 | Progress/checkpoints | Retain verified findings, completed operations and the next objective separately from a short recent-action buffer. Resume after interruption without recreating successful effects. Avoid retaining credentials or reusable private task contents by default. |
| P2 | External-agent adapter | Expose the same phone SDK/session API through a skill/MCP adapter so Codex-like agents can supply planning; keep the Android App as the device/preview/control host. This remains planned, not shipped. |

An illustrative API shape (not an implemented interface):

```text
session.query(text="搜索", editable=true)
session.input(target, keyword)
session.wait_until(text_contains=keyword, timeout=...)
session.read_matching(...)
```

Every mutation inside a program would still resolve/check its target freshly. A session must carry its display, cancellation, operation budget and human-control epoch. Payment/deletion confirmation must stay in the trusted execution layer, which generated helpers cannot rewrite. Unknown/partially accepted outcomes cannot be retried merely because a program raised an exception.

## Deliberate boundaries

The upstream persistent Python environment is not presented as a sandbox by its migration guide. Running unrestricted generated code as Android shell would be a materially different authority model. Choose an execution isolation design before introducing code execution; do not expose arbitrary shell as a shortcut around current checks.

Do not copy browser-specific DOM/network-idle assumptions into generic Android apps. Keep node actions for ordinary native controls and OCR/visual fallback for content that lacks useful nodes. Pixel-only animation detection and stable selector/target rebinding still need separate implementation and tests.

Before expanding the action space, establish a repeatable task set (search, files, form entry, cross-app navigation, recovery), then compare completion accuracy, false completions, model rounds, elapsed time and tokens. The article's numerical claims are not a substitute for this phone-specific evaluation.
