package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.ui.screens.InvoiceEditor
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.InvoicesVM
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [V3.5.2 إصلاح بلاغ المالك] السطر اليدوي في محرر الفواتير — بلاغ حقيقي بلقطة شاشة:
 * «لماذا يقول: أضف بنداً واحداً على الأقل بوصف وكمية أكبر من صفر؟» رغم أن الكمية
 * (22) والسعر (600) مُدخلان والمعاينة تعرض 13,200.00.
 *
 * السبب الجذري الموثق: الوصف كان يُضبط من منتقي الأصناف حصراً (نقر العنوان يفتح
 * المنتقي) — لا حقل نص حر لإدخاله — بينما بوابة الحفظ والمعاينة تشترطان
 * desc.isNotBlank فسطر يدوي بلا صنف محفوظ لا سبيل لحفظه أصلاً.
 *
 * العقد بعد الإصلاح:
 * • حقل وصف نص حر لكل سطر يُحفظ حرفياً كما كُتب.
 * • الكمية > 0 هي شرط الصلاحية الوحيد؛ الوصف الفارغ يُسمّى تلقائياً عند الحفظ
 *   بنفس تسمية العرض الاحتياطية («الصنف N») فلا يهبط اسم فارغ للمستودع ولا للـPDF
 *   ولا لـXML زاتكا، والمعاينة بنفس المرشّح فمساواة تامة بين المعروض والمحفوظ.
 * • الكمية صفر/فارغة تبقى مرفوضة برسالة البنود المطلوبة — لا فاتورة صمتية.
 *
 * ملاحظة أرشيفية: هذه الحزمة أُنجزت أصلاً كـV3.6.1 فوق V3.6.0 في جلسة سابقة
 * فأفقدَها تراجع بيئي قبل الدفع — أعيد بناؤها هنا حرفياً فوق V3.5.1 (origin/main
 * dafb8c4) وتُرقَّم V3.5.2، وموجات V3.6.0 ستعاد فوقها لاحقاً بنفس العقود.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InvoiceFreeLineTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var g: AppGraph
    private val testMain = UnconfinedTestDispatcher()
    private lateinit var symbol: String

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
        Dispatchers.setMain(testMain)
        runBlocking { g.settings.snapshot() }
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        symbol = runBlocking { AppVM(app).symbol.first() }
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    /** تمرير العقدة الهدف لنطاق الرؤية عبر أي حاوية تمرير تحتويها (نمط P40) */
    private fun scrollToText(label: String, substring: Boolean = false) {
        val count = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            runCatching {
                compose.onAllNodes(hasScrollAction())[i]
                    .performScrollToNode(hasText(label, substring = substring))
            }
        }
    }

    @Test
    fun `سطر يدوي بلا وصف بكمية وسعر يُحفظ باسم افتراضي ومعاينة مطابقة`() {
        val partyId = runBlocking { g.db.parties().upsert(Party(name = "عميل سطر حر", phone = "0501", type = 0)) }
        val vm = InvoicesVM(app)
        vm.editorType.value = 0
        runBlocking { vm.editorParty.value = g.db.parties().byId(partyId) }
        // بلاغ المالك حرفياً: كمية 22 × سعر 600 — بلا صنف مختار ولا وصف مُدخل
        vm.editorItems.value = listOf(InvoicesVM.EditorItem(null, "", "22", "600"))
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        // المعاينة تعتبر السطر صالحاً: 13,200.00 + 15% = 15,180.00 ريال = 1,518,000 قروش
        compose.onNodeWithText(Money.formatP(1_518_000L, symbol)).assertExists()
        scrollToText(app.getString(R.string.save))
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(60_000) {
            runBlocking { g.db.invoices().allOnce().any { it.isSale } }
        }
        val inv = runBlocking { g.db.invoices().allOnce().single { it.isSale } }
        assertEquals("الإجمالي المحفوظ == المعاينة — مساواة قروش تامة", 1_518_000L, inv.total)
        assertEquals(1_320_000L, inv.subtotal)
        assertEquals(198_000L, inv.taxAmount)
        assertEquals(partyId, inv.partyId)
        val lines = runBlocking { g.db.invoiceItems().allOnce().filter { it.invoiceId == inv.id } }
        assertEquals("سطر واحد محفوظ لا يسقط صامتاً", 1, lines.size)
        assertEquals(
            "الاسم الافتراضي بنفس تسمية العرض الاحتياطية — لا اسم فارغ في المستودع/PDF/زاتكا",
            app.getString(R.string.item) + " 1", lines[0].desc
        )
    }

    @Test
    fun `حقل وصف البند يقبل نصاً حراً ويُحفظ حرفياً`() {
        val partyId = runBlocking { g.db.parties().upsert(Party(name = "عميل وصف حر", phone = "0502", type = 0)) }
        val vm = InvoicesVM(app)
        vm.editorType.value = 0
        runBlocking { vm.editorParty.value = g.db.parties().byId(partyId) }
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val descLabel = app.getString(R.string.item)
        scrollToText(descLabel)
        compose.onNodeWithText(descLabel).performTextInput("زيت محرك 5 لتر")
        scrollToText(app.getString(R.string.qty))
        compose.onNodeWithText(app.getString(R.string.qty)).performTextInput("2")
        scrollToText(app.getString(R.string.price))
        compose.onNodeWithText(app.getString(R.string.price)).performTextInput("75")
        scrollToText(app.getString(R.string.save))
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(60_000) {
            runBlocking { g.db.invoices().allOnce().any { it.isSale } }
        }
        val inv = runBlocking { g.db.invoices().allOnce().single { it.isSale } }
        val lines = runBlocking { g.db.invoiceItems().allOnce().filter { it.invoiceId == inv.id } }
        assertEquals("الوصف الحر يُحفظ حرفياً كما كُتب", "زيت محرك 5 لتر", lines[0].desc)
        assertEquals(2.0, lines[0].qty, 0.0)
        assertEquals("75.00 ريال = 7500 قروش", 7_500L, lines[0].unitPrice)
    }

    @Test
    fun `سطر بكمية صفر يبقى مرفوضاً برسالة البنود المطلوبة`() {
        val partyId = runBlocking { g.db.parties().upsert(Party(name = "عميل صفر", phone = "0503", type = 0)) }
        val vm = InvoicesVM(app)
        vm.editorType.value = 0
        runBlocking { vm.editorParty.value = g.db.parties().byId(partyId) }
        vm.editorItems.value = listOf(InvoicesVM.EditorItem(null, "بند بلا كمية", "0", "50"))
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        scrollToText(app.getString(R.string.save))
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.err_items_required)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.err_items_required)).assertExists()
        compose.runOnIdle {}
        assertTrue("لا فاتورة تُكتب بكمية صفر", runBlocking { g.db.invoices().allOnce().isEmpty() })
    }
}
