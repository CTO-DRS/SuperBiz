package com.superbiz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.InstallmentEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * الموجة الرابعة P7 (الوكيل 7-d): سد فجوات roundtrip الموثقة في تقرير الفحص —
 * «فجوات roundtrip: الأقساط والأرشيف والإعدادات خارج اختبار النسخ/الاستعادة».
 *
 * نمط الاختبار منسوخ حرفياً من ChecksAndBackupTest: Room حقيقي في الذاكرة (لا mocks)،
 * exportJson → ملف مؤقت → importFrom(Uri.fromFile) → مطابقة حرفية قبل/بعد.
 *
 * عقود مثبَّتة هنا بالسلوك الفعلي لا بالادعاء
 * • الأقساط: الخطة وجدولها (المبالغ/التواريخ/حالة المسدد/اتجاه الدفعات) تعود حرفياً.
 * • الأرشيف: علم archived للمنتجات يبقى كما كان (مسار [P5-H10 إصلاح] الصريح).
 * • الإعدادات: مادة الرمز (pin*) وعلم biometric لا يتركان الجهاز أبداً —
 * لا في التصدير ([P5-H2 إصلاح] + /R12-C11) ولا في الاستيراد (المفاتيح
 * المحقونة في ملف عدواني تُتجاهل تماماً) — توحيداً مع عقد SettingsCodec الموثق.
 *
 * ملاحظات عقد رُصدت في هذا الملف ثم أُصلحت في التكامل (وسما [P7-X1]/[P7-X2 إصلاح])
 * • payments.planId (/M-4.9) كان غائباً عن حمولة النسخ — أُضيف للتصدير والاستيراد،
 * والنسخ القديمة بلا المفتاح تعود planId=null بأمان. لذا تُقارن الدفعات هنا بإسقاط
 * الحقول المنقولة حصراً (يعمل مع النسختين القديمة والجديدة).
 * • avatarPath و backupDirUri كانتا تُصدَّران وتُستوردان من مسار النسخ الكامل بعكس عقد
 * SettingsCodec («محليي الجهاز لا يُنقلان أبداً») — أُقصيا من التصدير وتُتجاهل في
 * الاستيراد؛ قيمة الجهاز المحلي تبقى كما هي.
 * [P33-P8] موجة القروش: كل مبالغ البذرة/الحمولة Long قروش (×100 من الريال — 1050 = 10.5 ريال)،
 * الظلال (PaymentShadow/ItemShadow) بقروش، ومقارنات المبالغ/الأرصدة مساواة صحيحة تامة بلا عتبة.
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRoundtripP7Test {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var ledger: LedgerRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var settings: SettingsRepo
    private lateinit var backup: BackupRepo

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        inventory = InventoryRepo(db, ledger)
        invoices = InvoiceRepo(db)
        installments = InstallmentRepo(db, ledger)
        settings = SettingsRepo(ctx)
        backup = BackupRepo(ctx, db, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun party(name: String, type: Int = 0): Party {
        val id = ledger.saveParty(Party(name = name, type = type))
        return ledger.party(id)!!
    }

    private fun tempFile(tag: String, json: String): File =
        File(ctx.cacheDir, "p7-$tag-${System.currentTimeMillis()}-${System.nanoTime()}.json")
            .apply { writeText(json) }

    /** إسقاط الدفعة إلى الحقول المنقولة فعلاً في حمولة النسخ (بلا id ولا planId — انظر ملاحظة العقد بالترويسة) */
    private data class PaymentShadow(val amount: Long, val date: Long, val direction: Int, val method: String, val note: String) // [P33-P8] المبلغ قروش Long

    /** إسقاط بند الفاتورة — معرّف البند يُولَّد من جديد عند الاستيراد (id ليس ضمن التصدير) */
    private data class ItemShadow(val invoiceId: Long, val productId: Long?, val desc: String, val qty: Double, val unitPrice: Long, val discount: Long) // [P33-P8] المبالغ قروش — qty كميّة تبقى Double

    private fun List<com.superbiz.app.data.db.Payment>.shadowPays() =
        map { PaymentShadow(it.amount, it.date, it.direction, it.method, it.note) }
            .sortedWith(compareBy({ it.method }, { it.date }, { it.amount }))

    private fun List<InvoiceItem>.shadowItems() =
        map { ItemShadow(it.invoiceId, it.productId, it.desc, it.qty, it.unitPrice, it.discount) }
            .sortedWith(compareBy({ it.invoiceId }, { it.desc }, { it.qty }))

    // ═══════════════════ L13: الأقساط ═══════════════════

    // ─── جولة أقساط كاملة: خطتا عميل ومورد (إحداهما مؤرشفة) بدفعات مسددة/جزئية تعود حرفياً ───
    @Test
    fun installmentPlanRoundtrip_restoresSchedulePaidStateAndPaymentDirections() = runBlocking {
        val cust = party("عميل الأقساط")
        val sup = party("مورد الأقساط", 1)

        // [P33-P8] خطة عميل: 1200 ريال (120000 قروش) بمقدمة 200 ريال (20000 قروش)
        // على 10 أشهر → 10 أقساط × 100 ريال (10000 قروش)
        val planId = installments.createPlan(
            title = "اتفاق بيع آجل", partyId = cust.id, direction = 0,
            total = 120_000L, downPayment = 20_000L, months = 10,
            startDate = t0, currency = "SAR", note = "خطة العميل", today = t0
        )
        val rows = installments.installmentsOf(planId)
        assertEquals(10, rows.size)
        installments.pay(rows[0], today = t0 + 1)          // سداد كامل للقسط الأول
        installments.pay(rows[1], 4_000L, today = t0 + 2)  // [P33-P8] سداد جزئي 40 ريال = 4000 قروش

        // [P33-P8] خطة مورد (اتجاه معاكس): 600 ريال (60000 قروش) بلا مقدمة على 6 أشهر
        val supPlanId = installments.createPlan(
            title = "اتفاق شراء آجل", partyId = sup.id, direction = 1,
            total = 60_000L, downPayment = 0L, months = 6,
            startDate = t0, currency = "SAR", note = "خطة المورد", today = t0
        )
        installments.pay(installments.installmentsOf(supPlanId)[0], today = t0 + 3)
        db.installments().archivePlan(supPlanId)   // العلم يجب أن يُصدَّر ويُستعاد

        // لقطات قبل النسخ
        val plansBefore = db.installments().plansExport()
        assertEquals(2, plansBefore.size)
        val instsBefore = plansBefore.associate { it.id to db.installments().installmentsOf(it.id) }
        val paysBefore = db.payments().since(0).shadowPays()
        val balancesBefore = ledger.balances()

        // export → ملف → إفساد قاعدة البيانات بسطر دخيل → import
        val exported = backup.exportJson()
        assertEquals(BackupRepo.FORMAT_VERSION, exported.getInt("format"))  // [P33-P8] الصيغة 3 = قروش
        val f = tempFile("installments", exported.toString(2))
        ledger.saveParty(Party(name = "Junk_${System.nanoTime()}"))
        assertTrue("الاستعادة فشلت", backup.importFrom(Uri.fromFile(f)))

        // الخطة مطابقة حرفياً بكل حقولها (شاملة archived للمؤرشفة)
        assertEquals(plansBefore, db.installments().plansExport())
        assertTrue(db.installments().plansExport().first { it.id == supPlanId }.archived)
        assertFalse(db.installments().plansExport().first { it.id == planId }.archived)

        // جدول الأقساط مطابق حرفياً: المبالغ والتواريخ والمسدد وتواريخ السداد والحالة
        for ((pid, before) in instsBefore) {
            assertEquals("أقساط الخطة $pid اختلفة", before, db.installments().installmentsOf(pid))
        }
        val afterRows = db.installments().installmentsOf(planId)
        assertEquals(InstallmentEngine.St.PAID, afterRows[0].status)
        assertEquals(10_000L, afterRows[0].paidAmount) // [P33-P8] قروش — مساواة تامة بلا عتبة عائمة
        assertEquals(t0 + 1, afterRows[0].paidDate!!)
        assertEquals(InstallmentEngine.St.PARTIAL, afterRows[1].status)
        assertEquals(4_000L, afterRows[1].paidAmount)
        assertEquals(0L, afterRows[2].paidAmount)
        assertEquals(InstallmentEngine.St.DUE, afterRows[2].status)

        // اتجاه الدفعات: مقدمة وقبض عميل (وارد)، وسداد مورد (صادر) — بالإسقاط الموحد
        assertEquals(paysBefore, db.payments().since(0).shadowPays())
        val pays = db.payments().since(0).shadowPays()
        assertTrue(pays.any { it.method == "INSTALLMENT_DOWN" && it.direction == 0 && it.amount == 20_000L }) // [P33-P8] 200 ريال = 20000 قروش
        assertTrue(pays.any { it.direction == 1 && it.amount == 10_000L })   // [P33-P8] دفعة المورد الصادرة 100 ريال = 10000 قروش

        // أرصدة الأطراف عادت كما كانت (قيود فتح الخطة والسداد كلها في الملف)
        val balancesAfter = ledger.balances()
        assertEquals(balancesBefore.size, balancesAfter.size)
        for ((pid, bal) in balancesBefore) {
            // [P33-P8] الأرصدة قروش Long — مساواة صحيحة تامة بلا عتبة عائمة
            assertEquals("رصيد الطرف $pid اختلف", bal, balancesAfter[pid]!!)
        }
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ═══════════════════ L14: أرشيف المنتجات ═══════════════════

    // ─── علم الأرشفة للمنتجين (الحي والمؤرشف) يبقى كما كان بعد جولة النسخ/الاستعادة ───
    @Test
    fun productArchivedFlag_survivesBackupRoundtrip() = runBlocking {
        val liveId = inventory.saveProduct(
            Product(name = "منتج حي", costPrice = 1000L, salePrice = 1500L, stockQty = 5.0, barcode = "P7-LIVE-1")) // [P33-P8] 10/15 ريال → قروش
        val deadId = inventory.saveProduct(
            Product(name = "منتج مؤرشف", costPrice = 600L, salePrice = 900L, stockQty = 2.0, barcode = "P7-DEAD-1")) // [P33-P8] 6/9 ريال → قروش
        // [P5-H10 إصلاح]: المسار الصريح للأرشفة — الحقل مُدار منذ الإصلاح ويجب أن يُدار في النسخ أيضاً
        inventory.setArchived(deadId, true)

        val before = listOf(inventory.product(liveId)!!, inventory.product(deadId)!!)

        val exported = backup.exportJson()
        // العلم داخل الملف نفسه (لا مجرد سلوك القاعدة)
        val flags = mutableMapOf<String, Boolean>()
        for (i in 0 until exported.getJSONArray("products").length()) {
            val j = exported.getJSONArray("products").getJSONObject(i)
            flags[j.getString("name")] = j.getBoolean("archived")
        }
        assertEquals(false, flags["منتج حي"])
        assertEquals(true, flags["منتج مؤرشف"])

        val f = tempFile("archive", exported.toString(2))
        assertTrue("الاستعادة فشلت", backup.importFrom(Uri.fromFile(f)))

        val after = listOf(inventory.product(liveId)!!, inventory.product(deadId)!!)
        assertEquals(before, after)   // مطابقة كاملة للكيانين شاملة archived وcreatedAt
        assertFalse(inventory.product(liveId)!!.archived)
        assertTrue(inventory.product(deadId)!!.archived)

        // ملاحظة تعاقدية: علم biometric لإعدادات القفل مستثنى عمداً من حمولة النسخ
        // منذ [P5-H2 إصلاح] (بوابة الأمان لا تترك الجهاز أبداً) — لا يُختبر هنا؛
        // إثباته على مستوى الملف والاستيراد في اختبارات الإعدادات أدناه.
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ═══════════════════ L15: الإعدادات ═══════════════════

    // ─── ملف النسخ لا يحمل مادة الرمز ولا biometric إطلاقاً، والهوية تعود حرفياً ───
    @Test
    fun settingsRoundtrip_restoresProfileAndLeavesSecurityMaterialBehind() = runBlocking {
        // جهاز A: هوية كاملة + مادة رمز محلية + بصمة
        settings.setBusinessName("متجر الأمانة")
        settings.setOwnerName("سالم العلي")
        settings.setLanguage("en")
        settings.setTheme("light")
        settings.setBaseCurrency("EGP")
        settings.setTaxRate(14.5)
        settings.setPinSecured("salt-device-A", "ks:blob-device-A", 600_000, 6)
        settings.setBiometric(true)

        // [P9-T2 إصلاح]: حارس «رفض الملفات الفارغة المحتوى» () يرفض نسخة بلا أطراف/منتجات/فواتير
        // عمداً كي لا تمسح قاعدة حية — اختبار الإعدادات الوحيد كان لا يزرع شيئاً فتُرفض نسخته دائماً
        party("عميل الجرد الأدنى")

        val exported = backup.exportJson()
        val st = exported.getJSONObject("settings")

        // القيم القابلة للنقل وصلت الملف كما هي
        assertEquals("متجر الأمانة", st.getString("businessName"))
        assertEquals("سالم العلي", st.getString("ownerName"))
        assertEquals("en", st.getString("language"))
        assertEquals("light", st.getString("theme"))
        assertEquals("EGP", st.getString("baseCurrency"))
        assertEquals(14.5, st.getDouble("taxRate"), 0.0)

        // القاعدة الأمنية (عقد SettingsCodec + [P5-H2 إصلاح] + /R12-C11)
        // مادة الرمز بواجهاتها الخمس والبوابة الحيوية غائبة عن الملف إطلاقاً
        for (key in listOf("pinHash", "pinSalt", "pinBlob", "pinIters", "pinLength", "biometric")) {
            assertFalse("المفتاح الأمني $key تسرب إلى ملف النسخ", st.has(key))
        }

        // جهاز B: هوية مختلفة ورمز مختلف وبلا بصمة (سيناريو H-2: جهاز بلا بصمة مسجلة)
        settings.setBusinessName("متجر آخر")
        settings.setOwnerName("مالك آخر")
        settings.setLanguage("ar")
        settings.setTheme("dark")
        settings.setBaseCurrency("SAR")
        settings.setTaxRate(15.0)
        settings.setPinSecured("salt-device-B", "ks:blob-device-B", 210_000, 8)
        settings.setBiometric(false)

        val f = tempFile("settings", exported.toString(2))
        assertTrue("الاستعادة فشلت", backup.importFrom(Uri.fromFile(f)))

        // المطابقة بعد الاستعادة: هوية A عادت كاملة
        val after = settings.snapshot()
        assertEquals("متجر الأمانة", after.businessName)
        assertEquals("سالم العلي", after.ownerName)
        assertEquals("en", after.language)
        assertEquals("light", after.theme)
        assertEquals("EGP", after.baseCurrency)
        assertEquals(14.5, after.taxRate, 0.0)

        // مادة الرمز والبوابة تبقى مادة جهاز B حرفياً — استيراد النسخة لا يمس الأمان المحلي
        assertEquals("salt-device-B", after.pinSalt)
        assertEquals("ks:blob-device-B", after.pinBlob)
        assertEquals(210_000, after.pinIters)
        assertEquals(8, after.pinLength)
        assertNull(after.pinHash)
        assertFalse(after.biometric)
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ─── ملف عدواني (أو نسخة قديمة) بمفاتيح أمنية محقونة: يُقبل الملف وتُتجاهل المفاتيح كلياً ───
    @Test
    fun import_ignoresInjectedPinAndBiometricKeysInSettingsFile() = runBlocking {
        // جهاز الهدف له مادة رمز خاصة به وبلا بصمة
        settings.setPinSecured("salt-target", "ks:blob-target", 600_000, 6)
        settings.setBiometric(false)

        // ملف بنيوياً صالح يحمل مفاتيح الأمان كما كانت النسخ قبل R12-C11/P5-H2 (أو حقن خبيث)
        val hostile = JSONObject()
            .put("app", "SuperBiz")
            .put("format", 2)
            .put("parties", JSONArray().put(JSONObject().put("id", 1).put("name", "طرف وحيد")))
            .put("settings", JSONObject()
                .put("pinHash", "injected-hash")
                .put("pinSalt", "injected-salt")
                .put("pinBlob", "v1:injected-blob")
                .put("pinIters", 1)
                .put("pinLength", 4)
                .put("biometric", true))

        val f = tempFile("hostile", hostile.toString())
        assertTrue("الملف الصالح بنيوياً يجب أن يُستورد", backup.importFrom(Uri.fromFile(f)))

        // السلوك الفعلي: مفاتيح الأمان لا تُقرأ من الملف إطلاقاً — مادة الهدف كما هي
        val s = settings.snapshot()
        assertEquals("salt-target", s.pinSalt)
        assertEquals("ks:blob-target", s.pinBlob)
        assertEquals(600_000, s.pinIters)
        assertEquals(6, s.pinLength)
        assertNull(s.pinHash)
        assertFalse(s.biometric)
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ═══════════════════ جولة شاملة على قاعدة فارغة (محاكاة جهاز جديد) ═══════════════════

    // ─── استعادة نسخة كاملة على قاعدة مُمسحة: طرف + فاتورة بيع + قيد + منتج يعودون جميعاً ───
    @Test
    fun fullRoundtripOntoEmptyDatabase_restoresPartyInvoiceJournalProduct() = runBlocking {
        val p = party("طرف الجولة")
        val pid = inventory.saveProduct(
            Product(name = "صنف الجولة", costPrice = 2_000L, salePrice = 3_000L, stockQty = 8.0, barcode = "P7-FULL-1")) // [P33-P8] 20/30 ريال → قروش
        val invId = invoices.save(
            Invoice(number = "INV-P7-1", partyId = p.id, type = 0, date = t0, dueDate = t0,
                subtotal = 10_000L, taxAmount = 0L, total = 10_000L, costTotal = 2_000L, currency = "SAR"), // [P33-P8] 100/20 ريال → قروش
            listOf(InvoiceItem(invoiceId = 0, productId = null, desc = "بند الجولة", qty = 1.0, unitPrice = 10_000L))) // [P33-P8] 100 ريال/وحدة → 10000 قروش — qty كميّة تبقى Double
        ledger.addDebt(p, 6_000L, t0, "دين الجولة") // [P33-P8] 60 ريال → 6000 قروش

        val invBefore = invoices.invoice(invId)!!
        val itemsBefore = db.invoiceItems().forInvoice(invId).shadowItems()
        val productBefore = inventory.product(pid)!!
        val balanceBefore = ledger.partyBalance(p.id)
        val linesBefore = db.journal().allLines()

        val f = tempFile("full", backup.exportJson().toString(2))

        // محاكاة جهاز جديد: القاعدة فارغة تماماً قبل الاستعادة
        db.maintenance().wipeAll()
        assertEquals(0, db.parties().count())
        assertEquals(0, db.invoices().count())

        assertTrue("الاستعادة على قاعدة فارغة فشلت", backup.importFrom(Uri.fromFile(f)))

        assertEquals(invBefore, invoices.invoice(invId))                 // الفاتورة حرفياً
        assertEquals(itemsBefore, db.invoiceItems().forInvoice(invId).shadowItems())  // بنودها
        assertEquals(productBefore, inventory.product(pid))              // المنتج حرفياً
        assertEquals(balanceBefore, ledger.partyBalance(p.id))           // [P33-P8] دين الطرف — مساواة صحيحة تامة بلا عتبة
        assertEquals(linesBefore.size, db.journal().allLines().size)     // القيود كلها
        val d = db.journal().allLines().sumOf { it.debit }
        val c = db.journal().allLines().sumOf { it.credit }
        assertEquals(d, c)                                               // [P33-P8] الدفتر متوازن — مساواة صحيحة تامة
        f.delete()
        Unit // JUnit4 يشترط void
    }
}
