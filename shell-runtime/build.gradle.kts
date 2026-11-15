plugins { id("com.android.application") }
android {
    namespace = "com.wukong.shell"
    compileSdk = 35
    defaultConfig { applicationId = "com.wukong.shell"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "0.2.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
