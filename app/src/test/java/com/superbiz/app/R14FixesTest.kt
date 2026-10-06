package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.EarlyPay
import com.superbiz.app.domain.InstallmentEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * — اختبارات انحدار لإصلاحات الموجة (كلها تفشل قبل الإصلاح)
 * F2 استيراد النسخة يقرأ العلم المؤرشف للخطط
 * F3 تسوية السداد المبكر تغلق القسط كلياً بقيد إعداب متوازن
 * F15 دمج عميل في مورّد يوحّد الدورين (type=2)
 * F19 ملخص الضريبة يجمع المحجوز (taxAmount) لا الاشتقاق المجدد
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R14FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var settings: SettingsRepo
    private lateinit var backup: BackupRepo
    private lateinit var reports: ReportsRepo
    private lateinit var ctx: Context

    @Before
    fun setup() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        installments = InstallmentRepo(db, ledger)
        settings = SettingsRepo(ctx)
        backup = BackupRepo(ctx, db, settings)
        reports = ReportsRepo(db)
        Unit
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun party(name: String, type: Int = 0): Party =
        Party(name = name, type = type).let { it.copy(id = db.parties().upsert(it)) }

    // ═══ F2: استيراد النسخة يستعيد الخطط المؤرشفة كنشطة ═══
    @Test
    fun importRestoresArchivedPlanFlag() = runBlocking {
        val pt = party("عميل الأرشفة")
        val t0 = System.currentTimeMillis()
        val planId = installments.createPlan("خطة مؤرشفة", pt.id, 0, 30_000L, 0L, 3, t0, "SAR", "")   // [P33-P8] 300.0/0.0 ريال → قروش
        db.installments().archivePlan(planId)

        val exported = backup.exportJson()
        val f = File(ctx.cacheDir, "r14-arch-test-${System.currentTimeMillis()}.json")
        f.writeText(exported.toString(2))
        assertTrue("الاستعادة فشلت", backup.importFrom(android.net.Uri.fromFile(f)))

        val restored = db.installments().plansExport().firstOrNull { it.title == "خطة مؤرشفة" }
        assertTrue("الخطة المستعادة غير موجودة", restored != null)
        assertTrue("الاستيراد أسقط العلم المؤرشف (R14-F2)", restored!!.archived)
    }

    // ═══ F3: التسوية المخفَّضة تغلق القسط كلياً بميزان متوازن ═══
    @Test
    fun earlySettlementClosesInstallmentAndBalances() = runBlocking {
        val pt = party("عميل السداد المبكر")
        val t0 = System.currentTimeMillis()
        val day = 86_400_000L
        // الاستحقاق بعد 30 يوماً — السداد «المبكر» يتطلب أن يكون قبل تاريخ الاستحقاق
        val planId = installments.createPlan("خطة مبكرة", pt.id, 0, 10_000L, 0L, 2, t0 + 30 * day, "SAR", "")   // [P33-P8] 100.0/0.0 ريال → قروش
        val inst = installments.installmentsOf(planId).first()
        val open = inst.amount - inst.paidAmount   // [P33-P8] قروش — فرق صحيح تام
        val quote = EarlyPay.quote(open)!!

        val recBefore = db.journal().accountSums().firstOrNull { it.account == "1100" }?.let { it.d - it.c } ?: 0L   // [P33-P8] قروش Long
        val paid = installments.payEarlySettlement(inst, t0)
        assertEquals(quote.first, paid)   // [P33-P8] مساواة تامة بالقروش (كانت 1e-6)

        val fresh = db.installments().installmentsOf(planId).first { it.id == inst.id }
        assertEquals("القسط لم يُغلق كلياً (R14-F3)", 0L, fresh.open)   // [P33-P8] مساواة تامة — عتبة 0.005 حُذفت
        assertEquals(InstallmentEngine.St.PAID, fresh.status)

        // الميزان: مجموع المدين = مجموع الدائن بعد قيد التحصيل + قيد الإعداب
        val sums = db.journal().accountSums()
        val d = sums.sumOf { it.d }   // [P33-P8] مجاميع قروش صحيحة
        val c = sums.sumOf { it.c }
        assertEquals("قيدا التسوية غير متوازنين", d, c)   // [P33-P8] اتزان تام — عتبة 0.005 حُذفت
        // الذمم نقصت بقدر المفتوح كاملاً (4900 تحصيل + 100 إعداب) — قبل الإصلاح كانت تنقص 4900 فقط
        val recAfter = (db.journal().accountSums().firstOrNull { it.account == "1100" })?.let { it.d - it.c } ?: 0L   // [P33-P8] قروش Long
        assertEquals("بقاء على الذمم = قيمة الخصم (علة R14-F3)", recBefore - open, recAfter)   // [P33-P8] مساواة تامة — عتبة 0.005 حُذفت
    }

    // ═══ F15: الدمج عبر الدورين يوحّد النوع ═══
    @Test
    fun mergeCrossRolesSetsTypeBoth() = runBlocking {
        val customer = party("عميل للدمج", 0)
        val supplier = party("مورد للدمج", 1)
        assertTrue(ledger.mergeParties(customer.id, supplier.id))
        val merged = db.parties().byId(customer.id)!!
        assertEquals("دمج العبر-الأدوار يجب أن ينتج كلاهما (R14-F15)", 2, merged.type)
    }

    // ═══ F19: ملخص الضريبة يجمع taxAmount المحجوز ═══
    @Test
    fun vatByRateSumsStoredTaxAmount() = runBlocking {
        val pt = party("عميل الضريبة")
        val t0 = System.currentTimeMillis()
        // صافي 33.33 ريال (3_333 قروشاً) بنسبة 15٪: المحجوز 500 قروشاً (5.00 ريال) بينما الاشتقاق الحرفي 499.95 [P33-P8]
        val inv = Invoice(
            number = "VAT-1", partyId = pt.id, type = 0, date = t0, dueDate = t0,
            subtotal = 3_333L, taxRate = 15.0, taxAmount = 500L, total = 3_833L, currency = "SAR",   // [P33-P8] المبالغ قروش — النسبة تبقى Double
        )
        db.invoices().upsert(inv)
        val rows = reports.vatByRate(t0 - 1, t0 + 1)
        assertEquals(1, rows.size)
        val (rate, net, vat) = rows[0]
        assertEquals(0.15, rate, 1e-9)
        assertEquals(3_333L, net)   // [P33-P8] قروش — مساواة تامة بلا عتبة 1e-9
        assertEquals("يجب جمع taxAmount المحجوز لا اشتقاقه (R14-F19)", 500L, vat)   // [P33-P8] قروش — مساواة تامة
    }
}
