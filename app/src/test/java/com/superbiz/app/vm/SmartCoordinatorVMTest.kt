package com.superbiz.app.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.domain.ProEntitlement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [H3-6] اختبارات المنسّق الذكي (Robolectric) — SmartCoordinatorVM على قاعدة
 * Room ملفية حقيقية بنمط الجهاز المعتمد في DebtsInventoryVMTest حرفياً:
 *
 * ═══ نمط الجهاز الافتراضي (إلزامي لتجنب تمهيد SuperBizApp وتسريب الـsingleton) ═══
 * - @Config(application = android.app.Application::class) — بلا قنوات/بذر إقلاع.
 * - صفّر AppGraph.instance بالانهكاس في @Before (انعكاس على حقل الـcompanion).
 * - Dispatchers.setMain(UnconfinedTestDispatcher) يجعل viewModelScope يبدأ فوراً.
 * - الجمع بـ runBlocking + withTimeout + first{مسند نهائي} — تقارب مضمون.
 *
 * البوابات المختبرة: الدردشة المحلية الحتمية (مبيعات/خارج النطاق/موضوع صنف/
 * ملخص مخزون) + باب Pro الجديد SMART_INTEL (H3-7) — قفله الافتراضي الصادق.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmartCoordinatorVMTest {

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
        app = ApplicationProvider.getApplicationContext<Application>()
        g = AppGraph.from(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun seed(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val partyId = g.ledger.saveParty(Party(name = "شركة الافق", type = 0))
        val productId = g.db.products().upsert(
            Product(name = "حليب المراع", stockQty = 12.0, reorderLevel = 5.0,
                costPrice = 300L, salePrice = 500L)
        )
        g.db.invoices().upsert(
            Invoice(number = "S-1", partyId = partyId, type = 0, date = now,
                dueDate = now + 14L * 86_400_000L, subtotal = 50000L, total = 50000L)
        )
        return partyId to productId
    }

    private fun newVM(): SmartCoordinatorVM = SmartCoordinatorVM(app)

    // ═════════ الجاهزية والبطاقات ═════════

    @Test
    fun ready_becomesTrueAndHonestWithoutFabrication() = runBlocking {
        seed()
        val vm = newVM()
        withTimeout(60_000) { vm.ready.first { it } }
        val home = vm.home.first { true }
        // بلا حركة كافية ⇒ بطاقة النقد تختفي (صدق الفراغ) — لا إنذار جفاف مزيّف
        assertTrue(home.dryDayWeek == null || home.monthEnd != null)
        // بلا مبيعات متكررة كافية ⇒ لا توصيات إعادة طلب وهمية
        // (فاتورة واحدة لا تصنع متوسطاً يُجدي — القائمة إما فارغة أو حقيقية)
        home.reorders.forEach { r ->
            assertTrue(r.rop > 0.0)
            assertTrue(r.daysOfSafety <= 7.0)
        }
    }

    // ═════════ الدردشة المحلية الحتمية ═════════

    @Test
    fun chat_salesQuestionAnswersFromLedger() = runBlocking {
        seed()
        val vm = newVM()
        withTimeout(60_000) { vm.ready.first { it } }
        vm.ask("كم مبيعات اليوم؟")
        val msg = withTimeout(60_000) {
            vm.chat.first { it.isNotEmpty() && it.last().answer?.key != null } .last()
        }
        assertEquals("ans_sales", msg.answer!!.key)
        // الأساس ظاهر — لا جواب بلا مصدر (بوابة H3-6)
        assertTrue(msg.answer!!.basis.isNotEmpty())
        assertEquals(500.0, msg.answer!!.numbers.getOrElse(0) { -1.0 }, 1e-9)
    }

    @Test
    fun chat_outOfScopeFailsPolitely() = runBlocking {
        seed()
        val vm = newVM()
        withTimeout(60_000) { vm.ready.first { it } }
        vm.ask("كيف الطقس اليوم؟")
        val msg = withTimeout(60_000) {
            vm.chat.first { it.isNotEmpty() && it.last().answer?.key != null }.last()
        }
        assertEquals("ans_nodata", msg.answer!!.key)
    }

    @Test
    fun chat_productSubjectWinsOverGenericIntent() = runBlocking {
        seed()
        val vm = newVM()
        withTimeout(60_000) { vm.ready.first { it } }
        vm.ask("مخزون حليب المراع")
        val msg = withTimeout(60_000) {
            vm.chat.first { it.isNotEmpty() && it.last().answer?.key != null }.last()
        }
        assertEquals("ans_inventory_item", msg.answer!!.key)
        assertEquals(12.0, msg.answer!!.numbers.getOrElse(0) { -1.0 }, 1e-9)
    }

    @Test
    fun chat_emptyQuestionIgnored() = runBlocking {
        seed()
        val vm = newVM()
        withTimeout(60_000) { vm.ready.first { it } }
        vm.ask("   ")
        // لا رسالة تُضاف لسؤال فارغ — عقد الإدخال المنضبط (إرجاع مبكر متزامن)
        assertTrue(vm.chat.value.isEmpty())
    }

    // ═════════ باب Pro الجديد (H3-7) ═════════

    @Test
    fun proDoor_smartIntel_lockedByDefault_unlockedOnPro() {
        // الافتراض الآمن: SMART_INTEL مقفل على FREE وUNKNOWN بلا كاش
        assertTrue(
            ProEntitlement.isLocked(ProEntitlement.ProFeature.SMART_INTEL,
                ProEntitlement.ProState.FREE, localCacheSaysPro = false))
        assertTrue(
            ProEntitlement.isLocked(ProEntitlement.ProFeature.SMART_INTEL,
                ProEntitlement.ProState.UNKNOWN, localCacheSaysPro = false))
        // PRO يفتح فوراً — بلا إعادة تشغيل (StateFlow حي في الواجهة)
        assertFalse(
            ProEntitlement.isLocked(ProEntitlement.ProFeature.SMART_INTEL,
                ProEntitlement.ProState.PRO, localCacheSaysPro = false))
        // الكاش المحلي وحده يفتح UNKNOWN مؤقتاً (عقد ProEntitlement نفسه)
        assertFalse(
            ProEntitlement.isLocked(ProEntitlement.ProFeature.SMART_INTEL,
                ProEntitlement.ProState.UNKNOWN, localCacheSaysPro = true))
    }
}
