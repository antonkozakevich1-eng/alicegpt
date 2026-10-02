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

        // Нативные библиотеки распознавания весят десятки мегабайт на архитектуру; эмуляторы x86 не нужны.
        // Облегчённая сборка только для 64-битных телефонов: ./gradlew assembleDebug -Pabis=arm64-v8a
        ndk {
            val abis = (findProperty("abis") as String?)?.split(",")?.map { it.trim() }
                ?: listOf("arm64-v8a", "armeabi-v7a")
            abiFilters += abis
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
        // JNI-обёртка sherpa-onnx связана только с onnxruntime; библиотеки C/C++ API приложению не нужны (≈5 МБ на архитектуру).
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    }

    testOptions {
        unitTests {
            // Robolectric запускает экран приложения на JVM (эмулятора в сборочной среде нет)
            isIncludeAndroidResources = true
        }
    }

    lint {
        // эмуляторы x86 и ChromeOS не нужны: нативные библиотеки весят десятки МБ на каждую архитектуру
        disable += "ChromeOsAbiSupport"
    }

    androidResources {
        // Модель — готовый zip; повторное сжатие только замедляет сборку.
        noCompress += "zip"
    }
}

dependencies {
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8@aar")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
    // в юнит-тестах android.jar — заглушки, поэтому настоящий org.json подключаем отдельно
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Модели распознавания не лежат в git. Что вшивается в APK, задаёт свойство bundleModel:
//   ./gradlew assembleDebug                      — нейросетевая модель (≈28 МБ): работает без интернета (по умолчанию);
//   ./gradlew assembleDebug -PbundleModel=false  — «лёгкий» APK без моделей: скачает модель при первом запуске;
//   -PbundleModel=vosk | all                     — вшить запасной Vosk (≈46 МБ) вместо нейросети / вместе с ней.
// Модель, которой нет в APK, приложение при необходимости скачивает само (см. NeuralModelStore, ModelInstaller).
val bundleModel = (findProperty("bundleModel") as String?) ?: "neural"
val bundleNeural = bundleModel == "neural" || bundleModel == "all"
val bundleVosk = bundleModel == "vosk" || bundleModel == "all"

// Нейросеть: файлы и размеры те же, что в NeuralModelStore.FILES (это проверяет юнит-тест).
class ModelFile(val name: String, val url: String, val size: Long)

val neuralFiles = listOf(
    ModelFile("encoder.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/encoder.int8.onnx", 26214060),
    ModelFile("decoder.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/decoder.onnx", 2093080),
    ModelFile("joiner.int8.onnx", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/joiner.int8.onnx", 259417),
    ModelFile("tokens.txt", "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/main/tokens.txt", 6388),
    ModelFile("unigram_500.vocab", "https://huggingface.co/alphacep/vosk-model-small-streaming-ru/resolve/main/lang/unigram_500.vocab", 8891),
)
val neuralAssetsRoot = layout.buildDirectory.dir("neural-assets").get().asFile
val neuralAssetsDir = File(neuralAssetsRoot, "neural-ru")

val downloadNeuralModel by tasks.registering {
    description = "Скачивает нейросетевую модель распознавания для вшивания в APK, если её ещё нет"
    outputs.dir(neuralAssetsDir)
    onlyIf { neuralFiles.any { File(neuralAssetsDir, it.name).length() != it.size } }
    doLast {
        neuralAssetsDir.mkdirs()
        for (f in neuralFiles) {
            val target = File(neuralAssetsDir, f.name)
            if (target.length() == f.size) continue
            val tmp = File(neuralAssetsDir, f.name + ".part")
            URL(f.url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() == f.size) { "${f.name}: скачано ${tmp.length()} байт из ${f.size}" }
            check(tmp.renameTo(target)) { "не удалось сохранить ${f.name}" }
        }
    }
}

// Запасной движок Vosk: модель — один zip (~46 МБ).
val voskModelName = "vosk-model-small-ru-0.22"
val voskAssetsDir = layout.buildDirectory.dir("vosk-assets").get().asFile
val voskModelZip = File(voskAssetsDir, "$voskModelName.zip")

val downloadVoskModel by tasks.registering {
    description = "Скачивает $voskModelName.zip для вшивания в APK, если его ещё нет"
    outputs.file(voskModelZip)
    onlyIf { !voskModelZip.exists() }
    doLast {
        val tmp = File(voskAssetsDir, "$voskModelName.zip.part")
        voskAssetsDir.mkdirs()
        URL("https://alphacephei.com/vosk/models/$voskModelName.zip").openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        check(tmp.length() > 40_000_000) { "Модель скачана не полностью: ${tmp.length()} байт" }
        tmp.renameTo(voskModelZip)
    }
}

if (bundleNeural) {
    android.sourceSets.getByName("main").assets.srcDir(neuralAssetsRoot)
    tasks.named("preBuild") { dependsOn(downloadNeuralModel) }
}
if (bundleVosk) {
    android.sourceSets.getByName("main").assets.srcDir(voskAssetsDir)
    tasks.named("preBuild") { dependsOn(downloadVoskModel) }
}
