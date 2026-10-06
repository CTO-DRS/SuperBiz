package com.superbiz.app

import com.superbiz.app.domain.algo.TextMath
import com.superbiz.app.ui.screens.DeviceContact
import com.superbiz.app.ui.screens.filterContacts
import com.superbiz.app.ui.screens.normalizeArabicText
import com.superbiz.app.ui.screens.normalizeContactKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P7-L3 إصلاح] — توحيد التطبيع العربي: ContactsSheet.normalizeArabicText مفوَّضة
 * إلى TextMath.arabicNormalize (القياسي)، فتغلق فجوة ى/ٱ/التطويل في مسار جهات
 * الاتصال أيضاً. الاختبارات تثبت أن الأزواج المكافئة تطابق بكل المسارات:
 * مفتاح جهة الاتصال، فلتر البحث، والدالة القياسية نفسها.
 */
class P7NormalizeUnifyTest {

    @Test
    fun `contacts normalize delegates to TextMath standard`() {
        // تطابق حرفي مع القياسي على مدخلات عربية ولاتينية
        for (s in listOf("أحمد", "على", "شــركة النور", "مؤسسة ٱ", "Omar", ""))
            assertEquals(TextMath.arabicNormalize(s), normalizeArabicText(s))
    }

    @Test
    fun `alef maksura pairs yield identical contact keys`() {
        assertEquals(
            normalizeContactKey("علي", "0501234567"),
            normalizeContactKey("على", "٠٥٠١٢٣٤٥٦٧"),
        )
    }

    @Test
    fun `tatweil and wasla collapse in contact keys`() {
        assertEquals(
            normalizeContactKey("شركة النور", "0599"),
            normalizeContactKey("شـــركة النور", "٠٥٩٩"),
        )
        assertEquals(
            normalizeContactKey("اسلام", ""),
            normalizeContactKey("ٱسلام", ""),
        )
    }

    @Test
    fun `contact search matches across normalized equivalents`() {
        val list = listOf(
            DeviceContact("على حسن", "0501112222"),
            DeviceContact("شــركة النور", "0599888777"),
            DeviceContact("مؤسسة الأمانة", "0566777888"),
        )
        // البحث عن «علي» يلتقط «على» بعد التطبيع الموحد (كان قبل الإصلاح لا يلتقطها)
        assertEquals(1, filterContacts(list, "علي").size)
        assertTrue(filterContacts(list, "علي").first().name == "على حسن")
        // البحث بالتطويل يلتقط الصيغة العادية والعكس
        assertEquals(1, filterContacts(list, "شــركة").size)
        assertEquals(1, filterContacts(list, "شركة").size)
        // والتطبيع القياسي نفسه يرى الأزواج متطابقة
        assertEquals("علي حسن", TextMath.arabicNormalize("على حسن"))
    }
}
