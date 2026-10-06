package com.superbiz.app

import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Product
import com.superbiz.app.domain.InvoiceDiscount
import com.superbiz.app.domain.InvoiceFilters
import com.superbiz.app.domain.InvoiceText
import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.PosCartLine
import com.superbiz.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * : اختبارات موجة نقطة البيع والفواتير —
 * توزيع الخصم قرشاً بقرش، بنّاء نص الفاتورة، مُرشِّح الفواتير، الربح، البيع الحر، وإعادة الطلب.
 * [P33-P8] كل المبالغ الحرفية بالقروش (Long) — الريال ×100 حرفياً (12.5 → Money.toPiasters(12.5)).
*/
class PosInvoiceP4Test {

    // [P33-P8] unitPrice/costPrice قروش Long — qty/stock كميات تبقى Double
    private fun line(id: Long, name: String, price: Long, cost: Long, qty: Double = 1.0) =
        PosCartLine(productId = id, name = name, unitPrice = price, costPrice = cost, qty = qty, stock = 100.0)

    // [P33-P8] total قروش Long (كان 100.0 ريال)
    private fun inv(
        id: Long, status: Int, date: Long, total: Long = 10000L,
        number: String = "INV-000$id", partyId: Long = 1
    ) = Invoice(
        id = id, number = number, partyId = partyId, type = 0, date = date, dueDate = date,
        subtotal = total, total = total, status = status
    )

    // ══ 1) تفسير الخصم ورفض غير الصالح ══

    @Test
    fun discount_resolve_rejectsNegative_aboveTotal_andPercentOver100() {
        assertNull(InvoiceDiscount.resolve(-1.0, InvoiceDiscount.MODE_AMOUNT, 10000L))
        assertNull(InvoiceDiscount.resolve(100.01, InvoiceDiscount.MODE_AMOUNT, 10000L))
        assertNull(InvoiceDiscount.resolve(101.0, InvoiceDiscount.MODE_PERCENT, 10000L))
        assertNull(InvoiceDiscount.resolve(Double.NaN, InvoiceDiscount.MODE_AMOUNT, 10000L))
        // حدود القبول: الإجمالي بالضبط و100% بالضبط — [P33-P8] مساواة صحيحة تامة بلا 1e-9
        assertEquals(10000L, InvoiceDiscount.resolve(100.0, InvoiceDiscount.MODE_AMOUNT, 10000L)!!)
        assertEquals(10000L, InvoiceDiscount.resolve(100.0, InvoiceDiscount.MODE_PERCENT, 10000L)!!)
    }

    @Test
    fun discount_resolve_percentComputesAmountFromSubtotal() {
        // [P33-P8] النتيجة قروش Long (كانت 3.33 ريال) — والمدخل المبلغي يمر عبر toPiasters حصراً
        assertEquals(333L, InvoiceDiscount.resolve(10.0, InvoiceDiscount.MODE_PERCENT, 3333L)!!)
        assertEquals(0L, InvoiceDiscount.resolve(0.0, InvoiceDiscount.MODE_PERCENT, 5000L)!!)
        assertEquals(Money.toPiasters(12.5), InvoiceDiscount.resolve(12.5, InvoiceDiscount.MODE_AMOUNT, 5000L)!!)
    }

    // ══ 2) التوزيع قرشاً بقرش ══

    @Test
    fun discount_distribute_sumsExactlyToDiscount() {
        val lines = listOf(line(1, "أ", 1000L, 0L), line(2, "ب", 1000L, 0L))
        val shares = InvoiceDiscount.distribute(lines, 3L)!!
        assertEquals(2, shares.size)
        assertEquals(3L, shares.sum())
        // البقية قرش واحد تذهب لأكبر حصة (التعادل → السطر الأول)
        assertEquals(2L, shares[0])
        assertEquals(1L, shares[1])
    }

    @Test
    fun discount_distribute_largestShareTakesRemainder() {
        val lines = listOf(line(1, "كبير", 3000L, 0L), line(2, "صغير", 1000L, 0L))
        val shares = InvoiceDiscount.distribute(lines, 5L)!!
        assertEquals(5L, shares.sum())
        // الحصص الأرضية: 3 و1 قروش — قرش البقايا (1) لأكبر حصة
        assertEquals(4L, shares[0])
        assertEquals(1L, shares[1])
    }

    @Test
    fun discount_distribute_rejectsInvalid_andZeroGivesZeros() {
        val lines = listOf(line(1, "أ", 1000L, 0L), line(2, "ب", 1000L, 0L))
        assertNull(InvoiceDiscount.distribute(lines, -1L))
        assertNull(InvoiceDiscount.distribute(lines, 2001L)) // أكبر من الإجمالي 2000 قرشاً
        assertNull(InvoiceDiscount.distribute(emptyList(), 500L))
        val zero = InvoiceDiscount.distribute(lines, 0L)!!
        assertEquals(listOf(0L, 0L), zero)
    }

    // ══ 3) الربح الحي للسلة ══

    @Test
    fun cartProfit_hiddenWhenAllLinesCostless() {
        val lines = listOf(line(1, "خدمة", 5000L, 0L), line(2, "خدمة٢", 2000L, 0L))
        assertNull(PosCart.cartProfit(lines, 0L))
        assertNull(PosCart.cartProfit(emptyList(), 0L))
    }

    @Test
    fun cartProfit_countsKnownCostsOnly_minusDiscount() {
        val lines = listOf(
            line(1, "عصير", 1000L, 400L, qty = 2.0), // ربح 1200 قرشاً
            line(2, "خدمة بلا تكلفة", 500L, 0L, qty = 1.0) // تُستثنى (تكلفة مجهولة)
        )
        assertEquals(1000L, PosCart.cartProfit(lines, 200L)!!)
    }

    // ══ 4) الربح المحقق لكل فاتورة ══

    @Test
    fun invoiceProfit_usesCurrentProductCost_minusInvoiceDiscount() {
        val items = listOf(
            InvoiceItem(invoiceId = 1, productId = 7, desc = "بند", qty = 2.0, unitPrice = 1000L),
            InvoiceItem(invoiceId = 1, productId = null, desc = "بيع حر", qty = 1.0, unitPrice = 1500L)
        )
        val profit = PosCart.invoiceProfit(items, 200L, costOf = { pid -> if (pid == 7L) 400L else null })
        // (10−4)×2 = 12 ريالاً (1200 قرشاً) − خصم 200 = 1000 — البند الحر بلا تكلفة يُستثنى
        assertEquals(1000L, profit!!)
    }

    @Test
    fun invoiceProfit_hiddenWhenNoCostAvailableForAnyItem() {
        val items = listOf(
            InvoiceItem(invoiceId = 1, productId = null, desc = "بيع حر", qty = 1.0, unitPrice = 1500L),
            InvoiceItem(invoiceId = 1, productId = 99, desc = "محذوف", qty = 2.0, unitPrice = 800L)
        )
        assertNull(PosCart.invoiceProfit(items, 0L, costOf = { null }))
    }

    // ══ 5) مُرشِّح الفواتير: حالة + نطاق ══

    @Test
    fun invoiceFilters_byStatus_andRange() {
        val now = System.currentTimeMillis()
        val today = now // فاتورة «اليوم» الآن — تمر دائماً ضمن نطاق اليوم
        val old = now - 10L * 86_400_000L
        val list = listOf(
            inv(1, status = 2, date = today),          // مدفوعة اليوم
            inv(2, status = 0, date = old),            // غير مدفوعة قديمة
            inv(3, status = 3, date = today)           // ملغاة اليوم
        )
        assertEquals(1, InvoiceFilters.apply(list, emptyMap(), 2, InvoiceFilters.RANGE_ALL, "", now).size)
        // نطاق اليوم يشمل المدفوعة والملغاة اليوم — والملغاة تظهر (رقاقة صادقة)
        val todayOnly = InvoiceFilters.apply(list, emptyMap(), -1, InvoiceFilters.RANGE_TODAY, "", now)
        assertEquals(2, todayOnly.size)
        // الكل بلا نطاق: 3 فواتير بما فيها الملغاة
        assertEquals(3, InvoiceFilters.apply(list, emptyMap(), -1, InvoiceFilters.RANGE_ALL, "", now).size)
        assertEquals(0L, InvoiceFilters.rangeStart(InvoiceFilters.RANGE_ALL, now))
        assertTrue(InvoiceFilters.rangeStart(InvoiceFilters.RANGE_MONTH, now) <= today)
    }

    @Test
    fun invoiceFilters_arabicNormalizedQuery_matchesNumberAndParty() {
        val now = System.currentTimeMillis()
        val list = listOf(
            inv(1, status = 0, date = now, number = "INV-002", partyId = 1),
            inv(2, status = 0, date = now, number = "INV-009", partyId = 2)
        )
        val parties = mapOf(1L to "أحمد", 2L to "مؤسسة النور")
        // تطبيع الهمزة: «احمد» تجد «أحمد»
        assertEquals(1, InvoiceFilters.apply(list, parties, -1, 0, "احمد", now).size)
        // رقم الفاتورة بحروف صغيرة
        assertEquals(1, InvoiceFilters.apply(list, parties, -1, 0, "inv-002", now).size)
        // جزء من اسم الطرف
        assertEquals(1, InvoiceFilters.apply(list, parties, -1, 0, "نور", now).size)
        // لا نتائج
        assertTrue(InvoiceFilters.apply(list, parties, -1, 0, "غير موجود", now).isEmpty())
    }

    // ══ 6) بنّاء نص الفاتورة ══

    @Test
    fun invoiceText_summary_containsNumberDatePartyItemsTotal() {
        val now = System.currentTimeMillis()
        val invoice = Invoice(
            id = 1, number = "INV-0042", partyId = 5, type = 0, date = now, dueDate = now,
            subtotal = 3000L, discount = 200L, taxAmount = 0L, total = 2800L, paid = 0L,
            currency = "SAR"
        )
        val items = listOf(
            InvoiceItem(invoiceId = 1, productId = 1, desc = "عصير", qty = 2.0, unitPrice = 1000L),
            InvoiceItem(invoiceId = 1, productId = null, desc = "توصيل", qty = 1.0, unitPrice = 1000L)
        )
        val text = InvoiceText.summary(invoice, items, "عميل التجارب")
        assertTrue(text.contains("INV-0042"))
        assertTrue(text.contains("عميل التجارب"))
        assertTrue(text.contains("عصير"))
        assertTrue(text.contains("توصيل"))
        assertTrue(text.contains("28"))
        assertTrue(text.contains("SAR"))
        assertTrue(text.contains("المتبقي")) // غير مسددة → المتبقي يظهر
    }

    // ══ 7) البيع الحر ══

    @Test
    fun freeSaleLine_buildsSingleFreeLine_andRejectsInvalidAmount() {
        val l = PosCart.freeSaleLine(4550L, "بيع حر", "توصيل طلبيات")
        assertNull(l.productId)
        assertEquals(1.0, l.qty, 1e-9)
        assertEquals(4550L, l.unitPrice)
        assertEquals(0L, l.costPrice)
        assertTrue(l.name.contains("بيع حر"))
        assertTrue(l.name.contains("توصيل طلبيات"))
        // بلا وصف → الاسم هو الوسم فقط
        assertEquals("بيع حر", PosCart.freeSaleLine(900L, "بيع حر", "  ").name)
        // المبالغ غير الصالحة مرفوضة
        try {
            PosCart.freeSaleLine(0L, "بيع حر", "")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* مقبول */ }
        try {
            PosCart.freeSaleLine(-300L, "بيع حر", "")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* مقبول */ }
    }

    // ══ 8) إعادة الطلب: بنود الفاتورة → سلة ══

    @Test
    fun reorder_linesFromInvoiceItems_usesCurrentProductData() {
        val product = Product(
            id = 7, name = "عصير برتقال", salePrice = 1200L, costPrice = 700L, stockQty = 50.0
        )
        val items = listOf(
            InvoiceItem(invoiceId = 1, productId = 7, desc = "اسم قديم", qty = 3.0, unitPrice = 1000L),
            InvoiceItem(invoiceId = 1, productId = null, desc = "خدمة تركيب", qty = 2.0, unitPrice = 2500L),
            InvoiceItem(invoiceId = 1, productId = 7, desc = "سطر مهمَل", qty = 0.0, unitPrice = 1000L)
        )
        val lines = PosCart.linesFromInvoiceItems(items) { pid -> if (pid == 7L) product else null }
        assertEquals(2, lines.size)
        // سطر المنتج: الكمية من الفاتورة والسعر والتكلفة والمخزون من المنتج الحالي
        assertEquals(7L, lines[0].productId)
        assertEquals(3.0, lines[0].qty, 1e-9)
        assertEquals(1200L, lines[0].unitPrice)
        assertEquals(700L, lines[0].costPrice)
        assertEquals(50.0, lines[0].stock, 1e-9)
        assertEquals("عصير برتقال", lines[0].name)
        // البند الحر القديم يعود بسعر الفاتورة نفسه
        assertEquals(null, lines[1].productId)
        assertEquals(2500L, lines[1].unitPrice)
    }
}
