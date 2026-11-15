#!/usr/bin/env python3
"""Drive Wukong's real on-device pairing UI on an English Android emulator.

The host only installs the APK and operates the UI; it never provisions shell
credentials, uploads the shell payload or starts app_process. Pairing codes
remain in memory and are not included in results.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    options = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", options.serial):
        parser.error("This fixture-only test cannot run on a physical phone")
    root = Path(__file__).resolve().parent.parent
    adb = [os.environ.get("ADB", "adb"), "-P", os.environ.get("ADB_SERVER_PORT", "5037"), "-s", options.serial]

    def run(*args):
        return subprocess.check_output(adb + list(args), stderr=subprocess.STDOUT).decode()

    def shell(*args):
        return run("shell", *args)

    def tree():
        path = "/data/local/tmp/wukong-wireless-ui.xml"
        shell("uiautomator", "dump", path)
        try:
            return ET.fromstring(shell("cat", path))
        finally:
            shell("rm", "-f", path)

    def tap_node(node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def tap_text(text):
        node = next((n for n in tree().iter("node") if n.get("text") == text), None)
        if node is None:
            raise RuntimeError("Missing UI control: " + text)
        tap_node(node)

    # Stop only this emulator's previous Wukong runtime. No activation is performed here.
    try:
        pid = shell("cat", "/data/local/tmp/wukong-shell.pid").strip()
        if pid.isdigit() and "com.wukong.shell.Main" in shell("cat", "/proc/" + pid + "/cmdline"):
            shell("kill", pid)
    except subprocess.CalledProcessError:
        pass
    run("install", "-r", str(root / "app/build/outputs/apk/debug/app-debug.apk"))
    shell("settings", "put", "global", "development_settings_enabled", "1")
    shell("am", "start", "--activity-clear-top", "-n", "com.wukong.phone/.MainActivity")
    tap_text("手机内激活")
    tap_text("启动配对助手")
    state = tree()
    if any(n.get("text") == "Allow" for n in state.iter("node")):
        tap_text("Allow")  # Wukong notification permission.
    for _ in range(12):
        state = tree()
        if any(n.get("text") == "Use wireless debugging" for n in state.iter("node")):
            break
        wireless = next((n for n in state.iter("node") if n.get("text") == "Wireless debugging"), None)
        if wireless is not None:
            tap_node(wireless)
            break
        shell("input", "swipe", "700", "2050", "700", "900", "350")
    state = tree()
    if not any(n.get("text") == "Pair device with pairing code" for n in state.iter("node")):
        tap_text("Use wireless debugging")
        if any(n.get("text") == "Allow" for n in tree().iter("node")):
            tap_text("Allow")
    tap_text("Pair device with pairing code")
    state = tree()
    code = next(n.get("text") for n in state.iter("node") if re.fullmatch(r"[0-9]{6}", n.get("text", "")))
    shell("cmd", "statusbar", "expand-notifications")
    tap_text("输入配对码")
    edit = next(n for n in tree().iter("node") if n.get("class") == "android.widget.EditText")
    tap_node(edit)
    shell("input", "text", code)
    code = None
    send = next(n for n in tree().iter("node") if n.get("resource-id", "").endswith("/remote_input_send"))
    tap_node(send)
    shell("cmd", "statusbar", "collapse")
    for _ in range(35):
        processes = shell("ps", "-A", "-o", "UID,PID,NAME,ARGS")
        runtime = [line for line in processes.splitlines() if "com.wukong.shell.Main" in line and line.strip().startswith("2000 ")]
        if runtime:
            print(json.dumps({"system_pairing": "passed", "on_device_activation": "passed", "runtime_uid": 2000, "host_activation_used": False}))
            return
        time.sleep(1)
    raise RuntimeError("Wukong did not start its shell runtime")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError:
        print("ADB UI operation failed; pairing values are intentionally omitted", file=sys.stderr)
        sys.exit(1)
