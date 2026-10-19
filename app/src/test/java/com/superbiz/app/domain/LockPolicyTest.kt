package com.superbiz.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RVW-11 V 3.2.3] عقد «لا بيومتريا بلا رمز» — تثبيت بوابة P5-H4 (القائمة فعلياً
 * في شاشة الإعدادات منذ موجة P5 وفي مسار كل مستخدم عبر شرط pinWrapped) كعقد
 * قابل للتنفيذ لا شرط UI حر قابل للانجراف.
 *
 * الخطر 3 (FINDINGS §2): المسار الطارئ في شاشة القفل يخدم الحالة القديمة فقط
 * (بصمة فعّالة من ما قبل البوابة بلا رمز) — حالة لا يمكن توليدها جديدة بعد هذا
 * العقد: أي تمكين جديد للبصمة يتطلب رمزاً مُعداً، والفشل مغلق.
 */
class LockPolicyTest {

    @Test
    fun `biometric enrollment requires a configured PIN - fail closed`() {
        assertFalse(LockPolicy.canEnableBiometric(false))
        assertTrue(LockPolicy.canEnableBiometric(true))
    }
}
