package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.AccountingEngine
import com.superbiz.app.domain.ArabicWords
import com.superbiz.app.export.DataExport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * — اختبارات انحدار لإصلاحات الموجة
 * كل إصلاح يفشل قبله وينجح بعده (ما أمكن بلا Android UI).
 * C2 نقل شيكات/خطط الدمج — C3 دفعات الخطة الشبحية — C4 DEBT ليس نقداً
 * C6/C8 حراسة اللانهاية — C11 لا مادة رمز في النسخة — C16 دقة الخلايا — C18 تفقيط المليارات
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R12FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var checks: ChecksRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var expenses: ExpenseRepo
    private lateinit var backup: BackupRepo

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        checks = ChecksRepo(db, ledger)
        installments = InstallmentRepo(db, ledger)
        expenses = ExpenseRepo(db, ledger)
        backup = BackupRepo(ctx, db, SettingsRepo(ctx))
        Unit
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun party(name: String): Party {
        val p = Party(name = name, type = 0)
        return p.copy(id = db.parties().upsert(p))
    }

    // ═══ C2: دمج الأطراف ينقل الشيكات وخطط الأقساط أيضاً ═══

    @Test fun c2_mergeParties_movesChecksAndPlans() = runBlocking {
        val keep = party("المُبقي")
        val dup = party("المكرر")
        val day = 86_400_000L
        // [P33-P8] مبالغ قروش: 500.0/900.0 ريال → 50_000/90_000 قروشاً
        checks.save(CheckEntity(number = "11", partyId = dup.id, amount = 50_000L,
            issueDate = day, dueDate = 2 * day))
        installments.createPlan(title = "خطة المكرر", partyId = dup.id, direction = 0,
            total = 90_000L, downPayment = 0L, months = 3, startDate = day,
            currency = "SAR", note = "")
        assertTrue(ledger.mergeParties(keep.id, dup.id))
        assertEquals(keep.id, db.checks().allOnce().single { it.number == "11" }.partyId)
        assertEquals(keep.id, db.installments().plansOnce().single().partyId)
        // لا رصيد وهمي باقي على الطرف المحذوف
        assertFalse(db.journal().partyBalances().any { it.pid == dup.id })
    }

    // ═══ C3: حذف الخطة ينظّف دفعاتها (INSTALLMENT/INSTALLMENT_DOWN) ═══

    @Test fun c3_deletePlan_removesGhostInstallmentPayments() = runBlocking {
        val pr = party("مقسّط")
        val planId = installments.createPlan(title = "خطة فريدة تماماً", partyId = pr.id,
            direction = 0, total = 30_000L, downPayment = 10_000L, months = 2,   // [P33-P8] 300.0/100.0 ريال → قروش
            startDate = 86_400_000L, currency = "SAR", note = "")
        val rows = db.installments().installmentsOf(planId)
        installments.pay(rows.first(), null)
        val countBefore = db.payments().since(0)
            .count { it.method == "INSTALLMENT" || it.method == "INSTALLMENT_DOWN" }
        assertEquals(2, countBefore)   // دفعة مقدمة + قسط واحد
        installments.deletePlan(db.installments().planById(planId)!!)
        val countAfter = db.payments().since(0)
            .count { it.method == "INSTALLMENT" || it.method == "INSTALLMENT_DOWN" }
        assertEquals(0, countAfter)
    }

    // ═══ C4: receivedBetweenCash يستبعد قيود «دين جديد» ═══

    @Test fun c4_receivedBetweenCash_excludesDebt() = runBlocking {
        val now = System.currentTimeMillis()
        db.payments().insert(Payment(amount = 50_000L, date = now, direction = 0, method = "DEBT"))   // [P33-P8] 500.0 ريال → قروش
        db.payments().insert(Payment(amount = 20_000L, date = now, direction = 0, method = "CASH"))   // [P33-P8] 200.0 ريال → قروش
        // [تدقيق L-1] receivedBetween (Double) حُذفت ميتة — الاسم كان يوحي بقبض نقدي
        // بينما يعيد مجموع DEBT أيضاً؛ العقد الوحيد الحي: receivedBetweenCash
        assertEquals(20_000L, db.payments().receivedBetweenCash(now - 1000, now + 1000))   // [P33-P8] 200.0 ريال = 20_000 قروشاً — مساواة تامة
    }

    // ═══ C6: المصروفات ترفض اللانهاية و NaN ═══

    @Test fun c6_expenseRepo_rejectsNonFinite() {
        // [P33-P8] كان يرفض NaN/∞ — Long لا يكون كليهما؛ ما تبقى مرفوضاً فعلاً: غير الموجب (require amount > 0)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { expenses.add(0L, "أخرى", "") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { expenses.add(-1L, "أخرى", "") }
        }
    }

    // ═══ C8: الشيكات ترفض اللانهاية ═══

    @Test fun c8_checksRepo_rejectsInfiniteAmount() {
        // [P33-P8] كان Infinity — Long لا يكون لانهائياً؛ السالب يبقى مرفوضاً بحدّ amount > 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                checks.save(CheckEntity(number = "x", partyId = 1L,
                    amount = -1L,
                    issueDate = 0L, dueDate = 86_400_000L))
            }
        }
    }

    // ═══ C11: النسخة الاحتياطية بلا مادة الرمز نهائياً ═══

    @Test fun c11_backupJson_hasNoPinMaterial() = runBlocking {
        val json = backup.exportJson().toString()
        assertFalse(json.contains("pinHash"))
        assertFalse(json.contains("pinBlob"))
        assertFalse(json.contains("pinSalt"))
        assertTrue(json.contains("\"app\":\"SuperBiz\""))
    }

    // ═══ C16: خلايا xlsx رقمية بدقة كاملة وبدون NaN ═══

    @Test fun c16_xlsxNumbers_fullPrecision() {
        val out = ByteArrayOutputStream()
        DataExport.writeXlsx(out, "test", listOf("qty", "amt"),
            listOf(listOf(2.375, 100.0), listOf(Double.NaN, null)))
        val xml = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var e = zip.nextEntry
            var found = ""
            while (e != null) {
                if (e.name.endsWith("sheet1.xml")) found = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
            found
        }
        assertTrue(xml.contains("<v>2.375</v>"))
        assertFalse(xml.contains("2.38"))
        assertFalse(xml.contains("NaN"))
    }

    // ═══ C18: تفقيط المبالغ الضخمة لا يُسقط المسار + القيد المالي متوازن دائماً ═══

    @Test fun c18_tafqith_billionGracefulAndEngineBalance() {
        val big = ArabicWords.amountInWords(2_500_000_000.0, "ريال", "هللة")
        assertTrue(big.startsWith("فقط "))
        assertTrue(big.contains("2500000000.00"))
        assertThrows(IllegalArgumentException::class.java) {
            ArabicWords.amountInWords(-5.0, "ريال", "هللة")
        }
        // C1 على مستوى المحرك: القيد بخصم مدور يبقى متوازناً (نفس اشتقاق saveInvoice)
        val draft = AccountingEngine.saleInvoice(
            // [P33-P8] قروش: 100.0/10.0/13.5/103.5/0.0 ريال → 10_000/1_000/1_350/10_350/0
            subtotal = 10_000L, discount = 1_000L, taxAmount = 1_350L, total = 10_350L,
            cogs = 0L, partyId = 1L, date = 0L, invoiceId = 1L)
        assertTrue(draft.balanced)
    }

    // ═══ C4 تكملة: paidOutBetween يبقى شاملاً (توقيع مستقر) ═══

    @Test fun c4b_paidOutBetween_signatureStable() = runBlocking {
        val now = System.currentTimeMillis()
        db.payments().insert(Payment(amount = 7_500L, date = now, direction = 1, method = "CASH"))   // [P33-P8] 75.0 ريال → قروش
        assertEquals(7_500L, db.payments().paidOutBetween(now - 1000, now + 1000))   // [P33-P8] 75.0 ريال = 7_500 قروشاً — مساواة تامة بلا عتبة
        assertNull(db.payments().since(now + 2000).firstOrNull())
    }
}
