plugins { id("com.android.library") }
android {
    namespace = "org.opencv"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
}
dependencies { api(files("libs/opencv-java-4.14.0.jar", "libs/onnxruntime-java-1.21.1.jar")) }
