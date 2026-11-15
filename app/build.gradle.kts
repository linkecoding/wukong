import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.wukong.phone"
    buildFeatures { aidl = true }
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.wukong.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("targetAbi").orNull?.let { requestedAbi ->
            require(requestedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) { "Unsupported targetAbi" }
            ndk { abiFilters += requestedAbi }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.isFile) {
        val releaseKeys = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("production") {
            storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (signingFile.isFile) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    bundle { language { enableSplit = false } }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation(project(":agent-core"))
    implementation(project(":quickjs"))
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
