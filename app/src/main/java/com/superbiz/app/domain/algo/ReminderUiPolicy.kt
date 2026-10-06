package com.superbiz.app.domain.algo

import java.util.Locale

/**
 * [P16-b] حالات بطاقة «التذكير اليومي» في شاشة الأذونات — البطاقة الثامنة،
 * إعدادٌ لا إذن وقت تشغيل: حالتها = هل التذكير مفعّل في prefs الميزة.
 */
enum class ReminderUiState {
    /** التذكير موقوف — يُعرض سطر «شغّل المفتاح لتجدول» */
    OFF,

    /** مفعّل والإذن الدقيق ممنوح — لا تحذير */
    ON_OK,

    /** مفعّل بلا إذن التنبيهات الدقيقة — صفّ تحذير كهرماني صادق بلا حجب */
    ON_EXACT_WARN
}

/**
 * [P16-b] سياسة عرض بطاقة التذكير اليومي في شاشة الأذونات — منطق نقي بلا أي
 * استيراد أندرويد (يُختبر على JVM مباشرة عبر ReminderUiPolicyTest)، على نمط
 * OverdueReminderPolicy: القرار هنا، والفعل (prefs/AlarmManager) في work/VisitReminder.
 *
 * لماذا وظيفة نقية لفرعٍ بهذه البساطة؟ لأن قواعد البطاقة الحية (متى يظهر التحذير
 * الكهرماني بالضبط) قرارٌ منتج يُستشهَد به عند المراجعة — وقفله باختبار يمنع أن
 * يتحول التحذير صامتاً إلى «يظهر دائماً» أو «لا يظهر أبداً» عند تعديل البطاقة لاحقاً.
 */
object ReminderUiPolicy {

    /**
     * فرع البطاقة من حالتيّ التذكير والإذن الدقيق:
     * - التذكير موقوف ⇒ OFF مهما كان الإذن (التحذير لا معنى له بلا تذكير مجدول).
     * - مفعّل + إذن دقيق ⇒ ON_OK.
     * - مفعّل بلا إذن دقيق ⇒ ON_EXACT_WARN — المذكّر مجدول فعلٌا (منبّه غير دقيق
     *   يعبر Doze) لكن بعض الأجهزة المقيدة لا توصله في وقته تماماً.
     */
    fun deriveReminderState(reminderEnabled: Boolean, exactAlarmGranted: Boolean): ReminderUiState =
        when {
            !reminderEnabled -> ReminderUiState.OFF
            exactAlarmGranted -> ReminderUiState.ON_OK
            else -> ReminderUiState.ON_EXACT_WARN
        }

    /**
     * عرض وقت التذكير بنظام 24 ساعة بأرقام لاتينية ثابتة (نمط بطاقة الإعدادات
     * String.format(Locale.US)) — القيم الخارجة عن النطاق (prefs فاسدة أو يدوية)
     * تُقيَّد كي لا تنتج صيغة مكسورة مثل «25:61»، والقيم السالبة تعود لمنتصف الليل.
     */
    fun timeLabel(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
}
