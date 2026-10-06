package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.ui.screens.statement.StatementScreen
import com.superbiz.app.vm.AppVM
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P40-M5] المرحلة 5 — جدار Compose UI — مسار إصدار الكشف (StatementScreen):
 *
 * شاشة الإصدار تعتمد NavHostController فعلياً (عودة/انتقالات القوالب) وتُركّب
 * القاعدة الحية عبر StatementUiFacade — هنا نثبت عقد التركيب الأول: الشاشة
 * تُركَّب فوق قاعدة ملفية حقيقية بطرف مبذور، وتظهر ترويسة الطرف وزر
 * «إصدار وحفظ» (st_generate_save) وتنسيقات الفترة دون أي انهيار، ومع معاينة
 * PDF الحية (LivePdfPreview) التي تتحمل غياب التوليد بسلام (حالات
 * تحميل/خطأ صادقة — لا انهيار على Robolectric ولا على جهاز بلا خطوة).
 *
 * مسار الكتابة الفعلي للإصدار (assemble→renderPdf→issue) مغطى بعقود
 * StatementServiceTest/StatementDeliveryRowsP36Test على مستوى المستودع —
 * هنا الحارس التركيبي الذي كان غائباً كلياً (أول تركيب لهذه الشاشة في
 * تاريخ المشروع بلا جهاز).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StatementIssueP40Test {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var g: AppGraph
    private val testMain = UnconfinedTestDispatcher()

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        app = ApplicationProvider.getApplicationContext()
        g = AppGraph.from(app)
        // [P40-M5] Main غير مقيّد: مهام viewModelScope تعمل بلهفة على خيط
        // الاختبار وRoom يستأنف على منفّذه — لا انتظار لوبِر في بيئة Compose
        Dispatchers.setMain(testMain)
        runBlocking { g.settings.snapshot() } // تسخين DataStore قبل التركيب
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    @Test
    fun `شاشة الكشف تركب بترويسة الطرف وزر الإصدار بلا انهيار`() {
        val partyId = runBlocking {
            g.db.parties().upsert(Party(name = "طرف كشف P40", phone = "0555555555", type = 0))
        }
        compose.setContent {
            val nav = rememberNavController()
            StatementScreen(appVM = AppVM(app), partyId = partyId, nav = nav)
        }
        // ترويسة الطرف تصل عبر partyBrief (DB حقيقية) — انتظار التركيب الحي
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("طرف كشف P40").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("طرف كشف P40").assertExists()
        // زر الإصدار والحفظ في شريط الإجراءات أسفل الشاشة — يُركَّب داخل قائمة
        // تمرير عند وصول التمرير إليه، فنمرّر عبر كل حاويات التمرير أولاً
        val issueLabel = app.getString(R.string.st_generate_save)
        val scrollables = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        for (i in 0 until scrollables) {
            runCatching {
                compose.onAllNodes(hasScrollAction())[i].performScrollToNode(hasText(issueLabel))
            }
        }
        compose.onNodeWithText(issueLabel).assertExists()
    }
}
