# QuickJS Android engine

Upstream: https://bellard.org/quickjs/ — official release **2026-06-04**, MIT.
Archive URL, archive SHA-256 and per-file SHA-256: `source-manifest.json`.

`src/main/cpp/vendor` contains unmodified engine/support sources and upstream build/version/license metadata. The local build uses only quickjs, cutils, dtoa, libregexp and libunicode. quickjs-libc, the CLI, std/os modules and external module loading are excluded.

The local `bridge.c` exposes create/evaluate/interrupt/destroy over JNI and exactly one JSON host callback to JavaScript. It configures a 64 MiB engine allocation limit, 256 KiB stack limit, a monotonic interrupt deadline/atomic cancel flag, and disables blocking Atomics. Source and bridge strings use UTF-8 instead of JNI modified UTF-8.

Build uses pinned project NDK/CMake, `-Oz`, `-fwrapv` (matching upstream signed-overflow assumptions), LTO, hidden symbols and section GC. Linker options enforce 16KB LOAD/RELRO alignment. Android packaging strips the final library. The arm64 release `.so` is approximately 0.59 MiB.

Rebuild: `./scripts/gradle-local.sh -PtargetAbi=arm64-v8a :app:assembleRelease` from repository root; omit targetAbi for a universal APK. Run `python3 scripts/check-native-alignment.py app/build/outputs/apk/release/app-release.apk` to check all packaged 64-bit libraries and ZIP alignment. See `docs/architecture/javascript-runtime.md` for SDK/process contracts and device validation.
