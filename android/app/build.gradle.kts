import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alicegpt.textfollower"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alicegpt.textfollower"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Нативные библиотеки Vosk весят по ~10 МБ на архитектуру; эмуляторы x86 не нужны.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подпись отладочным ключом, чтобы release-APK тоже можно было просто установить.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // JNA (через неё Vosk грузит libvosk.so) надёжнее работает с распакованными библиотеками.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests {
            // Robolectric запускает экран приложения на JVM (эмулятора в сборочной среде нет)
            isIncludeAndroidResources = true
        }
    }

    lint {
        // эмуляторы x86 и ChromeOS не нужны: Vosk весит ~10 МБ на каждую архитектуру
        disable += "ChromeOsAbiSupport"
    }

    androidResources {
        // Модель — готовый zip; повторное сжатие только замедляет сборку.
        noCompress += "zip"
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    // в юнит-тестах android.jar — заглушки, поэтому настоящий org.json подключаем отдельно
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Офлайн-модель распознавания (~46 МБ) не хранится в git: при первой сборке скачивается и кладётся в assets.
val voskModelName = "vosk-model-small-ru-0.22"
val voskModelZip = layout.projectDirectory.file("src/main/assets/$voskModelName.zip").asFile

val downloadVoskModel by tasks.registering {
    description = "Скачивает $voskModelName.zip в assets, если его там нет"
    outputs.file(voskModelZip)
    onlyIf { !voskModelZip.exists() }
    doLast {
        val tmp = File(voskModelZip.parentFile, "$voskModelName.zip.part")
        voskModelZip.parentFile.mkdirs()
        URL("https://alphacephei.com/vosk/models/$voskModelName.zip").openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        check(tmp.length() > 40_000_000) { "Модель скачана не полностью: ${tmp.length()} байт" }
        tmp.renameTo(voskModelZip)
    }
}

tasks.named("preBuild") { dependsOn(downloadVoskModel) }
