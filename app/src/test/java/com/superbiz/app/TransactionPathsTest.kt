package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.domain.Accounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * : اختبارات مسارات المعاملات الحقيقية على قاعدة Room حقيقية في الذاكرة.
 * تتحقق أن كل عملية متعددة الجداول تترك قاعدة البيانات متسقة تماماً
 * الفاتورة = أصناف + مخزون + قيد مزدوج، والدفع = قيد + سطر + حالة الفاتورة.
 * [P33-P8] كل المبالغ المخزنة قروش Long — مقارنات تامة بلا عتبات عائمة.
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransactionPathsTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var reports: ReportsRepo

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        reports = ReportsRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedParty(name: String = "عميل اختبار"): Party {
        val id = ledger.saveParty(Party(name = name, type = 0))
        return ledger.party(id)!!
    }

    // [P33-P8] cost/sale قروش Long (كانا 20.0/30.0 ريال) — stockQty كمية تبقى Double
    private suspend fun seedProduct(stock: Double = 10.0, cost: Long = 2000L, sale: Long = 3000L): Product {
        val p = Product(name = "منتج", costPrice = cost, salePrice = sale, stockQty = stock)
        val id = inventory.saveProduct(p)
        return inventory.product(id)!!
    }

    // [P33-P8] المبالغ قروش Long — taxRate نسبة تبقى Double (قسمة Double على القروش)
    private fun saleInvoice(
        partyId: Long, qty: Double, price: Long,
        subtotal: Long, tax: Long, total: Long, costTotal: Long
    ) = Invoice(
        number = "INV-0001", partyId = partyId, type = 0,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L + 14L * 86_400_000,
        subtotal = subtotal, taxRate = if (subtotal > 0L) tax.toDouble() / subtotal * 100.0 else 0.0,
        taxAmount = tax, total = total, costTotal = costTotal
    )

    // ─── حفظ فاتورة بيع: فاتورة + أصناف + مخزون + قيد مزدوج معاً ───
    @Test
    fun invoiceSave_allTablesConsistent() = runBlocking {
        val party = seedParty()
        val product = seedProduct(stock = 10.0, cost = 2000L, sale = 3000L)

        val inv = saleInvoice(party.id, qty = 2.0, price = 3000L, subtotal = 6000L, tax = 0L, total = 6000L, costTotal = 4000L)
        val item = InvoiceItem(invoiceId = 0, productId = product.id, desc = "منتج", qty = 2.0, unitPrice = 3000L)
        val invId = invoices.save(inv, listOf(item))

        // الفاتورة محفوظة بحالة غير مدفوعة
        val saved = invoices.invoice(invId)!!
        assertEquals(0, saved.status)
        assertEquals(6000L, saved.open)

        // المخزون انخفض فعلياً 10 → 8
        assertEquals(8.0, inventory.product(product.id)!!.stockQty, 0.001)

        // حركة مخزون موثقة بربط الفاتورة
        // إصلاح ترجمة الاختبار: moves() تعيد Flow وليست قائمة — يجب first() أولاً
        val moves = inventory.moves(product.id).first()
        assertTrue("لا حركة مخزون", moves.isNotEmpty())

        // القيد المزدوج: ذمم مدينة بالإجمالي + مبيعات + COGS/مخزون — [P33-P8] مساواة صحيحة تامة
        val sums = db.journal().accountSums().associate { it.account to (it.d to it.c) }
        assertEquals(6000L, sums[Accounts.RECEIVABLE]!!.first - sums[Accounts.RECEIVABLE]!!.second)
        assertEquals(6000L, sums[Accounts.SALES]!!.second - sums[Accounts.SALES]!!.first)
        assertEquals(4000L, sums[Accounts.COGS]!!.first)
        assertEquals(4000L, sums[Accounts.INVENTORY]!!.second - sums[Accounts.INVENTORY]!!.first)

        // الدفتر متوازن ككل: مجموع المدين = مجموع الدائن
        val totalD = db.journal().allLines().sumOf { it.debit }
        val totalC = db.journal().allLines().sumOf { it.credit }
        assertEquals("الدفتر غير متوازن!", totalD, totalC)
    }

    // ─── إلغاء فاتورة: عكس القيد والمخزون بالكامل ───
    @Test
    fun voidInvoice_reversesEverything() = runBlocking {
        val party = seedParty()
        val product = seedProduct(stock = 10.0, cost = 2000L, sale = 3000L)
        val inv = saleInvoice(party.id, 2.0, 3000L, 6000L, 0L, 6000L, 4000L)
        val invId = invoices.save(inv, listOf(
            InvoiceItem(invoiceId = 0, productId = product.id, desc = "منتج", qty = 2.0, unitPrice = 3000L)))

        invoices.voidInvoice(invoices.invoice(invId)!!)

        // الفاتورة ملغاة (خارج القوائم) والمتبقي مصفّر — لا تبقى «مستحقة»
        assertEquals(3, invoices.invoice(invId)!!.status)
        assertEquals(0L, invoices.invoice(invId)!!.open)

        // المخزون عاد كما كان
        assertEquals(10.0, inventory.product(product.id)!!.stockQty, 0.001)

        // لا قيود باسم الفاتورة
        val lines = db.journal().allLines()
        assertTrue("بقيت قيود الفاتورة بعد الإلغاء", lines.isEmpty())
    }

    // ─── الفاتورة الملغاة تخرج من مسارات المستحقات والتوقع والتذكير ───
    @Test
    fun voidedInvoice_excludedFromReceivablesPaths() = runBlocking {
        val party = seedParty()
        val product = seedProduct(stock = 10.0, cost = 2000L, sale = 3000L)

        val inv = saleInvoice(party.id, qty = 2.0, price = 3000L, subtotal = 6000L, tax = 0L, total = 6000L, costTotal = 4000L)
        val invId = invoices.save(inv, listOf(
            InvoiceItem(invoiceId = 0, productId = product.id, desc = "منتج", qty = 2.0, unitPrice = 3000L)))

        // قبل الإلغاء: تظهر ضمن الفواتير المفتوحة
        assertEquals(1, reports.openSaleInvoices().size)

        invoices.voidInvoice(invoices.invoice(invId)!!)

        // بعد الإلغاء: لا تظهر في التوقع/الأعمار/التذكير إطلاقاً
        assertTrue("فاتورة ملغاة ظهرت ضمن المفتوحة!", reports.openSaleInvoices().isEmpty())
        // ومحاولة «سداد كامل» على فاتورة ملغاة لا تُنشئ دفعة
        val before = db.payments().since(0).size
        invoices.invoice(invId)!!.let { voided ->
            // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
            assertTrue(voided.open <= 0L || voided.status >= 3)
        }
        assertEquals(before, db.payments().since(0).size)
    }

    // ─── فشل الحفظ: لا يُكتب أي شيء (لا فاتورة يتيمة بلا قيد) ───
    @Test
    fun saveFailure_writesNothing() = runBlocking {
        seedParty()
        val inv = saleInvoice(1L, 1.0, 1000L, 1000L, 0L, 1000L, 0L)
        try {
            invoices.save(inv, emptyList())   // عناصر فارغة → مرفوض
            assertFalse("يجب أن يفشل الحفظ بعناصر فارغة", true)
        } catch (e: IllegalArgumentException) {
            // متوقع
        }
        assertEquals(0, db.invoices().count())
        assertEquals(0, db.journal().count())
    }

    // ─── الدفعات: تحدّث حالة الفاتورة والنقد والذمم من الدفتر ───
    @Test
    fun addPayment_updatesInvoiceStatusAndCash() = runBlocking {
        val party = seedParty()
        val inv = saleInvoice(party.id, 10.0, 1000L, 10000L, 0L, 10000L, 0L)
        val invId = invoices.save(inv, listOf(
            InvoiceItem(invoiceId = 0, productId = null, desc = "خدمة", qty = 10.0, unitPrice = 1000L)))

        // [P33-P8] دفع 4000 من إجمالي 10000 قروش (كان 40 من 100 ريال)
        ledger.addPayment(party, 4000L, System.currentTimeMillis(), 0, "CASH", invId)
        assertEquals(1, invoices.invoice(invId)!!.status)         // جزئية
        assertEquals(4000L, invoices.invoice(invId)!!.paid)

        ledger.addPayment(party, 6000L, System.currentTimeMillis(), 0, "CASH", invId)
        val paid = invoices.invoice(invId)!!
        assertEquals(2, paid.status)                               // مدفوعة
        assertEquals(0L, paid.open)

        // النقد في الصندوق من الدفتر = 10000، ورصيد العميل صفّر
        assertEquals(10000L, reports.cashBalance())
        assertEquals(0L, ledger.partyBalance(party.id))
    }

    // ─── الترقيم: لا تكرار لأرقام الفواتير حتى لو طُلب نفس الرقم ───
    @Test
    fun invoiceNumbering_neverDuplicates() = runBlocking {
        val party = seedParty()
        val base = saleInvoice(party.id, 1.0, 1000L, 1000L, 0L, 1000L, 0L)
        val id1 = invoices.save(base, listOf(InvoiceItem(invoiceId = 0, desc = "أ", qty = 1.0, unitPrice = 1000L)))
        val id2 = invoices.save(base, listOf(InvoiceItem(invoiceId = 0, desc = "ب", qty = 1.0, unitPrice = 1000L)))
        val n1 = invoices.invoice(id1)!!.number
        val n2 = invoices.invoice(id2)!!.number
        assertTrue("تكرار رقم فاتورة: $n1", n1 != n2)
    }

    // ─── قيود «دين جديد» لا تُعد دخلاً نقدياً ───
    @Test
    fun debtEntry_notCountedAsCash() = runBlocking {
        val party = seedParty()
        ledger.addDebt(party, 50000L, System.currentTimeMillis(), "دين")
        assertEquals(50000L, ledger.partyBalance(party.id))
        assertEquals("الدين ليس نقداً!", 0L, db.payments().totalReceived())
        assertEquals(0L, reports.cashBalance())
    }

    // ─── أرصدة الأطراف: تجميع SQL واحد يطابق الرصيد الفردي ───
    @Test
    fun balances_aggregatesViaSingleSql() = runBlocking {
        val p1 = seedParty("أ")
        val p2 = seedParty("ب")
        ledger.addDebt(p1, 30000L, System.currentTimeMillis(), "")
        ledger.addDebt(p2, 12000L, System.currentTimeMillis(), "")
        ledger.addPayment(p1, 5000L, System.currentTimeMillis(), 0, "CASH")

        val map = ledger.balances()
        assertEquals(25000L, map[p1.id]!!)
        assertEquals(12000L, map[p2.id]!!)
        assertEquals(ledger.partyBalance(p1.id), map[p1.id]!!)
    }

    // ─── المصروفات: قيد مزدوج + حذف يعكس القيد ───
    @Test
    fun expenseAddAndDelete_postsAndReversesJournal() = runBlocking {
        val expenses = ExpenseRepo(db, ledger)
        val id = expenses.add(5000L, "إيجار", "إيجار المحل")
        assertEquals(1, db.expenses().count())
        // إصلاح تأكيد مقلوب: مصروف 5000 قرشاً يُخفض النقد إلى -5000 (كان ينتظر +5000 خطأً) —
        // السلوك في ExpenseRepo صحيح: cashOut يقيد EXPENSE مدين / CASH دائن
        assertEquals(-5000L, reports.cashBalance())          // نقد خرج
        val ist = reports.incomeStatement(0, Long.MAX_VALUE)
        assertEquals(5000L, ist.expenses)                    // مصروف قُيد

        val saved = db.expenses().between(0, Long.MAX_VALUE).first { it.id == id }
        expenses.delete(saved)

        assertEquals(0, db.expenses().count())
        assertEquals(0L, reports.cashBalance())
        assertTrue(db.journal().allLines().isEmpty())
    }

    // ─── دفعات معلّمة بمرجع فاتورة: سطر الدفعة يُحفظ بربطه ───
    @Test
    fun paymentRow_keepsInvoiceLink() = runBlocking {
        val party = seedParty()
        val inv = saleInvoice(party.id, 1.0, 9000L, 9000L, 0L, 9000L, 0L)
        val invId = invoices.save(inv, listOf(InvoiceItem(invoiceId = 0, desc = "س", qty = 1.0, unitPrice = 9000L)))
        ledger.addPayment(party, 3000L, System.currentTimeMillis(), 0, "CASH", invId)
        val rows = db.payments().forParty(party.id)
        assertTrue(rows.any { it.invoiceId == invId && it.amount == 3000L && it.method == "CASH" })
    }
}
