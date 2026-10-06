package com.superbiz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.data.repo.SettingsRepo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * : اختبارات الشيكات والنسخ الاحتياطي على قاعدة Room حقيقية.
 * الأهم: جولة تصدير→استعادة كاملة الدقة تحفظ كل شيء (حتى الشيكات المحصّلة
 * والأعلام المؤرشفة التي كانت تُفقد سابقاً)، والاستيراد الفاشل لا يمسّ البيانات أصلاً.
 * [P33-P8] كل المبالغ المخزنة قروش Long — مقارنات تامة بلا عتبات عائمة.
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChecksAndBackupTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var ledger: LedgerRepo
    private lateinit var checks: ChecksRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var expenses: ExpenseRepo
    private lateinit var reports: ReportsRepo
    private lateinit var backup: BackupRepo

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        checks = ChecksRepo(db, ledger)
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        expenses = ExpenseRepo(db, ledger)
        reports = ReportsRepo(db)
        backup = BackupRepo(ctx, db, SettingsRepo(ctx))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun party(name: String, type: Int = 0): Party {
        val id = ledger.saveParty(Party(name = name, type = type))
        return ledger.party(id)!!
    }

    // ─── تحصيل شيك وارد: النقد يدخل والذمم تنقص، ثم الارتجاع يعكس كل شيء ───
    @Test
    fun incomingCheck_clearThenBounce_netZero() = runBlocking {
        val p = party("شيكاتي")
        val c = CheckEntity(
            number = "CH-1", partyId = p.id, amount = 30000L,  // [P33-P8] كان 300.0 ريال
            issueDate = t0, dueDate = t0, direction = 0, status = 0
        )
        val id = checks.save(c)
        val saved = checks.check(id)!!

        checks.setStatus(saved, 2)   // محصّل
        assertEquals(30000L, reports.cashBalance())
        assertEquals(-30000L, ledger.partyBalance(p.id))
        assertEquals(2, checks.check(id)!!.status)
        assertTrue(db.payments().forParty(p.id).any { it.method == "CHECK" && it.checkId == id })

        checks.setStatus(checks.check(id)!!, 3)   // ارتجاع
        assertEquals(0L, reports.cashBalance())
        assertEquals(0L, ledger.partyBalance(p.id))
        assertTrue(db.payments().forParty(p.id).any { it.method == "CHECK_BOUNCE" })
    }

    // ─── شيك صادر يُصرف: النقد يخرج والذمم الدائنة تنقص ───
    @Test
    fun outgoingCheck_clear_paysCash() = runBlocking {
        val sup = party("مورد الشيكات", 1)
        val id = checks.save(
            CheckEntity(number = "CH-2", partyId = sup.id, amount = 15000L,  // [P33-P8] كان 150.0 ريال
                issueDate = t0, dueDate = t0, direction = 1, status = 0))
        checks.setStatus(checks.check(id)!!, 2)
        assertEquals(-15000L, reports.cashBalance())
    }

    // ─── جولة نسخ احتياطي كاملة: كل شيء يعود كما كان ───
    @Test
    fun backupRoundtrip_restoresEverything() = runBlocking {
        // 1) بيانات غنية
        val p1 = party("أحمد")
        val sup = party("مورد", 1)
        val product = Product(name = "صنف", costPrice = 1000L, salePrice = 1500L, stockQty = 5.0)  // [P33-P8] كان 10.0/15.0 ريال
        val pid = inventory.saveProduct(product)
        inventory.moveStock(inventory.product(pid)!!, -2.0, "ADJUST", t0, "عينة", postJournal = false)
        val archived = inventory.product(pid)!!.copy(archived = true)
        inventory.saveProduct(archived)   // علم أرشفة يجب أن يُحفظ

        val invId = invoices.save(
            Invoice(number = "INV-0001", partyId = p1.id, type = 0, date = t0, dueDate = t0,
                subtotal = 10000L, taxAmount = 0L, total = 10000L, costTotal = 2000L, currency = "SAR"),  // [P33-P8] كان 100.0/0.0/100.0/20.0 ريال
            listOf(InvoiceItem(invoiceId = 0, productId = null, desc = "بند", qty = 1.0, unitPrice = 10000L)))
        ledger.addDebt(p1, 8000L, t0, "دين")
        expenses.add(4000L, "نقل", "توصيل")

        val checkId = checks.save(CheckEntity(number = "CH-9", partyId = p1.id, amount = 6000L,
            issueDate = t0, dueDate = t0, direction = 0, status = 0))
        checks.setStatus(checks.check(checkId)!!, 2)   // شيك محصّل — كان يُفقد في النسخ القديم!

        val exported = backup.exportJson()
        // [P33-P8] نسخة الصيغة الحالية (3 = قروش) — ثابت من المصدر كي لا يكسرها ترحيل قادم
        assertEquals(BackupRepo.FORMAT_VERSION, exported.getInt("format"))
        val cashBefore = reports.cashBalance()
        val balancesBefore = ledger.balances()

        // 2) اكتب النسخة إلى ملف مؤقت
        val f = File(ctx.cacheDir, "backup-test-${System.currentTimeMillis()}.json")
        f.writeText(exported.toString(2))

        // 3) أفسد قاعدة البيانات ببيانات دخيلة، ثم استعدها
        ledger.saveParty(Party(name = "Junk_${System.nanoTime()}"))
        val imported = backup.importFrom(Uri.fromFile(f))
        assertTrue("الاستعادة فشلت", imported)

        // 4) كل شيء عاد تماماً
        val balancesAfter = ledger.balances()
        assertEquals(balancesBefore.size, balancesAfter.size)
        for ((pid2, bal) in balancesBefore) {
            // [P33-P8] أرصدة قروش Long — مساواة تامة بلا عتبة 0.005
            assertEquals("رصيد الطرف $pid2 اختلف", bal, balancesAfter[pid2]!!)
        }
        assertEquals(cashBefore, reports.cashBalance())
        assertEquals(1, db.invoices().count())   // فاتورة واحدة فقط (الدخيل مُسح)
        assertEquals("INV-0001", invoices.invoice(invId)!!.number)

        // الشيك المحصّل (status=2) عاد بحالته — الإصلاح الحاسم
        assertEquals(2, checks.check(checkId)!!.status)

        // علم الأرشفة عاد — الإصلاح الحاسم الثاني
        assertTrue(inventory.product(pid)!!.archived)

        // المصروف عاد
        assertEquals(1, db.expenses().count())

        // الدفتر متوازن بعد الاستعادة — [P33-P8] مساواة صحيحة تامة
        val d = db.journal().allLines().sumOf { it.debit }
        val c = db.journal().allLines().sumOf { it.credit }
        assertEquals(d, c)
        f.delete()
        Unit // JUnit4 يشترط void — كان f.delete() يعيد Boolean فيُرفض الصنف كلياً
    }

    // ─── استيراد فاسد: يُرفض ولا يُمسّ شيء (لا فقدان بيانات أبداً) ───
    @Test
    fun corruptImport_leavesDatabaseUntouched() = runBlocking {
        val p1 = party("ثابت")
        ledger.addDebt(p1, 50000L, t0, "دين قبل المحاولة")  // [P33-P8] كان 500.0 ريال
        val balanceBefore = ledger.partyBalance(p1.id)
        val partiesBefore = db.parties().count()

        // JSON سليم الشكل لكن فاتورته ناقصة حقل total إلزامي
        val bad = JSONObject()
            .put("app", "SuperBiz")
            .put("format", 2)
            .put("invoices", org.json.JSONArray()
                .put(JSONObject().put("id", 1).put("number", "X").put("subtotal", 5.0)))
        val f = File(ctx.cacheDir, "bad-${System.currentTimeMillis()}.json")
        f.writeText(bad.toString())

        val ok = backup.importFrom(Uri.fromFile(f))
        assertFalse("يجب رفض الملف الفاسد", ok)

        // قاعدة البيانات كما كانت حرفياً
        assertEquals(partiesBefore, db.parties().count())
        assertEquals(balanceBefore, ledger.partyBalance(p1.id))
        assertTrue(db.journal().allLines().isNotEmpty())
        f.delete()
        Unit // JUnit4 يشترط void
    }
}
