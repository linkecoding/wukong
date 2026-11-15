# Target queries, condition waits and completion evidence

Implemented on 2026-11-15. These tools run through the existing task session on its selected physical/virtual display. They do not introduce a new worker, AccessibilityService, arbitrary shell access or generated-code execution.

## Model-facing tools

- `phone_query`: filter the visible tree by `textContains` (case insensitive), `textEquals` (case sensitive), `resourceId`, exact `className`, editable/clickable/scrollable/enabled/checked/selected state, `ancestorResourceId`, and `nearText`. Filters combine with AND. Empty selector lists nodes. `offset` / `limit` page through matches; limit defaults to 20, maximum 60. Ancestor filters let the model inspect a known container's descendants. `nearText` uses the nearest shared non-scrolling ancestor of a matching label to disambiguate row controls; it does not infer pixel proximity.
- `phone_wait_until`: repeatedly resolve one to five conditions against fresh snapshots, typically every 150 ms, without requiring the entire page to stop changing. Defaults to ANY condition; `matchAll=true` requires ALL. Timeout defaults to 5 seconds and is restricted to 200–10,000 ms. A condition can require a foreground package and/or matching nodes, with `state=present|absent` and optional `minCount`. An absence condition always means zero matches; it cannot request a count. A wildcard selector is not a valid wait/assertion.
- `phone_verify`: check one fresh snapshot against the same conditions. Defaults to ALL. Returns concrete matching nodes, package and fingerprint as evidence. It does not itself mark a task complete.

Example wait for results OR an error, without clicking anything:

```json
{
  "conditions": [
    {"selector": {"ancestorResourceId": "example.app:id/results", "textContains": "耳机"}, "packageName": "example.app"},
    {"selector": {"textContains": "网络错误"}, "packageName": "example.app"}
  ],
  "matchAll": false,
  "timeoutMs": 5000
}
```

Tool results distinguish `met`, `not_met`, and `unknown`, expose per-condition checks, and report elapsed time, samples and timeout. A timeout returns evidence to the model; it is not an ambiguous device-mutation failure and performs no input. Waiting on ANY may return because an error appeared, so the model must inspect which condition matched.

A read can exceed the polling interval or requested timeout due to the existing bounded Binder/socket recovery. The wait cannot interrupt an in-flight Android query. Cancellation/takeover is checked before/after each read and sleep. Human-control epoch changes invalidate an in-flight wait even if control was handed back before the next check.

## Observation and target lifetime

The shell returns resource/class IDs, visible parent relationships, enabled/checked/selected states and normalized bounds. The initial model view contains at most 60 nodes, prioritizing relevant query/condition matches and labeled/interactive controls. `overviewTruncated` means other nodes remain available through queries; it does not mean the underlying source is incomplete. `totalNodes`, `sourceTruncated`, package and fingerprint are explicit.

Queries use the larger bounded source, not the shortened model overview. Source traversal is limited to 1,500 nodes / depth 40; text retains its existing 300-character limit. A structurally incomplete traversal is exposed for diagnosis and blocks capture/mutation, preserving the prior fail-closed behavior on excessively complex pages. Long-text truncation also marks the source incomplete for absence reasoning. Missing matches in an incomplete source produce `unknown`, never a successful absence assertion. Existing visible matches can still prove presence.

Only the currently exposed visible tree is searchable. Queries cannot retrieve lazy/off-screen content; scrolling or visual tools are still needed. A query does not make a stale node ID durable: every result is associated with a fingerprint, waits re-resolve selectors on every read, and mutations retain immediate snapshot validation, with only the narrow fractional-counter exception for ordinary actions described in [observation behavior](observation-and-threads.md). If a match is absent from the next overview, query again; do not reuse an old ID. Multiple matches are returned without silently choosing a target.

## Ending a task

`phone_action(action=finish)` now has `outcome=success|incomplete` and optional `evidence` conditions.

- `success` with evidence reads a fresh screen and checks ALL conditions before recording COMPLETED. Conditions should demonstrate the requested outcome, not merely the presence of the clicked button or app. When the App completion verifier is configured, this path also reviews the goal and summary against fresh tree evidence and task-local native readings/receipts. It does not require an additional screenshot. A changed tree after review invalidates the verdict.
- `success` without conditions captures a fresh screenshot and local OCR, then makes a separate review request to the configured model. That request exposes only a verdict tool, not phone actions. It judges the goal, proposed summary, recent results and current evidence. A text-only provider falls back to current OCR/tree evidence. The tree is re-read after the review; changed state invalidates the verdict. Pixel-only changes still have the limitations documented in observation-and-threads.md.
- `incomplete` ends with FAILED and the explanation rather than falsely marking the task complete. It makes no completion review request.
- Failed/uncertain verification is returned to the planning model as a failed finish result; three unsuccessful completions end the task within its existing overall step budget. Nothing is automatically replayed. An unknown payment/send outcome still requires inspection, not repetition.

This is evidence enforcement, not a proof of arbitrary task correctness. A model can choose an irrelevant assertion, misread an image, or produce an inaccurate summary. Prompting explicitly requires goal-relevant evidence; a broader evaluation set is still needed to measure false-complete rate. The independent review uses the same provider/model and can make correlated errors.

## Logs and integration

Query/condition results and completion evidence appear in the existing task's timeline and exported JSONL. They include selected matching text, state, fingerprint and timings; no screenshot bytes, model credentials or full UI-tree dump are added. Existing log retention/size limits remain. Review requests use the normal request/usage logger so their actual provider-reported tokens are counted with the run. Logging disabled still stops new records.

The App requires shell capabilities `uiEvents` and `inspection` and directs older engines to reactivate. The protocol remains version 2 with additive fields. Existing approvals, protected-input checks, exact capture/node validation and manual-control handling remain outside model control.

Bounded JavaScript workflows now use these same selectors and conditions: see [embedded QuickJS](javascript-runtime.md). Long-lived progress checkpoints and an external Codex skill/MCP adapter remain future work. Core contracts are platform-independent foundations, not an externally exposed API.

## Validation on Pixel 6

- 52 JVM tests passed: 46 core, 2 shell event-tracker and 4 model-settings tests. Coverage includes pagination/subtree queries, disabled controls, ANY/ALL, missing versus truncated evidence, bounded waits, cancellation/takeover, fresh finish checks, failed finish feedback, incomplete outcomes and changes during semantic review.
- Physical fixture: delayed enablement, text entry, one node click, late results alongside continuous unrelated text updates, query timeout, fresh verification and takeover rejection passed. One run's result wait took 622 ms / four reads while the unrelated text continued changing. This is a controlled fixture, not a cross-app performance benchmark.
- A real configured model completed the fixture query for 蓝牙耳机, read three results, and used the condition-wait tool. The query button was clicked exactly once. Separate real-model reviews accepted the matching result summary and rejected an unsupported paid-purchase claim, without any purchase actions.
- That live run recorded five planning responses and two review responses. The provider reported 23,030 planning tokens plus 4,709 review tokens (27,739 total). The two review calls were explicit validation checks, not an unavoidable cost for every successful task. At that milestone condition-based completion made no review call; the current App also performs semantic review after matching conditions. No before/after efficiency improvement is claimed.
- Real-model virtual-display execution, embedded/floating preview and takeover/handoff passed. Existing OCR/coordinate/stale-target fixture and provider protocol checks passed. The coordinate fixture waits for an actual UI result after input acceptance rather than assuming Android's injection acknowledgment means its click callback has already run.
- Debug/release builds, App/shell lint, production APK signing verification, ELF/ZIP 16 KB static alignment checks passed. No native runtime/model files changed in this work.

## Additional completion checks (2026-11-15)

A restricted, deterministic BigDecimal checker rejects demonstrably inconsistent basic equations in proposed summaries before model review. It supports ordinary arithmetic, parentheses, multiplication/division percentages and rounding tolerance; unsupported expressions and ambiguous additive percentage semantics are skipped. It does not interpret natural-language goals or prove arbitrary numerical claims. This was added after the planner and same-provider reviewer agreed on an incorrect calculator result. The native evidence ledger and semantic review reduce lost intermediate evidence, but correlated model mistakes remain possible. See [real-app validation](../real-world-validation.md).
