package com.superbiz.app.domain.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F1 - ADR-005] اختبارات مطابق المنتجات لأسماء OCR — تطبيع عربي كامل
 * (همزات/تاء مربوطة/تشكيل/تطويل) وتشابه Levenshtein + الاحتواء، حد قرار موثق 0.55.
 */
class OcrProductMatcherTest {

    private val catalog = listOf(
        1L to "شاورما دجاج",
        2L to "عصير برتقال طازج",
        3L to "حليب المراعي 1 لتر",
        4L to "شاورما لحم عربي"
    )

    @Test
    fun `exact normalized match scores full`() {
        val m = OcrProductMatcher.match("شاورما دجاج", catalog)!!
        assertEquals(1L, m.productId)
        assertTrue(m.score >= 0.99)
    }

    @Test
    fun `containment match finds product within sentence`() {
        val m = OcrProductMatcher.match("شاورما دجاج سوري", catalog)!!
        assertEquals(1L, m.productId)
        assertTrue("الاحتواء فوق الحد: ${m.score}", m.score >= 0.70)
    }

    @Test
    fun `hamza forms unify`() {
        val m = OcrProductMatcher.match("أشاورما أحمد", listOf(9L to "اشاورما احمد"))!!
        assertEquals(9L, m.productId)
        assertTrue(m.score >= 0.90)
    }

    @Test
    fun `ta marbuta unifies with haa`() {
        val m = OcrProductMatcher.match("دجاجة مشوية", listOf(3L to "دجاجه مشويه"))!!
        assertEquals(3L, m.productId)
        assertTrue(m.score >= 0.90)
    }

    @Test
    fun `diacritics and tatweel stripped`() {
        val m = OcrProductMatcher.match("شَــاورمــة دجـاج", listOf(1L to "شاورما دجاج"))!!
        assertEquals(1L, m.productId)
        assertTrue(m.score >= 0.90)
    }

    @Test
    fun `unrelated name yields null`() {
        assertNull(OcrProductMatcher.match("لابتوب ألترابوك", catalog))
    }

    @Test
    fun `best candidate wins among containments`() {
        val m = OcrProductMatcher.match("شاورما", listOf(1L to "شاورما دجاج", 2L to "شاورما"))!!
        assertEquals(2L, m.productId)
        assertEquals(1.0, m.score, 1e-9)
    }

    @Test
    fun `empty ocr name yields null`() {
        assertNull(OcrProductMatcher.match("   ", catalog))
    }

    @Test
    fun `empty catalog yields null`() {
        assertNull(OcrProductMatcher.match("شاورما دجاج", emptyList()))
    }

    @Test
    fun `latin digits normalized like money digits`() {
        val m = OcrProductMatcher.match("حليب المراعي ١ لتر", catalog)!!
        assertEquals(3L, m.productId)
    }
}
