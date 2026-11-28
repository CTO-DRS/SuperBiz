package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.RolePermissionEntity
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleGate
import com.superbiz.app.domain.rbac.RolePermissionCache
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.flow.MutableStateFlow

/** [D2] محرر مصفوفة الصلاحيات: المالك وحده (USERS_MANAGE)، لا يُعدَّل دور المالك، وكل تعديل يُدوَّن في audit_log */
class RolePermsVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    /** يزيد عند كل تعديل ليعيد الشاشة قراءة RoleGate */
    val version = MutableStateFlow(0)

    init {
        launchSafe {
            RolePermissionCache.replaceAll(g.db.rolePermissions().all())
            version.value++
        }
    }

    fun allowed(role: Role, op: Op): Boolean = RoleGate.allows(role, op)

    fun toggle(role: Role, op: Op) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        if (role == Role.OWNER) return@launchSafe
        val next = !RoleGate.allows(role, op)
        g.db.rolePermissions().upsert(RolePermissionEntity(role.id, op.name, next))
        RolePermissionCache.replaceAll(g.db.rolePermissions().all())
        g.db.auditLog().insert(
            AuditLogEntity(
                actor = session.name, action = "ROLE_PERMISSION_SET",
                details = "${role.name}:${op.name}=$next",
                actorId = session.userId.takeIf { it != 0L }, actorRole = session.role.id
            )
        )
        version.value++
    }

    /** [Phase 1.3] تطبيق قالب جاهز على دور غير المالك: صفوف role_permissions كلها دفعة واحدة وسطر تدقيق واحد */
    fun applyTemplate(role: Role, template: com.superbiz.app.domain.rbac.RoleTemplates.Template) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        if (role == Role.OWNER) return@launchSafe
        com.superbiz.app.domain.rbac.RoleTemplates.plan(role, template).forEach { (op, allowed) ->
            g.db.rolePermissions().upsert(RolePermissionEntity(role.id, op.name, allowed))
        }
        RolePermissionCache.replaceAll(g.db.rolePermissions().all())
        g.db.auditLog().insert(
            AuditLogEntity(
                actor = session.name, action = "ROLE_TEMPLATE_APPLIED",
                details = "${role.name}:${template.name}",
                actorId = session.userId.takeIf { it != 0L }, actorRole = session.role.id
            )
        )
        version.value++
    }
}
