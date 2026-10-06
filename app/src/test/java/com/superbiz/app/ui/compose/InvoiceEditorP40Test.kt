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
import com.superbiz.app.ui.components.parseNum
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
 * [P40-M5] المرحلة 5 — جدار Compose UI — مسار محرر الفواتير (خصم/بنود):
 * InvoiceEditor — المكوّن العام الأنظف بين المسارات الحرجة (بلا سياق ولا Nav).
 *
 * يغطي عقد المحرر الحقيقي:
 * • حفظ بلا طرف ⇒ رسالة err_party_required صادقة بدل الفشل الصامت [P7-L11].
 * • البنود الصالحة فقط تُحسب (نفس مرشّح الحفظ في المعاينة [R13-B6])، والخصم
 *   على مستوى البند يدخل في الصف المحفوظ (discount) والإجمالي.
 * • الحفظ الناجح يكتب فاتورة بيع فعلية بإجمالي مساوٍ تماماً لمعاينة
 *   vm.totals() (قروش Long — مساواة تامة بلا عتبة).
 *
 * حارس الاستطلاع: خيط اختبار Robolectric هو خيط اللوبِر الرئيسي — انتظار
 * القاعدة عبر compose.waitUntil (يُصرّف اللوبِر) لا Thread.sleep الحاجب.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InvoiceEditorP40Test {

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
        // [P40-M5] Main غير مقيّد: مهام viewModelScope تعمل بلهفة على خيط
        // الاختبار وRoom يستأنف على منفّذه — لا انتظار لوبِر في بيئة Compose
        Dispatchers.setMain(testMain)
        runBlocking { g.settings.snapshot() } // تسخين DataStore قبل التركيب
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        symbol = runBlocking { AppVM(app).symbol.first() } // رمز العملة الأساسية المبذورة
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }


    /** تمرير العقدة الهدف لنطاق الرؤية عبر أي حاوية تمرير تحتويها (المحرر يضم عدة حاويات) */
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
    fun `حفظ بلا طرف يظهر رسالة الطرف المطلوب بلا كتابة`() {
        val vm = InvoicesVM(app)
        vm.editorItems.value = listOf(InvoicesVM.EditorItem(null, "بند يتيم", "1", "100"))
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.err_party_required)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.err_party_required)).assertExists()
        compose.runOnIdle {}
        assertTrue("لا فاتورة تُكتب بلا طرف", runBlocking { g.db.invoices().allOnce().isEmpty() })
    }

    @Test
    fun `حفظ ببنود صالحة وخصم بند يكتب فاتورة مطابقة للمعاينة`() {
        val partyId = runBlocking { g.db.parties().upsert(Party(name = "عميل فاتورة P40", phone = "050", type = 0)) }
        val vm = InvoicesVM(app)
        vm.editorType.value = 0
        runBlocking { vm.editorParty.value = g.db.parties().byId(partyId) }
        // بند بخصم على مستوى السطر: 2×100.00 − 10.00 = 190.00 ريال، وبند ثانٍ 1×50.50
        vm.editorItems.value = listOf(
            InvoicesVM.EditorItem(null, "صنف مخصوم", "2", "100", "10"),
            InvoicesVM.EditorItem(null, "صنف عادي", "1", "50.5")
        )
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        // المعاينة بنفس دالة الحفظ: sub بلا خصم (25050)، الخصم 1000، الصافي 24050
        val (sub, tax, total) = vm.totals(
            vm.editorItems.value.filter { it.desc.isNotBlank() && parseNum(it.qty) > 0.0 },
            vm.editorTaxRate.value
        )
        assertEquals("sub هو مجموع الأسطر قبل الخصم", 25_050L, sub)
        compose.onNodeWithText(Money.formatP(total, symbol)).assertExists()
        // زر الحفظ أسفل لوحة أطول من نافذة Robolectric — تمرير قبل النقر
        scrollToText(app.getString(R.string.save))
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(60_000) {
            runBlocking { g.db.invoices().allOnce().any { it.isSale } }
        }
        val inv = runBlocking { g.db.invoices().allOnce().single { it.isSale } }
        assertEquals("الإجمالي المحفوظ == المعاينة — مساواة قروش تامة", total, inv.total)
        assertEquals(sub, inv.subtotal)
        assertEquals(tax, inv.taxAmount)
        assertEquals("خصم البند 10.00 ريال = 1000 قروش يدخل الصف فعلاً", 1_000L, inv.discount)
        assertEquals(partyId, inv.partyId)
        val lines = runBlocking { g.db.invoiceItems().allOnce().filter { it.invoiceId == inv.id } }
        assertEquals("بندان محفوظان", 2, lines.size)
    }

    @Test
    fun `زر إضافة بند يلحق سطراً فارغاً جديداً`() {
        val vm = InvoicesVM(app)
        vm.editorItems.value = emptyList()
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val addLabel = app.getString(R.string.add_item)
        scrollToText(addLabel, substring = true)
        compose.onNodeWithText(addLabel, substring = true).performClick()
        compose.waitUntil(10_000) { vm.editorItems.value.size == 1 }
        scrollToText(addLabel, substring = true)
        compose.onNodeWithText(addLabel, substring = true).performClick()
        compose.waitUntil(10_000) { vm.editorItems.value.size == 2 }
        assertEquals("سطران فارغان جاهزان للتحرير", 2, vm.editorItems.value.size)
    }
}
