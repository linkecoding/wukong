#!/usr/bin/env python3
"""Run the native execution sheet test with physical Back events on display 0."""
import argparse
import subprocess
import time
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('--adb',default='adb')
args=parser.parse_args()
adb=[args.adb,'-s',args.serial]
def run(*arguments):
    return subprocess.run(adb+list(arguments),capture_output=True,text=True,check=True).stdout.strip()
run('shell','run-as','com.wukong.phone','rm','-f','files/execution-back-request','files/execution-back-done')
p=subprocess.Popen(adb+['shell','am','instrument','-w','-e','physical_back','true','-e','class','com.wukong.phone.ExecutionOptionsUiTest','com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
seen=set();started=time.monotonic()
try:
    while p.poll() is None:
        if time.monotonic()-started>100:raise TimeoutError('Execution sheet test exceeded 100 seconds')
        r=subprocess.run(adb+['exec-out','run-as','com.wukong.phone','cat','files/execution-back-request'],capture_output=True,text=True)
        try:seq=int(r.stdout.strip())
        except ValueError:time.sleep(.15);continue
        if seq in seen:time.sleep(.1);continue
        run('shell','input','-d','0','keyevent','4')
        seen.add(seq)
        run('shell','run-as','com.wukong.phone','sh','-c',f"'echo {seq} > files/execution-back-done'")
    output=p.stdout.read();print(output)
    if 'OK (2 tests)' not in output or len(seen)!=6:raise SystemExit(1)
finally:
    if p.poll() is None:p.terminate()
