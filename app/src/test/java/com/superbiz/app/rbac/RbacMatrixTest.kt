package com.superbiz.app.rbac

import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.RoleGate
import com.superbiz.app.domain.rbac.SessionIdentity
import com.superbiz.app.domain.rbac.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][V 1.2.0] اختبار المصفوفة الشامل — كل خلية من خلايا تصميم §3 بالاسم
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (RBAC_V13_DESIGN §6-3): 4 أدوار × 17 عملية = 68 حالة موثقة
 * بالاسم، وقاعدة «الافتراض مغلق» تُثبت عكسياً: أي تعديل على المصفوفة بلا
 * تعديل هذا الاختبار يكسر البناء — المصفوفة والاختبار عقد واحد.
 * JVM خالص بلا Robolectric — ينجري في ثوانٍ.
 */
class RbacMatrixTest {

    // ─── المصفوفة الحرفية من التصميم §3 — المصدر الوحيد للحقيقة في هذا الملف ───

    private val expectedAllows: Map<Op, Set<Role>> = mapOf(
        // سطر 1: نقطة البيع وإنشاء فاتورة — المحاسب لا يبيع
        Op.POS_SELL to setOf(Role.OWNER, Role.MANAGER, Role.CASHIER),
        // سطر 2: طباعة/QR/مشاركة فاتورة قائمة — مفتوحة للجميع
        Op.INVOICE_SHARE to setOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER),
        // سطر 3: إلغاء فاتورة — بلا الكاشير
        Op.INVOICE_CANCEL to setOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT),
        // سطر 4: تعديل بيانات طرف/منتج — بلا المحاسب والكاشير
        Op.PARTY_PRODUCT_EDIT to setOf(Role.OWNER, Role.MANAGER),
        // سطر 5: حذف نهائي — المالك وحده
        Op.HARD_DELETE to setOf(Role.OWNER),
        // سطر 6-أ: اطلاع المخزون والجرد — الكاشير قراءة فقط، المحاسب محروم
        Op.INVENTORY_READ to setOf(Role.OWNER, Role.MANAGER, Role.CASHIER),
        // سطر 6-ب: تعديل المخزون والجرد — المالك والمدير
        Op.INVENTORY_MANAGE to setOf(Role.OWNER, Role.MANAGER),
        // سطر 7: التقارير المالية الكاملة + KPI — بلا الكاشير
        Op.FINANCIAL_REPORTS to setOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT),
        // سطر 8-أ: اطلاع الإقرار الضريبي — المالك والمحاسب (قراءة فقط للمحاسب)
        Op.TAX_RETURN_VIEW to setOf(Role.OWNER, Role.ACCOUNTANT),
        // سطر 8-ب: بيانات المالك الضريبية — المالك وحده
        Op.TAX_OWNER_DATA to setOf(Role.OWNER),
        // سطر 9: مصروفات/شيكات/أقساط — بلا الكاشير
        Op.EXPENSES_CHECKS_INSTALLMENTS to setOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT),
        // سطر 10: الإعدادات العامة — المالك والمدير
        Op.GENERAL_SETTINGS to setOf(Role.OWNER, Role.MANAGER),
        // سطر 11: إدارة المستخدمين — المالك وحده
        Op.USERS_MANAGE to setOf(Role.OWNER),
        // سطر 12: النسخ الاحتياطي/الاستعادة — المالك وحده
        Op.BACKUP_RESTORE to setOf(Role.OWNER),
        // سطر 13: تفعيل Pro/شراء/استعادة — المالك وحده
        Op.PRO_MANAGE to setOf(Role.OWNER),
        // سطر 14-أ: تطبيق الولاء على بيع قائم — بلا المحاسب (الكاشير تطبيق فقط)
        Op.LOYALTY_APPLY to setOf(Role.OWNER, Role.MANAGER, Role.CASHIER),
        // سطر 14-ب: إنشاء قواعد الولاء والكوبونات — المالك والمدير
        Op.LOYALTY_MANAGE to setOf(Role.OWNER, Role.MANAGER)
    )

    // ═══ المصفوفة كاملة: كل خلية (4 × 17 = 68) بالاسم ═══

    @Test
    fun `matrix row 1 — POS sell allows owner manager cashier denies accountant`() {
        assertEquals(expectedAllows[Op.POS_SELL], allowedRolesFor(Op.POS_SELL))
    }

    @Test
    fun `matrix row 2 — invoice share print is open to all four roles`() {
        assertEquals(expectedAllows[Op.INVOICE_SHARE], allowedRolesFor(Op.INVOICE_SHARE))
    }

    @Test
    fun `matrix row 3 — invoice cancel denies cashier`() {
        assertEquals(expectedAllows[Op.INVOICE_CANCEL], allowedRolesFor(Op.INVOICE_CANCEL))
    }

    @Test
    fun `matrix row 4 — party product edit is owner manager only`() {
        assertEquals(expectedAllows[Op.PARTY_PRODUCT_EDIT], allowedRolesFor(Op.PARTY_PRODUCT_EDIT))
    }

    @Test
    fun `matrix row 5 — hard delete is owner only`() {
        assertEquals(expectedAllows[Op.HARD_DELETE], allowedRolesFor(Op.HARD_DELETE))
    }

    @Test
    fun `matrix row 6a — inventory read denies accountant allows cashier readonly`() {
        assertEquals(expectedAllows[Op.INVENTORY_READ], allowedRolesFor(Op.INVENTORY_READ))
    }

    @Test
    fun `matrix row 6b — inventory manage is owner manager only`() {
        assertEquals(expectedAllows[Op.INVENTORY_MANAGE], allowedRolesFor(Op.INVENTORY_MANAGE))
    }

    @Test
    fun `matrix row 7 — full financial reports and KPI deny cashier`() {
        assertEquals(expectedAllows[Op.FINANCIAL_REPORTS], allowedRolesFor(Op.FINANCIAL_REPORTS))
    }

    @Test
    fun `matrix row 8a — tax return view allows owner and accountant`() {
        assertEquals(expectedAllows[Op.TAX_RETURN_VIEW], allowedRolesFor(Op.TAX_RETURN_VIEW))
    }

    @Test
    fun `matrix row 8b — ZATCA owner data is owner only`() {
        assertEquals(expectedAllows[Op.TAX_OWNER_DATA], allowedRolesFor(Op.TAX_OWNER_DATA))
    }

    @Test
    fun `matrix row 9 — expenses checks installments deny cashier`() {
        assertEquals(expectedAllows[Op.EXPENSES_CHECKS_INSTALLMENTS], allowedRolesFor(Op.EXPENSES_CHECKS_INSTALLMENTS))
    }

    @Test
    fun `matrix row 10 — general settings is owner manager only`() {
        assertEquals(expectedAllows[Op.GENERAL_SETTINGS], allowedRolesFor(Op.GENERAL_SETTINGS))
    }

    @Test
    fun `matrix row 11 — users management is owner only`() {
        assertEquals(expectedAllows[Op.USERS_MANAGE], allowedRolesFor(Op.USERS_MANAGE))
    }

    @Test
    fun `matrix row 12 — backup restore is owner only`() {
        assertEquals(expectedAllows[Op.BACKUP_RESTORE], allowedRolesFor(Op.BACKUP_RESTORE))
    }

    @Test
    fun `matrix row 13 — Pro purchase restore is owner only`() {
        assertEquals(expectedAllows[Op.PRO_MANAGE], allowedRolesFor(Op.PRO_MANAGE))
    }

    @Test
    fun `matrix row 14a — loyalty apply denies accountant allows cashier`() {
        assertEquals(expectedAllows[Op.LOYALTY_APPLY], allowedRolesFor(Op.LOYALTY_APPLY))
    }

    @Test
    fun `matrix row 14b — loyalty rule management is owner manager only`() {
        assertEquals(expectedAllows[Op.LOYALTY_MANAGE], allowedRolesFor(Op.LOYALTY_MANAGE))
    }

    // ═══ قاعدة الحسم وخصائصها ═══

    @Test
    fun `coverage — the expected map covers every Op exactly once`() {
        assertEquals(Op.entries.toSet(), expectedAllows.keys)
        assertEquals(Op.entries.size, expectedAllows.size)
    }

    @Test
    fun `default-closed — owner is the only role allowed everywhere`() {
        // المالك يمر على كل العمليات — لا خلية محرمة عليه في الموجة الأولى
        Op.entries.forEach { op ->
            assertTrue("owner must pass $op", RoleGate.allows(Role.OWNER, op))
        }
    }

    @Test
    fun `default-closed — cashier is the most restricted role`() {
        // الكاشير محروم من كل عملية لا تخدم وظيفته — عقد التصميم §2
        val cashierDenied = Op.entries.filter { !RoleGate.allows(Role.CASHIER, it) }
        assertEquals(
            setOf(
                Op.INVOICE_CANCEL, Op.PARTY_PRODUCT_EDIT, Op.HARD_DELETE,
                Op.INVENTORY_MANAGE, Op.FINANCIAL_REPORTS, Op.TAX_RETURN_VIEW,
                Op.TAX_OWNER_DATA, Op.EXPENSES_CHECKS_INSTALLMENTS,
                Op.GENERAL_SETTINGS, Op.USERS_MANAGE, Op.BACKUP_RESTORE, Op.PRO_MANAGE,
                Op.LOYALTY_MANAGE
            ),
            cashierDenied.toSet()
        )
    }

    @Test
    fun `role ids match the v13 schema contract literally`() {
        assertEquals(0, Role.OWNER.id)
        assertEquals(1, Role.MANAGER.id)
        assertEquals(2, Role.ACCOUNTANT.id)
        assertEquals(3, Role.CASHIER.id)
    }

    @Test
    fun `role fromId fails closed on unknown values`() {
        assertEquals(Role.CASHIER, Role.fromId(99))
        assertEquals(Role.CASHIER, Role.fromId(-1))
        assertEquals(Role.OWNER, Role.fromId(0))
        assertEquals(Role.CASHIER, Role.fromId(3))
    }

    // ═══ نقطة التحقق require والجلسة ═══

    @Test
    fun `require throws RoleDeniedException with op and role for denied cell`() {
        val cashier = SessionIdentity(7L, "سالم", Role.CASHIER)
        val ex = assertThrows(RoleDeniedException::class.java) {
            RoleGate.require(cashier, Op.FINANCIAL_REPORTS)
        }
        assertEquals(Op.FINANCIAL_REPORTS, ex.op)
        assertEquals(Role.CASHIER, ex.role)
    }

    @Test
    fun `require returns the session identity when allowed`() {
        val manager = SessionIdentity(3L, "ناصر", Role.MANAGER)
        assertEquals(manager, RoleGate.require(manager, Op.GENERAL_SETTINGS))
    }

    @Test
    fun `require with null session falls back to implicit owner (single-owner mode)`() {
        // عقد التصميم §4.3: وضع المالك الواحد الضمني يحفظ اختبارات v12 وسلوك
        // الأجهزة أحادية المالك — القفل الحقيقي هو PIN/بصمة الجهاز
        val s = RoleGate.require(null, Op.USERS_MANAGE)
        assertEquals(Role.OWNER, s.role)
        assertEquals(0L, s.userId)
    }

    @Test
    fun `session identity can() mirrors the matrix`() {
        assertFalse(SessionIdentity(1L, "ك", Role.CASHIER).can(Op.INVOICE_CANCEL))
        assertTrue(SessionIdentity(1L, "ك", Role.CASHIER).can(Op.POS_SELL))
        assertTrue(SessionIdentity(2L, "م", Role.ACCOUNTANT).can(Op.FINANCIAL_REPORTS))
        assertFalse(SessionIdentity(2L, "م", Role.ACCOUNTANT).can(Op.POS_SELL))
    }

    @Test
    fun `session state — start effective and clear lifecycle`() {
        try {
            assertFalse(SessionState.hasExplicitSession)
            SessionState.start(9L, "خالد", Role.MANAGER)
            assertTrue(SessionState.hasExplicitSession)
            assertEquals(Role.MANAGER, SessionState.effective().role)
            assertEquals(9L, SessionState.effective().userId)
            SessionState.clear()
            assertFalse(SessionState.hasExplicitSession)
            // بعد المحو يعود المالك الضمني — لا جلسة معلقة
            assertEquals(Role.OWNER, SessionState.effective().role)
        } finally {
            SessionState.clear()
        }
    }

    @Test
    fun `audit attribution — explicit session carries id and role snapshot`() {
        try {
            SessionState.start(5L, "هند", Role.ACCOUNTANT)
            val (id, roleId) = SessionState.auditAttribution()
            assertEquals(5L, id)
            assertEquals(2, roleId)
            SessionState.clear()
            val (nullId, nullRole) = SessionState.auditAttribution()
            assertEquals(null, nullId)
            assertEquals(null, nullRole)
        } finally {
            SessionState.clear()
        }
    }

    // ─── أدوات ───

    /** الخلايا المسموحة فعلاً لمحرك المصفوفة — تُقارن بالتصميم حرفياً */
    private fun allowedRolesFor(op: Op): Set<Role> =
        Role.entries.filterTo(mutableSetOf()) { RoleGate.allows(it, op) }
}
