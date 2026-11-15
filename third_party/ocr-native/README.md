# OCR-only native runtime bundle

This module packages Java APIs and custom native builds used only by Wukong's
PP-OCRv6 tiny pipeline. It is not a general replacement for all OpenCV/ONNX APIs.

- OpenCV 4.14.0, pinned commit `0654a42e19215ef25b1d367d822f3c630447e7c7`.
  Only `core`, `imgproc`, and Java/JNI bindings are built. OpenCL, TBB, ITT,
  protobuf and KleidiCV are disabled. Android BitmapFactory decodes images,
  so OpenCV imgcodecs and its image codec libraries are unnecessary. NEON and
  supported CPU dispatch remain enabled. Disabling KleidiCV also avoids its
  unsupported SVE instruction path on the Apple Silicon 16 KB AVD tested here.
- ONNX Runtime 1.21.1, pinned commit
  `8f7cce3a49fdbdac96e0868b75b7d0159db7ac7f`. CPU-only build with LTO,
  reduced operator registration from `required-operators.config`, no traditional
  ML operators, RTTI or telemetry. Standard ONNX model loading and graph
  optimization remain available; this is not an ORT-format-only minimal build.
- Eigen headers are fetched at the exact upstream dependency commit
  `1d8b82b0740839c0de7f1242a3585e3390ff5f33`. The CMake FetchContent source override
  uses this verified Git checkout because the GitLab archive hash differs from
  the older ORT release's expected archive. Dependency verification is not disabled.
- The Java API JARs and OpenCV resources come unchanged from the corresponding
  official Maven AARs. Unused Java APIs may remain in the JAR; only the stated
  OpenCV native modules and tiny-model operators are supported by this bundle.
- The shared C++ runtime comes from NDK 28.2.13676358. Native files are stripped
  of unnecessary symbols and built with 16 KB max/common page alignment.

`manifest.json` records source commits and binary/JAR hashes. Original model
hashes and download URLs are in `../paddleocr/models-manifest.json`.

## Rebuild

Install Python 3.10+, JDK17, SDK35 and NDK28.2.13676358 on the computer. The script
uses `JAVA_HOME` and `ANDROID_HOME` (or explicit `--sdk` / `--jdk`). Then:

```
python3.12 scripts/build-ocr-native.py --abi arm64-v8a --jobs 4
```

Omit `--abi` to rebuild all four supported ABIs. Sources and build caches are kept
in ignored `.tooling`. The script creates an isolated Python environment with
pinned CMake/Ninja/ONNX tooling when needed, verifies model hashes and source pins,
and publishes stripped libraries. `--publish-java` optionally copies the exact
upstream API JARs from the Gradle AAR cache; it does not replace the custom natives.

Changing OCR models requires regenerating the operator config, rebuilding, and
checking recognition/click accuracy and 16 KB runtime again. Kernel removal is
not numerical quantization; the bundled tiny weights remain unchanged FP32 ONNX.

Runtime validation is performed on Pixel 6 arm64 and an official Android 15 arm64
16 KB AVD. Other ABIs are cross-compiled and checked for packaging/alignment.
OpenCV is Apache-2.0; ONNX Runtime is MIT. Upstream dependency notices are bundled
under the app's `assets/third_party` and licenses are retained in `licenses/`.
