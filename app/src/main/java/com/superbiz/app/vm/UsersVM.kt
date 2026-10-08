package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.UserEntity
import com.superbiz.app.data.db.UserSecretEntity
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleGate
import com.superbiz.app.domain.rbac.SessionState
import com.superbiz.app.security.LockStatus
import com.superbiz.app.security.UserAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][H1-5][V 1.2.0] VM المستخدمين والجلسات — RBAC على الجهاز الواحد
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد التصميم: docs/RBAC_V13_DESIGN.md §2–§6 —
 *  • إدارة المستخدمين باب المالك وحده (RoleGate.require USERS_MANAGE على كل
 *    عملية كتابة — «الافتراض مغلق»).
 *  • فتح الجلسة: مصادقة ناجحة (رمز مستخدم أو بصمة مسموحة) تنشئ SessionIdentity
 *    تعيش حتى إعادة قفل التطبيق (MainActivity يستدعي endSession).
 *  • القفل التصاعدي لكل مستخدم على حدة (LockoutGuard بنطاق u<id>) وفشل الدخول
 *    يُنسب لاسمه في audit_log (§6-4).
 *  • حارس «لا يبقى جهاز بلا مالك»: لا تعطيل/حذف لآخر مالك فعّال — فشل مغلق.
 *  • بذرة المالك للأجهزة الجديدة (تركيب v13 نظيف): أول فتح جلسة يزرع المالك
 *    إن كان الجدول فارغاً وينقل مادة رمز الجهاز إن وجدت (نفس عقد ترحيل v13).
 */
class UsersVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    // ─── حالة الإدارة (شاشة المستخدمين — باب المالك) ───
    private val _users = MutableStateFlow<List<UserEntity>>(emptyList())
    val users: StateFlow<List<UserEntity>> = _users

    private val _activeUsers = MutableStateFlow<List<UserEntity>>(emptyList())
    val activeUsers: StateFlow<List<UserEntity>> = _activeUsers

    val busy = MutableStateFlow(false)
    val toast = MutableStateFlow<String?>(null)
    fun clearToast() { toast.value = null }

    // حالة قفل المحاولات للمستخدم المختار في شاشة القفل
    private val _lockout = MutableStateFlow(LockStatus(0, 0L, 0L))
    val lockout: StateFlow<LockStatus> = _lockout

    // حرّاس المحاولات لكل مستخدم — نطاق u<id> (كسول، خيط الإدارة نفسه)
    private val guards = HashMap<Long, com.superbiz.app.security.LockoutGuard>()
    private fun guardFor(userId: Long): com.superbiz.app.security.LockoutGuard =
        synchronized(guards) { guards.getOrPut(userId) { com.superbiz.app.security.LockoutGuard(getApplication(), "u$userId") } }

    // ─── التحميل ───

    /** تحديث قائمة الإدارة — يُستدعى من شاشة المستخدمين (المالك) فقط */
    fun refresh() = launchSafe {
        RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        ensureOwnerSeed()
        _users.value = g.db.users().all()
        _activeUsers.value = g.db.users().activeUsers()
    }

    /** تحديث المستخدمين الفعّالين لشاشة القفل — بلا بوابة (قراءة دورة الدخول) */
    fun refreshActiveUsers() = launchSafe {
        ensureOwnerSeed()
        _activeUsers.value = g.db.users().activeUsers()
    }

    fun refreshLockout(userId: Long) = launchSafe {
        _lockout.value = guardFor(userId).status()
    }

    // ─── قراءات لمرة واحدة لشاشة القفل (حالة عرض — القرارات الأمنية داخل UserAuth) ───

    /** معرفات من يملكون رمز دخول — يُعرض حقل الرمز لأصحابها فقط */
    suspend fun secretOwnersOnce(): Set<Long> = g.db.userSecrets().userIds().toSet()

    /** معرفات المسموح لهم بالبصمة (biometricAllowed=1 — المالك افتراضاً بالعقد) */
    suspend fun biometricAllowedOnce(): Set<Long> =
        g.db.userSecrets().userIds().mapNotNull { uid ->
            g.db.userSecrets().byUser(uid)?.takeIf { UserAuth.biometricAllowed(it) }?.userId
        }.toSet()

    // ─── مسار فتح الجلسة ───

    /**
     * تحقق رمز مستخدم محدد (وضع التعدد) — القفل التصاعدي على حدة وإسناد
     * التدقيق للاسم المستهدف سواء نجح الدخول أم فشل (عقد التصميم §6-4).
     */
    fun verifyUserPin(userId: Long, pin: String, onResult: (Boolean) -> Unit) = launchSafe {
        val st = guardFor(userId).status()
        if (st.isLocked(android.os.SystemClock.elapsedRealtime())) {
            _lockout.value = st
            onResult(false)
            return@launchSafe
        }
        val user = g.db.users().byId(userId)
        val secret = g.db.userSecrets().byUser(userId)
        // مستخدم بلا سر (لم يُنشأ له رمز) = فشل مغلق — لا فتح صامت أبداً
        val ok = user != null && user.active == 1 && secret != null && try {
            withContext(Dispatchers.Default) { UserAuth.verify(secret, pin) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
        if (ok) {
            guardFor(userId).onSuccess()
            openSession(user!!)
        } else {
            _lockout.value = guardFor(userId).onFailed()
            audit(
                actor = user?.name ?: "#$userId",
                actorId = userId,
                actorRole = user?.let { it.role },
                action = "USER_LOGIN_FAIL",
                details = "user=${user?.name ?: userId}"
            )
        }
        onResult(ok)
    }

    /**
     * فتح جلسة المالك للمسار الأحادي (التحقق بمرور عبر SettingsVM القائم
     * على مادة DataStore) — يزرع بذرة المالك للأجهزة الجديدة ثم يفتح الجلسة.
     * يُستدعى بعد نجاح التحقق فقط.
     */
    fun startSingleOwnerSession(onDone: () -> Unit) = launchSafe {
        ensureOwnerSeed()
        val owner = g.db.users().firstActiveOwner() ?: g.db.users().activeUsers().firstOrNull()
        if (owner != null) openSession(owner) else SessionState.clear()
        onDone()
    }

    /**
     * فتح جلسة بالبصمة لمستخدم مسموح له فقط (biometricAllowed — المالك
     * افتراضاً بالعقد) — تُستدعى بعد نجاح BiometricGate النظامية.
     */
    fun openBiometricSession(userId: Long, onDone: (Boolean) -> Unit) = launchSafe {
        val user = g.db.users().byId(userId)
        val secret = g.db.userSecrets().byUser(userId)
        val allowed = user != null && user.active == 1 && UserAuth.biometricAllowed(secret)
        if (allowed) {
            guardFor(userId).onSuccess()
            openSession(user!!)
        } else {
            audit(
                actor = user?.name ?: "#$userId",
                actorId = userId,
                actorRole = user?.role,
                action = "USER_LOGIN_FAIL",
                details = "biometric not allowed user=${user?.name ?: userId}"
            )
        }
        onDone(allowed)
    }

    /** إعادة قفل التطبيق — الهوية تموت مع القفل (عقد التصميم §1) */
    fun endSession() {
        SessionState.clear()
    }

    /** فتح الجلسة الفعلي — ختم الظهور + حدث تدقيق منسوب (داخل المستدعي بالفعل) */
    private suspend fun openSession(user: UserEntity) {
        g.db.users().touch(user.id, System.currentTimeMillis())
        SessionState.start(user.id, user.name, Role.fromId(user.role))
        audit(
            actor = user.name,
            actorId = user.id,
            actorRole = user.role,
            action = "USER_LOGIN_OK",
            details = "role=${user.role}"
        )
    }

    /**
     * بذرة المالك للأجهزة الجديدة (v13 نظيف بلا ترحيل) — نفس عقد ترحيل 12→13:
     * جدول فارغ → يُزرع «المالك»؛ ورمز الجهاز القائم (DataStore) يُنسخ نصاً
     * إلى user_secrets (نسخ مغلّف ks: لا إعادة تشفير). Idempotent حسب البنية.
     */
    private suspend fun ensureOwnerSeed() {
        if (g.db.users().count() > 0) return
        val s = g.settings.snapshot()
        val newId = g.db.users().insert(UserEntity(name = "المالك", role = 0, active = 1))
        if (!s.pinBlob.isNullOrBlank() && !s.pinSalt.isNullOrBlank()) {
            g.db.userSecrets().upsert(
                UserSecretEntity(
                    userId = newId,
                    pinWrapped = s.pinBlob!!,
                    pinSalt = s.pinSalt!!,
                    pinIters = s.pinIters,
                    biometricAllowed = 1
                )
            )
        }
    }

    // ─── إدارة المستخدمين — باب المالك وحده ───

    /**
     * إنشاء مستخدم جديد برمز دخول — المعاملة واحدة (صف المستخدم + صف السر)
     * ولا تُفتح إلا لمالك جالس (USERS_MANAGE). الحساب المكلف على Default
     * (PBKDF2 600k) كما في مسار setPin القائم.
     */
    fun addUser(name: String, role: Role, pin: String, biometricAllowed: Boolean = false) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            toast.value = getApplication<Application>().getString(R.string.users_err_name)
            return@launchSafe
        }
        if (pin.length !in 6..8 || pin.any { !it.isDigit() }) {
            toast.value = getApplication<Application>().getString(R.string.users_err_pin)
            return@launchSafe
        }
        busy.value = true
        try {
            // الترتيب المتعمد: إدراج المستخدم لنحصل على معرفه (مفتاح التغليف
            // alias به) ثم حساب السر المكلف خارج أي معاملة (PBKDF2 600k لا
            // يمسك قفل كتابة القاعدة) ثم إدراج السر. فجوة الفشل الوحيدة بين
            // الخطوات تترك مستخدماً بلا سر = غير قابل للدخول وقابل للحذف —
            // بلا حالة خطر إطلاقاً (فشل مغلق بنيوياً).
            val newId = g.db.users().insert(UserEntity(name = trimmed, role = role.id, active = 1))
            val created = withContext(Dispatchers.Default) { UserAuth.newSecret(newId, pin, biometricAllowed) }
            g.db.userSecrets().upsert(created)
            audit(
                actor = session.name,
                actorId = session.userId,
                actorRole = session.role.id,
                action = "USER_CREATE",
                details = "name=$trimmed role=${role.id} id=$newId"
            )
            refresh()
            toast.value = getApplication<Application>().getString(R.string.users_added)
        } finally {
            busy.value = false
        }
    }

    /** تعطيل/تفعيل — بلا حذف؛ آخر مالك فعّال محمي (فشل مغلق) */
    fun setActive(userId: Long, active: Boolean) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        val target = g.db.users().byId(userId) ?: return@launchSafe
        if (!active && target.role == 0) {
            val owners = g.db.users().all().count { it.role == 0 && it.active == 1 }
            if (owners <= 1) {
                toast.value = getApplication<Application>().getString(R.string.users_err_last_owner)
                return@launchSafe
            }
        }
        g.db.users().setActive(userId, if (active) 1 else 0)
        audit(
            actor = session.name,
            actorId = session.userId,
            actorRole = session.role.id,
            action = "USER_SET_ACTIVE",
            details = "id=$userId active=${if (active) 1 else 0}"
        )
        refresh()
    }

    /** حذف نهائي — CASCADE يمحو سره؛ آخر مالك محمي؛ غير الفعّال أو أي مستخدم يُحذف */
    fun deleteUser(userId: Long) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        val target = g.db.users().byId(userId) ?: return@launchSafe
        if (target.role == 0 && target.active == 1) {
            val owners = g.db.users().all().count { it.role == 0 && it.active == 1 }
            if (owners <= 1) {
                toast.value = getApplication<Application>().getString(R.string.users_err_last_owner)
                return@launchSafe
            }
        }
        if (SessionState.current?.userId == userId) {
            toast.value = getApplication<Application>().getString(R.string.users_err_self)
            return@launchSafe
        }
        g.db.users().delete(userId)
        audit(
            actor = session.name,
            actorId = session.userId,
            actorRole = session.role.id,
            action = "USER_DELETE",
            details = "id=$userId name=${target.name}"
        )
        refresh()
    }

    /** إعادة تعيين رمز مستخدم — نفس عقد الإنشاء (6..8 أرقام) */
    fun resetPin(userId: Long, pin: String) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        if (pin.length !in 6..8 || pin.any { !it.isDigit() }) {
            toast.value = getApplication<Application>().getString(R.string.users_err_pin)
            return@launchSafe
        }
        val target = g.db.users().byId(userId) ?: return@launchSafe
        busy.value = true
        try {
            withContext(Dispatchers.Default) {
                val old = g.db.userSecrets().byUser(userId)
                val s = UserAuth.newSecret(userId, pin, old?.biometricAllowed == 1)
                g.db.userSecrets().upsert(s)
            }
            guardFor(userId).onSuccess()   // رمز جديد يصفّر عدّاد مستهدفه
            audit(
                actor = session.name,
                actorId = session.userId,
                actorRole = session.role.id,
                action = "USER_RESET_PIN",
                details = "id=$userId name=${target.name}"
            )
            toast.value = getApplication<Application>().getString(R.string.users_pin_reset)
        } finally {
            busy.value = false
        }
    }

    /**
     * سماح البصمة — المالك وحده مستهدفاً في الموجة الأولى: بصمة الجهاز
     * مسجلة لأصابع صاحب الجهاز، فمنحها لغير المالك يفتح جلساته بإنشاء
     * لا يعرفه (قرار مغلق موثق — تصميم §4.1: «المالك فقط افتراضاً»).
     */
    fun setBiometricAllowed(userId: Long, allowed: Boolean) = launchSafe {
        val session = RoleGate.require(SessionState.effective(), Op.USERS_MANAGE)
        val target = g.db.users().byId(userId) ?: return@launchSafe
        if (target.role != 0) {
            toast.value = getApplication<Application>().getString(R.string.users_err_bio_owner_only)
            return@launchSafe
        }
        val old = g.db.userSecrets().byUser(userId)
        g.db.userSecrets().upsert(
            UserSecretEntity(
                userId = userId,
                pinWrapped = old?.pinWrapped ?: return@launchSafe,
                pinSalt = old!!.pinSalt,
                pinIters = old.pinIters,
                biometricAllowed = if (allowed) 1 else 0
            )
        )
        audit(
            actor = session.name,
            actorId = session.userId,
            actorRole = session.role.id,
            action = "USER_BIO_FLAG",
            details = "id=$userId allowed=${if (allowed) 1 else 0}"
        )
        refresh()
    }

    // ─── أدوات ───

    /** إسناد موحّد لسجل التدقيق — الجلسة الصريحة تحمل هويتها، وغيرها «نظام» */
    private suspend fun audit(
        actor: String,
        actorId: Long?,
        actorRole: Int?,
        action: String,
        details: String
    ) {
        g.db.auditLog().insert(
            com.superbiz.app.data.db.AuditLogEntity(
                actor = actor,
                action = action,
                details = details,
                actorId = actorId,
                actorRole = actorRole
            )
        )
    }
}
