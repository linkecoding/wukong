#!/usr/bin/env bash
set -euo pipefail
task_serial="${1:-}"
if [[ ! "$task_serial" =~ ^emulator-[0-9]+$ ]]; then echo "Usage: $0 emulator-5580" >&2; exit 2; fi
task_root="$(cd "$(dirname "$0")/.." && pwd)"
exec "$task_root/scripts/test-device.sh" "$task_serial"
