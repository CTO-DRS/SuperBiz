plugins {
    // [W1] AGP 8.9.2 — يدعم compileSdk/targetSdk 36 رسمياً (متطلب Google Play منذ 31/08/2026)
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
