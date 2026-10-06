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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P41-L2] جدار تكاملي على قاعدة Room حقيقية — التقرير الضريبي الدوري يفصل
 * الصفرية عن المعفاة من **بنود** الفواتير الواعية بالسطر (v11 — جولة 2).
 *
 * العقد المجرَّب:
 *  - فاتورة تاريخية (بنودها كلها 0/-1) تبقى على صف الرأس حرفياً — أرقام P38
 *    نفسها: rate/taxAmount من الرأس، والمعفاة صفر.
 *  - فاتورة واعية بالسطر (سطر صريح واحد يكفي): قياسي بنسبة الوراثة يورّث نسبة
 *    الرأس، وصفرية/معفاة تنزلان بخانتيهما المستقلتين بضريبة صفر — ومجموع
 *    ضرائب الأسطر = الضريبة المحجوزة في الرأس (نفس دالة LineTaxP41 على الجانبين).
 *  - فلاتر النافذة [from, to] وحصر الملغاة (status < 3) تنطبق على البنود كما
 *    على الفواتير (saleItemsBetween بنفس فلاتر saleInvoicesSince).
 *  - المشتريات بلا مساس — مسار الرأس كما في P38.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ZatcaReturnRepoP41Test {

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

    private suspend fun partyId(): Long = ledger.saveParty(Party(name = "عميل v11", type = 0))

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
                status = status, note = "P41"
            )
        ).let { if (it == -1L) 1L else it }
        db.invoiceItems().insertAll(items.map { it.copy(invoiceId = id) })
        return id
    }

    @Test
    fun legacyInvoice_keepsHeadRowExactly_exemptEmpty() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-L1", pid, subtotal = 10_000L, taxRate = 15.0, taxAmount = 1_500L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "سطر تاريخي", qty = 2.0, unitPrice = 5_000L))
        )
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals(10_000L, r.salesNet)
        assertEquals(1_500L, r.outputVat)
        assertEquals("لا معفاة من صفوف تاريخية", 0L, r.salesExemptNet)
        assertEquals(0L, r.salesZeroNet)
        assertEquals(listOf(15.0), r.byRate.map { it.rate })
    }

    @Test
    fun lineAwareInvoice_splitsZeroAndExempt_fromItsLines() = runBlocking {
        val pid = partyId()
        // رأس واعٍ بالسطر: taxAmount = 300 (القياسية فقط) — مخزون من نفس دالة المحرك
        val id = seedInvoice(
            "INV-A1", pid, subtotal = 3_200L, taxRate = 15.0, taxAmount = 300L,
            items = listOf(
                InvoiceItem(invoiceId = 0, desc = "قياسية", qty = 1.0, unitPrice = 2_000L),                                  // 300 وراثة 15%
                InvoiceItem(invoiceId = 0, desc = "صفرية", qty = 1.0, unitPrice = 500L, taxKind = LineTaxP41.KIND_ZERO),     // 0
                InvoiceItem(invoiceId = 0, desc = "معفاة", qty = 1.0, unitPrice = 700L, taxKind = LineTaxP41.KIND_EXEMPT),   // 0
            )
        )
        assertEquals("المحجوز في الرأس = مجموع ضرائب الأسطر (نفس الدالة)",
            300L, LineTaxP41.invoiceTaxFromLines(
                listOf(2_000L, 500L, 700L),
                listOf(0, LineTaxP41.KIND_ZERO, LineTaxP41.KIND_EXEMPT),
                listOf(-1.0, -1.0, -1.0), 15.0
            )
        )
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals(3_200L, r.salesNet)
        assertEquals(500L, r.salesZeroNet)
        assertEquals(700L, r.salesExemptNet)
        assertEquals(300L, r.outputVat)
        assertEquals("السطر القياسي بالوراثة يجتمع تحت نسبة الرأس", listOf(15.0), r.byRate.map { it.rate })
        assertEquals(2_000L, r.byRate[0].net)
        assertEquals(id, id) // وضوح القراءة فقط
    }

    @Test
    fun mixedLegacyAndAware_inOneWindow_sumWithoutDrift() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-L2", pid, subtotal = 10_000L, taxRate = 15.0, taxAmount = 1_500L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "تاريخي", qty = 1.0, unitPrice = 10_000L))
        )
        seedInvoice(
            "INV-A2", pid, subtotal = 2_500L, taxRate = 15.0, taxAmount = 300L,
            items = listOf(
                InvoiceItem(invoiceId = 0, desc = "قياسية", qty = 1.0, unitPrice = 2_000L),
                InvoiceItem(invoiceId = 0, desc = "معفاة", qty = 1.0, unitPrice = 500L, taxKind = LineTaxP41.KIND_EXEMPT),
            )
        )
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals(12_500L, r.salesNet)
        assertEquals(1_800L, r.outputVat)
        assertEquals(0L, r.salesZeroNet)
        assertEquals(500L, r.salesExemptNet)
    }

    @Test
    fun windowAndVoidFilters_applyToItemsToo() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "INV-W1", pid, subtotal = 1_000L, taxRate = 0.0, taxAmount = 0L,
            date = t0 - 2 * DAY, // خارج النافذة من جهة البداية
            items = listOf(InvoiceItem(invoiceId = 0, desc = "قديم", qty = 1.0, unitPrice = 1_000L, taxKind = LineTaxP41.KIND_ZERO))
        )
        seedInvoice(
            "INV-W2", pid, subtotal = 2_000L, taxRate = 0.0, taxAmount = 0L,
            status = 3, // ملغاة
            items = listOf(InvoiceItem(invoiceId = 0, desc = "ملغاة", qty = 1.0, unitPrice = 2_000L, taxKind = LineTaxP41.KIND_EXEMPT))
        )
        seedInvoice(
            "INV-W3", pid, subtotal = 400L, taxRate = 0.0, taxAmount = 0L,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "حية", qty = 1.0, unitPrice = 400L, taxKind = LineTaxP41.KIND_ZERO))
        )
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals("خارج النافذة والملغاة مستبعدان", 400L, r.salesZeroNet)
        assertEquals(0L, r.salesExemptNet)
        assertEquals(400L, r.salesNet)
    }

    @Test
    fun purchases_stayOnHeadPath() = runBlocking {
        val pid = partyId()
        seedInvoice(
            "PUR-1", pid, subtotal = 4_000L, taxRate = 15.0, taxAmount = 600L,
            type = 1,
            items = listOf(InvoiceItem(invoiceId = 0, desc = "شراء", qty = 1.0, unitPrice = 4_000L))
        )
        val r = reports.zatcaReturn(t0 - DAY, t0 + DAY)
        assertEquals(4_000L, r.purchasesNet)
        assertEquals(600L, r.inputVat)
        assertEquals(-600L, r.netVat)
        assertEquals(0L, r.salesNet)
    }
}
