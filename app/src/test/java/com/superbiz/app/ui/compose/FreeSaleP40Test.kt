package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.ui.screens.FreeSaleDialog
import com.superbiz.app.vm.PosVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P40-M5] المرحلة 5 — جدار Compose UI — مسار «بيع حر» في نقطة البيع:
 *
 * • القسم التركيبي (FreeSaleDialog): الحوار المستخرج حرفياً من PosScreen —
 *   مبلغ غير صالح ⇒ تأكيد معطّل + رسالة الفشل الصادقة؛ مبلغ صالح + وصف ⇒
 *   onSale يستقبل المدخلات كما كُتبت بعد إغلاق الحوار (نفس الترتيب الأصلي).
 *
 * • القسم النهائي (FreeSaleVmE2E): vm.quickSale الفعلي على قاعدة ملفية حقيقية
 *   (نمط P37: Main غير مقيّد) — فاتورة بيع بقروش مطابقة تماماً (125.50 ريال
 *   = 12550 قروش)، مفتوحة بالكامل، بسطر بيع حر مركب من PosCart.freeSaleLine.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FreeSaleP40Test {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var g: AppGraph

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
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        resetGraph()
    }

    @Test
    fun `مبلغ غير صالح يعطل التأكيد ويعرض الفشل الصادق`() {
        var sold: Pair<String, String>? = null
        compose.setContent {
            FreeSaleDialog(onDismiss = {}, onSale = { a, d -> sold = a to d })
        }
        compose.onNodeWithText(app.getString(R.string.pos_free_amount)).performTextInput("0")
        compose.onNodeWithText(app.getString(R.string.pos_free_amount_invalid)).assertExists()
        val confirmNode = compose.onNodeWithText(app.getString(R.string.confirm)).fetchSemanticsNode()
        assertTrue("زر التأكيد معطّل فعلاً بمبلغ غير صالح", confirmNode.config.getOrNull(SemanticsProperties.Disabled) != null)
        compose.runOnIdle {}
        assertEquals("بلا بيع بمبلغ غير صالح — الزر معطّل فلا نقر ممكن", null, sold)
    }

    @Test
    fun `مبلغ ووصف صالحان يسلمان المدخلات كما كتبت بعد الإغلاق`() {
        var sold: Pair<String, String>? = null
        var dismissed = false
        compose.setContent {
            FreeSaleDialog(onDismiss = { dismissed = true }, onSale = { a, d -> sold = a to d })
        }
        compose.onNodeWithText(app.getString(R.string.pos_free_amount)).performTextInput("125.5")
        compose.onNodeWithText(app.getString(R.string.pos_free_sale_desc)).performTextInput("جولة P40")
        compose.onNodeWithText(app.getString(R.string.confirm)).performClick()
        compose.runOnIdle {}
        assertEquals("المبلغ يُمرر كما كُتب (التحويل في VM)", "125.5", sold?.first)
        assertEquals("الوصف يُمرر كما كُتب", "جولة P40", sold?.second)
        assertTrue("الحوار يُغلق قبل البيع — نفس الترتيب الأصلي", dismissed)
    }
}

/**
 * [P40-M5] نهاية المسار: vm.quickSale يكتب فاتورة بيع حر حقيقية (VM→DB).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FreeSaleVmE2E {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph

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
        g = AppGraph.from(app)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    @Test
    fun `quickSale يكتب فاتورة بيع حر بمبلغ قروش مطابق ويفرغ السلة`() = runBlocking {
        val vm = PosVM(app)
        vm.quickSale("125.5", "بيع حر P40")
        // [تدقيق M-10] انتظار محدد المهلة مع استنزاف مجدول التركيب كل دورة —
        // (waitUntil لا يقبل استدعاءات DAO suspend في لامدته؛ النمط الموثق للعقد الدلالية فقط)
        val invDeadline = System.currentTimeMillis() + 60_000
        while (g.db.invoices().allOnce().none { it.isSale }) {
            assertTrue("انتهت مهلة انتظار فاتورة البيع الحر (M-10)", System.currentTimeMillis() < invDeadline)
            Thread.sleep(100)
        }
        val inv = g.db.invoices().allOnce().single { it.isSale }
        assertEquals("المبلغ الحر 125.50 ريال = 12550 قروش — الأساس قبل الضريبة [P33-P8]", 12_550L, inv.subtotal)
        // POS يطبق ضريبة الإعدادات (15٪ افتراضياً) — الإجمالي جمع صحيح تام
        val expectedTax = Math.round(12_550L * inv.taxRate / 100.0)
        assertEquals("الضريبة = Math.round(الصافي × النسبة/100)", expectedTax, inv.taxAmount)
        assertEquals(12_550L + expectedTax, inv.total)
        // البيع الحر نقدي — يُحصَّل داخل معاملة الحفظ نفسها [ R6]
        // مدفوع بالكامل (open=0) لا ذمّة معلّقة
        assertEquals("البيع الحر نقدي محصّل فوراً — open=0", 0L, inv.open)
        assertEquals("المقبوض == الإجمالي", inv.total, inv.paid)
        assertEquals("سطر البيع الحر برقم منتج null", null, g.db.invoiceItems().allOnce().single { it.invoiceId == inv.id }.productId)
        assertTrue("السلة تفرغ بعد البيع الحر", vm.cart.value.isEmpty())
        assertFalse(vm.lastSale.value <= 0L)
    }
}
