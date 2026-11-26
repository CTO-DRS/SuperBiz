package com.superbiz.app.rbac

import com.superbiz.app.data.db.RolePermissionEntity
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleGate
import com.superbiz.app.domain.rbac.RolePermissionCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePermissionOverrideTest {
    @After fun clear() = RolePermissionCache.clear()

    @Test fun overrideReplacesDefaultAndOwnerIsNeverLocked() {
        assertFalse(RoleGate.allows(Role.CASHIER, Op.EXPENSES_CHECKS_INSTALLMENTS))
        RolePermissionCache.replaceAll(listOf(
            RolePermissionEntity(Role.CASHIER.id, Op.EXPENSES_CHECKS_INSTALLMENTS.name, true),
            RolePermissionEntity(Role.MANAGER.id, Op.POS_SELL.name, false),
            RolePermissionEntity(Role.OWNER.id, Op.POS_SELL.name, false)
        ))
        assertTrue(RoleGate.allows(Role.CASHIER, Op.EXPENSES_CHECKS_INSTALLMENTS))
        assertFalse(RoleGate.allows(Role.MANAGER, Op.POS_SELL))
        assertTrue(RoleGate.allows(Role.OWNER, Op.POS_SELL))
    }

    @Test fun defaultsStayTheSeed() {
        Role.entries.forEach { r -> Op.entries.forEach { o -> assertEquals(RoleGate.defaultAllows(r, o), RoleGate.allows(r, o)) } }
    }
}
