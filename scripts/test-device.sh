#!/usr/bin/env bash
set -euo pipefail
# All device control is performed by the separately activated app_process, not UiAutomation in the test runner.
task_serial="${1:-}"
if [[ -z "$task_serial" ]]; then echo "Usage: $0 SERIAL [--allow-physical]" >&2; exit 2; fi
if [[ ! "$task_serial" =~ ^emulator-[0-9]+$ && "${2:-}" != "--allow-physical" ]]; then
  echo "Physical-device tests require --allow-physical" >&2; exit 2
fi
task_adb="${ADB:-adb}"
task_port="${ADB_SERVER_PORT:-5037}"
task_root="$(cd "$(dirname "$0")/.." && pwd)"
"$task_adb" -P "$task_port" -s "$task_serial" get-state >/dev/null
"$task_adb" -P "$task_port" -s "$task_serial" install -r "$task_root/app/build/outputs/apk/debug/app-debug.apk"
"$task_adb" -P "$task_port" -s "$task_serial" install -r "$task_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
task_use_running=false
for task_argument in "$@"; do if [[ "$task_argument" == "--use-running-shell" ]]; then task_use_running=true; fi; done
if [[ "$task_use_running" != true ]]; then
  python3 "$task_root/scripts/activate-shell.py" "$task_serial" --adb "$task_adb" --server-port "$task_port" --skip-install
fi
task_accessibility_before="$("$task_adb" -P "$task_port" -s "$task_serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
task_overlay_before="$("$task_adb" -P "$task_port" -s "$task_serial" shell appops get com.wukong.phone SYSTEM_ALERT_WINDOW | sed -nE 's/.*SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground).*/\1/p' | head -1 | tr -d '\r')"
task_overlay_before="${task_overlay_before:-default}"
restore_overlay() { "$task_adb" -P "$task_port" -s "$task_serial" shell appops set com.wukong.phone SYSTEM_ALERT_WINDOW "$task_overlay_before" >/dev/null; }
trap restore_overlay EXIT
"$task_adb" -P "$task_port" -s "$task_serial" shell appops set com.wukong.phone SYSTEM_ALERT_WINDOW allow
task_output="$("$task_adb" -P "$task_port" -s "$task_serial" shell am instrument -w -e class com.wukong.phone.DeviceDriverTest com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner)"
echo "$task_output"
if [[ "$task_output" != *"OK ("* || "$task_output" == *"FAILURES"* || "$task_output" == *"INSTRUMENTATION_FAILED"* ]]; then exit 1; fi
task_accessibility_after="$("$task_adb" -P "$task_port" -s "$task_serial" shell settings get secure enabled_accessibility_services | tr -d '\r')"
[[ "$task_accessibility_before" == "$task_accessibility_after" ]]
echo "Enabled accessibility services were not changed."
