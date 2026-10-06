package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.domain.LineTaxP41
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
 * [P42-R3] جولة 3 — جدار Compose UI للنسبة الصريحة للسطر (تفعيل القرار المؤجل في P41):
 * محرر الفاتورة يعرض حقل «نسبة السطر» للفئة القياسية فقط، ونصه ومشتقه الرقمية
 * يُكتبان معاً عبر setLineTaxRate فلا انفكاك بين المعروض والمحفوظ، والمعاينة
 * تدخل مسار السطر (عنوان «حسب السطور» الصادق) والحفظ يكتب النسبة المعلنة في
 * عمود v11 (invoice_items.taxRate) والضريبة من أسطره عبر LineTaxP41 نفسه.
 *
 * الأرقام المرجعية (بند 1×200.00 ريال = 20000 قروش، نسبة الرأس 15%):
 * • وراثة (فارغ) ⇒ الضريبة التاريخية على الرأس: round(20000×15/100) = 3000
 * • صريحة 5% ⇒ مسار السطر: round(20000×5/100) = 1000 — دليل قاطع على المسار
 *
 * حرس الاستطلاع (من محراب P40): خيط اختبار Robolectric هو خيط اللوبِر الرئيسي —
 * انتظار القاعدة عبر compose.waitUntil (يُصرّف اللوبِر) لا Thread.sleep الحاجب.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InvoiceLineRateP42Test {

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
        runBlocking { g.settings.snapshot() } // تسخين DataStore قبل التركيب
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        symbol = runBlocking { AppVM(app).symbol.first() } // رمز العملة الأساسية المبذورة
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

    private fun newVm(): InvoicesVM {
        val vm = InvoicesVM(app)
        vm.editorItems.value = listOf(
            InvoicesVM.EditorItem(null, "صنف نسبة", "1", "200")
        )
        return vm
    }

    @Test
    fun `كتابة نسبة صريحة تحدث حالة المحرر والمعاينة والعنوان الصادق معا`() {
        val vm = newVm()
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val rateLabel = app.getString(R.string.line_rate_label)
        scrollToText(rateLabel)
        compose.onNodeWithText(rateLabel).performTextInput("5")
        compose.runOnIdle {
            val item = vm.editorItems.value.single()
            assertEquals("النص المعروض محفوظ كما كُتب", "5", item.taxRateText)
            assertEquals("المشتقة الرقمية معلنة 5.0", 5.0, item.taxRatePct, 1e-9)
        }
        // مسار السطر: ضريبة 1000 قروش (وليس 3000 تاريخية) + العنوان الصادق
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(1_000L, symbol)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Money.formatP(1_000L, symbol)).assertExists()
        compose.onNodeWithText(Money.formatP(21_000L, symbol)).assertExists()
        compose.onNodeWithText(app.getString(R.string.tax_by_line)).assertExists()
        val historicLabel = app.getString(R.string.tax) + " (${Money.num(vm.editorTaxRate.value)}%)"
        compose.onNodeWithText(historicLabel).assertDoesNotExist()
        compose.onNodeWithText(Money.formatP(3_000L, symbol)).assertDoesNotExist()
    }

    @Test
    fun `النسبة الفارغة تبقي المسار التاريخي والعنوان بنسبة الرأس`() {
        val vm = newVm()
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        compose.runOnIdle {
            assertEquals("بلا نص ⇒ وراثة بعقد البذرة", LineTaxP41.RATE_INHERIT, vm.editorItems.value.single().taxRatePct, 1e-9)
        }
        // الحقل نفسه ظاهر للفئة القياسية الافتراضية
        compose.onNodeWithText(app.getString(R.string.line_rate_label)).assertExists()
        // المسار التاريخي: 3000 قروش بعنوان نسبة الرأس — لا عنوان السطور
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(3_000L, symbol)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Money.formatP(3_000L, symbol)).assertExists()
        val historicLabel = app.getString(R.string.tax) + " (${Money.num(vm.editorTaxRate.value)}%)"
        compose.onNodeWithText(historicLabel).assertExists()
        compose.onNodeWithText(app.getString(R.string.tax_by_line)).assertDoesNotExist()
        compose.onNodeWithText(Money.formatP(1_000L, symbol)).assertDoesNotExist()
    }

    @Test
    fun `مسح النص يعيد الوراثة والمعاينة للمسار التاريخي`() {
        val vm = newVm()
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val rateLabel = app.getString(R.string.line_rate_label)
        scrollToText(rateLabel)
        val field = compose.onNodeWithText(rateLabel)
        field.performTextInput("5")
        compose.runOnIdle {
            assertEquals(5.0, vm.editorItems.value.single().taxRatePct, 1e-9)
        }
        field.performTextClearance()
        compose.runOnIdle {
            val item = vm.editorItems.value.single()
            assertEquals("المسح يعيد النص فارغاً", "", item.taxRateText)
            assertEquals("والمشتقة تعود وراثةً بعقد البذرة", LineTaxP41.RATE_INHERIT, item.taxRatePct, 1e-9)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(3_000L, symbol)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Money.formatP(3_000L, symbol)).assertExists()
    }

    @Test
    fun `الحفظ يكتب نسبة السطر الصريحة والضريبة من أسطره عبر المحرك الواحد`() {
        val partyId = runBlocking { g.db.parties().upsert(Party(name = "عميل نسبة P42", phone = "050", type = 0)) }
        val vm = newVm()
        vm.editorType.value = 0
        runBlocking { vm.editorParty.value = g.db.parties().byId(partyId) }
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val rateLabel = app.getString(R.string.line_rate_label)
        scrollToText(rateLabel)
        compose.onNodeWithText(rateLabel).performTextInput("5")
        compose.onNodeWithText(app.getString(R.string.tax_by_line)).assertExists()
        scrollToText(app.getString(R.string.save))
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        compose.waitUntil(60_000) {
            runBlocking { g.db.invoices().allOnce().any { it.isSale } }
        }
        val inv = runBlocking { g.db.invoices().allOnce().single { it.isSale } }
        assertEquals("الضريبة من السطر 1000 (وليس تاريخية الرأس 3000) — دليل المسار", 1_000L, inv.taxAmount)
        assertEquals("الإجمالي 21000 = صافي 20000 + ضريبة السطر", 21_000L, inv.total)
        assertEquals(20_000L, inv.subtotal)
        assertEquals("نسبة الرأس تُحفظ كما هي (حقل تاريخي)", 15.0, inv.taxRate, 1e-9)
        val lines = runBlocking { g.db.invoiceItems().allOnce().filter { it.invoiceId == inv.id } }
        assertEquals("سطر واحد", 1, lines.size)
        val line = lines.single()
        assertEquals("النسبة المعلنة تُخزن في عمود v11", 5.0, line.taxRate, 1e-9)
        assertEquals("الفئة قياسية", LineTaxP41.KIND_STANDARD, line.taxKind)
        assertEquals(20_000L, line.unitPrice)
    }

    @Test
    fun `حقل النسبة يختفي للصفرية والمعفاة ويظهر للقياسية`() {
        val vm = newVm()
        compose.setContent { InvoiceEditor(vm, AppVM(app)) }
        val rateLabel = app.getString(R.string.line_rate_label)
        compose.onNodeWithText(rateLabel).assertExists()
        // الصفرية: لا مجال لنسبة عليهما (effectiveRate ⇒ 0 بلا استثناء)
        compose.runOnIdle {
            vm.editorItems.value = listOf(
                InvoicesVM.EditorItem(null, "صنف نسبة", "1", "200", taxKind = LineTaxP41.KIND_ZERO)
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(rateLabel).fetchSemanticsNodes().isEmpty()
        }
        // والمعفاة كذلك
        compose.runOnIdle {
            vm.editorItems.value = listOf(
                InvoicesVM.EditorItem(null, "صنف نسبة", "1", "200", taxKind = LineTaxP41.KIND_EXEMPT)
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(rateLabel).fetchSemanticsNodes().isEmpty()
        }
        // والعودة للقياسية تعيده — والنص المحفوظ سلفاً يظهر كما كُتب
        compose.runOnIdle {
            vm.editorItems.value = listOf(
                InvoicesVM.EditorItem(null, "صنف نسبة", "1", "200", taxRatePct = 5.0, taxRateText = "5")
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(rateLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(rateLabel).assertExists()
        assertTrue("النص المحفوظ معروض حرفياً", vm.editorItems.value.single().taxRateText == "5")
    }
}
