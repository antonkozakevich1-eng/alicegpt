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
        // Официальная Android-библиотека sherpa-onnx (нейросетевое распознавание) публикуется через JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
    }
}

rootProject.name = "TextFollower"
include(":app")
