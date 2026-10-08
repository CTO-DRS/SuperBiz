package com.superbiz.app.domain.rbac

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][V 1.2.0] محرك الأدوار النقي — RBAC بأربعة أدوار ثابتة بلا صلاحيات حرة
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * العقد التصميمي: docs/RBAC_V13_DESIGN.md §2–§3 — وحدة JVM خالصة بلا أي اعتماد
 * Android، تُختبر مصفوفتها بالكامل (4 أدوار × 17 عملية = 68 خلية موثقة بالاسم
 * في RbacMatrixTest) على نمط بقية محركات domain النقية (FinMath/LineTaxP41).
 *
 * ثوابت الأدوار مطابقة لعمود `users.role` في مخطط v13 حرفياً:
 *   0 = OWNER (المالك) · 1 = MANAGER (المدير) · 2 = ACCOUNTANT (المحاسب) · 3 = CASHIER (الكاشير)
 *
 * ─── قاعدة الحسم («الافتراض مغلق») ────────────────────────────────────────
 * أي عملية غير ممنوحة صراحةً في مصفوفة `allows` محرَّمة افتراضياً — باب جديد
 * يُبنى لاحقاً يبدأ مقفولاً على غير المالك والمدير حتى يُفتح بقرار موثق يعدّل
 * المصفوفة واختبارها معاً (نمط ProEntitlement نفسه).
 */

/** الأدوار الأربعة الثابتة — المعرف الرقمي هو ما يُخزَّن في عمود users.role */
enum class Role(val id: Int) {
    OWNER(0),
    MANAGER(1),
    ACCOUNTANT(2),
    CASHIER(3);

    companion object {
        /** قراءة الدور من المخطط — أي قيمة غريبة تعيد الأدنى صلاحية (فشل مغلق) */
        fun fromId(id: Int): Role = entries.firstOrNull { it.id == id } ?: CASHIER
    }
}

/**
 * العمليات المحمية — ثوابت مسماة من مصفوفة التصميم §3 (14 سطراً تتفرع إلى
 * 17 عملية لأن أسطر «قراءة فقط/تطبيق فقط» تُنمذج عمليات مستقلة كي تبقى كل
 * خلية قابلة للحسم البايتي في الاختبار).
 */
enum class Op {
    /** نقطة البيع وإنشاء فاتورة — المحاسب لا يبيع */
    POS_SELL,
    /** طباعة/QR/مشاركة فاتورة قائمة — مفتوحة للجميع */
    INVOICE_SHARE,
    /** إلغاء فاتورة (تحويلها ملغاة) — الكاشير لا يلغي */
    INVOICE_CANCEL,
    /** تعديل بيانات طرف/منتج قائم — الإنشاء الجديد بلا بوابة */
    PARTY_PRODUCT_EDIT,
    /** حذف نهائي لمنتج/طرف — المالك وحده */
    HARD_DELETE,
    /** اطلاع على المخزون والجرد — الكاشير قراءة فقط، والمحاسب محروم وفق المصفوفة */
    INVENTORY_READ,
    /** تعديل المخزون/الجرد/حدود الطلب — المالك والمدير */
    INVENTORY_MANAGE,
    /** التقارير المالية الكاملة (ربح/كلفة) + لوحة KPI — بلا الكاشير */
    FINANCIAL_REPORTS,
    /** اطلاع على الإقرار الضريبي — المالك والمحاسب (قراءة الإقرار فقط) */
    TAX_RETURN_VIEW,
    /** بيانات المالك الضريبية وإعدادات ZATCA الرسمية — المالك وحده */
    TAX_OWNER_DATA,
    /** المصروفات والشيكات والأقساط — بلا الكاشير */
    EXPENSES_CHECKS_INSTALLMENTS,
    /** الإعدادات العامة (ضريبة/عملة/طابعات/أتمتة) — بلا المحاسب والكاشير */
    GENERAL_SETTINGS,
    /** إدارة المستخدمين والأدوار — المالك وحده */
    USERS_MANAGE,
    /** النسخ الاحتياطي/الاستعادة — المالك وحده */
    BACKUP_RESTORE,
    /** تفعيل Pro / شراء / استعادة مشتريات — المالك وحده */
    PRO_MANAGE,
    /** تطبيق الولاء والكوبونات على بيع قائم — الكاشير يطبق فقط */
    LOYALTY_APPLY,
    /** إنشاء/تعديل قواعد الولاء والكوبونات — المالك والمدير */
    LOYALTY_MANAGE
}

/** استثناء الرفض — يُرمى من [RoleGate.require] ويُلتقط في launchSafe فيتحول لإشعار مفهوم */
class RoleDeniedException(val op: Op, val role: Role) :
    RuntimeException("RBAC: role ${role.name} denied op ${op.name}")

/** هوية الجلسة الحية — تُنشأ عند فتح القفل وتُمحى عند إعادة قفل التطبيق */
data class SessionIdentity(
    val userId: Long,
    val name: String,
    val role: Role
) {
    /** يملك هذه العملية؟ — اختصار قراءة فوق المصفوفة */
    fun can(op: Op): Boolean = RoleGate.allows(role, op)
}

/**
 * بوابة الدور الوحيدة — كل نقطة تحقق في التطبيق تمر من هنا (عقد التصميم §3:
 * نقطة واحدة `require` لا بوابات متناثرة)، والبوابات تُزرع في VMs لا في
 * Composables كي تبقى قابلة للاختبار JVM بلا جهاز.
 */
object RoleGate {

    /**
     * المصفوفة الحاكمة — أسمحُ صراحةً أم أرفض؟ كل خلية من خلايا التصميم §3
     * هنا، وأي عملية غير مذكورة = رفض (الافتراض مغلق لا استثناء له).
     */
    fun allows(role: Role, op: Op): Boolean = when (op) {
        // نقطة البيع وإنشاء فاتورة — المحاسب لا يبيع (تصميم §3 سطر 1)
        Op.POS_SELL -> role == Role.OWNER || role == Role.MANAGER || role == Role.CASHIER
        // طباعة/QR/مشاركة فاتورة قائمة — مفتوحة للجميع (سطر 2)
        Op.INVOICE_SHARE -> true
        // إلغاء فاتورة — بلا الكاشير (سطر 3)
        Op.INVOICE_CANCEL -> role != Role.CASHIER
        // تعديل طرف/منتج — بلا المحاسب والكاشير (سطر 4)
        Op.PARTY_PRODUCT_EDIT -> role == Role.OWNER || role == Role.MANAGER
        // حذف نهائي — المالك وحده (سطر 5)
        Op.HARD_DELETE -> role == Role.OWNER
        // اطلاع المخزون — المحاسب محروم والكاشير قراءة فقط (سطر 6)
        Op.INVENTORY_READ -> role != Role.ACCOUNTANT
        // تعديل المخزون والجرد — المالك والمدير (سطر 6 نفسه)
        Op.INVENTORY_MANAGE -> role == Role.OWNER || role == Role.MANAGER
        // التقارير المالية الكاملة + KPI — بلا الكاشير (سطر 7)
        Op.FINANCIAL_REPORTS -> role != Role.CASHIER
        // اطلاع الإقرار الضريبي — المالك والمحاسب (سطر 8)
        Op.TAX_RETURN_VIEW -> role == Role.OWNER || role == Role.ACCOUNTANT
        // بيانات المالك الضريبية — المالك وحده (سطر 8)
        Op.TAX_OWNER_DATA -> role == Role.OWNER
        // مصروفات/شيكات/أقساط — بلا الكاشير (سطر 9)
        Op.EXPENSES_CHECKS_INSTALLMENTS -> role != Role.CASHIER
        // الإعدادات العامة — المالك والمدير (سطر 10)
        Op.GENERAL_SETTINGS -> role == Role.OWNER || role == Role.MANAGER
        // إدارة المستخدمين — المالك وحده (سطر 11)
        Op.USERS_MANAGE -> role == Role.OWNER
        // النسخ الاحتياطي/الاستعادة — المالك وحده (سطر 12)
        Op.BACKUP_RESTORE -> role == Role.OWNER
        // تفعيل Pro/شراء/استعادة — المالك وحده (سطر 13)
        Op.PRO_MANAGE -> role == Role.OWNER
        // تطبيق الولاء على بيع قائم — بلا المحاسب (سطر 14: الكاشير تطبيق فقط)
        Op.LOYALTY_APPLY -> role != Role.ACCOUNTANT
        // إنشاء قواعد الولاء والكوبونات — المالك والمدير (سطر 14)
        Op.LOYALTY_MANAGE -> role == Role.OWNER || role == Role.MANAGER
    }

    /**
     * نقطة التحقق الإلزامية — ترمي [RoleDeniedException] عند الرفض.
     * الجلسة null = «وضع المالك الواحد الضمني»: قفل الجهاز (PIN/بصمة) هو
     * الحاجز الفعلي كما قبل v13، والجلسة الصريحة تُنشأ عند فتح القفل. هذا
     * يحفظ عقد التصميم §4.3 (اختبارات v12 القائمة تبقى خضراء بلا تعديل)
     * ويثبّت سلوك الأجهزة أحادية المالك حرفياً — التعدد الفعلي يبدأ لحظة
     * إنشاء المالك مستخدماً ثانياً، فيصبح وجود الجلسة الصريحة شرطاً.
     */
    fun require(session: SessionIdentity?, op: Op): SessionIdentity {
        val s = session ?: SessionState.IMPLICIT_OWNER
        if (!allows(s.role, op)) throw RoleDeniedException(op, s.role)
        return s
    }
}

/**
 * حالة الجلسة الحية — كائن عملي بسيط (عملية واحدة على الجهاز؛ لا حاجة
 * لقنوات ولا Flow هنا: القارئ الوحيد هو RoleGate في مسار الطلب نفسه).
 * تُملأ من UsersVM عند فتح القفل وتُمحى عند إعادة قفل التطبيق (MainActivity).
 */
object SessionState {

    /** هوية المالك الضمنية لوضع الجهاز الأحادي قبل إنشاء جلسة صريحة */
    val IMPLICIT_OWNER = SessionIdentity(0L, "owner", Role.OWNER)

    @Volatile
    var current: SessionIdentity? = null
        private set

    /** الهوية الفعالة — الجلسة الصريحة أو المالك الضمني (عقد RoleGate أعلاه) */
    fun effective(): SessionIdentity = current ?: IMPLICIT_OWNER

    /** هل توجد جلسة مستخدم صريحة (وضع متعدد المستخدمين نشط)؟ */
    val hasExplicitSession: Boolean get() = current != null

    /** فتح جلسة بعد مصادقة ناجحة — يُستدعى من UsersVM حصراً */
    fun start(userId: Long, name: String, role: Role) {
        current = SessionIdentity(userId, name, role)
    }

    /** إعادة قفل التطبيق — الهوية تموت مع القفل (عقد التصميم §1) */
    fun clear() {
        current = null
    }

    /** نسخة إسناد التدقيق — actorId/actorRole لصف audit_log (NULL قبل التبني) */
    fun auditAttribution(): Pair<Long?, Int?> =
        current?.let { it.userId to it.role.id } ?: (null to null)
}
