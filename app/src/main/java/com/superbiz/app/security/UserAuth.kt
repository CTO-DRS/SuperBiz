package com.superbiz.app.security

import com.superbiz.app.data.db.UserSecretEntity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][H1-5][v13] مصادقة المستخدم — تعميم وحدة PIN الواحدة إلى N مستخدم
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد التصميم (RBAC_V13_DESIGN §1/§4.1): لا طبقة أمنية جديدة — نفس PinManager
 * (PBKDF2-HMAC-SHA256 بملح 128بت و600k دورة ومقارنة زمنية ثابتة) و نفس
 * PinVault (AES-256-GCM داخل AndroidKeyStore بصيغة ks:<iv>:<ct>)، والمفتاح
 * alias لكل مستخدم منذ v13 (PinVault.encryptFor/decryptFor).
 *
 * المسار كله JVM-قابل للاختبار بمفاتيح محقونة — لا Android API هنا سوى ما
 * يغطيه PinVault نفسه.
 */
object UserAuth {

    /** دورات الاشتقاق — نفس PinManager (القراءة من مصدر الحقيقة الواحد) */
    const val ITERATIONS = PinManager.ITERATIONS

    /**
     * إنشاء سر دخول جديد لمستخدم — يُغلّف بمفتاح المستخدم الخاص
     * (superbiz_pin_u<id>) ويعيد الصف الجاهز للإدراج.
     * الحساب المكلف (PBKDF2 600k) يُستدعى من خيط خلفي — المستدعي (UsersVM)
     * مسؤول عن withContext(Dispatchers.Default) كما يفعل SettingsVM.setPin.
     */
    fun newSecret(userId: Long, pin: String, biometricAllowed: Boolean): UserSecretEntity {
        val salt = PinManager.newSalt()
        val hash = PinManager.hash(pin, salt, ITERATIONS)
        return UserSecretEntity(
            userId = userId,
            pinWrapped = PinVault.encryptFor(userId, hash),
            pinSalt = salt,
            pinIters = ITERATIONS,
            biometricAllowed = if (biometricAllowed) 1 else 0
        )
    }

    /**
     * التحقق من رمز مستخدم — فك المغلّف ثم تحقق زمني ثابت.
     * يقرأ مفتاح المستخدم ثم المفتاح الرئيسي (بذرة المالك المُرحّلة) —
     * انظر [PinVault.decryptFor]. null/فشل = false دائماً، لا فتح طارئ صامت.
     */
    fun verify(secret: UserSecretEntity, pin: String): Boolean {
        val unwrapped = PinVault.decryptFor(secret.userId, secret.pinWrapped) ?: return false
        val iters = if (secret.pinIters > 0) secret.pinIters else ITERATIONS
        return PinManager.verify(pin, secret.pinSalt, unwrapped, iters)
    }

    /** هل يُسمح لهذا المستخدم بفتح البصمة؟ (المالك فقط افتراضاً — عقد المخطط) */
    fun biometricAllowed(secret: UserSecretEntity?): Boolean = secret?.biometricAllowed == 1
}
