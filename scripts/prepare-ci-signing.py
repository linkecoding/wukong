#!/usr/bin/env python3
"""Restore an Actions signing identity without printing secret values."""
import base64, os, pathlib, re
root = pathlib.Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root/'app/build.gradle.kts').read_text()).group(1)
if os.environ['GITHUB_REF_NAME'] != 'v'+version:
    raise SystemExit('Release tag must match app versionName.')
names=['ANDROID_KEYSTORE_BASE64','ANDROID_KEYSTORE_PASSWORD','ANDROID_KEY_ALIAS','ANDROID_KEY_PASSWORD']
if any(not os.environ.get(name) for name in names): raise SystemExit('Missing Android signing secrets.')
key = pathlib.Path(os.environ['RUNNER_TEMP'])/'wukong-release.p12'
key.write_bytes(base64.b64decode(os.environ[names[0]],validate=True)); key.chmod(0o600)
def escape(value):
    return value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r').replace('=','\\=').replace(':','\\:').replace(' ','\\ ')
values={'storeFile':str(key),'storePassword':os.environ[names[1]],'keyAlias':os.environ[names[2]],'keyPassword':os.environ[names[3]]}
config = root/'signing.properties'
fd=os.open(config,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:f.write(''.join(f'{k}={escape(v)}\n' for k,v in values.items()))
print('Release tag validated; signing identity restored.')
