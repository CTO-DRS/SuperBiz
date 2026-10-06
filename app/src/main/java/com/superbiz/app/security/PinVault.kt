package com.superbiz.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * خزنة PIN — تُغلّف بصمة الهاش بمفتاح AES-256-GCM داخل AndroidKeyStore
 * (مفتاح غير قابل للتصدير من الجهاز ولا يتطلب مصادقة مستخدم في كل عملية).
 *
 * النتيجة العملية: سحب ملفات DataStore (نسخة احتياطية، adb، روت) لا يكفي لكسر الرمز —
 * فالتحقق مستحيل خارج الجهاز الأصلي لأن المفتاح لا يترك العتاد أبداً.
 *
 * صيغ التخزين في DataStore
 * • "ks:<ivHex>:<ctHex>" مشفّر بمفتاح Keystore — الصيغة الوحيدة التي يكتبها encrypt منذ [P6-M6-14]
 * • "v1:<hashHex>" صيغة تراجع قديمة (بصمة PBKDF2 عارية) — تُقرأ للتوافق/الترحيل فقط
 * ولا تُكتب أبداً، وتُرقّى شفافياً إلى ks بعد أول تحقق ناجح (rewrap)
 *
 * [P6-M6-14 إصلاح] فشل Keystore عند التشفير = استثناء PinVaultException (فشل مغلق) —
 * أُزيل التراجع الصامت إلى "v1:<hashHex>" الذي كان يخزّن بصمة قابلة للكسر دون اتصال (GPU).
 * أما فك التشفير الفاشل فيعيد null → الواجهة تبقى مقفلة (فشل مغلق دائماً، لا فتح طارئ صامت).
*/
object PinVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "superbiz_pin_master_v1"
    private const val TAG_LEN_BITS = 128
    private const val KS_PREFIX = "ks:"
    private const val V1_PREFIX = "v1:"

    /** [P6-M6-14 إصلاح] استثناء فشل مغلق: غياب/عطل Keystore عند التغليف يُرمى صريحاً ولا يُخفى بتراجع */
    class PinVaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /**
     * [P6-M6-14 إصلاح] مزوّد مفتاح التغليف — قابل للاستبدال في اختبارات Robolectric فقط
     * (بيئة بلا AndroidKeyStore). الإنتاج يستخدم masterKey دائماً ولا يلمس هذا الحقل.
     */
    internal var keyProvider: () -> SecretKey = { masterKey() }

    /** يشفّر بصمة الهاش ويعيد النص الجاهز للتخزين في DataStore */
    fun encrypt(hashHex: String): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val iv = cipher.iv
            val ct = cipher.doFinal(hashHex.toByteArray(Charsets.UTF_8))
            return KS_PREFIX + iv.joinToString("") { "%02x".format(it) } +
                ":" + ct.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // [P6-M6-14 إصلاح] فشل مغلق: لا يُكتب "v1:<hashHex>" عاري جديد مطلقاً — الكتابة
            // الصامتة كانت تُبعد المادة عن حماية العتاد فتصبح الرموز قابلة للكسر دون اتصال.
            // غياب Keystore الآن = رفض واضح يبرز المشكلة بدل تخزين مادة ضعيفة بصمت.
            throw PinVaultException(
                "تعذر تغليف بصمة الرمز بمفتاح Keystore — الرفض مغلق ولا يُخزَّن أي بديل قابل للكسر دون اتصال",
                e
            )
        }
    }

    /**
     * يفكّ التغليف ويعيد بصمة الهاش؛ null يعني فشل فادح (مفتاح مفقود/تلاعب) —
     * المستدعي يبقي الشاشة مقفلة ولا يفتح أبداً على null.
     * قراءة "v1:" القديمة مدعومة للتوافق (ترحيل مخزون الإصدارات السابقة).
     */
    fun decrypt(blob: String): String? {
        return try {
            when {
                blob.startsWith(V1_PREFIX) -> blob.removePrefix(V1_PREFIX).ifEmpty { null }
                blob.startsWith(KS_PREFIX) -> {
                    val body = blob.removePrefix(KS_PREFIX)
                    val parts = body.split(":", limit = 2)
                    if (parts.size != 2) return null
                    val iv = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val ct = parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LEN_BITS, iv))
                    String(cipher.doFinal(ct), Charsets.UTF_8)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** [P6-M6-14 إصلاح] هل المخزون بصيغة v1 القديمة (بصمة عارية قابلة للكسر دون اتصال)؟ */
    fun needsUpgrade(blob: String?): Boolean = blob != null && blob.startsWith(V1_PREFIX)

    /**
     * [P6-M6-14 إصلاح] الترقية الشفافة v1→ks: تُستدعى بعد أول تحقق ناجح لرمز مخزون بصيغة v1
     * (من SettingsVM.verifyPin) لتعيد تغليف البصمة نفسها بصيغة ks الحالية دون أي إدخال من المستخدم.
     * تعيد السلسلة الجديدة "ks:..." عند النجاح، أو null إذا تعذر (بلا Keystore/فك فاشل/ليست v1) —
     * وفي كل حالات null يبقى المخزون القديم كما هو مقروءاً (لا تدمير للمادة، فشل هادئ يعاد لاحقاً).
     */
    fun rewrap(legacyBlob: String?): String? {
        if (!needsUpgrade(legacyBlob)) return null
        return try {
            val hash = decrypt(legacyBlob!!) ?: return null
            if (hash.isEmpty()) return null
            encrypt(hash)
        } catch (_: Exception) {
            null
        }
    }

    /** يحمّل مفتاح التغليف أو ينشئه مرة واحدة داخل العتاد (داخلي: يُعاد ربطه في الاختبارات بعد الحقن) */
    internal fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }
}
