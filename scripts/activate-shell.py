#!/usr/bin/env python3
"""Provision and start the UID 2000 runtime. Never print the activation token."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import socket
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Explicit ADB device serial")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--server-port", default=os.environ.get("ADB_SERVER_PORT", "5037"))
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--stop", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    adb = [args.adb, "-P", args.server_port, "-s", args.serial]

    def run(*argv, data=None):
        return subprocess.run(adb + list(argv), input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout.decode().strip()

    def shell(*argv, data=None):
        return run("shell", shlex.join(argv), data=data)

    if run("get-state") != "device":
        raise RuntimeError("Device is not authorized")
    stop_script = r'''
        pfile=/data/local/tmp/wukong-shell.pid
        if [ -f "$pfile" ]; then
            p=$(cat "$pfile")
            case "$p" in ''|*[!0-9]*) exit 1 ;; esac
            if [ -r "/proc/$p/cmdline" ]; then
                command=$(tr '\000' ' ' < "/proc/$p/cmdline")
                case "$command" in *com.wukong.shell.Main*) kill "$p" ;; esac
            fi
            rm -f "$pfile"
        fi
    '''
    if args.stop:
        shell("sh", "-c", stop_script)
        print("悟空 Shell 已停止")
        return
    app = root / "app/build/outputs/apk/debug/app-debug.apk"
    runtime = root / "shell-runtime/build/outputs/apk/debug/shell-runtime-debug.apk"
    if not runtime.is_file() or (not args.skip_install and not app.is_file()):
        raise RuntimeError("Build :app:assembleDebug and :shell-runtime:assembleDebug first")
    if not args.skip_install:
        run("install", "-r", str(app))
    shell("sh", "-c", stop_script)
    run("push", str(runtime), "/data/local/tmp/wukong-shell.apk")
    prepared = shell("content", "call", "--uri", "content://com.wukong.phone.bootstrap", "--method", "prepare")
    match = re.search(r"\btoken=([a-f0-9]{64})\b", prepared)
    if not match:
        raise RuntimeError("App did not provision the shell connection")
    token = match.group(1)
    shell("sh", "-c", "umask 077; cat > /data/local/tmp/wukong-shell.token", data=token.encode())
    shell("sh", "-c", "CLASSPATH=/data/local/tmp/wukong-shell.apk nohup app_process /system/bin com.wukong.shell.Main 43219 /data/local/tmp/wukong-shell.token >/data/local/tmp/wukong-shell.log 2>&1 </dev/null & echo $! > /data/local/tmp/wukong-shell.pid")
    port = run("forward", "tcp:0", "tcp:43219")
    try:
        ready = False
        for _ in range(40):
            try:
                with socket.create_connection(("127.0.0.1", int(port)), timeout=1) as connection:
                    connection.sendall((json.dumps({"method": "status", "token": token}) + "\n").encode())
                    data = b""
                    while b"\n" not in data and len(data) < 4096:
                        chunk = connection.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                    result = json.loads(data)
                    ready = result.get("ok") is True and result["result"].get("uid") == 2000 and result["result"].get("protocol") == 2
                    if ready:
                        break
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.25)
        if not ready:
            raise RuntimeError("Shell did not become ready; inspect /data/local/tmp/wukong-shell.log on the device")
    finally:
        run("forward", "--remove", "tcp:" + port)
    shell("am", "start", "--activity-single-top", "-n", "com.wukong.phone/.MainActivity")
    print("悟空 Shell 已激活：app_process / UID 2000 / protocol 2")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # subprocess errors may carry command output. Deliberately never dump that output.
        print("激活失败：" + (str(error) if not isinstance(error, subprocess.CalledProcessError) else "ADB 操作失败，请检查设备连接与授权"), file=sys.stderr)
        sys.exit(1)
