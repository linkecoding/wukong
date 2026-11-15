# Embedded QuickJS phone workflows

Implemented 2026-11-15. The model can call `phone_run_js` to organize existing phone capabilities with synchronous JavaScript, loops, branches and task-local JSON state. No Auto.js application, user-enabled AccessibilityService, Node.js installation or external scripting app is required. Auto.js is an API/workflow reference; no Auto.js source was copied.

## Runtime and authority

```text
Model → phone_run_js(code)
  → App task worker: JsSession dispatch loop
    ↔ Binder ↔ isolated Android service (:javascript)
                  → one wukong-quickjs worker / embedded QuickJS
    → trusted ScriptHost → validation / local approval / control epoch
      → AndroidDriver → existing UID 2000 app_process → phone
```

QuickJS 2026-06-04 is built from pinned MIT-licensed upstream source in `third_party/quickjs`. Its source archive and individual files have SHA-256 values in `source-manifest.json`. A small local JNI bridge uses only the engine, regexp, Unicode, dtoa and utility sources. The command-line interpreter, quickjs-libc, std/os modules and filesystem/network loaders are not built or registered. See [upstream QuickJS](https://bellard.org/quickjs/) and its [embedding API](https://bellard.org/quickjs/quickjs.html).

The service is non-exported and has `isolatedProcess=true`, so it has a separate isolated UID and does not inherit the App's permissions. The shell still runs separately at UID 2000. See [Android's service contract](https://developer.android.com/guide/topics/manifest/service-element#isolated). The JS engine itself is not treated as the Android permission boundary.

Only a string-in/string-out native/Binder bridge is exposed. No Java reflection/proxy loader, Context, shell token, model credentials, files or network APIs reach scripts. `Java`, `Packages`, `require` and `fetch` are absent. The existing model service still receives task/evidence through the App normally. JNI transfers source, responses and results as real UTF-8, including supplementary characters such as emoji.

Binder callbacks only enqueue host calls and wait for replies. The task worker processes that queue; phone reads, actions and approval waits stay on its existing thread. Native UI events continue on the shell's event handler. The isolated process has one JS worker plus Android Binder/framework threads; QuickJS has no added compiler/GC worker pool or Javet guard daemon. Runtime/context ownership stays on its worker, with only an atomic cancel flag touched by the cancellation entry point.

## SDK

The global API object is `wukong`, for example `wukong.query()` and `wukong.tap()`.

| API | Behavior |
| --- | --- |
| `wukong.id(fullId)` / `text(exactText)` / `textEquals(exactText)` / `textContains(text)` / `select(object)` | Explicit immutable selector plans, combinable with `.enabled(true)`, `.editable(true)`, `.ancestorResourceId(fullId)`, `.nearText(rowLabel)`; no retained native handles |
| `wukong.waitAny({success: selectorOrCondition, error: selectorOrCondition}, {timeoutMs})` | Resolve named outcomes from one fresh snapshot per sample; return `key`, `matchedKeys`, `matches`, `timedOut` and full verification evidence. `key=null` on timeout; multiple matches retain insertion order and all names. |
| `wukong.waitGone(selector, options)` / `waitEnabled(selector, options)` / `exists(selector)` | Bounded structured waiting; exists returns true/false/null (unknown due to incomplete source). Predicate callbacks are rejected explicitly. |
| `wukong.setText(selector,text,options)` / `setChecked(selector,checked,options)` | Reconcile desired state (text may be empty, maximum 2000 characters), skip a correct state, otherwise inject once and verify with fresh reads up to 2s. Failure to verify terminates the cell without replay. |
| `wukong.observe()` | Compact current snapshot with package, fingerprint, revision, normalized nodes and any current OCR metadata |
| `wukong.query(selector, {limit, offset})` | Read/filter/page the larger visible source |
| `wukong.waitUntil(selectorOrConditions, options)` | Freshly resolve conditions without global page-quiet settling |
| `wukong.verify(selectorOrConditions, options)` | Read current condition evidence, without finishing the task |
| `wukong.tap(selector,options)` / `input(selector,text,options)` / `scroll(selector,forward,options)` | Wait read-only for a unique enabled target, then perform one validated action. `timeoutMs` defaults to 3000, range 0–10000; zero samples once. |
| `wukong.launch(packageName)` / `home()` / `back()` | Navigate within the session's target display |
| `wukong.ocr()` / `screenshot()` | Current capture ID, local OCR and normalized boxes; raw image bytes are not copied into JS |
| `wukong.tapAt(x,y,captureId)` / `longPress(x,y,captureId,duration)` / `swipe(x,y,endX,endY,captureId,duration)` | Existing guarded coordinate actions |
| `wukong.sleep(milliseconds)` | Bounded, cancelable wait; conditions are preferable |
| `wukong.log(message)` | Add bounded diagnostic output to this task's existing trace |
| `wukong.events.onChange(callback)` | Register a cell-local change listener; returns an unsubscribe function |
| `wukong.events.next({after,timeoutMs})` | Wait for relevant revision/package/fingerprint changes, return an event and synchronously dispatch registered listeners |

Selectors/conditions share [the query tool contracts](condition-tools.md). A string is an exact text selector. Query returns a page object (`nodes`, `totalMatches`, `hasMore`, `sourceTruncated`, fingerprint). Prefer `page.nodes.map(...)`; `.map`, `.filter`, `.find`, `.length` and iteration are also provided as non-enumerable conveniences, preserving the metadata when returning/serializing the page. A numeric query second argument is shorthand for `{limit: n}`. Wait/verify accept a selector, a condition object, an array of selectors/conditions, or the full `{conditions,matchAll,timeoutMs}` form. Target actions require a unique enabled target on a complete source. Input/scroll add their capability filters. Tap can resolve a non-clickable label to its nearest clickable ancestor, but never climbs through a scrolling/list/grid container. Ambiguous targets remain errors. `nearText` selects controls within the nearest shared non-scrolling ancestor of a matching label; it does not use pixel proximity. Text `text` keeps its existing combined label/value/hint semantics for compatibility. Native nodes additionally expose `rawText`; prefer it for displayed readings when combined accessibility descriptions disagree. Editable showing-hint text means an empty value, and protected inputs stay redacted. State-setting uses the raw value for exact comparison. Unlabeled clickable containers expose bounded `childLabels` consistently in overview, query and condition results. Use stable IDs for desired-state operations; a selector that matches only the previous value cannot verify the new state. Unknown selector/option fields and incorrect value types fail early. Targets are selectors, not durable node handles. The shell checks the snapshot immediately before mutation. Ordinary node/navigation actions may tolerate only fractional stopwatch-counter digit changes through a second interaction fingerprint; labels, topology, bounds and states remain exact. Captures, coordinate actions and sensitive actions require the full fingerprint.

Example for an illustrative app, with placeholder resource IDs:

```js
state.keyword = '蓝牙耳机';
wukong.setText(wukong.id('example.app:id/query'), state.keyword);
wukong.tap(wukong.id('example.app:id/search'), {timeoutMs: 3000});
const outcome = wukong.waitAny({
  success: wukong.textContains('查询结果').ancestorResourceId('example.app:id/results'),
  error: wukong.textContains('网络错误')
}, {timeoutMs: 8000});
return outcome;
```

A script's return is evidence for the next model decision. It never marks the task complete. The planner still uses `finish` with fresh conditions or independent visual/semantic review.

## Event and state semantics

Listeners are cooperative and synchronous in this first version. Registering a callback does not create a background event loop; callbacks run when `events.next()` returns a change. They do not run on the shell event thread, during CPU-only JS, or automatically during every other blocking API. Multiple native events can be coalesced into one revision change. Only the existing window/tree signals are observed; pixel-only video/canvas changes are not promised. Listeners end with the cell. `async`/`await`, promises and Node timers are not supported as cell orchestration APIs.

Each cell uses a fresh QuickJS runtime/context. Only the explicit `state` object survives successful cells in the same task, serialized as JSON (maximum 32K characters). Top-level variables, closures, node objects and listeners do not persist. Errors, human takeover and stopping clear state; nothing is written to persistent storage. This avoids carrying old asynchronous callbacks or stale target objects into a new decision. The task-local isolated service is lazy-loaded on the first script and unbound at task end; native runtime/context resources are released after every cell.

## Bounds and failure handling

- Source limit: 16,000 characters; return value: 64K; serialized state: 32K. Oversized results fail rather than silently truncating structured data.
- Cell deadline: 60 seconds including host calls/approvals, with 80 high-level phone calls. Across a task, the trusted host bounds reads to 1,000 and scripted mutations to 100, in addition to the existing model-turn budget. In-memory turn snapshots are capped to the existing eight-turn model window so a long cell does not accumulate a full tree/image per operation.
- `JS_SetMemoryLimit` caps engine allocations at 64 MiB; this is not a cap on total native/process RSS. A 256 KiB native-stack limit turns excessive recursion into a script error. Allocation-limit errors return to the App and the next cell gets a fresh runtime. The isolated process still contains unexpected native failures.
- `JS_SetInterruptHandler` checks a monotonic deadline and atomic cancellation flag during evaluation. The App also polls cancellation/control epoch/deadline. `JS_SetCanBlock(false)` rejects blocking `Atomics.wait` so it cannot bypass interruption. In-flight Binder/system input retains its existing transport bounds and cannot be undone by interrupting JS.
- Every mutation crosses the trusted core validator, sensitive-action approval and execution-time snapshot/capture checks. Approval expiry or a changed page invalidates that approval and stops the cell. A human-control epoch change stops the old script; it is not resumed/replayed on handoff.
- A host error is fatal to the cell even if JS catches the thrown error. This prevents retrying an ambiguous mutation inside a catch/loop. Completed effects remain recorded; the model gets the error and new observations so it can continue from the actual state. User rejection ends the task. No automatic rollback/replay is attempted.
- If the isolated process dies, that JS session is unusable and is not silently recreated with old script contents. A new task can create a fresh session.

Malformed model tool arguments become local `Action.Invalid` feedback, not device operations. The existing core validation-retry limit applies; the next request includes the rejected tool call and its matching tool result. Unambiguous selector-only entries in condition/evidence arrays normalize into presence conditions; mixed or misspelled fields still fail. A legacy engine without rawText is rejected before setText dispatch and asks for built-in shell reactivation. JavaScript evaluation failures also return partial accepted-effect summaries.

Native call failures include a structured diagnostic in the planner error and `script_call_error` trace: `code`, `method`, `selector` when present, cell-local `step`, `elapsedMs`, `attempts`, `accepted`, `verified`, `acceptedCount`, and the last four completed mutations. Codes distinguish invalid arguments, missing/ambiguous/not-ready targets, incomplete source, cancellation and unknown outcomes. `accepted` is true after a successful host response, false before dispatch or after a proven pre-injection rejection, and conservatively `"unknown"` for other dispatch failures: legacy driver errors do not establish whether injection occurred. `verified` is independent of acceptance. Partial effects are not rolled back. Wait timeouts remain ordinary read results, not fatal exceptions.

Logs group the script under its model step and record individual API methods/arguments, results (bounded), timing and errors. Script source is a recorded tool argument. API key redaction, retention, disabled-logging behavior and token accounting remain unchanged. Native JS execution itself has no model-token usage; planning/review responses retain their provider-reported usage.

## Size and compatibility

The following arm64 production builds use the same app features, release signing and R8/resource shrinking settings. V8 was a local experiment; it was not published as a release.

| Build | APK | Engine library after strip |
| --- | --- | --- |
| Before script workflows | 26.82 MiB | — |
| V8 / Javet 6.0.3 experiment | 86.57 MiB | 59.27 MiB |
| QuickJS with the same phone SDK | 27.45 MiB | 0.59 MiB |

QuickJS adds approximately 0.63 MiB to the pre-script APK and removes about 59.1 MiB versus the V8 build. JNI exposes only four native entry points; `-Oz`, LTO, hidden symbols and section garbage collection discard unused functions. Packaging still strips native symbols, and libraries remain uncompressed for direct loading. The current APK contains no Javet classes, library or notices. No optional V8 download or companion app is required. Universal APKs include all configured ABIs and are larger.

This is a size comparison, not a claim that QuickJS matches V8's compute throughput. These phone workflows mostly wait on device/model IO; tests exercise representative workflows rather than a general CPU benchmark. The same JS API/state/error contracts are preserved.

The new library and all other arm64 libraries pass ELF and ZIP 16KB static alignment checks; CMake links with explicit 16KB page alignment using NDK 28.2. The physical Pixel 6 runs with 4KB pages, so this is not a new 16KB-device runtime test. Release R8 retains the four JNI entries and bridge method; the production APK is signed and verified.

The project still targets Android 10+; built-in wireless activation requires Android 11+. Codex/MCP integration and a full Auto.js compatibility layer remain future work.

## Validation

- 60 JVM tests passed, including per-operation approvals, stale approval invalidation, bounded task snapshots, mutation budgets, partial/ambiguous failure handling and control epochs.
- Six physical engine tests passed: isolated UID, state/context lifetime, no Java/Node/network globals, list/pagination query compatibility, Unicode/emoji round trips, infinite-loop termination/recovery, caught host failures, human takeover, engine allocation limits with the same service remaining usable, recursive-stack overflow and blocked Atomics rejection.
- Two provider-protocol checks passed with the added JS tool. Four physical flow checks covered the scripted fixture (query/input/tap/change callback/wait/result), condition waits during unrelated changes, compact observations and OCR/coordinate/stale-target behavior.
- Real-model fixture and real-model virtual-display takeover/handoff passed. The fixture required a completed JS cell, verified the result count and asserted exactly one query-button click. Independent reviews accepted the actual result and rejected a paid-purchase claim; no payment actions were performed.
- Debug/arm64/universal release builds and App/shell lint passed. Production signatures and ELF/ZIP alignment passed for both arm64 and universal APKs (14 64-bit libraries checked in the universal package). Current universal APK is approximately 88.20 MiB, versus 308.43 MiB for the local V8 experiment; current arm64 APK is 27.45 MiB.

These are controlled Pixel 6 checks, not a broad app-compatibility or CPU-speed benchmark. No general timing improvement is claimed from the engine replacement.

## Real-app hardening (2026-11-15)

A typed `ActionNotExecuted` distinguishes proven pre-injection rejection from ambiguous outcomes. Only stale, unexecuted selector actions re-observe and resolve the original selector again, within the original timeout and four attempts. Accepted effects, unknown failures and whole scripts are never automatically replayed.

The Android planner allows 50 turns (the core default remains 30); cell API and task mutation budgets are unchanged. A task-local native evidence ledger retains at most 16 readings and 128 accepted-action receipts, independently of persisted logging. Historical readings contain no actionable node IDs and protected-input screens are excluded. This preserves intermediate readings and distinguishes one accepted button press from multiple app-generated rows. No new worker thread is introduced. See [real-app validation](../real-world-validation.md).
