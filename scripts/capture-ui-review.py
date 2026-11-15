#!/usr/bin/env python3
"""Capture opt-in ScreenReviewTest windows over ADB without stealing UiAutomation."""
import subprocess,time,pathlib,argparse
parser=argparse.ArgumentParser()
parser.add_argument('serial')
args=parser.parse_args()
root=pathlib.Path('.tooling/cobalt-review');root.mkdir(parents=True,exist_ok=True)
base=['adb','-s',args.serial]
def adb(*a):return subprocess.run(base+list(a),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
adb('shell','run-as','com.wukong.phone','rm','-f','files/screen-review-request','files/screen-review-done')
with open('.tooling/screen-review-test.log','wb') as log:
 p=subprocess.Popen(base+['shell','am','instrument','-w','-e','review_screens','true','-e','class','com.wukong.phone.ScreenReviewTest','com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=log)
 last=None
 while p.poll() is None:
  r=adb('shell','run-as','com.wukong.phone','cat','files/screen-review-request')
  name=r.stdout.decode().strip() if r.returncode==0 else ''
  if name and name!=last:
   image=adb('exec-out','screencap','-p')
   if image.returncode or not image.stdout.startswith(b'\x89PNG'):
    p.terminate(); raise SystemExit('ADB screenshot failed: '+name)
   (root/(name+'.png')).write_bytes(image.stdout)
   adb('shell','run-as','com.wukong.phone','touch','files/screen-review-done');last=name
  time.sleep(.15)
print(pathlib.Path('.tooling/screen-review-test.log').read_text())

if p.returncode or "OK (1 test)" not in pathlib.Path(".tooling/screen-review-test.log").read_text(): raise SystemExit(1)
