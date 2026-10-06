package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P42-R3] جولة 3 — عقد تحليل نص «نسبة السطر» في محرر الفواتير.
 *
 * الموجة تفعّل القرار المؤجل الموثق في P41 (الحقول جاهزة في v11 والمحرك يدعم
 * النسبة الصريحة منذ يومها) — والمدخل الوحيد الجديد هنا parseExplicitRate:
 * نص حقل المحرر → قيمة معلنة بعقد الحصر نفسه، فبقي المحرك مصدر الحقيقة
 * الوحيد للحفظ والتقرير وXML بلا أي تغيير في العقود القائمة.
 *
 * العقد:
 * • فارغ/أبيض/غير رقمي/سالب ⇒ null = وراثة نسبة الرأس (RATE_INHERIT) —
 *   السلوك التاريخي الحرفي كما لو لم يُكتب شيء.
 * • الفاصلة «,» فاصل عشري مقبول (لوحة الأرقام العربية).
 * • خارج 0..100 يُحصر — والصفر صريح (0.0) يفتح مسار السطر بعقد isExplicit.
 * • الصفرية/المعفاة تتجاهل أي نسبة مكتوبة (effectiveRate ⇒ 0.0 بلا استثناء).
 */
class LineRateP42Test {

    @Test
    fun `النص الفارغ والأبيض يعودان بلا نسبة صريحة - وراثة الرأس`() {
        assertNull(LineTaxP41.parseExplicitRate(""))
        assertNull(LineTaxP41.parseExplicitRate("   "))
    }

    @Test
    fun `غير الرقمي يعود بلا نسبة - حرس دفاعي لا سياسة`() {
        assertNull(LineTaxP41.parseExplicitRate("abc"))
        assertNull(LineTaxP41.parseExplicitRate("12x"))
        assertNull(LineTaxP41.parseExplicitRate("5.5.5"))
    }

    @Test
    fun `السالب يعود بلا نسبة - عقد البذرة الدال على الوراثة لا يُفتح من المستخدم`() {
        assertNull(LineTaxP41.parseExplicitRate("-3"))
        assertNull(LineTaxP41.parseExplicitRate("-0.5"))
    }

    @Test
    fun `الرقمي البسيط يُقبل نسبة معلنة والفاصلة العربية فاصل عشري`() {
        assertEquals(5.0, LineTaxP41.parseExplicitRate("5")!!, 1e-9)
        assertEquals(5.5, LineTaxP41.parseExplicitRate("5.5")!!, 1e-9)
        assertEquals(5.5, LineTaxP41.parseExplicitRate("5,5")!!, 1e-9)
        assertEquals(15.0, LineTaxP41.parseExplicitRate(" 15 ")!!, 1e-9)
        assertEquals(0.25, LineTaxP41.parseExplicitRate("0,25")!!, 1e-9)
    }

    @Test
    fun `الصفر صريح ويفتح مسار السطر بعقد isExplicit`() {
        assertEquals(0.0, LineTaxP41.parseExplicitRate("0")!!, 1e-9)
        assertEquals(0.0, LineTaxP41.parseExplicitRate("0.0")!!, 1e-9)
        assertTrue(LineTaxP41.isExplicit(LineTaxP41.KIND_STANDARD, 0.0))
        // وضده: الوراثة (-1) ليست صريحة على الفئة القياسية — العقد التاريخي
        assertEquals(false, LineTaxP41.isExplicit(LineTaxP41.KIND_STANDARD, LineTaxP41.RATE_INHERIT))
    }

    @Test
    fun `خارج النطاق 0 إلى 100 يُحصر بعقد effectiveRate الدفاعي نفسه`() {
        assertEquals(100.0, LineTaxP41.parseExplicitRate("150")!!, 1e-9)
        assertEquals(100.0, LineTaxP41.parseExplicitRate("999")!!, 1e-9)
        assertEquals(100.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_STANDARD, 150.0, 15.0), 1e-9)
        assertEquals(0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_STANDARD, 0.0, 15.0), 1e-9)
    }

    @Test
    fun `الصفرية والمعفاة تتجاهلان أي نسبة مكتوبة - effectiveRate بلا استثناء`() {
        assertEquals(0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_ZERO, 5.0, 15.0), 1e-9)
        assertEquals(0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_EXEMPT, 5.0, 15.0), 1e-9)
        assertEquals(0L, LineTaxP41.lineTax(20_000L, LineTaxP41.KIND_ZERO, 5.0, 15.0))
        assertEquals(0L, LineTaxP41.lineTax(20_000L, LineTaxP41.KIND_EXEMPT, 5.0, 15.0))
    }

    @Test
    fun `الوراثة تعطي نسبة الرأس وإلا النسبة المعلنة فعالة`() {
        assertEquals(15.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_STANDARD, LineTaxP41.RATE_INHERIT, 15.0), 1e-9)
        assertEquals(5.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_STANDARD, 5.0, 15.0), 1e-9)
    }

    @Test
    fun `ضريبة السطر بالنسبة الصريحة بحرف التاريخ Math_round`() {
        // 200.00 ريال × 5% = 10.00 ريال = 1000 قروش
        assertEquals(1_000L, LineTaxP41.lineTax(20_000L, LineTaxP41.KIND_STANDARD, 5.0, 15.0))
        // صفر صريح على فئة قياسية ⇒ صفر رغم نسبة الرأس
        assertEquals(0L, LineTaxP41.lineTax(20_000L, LineTaxP41.KIND_STANDARD, 0.0, 15.0))
        // النص المُحلّل يمر بالمحرك كما سيحفظ: 150 يُحصر 100 ⇒ 200.00×100% = 200.00
        val clamped = LineTaxP41.parseExplicitRate("150")!!
        assertEquals(20_000L, LineTaxP41.lineTax(20_000L, LineTaxP41.KIND_STANDARD, clamped, 15.0))
    }
}
