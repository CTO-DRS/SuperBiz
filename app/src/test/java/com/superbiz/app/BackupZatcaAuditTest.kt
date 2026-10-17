package com.superbiz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.ZatcaDocEntity
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.backup.BackupTables
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * [AUD-1/AUD-2] اختبارات انحدار التدقيق — اكتمال النسخ الاحتياطي وسلامة القيد المزدوج.
 *
 * نمط الاختبار من BackupRoundtripP7Test حرفياً: Room حقيقي في الذاكرة (لا mocks)،
 * exportJson → ملف مؤقت → importFrom(Uri.fromFile) → مطابقة حرفية قبل/بعد.
 *
 * العقود المثبتة هنا بالسلوك الفعلي:
 * ① هوية ZATCA (v13: uuid/icv/pih/subtype/buyer-fields/status) وختم الفئة الأصلية
 *    (v15: orig-fields) وأرشيف zatca_docs (v14) تُصدَّر وتُستعاد حرفياً — كانت تُمسح
 *    صمتاً في كل استعادة فتفقد سلسلة ICV/PIH واستمرار الربط.
 * ② الاستعادة ترفض ملفاً بقيود غير متوازنة (Σمدين ≠ Σدائن) قبل أي مساس بالقاعدة —
 *    كانت المسار الوحيد الذي يتجاوز عقد totalDebit == totalCredit المساواة التامة.
 * ③ الملفات القديمة بلا المفاتيح الجديدة (بذور محايدة) تُستعاد كما كانت — التوافق
 *    التراكمي (نمط P36-BK) محفوظ: صيغة 3 تبقى 3.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupZatcaAuditTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var settings: SettingsRepo
    private lateinit var backup: BackupRepo

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepo(ctx)
        backup = BackupRepo(ctx, db, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tempFile(tag: String, json: String): File =
        File(ctx.cacheDir, "audit-$tag-${System.currentTimeMillis()}-${System.nanoTime()}.json")
            .apply { writeText(json) }

    /** فاتورة بيع مختومة ZATCA كاملة الهوية (v13) مختومة بالفئة الأصلية (v15) */
    private fun stampedInvoice(id: Long, partyId: Long, icv: Long, pih: String) = Invoice(
        id = id, number = "INV-2026-000$id", partyId = partyId,
        type = 0, date = t0, dueDate = t0,
        subtotal = 10_000L, taxRate = 0.15, taxAmount = 1_500L, total = 11_500L,
        paid = 0L, status = 0,
        uuid = "8f2e2a55-9d61-4e3f-a1c7-00000000000$id",
        icv = icv, pih = pih,
        zatcaSubtype = "0100000", deliveryDate = t0,
        buyerName = "شركة الاختبار", buyerVat = "310122393500003",
        buyerAddress = "الرياض، حي العليا",
        zatcaStatus = 2,
        origCurrency = "USD", origTotal = 3_067, origFxMicros = 3_750_000L
    )

    // ═══════════ ① دورة ZATCA الكاملة: هوية + أرشيف يبقيان بعد الاستعادة ═══════════
    @Test
    fun zatcaIdentityAndDocsSurviveBackupRestoreRoundtrip() = runBlocking {
        val pid = db.parties().upsert(Party(name = "عميل ZATCA"))
        // فاتورتان بتسلسل ICV 1,2 — الثانية تحمل PIH الأولى (سلسلة السلامة الحرفية)
        db.invoices().upsert(stampedInvoice(1L, pid, icv = 1L, pih = ""))
        db.invoices().upsert(stampedInvoice(2L, pid, icv = 2L, pih = "ABCD1234-hash-of-first"))
        val doc = ZatcaDocEntity(
            invoiceId = 2L,
            xml = "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">اختبار</Invoice>",
            xmlHash = "kP/1cWk1hashSecond==", subtype = "0100000",
            issuedAt = t0 + 5, reportedAt = t0 + 6, attemptCount = 0, clearedXml = "<cleared/>"
        )
        db.zatcaDocs().archiveFirst(doc)
        assertEquals(1, db.zatcaDocs().count())

        val exported = backup.exportJson()
        assertEquals("الصيغة تبقى 3 — الإضافة تراكمية", BackupRepo.FORMAT_VERSION, exported.getInt("format"))
        assertTrue("فاتورة بلا uuid = علة AUD-1", exported.getJSONArray("invoices").getJSONObject(1).getString("uuid").isNotEmpty())
        assertEquals(1, exported.getJSONArray("zatca_docs").length())

        // إفساد القاعدة بصف دخيل ثم استعادة
        val f = tempFile("zatca", exported.toString(2))
        db.parties().upsert(Party(name = "Junk_${System.nanoTime()}"))
        assertTrue("الاستعادة فشلت", backup.importFrom(Uri.fromFile(f)))

        // 1) هوية الفاتورة حرفياً
        val inv2 = db.invoices().byId(2L)
        assertNotNull(inv2)
        assertEquals("8f2e2a55-9d61-4e3f-a1c7-000000000002", inv2!!.uuid)
        assertEquals(2L, inv2.icv)
        assertEquals("ABCD1234-hash-of-first", inv2.pih)
        assertEquals("0100000", inv2.zatcaSubtype)
        assertEquals("310122393500003", inv2.buyerVat)
        assertEquals(2, inv2.zatcaStatus)
        // 2) ختم الفئة الأصلية حرفياً
        assertEquals("USD", inv2.origCurrency)
        assertEquals(3_067L, inv2.origTotal)
        assertEquals(3_750_000L, inv2.origFxMicros)
        // 3) الأرشيف كامل الجدول حرفياً — و链条 PIH متواصل: latestHash يعيد هاش الأرشيف
        assertEquals(1, db.zatcaDocs().count())
        val restored = db.zatcaDocs().byInvoice(2L)!!
        assertEquals(doc.xml, restored.xml)
        assertEquals(doc.xmlHash, restored.xmlHash)
        assertEquals(doc.clearedXml, restored.clearedXml)
        assertEquals(doc.reportedAt, restored.reportedAt)
        assertEquals(doc.xmlHash, db.zatcaDocs().latestHash())
        assertEquals(2L, db.invoices().maxIcv())
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ═══════════ ② رفض القيود غير المتوازنة قبل أي مساس بالقاعدة ═══════════
    @Test
    fun restoreRejectsUnbalancedJournalAndLeavesDatabaseUntouched() = runBlocking {
        // بذرة حية يجب أن تبقى كما هي بعد الرفض
        val pid = db.parties().upsert(Party(name = "مالك البيانات الحية"))
        assertEquals(1, db.parties().allOnce().size)

        val parties = JSONArray().put(JSONObject()
            .put("id", 1L).put("name", "طرف الملف").put("phone", "").put("type", 0))
        val invoices = JSONArray().put(JSONObject()
            .put("id", 1L).put("number", "INV-X").put("partyId", 1L).put("type", 0)
            .put("date", t0).put("dueDate", t0).put("subtotal", 10_000L)
            .put("taxRate", 0.15).put("total", 10_000L).put("status", 0))
        // قيد غير متوازن: مدين 10,000 ودائن 9,999 — علة AUD-2 كانت تُدخله صمتاً
        val journal = JSONArray().put(JSONObject()
            .put("id", 1L).put("date", t0).put("memo", "قيد فاسد"))
        val lines = JSONArray()
            .put(JSONObject().put("entryId", 1L).put("account", "CASH")
                .put("debit", 10_000L).put("credit", 0L))
            .put(JSONObject().put("entryId", 1L).put("account", "REVENUE")
                .put("debit", 0L).put("credit", 9_999L))
        val bad = JSONObject()
            .put("app", "SuperBiz").put("format", BackupRepo.FORMAT_VERSION)
            .put("exportedAt", t0)
            .put("parties", parties).put("invoices", invoices)
            .put(BackupTables.JOURNAL, journal).put(BackupTables.JOURNAL_LINES, lines)

        val f = tempFile("unbalanced", bad.toString(2))
        assertFalse("الملف غير المتوازن يجب أن يُرفض", backup.importFrom(Uri.fromFile(f)))

        // القاعدة لم تُمسّ — البيانات الحية باقية (الرفض قبل wipeAll)
        assertEquals("الرفض لم يمس القاعدة", 1, db.parties().exportOnce().size)
        assertEquals("لا فواتير دخيلة", 0, db.invoices().exportOnce().size)
        assertEquals("لا قيود دخيلة", 0, db.journal().allEntries().size)
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ═══════════ ②ب رفض قيد بلا سطور ورفض سطر بلا قيد ═══════════
    @Test
    fun restoreRejectsLinelessEntryAndOrphanLine() = runBlocking {
        val parties = JSONArray().put(JSONObject()
            .put("id", 1L).put("name", "طرف").put("type", 0))
        val invoices = JSONArray().put(JSONObject()
            .put("id", 1L).put("number", "INV-Y").put("partyId", 1L).put("type", 0)
            .put("date", t0).put("dueDate", t0).put("subtotal", 5_000L)
            .put("taxRate", 0.15).put("total", 5_000L).put("status", 0))

        // قيد بلا سطور (سياسة النواة الصارمة ①: «قيد بلا سطر مكتمل = ملف غير قابل للاستعادة»)
        val journalNoLines = JSONArray().put(JSONObject()
            .put("id", 9L).put("date", t0).put("memo", "قيد يتيم"))
        val noLines = JSONObject()
            .put("app", "SuperBiz").put("format", BackupRepo.FORMAT_VERSION)
            .put("parties", parties).put("invoices", invoices)
            .put(BackupTables.JOURNAL, journalNoLines)
            .put(BackupTables.JOURNAL_LINES, JSONArray())
        val f1 = tempFile("lineless", noLines.toString(2))
        assertFalse("قيد بلا سطور يجب أن يُرفض", backup.importFrom(Uri.fromFile(f1)))
        f1.delete()

        // سطر يشير إلى قيد غائب عن الملف
        val orphanLine = JSONObject()
            .put("app", "SuperBiz").put("format", BackupRepo.FORMAT_VERSION)
            .put("parties", parties).put("invoices", invoices)
            .put(BackupTables.JOURNAL_LINES, JSONArray().put(JSONObject()
                .put("entryId", 99L).put("account", "CASH")
                .put("debit", 100L).put("credit", 100L)))
        val f2 = tempFile("orphan", orphanLine.toString(2))
        assertFalse("سطر بلا قيد يجب أن يُرفض", backup.importFrom(Uri.fromFile(f2)))
        f2.delete()

        // القاعدة ما زالت سليمة
        assertEquals(0, db.invoices().exportOnce().size)
    }

    // ═══════════ ③ التوافق التراكمي: ملف ما قبل الإصلاح بلا المفاتيح ═══════════
    @Test
    fun legacyFileWithoutNewKeysRestoresWithNeutralSeeds() = runBlocking {
        // ملف بصيغة 3 مولّد قبل الإصلاح — بلا uuid/icv/pih/orig*/zatca_docs
        val legacy = JSONObject()
            .put("app", "SuperBiz").put("format", 3).put("exportedAt", t0)
            .put("parties", JSONArray().put(JSONObject()
                .put("id", 1L).put("name", "طرف قديم").put("type", 0)))
            .put("invoices", JSONArray().put(JSONObject()
                .put("id", 1L).put("number", "INV-OLD").put("partyId", 1L).put("type", 0)
                .put("date", t0).put("dueDate", t0).put("subtotal", 7_000L)
                .put("taxRate", 0.15).put("total", 7_000L).put("status", 0)))
        val f = tempFile("legacy", legacy.toString(2))
        assertTrue("الملف القديم يجب أن يُقبل", backup.importFrom(Uri.fromFile(f)))

        val inv = db.invoices().byId(1L)!!
        assertEquals("بذور محايدة للهوية", "", inv.uuid)
        assertEquals(0L, inv.icv)
        assertEquals("", inv.pih)
        assertEquals(0, inv.zatcaStatus)
        assertEquals("بذور محايدة للفئة الأصلية", "", inv.origCurrency)
        assertEquals(0L, inv.origTotal)
        f.delete()
        Unit // JUnit4 يشترط void
    }
}
