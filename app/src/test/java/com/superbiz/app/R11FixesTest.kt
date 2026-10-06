package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.export.DataExport
import kotlinx.coroutines.runBlocking
import com.superbiz.app.domain.algo.SeriesMath
import com.superbiz.app.domain.algo.TimeMath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * — اختبارات انحدار لإصلاحات الموجة
 * كل إصلاح يفشل قبله وينجح بعده (ما أمكن بلا Android UI).
 * C1 إلغاء الفواتير المدفوعة — C2 دفعة مقدمة سامة — C3 قصّ داخل المعاملة
 * C7 كميات شراء/بيع — C10 CSV السالب — C11 فجوات الترقيم — C4 أيام العمل بتوقيت صيفي
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R11FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var reports: ReportsRepo

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        installments = InstallmentRepo(db, ledger)
        reports = ReportsRepo(db)
        Unit
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun party(name: String = "عميل"): Party {
        val p = Party(name = name, type = 0)
        return p.copy(id = db.parties().upsert(p))
    }

    // [P33-P8] السعر قروش Long — الكمية تبقى Double
    private fun saleLine(pid: Long?, price: Long, cost: Long) =
        InvoiceItem(invoiceId = 0, productId = pid, desc = "صنف", qty = 1.0, unitPrice = price)

    // ═══ C1: إلغاء فاتورة نقدية مدفوعة يعكس قيد السند ويحذف صفوف الدفعات ═══

    @Test
    fun c1_voidPaidInvoice_reversesReceiptAndDeletesPayments() = runBlocking {
        val pr = party()
        val product0 = Product(name = "صنف", costPrice = 500L, salePrice = 1_000L, stockQty = 0.0)   // [P33-P8] 5.0/10.0 ريال → قروش
        val product = product0.copy(id = db.products().upsert(product0))
        val inv = Invoice(
            number = "INV-9001", partyId = pr.id, type = 0,
            date = 1_700_000_000_000, dueDate = 1_700_000_000_000,
            subtotal = 1_000L, total = 1_000L, costTotal = 500L,   // [P33-P8] 10.0/10.0/5.0 ريال → قروش
        )
        val invId = invoices.save(
            inv,
            listOf(saleLine(product.id, 1_000L, 500L)),   // [P33-P8] قروش
            moveStock = true,
            collectCashDirection = 0,   // بيع نقدي: قيد سند + صف دفعة
        )
        // قبل الإلغاء: قيد سند حي + صف دفعة + نقد 10
        assertEquals(1_000L, db.payments().totalReceived())   // [P33-P8] 10.0 ريال = 1000 قروشاً — مساواة تامة
        assertEquals(1_000L, reports.cashBalance())   // [P33-P8] قروش — مساواة تامة
        assertEquals(1, db.payments().since(0).size)

        val fresh = db.invoices().byId(invId)!!
        invoices.voidInvoice(fresh)

        // بعد الإلغاء: لا صفوف دفعات لهذه الفاتورة، والنقد عاد إلى صفر
        assertTrue(db.payments().since(0).filter { it.invoiceId == invId }.isEmpty())
        assertEquals(0L, db.payments().totalReceived())   // [P33-P8] مساواة تامة بلا عتبة 1e-9
        assertEquals(0L, reports.cashBalance())   // [P33-P8] مساواة تامة
        // والإيصالات المجملة (refType=payment) أُزيلت من الدفتر
        val entryCount = db.journal().allEntries().count { it.refType == "payment" && it.refId == invId }
        assertEquals(0, entryCount)
    }

    // ═══ C2: دفعة مقدمة سامّة (كانت NaN) أو ≥ الإجمالي تُرفض ═══
    // ([P33-P8] Long لا يكون NaN — السالب هو السامّ المكافئ)

    @Test
    fun c2_createPlan_rejectsPoisonedDownPayment() = runBlocking {
        val pr = party()
        val ok = 1_700_000_000_000L
        assertThrows(IllegalArgumentException::class.java) {
            // [P33-P8] كانت المقدمة NaN — Long لا يكون NaN؛ السالب هو السامّ المكافئ (require downPayment >= 0)
            runBlocking { installments.createPlan("خطة", pr.id, 0, 100_000L, -1L, 4, ok, "SAR", "") }   // [P33-P8] 1000.0 ريال → قروش
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { installments.createPlan("خطة", pr.id, 0, 100_000L, 100_000L, 4, ok, "SAR", "") }   // [P33-P8] المقدمة ≥ الإجمالي تُرفض (قروش)
        }
        // ولا توجد أي خطة أو قسط سُمّم
        assertEquals(0, db.installments().allInstallments().size)
    }

    // ═══ C3: الدفعة الزائدة تُقصّ للمفتوح داخل المعاملة ═══

    @Test
    fun c3_addPayment_clampsToOpen_insideTransaction() = runBlocking {
        val pr = party()
        val inv = Invoice(
            number = "INV-9002", partyId = pr.id, type = 0,
            date = 1_700_000_000_000, dueDate = 1_700_000_000_000,
            subtotal = 10_000L, total = 10_000L,   // [P33-P8] 100.0 ريال → 10_000 قروشاً
        )
        val invId = invoices.save(inv, listOf(saleLine(null, 10_000L, 0L)), moveStock = false)   // [P33-P8] قروش
        // دفعة أكبر من المفتوح تُقصّ إلى 100 وليس أكثر
        ledger.addPayment(pr, 25_000L, 1_700_010_000_000L, 0, "CASH", invoiceId = invId)   // [P33-P8] 250.0 ريال → قروش
        val after = db.invoices().byId(invId)!!
        assertEquals(10_000L, after.paid)   // [P33-P8] 100.0 ريال = 10_000 قروشاً — مساواة تامة
        assertEquals(2, after.status)
        // الفاتورة المسددة بالكامل ترفض دفعة جديدة
        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ledger.addPayment(pr, 1_000L, 1_700_020_000_000L, 0, "CASH", invoiceId = invId) }   // [P33-P8] 10.0 ريال → قروش
        }
        assertTrue(e.message!!.contains("مسددة"))
    }

    // ═══ C7: شراء/بيع بكمية ≤0 مرفوض ═══

    @Test
    fun c7_moveStock_rejectsNonPositivePurchaseSale() = runBlocking {
        val product0 = Product(name = "صنف", costPrice = 500L, salePrice = 1_000L, stockQty = 0.0)   // [P33-P8] قروش
        val product = product0.copy(id = db.products().upsert(product0))
        val fresh = db.products().byId(product.id)!!
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { inventory.moveStock(fresh, -5.0, "PURCHASE", 1_700_000_000_000L, "ممنوع", postJournal = true) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { inventory.moveStock(fresh, 0.0, "SALE", 1_700_000_000_000L, "ممنوع", postJournal = false) }
        }
        // ADJUST يبقى حرّاً بالإشارة (تسوية نزولية مشروعة)
        inventory.moveStock(fresh, -2.0, "ADJUST", 1_700_000_000_000L, "تسوية", postJournal = false)
        assertEquals(-2.0, db.products().byId(product.id)!!.stockQty, 1e-9)
    }

    // ═══ C10: الأرقام السالبة لا تُصدَّر كنص ═══

    @Test
    fun c10_csv_negativeNumbersStayNumeric() {
        val csv = DataExport.csv(listOf("الحساب", "الرصيد"), listOf(listOf("بنك", -50.5)))
        // lineSequence().last() كانت تعيد السطر الفارغ بعد \n الختامية
        val line = csv.lineSequence().filter { it.isNotBlank() }.last()
        assertTrue(line.contains("-50.5"))
        assertFalse(line.contains("'-50.5"))
        // حماية الصيغ باقية للبادئات الخطر فعلاً
        val guarded = DataExport.csv(listOf("أ"), listOf(listOf("=SUM(A1)")))
        assertTrue(guarded.lineSequence().filter { it.isNotBlank() }.last().contains("'=SUM(A1)"))
    }

    // ═══ C11: فجوات الترقيم ترفض المدى الشاسع ═══

    @Test
    fun c11_numberingGaps_rejectsWideRanges() {
        val normal = SeriesMath.numberingGaps(listOf(1, 2, 4, 7))
        assertEquals(listOf(3, 5, 6), normal)
        // مدى واسع (ترقيم مختلط الأنماط) يُرفض حمايةً من ANR
        assertEquals(emptyList<Int>(), SeriesMath.numberingGaps(listOf(5, 20_240_912)))
    }

    // ═══ C4: أيام العمل تنتهي عبر حدود التوقيت الصيفي ═══

    @Test
    fun c4_businessDaysBetween_terminatesAcrossDst() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // نافذة تمس انتقال الصيف (2024-03-10 في نيويورك: يوم 23 ساعة)
            // ثلاثاء → ثلاثاء للتماثل (عقد العدّ: يستثني يوم البداية ويشمل يوم النهاية)
            // الأراضي: 3/6أربعاء،3/7خميس،3/8جمعة✗،3/9سبت✗،3/10أحد(الانتقال!)،3/11اثنين،3/12ثلاثاء = 5
            val from = java.util.Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
                clear(); set(2024, 2, 5, 11, 0, 0)
            }.timeInMillis
            val to = java.util.Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
                clear(); set(2024, 2, 12, 15, 0, 0)
            }.timeInMillis
            val count = TimeMath.businessDaysBetween(from, to)
            assertEquals(5, count)
            // والاتجاه المعاكس سالب مطابق
            assertEquals(-5, TimeMath.businessDaysBetween(to, from))
        } finally {
            TimeZone.setDefault(old)
        }
    }

    private fun runBlocking(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
