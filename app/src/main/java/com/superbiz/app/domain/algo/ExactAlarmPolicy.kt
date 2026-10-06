package com.superbiz.app.domain.algo

/**
 * [P11-b] سياسة التنبيهات الدقيقة — منطق نقي بلا أي استيراد أندرويد (قابل للاختبار JVM).
 *
 * قرار «هل يُسلَّح تنبيه دقيق؟» وفصل الدفعات الصالحة وترميز مفاتيح دفتر اليومية
 * كلها هنا؛ طبقة work/ExactAlarms تستدعيها وتضيف فعل النظام (AlarmManager).
 */
object ExactAlarmPolicy {

    /**
     * قبل API 31 لا يوجد قيد إطلاقاً (setExact متاح دائماً)؛
     * من API 31 يلزم منح النظام SCHEDULE_EXACT_ALARM (canScheduleExactAlarms).
     */
    fun canSchedule(apiLevel: Int, systemAllows: Boolean): Boolean =
        apiLevel < 31 || systemAllows

    /**
     * أي دفعات تصلح للتنبيه الدقيق؟ المستقبلية فقط (> now) مرتبة تصاعدياً؛
     * الماضية/الحالية يسندها مسار WorkManager فوراً (السلوك القائم).
     */
    fun schedulable(dueDates: List<Long>, now: Long): List<Long> =
        dueDates.filter { it > now }.sorted()

    /**
     * مفاتيح دفتر اليومية المشفرة — «partyId:dueDate» لكل صف تذكير مسلّح،
     * مقرونة بـ«;». ترميز خالٍ من JSON: رقمان صحيحان فقط لا يظهران في نص عنوان عربي،
     * وتستخدمه طبقة ExactAlarms كبادئة لكل صف كامل (راجع ExactAlarms.Row).
     */
    fun encodeJournal(entries: List<Pair<Long, Long>>): String =
        entries.joinToString(";") { "${it.first}:${it.second}" }

    /** فك الترميز — متسامح: أي صف مشوّه (بلا «:» أو أرقام غير صالحة) يُتخطى بلا انهيار */
    fun decodeJournal(s: String): List<Pair<Long, Long>> =
        s.split(';').mapNotNull { row ->
            val i = row.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val pid = row.substring(0, i).trim().toLongOrNull() ?: return@mapNotNull null
            val due = row.substring(i + 1).trim().toLongOrNull() ?: return@mapNotNull null
            pid to due
        }
}
