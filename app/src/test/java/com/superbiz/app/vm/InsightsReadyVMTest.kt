package com.superbiz.app.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P39-M4-10] جدران علم الجاهزية لبطاقات الرؤى R11..R15 — إكمال دفعة الواجهة الثالثة.
 *
 * العقد المجرَّب (نمط R9/RInsightsVM الموحد منذ P36):
 *  - كل VM من العائلة يكشف `ready: StateFlow<Boolean>` علناً.
 *  - يبدأ false ويصير true بعد اكتمال أول loadAll() كاملة (حتى على قاعدة فارغة).
 *  - refresh() اليدوي لا يعيده إلى false — لا وميض سكيلتون بعد الجاهزية.
 *
 * حارس الواجهة (if (!ready) InsightCardSkeleton()) عقد تركيبي مطابق لبطاقات
 * R9/R10 المجرَّبة سلوكياً منذ P36 — هذا الملف يجبر عقد المصدر (الـVM) الذي
 * يعتمد عليه الحارس في الشاشات الأربعين.
 *
 * ═══ نمط الجهاز (إلزامي — نمط SearchHealthVMTest) ═══
 * - @Config(application = Application) يمنع إقلاع SuperBizApp الحقيقي.
 * - AppGraph.instance يُصفَّر بالانعكاس قبل كل اختبار (حقل نسخة على companion).
 * - Dispatchers.setMain(UnconfinedTestDispatcher) — launch في init يبدأ متحمساً
 *   حتى أول تعليق (withContext(Default) في loadAll) فيعود البناء فوراً وready
 *   ما زال false ثم يرتفع من خيط Default عند اكتمال الحساب — first{it} يلتقطه.
 * - قاعدة Room الملفية الفارغة مقصودة: العقد «جاهزية بعد أول جولة» لا يعتمد على البيانات.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class InsightsReadyVMTest {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        Dispatchers.setMain(testMain)
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun awaitReady(ready: StateFlow<Boolean>, label: String) {
        withTimeout(60_000L) { ready.first { it } }
        assertEquals("$label: ready يجب أن يكون true بعد أول جولة", true, ready.value)
    }

    @Test
    fun r11_readyFlowsFalseThenTrue_andRefreshNeverFlickers() = runBlocking {
        val vm = R11InsightsVM(app)
        assertFalse(vm.ready.value) // البناء يعود قبل اكتمال loadAll (withContext معلّق)
        awaitReady(vm.ready, "R11")
        vm.refresh()
        assertEquals("R11: refresh لا يُرجع السكيلتون", true, vm.ready.value)
    }

    @Test
    fun r12_readyAfterFirstCompute() = runBlocking {
        awaitReady(R12InsightsVM(app).ready, "R12")
    }

    @Test
    fun r13_readyAfterFirstCompute() = runBlocking {
        awaitReady(R13InsightsVM(app).ready, "R13")
    }

    @Test
    fun r14_readyAfterFirstCompute() = runBlocking {
        awaitReady(R14InsightsVM(app).ready, "R14")
    }

    @Test
    fun r15_readyAfterFirstCompute() = runBlocking {
        awaitReady(R15InsightsVM(app).ready, "R15")
    }
}
