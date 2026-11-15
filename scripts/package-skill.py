#!/usr/bin/env python3
"""Create a portable skill ZIP, excluding caches and local state."""
import argparse
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
source = Path(__file__).resolve().parents[1] / 'skills/wukong'
allowed = {'.md', '.yaml', '.py', '.html', '.css', '.js', '.svg'}
files = sorted(path for path in source.rglob('*') if path.is_file() and path.suffix in allowed and '__pycache__' not in path.parts)
assert source.joinpath('SKILL.md') in files
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in files:
        archive.write(path, Path('wukong') / path.relative_to(source))
print(f'{args.output}: {len(files)} files, {args.output.stat().st_size} bytes')
