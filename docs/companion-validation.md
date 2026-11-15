# Agent companion validation

Development after v0.0.2; tested on a physical Pixel 6 (Android 17) with an authorized wireless ADB connection and the existing shell-UID runtime. The phone's configured DeepSeek Flash execution model had thinking disabled. No Wukong-provided API key was used. Other providers/device families were not exercised in this live pass.

## Checks

- Android debug app/test builds and lint passed. Existing JVM suites passed: agent-core 67, shell-runtime 2, app 4.
- Six host tests passed for the local page, browser bearer, Host/Origin restrictions, JSON framing route allowlist, stale-bridge reconnection cleanup, and rejection of an implicit switch to a different device. Remote approve/shell/direct input routes are absent.
- Three on-device protocol tests passed: authorization, Origin rejection, oversized/duplicate/chunked request framing, unknown endpoints, model-key redaction, protected bootstrap/activity/service declarations, and notification disconnect credential revocation/listener shutdown.
- The actual webpage fetched two models from the configured provider, tested the model through the phone, and saved its configuration while retaining the page. No browser JavaScript errors or horizontal overflow across 14 English/Chinese layout combinations (320, 375, 390, 720, 960, 1440 and 1920 px). The key input was empty after save; the saved key was not returned by the API. English/Chinese layouts were visually reviewed.
- A real task used Chrome's public recipe page (`https://xvcat.com/post/2471`) and Clock: read the honey chicken-wing temperature/duration, then prepare the corresponding timer without starting it. It completed with **180°C / 20 minutes**. Independent native node inspection confirmed `hour_text=00`, `minute_text=20`, `second_text=00`, and the `timer_start_button` still read “开始”. The public page was already cached; Chrome displayed an offline banner. This is not a claim of successful fresh Internet retrieval.
- The same task was paused through the companion, previewed at 720×1600, held past the earlier auto-sleep failure, and resumed. The task wake lock remained held while active and was absent from the current held-lock list after completion. It does not change the phone's stay-awake setting.
- Submitting the same request UUID returned the same conversation/receipt. Reusing it with a different goal was rejected. A completed task ID could not stop a later task.
- Closing/reopening the local webpage bridge left an accepted paused phone task intact. The task was queried after reconnect, then cancelled successfully while paused.
- Force-stopping the Android app with a reserved paused task produced `INTERRUPTED` on reconnect. Retrying its original request did not start another execution.
- Exporting the successful run produced a ZIP containing manifest and redacted per-run events. Detailed logs and private device captures are not committed.

## Findings and limits

The initial live attempt failed after the phone automatically slept during takeover: virtual display preview became black and observation failed. Active-task screen wake handling and an explicit locked/asleep start check were added. The subsequent task completed. A final receipt can arrive before the Android main-thread listener releases its wake lock; the check waits for the listener rather than assuming synchronous release.

The successful cooking trial used 19 planner turns plus completion review (20 model responses), approximately 130 seconds including a deliberately injected 55-second takeover pause. It included stale-input rejections and model replanning in Chrome. This is a selected integration test, not a speed or success-rate benchmark; reducing the calling agent's per-action overhead does not remove device-model inference cost or UI instability. The retained trace's provider-reported usage total was 265,757 tokens (263,374 prompt, 2,383 completion, including cached prompt tokens).

The webpage is a roughly 1.2-second read-only monitor. Manual touch/text input still happens in the phone floating window. Payments/deletion approvals remain on the phone, with no new high-risk transaction performed during validation. Locking the phone manually can still interrupt a task. Clearing app data/uninstalling removes persisted request IDs. v0.0.2's already published APKs predate the companion interface; this validation uses the current development build.
