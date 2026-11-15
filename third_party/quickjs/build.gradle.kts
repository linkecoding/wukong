plugins { id("com.android.library") }
android {
    namespace = "com.wukong.quickjs"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        providers.gradleProperty("targetAbi").orNull?.let { ndk { abiFilters += it } }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
