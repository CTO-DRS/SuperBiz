package com.superbiz.app

import com.superbiz.app.data.db.Product
import com.superbiz.app.widget.StockMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات منطق ويدجت «المخزون المنخفض»
 * عزل المنخفض والنافد بغير المؤرشفة وغير محددة حد الطلب،
 * خرائط الخطورة، وترتيب الأصناف الأكثر إلحاحاً.
*/
class StockMathTest {

    private fun p(
        name: String, qty: Double, reorder: Double, archived: Boolean = false
    ) = Product(id = 0, name = name, stockQty = qty, reorderLevel = reorder, archived = archived)

    @Test
    fun `low keeps only unarchived at or below reorder level`() {
        val items = listOf(
            p("سكر", 5.0, 10.0),        // تحت حد الطلب — يُحتسب
            p("أرز", 10.0, 10.0),       // عند حد الطلب بالضبط (≤) — يُحتسب
            p("شاي", 11.0, 10.0),       // فوق الحد — يُستثنى
            p("ملح", 0.0, 0.0),         // بلا حد طلب — يُستثنى من «المنخفض»
            p("بهار", 1.0, 5.0, archived = true) // مؤرشف — يُستثنى
        )
        assertEquals(listOf("سكر", "أرز"), StockMath.low(items).map { it.name })
    }

    @Test
    fun `out keeps only unarchived zero stock with a reorder level`() {
        val items = listOf(
            p("زيت", 0.0, 8.0),         // نافد — يُحتسب
            p("دقيق", -3.0, 8.0),       // سالب (خلل جرد) — نافد أيضاً
            p("تمر", 0.0, 0.0),         // بلا حد طلب — يُستثنى
            p("عصير", 2.0, 8.0),        // فيه رصيد — يُستثنى
            p("ماء", 0.0, 8.0, archived = true) // مؤرشف — يُستثنى
        )
        assertEquals(listOf("زيت", "دقيق"), StockMath.out(items).map { it.name })
    }

    @Test
    fun `severity maps out first then low then healthy`() {
        assertEquals(2, StockMath.severity(lowCount = 3, outCount = 1))
        assertEquals(1, StockMath.severity(lowCount = 2, outCount = 0))
        assertEquals(0, StockMath.severity(lowCount = 0, outCount = 0))
    }

    @Test
    fun `worst names sorts by stock to reorder ratio ascending and caps`() {
        val items = listOf(
            p("كامل", 50.0, 10.0),   // نسبة 5.0 — الأقل إلحاحاً (ليست منخفضة أصلاً)
            p("منخفض", 8.0, 10.0),   // نسبة 0.8
            p("حرج", 1.0, 10.0),     // نسبة 0.1
            p("نافد", 0.0, 10.0)     // نسبة 0.0 — الأكثر إلحاحاً
        )
        val names = StockMath.worstNames(items, max = 2)
        assertEquals(listOf("نافد", "حرج"), names)
    }

    @Test
    fun `worst names ignores healthy items and honors max of one`() {
        val items = listOf(
            p("سليم", 99.0, 10.0),
            p("منخفض", 3.0, 10.0)
        )
        val names = StockMath.worstNames(items, max = 5)
        assertEquals(listOf("منخفض"), names)
        assertTrue(StockMath.worstNames(items, max = 0).size == 1) // السقف الأدنى 1
    }

    @Test
    fun `empty inventory is healthy`() {
        assertEquals(0, StockMath.low(emptyList()).size)
        assertEquals(0, StockMath.out(emptyList()).size)
        assertTrue(StockMath.worstNames(emptyList(), 3).isEmpty())
    }
}
