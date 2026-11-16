#!/usr/bin/env python3
"""Fetch the pinned upstream server; verify GitHub's published SHA-256 before packaging."""
from pathlib import Path
import hashlib,urllib.request,json
root=Path(__file__).resolve().parents[1];out=root/'skills/wukong/assets/runtime';out.mkdir(parents=True,exist_ok=True)
version='3.3.3';expected='7e70323ba7f259649dd4acce97ac4fefbae8102b2c6d91e2e7be613fd5354be0'
file=out/'scrcpy-server.jar'
if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=expected:
    with urllib.request.urlopen(f'https://github.com/Genymobile/scrcpy/releases/download/v{version}/scrcpy-server-v{version}',timeout=60) as response:data=response.read(2*1024*1024)
    assert hashlib.sha256(data).hexdigest()==expected
    file.write_bytes(data)
(out/'scrcpy.json').write_text(json.dumps({'version':version,'sha256':expected})+'\n')
print(f'scrcpy {version}: {file.stat().st_size} bytes, checksum verified')
