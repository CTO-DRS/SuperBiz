package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.LoyaltyEntryEntity
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.Visit
import com.superbiz.app.data.repo.LedgerRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار إصلاحات الموجة-1 من تقرير التدقيق الكامل (25 ملاحظة):
 *
 * H-1 دمج طرف مكرر كان يمحو نقاط ولائه (FK=CASCADE) — النقاط تُنقل الآن للطرف الباقي
 * H-2 دمج طرف صادر كشوفاً كان يرمي SQLiteConstraintException (FK=RESTRICT) — الكشوف والزيارات تُنقل الآن
 *
 * كل اختبار هنا كان يفشل قبل الإصلاح — والفشل قبل الإصلاح هو صحة الاختبار.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AuditWave1FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun party(name: String, type: Int = 0): Party =
        Party(name = name, type = type).let { it.copy(id = db.parties().upsert(it)) }

    // ═══ H-1: نقاط الولاء تنجو من الدمج ═══
    @Test
    fun mergeKeepsLoyaltyPointsOfMergedParty() = runBlocking {
        val keep = party("الطرف الباقي")
        val dup = party("الطرف المكرر بنقاط")

        // دفتر نقاط للمكرر: كسب 150 + كسب 60 − استبدال 40 = رصيد 170
        db.loyalty().insert(LoyaltyEntryEntity(partyId = dup.id, delta = 150L, reason = "EARN"))
        db.loyalty().insert(LoyaltyEntryEntity(partyId = dup.id, delta = 60L, reason = "EARN"))
        db.loyalty().insert(LoyaltyEntryEntity(partyId = dup.id, delta = -40L, reason = "REDEEM"))
        val dupBalanceBefore = db.loyalty().sumByParty(dup.id) ?: 0L
        assertEquals(170L, dupBalanceBefore)

        assertTrue("الدمج يجب أن ينجح", ledger.mergeParties(keep.id, dup.id))

        // [H-1] كان FK=CASCADE يمحو صفوف الدفتر مع الطرف المكرر — الرصيد يضيع بصمت
        assertEquals(
            "نقاط الطرف المدموج يجب أن تنجو في الطرف الباقي (تدقيق H-1)",
            170L, db.loyalty().sumByParty(keep.id) ?: 0L
        )
        // الدفتر انتقل كاملاً بصفوفه الثلاثة — لا صف تاريخي ضاع
        assertEquals(3, db.loyalty().forPartyOnce(keep.id).size)
        // والطرف المكرر بلا دفتر (حُذف مع طرفه)
        assertEquals(null, db.loyalty().sumByParty(dup.id))
    }

    // ═══ H-1 مكمّل: نقاط الطرفين معاً تتجمع بلا فقد ═══
    @Test
    fun mergeCombinesBothPartiesLoyaltyBalances() = runBlocking {
        val keep = party("الأساس بنقاط")
        val dup = party("المكرر بنقاط")
        db.loyalty().insert(LoyaltyEntryEntity(partyId = keep.id, delta = 100L, reason = "EARN"))
        db.loyalty().insert(LoyaltyEntryEntity(partyId = dup.id, delta = 70L, reason = "EARN"))

        assertTrue(ledger.mergeParties(keep.id, dup.id))
        assertEquals(
            "رصيد الطرف الباقي = مجموع دفتريه بعد الدمج (تدقيق H-1)",
            170L, db.loyalty().sumByParty(keep.id) ?: 0L
        )
    }

    // ═══ H-2: الدمج ينجح لطرف صادر كشوفاً (كان RESTRICT يرمي استثناء القيد) ═══
    @Test
    fun mergeSucceedsWhenDuplicatePartyHasStatements() = runBlocking {
        val keep = party("الطرف الباقي بكشوف")
        val dup = party("المكرر صاحب الكشف")

        fun stmt(n: String, pid: Long) = StatementEntity(
            statementNumber = n,
            verificationId = "SB-ST-TEST-$n", partyId = pid,
            fromTs = 1_700_407_200_000L, toTs = 1_702_348_799_000L,
            templateId = "7", currency = "SAR", contentHash = "ab12cd34ef56",
            filePath = "filesDir/pdfs/$n.pdf", note = null,
            lang = "BILINGUAL"
        )
        db.statements().insert(stmt("STATEMENT-2026-000001", dup.id))
        db.statements().insert(stmt("STATEMENT-2026-000002", dup.id))

        // [H-2] قبل الإصلاح: deleteRow(dupId) كان يرمي SQLiteConstraintException
        // (statements.partyId FK = RESTRICT) فيفشل الدمج كلياً لزبنائه الأغنى تاريخاً
        assertTrue(
            "دمج طرف له كشوف يجب أن ينجح (تدقيق H-2)",
            ledger.mergeParties(keep.id, dup.id)
        )
        // الكشوف انتقلت للطرف الباقي — سجلها المالي كامل بعد الدمج
        assertEquals(2, db.statements().historyForParty(keep.id).size)
        assertNotNull(db.statements().findByStatementNumber("STATEMENT-2026-000001"))
        assertEquals("الطرف المكرر حُذف فعلًا", null, db.parties().byId(dup.id))
    }

    // ═══ H-2 مكمّل: الزيارات تنتقل أيضاً فيبقى التاريخ في بطاقة الطرف الباقي ═══
    @Test
    fun mergeMovesVisitsToSurvivingParty() = runBlocking {
        val keep = party("الطرف الباقي بزيارات")
        val dup = party("المكرر له زيارات")
        db.visits().insert(Visit(partyId = dup.id, lat = 24.7, lng = 46.6, note = "زيارة معاينة"))
        db.visits().insert(Visit(partyId = dup.id, note = "زيارة ثانية بلا موقع"))

        assertTrue(ledger.mergeParties(keep.id, dup.id))
        assertEquals(
            "زيارات الطرف المدموج يجب أن تظهر في تاريخ الطرف الباقي (تدقيق H-2)",
            2, db.visits().forParty(keep.id).size
        )
    }

    // ═══ سلامة السلوك: دمج بلا أبناء يبقى ناجحاً كما كان ═══
    @Test
    fun mergePlainPartiesStillSucceeds() = runBlocking {
        val keep = party("عادي باقٍ")
        val dup = party("عادي مكرر")
        assertTrue(ledger.mergeParties(keep.id, dup.id))
        assertEquals(null, db.parties().byId(dup.id))
        assertNotNull(db.parties().byId(keep.id))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [تدقيق H-6] كلمة مرور SMTP — خزنة Keystore بدل النص العاري في statement_prefs.xml
    // مفتاح AES حقيقي محقون (نمط SecurityWaveTest) — Robolectric بلا AndroidKeyStore
    // ═══════════════════════════════════════════════════════════════════════

    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun useTestKey() { com.superbiz.app.security.PinVault.keyProvider = { testKey } }
    private fun restoreKey() {
        com.superbiz.app.security.PinVault.keyProvider =
            { com.superbiz.app.security.PinVault.masterKey() }
    }

    private fun rawPrefs() =
        ctx.getSharedPreferences("statement_prefs", Context.MODE_PRIVATE)

    @Test
    fun smtpSaveStoresOnlyVaultBlob_neverPlaintext() {
        useTestKey()
        try {
            val pass = "Smtp-Secret-9941"
            com.superbiz.app.domain.statement.StatementPrefs.save(
                ctx,
                com.superbiz.app.domain.statement.SmtpPrefsUi(
                    smtpEnabled = true, host = "smtp.example.com", user = "billing@example.com",
                    pass = pass, from = "billing@example.com"
                )
            )
            val p = rawPrefs()
            // [H-6] النص العاري لم يعد يُكتب إطلاقاً — المفتاح القديم غائب
            assertEquals(null, p.getString("smtpPass", null))
            // الصيغة المخزنة ks: (مشفرة Keystore) وليست النص
            val blob = p.getString("smtpPassVault", null)
            assertTrue("الصيغة ks:", blob != null && blob.startsWith("ks:"))
            assertTrue("النص العاري غير موجود داخل المخزن", !blob!!.contains(pass))
            // round-trip: الحمل يعيد كلمة المرور نفسها
            assertEquals(pass, com.superbiz.app.domain.statement.StatementPrefs.load(ctx).pass)
        } finally { restoreKey() }
    }

    @Test
    fun smtpLegacyPlaintext_migratesOnRead_andWipesCleartext() {
        useTestKey()
        try {
            // تثبيت قديم: نص عاري في المفتاح القديم كما كانت تكتبه الإصدارات السابقة
            rawPrefs().edit()
                .putString("smtpPass", "Legacy-Clear-7731")
                .putString("smtpUser", "old@example.com")
                .apply()

            val loaded = com.superbiz.app.domain.statement.StatementPrefs.load(ctx)
            // الترحيل الشفاف يعيد القيمة نفسها للمستخدم
            assertEquals("Legacy-Clear-7731", loaded.pass)
            // والقرص نظيف بعد القراءة الأولى: النص العاري حُذف والمخزن ks: كُتب
            val p = rawPrefs()
            assertEquals(null, p.getString("smtpPass", null))
            assertTrue(p.getString("smtpPassVault", null)!!.startsWith("ks:"))
            // القراءة الثانية تمر عبر المخزن — نفس القيمة
            assertEquals("Legacy-Clear-7731", com.superbiz.app.domain.statement.StatementPrefs.load(ctx).pass)
        } finally { restoreKey() }
    }

    @Test
    fun smtpVaultDecryptFailure_failsClosed_toEmptyPassword() {
        useTestKey()
        try {
            // مخزن صالح ثم تبديل المفتاح = فشل فك محتم (بيئة جديدة/مفتاح مفقود)
            com.superbiz.app.domain.statement.StatementPrefs.save(
                ctx,
                com.superbiz.app.domain.statement.SmtpPrefsUi(smtpEnabled = true, host = "h", pass = "x")
            )
            com.superbiz.app.security.PinVault.keyProvider = {
                javax.crypto.spec.SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
            }
            // فك فاشل = "" صادق (لا استثناء يهدم الإعدادات ولا كلمة مرور ملفقة)
            assertEquals("", com.superbiz.app.domain.statement.StatementPrefs.load(ctx).pass)
        } finally { restoreKey() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [تدقيق M-6] دين الطرف ثنائي الدور يُوجَّه بالاتجاه الصريح لا نوع الطرف وحده
    // Accounts.RECEIVABLE = "1100" / Accounts.PAYABLE = "2000"
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun accountRow(account: String) =
        db.journal().accountSums().firstOrNull { it.account == account }

    @Test
    fun dualRoleParty_explicitSupplierDirection_booksPayable() = runBlocking {
        val dual = party("عميل ومورد معاً", type = 2)
        ledger.addDebt(dual, 50_000L, System.currentTimeMillis(), "شراء آجل", direction = 1)

        // [M-6] قبل الإصلاح: كان يقع دائماً في فرع دين العميل (RECEIVABLE مدين)
        // الآن الاتجاه الصريح 1 يرحّل ذمة دائنة: مدين مخزون / دائن ذمم موردين
        val payable = accountRow("2000")
        assertTrue("الدين على ذمم الموردين لا العملاء (M-6)", payable != null && payable.c == 50_000L)
        assertEquals("لا ذمم عملاء إطلاقاً", null, accountRow("1100"))
        // سطر الدفعة يحمل اتجاه الدين (صادر) — كان 0 دائماً
        val pay = db.payments().forParty(dual.id).first { it.method == "DEBT" }
        assertEquals(1, pay.direction)
    }

    @Test
    fun dualRoleParty_defaultDirection_keepsCustomerSide() = runBlocking {
        val dual = party("ثنائي الدور بيع", type = 2)
        ledger.addDebt(dual, 20_000L, System.currentTimeMillis(), "بيع آجل")
        // الافتراضي يحفظ السلوك القائم: جانب العميل
        val receivable = accountRow("1100")
        assertTrue(receivable != null && receivable.d == 20_000L)
        assertEquals(0, db.payments().forParty(dual.id).first { it.method == "DEBT" }.direction)
    }

    @Test
    fun pureSupplier_stillBooksSupplierSide_withoutExplicitDirection() = runBlocking {
        val sup = party("مورد خالص", type = 1)
        ledger.addDebt(sup, 8_000L, System.currentTimeMillis(), "شراء آجل")
        val payable = accountRow("2000")
        assertTrue("المورد الخالص يبقى على جانبه كما كان (M-6)", payable != null && payable.c == 8_000L)
    }
}
