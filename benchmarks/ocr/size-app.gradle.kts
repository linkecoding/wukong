plugins { id("com.android.application"); kotlin("android") }
android {
 namespace="com.wukong.ocrbenchmark"; compileSdk=35
 defaultConfig { applicationId="com.wukong.ocrbenchmark"; minSdk=29; targetSdk=35; versionCode=1; versionName="1"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += "arm64-v8a" } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies {
 if (providers.gradleProperty("sizeEngine").get() !in listOf("baseline","mlkit")) implementation(project(":ppocr-sdk"))
 if (providers.gradleProperty("sizeEngine").get()=="mlkit") implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
