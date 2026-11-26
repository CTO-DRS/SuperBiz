pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // [OCR عربي] Tesseract4Android غير منشور على Central — JitPack مقيّد بمجموعته وحدها
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.adaptech-cz") }
        }
    }
}
rootProject.name = "SuperBiz"
include(":app")
