pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
    }
}
rootProject.name = "Wukong"
include(":agent-core", ":app", ":shell-runtime")
include(":adb-transport")
project(":adb-transport").projectDir = file("third_party/libadb")

include(":spake2")
project(":spake2").projectDir = file("third_party/spake2")

include(":paddleocr")
project(":paddleocr").projectDir = file("third_party/paddleocr")

include(":ocr-native")
project(":ocr-native").projectDir = file("third_party/ocr-native")

include(":quickjs")
project(":quickjs").projectDir = file("third_party/quickjs")

include(":standalone-runtime")
