#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  [[ -z "$JAVA_HOME" ]] || export JAVA_HOME
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
cd "$task_root"
exec ./gradlew "$@"
