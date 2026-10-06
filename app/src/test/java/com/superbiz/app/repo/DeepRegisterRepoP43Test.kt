package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.domain.LineTaxP41
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P43-D1] جولة 4 — جدار تكاملي على قاعدة Room حقيقية للدفتر التفصيلي
 * (تصدير عميق — صفوف لكل بند بأعمدة الضريبة v11).
 *
 * العقد المجرَّب:
 *  - فاتورة واعية بالسطر تنزل بنداً بنداً وأسماء الأطراف محلولة، والتاريخية
 *    صفاً مجمعاً من رأسها (نفس قرار zatcaReturn حرفياً).
 *  - المشتريات عبر purchaseItemsBetween بنفس الفلاتر (نافذة/ملغاة) وبلا مساس
 *    بصفوف البيع.
 *  - **التوافقية العابرة للتقارير**: مجموع صوافي الدفتر وضرائبه = أرقام
 *    التقرير الدوري لنفس النافذة (salesNet/outputVat/salesZeroNet/
 *    salesExemptNet) — لا انحراف بين الدفتر والملخص أبداً.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeepRegisterRepoP43Test {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var reports: ReportsRepo

    private val t0 = 1_700_000_000_000L
    private val DAY = 86_400_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        reports = ReportsRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun partyId(name: String = "عميل الدفتر P43"): Long =
        ledger.saveParty(Party(name = name, type = 0))

    private suspend fun seedInvoice(
        number: String,
        pid: Long,
        subtotal: Long,
        taxRate: Double,
        taxAmount: Long,
        date: Long = t0,
        type: Int = 0,
        status: Int = 0,
        items: List<InvoiceItem>,
    ): Long {
        val id = db.invoices().upsert(
            Invoice(
                number = number, partyId = pid, type = type,
                date = date, dueDate = date + 14 * DAY,
                subtotal = subtotal, discount = 0L,
                taxRate = taxRate, taxAmount = taxAmount, total = subtotal + taxAmount,
                status = status, note = "P43"
            )
        ).let { if (it == -1L) 1L else it }
        db.invoiceItems().insertAll(items.map { it.copy(invoiceId = id) })
        return id
    }

    @Test
    fun lineAwareInvoice_perItemRows_withPartyNames() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-D1", pid, subtotal = 3_200L, taxRate = 15.0, taxAmount = 300L,
            items = listOf(
                InvoiceItem(invoiceId = 0, desc = "قياسية", qty = 1.0, unitPrice = 2_000L),
                InvoiceItem(invoiceId = 0, desc = "صفرية", qty = 1.0, unitPrice = 500L, taxKind = LineTaxP41.KIND_ZERO),
                InvoiceItem(invoiceId = 0, desc = "معفاة", qty = 1.0, unitPrice = 700L, taxKind = LineTaxP41.KIND_EXEMPT),
            )
        )
        val d = reports.deepRegister(t0 - DAY, t0 + DAY)
        assertEquals("ثلاثة بنود ⇒ ثلاثة صفوف", 3, d.sales.size)
        assertTrue("اسم الطرف محلول", d.sales.all { it.party == "عميل الدفتر P43" })
        assertEquals("INV-D1", d.sales[0].invoiceNo)
        assertEquals("الوارث بنسبة الرأس", 15.0, d.sales[0].effectiveRate, 0.0)
        assertEquals(300L, d.sales[0].lineVatP)
        assertEquals(0L, d.sales[1].lineVatP)
        assertEquals(0L, d.sales[2].lineVatP)
    }

    @Test
    fun legacyInvoice_oneAggregatedRow_fromHead() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-D2", pid, subtotal = 10_000L, taxRate = 15.0, taxAmount = 1_500L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "تاريخي", qty = 1.0, unitPrice = 10_000L))
        )
        val d = reports.deepRegister(t0 - DAY, t0 + DAY)
        assertEquals("صف واحد مجمع", 1, d.sales.size)
        assertEquals(10_000L, d.sales[0].lineNetP)
        assertEquals(1_500L, d.sales[0].lineVatP)
        assertEquals(15.0, d.sales[0].effectiveRate, 0.0)
    }

    @Test
    fun purchases_flowThroughTheirOwnMirrorQuery() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "PUR-D1", pid, subtotal = 4_000L, taxRate = 15.0, taxAmount = 600L,
            type = 1,
            items = listOf(
                InvoiceItem(invoiceId = 0, desc = "شراء أ", qty = 1.0, unitPrice = 1_500L),
                InvoiceItem(invoiceId = 0, desc = "شراء ب", qty = 1.0, unitPrice = 2_500L),
            )
        )
        seedInvoice(
            "SALE-D1", pid, subtotal = 1_000L, taxRate = 15.0, taxAmount = 150L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "بيع", qty = 1.0, unitPrice = 1_000L))
        )
        val d = reports.deepRegister(t0 - DAY, t0 + DAY)
        assertEquals("بنود المشتريات بلا تصنيف صريح ⇒ صف مجمع واحد من الرأس (نفس العقد)", 1, d.purchases.size)
        assertEquals(4_000L, d.purchasesTotals.netP)
        assertEquals(600L, d.purchasesTotals.vatP)
        assertEquals("صفوف البيع بلا مساس", 1, d.sales.size)
    }

    @Test
    fun windowAndVoidFilters_applyToDeepRegister() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-OLD", pid, subtotal = 5_000L, taxRate = 15.0, taxAmount = 750L,
            date = t0 - 2 * DAY,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "قديم", qty = 1.0, unitPrice = 5_000L, taxKind = LineTaxP41.KIND_ZERO))
        )
        seedInvoice(
            "INV-VOID", pid, subtotal = 2_000L, taxRate = 15.0, taxAmount = 0L,
            status = 3,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "ملغاة", qty = 1.0, unitPrice = 2_000L, taxKind = LineTaxP41.KIND_EXEMPT))
        )
        seedInvoice(
            "INV-LIVE", pid, subtotal = 400L, taxRate = 15.0, taxAmount = 0L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "حية", qty = 1.0, unitPrice = 400L, taxKind = LineTaxP41.KIND_ZERO))
        )
        val d = reports.deepRegister(t0 - DAY, t0 + DAY)
        assertEquals("خارج النافذة والملغاة مستبعدان", 1, d.sales.size)
        assertEquals(400L, d.salesTotals.netP)
    }

    @Test
    fun crossReportConsistency_registerTotalsEqualVatReturnForSameWindow() = runBlocking {
        val pid = partyId()
        // تاريخية + واعية بالسطر (صريح 5.5% + صفرية + معفاة) + مشتريات — خليط كامل
        seedInvoice(
            "INV-X1", pid, subtotal = 10_000L, taxRate = 15.0, taxAmount = 1_500L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "تاريخي", qty = 1.0, unitPrice = 10_000L))
        )
        seedInvoice(
            "INV-X2", pid, subtotal = 12_000L, taxRate = 15.0, taxAmount = 850L,
            items = listOf(
                InvoiceItem(invoiceId = 0, desc = "خمس ونصف", qty = 1.0, unitPrice = 10_000L,
                    taxKind = LineTaxP41.KIND_STANDARD, taxRate = 5.5),
                InvoiceItem(invoiceId = 0, desc = "صفرية", qty = 1.0, unitPrice = 1_000L, taxKind = LineTaxP41.KIND_ZERO),
                InvoiceItem(invoiceId = 0, desc = "معفاة", qty = 1.0, unitPrice = 1_000L, taxKind = LineTaxP41.KIND_EXEMPT),
            )
        )
        seedInvoice(
            "PUR-X1", pid, subtotal = 4_000L, taxRate = 15.0, taxAmount = 600L,
            type = 1,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "شراء", qty = 1.0, unitPrice = 4_000L))
        )
        val d = reports.deepRegister(t0 - DAY, t0 + DAY)
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals("الدفتر والتقرير من صندوق رياضيات واحد — المبيعات",
            r.salesNet, d.salesTotals.netP)
        assertEquals("الدفتر والتقرير من صندوق رياضيات واحد — المخرجات",
            r.outputVat, d.salesTotals.vatP)
        assertEquals("الدفتر والتقرير — المشتريات",
            r.purchasesNet, d.purchasesTotals.netP)
        assertEquals("الدفتر والتقرير — المدخلات",
            r.inputVat, d.purchasesTotals.vatP)
    }
}
