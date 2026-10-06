package com.superbiz.app.security

import android.content.Context

/**
 * [P15-a] إعدادات بصمة ZATCA — ملف prefs مستقل «zatca» على نمط ميزة
 * VisitReminder (prefs مستقلة لكل ميزة، لا DataStore ولا VM موازٍ):
 * مفتاح الحقيقة واحد هو هذا الملف، والبطاقة/UI تقرأ منه مباشرة.
 *
 * - «stamped»: تفعيل إضافة الوسوم 6/7/8 (الهاش/التوقيع/المفتاح العام) لرمز QR
 *   في الفواتير. افتراضه false — سلوك المرحلة-1 هو الافتراضي بايتاً ببايت.
 * - التفعيل يمر عبر ZatcaKeys.ensureKeyPair أولاً: تعذّر تجهيز مفتاح الجهاز
 *   ⇒ لا يُفعَّل (يعود false) ولا يُكتب تفعيل كاذب في prefs.
 * - كل الاستدعاءات مغلَّفة try/catch — أي فشل تخزين يقرأه المستخدم كحالة «موقوف»
 *   ولا يغلق شيئاً.
 */
object ZatcaPrefs {

    private const val PREFS = "zatca"
    private const val KEY_STAMPED = "stamped"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_STAMPED, false) } catch (_: Exception) { false }

    /**
     * تفعيل/تعطيل البصمة، ويعيد الحالة الفعلية بعد النداء:
     * - on=true: يتطلب نجاح ensureKeyPair — فشله يعيد false ولا يُفعَّل شيء
     *   (البطاقة تعرض خطأ المفتاح وتبقي المفتاح مطفأً).
     * - on=false: إيقاف دائماً متاح، يعيد false (الحالة الناتجة).
     */
    fun setEnabled(context: Context, on: Boolean): Boolean = try {
        val result = if (on) ZatcaKeys.ensureKeyPair() else false
        prefs(context).edit().putBoolean(KEY_STAMPED, result).apply()
        result
    } catch (_: Exception) {
        false
    }
}
