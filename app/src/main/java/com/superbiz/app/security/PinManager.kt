package com.superbiz.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * حماية بـ PIN — PBKDF2WithHmacSHA256 بملح عشوائي 128 بت وبلا أي تخزين صريح للرمز.
 *
 * ما الجديد في
 * • الدورات 600,000 بدلاً من 60,000 (إرشاد OWASP 2023 لـ PBKDF2-HMAC-SHA256).
 * • ترقية شفافة: بصمات الإصدارات القديمة (60k) تبقى صحيحة عبر تمرير عدد الدورات
 * المخزّن مع البصمة، ويُعاد تشفيرها بالصيغة الجديدة عند أول دخول ناجح.
 * • مقارنة زمنية ثابتة عبر MessageDigest.isEqual (مستوى البايتات، بلا تسريب طول).
*/
object PinManager {

    /** عدد دورات الاشتقاق للبصمات الجديدة (OWASP PBKDF2-HMAC-SHA256 ≥ 600k) */
    const val ITERATIONS = 600_000

    /** دورات الإصدارات السابقة ( وأقدم) — للتحقق أثناء الترقية التلقائية فقط */
    const val LEGACY_ITERATIONS = 60_000

    private const val KEY_LEN = 256

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** اشتقاق بصمة الرمز — عدد الدورات يُمرّر من المخزن لدعم الترقية الشفافة */
    fun hash(pin: String, saltHex: String, iterations: Int = ITERATIONS): String {
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LEN)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    fun verify(
        pin: String,
        saltHex: String,
        expectedHash: String,
        iterations: Int = ITERATIONS
    ): Boolean = secureCompare(hash(pin, saltHex, iterations), expectedHash)

    /** مقارنة زمنية ثابتة حقيقية (تستهلك كامل البايتات بغضّ النظر عن موضع الاختلاف) */
    fun secureCompare(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
}
