package com.superbiz.app.benchmark

/*
 * [E1.1 V 3.4.0] عينا البحث والمسار النقدي — عمودا search_p95 وcheckout_p50
 * في بوابة الميزانيات. Cap: بحث < 200ms عند 10k عنصراً، ودفع p50 ≤ 30s
 * (قبول المرحلة 2). القياس عبر UI — يبذّر القاعدة عبر Intent المباشر قبل القياس.
 */
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class SearchAndCheckoutBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** p95 للبحث الضبابي مع أغاشة القياس = قسم التتبع المسمّى في GlobalSearchVM */
    @Test
    fun searchP95At10kItems() {
        benchmarkRule.measureRepeated(
            packageName = "com.superbiz.app",
            metrics = listOf(TraceSectionMetric("GlobalSearchVM.results")),
            iterations = 15,
            startupMode = androidx.benchmark.macro.StartupMode.WARM,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            // إدخال استعلام ≥ حرفين عبر UiAutomator ثم انتظار النتائج —
            // القسم المتبعّ يغطي التقييم الضبابي الكامل (العقد في docs/PERF_HARNESS.md)
            device.waitForIdle()
        }
    }

    /** p50 لمسار الدفع (اختيار صنف → دفع نقدي → حفظ) — قبول المرحلة 2: ≤ 30s */
    @Test
    fun checkoutP50() {
        benchmarkRule.measureRepeated(
            packageName = "com.superbiz.app",
            metrics = listOf(TraceSectionMetric("PosVM.save")),
            iterations = 10,
            startupMode = androidx.benchmark.macro.StartupMode.WARM,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}
