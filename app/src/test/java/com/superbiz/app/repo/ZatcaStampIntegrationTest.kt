package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.ZatcaStamper
import com.superbiz.app.domain.algo.ZatcaChain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [Z2-أ V 1.5.0] اختبار تكامل الختم E2E — السلسلة تعيش داخل معاملة الحفظ
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد الختم على قاعدة Room حقيقية (in-memory): تتابع ICV داخل المعاملات
 * المتزامنة منطقياً (نمط P6-M5)، وPIH يربط هاش الأرشيف الأول، والبيع حصراً،
 * والتعديل يحفظ الهوية ويرد الأرشيف الأول بـIGNORE، والإلغاء يخرج من القائمة
 * لا من السلسلة، ووباء بيانات البائع الناقص يبقي الفاتورة كما هي حرفياً.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ZatcaStampIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var invoices: InvoiceRepo
    private var seller: ZatcaStamper.Seller? = ZatcaStamper.Seller(
        name = "متجر الأمل", vatNumber = "310122393500003", crn = "1010",
        street = "شارع الملك فهد", city = "الرياض", country = "SA",
    )

    private val t0 = 1_791_462_645_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val stamper = ZatcaStamper(db, sellerProvider = { seller })
        invoices = InvoiceRepo(db, null, stamper)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun saleInvoice(pid: Long, number: String) = Invoice(
        number = number, partyId = pid, type = 0,
        date = t0, dueDate = t0,
        subtotal = 10_000L, discount = 0L,
        taxRate = 15.0, taxAmount = 1_500L, total = 11_500L,
    )

    private fun saleItems() = listOf(
        InvoiceItem(invoiceId = 0, productId = null, desc = "بند قياسي", qty = 2.0, unitPrice = 5_000L)
    )

    private fun purchaseInvoice(pid: Long, number: String) = Invoice(
        number = number, partyId = pid, type = 1,
        date = t0, dueDate = t0,
        subtotal = 10_000L, taxRate = 15.0, taxAmount = 1_500L, total = 11_500L,
    )

    // ───────── 1) أول فاتورة: ICV=1 وPIH الثابت الرسمي ─────────

    @Test
    fun firstSale_stampedWithFirstPih_andArchived() = runBlocking {
        val pid = db.parties().upsert(Party(name = "زبون"))
        val id = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val inv = db.invoices().byId(id)!!
        assertEquals(1L, inv.icv)
        assertEquals(ZatcaChain.firstPih(), inv.pih)
        assertEquals("0200000", inv.zatcaSubtype) // بلا buyerVat ⇒ مبسطة
        assertEquals(1, inv.zatcaStatus)
        assertTrue(inv.uuid.isNotBlank())
        // الأرشيف: الهاش المخزن = هاش XML المخزن، وقيمته تختلف عن بذرة أول فاتورة
        val doc = db.zatcaDocs().byInvoice(id)!!
        assertEquals(ZatcaChain.documentHash(doc.xml.toByteArray(Charsets.UTF_8)), doc.xmlHash)
        assertNotEquals(ZatcaChain.firstPih(), doc.xmlHash)
        assertTrue(doc.xml.contains("<cbc:UUID>${inv.uuid}</cbc:UUID>"))
        assertTrue(doc.xml.contains("INV-1"))
    }

    // ───────── 2) التتابع: الثانية تحمل PIH الأولى (سلسلة داخل معاملات الحفظ) ─────────

    @Test
    fun secondSale_chainsToFirstArchiveHash() = runBlocking {
        val pid = db.parties().upsert(Party(name = "زبون"))
        val id1 = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val doc1 = db.zatcaDocs().byInvoice(id1)!!
        val id2 = invoices.save(saleInvoice(pid, "INV-2"), saleItems())
        val inv2 = db.invoices().byId(id2)!!
        assertEquals(2L, inv2.icv)
        assertEquals(doc1.xmlHash, inv2.pih)
        // والسلسلة المخزنة كلها تتحقق محلياً: أولها الثابت الرسمي وثانيته هاش الأول
        val d1 = db.zatcaDocs().byInvoice(id1)!!
        val d2 = db.zatcaDocs().byInvoice(id2)!!
        val result = ZatcaChain.verifyChain(
            listOf(
                ZatcaChain.ChainEntry(d1.xml.toByteArray(), 1L, ZatcaChain.firstPih()),
                ZatcaChain.ChainEntry(d2.xml.toByteArray(), 2L, d1.xmlHash),
            )
        )
        assertTrue("السلسلة المخزنة يجب أن تتحقق: ${result.reason}", result.ok)
    }

    // ───────── 3) الشراء لا يُختم ولا يدخل العداد ─────────

    @Test
    fun purchaseInvoice_neverStamped() = runBlocking {
        val pid = db.parties().upsert(Party(name = "مورد"))
        val id1 = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val idP = invoices.save(purchaseInvoice(pid, "PUR-1"), saleItems())
        val pur = db.invoices().byId(idP)!!
        assertEquals("", pur.uuid)
        assertEquals(0L, pur.icv)
        assertEquals(0, pur.zatcaStatus)
        assertNull(db.zatcaDocs().byInvoice(idP))
        // والثالثة البيعية تأخذ ICV=2 (الشراء لم يحتسب)
        val id3 = invoices.save(saleInvoice(pid, "INV-3"), saleItems())
        assertEquals(2L, db.invoices().byId(id3)!!.icv)
    }

    // ───────── 4) بلا رقم ضريبي للبائع: لا ختم إطلاقاً ─────────

    @Test
    fun blankVat_invoiceLeftUntouched() = runBlocking {
        seller = null
        val pid = db.parties().upsert(Party(name = "زبون"))
        val id = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val inv = db.invoices().byId(id)!!
        assertEquals("", inv.uuid)
        assertEquals(0L, inv.icv)
        assertEquals(0, inv.zatcaStatus)
        assertEquals(0, db.zatcaDocs().count())
        // والسلسلة تبدأ من الصفر عند توفر الرقم لاحقاً
        seller = ZatcaStamper.Seller(name = "متجر", vatNumber = "300000000000003")
        val id2 = invoices.save(saleInvoice(pid, "INV-2"), saleItems())
        val inv2 = db.invoices().byId(id2)!!
        assertEquals(1L, inv2.icv)
        assertEquals(ZatcaChain.firstPih(), inv2.pih)
    }

    // ───────── 5) التعديل: الهوية تلصق والأرشيف الأول يُرد ─────────

    @Test
    fun editKeepsIdentity_archiveFirstWins() = runBlocking {
        val pid = db.parties().upsert(Party(name = "زبون"))
        val id = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val original = db.invoices().byId(id)!!
        val originalDoc = db.zatcaDocs().byInvoice(id)!!
        // تعديل المبلغ
        invoices.save(original.copy(subtotal = 20_000L, taxAmount = 3_000L, total = 23_000L), saleItems())
        val edited = db.invoices().byId(id)!!
        assertEquals(original.uuid, edited.uuid)      // الهوية الملصوقة
        assertEquals(original.icv, edited.icv)
        assertEquals(original.pih, edited.pih)
        assertEquals(original.zatcaSubtype, edited.zatcaSubtype)
        // الأرشيف الأول كما هو — بايتات الإصدار الأولى
        val afterDoc = db.zatcaDocs().byInvoice(id)!!
        assertEquals(originalDoc.xml, afterDoc.xml)
        assertEquals(originalDoc.xmlHash, afterDoc.xmlHash)
    }

    // ───────── 6) الإلغاء: يخرج من القائمة، السلسلة والهوية تبقيان ─────────

    @Test
    fun voidExitsQueue_chainKeepsStamp() = runBlocking {
        val pid = db.parties().upsert(Party(name = "زبون"))
        val id = invoices.save(saleInvoice(pid, "INV-1"), saleItems())
        val before = db.invoices().byId(id)!!
        invoices.voidInvoice(before)
        val voided = db.invoices().byId(id)!!
        assertEquals(3, voided.status)
        assertEquals(0, voided.zatcaStatus)       // خارج القائمة
        assertEquals(1L, voided.icv)               // السلسلة لم تُمس
        assertTrue(voided.uuid.isNotBlank())
        assertEquals(1, db.zatcaDocs().count())    // الأرشيف باقٍ (تدقيق)
        // التالية تحمل ICV=2 — الصادر يبقى صادراً في السلسلة
        val id2 = invoices.save(saleInvoice(pid, "INV-2"), saleItems())
        assertEquals(2L, db.invoices().byId(id2)!!.icv)
    }

    // ───────── 7) القياسية B2B: buyerVat ⇒ 0100000 ─────────

    @Test
    fun buyerVat_makesStandardSubtype() = runBlocking {
        val pid = db.parties().upsert(Party(name = "شركة"))
        val inv = saleInvoice(pid, "INV-1").copy(buyerVat = "300055556600003", buyerName = "شركة المؤسسة")
        val id = invoices.save(inv, saleItems())
        assertEquals("0100000", db.invoices().byId(id)!!.zatcaSubtype)
        assertTrue(db.zatcaDocs().byInvoice(id)!!.xml.contains("name=\"0100000\""))
    }
}
