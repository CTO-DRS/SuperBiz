package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.Product
import com.superbiz.app.ui.screens.CheckoutSheet
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.PosVM
import kotlinx.coroutines.flow.first
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
 * [P40-M5] المرحلة 5 — أول جدار Compose UI في المشروع — مسار الدفع الحرجة:
 * CheckoutSheet (فكّ/دفع/فئات) فوق Robolectric-Compose بقاعدة ملفية حقيقية.
 *
 * يغطي عقد الواجهة الحقيقي للورقة:
 * • عرض سطر السلة والإجمالي الحي من vm.totals().
 * • الدفع النقدي: مبلغ ناقص ⇒ رسالة النقص الصادقة، ومبلغ زائد ⇒ سطر الباقي
 *   وعنوان «فكّ الباقي» ورقائق الفئات (×N <فئة>) من changeBreakdown.
 * • التأكيد النقدي المكتمل يكتب فاتورة بيع فعلية في القاعدة عبر vm.checkout()
 *   ويفرّغ السلة — الواجهة→VM→DB من طرف إلى طرف بلا جهاز أو محاكي.
 *
 * حارس الاستطلاع: خيط اختبار Robolectric هو خيط اللوبِر الرئيسي — أي انتظار
 * Thread.sleep يحجب مهام Dispatchers.Main ( viewModelScope/launchSafe) فيعلّق —
 * لذا كل انتظار لَمُّ في القاعدة عبر compose.waitUntil الذي يُصرّف اللوبِر.
 *
 * حارس الموضع [P33-P8]: كل المبالغ قروش Long — التوقعات تُبنى بنفس دوال
 * Money الإنتاجية (formatP/parseToPiasters/fromPiasters) لا بسلاسل يدوية.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CheckoutSheetP40Test {

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

    private fun seedProduct(name: String, sale: Long, cost: Long): Product {
        val pid = runBlocking {
            g.db.products().upsert(Product(name = name, salePrice = sale, costPrice = cost, stockQty = 9.0, unit = "قطعة"))
        }
        return runBlocking { g.db.products().byId(pid)!! }
    }

    @Test
    fun `الورقة تعرض سطر السلة والإجمالي الحي`() {
        val p = seedProduct("منتج دفع P40", 11_500L, 6_000L)
        val vm = PosVM(app)
        vm.addToCart(p)
        val t = runBlocking { vm.totals() }
        compose.setContent {
            CheckoutSheet(vm = vm, appVM = AppVM(app), symbol = "ر.س", onDismiss = {})
        }
        compose.onNodeWithText("منتج دفع P40").assertExists()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(t.total, "ر.س")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(Money.formatP(t.total, "ر.س")).assertExists()
    }

    @Test
    fun `نقد ناقص يظهر رسالة النقص وزائد يفك فئات الباقي`() {
        val p = seedProduct("منتج الفئات", 5_000L, 2_000L)
        val vm = PosVM(app)
        vm.addToCart(p)
        val t = runBlocking { vm.totals() } // 5750 قروش بضريبة 15٪
        compose.setContent {
            CheckoutSheet(vm = vm, appVM = AppVM(app), symbol = "ر.س", onDismiss = {})
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(t.total, "ر.س")).fetchSemanticsNodes().isNotEmpty()
        }
        val tenderedLabel = app.getString(R.string.pos_tendered)
        // مقبوض أقل من الإجمالي ⇒ رسالة النقص الصادقة
        compose.onNodeWithText(tenderedLabel).performTextInput("10")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.pos_insufficient)).fetchSemanticsNodes().isNotEmpty()
        }
        // مقبوض زائد بـ150.00 ريال ⇒ سطر الباقي + «فكّ الباقي» + رقائق ×N
        // (performTextInput يُلحق عند المؤشر — نُفرّغ الحقل أولاً صراحة)
        compose.onNodeWithText(tenderedLabel)
            .performTextReplacement(Money.fromPiasters(t.total + 15_000L).toString())
        val change = 15_000L
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(
                app.getString(R.string.pos_change, Money.formatP(change, "ر.س"))
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.pos_change_breakdown)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "رقائق الفئات ظاهرة: 150 ريال = ×1 100 + ×1 50 من changeBreakdown",
            compose.onAllNodesWithText("×1 100", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("×1 50", substring = true).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `تأكيد البيع النقدي يكتب فاتورة حقيقية ويفرغ السلة`() {
        val p = seedProduct("منتج إتمام", 20_000L, 12_000L)
        val vm = PosVM(app)
        vm.addToCart(p)
        val t = runBlocking { vm.totals() }
        compose.setContent {
            CheckoutSheet(vm = vm, appVM = AppVM(app), symbol = "ر.س", onDismiss = {})
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(Money.formatP(t.total, "ر.س")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.pos_tendered))
            .performTextInput(Money.fromPiasters(t.total).toString())
        // زر التأكيد أسفل ورقة أطول من نافذة Robolectric (320×470) — نمرّره لنطاق الرؤية أولاً
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText(app.getString(R.string.pos_confirm)))
        compose.onNodeWithText(app.getString(R.string.pos_confirm)).assertIsEnabled()
        compose.onNodeWithText(app.getString(R.string.pos_confirm)).performClick()
        // فاتورة بيع بقروش مطابقة تماماً وُلدت عبر vm.checkout() — استطلاع يُصرّف
        // اللوبِر بين القراءات ويوثق ما تراه القاعدة عند الفشل (تشخيص)
        val deadline = System.currentTimeMillis() + 60_000
        var rows: String = ""
        var saw = false
        while (System.currentTimeMillis() < deadline) {
            compose.runOnIdle {}
            runBlocking {
                val all = g.db.invoices().allOnce()
                rows = all.joinToString { "id=${it.id}:total=${it.total}:sale=${it.isSale}" }
                saw = all.any { it.isSale && it.total == t.total }
            }
            if (saw) break
            Thread.sleep(150)
        }
        assertTrue("فشل vm.checkout عبر الواجهة — rows=[$rows] cart=${vm.cart.value} t=${t.total}", saw)
        val deadline2 = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline2 && vm.cart.value.isNotEmpty()) {
            compose.runOnIdle {}
            Thread.sleep(150)
        }
        assertTrue("السلة تُفرَّغ بعد البيع — لا بيع مزدوج (cart=${vm.cart.value})", vm.cart.value.isEmpty())
    }
}
