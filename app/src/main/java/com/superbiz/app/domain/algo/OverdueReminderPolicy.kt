package com.superbiz.app.domain.algo

import java.util.Calendar

/**
 * [P14-a] سياسة تذكير «العملاء الذين لم يُزاروا» — منطق نقي بلا أي استيراد أندرويد
 * (قابل للاختبار على JVM مباشرة عبر OverdueReminderPolicyTest).
 *
 * يُغذّي work/VisitReminder (طبقة AlarmManager) وبطاقة الإعداد في مركز الإعدادات،
 * على نمط ExactAlarmPolicy: القرار هنا، والفعل النظامي هناك.
 *
 * لماذا java.util.Calendar لا java.time؟ minSdk هو 24 والمشروع مبني بلا
 * coreLibraryDesugaring، فواجهة java.time غير متاحة على الأجهزة قبل API 26.
 * Calendar يعالج التوقيت الصيفي واختلافت أطوال الأشهر والسنوات تلقائياً.
 */
object OverdueReminderPolicy {

    /** الافتراض: تذكير يومي في الثامنة صباحاً */
    const val DEFAULT_HOUR = 8
    const val DEFAULT_MINUTE = 0

    /**
     * أقرب لحظة إطلاق قادمة لتوقيت hour:minute:
     * - إن لم يحِن توقيت اليوم بعد (أكبر صرامةً من now) فالموعد اليوم.
     * - وإلا (حاز أو يساوي now تماماً) فالموعد غداً — الحد صارم: اللحظة نفسها
     *   لا تصلح للجدولة المستقبلية (نمط ExactAlarmPolicy.schedulable: > now فقط)،
     *   وإلا عاد المنبّه ليطلق في الحين نفسه دورةً بعد دورة.
     * - الثواني وأجزاء الثانية تُصفَّر دائماً: الإطلاق في بداية الدقيقة المحددة.
     * - التوقيت الصيفي: add(DAY_OF_YEAR, 1) عبر Calendar يُبقي الساعة المحلية
     *   ثابتة حتى لو كان اليوم 23 أو 25 ساعة (فجوة الربيع تُحلّ بحتمية lenient).
     */
    fun nextTriggerAt(now: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * هل يجب إطلاق إشعار عند اكتمال موعد التذكير؟
     * المفعّل + عميل متأخر واحد على الأقل — لا إشعار «لا جديد»، فالإشعار
     * المزعج بلا محتوى هو ما يقتل الثقة بالميزة كلها.
     */
    fun shouldNotify(enabled: Boolean, overdueCount: Int): Boolean =
        enabled && overdueCount > 0

    // ═══ [P15-c] أسماء المتأخرين التي تُعرض في متن الإشعار ═══

    /**
     * [P15-c] أسماء العملاء المتأخرين المرشّحة لنص الإشعار الموسّع — منطق نقي
     * بلا أي استيراد أندرويد (يُختبر على JVM في OverdueReminderPolicyTest).
     *
     * القواعد:
     * - الفراغات الطرفية تُقصّ من كل اسم، والاسم الذي يصير فارغاً بعد القص يُسقَط.
     * - الترتيب يبقى كما ورد (أول المتأخرين أولاً).
     * - التكرار يُزال بعد القصّ — أول ظهور هو الذي يبقى.
     * - الناتج لا يتجاوز max اسماً؛ البقية يعوّضها سطر «وN عملاء آخرين…» في
     *   طبقة الإشعار (VisitReminder.onFired) لا هنا.
     * - max <= 0 ⇒ قائمة فارغة (لا أسماء تُعرض إطلاقاً).
     */
    fun topOverdueNames(overdueNames: List<String>, max: Int = 5): List<String> {
        if (max <= 0) return emptyList()
        val out = ArrayList<String>(minOf(max, overdueNames.size))
        val seen = HashSet<String>()
        for (raw in overdueNames) {
            val name = raw.trim()
            if (name.isEmpty()) continue      // اسم فارغ أو فراغات فقط ⇒ يُسقَط
            if (!seen.add(name)) continue     // مكرر بعد القص ⇒ الأول يبقى
            out.add(name)
            if (out.size == max) break        // السقف — ما بعده لا يُعرض في الإشعار
        }
        return out
    }
}
