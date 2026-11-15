# Observation, settling and thread ownership

Checked against the source on 2026-11-15. This describes the implementation, not a promise that every Android page emits every event.

## Current observation path

```text
App UI: user starts a task
    → wukong-agent (TaskRuntime single worker)
        → loopback Socket (ShellSession / ShellClient)
            → app_process UID 2000 request worker
                → UiAutomation reads the target application's exposed nodes

app_process wukong-ui-events
    → window/content/scroll/text events
        → bounded per-window revision/time counters
```

The Wukong App main thread does not traverse the target tree. Shell's main thread accepts sockets; one of four request workers traverses the tree, with device operations serialized by deviceLock. The dedicated UI-event HandlerThread only records counters and timestamps. Android's target-app accessibility query machinery may still need that target app's UI thread; moving Wukong's caller off its own UI thread cannot remove a slow target-app dependency.

Node observation no longer hides/restores control overlays through Wukong's main thread: the reader selects application windows. Input injection and screenshot capture still use main-thread overlay updates where needed. OCR is explicitly rejected on the App main thread.

## Settling policy

The unconditional 650 ms delay after an accepted action is removed. Before the next model decision:

- Sample on the existing task worker, typically at 100 ms intervals.
- Require a 200 ms quiet interval with an unchanged package, snapshot fingerprint and relevant-window event revision. Recent UI events also delay a settled result.
- A changed sample restarts the quiet interval. Continuous changes exhaust the 1,800 ms sampling budget and return the latest snapshot with observation.settled=false, instead of failing the task.
- The budget includes elapsed reads, but cannot interrupt an already-running Binder/socket read. Individual reads retain their own bounded connection recovery and transport timeout; 1,800 ms is not an absolute wall-clock timeout for all IO.
- Observation metadata (settled, samples, elapsedMs, uiEvents) reaches the model. Logs also record event revision/quiet age and the calling thread.

The runtime listens for window state/content changes, window changes, scrolling and text changes. Counters used for one observation are scoped to the observed application window and its display's input-method windows. Window topology, focus and bounds are part of the exact fingerprint. Other apps' content events and other displays do not reset the target window's quiet interval.

A quiet tree is not a completed network request. A loading indicator can stay unchanged. Canvas/video/pixel-only changes may not generate usable tree events. Automatic image-difference settling is not implemented; screenshots/OCR remain available to the model.

## Changes during a decision

Exact execution-time checks remain. Old node IDs and capture IDs are never replayed against a new snapshot.

A positively identified pre-injection stale failure is now returned as an unexecuted tool result to the model. The next turn observes again and plans from the fresh state. Up to six consecutive stale replans are allowed, within the overall 30-round budget. Unknown/ambiguous execution failures are not automatically retried. If the UI changes while waiting for approval, the old approval is invalidated and the task replans; consequential actions require new approval.

## App thread inventory

These are ownership/allocation limits, not a fixed live thread count. Executors generally create threads when used, activities have lifecycles, and native/framework threads are additional.

| Owner | Worker allocation | Work |
| --- | --- | --- |
| TaskRuntime | 1 wukong-agent | Observation, sampling, model HTTP, action sequencing, synchronous OCR calls, approval wait |
| TaskRuntime | 1 wukong-task-cancel | Cancellation and shell cancellation requests |
| MainActivity | 1 executor per instance | Engine status/recovery request, preparing/copying log exports |
| SetupActivity | 1 executor per instance | Model tool-call test and device connection checks |
| ModelConfigurationView | 1 lazy executor per live form | Model-list requests; disposed with the view |
| WirelessActivation | 1 executor | Pairing/activation |
| WirelessSetupActivity | 1 executor per instance | Connection checks |
| ConnectionRecovery | 1 executor | Reconnect/activation scheduling |
| VirtualScreenService | 1 scheduled executor | Frame polling, decoding and virtual control requests; UI updates post to main |
| ChatModel / ModelCatalog | 1 closer executor each | Disconnect blocked HTTP connections during cancellation |
| LocalAdb | 2 scheduled workers | Connection deadline handling |
| ADB transport dependency | Reader thread per connection | ADB packet reading and stream dispatch |
| PP-OCRv6 tiny / ONNX Runtime | Native inference workers | Configured intra-op parallelism 4 per session; detection and recognition have separate sessions |

The App main thread still owns views, navigation, UI overlays, and UI callbacks. Existing app enumeration/label loading, some settings encryption/commit and history/log rendering also have main-thread work; this change does not move all storage/package-manager work off main.

## Additional shell-process threads

| Owner | Allocation | Work |
| --- | --- | --- |
| Main | Main accept loop | Accept local authenticated requests |
| Main | 4 request workers, queue bounded to 16 | UI reads, capture and input requests; deviceLock serializes device access |
| UiAutomation | 1 wukong-ui-events HandlerThread | Framework UI callbacks and change counters |
| VirtualScreen | 1 wukong-virtual-frames HandlerThread when used | Receive/copy virtual-display frames |
| VirtualScreen | 1 shared wukong-system-context HandlerThread when used | Initialize system context |

ART, Binder, graphics and native-library runtime threads are outside these explicit executor counts.

## Verification

35 JVM tests passed (29 agent-core, 2 shell event-tracker, 4 model settings). New tests cover quiet pages, transitions, event-only churn, bounded motion, cancellation, approval invalidation/reconfirmation, and explicit unexecuted tool feedback.

On the physical Pixel 6, controlled UI text transitions settled in 771 ms / 7 samples; continuous updates returned an unsettled latest snapshot in 1,817 ms / 15 samples. A fixture node action returned in 64 ms without a fixed post-action sleep. The stale repeat was rejected before a second click. OCR took 573 ms in that fixture run and its fresh capture supported a coordinate click. These are observations from one run, not cross-device benchmarks.

Provider compatibility tests and a real-model virtual-display task with manual takeover/handoff passed. Debug/release builds and app/shell lint passed. New App builds require the runtime's uiEvents and inspection capabilities; older runtimes show the existing update/reactivation path. No new service app or enabled AccessibilityService is required.

## Condition-based tools

The task loop now also exposes read-only query/wait/verify tools on the same worker. Condition waits sample fresh trees without global quiet settling, and cancellation/control epochs are checked between reads. The default model overview is compact; queries access the larger bounded source. Successful finish requires fresh deterministic evidence or a separate fresh screenshot/OCR review. See [condition tools](condition-tools.md) for contracts and limitations.

## Embedded QuickJS workflow threads

A lazy task-local JsSession now binds an isolated Android service on the first script. Its Binder callbacks enqueue work for the existing App task worker; phone API/approval work does not move to the App main thread. The isolated process adds one `wukong-quickjs` worker plus Android Binder/framework threads; no extra engine compiler/GC worker pool. Native input remains in the existing shell process. See [JS runtime](javascript-runtime.md).
