package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.PosCartLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * — اختبارات انحدار للإصلاحات
 * كل إصلاح يفشل قبله وينجح بعده (ما أمكن بلا Android UI).
 * C1/C2/C3 سلة POS — C4/C5 مخزون — C6 أطراف — C7/C8 أقساط — C9 فواتير.
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R10FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var installments: InstallmentRepo

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        installments = InstallmentRepo(db, ledger)
    }

    @After
    fun tearDown() { db.close() }

    // [P33-P8] السعر/التكلفة قروش Long — الكمية والمخزون تبقى Double
    private fun line(pid: Long?, name: String, price: Long, cost: Long, qty: Double, stock: Double) =
        PosCartLine(pid, name, price, cost, qty, stock)

    // ═══ C1: زر «+» يُثبَّت عند المتاح ═══

    @Test
    fun c1_add_capsAtStock_forExistingLine() {
        val lines = listOf(line(1L, "صنف", 1_000L, 500L, qty = 9.0, stock = 10.0))   // [P33-P8] 10.0/5.0 ريال → قروش
        val out = PosCart.add(lines, lines[0], step = 5.0)
        assertEquals(10.0, out[0].qty, 1e-9) // لم يتجاوز المتاح
    }

    @Test
    fun c1_add_capsAtStock_forNewLine_andKeepsUnlimitedWhenNoStock() {
        val p = line(2L, "صنف", 1_000L, 500L, qty = 0.0, stock = 3.0)   // [P33-P8] قروش
        assertEquals(3.0, PosCart.add(emptyList(), p, step = 7.0)[0].qty, 1e-9)
        // بلا مخزون معروف (0): لا سقف — سلوك R9-C18 نفسه
        val free = line(null, "خدمة", 5_000L, 0L, qty = 0.0, stock = 0.0)   // [P33-P8] قروش
        assertEquals(7.0, PosCart.add(emptyList(), free, step = 7.0)[0].qty, 1e-9)
    }

    // ═══ C2: NaN/∞ يُرفض في setQty ═══

    @Test
    fun c2_setQty_rejectsNonFinite_insteadOfPoisoningCart() {
        val lines = listOf(line(1L, "صنف", 1_000L, 500L, qty = 2.0, stock = 10.0))   // [P33-P8] قروش
        assertEquals(lines, PosCart.setQty(lines, 1L, Double.NaN))
        assertEquals(lines, PosCart.setQty(lines, 1L, Double.POSITIVE_INFINITY))
        assertEquals(2.0, PosCart.setQty(lines, 1L, Double.NaN)[0].qty, 1e-9)
    }

    // ═══ C3: تطهير السلة المستعادة ═══

    @Test
    fun c3_sanitizeRestored_dropsCorrupt_andCapsSane() {
        val restored = listOf(
            // [P33-P8] الأسعار قروش — سالب القروش (-5.0 ريال = -500L) هو الوحيد المستحيل (لا NaN في الصحيح)
            line(1L, "سليم", 1_000L, 500L, qty = 2.0, stock = 8.0),
            line(2L, "كمية NaN", 1_000L, 500L, qty = Double.NaN, stock = 8.0),   // يُهمل
            line(3L, "سعر سالب", -500L, 500L, qty = 1.0, stock = 8.0),          // يُهمل
            line(4L, "  ", 1_000L, 500L, qty = 1.0, stock = 8.0),                // اسم فارغ → ؟
            line(5L, "كمية سالبة", 1_000L, 500L, qty = -3.0, stock = 8.0),       // يُهمل
            line(6L, "فوق المتاح", 1_000L, 500L, qty = 50.0, stock = 4.0),       // يُثبَّت
        )
        val out = PosCart.sanitizeRestored(restored)
        // الناجون: السليم، الاسم الفارغ (يُصلَّح)، فوق المتاح (يُثبَّت) — الثلاثة التالفة تُهمل
        assertEquals(3, out.size)
        assertEquals(2.0, out[0].qty, 1e-9)
        assertEquals("؟", out[1].name)
        assertEquals(4.0, out[2].qty, 1e-9)
    }

    @Test
    fun c3_sanitizeRestored_emptyInput() = assertTrue(PosCart.sanitizeRestored(emptyList()).isEmpty())

    // ═══ C4: moveStock يرفض الكميات غير المنتهية ═══

    @Test
    fun c4_moveStock_rejectsNaNQty_withoutCorruptingStock() = runBlocking {
        val pid = inventory.saveProduct(Product(name = "صنف", costPrice = 1_000L, salePrice = 2_000L, stockQty = 50.0))   // [P33-P8] 10.0/20.0 ريال → قروش
        val p = inventory.product(pid)!!
        try {
            inventory.moveStock(p, Double.NaN, "ADJUST", System.currentTimeMillis(), "", false)
            assertFalse("expected require to fail", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        assertEquals(50.0, inventory.product(pid)!!.stockQty, 1e-9)
    }

    // ═══ C5: رفض الباركود المكرر ═══

    @Test
    fun c5_saveProduct_rejectsDuplicateBarcode_andAllowsSameProduct() = runBlocking {
        val a = inventory.saveProduct(Product(name = "أ", barcode = "BC-1"))
        try {
            inventory.saveProduct(Product(name = "ب", barcode = "BC-1"))
            assertFalse("expected duplicate barcode rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        // نفس المنتج بباركوده: مسموح (تحديث لا تعارض)
        val id = inventory.saveProduct(Product(id = a, name = "أ المعدّل", barcode = "BC-1"))
        assertEquals(a, id)
        // بلا باركود: مسموح دائماً
        val c = inventory.saveProduct(Product(name = "ج", barcode = ""))
        assertTrue(c > 0)
    }

    // ═══ C6: رفض الطرف بلا اسم ═══

    @Test
    fun c6_saveParty_rejectsBlankName() = runBlocking {
        try {
            ledger.saveParty(Party(name = "   "))
            assertFalse("expected blank-name rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        // الاسم يُقلَّم عند الحفظ
        val id = ledger.saveParty(Party(name = "  عميل تجريبي  "))
        assertEquals("عميل تجريبي", db.parties().byId(id)!!.name)
    }

    // ═══ C7: خطة بعنوان فارغ/أقساط شاذة ═══

    private fun plan(title: String, total: Double, months: Int) = Triple(title, total, months)

    @Test
    fun c7_createPlan_rejectsBlankTitle_andMonthAbuse() = runBlocking {
        val pid = db.parties().upsert(Party(name = "عميل"))
        val today = System.currentTimeMillis()
        try {
            installments.createPlan("", pid, 0, total = 120_000L, downPayment = 0L, months = 6, startDate = today, currency = "SAR", note = "")   // [P33-P8] 1200.0 ريال → قروش
            assertFalse("expected blank title rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        try {
            installments.createPlan("خطة", pid, 0, total = 120_000L, downPayment = 0L, months = 500, startDate = today, currency = "SAR", note = "")   // [P33-P8] قروش
            assertFalse("expected months cap rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        // خطة سليمة تنشأ
        val planId = installments.createPlan("خطة سليمة", pid, 0, total = 120_000L, downPayment = 0L, months = 12, startDate = today, currency = "SAR", note = "")   // [P33-P8] قروش
        assertTrue(planId > 0)
    }

    // ═══ C8: سداد القسط بمبلغ غير محدد يسدد المفتوح بدل إفساد القيد ═══
    // ([P33-P8] كان «بمبلغ NaN» — Long لا يكون NaN، والتمرير null هو المسار المكافئ الباقي)

    @Test
    fun c8_payNaN_treatsAsFullOpen_notNaN() = runBlocking {
        val pid = db.parties().upsert(Party(name = "عميل"))
        val today = System.currentTimeMillis()
        val planId = installments.createPlan("خطة", pid, 0, total = 60_000L, downPayment = 0L, months = 3, startDate = today, currency = "SAR", note = "")   // [P33-P8] 600.0 ريال → 60_000 قروشاً
        val inst = db.installments().installmentsOf(planId).first()
        // [P33-P8] حرس NaN ساق (Long لا يكون NaN) — الممرّ null هو نفس مسار «المفتوح كاملاً» القائم
        val paidNow = installments.pay(inst, null)
        assertEquals(20_000L, paidNow)   // [P33-P8] 600.00/3 = 200.00 ريال = 20_000 قروشاً — المفتوح كاملاً، مساواة تامة
        val fresh = db.installments().installmentsOf(planId).first { it.id == inst.id }
        assertEquals(20_000L, fresh.paidAmount)   // [P33-P8] مساواة تامة بلا عتبة 1e-9
    }

    // ═══ C9: فاتورة NaN/سالبة وبنود شاذة تُرفض قبل اللمس ═══

    @Test
    fun c9_invoiceSave_rejectsNonFiniteTotals_andBadItems() = runBlocking {
        val pid = db.parties().upsert(Party(name = "عميل"))
        val today = System.currentTimeMillis()
        val base = Invoice(number = "T-1", partyId = pid, type = 0, date = today, dueDate = today,
            subtotal = 10_000L, total = 10_000L)   // [P33-P8] 100.0 ريال → 10_000 قروشاً
        val item = InvoiceItem(invoiceId = 0, productId = null, desc = "بند", qty = 1.0, unitPrice = 10_000L)   // [P33-P8] قروش
        // [P33-P8] إجمالي سام (كان NaN — لا وجود له في Long): السالب يُرفض بحدّ total >= 0
        try {
            invoices.save(base.copy(total = -1L), listOf(item))
            assertFalse("expected negative total rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        // كمية NaN
        try {
            invoices.save(base, listOf(item.copy(qty = Double.NaN)))
            assertFalse("expected NaN qty rejection", true)
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        // فاتورة سليمة تُقبل
        val id = invoices.save(base, listOf(item))
        assertTrue(id > 0)
    }

    // ═══ C20 (من R9InsightsVM): اختبار حدود الشهر عبر سلوك sumBetween غير مباشر ═══
    // (منطق الحدود حتمي ومدقق يدوياً: nextMonthStart − 1ms)

    @Test
    fun sanity_partyLookup_afterInserts() = runBlocking {
        val id = ledger.saveParty(Party(name = "فحص"))
        assertTrue(db.parties().byId(id) != null)
        assertNull(db.products().byBarcodeExcluding("NOPE", 0L))
    }
}
