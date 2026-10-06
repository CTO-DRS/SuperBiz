package com.superbiz.app

import com.superbiz.app.widget.CashMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات منطق ويدجت «النقد في الصندوق»
 * حالة الرصيد (نقد/فارغ/عجز) + صافي حركة اليوم + كشف الحركة.
 * [P33-P8] الرصيد قروش Long — المقارنات مساواة تامة بلا عتبات الفاصلة العائمة (0.004 أُلغيت).
*/
class CashMathTest {

    @Test
    fun `positive balance is cash state`() {
        assertEquals(1, CashMath.state(15_000L)) // 150.0 ريال = 15000 قرشاً
        assertEquals(1, CashMath.state(1L))      // [P33-P8] قرش واحد فوق الصفر صريح — بلا عتبة
    }

    @Test
    fun `zero balance is empty state`() {
        assertEquals(0, CashMath.state(0L))
        // [P33-P8] كانت state(0.004) «فارغاً عملياً» داخل العتبة — الفارغ الوحيد الآن 0L تماماً
    }

    @Test
    fun `negative balance is deficit state`() {
        assertEquals(-1, CashMath.state(-5_000L)) // -50.0 ريال
        assertEquals(-1, CashMath.state(-1L))     // [P33-P8] قرش واحد عجز صريح — بلا عتبة
    }

    @Test
    fun `net today is income minus outcome exact`() {
        assertEquals(3_000L, CashMath.netToday(10_000L, 7_000L))  // 100.0 − 70.0 ريال
        assertEquals(-2_000L, CashMath.netToday(0L, 2_000L))
        // [P33-P8] كانت 0.3 تُقارن بعتبة بسبب فاصلة 0.1+0.2 الحسابية — 30 قرشاً طرح صحيح تام
        assertEquals(30L, CashMath.netToday(30L, 0L))
    }

    @Test
    fun `hasMovement detects quiet day`() {
        assertTrue(CashMath.hasMovement(50_000L, 0L))
        assertTrue(CashMath.hasMovement(0L, 12_000L))
        assertFalse(CashMath.hasMovement(0L, 0L))
        // [P33-P8] كانت hasMovement(0.003, 0.002) ساكنة داخل العتبة — الآن أي قرش حركة تامة (≠ 0L)
        assertTrue(CashMath.hasMovement(1L, 0L))
        assertTrue(CashMath.hasMovement(0L, 1L))
    }
}
