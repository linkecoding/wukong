#!/usr/bin/env python3
"""Drive physical-display input while the instrumented virtual fixture checks mapped events.
Build/install the debug App and test APK, then activate the shell runtime before running.
"""
import argparse
import json
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]

def run(*argv):
    return subprocess.run(adb + list(argv), capture_output=True, text=True, check=True).stdout.strip()

run('shell', 'run-as', 'com.wukong.phone', 'rm', '-f', 'files/floating-input-plan.json', 'files/floating-input-ack.txt', 'files/floating-input-report.json')
process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-e', 'physical_input', 'true', '-e', 'class', 'com.wukong.phone.FloatingInputTest', 'com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
seen = set()
started = time.monotonic()
try:
    while process.poll() is None:
        if time.monotonic() - started > 240:
            raise TimeoutError('Physical input test exceeded 240 seconds')
        packet = subprocess.run(adb + ['exec-out', 'run-as', 'com.wukong.phone', 'cat', 'files/floating-input-plan.json'], capture_output=True, text=True)
        try:
            command = json.loads(packet.stdout)
        except (ValueError, TypeError):
            time.sleep(.15)
            continue
        number = int(command['id'])
        if number in seen:
            time.sleep(.1)
            continue
        values = [str(int(command[key])) for key in ('x', 'y', 'endX', 'endY')]
        if command['kind'] == 'tap':
            run('shell', 'input', '-d', '0', 'tap', *values[:2])
        elif command['kind'] == 'swipe':
            run('shell', 'input', '-d', '0', 'swipe', *values, '400')
        else:
            raise ValueError('Unexpected input kind')
        seen.add(number)
        run('shell', 'run-as', 'com.wukong.phone', 'sh', '-c', f"'echo {number} > files/floating-input-ack.txt'")
        print(f'Physical display 0: {number} {command["kind"]} {values}', flush=True)
    output = process.stdout.read()
    print(output)
    if 'OK (1 test)' not in output or 'FAILURES!!!' in output:
        raise SystemExit(1)
    report = json.loads(run('exec-out', 'run-as', 'com.wukong.phone', 'cat', 'files/floating-input-report.json'))
    if len(seen) != 60 or len(report) != 5 or any(len(case['operations']) != 9 or case['letterbox_taps_ignored'] != 2 for case in report):
        raise RuntimeError('Incomplete physical input coverage')
    print('Passed: 5 live sizes, 25 target taps, 20 swipes, 10 ignored margin taps, 5 resize drags')
finally:
    if process.poll() is None:
        process.terminate()
