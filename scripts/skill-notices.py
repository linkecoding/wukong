#!/usr/bin/env python3
"""Collect bundled browser dependency licenses plus scrcpy/Kotlin attribution."""
from pathlib import Path
import json,urllib.request
root=Path(__file__).resolve().parents[1];modules=root/'tooling/webusb/node_modules'
records=['Wukong skill bundled dependencies\n\nThe Wukong project has not selected its own license. Third-party licenses follow.\n']
for package in sorted(modules.glob('@yume-chan/*')):
    meta=package/'package.json'
    if not meta.exists():continue
    info=json.loads(meta.read_text());records.append(f"\n{info['name']} {info['version']}\n")
    licenses=[p for p in package.iterdir() if p.name.lower() in ('license','license.txt','license.md','copying')]
    for path in licenses:records.append(path.read_text())
    if not licenses:records.append('License: '+str(info.get('license'))+'; source: https://github.com/yume-chan/ya-webadb\n')
records.append('\nscrcpy server 3.3.3 — Genymobile and contributors; Apache-2.0\nSource: https://github.com/Genymobile/scrcpy/tree/v3.3.3\n')
with urllib.request.urlopen('https://raw.githubusercontent.com/Genymobile/scrcpy/v3.3.3/LICENSE',timeout=30) as response:records.append(response.read().decode())
records.append('\nKotlin standard library — Copyright JetBrains s.r.o. and Kotlin Programming Language contributors; Apache-2.0 (license above). Source: https://github.com/JetBrains/kotlin\n')
(root/'skills/wukong/assets/THIRD-PARTY-NOTICES.txt').write_text('\n'.join(records))
