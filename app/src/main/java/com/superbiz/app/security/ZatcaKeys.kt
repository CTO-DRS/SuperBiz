package com.superbiz.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.superbiz.app.domain.algo.ZatcaStamp
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * [P15-a] طبقة أندرويد رقيقة فوق AndroidKeyStore لمفتاح بصمة ZATCA —
 * ليست ضمن اختبارات الوحدة (تتطلب جهازاً/محاكياً بمخزن مفاتيح حقيقي).
 *
 * - المفتاح: EC منحنى NIST P-256 (secp256r1) باسم ثابت «superbiz_zatca»،
 *   أغراض SIGN/VERIFY وتهشيم SHA-256 فقط — KeyGenParameterSpec بأغراض
 *   التوقيع مدعوم منذ API 23 (minSdk 24 ✓) والمفتاح داخل TEE/StrongBox
 *   حين يتوفر ولا يُصدَّر أبداً.
 * - كل دالة مغلَّفة try/catch وتعيد null/false عند أي فشل — لا تُغلق أبداً
 *   توليد PDF (InvoicePdf يعود للمرحلة-1 بصمت عند null).
 * - قرار التوقيع (مع ZatcaStamp): signer.sign(bytes(canonicalXml)) —
 *   «SHA256withECDSA» في AndroidKeyStore يستقبل البيانات الخام ويهشّم
 *   داخلياً بـ SHA-256 (المُحدَّد في setDigests) ثم يوقّع.
 */
object ZatcaKeys {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "superbiz_zatca"

    /** اسم العنوان الثابت — مفيد للاختبارات/التشخيص ولا يُغيَّر (تغييره يفقد التوقيعات) */
    val keyAlias: String get() = ALIAS

    /**
     * ضمان وجود زوج المفاتيح: موجود ⇒ true؛ غير موجود ⇒ توليد ثم تأكيد الوجود.
     * أي فشل (KeyStore غير متاح مثلاً على أجهزة مقيدة) ⇒ false بلا استثناءات.
     */
    fun ensureKeyPair(): Boolean = try {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (ks.containsAlias(ALIAS)) {
            true
        } else {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            kpg.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    // المنحنى صراحةً P-256 (المرجع نفسه في اختبارات ZatcaStamp البرمجية)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            kpg.generateKeyPair()
            ks.containsAlias(ALIAS)
        }
    } catch (_: Exception) {
        false
    }

    /**
     * مغلِّف توقيع فوق المفتاح الخاص المخزَّن — يُنشئ نسخة Signature لكل نداء
     * (Signature غير آمنة خيطياً وقد يُولَّد أكثر من PDF بخيوط IO متوازية).
     * فشل الوصول للمفتاح أو تهيئة التوقيع ⇒ null (لا استثناءات للخارج).
     */
    fun signer(): ZatcaStamp.Signer? = try {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: return null
        val privateKey = entry.privateKey
        ZatcaStamp.Signer { data ->
            val s = Signature.getInstance("SHA256withECDSA", PROVIDER)
            s.initSign(privateKey)
            s.update(data)
            s.sign()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * نقطة المفتاح العام 65 بايت (0x04||X||Y) لوسم 8 — تُستخرج من شهادة العنوان:
     * ترميز X.509 لـ SubjectPublicKeyInfo ينتهي دائماً بمحتوى BIT STRING وهو
     * نقطة SEC1 غير المضغوطة، فلمنحنى P-256 تكون آخر 65 بايت من الترميز.
     * استخراج دفاعي: نتحقق من الحد الأدنى للطول ومن بادئة 0x04 عند الإزاحة
     * (حجم-65) بالضبط — أي انحراف (منحنى آخر، ترميز غير متوقع) ⇒ null.
     */
    fun publicKeyPoint(): ByteArray? {
        return try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val cert = ks.getCertificate(ALIAS) ?: return null
            val enc = cert.publicKey.encoded
            if (enc.size < 65) return null
            val start = enc.size - 65
            if (enc[start] != 0x04.toByte()) return null
            enc.copyOfRange(start, enc.size)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [P16-a] ترميز X.509 SubjectPublicKeyInfo الكامل للمفتاح العام (DER) —
     * المُدخل القياسي لغلاف PEM في ZatcaPem.toPem (تصدير/مشاركة المفتاح العام
     * مع مُتحقِّق خارجي). يختلف عن publicKeyPoint() أعلاه: تلك تعيد نقطة SEC1
     * العارية (آخر 65 بايت) لوسم 8 داخل QR، وهذه تعيد غلاف SPKI كاملاً
     * (91 بايتاً لمنحنى P-256) وهو الشكل الذي تتوقعه أدوات التحقق الخارجية.
     * جسم كتلي صراحةً (درس 15-integration: return داخل جسم تعبيرية لا يُصرَّف).
     * دفاعي: أي فشل ⇒ null بلا استثناءات.
     */
    fun publicKeySpki(): ByteArray? {
        return try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val cert = ks.getCertificate(ALIAS)
            cert?.publicKey?.encoded
        } catch (_: Exception) {
            null
        }
    }
}
