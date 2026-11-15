# JNI implementations resolve these Java classes and fields by their original
# names. Keep their Java/native contracts while R8 trims other app dependencies.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.core.** { *; }
-keep class org.opencv.imgproc.** { *; }
-keep class org.opencv.android.Utils { *; }
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.crypto.spake2.** { *; }
