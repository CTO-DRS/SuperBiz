package com.superbiz.app.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

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
 * نفس الترجمة الداخلية للتركيبة بلا رمي الاستثناء، ويدعم مكتبياً API 28-29.
 * يبقى كل شيء fail-closed: أي استثناء يلتقطه catch الموجود ويعيد onError/UNKNOWN ولا يفتح القفل أبداً.
*/
object BiometricGate {

    /** نتيجة فحص الجهاز — أرقام مكشوفة لسهولة الاختبار */
    const val OK = 0
    const val NO_ENROLLED = 1
    const val NO_HARDWARE = 2
    const val HW_UNAVAILABLE = 3
    const val UNKNOWN = 4

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
     * عرض نافذة النظام للمصادقة (بصمة/وجه/رمز الجهاز).
     * onSuccess عند النجاح؛ onError برسالة خطأ قابلة للعرض عند الفشل أو الإلغاء.
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
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // لا نعتبر إلغاء المستخدم خطأً مزعجاً
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED
                        ) {
                            onError(errString?.toString() ?: "")
                        } else {
                            onError(errString?.toString() ?: "ERROR")
                        }
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
            } else {
                // API 28-29: أي تركيبة WEAK|CREDENTIAL (أو استدعاء بنّاء التركيبة الحديثة) يرمي
                // IllegalArgumentException — المسار المدعوم هنا هو BIOMETRIC_STRONG (الافتراضي)
                // مع setDeviceCredentialAllowed(true) ليعرض النظام رمز قفل الجهاز بديلاً
                builder.setDeviceCredentialAllowed(true)
            }
            prompt.authenticate(builder.build())
        } catch (e: Exception) {
            onError(e.message ?: "ERROR")
        }
    }
}
