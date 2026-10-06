package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.SettingsRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * — اختبارات انحدار لإصلاحات الموجة (كلها تفشل قبل الإصلاح)
 * B1 عنوان الخطة بـ% لم يعد يمسح دفعات خطط أخرى — B9 هدف شهري Infinity يُرفض
 * B15 مرتجع الشيك لا يُحسب تحصيلاً في درجة المخاطر
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R13FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var settings: SettingsRepo

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        installments = InstallmentRepo(db, ledger)
        settings = SettingsRepo(ctx)
        Unit
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun party(name: String): Party =
        Party(name = name, type = 0).let { it.copy(id = db.parties().upsert(it)) }

    // ═══ B1: LIKE wildcard في حذف دفعات الخطة ═══
    @Test
    fun planTitleWithWildcard_cleansOnlyOwnPayments() = runBlocking {
        val pt = party("عميل التجارب")
        val now = System.currentTimeMillis()
        // خطتان عناوينهما تتشاركان البادئة والأولى بـ% (حرف بدل LIKE)
        // [P33-P8] مبالغ قروش: 300.0/0.0 و600.0/0.0 ريال → 30_000/0 و60_000/0
        val plan1 = installments.createPlan("خصم 50% عرض", pt.id, 0, 30_000L, 0L, 2, now, "SAR", "")
        val plan2 = installments.createPlan("خصم 50% عرض إضافي", pt.id, 0, 60_000L, 0L, 3, now + 1000, "SAR", "")
        // سداد قسط من كل خطة — يولّد صفّي Payment بـ INSTALLMENT
        installments.pay(db.installments().installmentsOf(plan1).first(), null, now + 2000)
        installments.pay(db.installments().installmentsOf(plan2).first(), null, now + 3000)
        assertEquals(2, db.payments().since(0).count { it.method == "INSTALLMENT" })

        // حذف الخطة الأولى (بعنوان %) يجب أن ينظف دفعتها فقط — لا دفعة الخطة الثانية
        installments.deletePlan(db.installments().plansExport().first { it.id == plan1 })
        val remaining = db.payments().since(0).filter { it.method == "INSTALLMENT" }
        assertEquals("دفعة الخطة الثانية يجب أن تبقى", 1, remaining.size)
        assertEquals(3, db.installments().installmentsOf(plan2).size)
        assertNull(db.installments().planById(plan1))
    }

    // ═══ B9: الهدف الشهري يرفض Infinity وNaN (0 = معطّل) ═══
    @Test
    fun monthlyGoal_rejectsInfinityAndNaN() = runBlocking {
        settings.setMonthlyGoal(Double.POSITIVE_INFINITY)
        assertEquals(0.0, settings.snapshot().monthlyGoal, 1e-9)
        settings.setMonthlyGoal(Double.NaN)
        assertEquals(0.0, settings.snapshot().monthlyGoal, 1e-9)
        settings.setMonthlyGoal(5000.0)
        assertEquals(5000.0, settings.snapshot().monthlyGoal, 1e-9)
    }

    // ═══ B15: مرتجع الشيك (direction=1) لا يُحسب تحصيلاً في المخاطر ═══
    @Test
    fun bouncedCheckNotCountedAsPaidInRisk() = runBlocking {
        val pt = party("عميل الشيكات المرتدة")
        val now = System.currentTimeMillis()
        val invId = db.invoices().upsert(
            Invoice(number = "R13-1", partyId = pt.id, type = 0,
                date = now, dueDate = now, subtotal = 100_000L, total = 100_000L)   // [P33-P8] 1000.0 ريال → 100_000 قروشاً
        )
        // فاتورة مفتوحة بالكامل + شيك تحصّل ثم ارتد (صفان: وارد CHECK وصادر CHECK_BOUNCE)
        db.payments().insert(Payment(partyId = pt.id, invoiceId = invId, amount = 100_000L,   // [P33-P8] قروش
            date = now, direction = 0, method = "CHECK"))
        db.payments().insert(Payment(partyId = pt.id, invoiceId = invId, amount = 100_000L,   // [P33-P8] قروش
            date = now + 1, direction = 1, method = "CHECK_BOUNCE"))
        val risk = ledger.risk(pt.id, now + 2 * 86_400_000L)
        // قبل B15: totalPaid=2000 (المرتجع يُجمع كتحصيل) ⇒ انتظام السداد «عادي» (2 نقاط)
        // بعد B15: totalPaid=0 ⇒ غرامة الانضباط كاملة (10 نقاط) — الفاتورة غير مسددة فعلياً
        assertTrue(risk.factors.any { it.startsWith("discipline=10") })
    }
}
