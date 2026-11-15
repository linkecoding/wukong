# Auto.js API review against the 12-run benchmark

Reviewed 2026-11-15 against Wukong `1e1aa6f` and AutoJs6 documentation commit `f611dcd45e993ecbcb59a73d7e0f35415e73b6aa`. This document is a review/proposal, not a list of newly shipped APIs. No runtime behavior or original benchmark measurements are changed by this review.

The first implementation batch is documented in [JavaScript runtime](javascript-runtime.md): explicit builders, named waits, read-only action readiness, desired-state setters and structured native failures. The code sketches below describe the original proposal. Local text-change/stability helpers, list traversal, event wakeups and context deduplication remain follow-ups.

## Evidence and priorities

All 12 synthetic native-UI tasks completed correctly. That does not mean the current SDK is easy for a model to use. Across six JS runs / 30 cells, the trace has 217 attempted host calls, 214 completed calls, 90 queries, 51 sleeps (24,151 ms total), and one 8,013 ms condition-wait timeout. There were two selector misses, one disabled-control rejection and one stale snapshot refusal in the tools group. See [derived audit](../../benchmarks/execution/results/2026-11-15/sdk-audit.json) and [original traces](../../benchmarks/execution/results/2026-11-15/traces).

The entire 24 seconds of sleep is not waste: some waits cover real loading. The previously audited final-cell polling mistakes are the stronger evidence for avoidable latency. The model waited for a product word to disappear even though a successful summary legitimately retained that word. The SDK cannot infer a user's success criterion automatically; it should make correct waiting easier to express and diagnose.

| Priority | General problem | Evidence / benefit | Proposed direction |
| --- | --- | --- | --- |
| P0 | String selectors hide meaning | Two model programs used CSS-like/full-ID strings where current strings mean exact text | Explicit `wukong.id(...)`, `text(...)`, `textContains(...)` builders; continue accepting documented object selectors. Reject unknown keys/options early with useful hints. |
| P0 | Waiting requires too much manual interpretation | 51 sleeps; final polling loops around 10 s; a success-only wait ignored an error branch until timeout | Named `waitAny` outcomes; presence/gone/enabled/text-changed helpers; clear finite timeouts; return matched branch plus current evidence. |
| P0 | An action on a temporarily unavailable target terminates a cell | One disabled-control rejection; callers repeatedly implement wait-then-act | Bounded read-only readiness waits inside action options, re-resolve uniquely, inject once and optionally verify afterward. |
| P0/P1 | Failures are strings rather than a usable contract | Failed calls need new model turns and may follow already accepted effects | Structured error/result codes, failed method/selector, elapsed/attempts, accepted/unknown-effect status and completed-operation summaries. Expected absence stays data. |
| P1 | Text labels and editable values have mixed semantics | Source review: shell joins text/description/hint into the one `text` field | Separate raw text/value, description, hint and readable label; preserve protected-input redaction and define migration semantics. |
| P1 | Read payloads/history are unnecessarily rich | Every node serializes all fields; tool-result text also appears in recentActions | Field projection / values-only reads, compact returns, one full copy of each tool result and short progress summaries. Measure token effect rather than promise savings. |
| P1 | Future real lists need relations and bounded traversal | Current tests have no lazy lists; labels are often children of clickable rows | Explicit scoped/parent/child queries, controlled ancestor targeting, scroll-until/collection limits and caller-specified dedupe identity. Add tests beyond the existing 12 runs. |

## What the AutoJs6 docs contribute

Pinned primary sources:

- [UiSelector](https://github.com/SuperMonster003/AutoJs6-Documentation/blob/f611dcd45e993ecbcb59a73d7e0f35415e73b6aa/api/uiSelectorType.md): explicit id/text/state filters, chainable selectors and bounded find operations. Its documented findOne selects the first match, and some overloads permit unlimited waits. Wukong should retain strict unique-target resolution and finite deadlines.
- [Global waits](https://github.com/SuperMonster003/AutoJs6-Documentation/blob/f611dcd45e993ecbcb59a73d7e0f35415e73b6aa/api/global.md): selector/predicate waiting and combined wait/action helpers. Wukong should use one unambiguous millisecond timeout convention instead of adopting overload-specific time/count meanings.
- [Automator](https://github.com/SuperMonster003/AutoJs6-Documentation/blob/f611dcd45e993ecbcb59a73d7e0f35415e73b6aa/api/automator.md): higher-level target resolution, alternative-outcome lookup, text/state setting, bounded list traversal and event-assisted observation. These patterns can wrap Wukong's existing shell driver.
- [UiObject](https://github.com/SuperMonster003/AutoJs6-Documentation/blob/f611dcd45e993ecbcb59a73d7e0f35415e73b6aa/api/uiObjectType.md): parent/child navigation, separate text/description, refresh/stale state, local fingerprints and property-limited subtree export.
- [FlowError](https://github.com/SuperMonster003/AutoJs6-Documentation/blob/f611dcd45e993ecbcb59a73d7e0f35415e73b6aa/api/flowErrorType.md): machine-readable failure categories and step/target/timing context. Wukong should add its own effect-acceptance semantics.

These are current documentation contracts with runtime/version support differences, not evidence that every method is present in an installed Auto.js version. No upstream implementation is copied; Wukong retains QuickJS plus its existing UID 2000 engine.

## First implementation batch: selectors and waits

Proposed API sketch, **not currently available**:

```js
const next = wukong.id('example.app:id/next').enabled(true);
wukong.tap(next, { timeoutMs: 3000 });

const outcome = wukong.waitAny({
  nextPage: wukong.id('example.app:id/next_page'),
  error: wukong.id('example.app:id/error')
}, { timeoutMs: 5000 });
return { branch: outcome.key, evidence: outcome.matches };
```

The proposal intentionally supplies no business-specific search/count logic. It applies to opening pages, submitting forms, loading lists and recoverable errors.

1. **Explicit selectors.** Builders return serializable selector plans, not retained device handles. Keep string-as-text behavior explicit rather than guessing whether arbitrary strings are text, IDs or CSS. If short IDs are supported, give them explicit semantics and preserve ambiguity detection. A target's scope/display stays attached to the task. Unknown option names such as `timeuotMs` or `recourceId` must not silently broaden a selector or use defaults.
2. **Named outcomes.** Existing waitUntil already supports ANY/ALL conditions, but callers have to inspect a nested report and often reconstruct their own string polling. A named result makes success/error/timeout a clear branch. Preserve `unknown` for incomplete observations. Check all alternatives against one fresh snapshot and do not auto-click the first matching alternative.
3. **Condition-specific waiting.** Add helpers for enabled, absent, changed text and local target stability. Change/stability alone is not business success: a loading label can change before a result arrives. A target-local stable check should not wait on unrelated clocks, carousels or other windows.
4. **Function predicates, carefully staged.** The first implementation can cover common waits with structured selectors. A later read-only predicate form would match model/Auto.js expectations; it should run on the JS worker with a bounded deadline and a defined return type. It must not repeat mutations inside a polling callback. Until supported, reject function arguments explicitly before JSON.stringify can silently drop them. A function-style wait appeared in generated code, but an earlier selector error prevented it from being executed in this benchmark; this is a source-review finding, not a measured extra failure.

Acceptance checks: text literally resembling an ID; full and short IDs; duplicates; misspelled keys/options; delayed enablement; success/error racing; no signal events; unknown/incomplete source; continuous unrelated animation; cancellation/handoff at each wait boundary. Perform one accepted input at most; test that an error outcome returns promptly rather than consuming the success timeout.

## Stateful operations and reliable reads

Borrow the intent of AutoJs6's text-entry and toggle helpers:

- Prefer `setText`/`setChecked` semantics for desired state. If the actual value/state is already correct, return a no-op. Otherwise perform one mutation and verify the new value. This helps after interruption and avoids accidentally toggling a checkbox twice.
- `input` currently replaces all text. Its meaning should remain explicit because similarly named APIs elsewhere can append. Use raw editable value for comparison; the current joined label is not a reliable value.
- A read can offer selected properties, `.text()`/`.value()`/`.exists()` or a compact data result instead of a whole node page. Keep snapshot/truncation context when absence or later target resolution depends on it. Reading text is not automatically proof the requested task completed.
- For a label inside a clickable row, an explicit limited ancestor resolution may be useful. Resolve before injection, validate the actual target and keep approval on that target. The documented upstream strategy of trying more input methods after failure should not become an automatic replay of an effect with unknown outcome.

The raw text/value split is an additional source finding; the 12 runs did not show an incorrect final extracted count. Compatibility tests should cover nonempty inputs with hints, icon-only descriptions, duplicate labels and protected inputs before changing selector semantics.

## Errors and progress

Proposed native outcome categories include `INVALID_ARGUMENT`, `NOT_FOUND`, `AMBIGUOUS_TARGET`, `NOT_READY`, `STALE_SNAPSHOT`, `TIMEOUT`, `CANCELLED`, and `OUTCOME_UNKNOWN`. Include method, selector, failing step, elapsed time, attempts and `accepted=false|true|unknown` as appropriate. An accepted input is not the same as a verified business effect.

Use ordinary return values for expected absence (`exists`, optional lookup) and explicit outcome branches for wait timeouts. Preserve cell termination for ambiguous action failures and human takeover. Do not make every host error catchable just to keep a script running; prior accepted operations must remain visible to the planner. Arbitrary retry of a payment/send/delete operation should not be inherited from a generic retry(fn) design. Reads can retry within a budget; mutations need explicit evidence that no input was accepted, or desired-state reconciliation with fresh evidence.

No benchmark cell referenced `state`. This shows the current model did not use explicit state here, not that state persistence is broken or that preserving all variables would improve results. Progress/checkpoints are a later candidate: retain verified facts and completed work separately from transient node IDs; do not resume an old script or reuse an approval after human takeover.

## General list and event support

For new tasks, borrow bounded scrollUntil/collectList patterns: scope to an explicit container, set limits on time/steps/items, detect no progress, and report truncation/budget exhaustion. Caller-specified stable keys are preferable to dropping all equal text; duplicate names or reused RecyclerView IDs do not establish item identity. A loading page is not automatically the end of a list.

Current UiChanges records per-window revisions/time only. A future event-assisted wait can wake on relevant changes and retain periodic fallback reads; events are hints to refresh evidence, not proof of success. Blocking event waits must release the device-operation lock so manual takeover and cancellation remain responsive. This optimization is less urgent than fixing incorrect wait semantics: completed query calls total about 5.5 seconds here, while sleep alone totals 24.2 seconds.

Full asynchronous Flow, background popup guards, arbitrary shell/Java/file/network APIs and a complete Auto.js compatibility layer are not required to address the observed errors. The first batch can remain synchronous and keep the current small QuickJS backend.

## Model context and documentation

Source review found complete results in both tool-role messages and recentActions. Replace the latter with a compact status/progress summary and keep full evidence once, preserving valid tool-call/result pairing and replan feedback. This is a plausible token optimization; no new savings have been measured.

The SDK implementation, tool-description text and docs are separately maintained. A small shared API descriptor should generate concise model help, declarations and documentation. Include short examples for ID versus text, page.nodes versus values, named outcome branches and partial execution. Do not bundle a TypeScript compiler or a large new runtime merely to obtain declarations. Evaluate on-demand help only when the API has grown enough to justify an extra model round.

## How to validate the changes

Keep the original 12-run report and traces immutable. After implementing the first batch, repeat the same paired ordering with new report/trace names and source hashes. Compare success, duplicate accepted actions, script errors, condition timeouts, sleep/query counts, latency, planning turns, input/output/cache tokens and payload sizes. Do not treat removing all sleeps or passing these fixtures as universal improvement.

Add transfer tests with different labels/IDs, duplicate targets, errors arriving before success, delayed controls, label/parent relationships and an actual virtualized list. Business-specific helpers or hardcoded known counts would not address the general issues identified here.
