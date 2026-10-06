package com.superbiz.app.util

import com.superbiz.app.ui.components.parseNum
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P7-L2 إصلاح] — تثبيت عقد parseNum الصامت (سلسلة منحلة ⇒ 0.0) بعد نقل
 * التنفيذ القياسي إلى util.NumText ([P7-L1 إصلاح]) والاحتفاظ بـ alias مفوض
 * في ui.components.Common بنفس التوقيع. بلا تغيير سلوك — الاختبارات تثبت
 * العقد كما هو قبل وبعد النقل، وأن مدخل الـalias يطابق المدخل القياسي.
 */
class NumTextParseTest {

    @Test
    fun `blank and empty collapse to zero`() {
        assertEquals(0.0, NumText.parseNum(""), 0.0)
        assertEquals(0.0, NumText.parseNum("   "), 0.0)
        assertEquals(0.0, NumText.parseNum("\t\n"), 0.0)
    }

    @Test
    fun `letters and garbage collapse to zero silently`() {
        assertEquals(0.0, NumText.parseNum("abc"), 0.0)
        assertEquals(0.0, NumText.parseNum("خمسون"), 0.0)
        assertEquals(0.0, NumText.parseNum("12x"), 0.0)
        assertEquals(0.0, NumText.parseNum("٥٠ ريال"), 0.0)   // الوحدة تلغي الصيغة الرقمية
        assertEquals(0.0, NumText.parseNum("1.2.3"), 0.0)
    }

    @Test
    fun `arabic-indic digits and decimal separator parse`() {
        assertEquals(12.5, NumText.parseNum("١٢٫٥"), 1e-9)
        assertEquals(50.0, NumText.parseNum("٥٠"), 1e-9)
        // فارسية/أردية (لوحة احتياطية)
        assertEquals(7.25, NumText.parseNum("۷٫۲۵"), 1e-9)
    }

    @Test
    fun `group separators are dropped both western and arabic`() {
        assertEquals(1200.0, NumText.parseNum("1,200"), 1e-9)
        assertEquals(1200.0, NumText.parseNum("١٬٢٠٠"), 1e-9)
        assertEquals(1234567.89, NumText.parseNum("1,234,567.89"), 1e-9)
    }

    @Test
    fun `western decimal and negative values pass through`() {
        assertEquals(12.5, NumText.parseNum("12.5"), 1e-9)
        assertEquals(-50.0, NumText.parseNum("-50"), 1e-9)     // السالب مقبول — القيد عند المستدعي
        assertEquals(-2.75, NumText.parseNum("-٢٫٧٥"), 1e-9)
        assertEquals(0.005, NumText.parseNum("0.005"), 1e-9)
    }

    @Test
    fun `ui alias delegates to the util standard`() {
        // [P7-L1 إصلاح] المدخل القديم (ui.components) هو alias مفوض للقياسي
        for (s in listOf("", "abc", "١٢٫٥", "1,200", "-50", "12.5"))
            assertEquals(NumText.parseNum(s), parseNum(s), 0.0)
    }
}
