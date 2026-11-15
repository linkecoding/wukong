# First task onboarding

The first screen leads with a task composer and one primary action. Three lightweight example rows offer focused tasks: find a downloaded file, search for an answer using Baidu in the browser, or search within a named installed app. Each asks for the filename/keyword, specific question, or app and search term. Tasks stop at a file or search results list; they do not purchase, send, download or delete anything. Browser searches need network access; file managers may require a first-use permission; app searches need the named app to be installed and accessible. Login, permission and captcha blocks are reported rather than bypassed.

1. Choose a task and enter the filename, question, or app and keyword. You can also write your own goal.
2. Tap Continue setup if anything is missing. Choose a model provider, follow its API platform link if needed, and enter your key. The standard address and model are prefilled, and the model name is directly editable in the main form. Advanced configuration preserves custom request URLs and thinking controls.
3. Test and continue. The tool-call check does not perform device actions; only success saves the candidate configuration.
4. Connect the phone. Wukong shows the current prerequisite: Wi-Fi, Developer options, pairing, or reconnection. The pairing notification lets you submit the six-digit code while Android's pairing dialog stays open. Ports and manual pairing are under Connection troubleshooting.
5. Allow floating controls when prompted so you can stop and approve tasks across apps.
6. Return to the saved task, review it, and tap Run task. Setup never runs it automatically.

The pending task is stored in app-private storage as you edit and survives switching apps or restarting Wukong. It is cleared when execution starts. It is not a resumed agent session. An active or interrupted agent run is not silently restarted.

Starting-app selection and virtual-display configuration are under Task options. An enabled virtual display's preview remains visible even while those options are collapsed. Settings contains the same guided model editor and language, log-retention, permission, and data controls.

## Returning users

When opening the app, Wukong checks the shell engine and discovers local wireless debugging. If a pairing identity was previously authorized and a local connection port is advertised, it attempts reconnection without asking for the pairing code again. Attempts are bounded to one per port per 60 seconds; failure leaves the explicit connection guide available. Reconnection does not enable wireless debugging, create a pairing authorization, or start a task. Network and system restrictions may still require the user to enable wireless debugging again.

No official hosted trial service is included. Users need their own provider account and API balance. The UI does not present a nonfunctional trial button.

See [provider presets](model-providers.md) for official sources and compatibility rules.

[Device verification and known limits](onboarding-validation.md) · [English task preview](images/onboarding/home-en.png) · [Model setup preview](images/onboarding/model-en.png)

## Review a completed task

Open History and tap its status card. Task details show the full goal, result, reported token totals and grouped execution events. Tap an event to inspect its recorded JSON. Use the fixed top-left Back action or Android Back to return. Clear-all logs and clear history are in the History top-right menu; removing a single trace is in its task detail menu. Both deletion paths require confirmation. Clearing logs preserves task results and never redirects an old history entry to a different run. Connection tests are separate from tasks and are listed under Settings → Connection diagnostics.
