#!/usr/bin/env python3
"""Send local signing material to Actions Secrets over authenticated gh stdin."""
import base64, pathlib, subprocess
root = pathlib.Path(__file__).resolve().parents[1]
config = dict(line.split('=',1) for line in (root/'signing.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
values = {'ANDROID_KEYSTORE_BASE64': base64.b64encode(pathlib.Path(config['storeFile']).read_bytes()).decode(), 'ANDROID_KEYSTORE_PASSWORD':config['storePassword'], 'ANDROID_KEY_ALIAS':config['keyAlias'], 'ANDROID_KEY_PASSWORD':config['keyPassword']}
subprocess.run(['gh','auth','status'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
for name,value in values.items():
    subprocess.run(['gh','secret','set',name,'--repo','linkecoding/wukong'], input=value.encode(),check=True)
    print('Configured '+name)
