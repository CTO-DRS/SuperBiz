import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // [P40-M5] تقرير تغطية أسماء (خفيف، بلا بوابة فشل) — المرحلة 5
    id("jacoco")
}

// بيانات التوقيع من keystore.properties (خارج الكود) — انسخ keystore.properties.example
// وأضف مسار مخزن مفاتيحك وكلمات السر، أو اتركه غائباً وسيُبنى الإصدار غير الموقّع.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.superbiz.app"
    // [W1] compileSdk 36 — أدوات بناء متوافقة (AGP 8.9.2+)
    compileSdk = 36

    defaultConfig {
        applicationId = "com.superbiz.app"
        minSdk = 24
        // [W1] targetSdk 36 — متطلب Google Play منذ 31/08/2026 (edge-to-edge إجباري
        // بلا خيار الاستبعاد: windowOptOutEdgeToEdgeEnforcement مُهمَل عند استهداف 36 —
        // المعالج القائم enableEdgeToEdge() في MainActivity + الحشوات في الشاشات يغطي ذلك)
        targetSdk = 36
        // ─── SuperBiz V 1.1.0 — Audit Remediation Release ─────────────────────────
        // موجة إصلاح التدقيق الكامل (25 ملاحظة: 7 HIGH و10 MEDIUM و8 LOW — كلها مغلقة):
        // دمج يحفظ الولاء والكشوف، نسخ احتياطي بلقطة متسقة، رابط تحقق QR حي،
        // تصدير XLSX وحدات موحدة، خزنة SMTP بمفتاح Keystore، TLS بهوية نقطة نهاية،
        // ذرية إصدار/تسليم الكشوف، تراجع أُسّي مجدول، اتجاه دين صريح للأدوار المزدوجة،
        // سياسة يتامى موثقة، بيومتريا CryptoObject وقفل زمن أحادي — 1471 اختبار وحدة أخضر.
        // الإصدار يُعرض للمستخدم حصريًا من
        // BuildConfig.VERSION_NAME / VERSION_CODE (الإعدادات + تذييل PDF).
        versionCode = 8
        versionName = "3.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        // توليد BuildConfig — مصدر وحيد للإصدار في تذييل PDF
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProps.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // lintVital لنسخة الإصدار يعطَّل في بيئة البناء محدودة القرص فقط —
    // (يتطلب تنزيل حزم Lint ثقيلة ~200MB). التحقق الكامل lint يبقى واجباً على جهاز
    // المالك قبل رفع AAB للمتجر: ./gradlew :app:lintRelease
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        // توقيع نسخ التطوير بنفس المفتاح المستقر — ثبات الشهادة عبر الجلسات (فوق-تثبيت آمن)
        // [P20-FIX agent20]: حارس keystore — كان يفشل على جهاز بلا keystore.properties
        debug {
            signingConfig = if (keystoreProps.getProperty("storeFile") != null)
                signingConfigs.getByName("release") else null
        }
        release {
            // [P9-9b-R8] تفعيل تصغير R8 وموارد الشرينك — تقليل الحجم وحماية إضافية؛
            // قواعد الاحتفاظ المحافظة في proguard-rules.pro (قواعد AGP الافتراضية تغطي Compose/Room/Coroutines)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // يُوقّع فقط عند توفر keystore.properties — وإلا يُبنى غير موقّع (توقيعه على مطوّرك)
            signingConfig = if (keystoreProps.getProperty("storeFile") != null)
                signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // [P40-M5] سقف صريح لكومة JVM المفروضة للاختبارات على المضيف 4GB —
        // كانت الوفاة الصامتة (OOM من النواة) لحظة تفرّع جهاز الاختبار مع
        // جدار Compose الثقيل (Robolectric + ui-test + work-testing)
        unitTests.all {
            it.maxHeapSize = "1024m"
            it.jvmArgs("-XX:MaxMetaspaceSize=320m")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // [W1] Play Billing v7 — فريميوم Pro بلا خادم (شراء لمرة واحدة + استعادة)
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // [Z2-ب V 1.5.0] org.json الحقيقي على JVM — كي تعمل اختبارات خريطة واجهات
    // فاتورة النقية (ZatcaApi) بلا Robolectric، وتبعية اختبارية حصراً صفر أثر APK
    testImplementation("org.json:json:20240303")
    // اختبارات مسارات المعاملات على قاعدة بيانات Room حقيقية
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    // [P40-M5] المرحلة 5 — جدران الحماية المكتملة:
    // Robolectric-Compose للمسارات الحرجة الخمسة (CheckoutSheet/حوار الطرف 12 حقلاً/
    // شاشة الكشف/محرر الفواتير/البيع الحر) بلا جهاز أو محاكي
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // اختبارات العمال الحقيقية: TestListenableWorkerBuilder + WorkManagerTestInitHelper
    testImplementation("androidx.work:work-testing:2.9.1")
}

// [P40-M5] تقرير تغطية أسماء لاختبارات unit (release) — خفيف وبلا بوابة فشل:
// يُنتج HTML/XML في build/reports/jacoco ولا يفشل البناء على أي نسبة.
tasks.register("jacocoUnitTestReport", JacocoReport::class) {
    dependsOn("testReleaseUnitTest")
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(files(
        fileTree(layout.buildDirectory.dir("intermediates/javac/release")),
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/release"))
    ))
    executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") })
}

// تصدير مخطط Room إلى JSON — انحراف المخطط أصبح
// قابلاً للكشف وقت البناء، و5.json مرجع اختبارات الترحيل (كان مخططاً بلا مرجع)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
