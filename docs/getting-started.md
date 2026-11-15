# First task onboarding

The first screen is a conversation with one Send action. Two lightweight example rows offer focused tasks: find a downloaded file, search for an answer using Baidu in the browser. You can also write a goal for an installed app. Each asks for the filename/keyword, specific question, or app and search term. Tasks stop at a file or search results list; they do not purchase, send, download or delete anything. Browser searches need network access; file managers may require a first-use permission; app searches need the named app to be installed and accessible. Login, permission and captcha blocks are reported rather than bypassed.

1. Choose a task and enter the filename, question, or app and keyword. You can also write your own goal.
2. Tap Send; guided setup opens if anything is missing. Choose a model provider, follow its API platform link if needed, and enter your key. The standard address and model are prefilled, and the model name can be fetched from the dropdown after entering the key, or entered manually. Advanced configuration preserves custom request URLs and thinking controls.
3. Test and continue. The tool-call check does not perform device actions; only success saves the candidate configuration.
4. Connect the phone. Wukong shows the current prerequisite: Wi-Fi, Developer options, pairing, or reconnection. The pairing notification lets you submit the six-digit code while Android's pairing dialog stays open. Ports and manual pairing are under Connection troubleshooting.
5. Allow floating controls when prompted so you can stop and approve tasks across apps.
6. Return to the saved task, review it, and tap Send. Setup never runs it automatically.

The pending task is stored in app-private storage as you edit and survives switching apps or restarting Wukong. It is cleared when execution starts. It is not a resumed agent session. An active or interrupted agent run is not silently restarted.

Starting-app selection and phone/virtual display configuration are in the native execution-options bottom sheet below the composer. During a run, expand View screen in its conversation task card to watch and take over. The side drawer contains recent conversations, History and Settings; secondary pages push onto the Android Activity stack. Settings contains the same guided model editor and language, log-retention, permission, and data controls.

## Returning users

When opening the app, Wukong checks the shell engine and discovers local wireless debugging. If a pairing identity was previously authorized and a local connection port is advertised, it attempts reconnection without asking for the pairing code again. Attempts are bounded to one per port per 60 seconds; failure leaves the explicit connection guide available. Reconnection does not enable wireless debugging, create a pairing authorization, or start a task. Network and system restrictions may still require the user to enable wireless debugging again.

No official hosted trial service is included. Users need their own provider account and API balance. The UI does not present a nonfunctional trial button.

See [provider presets](model-providers.md) for official sources and compatibility rules.

[Device verification and known limits](onboarding-validation.md) · [English task preview](images/conversation/home-en.png) · [Model setup preview](images/onboarding/model-en.png)

## Review a completed task

Tap Execution details in a conversation task card, or open sidebar History and tap its status card. Task details show the full goal, result, reported token totals and grouped execution events. Tap an event to inspect its recorded JSON. Use the fixed top-left Back action or Android Back to return. Clear-all logs and clear history are in the History top-right menu; removing a single trace is in its task detail menu. Both deletion paths require confirmation. Clearing logs preserves task results and never redirects an old history entry to a different run. Connection tests are separate from tasks and are listed under Settings → Connection diagnostics.

## Virtual display and manual takeover

Open Execution options below the conversation composer and choose Virtual display. The starting-app row opens a searchable list; Automatic starts from the virtual desktop. The starting app does not limit subsequent cross-app navigation. Wait for the display to be ready before sending a task.

Expand View screen in the task card. The preview is embedded while Wukong is in the foreground and floats when you leave the app. Drag its header to move it and the corner handle to resize it; the last geometry is remembered. Closing the preview hides it without stopping the task. Return to Wukong and leave again to show it.

Tap Take over before touching the virtual screen. The agent pauses; you can tap, swipe and use Back/Home. Hand control back to let the agent read the current screen and continue. Stop ends the current run; it does not undo completed actions. Some apps restrict virtual displays, protected content or simultaneous instances; try the phone display when appropriate.

## Logs and feedback

Enable log retention in Settings before reproducing a problem. Open the task's Execution details and use the top-right menu to export that run. The ZIP contains `events.jsonl` and `manifest.json`, including device/app versions, tool calls and provider-reported token usage when supplied. Earlier runs without retained logs cannot be reconstructed. Token counts can be absent when the provider does not return usage.

Review task text, tool arguments/results and device metadata before attaching an export to an issue. Do not share API keys, pairing codes, passwords or private screenshots. See [reporting guidance](../CONTRIBUTING.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Pairing or connection fails | Connect Wi-Fi and enable Wireless debugging. Keep Android's pairing-code dialog open. Pairing and connection ports differ; use Connection troubleshooting if discovery fails. Reboots/network changes may require reconnecting. |
| Model request returns 401/403 | Check the key, account permissions, balance and selected model with your provider. |
| Model request returns 404 | Check the endpoint and model ID. Standard base URLs get `/chat/completions` appended; select exact-request-URL mode only for a complete endpoint. There is no required base-URL suffix. |
| Model dropdown is empty | Some compatible endpoints do not provide a usable model-list API. Enter a provider-supported tool-calling model ID manually. |
| App/preview is blank on the virtual display | The app may restrict multi-display use or protected content. Take over to inspect, or run on the phone display. |
| Task reports a wrong result | Inspect the actual screen and execution details. Completion checks reduce errors but cannot prove arbitrary task correctness. Export a redacted run and report the minimal case. |

For privacy, cost and autonomy limits, read the [disclaimer](../DISCLAIMER.md). For build/update signing differences, see [release documentation](releasing.md).
