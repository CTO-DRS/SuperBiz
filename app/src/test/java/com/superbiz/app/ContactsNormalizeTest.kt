package com.superbiz.app

import com.superbiz.app.ui.screens.normalizeContactKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * — اختبارات تطبيع البحث في استيراد جهات الاتصال
 * الهمزات والتاء المربوطة والواو/الياء بالهمزة والتشكيل والأرقام العربية-الهندية.
 * الدالة نقية بلا أندرويد — تعمل على JVM المحلي.
*/
class ContactsNormalizeTest {

    @Test
    fun `alef variants and arabic-indic digits normalize`() {
        assertEquals(
            "احمد|0501234567",
            normalizeContactKey("أَحْمَد", "٠٥٠١٢٣٤٥٦٧")
        )
    }

    @Test
    fun `ta marbuta waw and ya hamza map`() {
        assertEquals("ساره|", normalizeContactKey("سارة", ""))
        assertEquals("مومن|", normalizeContactKey("مؤمن", ""))
        assertEquals("يمان|", normalizeContactKey("ئمان", ""))
    }

    @Test
    fun `latin names are lowercased`() {
        assertEquals("omar|0599", normalizeContactKey("Omar", "0599"))
    }

    @Test
    fun `phone keeps digits only`() {
        assertEquals("|970599123456", normalizeContactKey("", "+٩٧٠ (59) 9-123-456"))
    }

    @Test
    fun `diacritics are stripped`() {
        assertEquals("يوسف|", normalizeContactKey("يُوسُفٌ", ""))
    }

    @Test
    fun `equivalent contacts yield the same key`() {
        assertEquals(
            normalizeContactKey("أحمد", "050 123 4567"),
            normalizeContactKey("احمد", "٠٥٠١٢٣٤٥٦٧")
        )
    }
}
