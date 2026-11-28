package com.superbiz.app

import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleGate
import com.superbiz.app.domain.rbac.RoleTemplates
import com.superbiz.app.domain.rbac.RoleTemplates.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleTemplatesTest {
    private val nonOwner = listOf(Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER)

    @Test fun ownerIsNeverTouched() {
        Template.entries.forEach { assertTrue(RoleTemplates.plan(Role.OWNER, it).isEmpty()) }
    }

    @Test fun presetsNeverGrantOwnerOnlyOps() {
        for (r in nonOwner) for (t in listOf(Template.SALES_ONLY, Template.FINANCE_VIEW)) {
            val p = RoleTemplates.plan(r, t)
            RoleTemplates.OWNER_ONLY.forEach { assertFalse("$r $t $it", p[it] == true) }
            assertEquals(Op.entries.size, p.size)
        }
    }

    @Test fun defaultMatchesCodeMatrix() {
        for (r in nonOwner) {
            val p = RoleTemplates.plan(r, Template.DEFAULT)
            Op.entries.forEach { assertEquals("$r $it", RoleGate.defaultAllows(r, it), p[it]) }
        }
    }

    @Test fun salesOnlyShape() {
        val p = RoleTemplates.plan(Role.MANAGER, Template.SALES_ONLY)
        assertTrue(p[Op.POS_SELL] == true)
        assertFalse(p[Op.FINANCIAL_REPORTS] == true)
        assertFalse(p[Op.EXPENSES_CHECKS_INSTALLMENTS] == true)
    }
}
