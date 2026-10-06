package com.superbiz.app

import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.PosCartLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCartTest {

    // [P33-P8] الأسعار والتكاليف قروش Long (10.0 ريال = 1000L) — الكميات والمخزون تبقى Double
    private fun line(id: Long, name: String, price: Long, cost: Long, qty: Double = 1.0, stock: Double = 100.0) =
        PosCartLine(productId = id, name = name, unitPrice = price, costPrice = cost, qty = qty, stock = stock)

    @Test
    fun add_mergesSameProduct() {
        val l = listOf(line(1, "عصير", 500L, 300L))
        val out = PosCart.add(l, line(1, "عصير", 500L, 300L))
        assertEquals(1, out.size)
        assertEquals(2.0, out[0].qty, 1e-9)
    }

    @Test
    fun add_newProductAppendsLine() {
        val l = listOf(line(1, "عصير", 500L, 300L))
        val out = PosCart.add(l, line(2, "خبز", 250L, 100L))
        assertEquals(2, out.size)
        assertEquals(1.0, out[1].qty, 1e-9)
    }

    @Test
    fun setQty_zeroRemovesLine() {
        val l = listOf(
            line(1, "عصير", 500L, 300L, qty = 2.0),
            line(2, "خبز", 250L, 100L)
        )
        val out = PosCart.setQty(l, 1L, 0.0)
        assertEquals(1, out.size)
        assertEquals(2L, out[0].productId)
    }

    @Test
    fun subtotal_sumsQtyTimesPrice() {
        val l = listOf(
            line(1, "عصير", 500L, 300L, qty = 2.0),
            line(2, "خبز", 250L, 100L, qty = 4.0)
        )
        // [P33-P8] مجموع قروش صحيح تام: 2×500 + 4×250 = 2000 (كان 20.0 ريال بعتبة 1e-9)
        assertEquals(2000L, PosCart.subtotal(l))
        assertEquals(6.0, PosCart.units(l), 1e-9) // الوحدات كمية تبقى Double
    }

    @Test
    fun cost_sumsQtyTimesCost() {
        val l = listOf(
            line(1, "عصير", 500L, 300L, qty = 2.0),
            line(2, "خبز", 250L, 100L, qty = 4.0)
        )
        // [P33-P8] 2×300 + 4×100 = 1000 قرشاً تام
        assertEquals(1000L, PosCart.cost(l))
    }

    @Test
    fun netTotal_discountNeverBelowZero() {
        val l = listOf(line(1, "عصير", 500L, 300L, qty = 2.0)) // 1000 قرشاً
        // [P33-P8] الخصم قروش Long — مساواة تامة بلا عتبة 1e-9
        assertEquals(750L, PosCart.netTotal(l, 250L))
        assertEquals(0L, PosCart.netTotal(l, 9_900L))
    }

    @Test
    fun remove_deletesOnlyTargetLine() {
        val l = listOf(line(1, "عصير", 500L, 300L), line(2, "خبز", 250L, 100L))
        val out = PosCart.remove(l, 1L)
        assertEquals(1, out.size)
        assertEquals(2L, out[0].productId)
    }

    @Test
    fun rounding_appliesToLineArithmetics() {
        // [P33-P8] كان 1.99×3 = 5.97 ريال بعتبة — الآن 199×3 = 597 قرشاً حاصل ضرب صحيح تام
        val l = listOf(line(1, "ماء", 199L, 90L, qty = 3.0))
        assertEquals(597L, PosCart.subtotal(l))
        assertEquals(270L, PosCart.cost(l))
    }

    @Test
    fun isEmpty_reflectsCartState() {
        assertTrue(PosCart.isEmpty(emptyList()))
        assertFalse(PosCart.isEmpty(listOf(line(1, "عصير", 500L, 300L))))
    }
}
