package com.superbiz.app

import com.superbiz.app.domain.ArabicWords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * — اختبارات التفقيط (تحويل المبالغ إلى كلمات عربية)
*/
class ArabicWordsTest {

    @Test fun integers_basics() {
        assertEquals("صفر", ArabicWords.integerToWords(0))
        assertEquals("واحد", ArabicWords.integerToWords(1))
        assertEquals("اثنان", ArabicWords.integerToWords(2))
        assertEquals("خمسة عشر", ArabicWords.integerToWords(15))
        assertEquals("عشرون", ArabicWords.integerToWords(20))
        assertEquals("خمسة وعشرون", ArabicWords.integerToWords(25))
        assertEquals("مئة", ArabicWords.integerToWords(100))
        assertEquals("مئتان", ArabicWords.integerToWords(200))
        assertEquals("ثلاثمئة وخمسة وأربعون", ArabicWords.integerToWords(345))
    }

    @Test fun integers_thousandsScales() {
        assertEquals("ألف", ArabicWords.integerToWords(1_000))
        assertEquals("ألفان", ArabicWords.integerToWords(2_000))
        assertEquals("ثلاثة آلاف", ArabicWords.integerToWords(3_000))
        // [P20-FIX agent13]: 11..99 ممنوص منصوب (أحد عشر ألفاً) — كانت بلا تنوين
        assertEquals("أحد عشر ألفاً", ArabicWords.integerToWords(11_000))
        assertEquals("خمسة وعشرون ألفاً وخمسمئة", ArabicWords.integerToWords(25_500))
        // مليون
        assertEquals("مليون", ArabicWords.integerToWords(1_000_000))
        assertEquals("مليونان", ArabicWords.integerToWords(2_000_000))
        assertEquals("ثلاثة ملايين", ArabicWords.integerToWords(3_000_000))
        assertEquals("مئة وأحد عشر ألفاً", ArabicWords.integerToWords(111_000))
    }

    @Test fun amounts_fullSentences() {
        assertEquals(
            "فقط أربعة عشر ريالاً وثمانية وثلاثون هللة لا غير",
            ArabicWords.amountInWords(14.38, "ريال", "هللة")
        )
        assertEquals("فقط صفر ريال لا غير", ArabicWords.amountInWords(0.0, "ريال", "هللة"))
        assertEquals("فقط واحد ريال لا غير", ArabicWords.amountInWords(1.0, "ريال", "هللة"))
        assertEquals("فقط ريالان لا غير", ArabicWords.amountInWords(2.0, "ريال", "هللة"))
        assertEquals("فقط خمسة ريالات لا غير", ArabicWords.amountInWords(5.0, "ريال", "هللة"))
        assertEquals("فقط واحد ريال وخمسون هللة لا غير", ArabicWords.amountInWords(1.50, "ريال", "هللة"))
    }

    /** [P5-H13 إصلاح]: المثنى للكسر المنتهي بـ ة كان ينتج «هللةان» ويُطبع على PDF
     *  كل فاتورة يكسرها 2 هللة — الصواب «هللتان» باستبدال تاء المؤنث تاء المثنى */
    @Test fun amounts_dualFeminineHalala() {
        assertEquals(
            "فقط ريالان وهللتان لا غير",
            ArabicWords.amountInWords(2.02, "ريال", "هللة")
        )
        // المثنى للساكن النهاية يبقى بالألفان — سلوك سليم لم يُمس
        assertEquals("فقط ريالان وفلسان لا غير", ArabicWords.amountInWords(2.02, "ريال", "فلس"))
    }

    @Test fun amounts_roundingHalfCent() {
        // 1.005 يقرّب إلى 101 هللة = ريال وهللة
        assertEquals(
            "فقط واحد ريال وواحد هللة لا غير",
            ArabicWords.amountInWords(1.005, "ريال", "هللة")
        )
    }

    @Test fun rangeGuard() {
        try {
            ArabicWords.integerToWords(1_000_000_000)
            org.junit.Assert.fail("يجب رفض خارج المدى")
        } catch (e: IllegalArgumentException) { /* متوقع */ }
        try {
            ArabicWords.amountInWords(-5.0, "ريال", "هللة")
            org.junit.Assert.fail("يجب رفض السالب")
        } catch (e: IllegalArgumentException) { /* متوقع */ }
    }
}
