package com.superbiz.app.domain.rbac

/**
 * [Phase 1.3] قوالب أدوار جاهزة تُطبَّق على دور غير المالك دفعة واحدة. نقي (JVM).
 * القاعدة الأمنية: لا قالب يمنح عملية «مالك فقط» (حذف نهائي، مستخدمون، نسخ احتياطي، Pro، بيانات ضريبة المالك، اعتماد التحويل).
 * DEFAULT = رجوع إلى مصفوفة الكود الافتراضية للدور نفسه.
 */
object RoleTemplates {
    enum class Template { DEFAULT, SALES_ONLY, FINANCE_VIEW }

    /** عمليات لا يمنحها أي قالب لغير المالك */
    val OWNER_ONLY: Set<Op> = setOf(
        Op.HARD_DELETE, Op.USERS_MANAGE, Op.BACKUP_RESTORE, Op.PRO_MANAGE, Op.TAX_OWNER_DATA, Op.TRANSFER_APPROVE
    )

    private val SALES = setOf(Op.POS_SELL, Op.INVOICE_SHARE, Op.COLLECTION, Op.LOYALTY_APPLY, Op.INVENTORY_READ)
    private val FINANCE = setOf(Op.FINANCIAL_REPORTS, Op.TAX_RETURN_VIEW, Op.INVOICE_SHARE, Op.INVENTORY_READ)

    /** القيمة المطلوبة لكل عملية بعد تطبيق القالب على [role]؛ المالك لا يُمَسّ (خريطة فارغة). */
    fun plan(role: Role, template: Template): Map<Op, Boolean> {
        if (role == Role.OWNER) return emptyMap()
        return Op.entries.associateWith { op ->
            when (template) {
                Template.DEFAULT -> RoleGate.defaultAllows(role, op)
                Template.SALES_ONLY -> op in SALES
                Template.FINANCE_VIEW -> op in FINANCE
            }
        }
    }
}
