package com.superbiz.app.rbac

import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.SessionIdentity
import com.superbiz.app.domain.rbac.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-5 V 3.2.2] وضع التعدد وهوية النظام — جدار عقد SessionState الموسع
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * يثبّت:
 *  1) الوضع الأحادي (الافتراض) يبقى على عقد IMPLICIT_OWNER الحرفي (تصميم §4.3)
 *     — سلوك v13 كله بلا مسّ.
 *  2) وضع التعدد بلا جلسة صريحة: الهوية الفعالة SYSTEM (إسناد "system" صادق
 *     في audit_log بدل «owner» المزيف) والدور OWNER كي تبقى الأتمتة الخلفية
 *     المشروعة تعمل أثناء القفل (عقد الأتمتة الموثق — الإغلاق الإنفاذي الكامل
 *     قرار تصميم منفصل).
 *  3) الجلسة الصريحة تحسم دائماً فوق أي وضع.
 *  4) إسناد auditAttribution يبقى NULL بلا جلسة صريحة في الوضعين — عقد
 *     «NULL = حدث نظام/قبل التبني» محفوظ حرفياً.
 */
class SessionStateModeTest {

    @Before
    fun reset() {
        SessionState.clear()
        SessionState.multiUserMode = false
    }

    @After
    fun cleanup() {
        SessionState.clear()
        SessionState.multiUserMode = false
    }

    @Test
    fun `single-owner mode keeps the historic implicit owner contract`() {
        val eff = SessionState.effective()
        assertSame(SessionState.IMPLICIT_OWNER, eff)
        assertEquals("owner", eff.name)
        assertEquals(Role.OWNER, eff.role)
    }

    @Test
    fun `multi-user mode without session resolves to system identity`() {
        SessionState.multiUserMode = true
        val eff = SessionState.effective()
        assertSame(SessionState.SYSTEM, eff)
        assertEquals("system", eff.name)
        // عقد الأتمتة: الكتابات الخلفية المشروعة تبقى ممكنة أثناء القفل
        assertEquals(Role.OWNER, eff.role)
        // صادق التمييز: ليس «owner» المزيف
        assertNotEquals(SessionState.IMPLICIT_OWNER, eff)
    }

    @Test
    fun `explicit session always wins over mode identity`() {
        SessionState.multiUserMode = true
        val s = SessionIdentity(7L, "أمين الصندوق", Role.CASHIER)
        SessionState.start(s.userId, s.name, s.role)
        // start ينشئ نسخة متساوية لا تحمل نفس المرجع — المساواة البنيوية هي العقد
        assertEquals(s, SessionState.effective())
        assertEquals("أمين الصندوق", SessionState.effective().name)
        assertEquals(Role.CASHIER, SessionState.effective().role)
    }

    @Test
    fun `audit attribution stays null without explicit session in both modes`() {
        assertNull(SessionState.auditAttribution().first)
        assertNull(SessionState.auditAttribution().second)
        SessionState.multiUserMode = true
        assertNull(SessionState.auditAttribution().first)
        assertNull(SessionState.auditAttribution().second)
        // مع جلسة صريحة يعود الإسناد الكامل كما كان
        SessionState.start(3L, "مدير", Role.MANAGER)
        assertEquals(3L, SessionState.auditAttribution().first)
        assertEquals(Role.MANAGER.id, SessionState.auditAttribution().second)
    }

    @Test
    fun `clear returns to mode identity not owner on multi-user devices`() {
        SessionState.start(1L, "المالك", Role.OWNER)
        SessionState.multiUserMode = true
        SessionState.clear()
        // إعادة القفل في وضع التعدد: لا «owner» ضمني — هوية النظام
        assertEquals("system", SessionState.effective().name)
    }
}
