/*
 * [E1.1 V 3.4.0] وحدة Macrobenchmark — تُفعَّل عند توفر مصفوفة الأجهزة (E0.3)
 *
 * التفعيل: انسخ هذا المجلد إلى benchmark/ في جذر المستودع وأضف
 * `include(":benchmark")` إلى settings.gradle.kts ثم
 * `./gradlew :benchmark:connectedCheck` على جهاز حقيقي 4غ.
 * لا تدخل شيفرة غير مبنية رسم البناء — عقد docs/PERF_HARNESS.md.
 */
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.superbiz.app.benchmark"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.benchmark.AndroidBenchmarkRunner"
    }

    // مقياس يواجه app بمتغير benchmark (R8 مطفأ التصحيح — عقد القياس)
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
