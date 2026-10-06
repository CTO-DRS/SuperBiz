package com.superbiz.app

import com.superbiz.app.work.EmailPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * — اختبارات المستلم الافتراضي في جدولة التقرير
 * تنقية البريد والتحقق من صيغته، وترتيب الأفضلية
 * (المخصص للتقارير ← البريد المحفوظ في الملف الشخصي ← فارغ).
*/
class EmailPolicyTest {

    @Test
    fun `normalize trims and lowercases valid email`() {
        assertEquals("client@shop.com", EmailPolicy.normalize("  Client@Shop.COM  "))
        assertEquals("a.b+c@sub.domain.io", EmailPolicy.normalize("A.B+C@Sub.Domain.IO"))
    }

    @Test
    fun `normalize rejects malformed emails`() {
        assertNull(EmailPolicy.normalize(""))                       // فارغ
        assertNull(EmailPolicy.normalize("ab@c.d"))                 // أقصر من 6 محارف
        assertNull(EmailPolicy.normalize("no at sign"))             // بلا @
        assertNull(EmailPolicy.normalize("two@@ats.com"))           // @ مزدوج (الصيغة)
        assertNull(EmailPolicy.normalize("space @mail.com"))        // فيه مسافة
        assertNull(EmailPolicy.normalize("user@mailcom"))           // نطاق بلا نقطة
        assertNull(EmailPolicy.normalize("user@domain.c"))          // امتداد حرف واحد
    }

    @Test
    fun `pick prefers report recipient over saved profile email`() {
        assertEquals(
            "reports@biz.com",
            EmailPolicy.pick(primary = "Reports@Biz.com", fallback = "owner@personal.com")
        )
    }

    @Test
    fun `pick falls back to saved profile email when unset`() {
        assertEquals(
            "owner@personal.com",
            EmailPolicy.pick(primary = "", fallback = "owner@personal.com")
        )
        // الاحتياطي نفسه يجب أن يكون صالحاً وإلا يعيد فارغاً
        assertEquals("", EmailPolicy.pick(primary = "", fallback = "not-an-email"))
    }

    @Test
    fun `pick returns empty string when nothing is valid`() {
        assertEquals("", EmailPolicy.pick(primary = "bad", fallback = "worse"))
        assertEquals("", EmailPolicy.pick(primary = "", fallback = ""))
    }
}
