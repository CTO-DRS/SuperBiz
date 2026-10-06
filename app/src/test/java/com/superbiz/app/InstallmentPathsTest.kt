package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.InstallmentEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * : اختبارات مسار الأقساط الكامل على قاعدة Room حقيقية
 * فتح الخطة = خطة + جدول + قيد + دفعة مقدمة؛ السداد = قيد + دفعة + حالة القسط؛
 * الحذف = عكس كل القيود. كلها ذرّية — تُقاس هنا على البيانات الفعلية.
 *
 * [P33-P8] كل مبالغ الاختبار كانت ريال Double وصارت قروش Long (×100) —
 * والتوازن والأرصدة مساواة تامة بلا أي عتبة (0.005/0.01/0.001 حُذفت).
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstallmentPathsTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var reports: ReportsRepo

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        installments = InstallmentRepo(db, ledger)
        reports = ReportsRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun customer(name: String = "عميل تقسيط"): Party {
        val id = ledger.saveParty(Party(name = name, type = 0))
        return ledger.party(id)!!
    }

    private val t0 = 1_700_000_000_000L

    // ─── فتح خطة: خطة + جدول 10 أقساط + قيدا بيع ومقدمة + دفعة مقدمة ───
    @Test
    fun createPlan_buildsEverythingConsistently() = runBlocking {
        val party = customer()
        // [P33-P8] كانت 1200/200 ريالاً — صارت 120_000/20_000 قروشاً
        val planId = installments.createPlan(
            title = "اتفاق 1", partyId = party.id, direction = 0,
            total = 120_000L, downPayment = 20_000L, months = 10,
            startDate = t0, currency = "SAR", note = "", today = t0
        )

        // الجدول: 10 أقساط مجموعها المبلغ المجدول تماماً (1200.00 − 200.00 = 1000.00 ريال)
        val rows = installments.installmentsOf(planId)
        assertEquals(10, rows.size)
        assertEquals(100_000L, rows.sumOf { it.amount }) // [P33-P8] مساواة تامة بلا 0.005

        // الدفعة المقدمة مسجلة كدفعة حقيقية
        val pays = db.payments().forParty(party.id)
        assertEquals(1, pays.size)
        assertEquals("INSTALLMENT_DOWN", pays[0].method)
        assertEquals(20_000L, pays[0].amount)

        // القيود: بيع بالتقسيط 1200 + قبض مقدمة 200 → رصيد العميل 1000 (100_000 قروش)
        assertEquals(100_000L, ledger.partyBalance(party.id))

        // النقد زاد بمقدار المقدمة فقط
        assertEquals(20_000L, reports.cashBalance())

        // الدفتر متوازن — [P33-P8] مساواة تامة بلا عتبة
        val d = db.journal().allLines().sumOf { it.debit }
        val c = db.journal().allLines().sumOf { it.credit }
        assertEquals(d, c)
    }

    // ─── سداد جزئي ثم كلي: حالات القسط والقيود تتحدث ذرّياً ───
    @Test
    fun pay_partialThenFull_updatesStatusCorrectly() = runBlocking {
        val party = customer()
        val planId = installments.createPlan(
            "اتفاق 2", party.id, 0, 60_000L, 0L, 6, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()

        // سداد نصف القسط الأول (50.00 من 100.00 ريال = 5_000 من 10_000 قروش)
        val paid = installments.pay(first, 5_000L, t0)
        assertEquals(5_000L, paid)
        val afterPartial = installments.installmentsOf(planId).first()
        assertEquals(InstallmentEngine.St.PARTIAL, afterPartial.status)
        assertEquals(5_000L, afterPartial.paidAmount)

        // إتمام القسط الأول
        installments.pay(afterPartial, 5_000L, t0)
        val afterFull = installments.installmentsOf(planId).first()
        assertEquals(InstallmentEngine.St.PAID, afterFull.status)
        assertEquals(10_000L, afterFull.paidAmount)

        // رصيد العميل نقص 100.00، والنقد زاد 100.00 (10_000 قروش)
        assertEquals(50_000L, ledger.partyBalance(party.id))
        assertEquals(10_000L, reports.cashBalance())

        // سطرا دفعة حقيقيان
        assertEquals(2, db.payments().forParty(party.id).size)
    }

    // ─── طلب سداد أعلى من المتبقي: يُثبَّت على المتبقي ولا يتجاوزه ───
    @Test
    fun pay_overPaymentIsClamped() = runBlocking {
        val party = customer()
        val planId = installments.createPlan(
            "اتفاق 3", party.id, 0, 30_000L, 0L, 3, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()
        val paid = installments.pay(first, 99_900L, t0) // [P33-P8] كانت 999.0 ريالاً — طلب متجاوز بوضوح
        assertEquals(10_000L, paid)   // القسط الأول 100.00 ريال = 10_000 قروش فقط
        val updated = installments.installmentsOf(planId).first()
        assertEquals(InstallmentEngine.St.PAID, updated.status)
    }

    // ─── سداد القسط المدفوع فعلاً: لا شيء يحدث ───
    @Test
    fun pay_alreadyPaid_noop() = runBlocking {
        val party = customer()
        val planId = installments.createPlan(
            "اتفاق 4", party.id, 0, 20_000L, 0L, 2, t0, "SAR", "", today = t0)
        val first = installments.installmentsOf(planId).first()
        installments.pay(first, null, t0)
        val again = installments.pay(first, null, t0)
        assertEquals(0L, again)
        assertEquals(1, db.payments().forParty(party.id).size)
    }

    // ─── حذف الخطة: كل قيودها وأقساطها تختفي والرصيد يعكس ذلك ───
    @Test
    fun deletePlan_unpostsEverything() = runBlocking {
        val party = customer()
        val planId = installments.createPlan(
            "اتفاق 5", party.id, 0, 90_000L, 10_000L, 9, t0, "SAR", "", today = t0)
        installments.pay(installments.installmentsOf(planId).first(), null, t0)

        val plan = installments.plan(planId)!!
        installments.deletePlan(plan)

        assertEquals(0, db.installments().planCount())
        assertEquals(0, installments.installmentsOf(planId).size)
        // لا قيود مرتبطة بالخطط
        val planLines = db.journal().allLines().filter { l ->
            db.journal().allEntries()
                .filter { it.refType == "plan" }
                .any { it.id == l.entryId }
        }
        assertTrue("بقيت قيود الخطة بعد الحذف", planLines.isEmpty())
        // رصيد العميل عاد صفراً (القيود عُكست) — الدفعات النقدية التاريخية تبقى كما هي
        assertEquals(0L, ledger.partyBalance(party.id))
    }

    // ─── خطة مورد (اتجاه معاكس): قيد شراء وسداد للمورد ───
    @Test
    fun supplierPlan_postsPayableSide() = runBlocking {
        val supplierId = ledger.saveParty(Party(name = "مورد", type = 1))
        val planId = installments.createPlan(
            "شراء بالتقسيط", supplierId, 1, 40_000L, 0L, 4, t0, "SAR", "", today = t0)
        val sums = db.journal().accountSums().associate { it.account to (it.d to it.c) }
        assertEquals(40_000L, sums[Accounts.INVENTORY]!!.first)
        assertEquals(40_000L, sums[Accounts.PAYABLE]!!.second - sums[Accounts.PAYABLE]!!.first)
        // سداد قسط لمورد ينقص النقد
        installments.pay(installments.installmentsOf(planId).first(), null, t0)
        assertEquals(-10_000L, reports.cashBalance())
    }
}
