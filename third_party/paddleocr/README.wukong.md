# Wukong bundled PaddleOCR

Source: PaddlePaddle/PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`, `deploy/ppocr-android/ppocr-sdk/src/main/java`. Copyright and Apache-2.0 notices are retained in source files.

Bundled models: official PP-OCRv6_tiny_det + PP-OCRv6_tiny_rec ONNX. Exact upstream download URLs and SHA-256 values are recorded in `models-manifest.json`. Model cards identify Apache-2.0:
- https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det
- https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec

Wukong changes: local Gradle module uses the app's toolchain, minSdk 29, locally trimmed ONNX Runtime 1.21.1 and OpenCV 4.14.0 via `../ocr-native` (upstream sample used QuickBird 4.5.3). Only tiny det/rec assets are bundled. Adapter configuration is in the app's ScreenOcr; inference runs locally without model download or external App.

Additional local change: `BitmapUtils.imdecodeBGR` uses Android BitmapFactory, removing the OpenCV imgcodecs dependency. Native source pins, build flags and hashes are in `../ocr-native`.
