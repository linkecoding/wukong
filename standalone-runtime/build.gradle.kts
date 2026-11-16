import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.wukong.phone"
    compileSdk = 35
    defaultConfig { applicationId = "com.wukong.standalone"; minSdk = 29; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir("../shell-runtime/src/main/java")
}
dependencies { implementation(project(":agent-core")) }
val shared = layout.buildDirectory.dir("generated/shared")
val shareRuntimeSources by tasks.registering(Copy::class) {
    from("../app/src/main/java/com/wukong/phone") {
        include("ChatModel.kt", "SettingsStore.kt", "ModelPresets.kt", "ModelCatalog.kt", "TaskEvidence.kt", "InspectionJson.kt", "ShellClient.kt")
    }
    into(shared)
}
android.sourceSets.getByName("main").java.srcDir(shared)
tasks.named("preBuild").configure { dependsOn(shareRuntimeSources) }

val identityDirectory = layout.buildDirectory.dir("generated/identity")
val generateRuntimeIdentity by tasks.registering {
    val sources = files(fileTree("src"), fileTree("../shell-runtime/src/main/java"), fileTree("../agent-core/src/main"),
        fileTree("../app/src/main/java/com/wukong/phone") { include("ChatModel.kt", "SettingsStore.kt", "ModelPresets.kt", "ModelCatalog.kt", "TaskEvidence.kt", "InspectionJson.kt", "ShellClient.kt") })
    inputs.files(sources)
    outputs.dir(identityDirectory)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        sources.files.sortedBy { it.relativeTo(rootDir).path }.forEach { digest.update(it.relativeTo(rootDir).path.toByteArray()); digest.update(it.readBytes()) }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        identityDirectory.get().file("RuntimeBuildId.kt").asFile.apply { parentFile.mkdirs(); writeText("package com.wukong.phone\ninternal object RuntimeBuildId { const val VALUE = \"$hash\" }\n") }
    }
}
android.sourceSets.getByName("main").java.srcDir(identityDirectory)
tasks.named("preBuild").configure { dependsOn(generateRuntimeIdentity) }
