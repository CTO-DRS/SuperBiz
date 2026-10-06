package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.InstallmentEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P37-T-D] جدران المستودعات — ثوابت لا تنكسر (Invariants) على قاعدة Room حقيقية في الذاكرة
 * (نمط TransactionPathsTest حرفياً: AndroidJUnit4 + sdk 34 + inMemory + allowMainThreadQueries + runBlocking).
 *
 * يقيس السلوك الفعلي للمستودعات الأربعة المالية على البيانات الحقيقية لا على محاكاة:
 *
 * • [LedgerRepo] — القيد المزدوج: كل عملية (دين/قبض/سداد) تُرحّل قيداً متوازناً بمساواة
 *   تامة بالقروش (مدين == دائن) على مستوى الدفتر ككل وعلى مستوى كل قيد منفرداً،
 *   وbalances() تجمّع أرصدة العملاء والموردين من الدفتر نفسه.
 * • [InstallmentRepo] — الجدول المولَّد مجموعه = الممول بالضبط (لا قسط أخير سالب ولا صفري
 *   بافتراض المحرك)، والسداد الجزئي/الكامل يتراكم في paidAmount ويحول الحالة،
 *   والتأجيل يحفظ المبلغ ولا يلمس المال.
 * • [ChecksRepo] — الإيداع بلا قيد، والتحصيل/الإرجاع يولّد قيداً متوازناً ودفعة سليمة،
 *   والتحولات خارج القائمة البيضاء تُرفض بلا أي أثر.
 * • [ExpenseRepo] — المصروف يُقيَّد مزدوجاً (مصروفات/نقد)، يظهر في فترته وفئته،
 *   وحذفه يعكس قيده بالكامل.
 *
 * [P33-P8] كل المبالغ قروش Long — لا Double في أي تأكيد مالي (مساواة تامة بلا عتبات).
 * كل الأزمنة صريحة ثابتة (t0 ومشتقاته) — لا زمن نظام في البذرة ولا في التوقعات.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepoInvariantsTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var checks: ChecksRepo
    private lateinit var expenses: ExpenseRepo
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
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        installments = InstallmentRepo(db, ledger)
        checks = ChecksRepo(db, ledger)
        expenses = ExpenseRepo(db, ledger)
        reports = ReportsRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun customer(name: String = "عميل الجدار"): Party {
        val id = ledger.saveParty(Party(name = name, type = 0))
        return ledger.party(id)!!
    }

    private suspend fun supplier(name: String = "مورد الجدار"): Party {
        val id = ledger.saveParty(Party(name = name, type = 1))
        return ledger.party(id)!!
    }

    /** مجموع المدين والدائن على مستوى الدفتر ككل — [P33-P8] مساواة تامة */
    private suspend fun journalTotals(): Pair<Long, Long> {
        val lines = db.journal().allLines()
        return lines.sumOf { it.debit } to lines.sumOf { it.credit }
    }

    private suspend fun assertLedgerBalanced(message: String) {
        val (d, c) = journalTotals()
        assertEquals(message, d, c)
    }

    // ═══════════════════════ LedgerRepo ═══════════════════════

    // ─── الدفتر متوازن تماماً بعد كل عملية: دين ثم سداد جزئي ثم سداد كامل ───
    @Test
    fun `الدفتر_متوازن_بعد_كل_عملية_دين_وسداد_جزئي_وسداد_كامل`() = runBlocking {
        val p = customer()
        ledger.addDebt(p, 30_000L, t0, "دين أول")
        assertLedgerBalanced("الدفتر غير متوازن بعد الدين")

        ledger.addPayment(p, 12_000L, t0 + DAY, 0, "CASH")
        assertLedgerBalanced("الدفتر غير متوازن بعد السداد الجزئي")
        assertEquals(18_000L, ledger.partyBalance(p.id))

        ledger.addPayment(p, 18_000L, t0 + 2 * DAY, 0, "CASH")
        assertLedgerBalanced("الدفتر غير متوازن بعد السداد الكامل")
        assertEquals(0L, ledger.partyBalance(p.id))
    }

    // ─── كل قيد منفرداً متوازن: أسطر القيد الواحد مدينها = دائنها ───
    @Test
    fun `كل_قيد_منفردا_متوازن_بعد_سلسلة_عمليات`() = runBlocking {
        val p = customer()
        ledger.addDebt(p, 10_000L, t0, "دين")
        ledger.addPayment(p, 4_000L, t0 + DAY, 0, "CASH")
        ledger.addPayment(p, 6_000L, t0 + 2 * DAY, 0, "CASH")

        val byEntry = db.journal().allLines().groupBy { it.entryId }
        assertTrue(byEntry.isNotEmpty())
        for ((entryId, lines) in byEntry) {
            assertEquals("القيد $entryId غير متوازن", lines.sumOf { it.debit }, lines.sumOf { it.credit })
        }
    }

    // ─── balances() تعيد أرصدة صحيحة لعملاء وموردين مطابقة للحساب الفردي ───
    @Test
    fun `balances_تعيد_أرصدة_العملاء_والموردين_مطابقة_للدفتر`() = runBlocking {
        val c1 = customer("عميل أ")
        val c2 = customer("عميل ب")
        val s1 = supplier("مورد أ")
        ledger.addDebt(c1, 30_000L, t0, "")
        ledger.addDebt(c2, 12_000L, t0, "")
        ledger.addPayment(c1, 5_000L, t0 + DAY, 0, "CASH")
        ledger.addDebt(s1, 8_000L, t0, "") // دين مورد = أنت مدين له

        val map = ledger.balances()
        assertEquals(25_000L, map[c1.id]!!)
        assertEquals(12_000L, map[c2.id]!!)
        assertEquals(-8_000L, map[s1.id]!!)        // المورد رصيده سالب (دائن لك)
        assertEquals(ledger.partyBalance(c1.id), map[c1.id]!!)
        assertEquals(ledger.partyBalance(s1.id), map[s1.id]!!)
    }

    // ─── دين المورد يُرحَّل على الذمم الدائنة لا المدينة ───
    @Test
    fun `دين_المورد_يسجل_في_الذمم_الدائنة_لا_المدينة`() = runBlocking {
        val s = supplier()
        ledger.addDebt(s, 8_000L, t0, "شراء آجل")

        val (ar, ap) = ledger.arAp()
        assertEquals(0L, ar)
        assertEquals(8_000L, ap)

        val sums = db.journal().accountSums().associate { it.account to (it.d to it.c) }
        assertEquals(8_000L, sums[Accounts.INVENTORY]!!.first)   // المخزون مدين
        assertEquals(8_000L, sums[Accounts.PAYABLE]!!.second)    // الذمم الدائنة دائن
        assertEquals(-8_000L, ledger.partyBalance(s.id))
        assertLedgerBalanced("قيد دين المورد غير متوازن")
    }

    // ─── سداد جزئي ثم كامل: حالة الفاتورة تتحول والذمة تُقفل والنقد يدخل ───
    @Test
    fun `سداد_جزئي_ثم_كامل_يحدث_حالة_الفاتورة_ويصفر_الذمة`() = runBlocking {
        val p = customer()
        val invId = invoices.save(
            Invoice(number = "INV-INV1", partyId = p.id, type = 0, date = t0, dueDate = t0 + 14 * DAY,
                subtotal = 10_000L, taxAmount = 0L, total = 10_000L, costTotal = 0L),
            listOf(InvoiceItem(invoiceId = 0, productId = null, desc = "خدمة", qty = 1.0, unitPrice = 10_000L)))

        ledger.addPayment(p, 4_000L, t0 + DAY, 0, "CASH", invId)
        assertEquals(1, invoices.invoice(invId)!!.status)   // جزئية
        assertEquals(4_000L, invoices.invoice(invId)!!.paid)
        assertEquals(6_000L, invoices.invoice(invId)!!.open)

        ledger.addPayment(p, 6_000L, t0 + 2 * DAY, 0, "CASH", invId)
        val paid = invoices.invoice(invId)!!
        assertEquals(2, paid.status)                         // مدفوعة
        assertEquals(10_000L, paid.paid)
        assertEquals(0L, paid.open)
        assertEquals(0L, ledger.partyBalance(p.id))
        assertEquals(10_000L, reports.cashBalance())
        assertLedgerBalanced("الدفتر غير متوازن بعد إقفال الفاتورة")
    }

    // ─── الدفعة الزائدة عن المفتوح تُقص إليه — لا رصيد سالب ولا تجاوز ───
    @Test
    fun `الدفعة_الزائدة_تقص_إلى_المفتوح_بلا_رصيد_سالب`() = runBlocking {
        val p = customer()
        val invId = invoices.save(
            Invoice(number = "INV-INV2", partyId = p.id, type = 0, date = t0, dueDate = t0 + DAY,
                subtotal = 10_000L, taxAmount = 0L, total = 10_000L, costTotal = 0L),
            listOf(InvoiceItem(invoiceId = 0, productId = null, desc = "بضاعة", qty = 1.0, unitPrice = 10_000L)))

        ledger.addPayment(p, 99_999L, t0 + DAY, 0, "CASH", invId)

        assertEquals(10_000L, invoices.invoice(invId)!!.paid)
        assertEquals(2, invoices.invoice(invId)!!.status)
        val rows = db.payments().since(0)
        assertEquals(1, rows.size)
        assertEquals("الدفعة المخزنة يجب أن تكون مقصوصة إلى المفتوح", 10_000L, rows.single().amount)
        assertEquals(10_000L, reports.cashBalance())
        assertLedgerBalanced("القص أفرز قيداً غير متوازن")
    }

    // ─── الدفعة الصفرية/السالبة تُرفض بلا أي أثر على الدفتر ───
    @Test
    fun `رفض_دفعة_صفرية_أو_سالبة_بلا_أثر_على_الدفتر`() = runBlocking {
        val p = customer()
        for (bad in listOf(0L, -5_000L)) {
            try {
                ledger.addPayment(p, bad, t0, 0, "CASH")
                fail("المبلغ $bad يجب أن يُرفض")
            } catch (e: IllegalArgumentException) {
                // متوقع — عقد
            }
        }
        assertEquals(0, db.journal().allLines().size)
        assertEquals(0, db.payments().since(0).size)
    }

    // ─── الدين الصفري/السالب يُرفض، والدين لطرف بلا دور يُرفض ───
    @Test
    fun `رفض_دين_غير_منطقي_ولطرف_بلا_دور`() = runBlocking {
        val p = customer()
        try {
            ledger.addDebt(p, 0L, t0, "")
            fail("دين صفري يجب أن يُرفض")
        } catch (e: IllegalArgumentException) {
        }
        val roleless = ledger.party(ledger.saveParty(Party(name = "طرف بلا دور", type = 3)))!!
        try {
            ledger.addDebt(roleless, 5_000L, t0, "")
            fail("دين لطرف بلا دور يجب أن يُرفض")
        } catch (e: IllegalArgumentException) {
        }
        assertTrue(db.journal().allLines().isEmpty())
    }

    // ─── كشف الحساب برصيد متحرك صحيح بالقروش ينتهي عند رصيد الطرف ───
    @Test
    fun `كشف_الحساب_برصيد_متحرك_صحيح_بالقروش`() = runBlocking {
        val p = customer()
        ledger.addDebt(p, 10_000L, t0, "دين")
        ledger.addPayment(p, 4_000L, t0 + DAY, 0, "CASH")

        val rows = ledger.statement(p.id)
        assertEquals(2, rows.size)
        assertEquals(10_000L, rows[0].debit)
        assertEquals(10_000L, rows[0].balance)
        assertEquals(4_000L, rows[1].credit)
        assertEquals(6_000L, rows[1].balance)
        assertEquals(ledger.partyBalance(p.id), rows.last().balance)
    }

    // ═══════════════════════ InstallmentRepo ═══════════════════════

    // ─── الجدول المولَّد مجموعه = الممول بالضبط — بقايا القروش على الأقساط الأولى، لا سالب ───
    @Test
    fun `جدول_الأقساط_مجموعه_يساوي_الممول_بالضبط_بلا_قسط_سالب`() = runBlocking {
        val p = customer()
        // الممول 100_003 على 12 شهراً: base=8333 والباقي 7 قروش توزع على الأوائل
        val planId = installments.createPlan(
            "اتفاق التوزيع", p.id, 0, 120_003L, 20_000L, 12, t0, "SAR", "", today = t0)
        val rows = installments.installmentsOf(planId)
        assertEquals(12, rows.size)
        assertEquals(100_003L, rows.sumOf { it.amount })   // [P33-P8] مساواة تامة
        assertEquals(100_003L, db.installments().planById(planId)!!.financed)
        assertTrue(rows.all { it.amount > 0L })            // لا قسط صفري ولا سالب
        assertEquals(8_334L, rows[0].amount)
        assertEquals(8_334L, rows[6].amount)
        assertEquals(8_333L, rows[7].amount)
        assertEquals(8_333L, rows[11].amount)
    }

    // ─── تمويل ضئيل لا يحتمل قرشاً لكل شهر: يسقط في قسط واحد يساوي المبلغ ───
    @Test
    fun `التمويل_الضئيل_يسقط_في_قسط_واحد_يساوي_المبلغ_تماما`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق ضئيل", p.id, 0, 105L, 100L, 12, t0, "SAR", "", today = t0)
        val rows = installments.installmentsOf(planId)
        assertEquals(1, rows.size)
        assertEquals(5L, rows.single().amount)
        assertEquals(5L, rows.sumOf { it.amount })
        assertEquals(0L, rows.sumOf { it.paidAmount })
    }

    // ─── فتح الخطة يرحّل قيدي فتح ومقدمة متوازنين ويدفع المقدمة نقداً ───
    @Test
    fun `فتح_الخطة_يرحل_قيدين_متوازنين_ويسجل_المقدمة_نقدا`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق الفتح", p.id, 0, 120_000L, 20_000L, 10, t0, "SAR", "", today = t0)

        // قيدا الخطة (فتح + مقدمة) مرجعيهما "plan" ومعرف الخطة
        val planEntries = db.journal().allEntries().filter { it.refType == "plan" && it.refId == planId }
        assertEquals(2, planEntries.size)
        val linesByEntry = db.journal().allLines().groupBy { it.entryId }
        for (e in planEntries) {
            val lines = linesByEntry[e.id]!!
            assertEquals("قيد الخطة $e غير متوازن", lines.sumOf { it.debit }, lines.sumOf { it.credit })
        }
        assertEquals(20_000L, reports.cashBalance())      // المقدمة نقد دخل
        assertEquals(100_000L, ledger.partyBalance(p.id)) // الإجمالي ناقص المقدمة
        val down = db.payments().forParty(p.id).single()
        assertEquals("INSTALLMENT_DOWN", down.method)
        assertEquals(20_000L, down.amount)
    }

    // ─── السداد الجزئي يتراكم في paidAmount ويحوّل الحالة إلى جزئي ───
    @Test
    fun `سداد_جزئي_يتراكم_في_paidAmount_ويحول_الحالة_الى_جزئي`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق الجزئي", p.id, 0, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()
        assertEquals(10_000L, first.amount)
        assertEquals(InstallmentEngine.St.DUE, first.status)

        val paid1 = installments.pay(first, 4_000L, t0 + DAY)
        assertEquals(4_000L, paid1)
        val after1 = installments.installmentsOf(planId).first()
        assertEquals(4_000L, after1.paidAmount)
        assertEquals(InstallmentEngine.St.PARTIAL, after1.status)
        assertEquals(6_000L, after1.open)

        val paid2 = installments.pay(after1, 2_000L, t0 + 2 * DAY)
        assertEquals(2_000L, paid2)
        val after2 = installments.installmentsOf(planId).first()
        assertEquals("paidAmount يجب أن يتراكم", 6_000L, after2.paidAmount)
        assertEquals(InstallmentEngine.St.PARTIAL, after2.status)
    }

    // ─── السداد الكامل يغلق القسط (PAID) ويختم paidDate ───
    @Test
    fun `سداد_كامل_يغلق_القسط_ويحول_الحالة_الى_مدفوع`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق الكامل", p.id, 0, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()

        val paid = installments.pay(first, today = t0 + DAY) // بلا مبلغ = إغلاق المفتوح
        assertEquals(10_000L, paid)
        val after = installments.installmentsOf(planId).first()
        assertEquals(InstallmentEngine.St.PAID, after.status)
        assertEquals(10_000L, after.paidAmount)
        assertEquals(0L, after.open)
        assertEquals(t0 + DAY, after.paidDate!!)
        assertEquals(10_000L, reports.cashBalance())
        assertLedgerBalanced("قيد تحصيل القسط غير متوازن")
    }

    // ─── طلب سداد أعلى من المفتوح يقص إليه — لا يتجاوز القسط أبداً ───
    @Test
    fun `السداد_الزائد_على_القسط_يقص_الى_المفتوح`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق القص", p.id, 0, 30_000L, 0L, 3, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()

        val paidNow = installments.pay(first, 999_999L, t0 + DAY)
        assertEquals(10_000L, paidNow)
        val after = installments.installmentsOf(planId).first()
        assertEquals(InstallmentEngine.St.PAID, after.status)
        assertEquals(10_000L, after.paidAmount)
        // دفعة واحدة فقط بالقيمة المقصوصة
        val rows = db.payments().forParty(p.id)
        assertEquals(1, rows.size)
        assertEquals(10_000L, rows.single().amount)
    }

    // ─── تأجيل القسط يغير الاستحقاق فقط: المبلغ والمسدد والقيود والدفعات كما هي ───
    @Test
    fun `تأجيل_القسط_يحفظ_المبلغ_ويغير_الاستحقاق_فقط`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق التأجيل", p.id, 0, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()
        installments.pay(first, 4_000L, t0 + DAY)

        val linesBefore = db.journal().allLines().size
        val paymentsBefore = db.payments().since(0).size

        val ok = installments.reschedule(installments.installmentsOf(planId).first(), t0 + 30 * DAY, t0 + DAY)
        assertTrue("تأجيل قسط غير مسدد كلياً لتاريخ مستقبلي يجب أن ينجح", ok)

        val after = installments.installmentsOf(planId).first()
        assertEquals("تأجيل يجب أن يحفظ المبلغ", 10_000L, after.amount)
        assertEquals("تأجيل يجب أن يحفظ المسدد", 4_000L, after.paidAmount)
        assertEquals("تأجيل يجب أن يغيّر الاستحقاق إلى التاريخ الجديد", t0 + 30 * DAY, after.dueDate)
        assertEquals(linesBefore, db.journal().allLines().size)  // لا حركة مال
        assertEquals(paymentsBefore, db.payments().since(0).size)
    }

    // ─── تأجيل القسط المسدد كلياً أو لتاريخ يسبق اليوم يُرفض بلا تغيير ───
    @Test
    fun `تأجيل_القسط_المسدد_كليا_أو_لتاريخ_ماض_يرفض`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق الرفض", p.id, 0, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()
        installments.pay(first, today = t0)   // مسدد كلياً

        assertFalse("تأجيل قسط مسدد كلياً يجب أن يُرفض",
            installments.reschedule(installments.installmentsOf(planId).first(), t0 + 30 * DAY, t0))

        // تاريخ جديد يسبق بداية اليوم (today = t0) — الرفض حتمي بغضّ المنطقة الزمنية
        val second = installments.installmentsOf(planId)[1]
        val dueBefore = second.dueDate
        assertFalse("تأجيل لتاريخ ماضٍ يجب أن يُرفض",
            installments.reschedule(second, t0 - DAY, t0))
        assertEquals("الرفض يجب ألا يغيّر الاستحقاق", dueBefore, installments.installmentsOf(planId)[1].dueDate)
    }

    // ─── خطة مورد: الفتح على الذمم الدائنة والسداد منها والنقد يخرج ───
    @Test
    fun `خطة_المورد_ترحل_على_الذمم_الدائنة_والسداد_يخرج_نقدا`() = runBlocking {
        val s = supplier()
        val planId = installments.createPlan(
            "اتفاق شراء", s.id, 1, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        assertEquals(-60_000L, ledger.partyBalance(s.id))

        installments.pay(installments.installmentsOf(planId).first(), today = t0 + DAY)
        assertEquals(-50_000L, ledger.partyBalance(s.id))
        assertEquals(-10_000L, reports.cashBalance())   // نقد خرج للمورد

        val sums = db.journal().accountSums().associate { it.account to (it.d to it.c) }
        assertEquals(10_000L, sums[Accounts.PAYABLE]!!.first)    // سداد مدين للذمم الدائنة
        assertEquals(10_000L, sums[Accounts.CASH]!!.second)      // النقد دائن (خرج)
        assertLedgerBalanced("قيد خطة المورد غير متوازن")
    }

    // ─── حذف الخطة يعكس قيودها ويحذف دفعاتها وأقساطها — لا شبح في الدفتر ───
    @Test
    fun `حذف_الخطة_يعكس_قيودها_وينظف_دفعاتها_وأقساطها`() = runBlocking {
        val p = customer()
        val planId = installments.createPlan(
            "اتفاق الحذف", p.id, 0, 60_000L, 10_000L, 6, t0, "SAR", "", today = t0)
        installments.pay(installments.installmentsOf(planId).first(), today = t0 + DAY)
        assertTrue(db.journal().allLines().isNotEmpty())
        assertEquals(2, db.payments().forParty(p.id).size)   // مقدمة + قسط

        installments.deletePlan(db.installments().planById(planId)!!)

        // الخطة كانت المصدر الوحيد للقيود في هذا الاختبار — حذفها يعكسها كلها
        assertTrue(db.journal().allEntries().isEmpty())
        assertTrue(db.journal().allLines().isEmpty())
        assertTrue(db.payments().forParty(p.id).isEmpty())
        assertTrue(db.installments().installmentsOf(planId).isEmpty())
        assertNull(db.installments().planById(planId))
        assertLedgerBalanced("بعد حذف الخطة بقيت أسطر غير مقفلة")
    }

    // ═══════════════════════ ChecksRepo ═══════════════════════

    private suspend fun incomingCheck(partyId: Long, number: String = "CH-P37-0001") = CheckEntity(
        number = number, partyId = partyId, bank = "بنك الجدار",
        amount = 15_000L, issueDate = t0, dueDate = t0 + 30 * DAY, direction = 0, status = 0)

    // ─── إيداع الشيك يغير الحالة بلا أي قيد ولا دفعة ───
    @Test
    fun `إيداع_الشيك_يغير_الحالة_بلا_أي_قيد`() = runBlocking {
        val p = customer()
        val id = checks.save(incomingCheck(p.id))
        checks.setStatus(checks.check(id)!!, 1, t0 + DAY)

        assertEquals(1, checks.check(id)!!.status)
        assertTrue("الإيداع يجب ألا يرحل قيداً", db.journal().allLines().isEmpty())
        assertTrue(db.payments().since(0).isEmpty())
    }

    // ─── تحصيل شيك وارد: قيد متوازن (نقد/ذمم) + دفعة CHECK وحالة محصّل ───
    @Test
    fun `تحصيل_شيك_وارد_يولد_قيدا_متوازنا_ودفعة_شيك`() = runBlocking {
        val p = customer()
        val id = checks.save(incomingCheck(p.id))
        checks.setStatus(checks.check(id)!!, 2, t0 + DAY)

        assertEquals(2, checks.check(id)!!.status)
        assertLedgerBalanced("قيد تحصيل الشيك غير متوازن")
        val lines = db.journal().allLines()
        assertEquals(15_000L, lines.first { it.account == Accounts.CASH }.debit)
        val recv = lines.first { it.account == Accounts.RECEIVABLE }
        assertEquals(15_000L, recv.credit)
        assertEquals(p.id, recv.partyId)

        val pay = db.payments().since(0).single()
        assertEquals("CHECK", pay.method)
        assertEquals(0, pay.direction)
        assertEquals(15_000L, pay.amount)
        assertEquals(id, pay.checkId)
        assertEquals(15_000L, reports.cashBalance())
        assertEquals(-15_000L, ledger.partyBalance(p.id))
    }

    // ─── ارتجاع شيك بعد تحصيله يعكس القيد: النقد والذمة يعودان كما كانا ───
    @Test
    fun `ارتجاع_الشيك_بعد_التحصيل_يعكس_الاثر_النقدي_والذممي`() = runBlocking {
        val p = customer()
        val id = checks.save(incomingCheck(p.id))
        checks.setStatus(checks.check(id)!!, 2, t0 + DAY)
        assertEquals(15_000L, reports.cashBalance())

        checks.setStatus(checks.check(id)!!, 3, t0 + 2 * DAY)

        assertEquals(3, checks.check(id)!!.status)
        assertLedgerBalanced("قيد الارتجاع غير متوازن")
        assertEquals("صافي أثر التحصيل ثم الارتجاع يجب أن يكون صفراً", 0L, reports.cashBalance())
        assertEquals(0L, ledger.partyBalance(p.id))
        val bounce = db.payments().since(0).single { it.method == "CHECK_BOUNCE" }
        assertEquals(1, bounce.direction)
        assertEquals(15_000L, bounce.amount)
        assertEquals(id, bounce.checkId)
    }

    // ─── شيك صادر يُحصَّل: يقيد على الذمم الدائنة والنقد يخرج ───
    @Test
    fun `شيك_صادر_محصل_يقيد_على_الذمم_الدائنة_والنقد_يخرج`() = runBlocking {
        val s = supplier()
        val id = checks.save(CheckEntity(
            number = "CH-OUT-1", partyId = s.id, bank = "بنك",
            amount = 12_000L, issueDate = t0, dueDate = t0 + 15 * DAY, direction = 1, status = 0))
        checks.setStatus(checks.check(id)!!, 2, t0 + DAY)

        assertEquals(2, checks.check(id)!!.status)
        assertLedgerBalanced("قيد صفاء الشيك الصادر غير متوازن")
        val lines = db.journal().allLines()
        val payable = lines.first { it.account == Accounts.PAYABLE }
        assertEquals(12_000L, payable.debit)
        assertEquals(s.id, payable.partyId)
        assertEquals(12_000L, lines.first { it.account == Accounts.CASH }.credit)
        assertEquals(-12_000L, reports.cashBalance())
        assertEquals(1, db.payments().since(0).single().direction) // صادر
    }

    // ─── تحول غير مدرج في القائمة البيضاء (محصّل → قيد التحصيل) يُرفض بلا أي أثر ───
    @Test
    fun `تحول_غير_مدرج_في_القائمة_البيضاء_يرفض_بلا_اثر`() = runBlocking {
        val p = customer()
        val id = checks.save(incomingCheck(p.id))
        checks.setStatus(checks.check(id)!!, 2, t0 + DAY)
        val linesBefore = db.journal().allLines().size
        val paymentsBefore = db.payments().since(0).size

        checks.setStatus(checks.check(id)!!, 0, t0 + 2 * DAY) // مرفوض: يتطلب إلغاء ترحيل

        assertEquals("الحالة يجب ألا تتغير على تحول مرفوض", 2, checks.check(id)!!.status)
        assertEquals(linesBefore, db.journal().allLines().size)
        assertEquals(paymentsBefore, db.payments().since(0).size)
    }

    // ─── حراسة الحفظ: مبلغ سالب/صفر ورقم فارغ واستحقاق قبل الإصدار كلها تُرفض ───
    @Test
    fun `حفظ_شيك_بمبلغ_غير_شرعي_أو_رقم_فارغ_أو_استحقاق_قبل_الإصدار_يرفض`() = runBlocking {
        val p = customer()
        val base = incomingCheck(p.id)
        for (bad in listOf(base.copy(amount = 0L), base.copy(amount = -100L))) {
            try {
                checks.save(bad)
                fail("مبلغ ${bad.amount} يجب أن يُرفض")
            } catch (e: IllegalArgumentException) {
            }
        }
        try {
            checks.save(base.copy(number = "  "))
            fail("رقم فارغ يجب أن يُرفض")
        } catch (e: IllegalArgumentException) {
        }
        try {
            checks.save(base.copy(dueDate = t0 - DAY))
            fail("استحقاق قبل الإصدار يجب أن يُرفض")
        } catch (e: IllegalArgumentException) {
        }
        assertTrue(db.checks().allOnce().isEmpty())
        assertTrue(checks.save(base) > 0L) // والصالح يُقبل
    }

    // ═══════════════════════ ExpenseRepo ═══════════════════════

    // ─── المصروف يُرحَّل قيداً متوازناً: مدين مصروفات / دائن نقد ───
    @Test
    fun `المصروف_يرحل_قيدا_متوازنا_مدين_مصروفات_دائن_نقد`() = runBlocking {
        val id = expenses.add(5_000L, "إيجار", "إيجار المحل", t0)
        assertTrue(id > 0L)

        assertLedgerBalanced("قيد المصروف غير متوازن")
        val lines = db.journal().allLines()
        assertEquals(5_000L, lines.first { it.account == Accounts.EXPENSE }.debit)
        assertEquals(5_000L, lines.first { it.account == Accounts.CASH }.credit)
        assertEquals(-5_000L, reports.cashBalance())
        assertEquals(5_000L, reports.incomeStatement(t0 - DAY, t0 + DAY).expenses)
        assertEquals(1, db.expenses().count())
    }

    // ─── المصروف يظهر في فترته وفئته فقط — نافذة صريحة حتمية ───
    @Test
    fun `المصروف_يظهر_في_فترته_وفئته_فقط`() = runBlocking {
        expenses.add(5_000L, "إيجار", "", t0)
        expenses.add(3_000L, "نقل وشحن", "", t0 + DAY)

        assertEquals(5_000L, expenses.totalBetween(t0 - DAY, t0))
        assertEquals(8_000L, expenses.totalBetween(t0 - DAY, t0 + DAY))
        assertEquals(3_000L, expenses.totalBetween(t0 + DAY, t0 + DAY))
        assertEquals(0L, expenses.totalBetween(t0 + 2 * DAY, t0 + 3 * DAY))

        val since0 = expenses.byCategorySince(t0 - DAY).toMap()
        assertEquals(5_000L, since0["إيجار"])
        assertEquals(3_000L, since0["نقل وشحن"])
        val sinceLate = expenses.byCategorySince(t0 + DAY).toMap()
        assertEquals(setOf("نقل وشحن"), sinceLate.keys)
    }

    // ─── حذف المصروف يعكس قيده بالكامل — لا أثر في الدفتر ولا في النقد ───
    @Test
    fun `حذف_المصروف_يعكس_قيده_بالكامل`() = runBlocking {
        val id = expenses.add(5_000L, "كهرباء وماء", "", t0)
        assertEquals(-5_000L, reports.cashBalance())

        val saved = db.expenses().between(0, Long.MAX_VALUE).first { it.id == id }
        expenses.delete(saved)

        assertEquals(0, db.expenses().count())
        assertEquals(0L, reports.cashBalance())
        assertTrue("بقيت أسطر قيد المصروف بعد الحذف", db.journal().allLines().isEmpty())
    }

    // ─── مصروف بصفر أو سالب يُرفض بلا أي أثر ───
    @Test
    fun `رفض_مصروف_بصفر_أو_سالب_بلا_أثر`() = runBlocking {
        for (bad in listOf(0L, -2_500L)) {
            try {
                expenses.add(bad, "أخرى", "", t0)
                fail("مبلغ $bad يجب أن يُرفض")
            } catch (e: IllegalArgumentException) {
            }
        }
        assertEquals(0, db.expenses().count())
        assertTrue(db.journal().allLines().isEmpty())
        assertEquals(0L, reports.cashBalance())
    }
}
