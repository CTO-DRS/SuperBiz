package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.domain.InvoiceText
import com.superbiz.app.util.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-1 V 2.5.0] بوابة القبول المالية — توازن محاسبي كامل عبر عملات مختلطة
 * ═══════════════════════════════════════════════════════════════════════════
 * سيناريو القبول من خارطة الطريق (H4-1): فواتير بثلاث عملات (SAR أساس + USD +
 * EUR بأسعار الكتالوج المبذّرة v1) ومصروف أجنبي — على قاعدة Room حقيقية
 * والقيد المزدوج متوازن بمساواة تامة (مدين == دائن) ووحدة القياس الموحدة
 * قروش الأساس في كل عمود مالي، والفئة الأصلية بسعرها التاريخي وثيقة عرض لا
 * إعادة حساب. صفر Double في أي تأكيد — التحويلات كلها عبر Money.foreignToBasePiasters
 * (نقطة التحويل الوحيدة R17) وهي نفسها التي يمر بها الحفظ الإنتاجي من الـVM.
 *
 * القيم المشتقة يدوياً (مُتحقَّق منها عددياً خارج الكود):
 * USD: 1/0.2665 = 3.7523452158… → micros 375_234_522 → $100.00 = 37_523 قرشاً
 * EUR: 1/0.2453 = 4.0766408479… → micros 407_664_085 → €50.00 = 20_383 قرشاً
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class H4MixedCurrencyTest {

    private lateinit var db: AppDatabase
    private lateinit var invoices: InvoiceRepo
    private lateinit var expenses: ExpenseRepo
    private lateinit var ledger: LedgerRepo
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        invoices = InvoiceRepo(db)
        expenses = ExpenseRepo(db, LedgerRepo(db))
        ledger = LedgerRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** نفس مسار الإنتاج: الـVM يحوّل عبر Money.foreignToBasePiasters ثم يبني الفاتورة */
    private fun stampForeign(foreignMinor: Long, rateToBase: Double): Triple<Long, Long, Long> {
        val micros = com.superbiz.app.domain.algo.FxStampMath.rateMicrosFromCatalog(rateToBase)!!
        val base = Money.foreignToBasePiasters(foreignMinor, 2, micros)!!
        return Triple(base, foreignMinor, micros)
    }

    @Test
    fun mixedCurrencies_journalStaysBalanced_andStampsPersist() = runBlocking {
        val partyId = db.parties().upsert(Party(name = "عميل متعدد العملات"))

        // 1) فاتورة بالأساس — 100.00 ر.س
        val idSar = invoices.save(
            Invoice(number = "T-SAR", partyId = partyId, type = 0, date = t0, dueDate = t0,
                subtotal = 10_000, taxRate = 0.0, taxAmount = 0, total = 10_000, currency = "SAR"),
            listOf(InvoiceItem(invoiceId = 0, desc = "بند ريالي", qty = 1.0, unitPrice = 10_000))
        )
        val sar = db.invoices().byId(idSar)!!
        assertEquals("", sar.origCurrency) // بالأساس: ختم فارغ

        // 2) فاتورة بالدولار — $100.00 بسعر الكتالوج المبذّر (0.2665 وحدة لكل ريال)
        val (usdBase, usdForeign, usdMicros) = stampForeign(10_000, 0.2665)
        assertEquals(375_234_522L, usdMicros) // اليدوي: 1e8/0.2665 = 375234521.57… → HALF_UP
        assertEquals(37_523L, usdBase)        // اليدوي: 10000×375234522/1e8 = 37523.45 → 37523
        val idUsd = invoices.save(
            Invoice(number = "T-USD", partyId = partyId, type = 0, date = t0, dueDate = t0,
                subtotal = usdBase, taxRate = 0.0, taxAmount = 0, total = usdBase, currency = "USD",
                origCurrency = "USD", origTotal = usdForeign, origFxMicros = usdMicros),
            listOf(InvoiceItem(invoiceId = 0, desc = "بند دولاري", qty = 1.0, unitPrice = usdBase))
        )
        val usd = db.invoices().byId(idUsd)!!
        assertEquals("USD", usd.origCurrency)
        assertEquals(10_000L, usd.origTotal)
        assertEquals(375_234_522L, usd.origFxMicros)
        assertEquals(37_523L, usd.total) // وحدة القياس الموحدة قروش أساس

        // 3) فاتورة باليورو — €50.00 بسعر الكتالوج (0.2453)
        val (eurBase, eurForeign, eurMicros) = stampForeign(5_000, 0.2453)
        assertEquals(407_664_085L, eurMicros) // اليدوي: 1e8/0.2453 = 407664084.79… → HALF_UP
        assertEquals(20_383L, eurBase)        // اليدوي: 5000×407664085/1e8 = 20383.20 → 20383
        val idEur = invoices.save(
            Invoice(number = "T-EUR", partyId = partyId, type = 0, date = t0, dueDate = t0,
                subtotal = eurBase, taxRate = 0.0, taxAmount = 0, total = eurBase, currency = "EUR",
                origCurrency = "EUR", origTotal = eurForeign, origFxMicros = eurMicros),
            listOf(InvoiceItem(invoiceId = 0, desc = "بند يوروي", qty = 1.0, unitPrice = eurBase))
        )

        // 4) مصروف بالدولار — $20.00 عبر المسار الإنتاجي (addStamped بعد التحويل)
        val (expBase, expForeign, expMicros) = stampForeign(2_000, 0.2665)
        assertEquals(7_505L, expBase) // اليدوي: 2000×375234522/1e8 = 7504.69 → 7505 (HALF_UP)
        expenses.addStamped(expBase, "USD", expForeign, expMicros, "نقل وشحن", "شحن دولاري", t0)

        // ═══ بوابة القبول 2: التقارير الموحدة تقرأ قروش الأساس حصراً (بلا إعادة حساب) ═══
        val sums = db.journal().accountSums()
        // ═══ بوابة القبول 1: القيد المزدوج متوازن بمساواة تامة عبر العملات المختلطة ═══
        val debit = sums.sumOf { it.d }
        val credit = sums.sumOf { it.c }
        assertEquals("مدين == دائن عبر ثلاث عملات ومصروف أجنبي", debit, credit)
        assertTrue("الدخل متوازن وغير صفر", debit > 0L)
        val receivables = sums.firstOrNull { it.account == com.superbiz.app.domain.Accounts.RECEIVABLE }?.let { it.d - it.c } ?: 0L
        assertEquals(10_000L + 37_523L + 20_383L, receivables) // 67_906 قرشاً بالأساس
        val expenseAcc = sums.firstOrNull { it.account == com.superbiz.app.domain.Accounts.EXPENSE }?.let { it.d - it.c } ?: 0L
        assertEquals(7_505L, expenseAcc) // المصروف الدولاري بقروش الأساس

        // أرصدة الطرف من الدفتر نفسه — نفس المجموع الموحد
        assertEquals(67_906L, ledger.partyBalance(partyId))

        // ═══ بوابة القبول 3: وثيقة العرض تحمل الفئة الأصلية بسعرها التاريخي ═══
        val text = InvoiceText.summary(
            usd, db.invoiceItems().forInvoice(idUsd), "عميل متعدد العملات",
            InvoiceText.Labels(originalAmount = "الفئة الأصلية")
        )
        assertTrue(text.contains("الفئة الأصلية"))
        assertTrue(text.contains("100 USD")) // numP يطوي الأصفار الكسرية الصحيحة (100.00 → 100)
        assertTrue(text.contains("@ 3.75234522"))
    }
}
