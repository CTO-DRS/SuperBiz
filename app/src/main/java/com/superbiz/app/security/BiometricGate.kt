package com.superbiz.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * بوابة البيومتريا — بصمة الإصبع والوجه عبر androidx.biometric (أحدث تقنية مدعومة).
 * يدعم الارتداد الآمن إلى رمز قفل الجهاز (DEVICE_CREDENTIAL) على كل الإصدارات.
 *
 * [P6-M6-15 إصلاح] على API 28-29 (أندرويد 9/10) كانت التركيبة BIOMETRIC_WEAK|DEVICE_CREDENTIAL
 * ترمي IllegalArgumentException من PromptInfo.Builder فيبتلعها catch الموجود وتبقى البصمة
 * معطّلة كلياً هناك (فشل مغلق بلا بيومتريا). المنهج الجديد
 * • BIOMETRIC_STRONG بدل BIOMETRIC_WEAK في كل الفحوصات — التركيبة المدعومة رسمياً على 28+.
 * • API ≥ 30: التركيبة الحديثة STRONG|DEVICE_CREDENTIAL عبر setAllowedAuthenticators.
 * • API < 30: المسار المدعوم setDeviceCredentialAllowed(true) (الافتراضي BIOMETRIC_STRONG) —
 *   نفس الترجمة الداخلية للتركيبة بلا رمي الاستثناء، ويدعم مكتبياً API 28-29.
 * يبقى كل شيء fail-closed: أي استثناء يلتقطه catch الموجود ويعيد onError/UNKNOWN ولا يفتح القفل أبداً.
 *
 * [تدقيق M-9 إصلاح] ربط المصادقة بمفتاح Keystore عبر CryptoObject:
 * كانت المصادقة بلا كائن تشفير فتثبت «فتح الجهاز» لا «حيازة سر التطبيق» —
 * بصمة جديدة يضيفها غير المالك على جهاز غير مقفل كانت تجتاز البوابة. الآن
 * • مفتاح AES-256-GCM «superbiz_biometric_gate_v1» بتوثيق لكل استخدام
 *   (setUserAuthenticationRequired + صلاحية -1) وإبطال عند إضافة بصمة جديدة،
 * • البوابة تُمرَّر للنظام كمزمع تشفير — النجاح يفك المفتاح فعلاً،
 * • والنجاح المعلن لا يُقبل إلا بإكمال العملية التشفيرية (doFinal) —
 *   أي غياب CryptoObject أو فشل التشفير = onError (فشل مغلق — لا فتح احتياطي
 *   بلا تشفير يعيد ثغرة «بصمة الجهاز تكفي»).
 * فشل تهيئة المفتاح (عطل عتاد نادر) = البصمة غير متاحة هذه الجلسة والمستخدم
 * يعود للرمز — القفل الأساسي (خزنة PIN) لم يمسّه هذا الإصلاح.
 */
object BiometricGate {

    /** نتيجة فحص الجهاز — أرقام مكشوفة لسهولة الاختبار */
    const val OK = 0
    const val NO_ENROLLED = 1
    const val NO_HARDWARE = 2
    const val HW_UNAVAILABLE = 3
    const val UNKNOWN = 4

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GATE_KEY_ALIAS = "superbiz_biometric_gate_v1"
    /** تحدي مصغّر يُحسم عبر doFinal عند النجاح — إثبات استخدام المفتاح فعلاً */
    private val GATE_CHALLENGE = "superbiz-gate".toByteArray(Charsets.UTF_8)

    /**
     * مفتاح بوابة البيومتريا — ينشأ مرة واحدة داخل العتاد:
     * توثيق لكل استخدام (لا صلاحية زمنية) + إبطال عند إضافة بصمة جديدة،
     * وعلى API 30+ يسمح بالمتصدقين الاثنين (بصمة قوية + رمز الجهاز) مطابقةً
     * لتركيبة PromptInfo؛ على 28-29 بصمة قوية فقط (قيود المنصة — رمز الجهاز
     * هناك لا يدعم CryptoObject أصلاً فيفقد البصمةُ الارتدادَ وتبقى الرمزَ متاحاً).
     */
    private fun gateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        (ks.getEntry(GATE_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            GATE_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // [تدقيق M-9] توثيق لكل استخدام مع كلا المتصدقين — مطابق لتركيبة البوابة
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            // 28-29: لكل استخدام (بصمة قوية فقط تحت CryptoObject)
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        gen.init(builder.build())
        return gen.generateKey()
    }

    /** هل يمكن المصادقة الآن (بصمة قوية أو وجه أو رمز الجهاز)؟ */
    fun check(activity: FragmentActivity): Int = try {
        val bm = BiometricManager.from(activity)
        // [P6-M6-15 إصلاح] STRONG|CREDENTIAL هي التركيبة الموثقة المدعومة في canAuthenticate على 28+ بدل WEAK
        val auths = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        when (bm.canAuthenticate(auths)) {
            BiometricManager.BIOMETRIC_SUCCESS -> OK
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> NO_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> HW_UNAVAILABLE
            else -> UNKNOWN
        }
    } catch (e: Exception) {
        UNKNOWN
    }

    /**
     * عرض نافذة النظام للمصادقة (بصمة/وجه/رمز الجهاز) — مقيّدة بمزمع تشفير.
     * onSuccess عند النجاح التشفيري المكتمل؛ onError برسالة قابلة للعرض عند
     * الفشل أو الإلغاء أو عجز تهيئة المفتاح (فشل مغلق — لا فتح بلا تشفير).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            // تهيئة المزمع أولاً — فشل Keystore هنا = رفض صريح قبل عرض النافذة
            // (لا تراجع لاستدعاء بلا تشفير — كان سيعيد ثغرة M-9 من النافذة الخلفية)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, gateKey())
            val crypto = BiometricPrompt.CryptoObject(cipher)

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        // [تدقيق M-9] النجاح المعلن لا يكفي — العملية التشفيرية تُحسم فعلاً:
                        // غياب CryptoObject (نظرياً) أو فشل doFinal = رفض مغلق
                        val done = try {
                            result.cryptoObject?.cipher?.doFinal(GATE_CHALLENGE) != null
                        } catch (_: Exception) {
                            false
                        }
                        if (done) onSuccess()
                        else onError("gate cipher failed")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // لا نعتبر إلغاء المستخدم خطأً مزعجاً
                        onError(errString?.toString() ?: "")
                    }
                }
            )
            // [P6-M6-15 إصلاح] بناء PromptInfo مشروطاً بالإصدار — لا تركيبة غير مدعومة تُرمى على 28-29
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            }
            // API 28-29 [تدقيق M-9]: لا يُضبط setDeviceCredentialAllowed مع CryptoObject —
            // الجمع بينهما يرمي IllegalArgumentException فيبتلعه catch وتبقى البصمة
            // معطلة كلياً هناك (انحدار P6-M6-15). الافتراضي بلا الضبط هو STRONG فقط —
            // المسار الوحيد المدعوم للمصادقة التشفيرية على 28-29، ورمز الجهاز يبقى
            // متاحاً كمسار الإدخال اليدوي في شاشة القفل نفسها.
            prompt.authenticate(builder.build(), crypto)
        } catch (e: Exception) {
            // فشل مغلق: عطل مفتاح/عتاد = البصمة غير متاحة — الرمز هو المسار الآمن
            onError(e.message ?: "ERROR")
        }
    }
}
