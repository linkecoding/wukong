plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.wukong.phone"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.wukong.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("targetAbi").orNull?.let { requestedAbi ->
            require(requestedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) { "Unsupported targetAbi" }
            ndk { abiFilters += requestedAbi }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation(project(":agent-core"))
    implementation(project(":adb-transport"))
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation(project(":paddleocr"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val shellAssets = layout.buildDirectory.dir("generated/shellAssets")
val bundleShellRuntime by tasks.registering(Copy::class) {
    dependsOn(":shell-runtime:assembleDebug")
    from(project(":shell-runtime").layout.buildDirectory.file("outputs/apk/debug/shell-runtime-debug.apk"))
    into(shellAssets)
    rename { "wukong-shell.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(shellAssets)
tasks.named("preBuild").configure { dependsOn(bundleShellRuntime) }
