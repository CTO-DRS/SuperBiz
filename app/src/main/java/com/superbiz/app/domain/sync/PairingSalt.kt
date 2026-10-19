package com.superbiz.app.domain.sync

import java.security.SecureRandom

/**
 * [RVW-8 V 3.2.3] ملح الإقران لكل زوج أجهزة — إغلاق خطر «الملح الثابت العام»
 * (خطر 1 في FINDINGS §2: Argon2id بملح مجال ثابت «superbiz-sync-v1» يتيح
 * جدولة مسبقة مُستهلكة عبر كل أجهزة SuperBiz إن سرُبت الكتل المغلَّفة).
 *
 * العقد (ADR-002 D1 بصيغة v2 — إضافي لا كاسر):
 * - **الملح عام بالتصميم** — سرّيته ليست شرطاً؛ شرط الأمان تفرّده لكل إقران.
 *   يُخزَّن نصاً في بوابة المزامنة ويُعرض في حوار عبارة المرور لينقل وجهًا
 *   لوجه مع بصمة الإقرار (الجهاز الثاني يُدخله في حقل «رمز الإقران»).
 * - **توافق حرفي مع الأقران القدامى**: حقل الرمز فارغ ⇒ الاشتقاق بملح المجال
 *   الثابت كما كان (v1) — إقران قديم لا يتأثر إطلاقاً.
 * - **فشل ظاهر لا صامت**: رمز منقوح خاطئ ⇒ KEK مختلف ⇒ بصمة الإقرار لا
 *   تتطابق وجهًا لوجه فلا يكتمل الإقران (نفس آلية كشف عبارة المرور الخاطئة).
 * - إعادة الإقران على الجهاز نفسه تعيد استخدام الملح المخزَّن — لا انكسار
 *   صامت لإقران قائم.
 *
 * JVM خالص — قابل للاختبار بلا جهاز (نمط محرك domain النقي).
 */
object PairingSalt {

    /** 16 بايت — حجم ملح Argon2id الموصى به (RFC 9106 §3.1) ومنصّة D1. */
    const val SIZE_BYTES: Int = 16

    /** ملح عشوائي جديد لكل إقران — SecureRandom حصراً. */
    fun newSalt(): ByteArray = ByteArray(SIZE_BYTES).also { SecureRandom().nextBytes(it) }

    /** ترميز الملح للعرض/النقل — نفس مرمّز المزامنة (توحيد الأدوات، حرفياً). */
    fun encode(salt: ByteArray): String {
        require(salt.size == SIZE_BYTES) { "pairing salt 16B" }
        return SyncCrypto.toBase64(salt)
    }

    /**
     * فك الرمز المدخل بصرامة — null عند أي شك (طول/محارف/حجم) كي يُعرض
     * «رمز غير صالح» بدل اشتقاق KEK خاطئ بصمت.
     */
    fun decodeOrNull(text: String): ByteArray? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return runCatching {
            val bytes = SyncCrypto.fromBase64(clean)
            if (bytes.size == SIZE_BYTES) bytes else null
        }.getOrNull()
    }
}
