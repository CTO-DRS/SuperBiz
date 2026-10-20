package com.superbiz.app.benchmark

/*
 * [E1.1 V 3.4.0] عين البداية الباردة — عمود cold_start_p50 في بوابة الميزانيات.
 * Cap: 2000ms على جهاز 4غ الطبقة (عقد E1.1 حرفياً).
 */
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** البارد الكامل — لا BaselineProfile بعد (يُضاف في تحسين لاحق بقياس قبل/بعد) */
    @Test
    fun coldStartCompilationNone() {
        benchmarkRule.measureRepeated(
            packageName = "com.superbiz.app",
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.None(),
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            // أول إطار على شاشة القفل/الرئيسية — عندها يكتمل StartupTimingMetric
            device.waitForIdle()
        }
    }
}
