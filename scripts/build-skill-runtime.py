#!/usr/bin/env python3
"""Build a dex-only runtime JAR. The archive has no installable Android manifest."""
from pathlib import Path
import hashlib,json,subprocess,zipfile,re
root=Path(__file__).resolve().parents[1]
subprocess.run([str(root/'scripts/gradle-local.sh'),':standalone-runtime:assembleDebug'],cwd=root,check=True)
out=root/'skills/wukong/assets/runtime';out.mkdir(parents=True,exist_ok=True)
jar=out/'wukong-runtime.jar'
with zipfile.ZipFile(root/'standalone-runtime/build/outputs/apk/debug/standalone-runtime-debug.apk') as source,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as target:
    for name in source.namelist():
        if name.startswith('classes') and name.endswith('.dex'):target.writestr(name,source.read(name))
build_id=re.search(r'VALUE = "([a-f0-9]+)"',(root/'standalone-runtime/build/generated/identity/RuntimeBuildId.kt').read_text()).group(1)
(out/'manifest.json').write_text(json.dumps({'buildId':build_id,'protocol':1,'plannerLocation':'phone','sha256':hashlib.sha256(jar.read_bytes()).hexdigest()},indent=2)+'\n')
print(f'{jar.name}: {jar.stat().st_size} bytes; no APK installation')
