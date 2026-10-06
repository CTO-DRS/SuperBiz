package com.superbiz.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superbiz.app.AppGraph
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.SuperBizApp
import com.superbiz.app.domain.algo.OverdueReminderPolicy
import com.superbiz.app.domain.algo.VisitReport
import com.superbiz.app.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [P14-a] تذكير يومي واحد بعملاء «الذين لم يُزاروا» — على نمط ExactAlarms لكن
 * بمنبّه وحيد ثابت (requestCode واحد يُستبدَل بالتحديث) بدل منبّه لكل دفعة:
 *
 * - الإعداد (enabled/hour/minute/threshold/armedFor) في SharedPreferences
 *   المستقلة «visit_reminder» — بعيدة عن دفتر exact_alarm_journal كي لا
 *   يتشابك دفتر الأقساط لكل-دفعة مع المنبّه اليومي الوحيد.
 * - عند الإطلاق: onFired يقرأ الزيارات والأطراف من القاعدة، يحسب المتأخرين
 *   عبر VisitReport.summarize بعتبة الأيام المخزّنة، يُطلق إشعاراً إذا كان
 *   هناك متأخرون (OverdueReminderPolicy.shouldNotify) ثم يعيد تسليح موعد الغد.
 * - معرف الإشعار ثابت (NOTIF_ID): التكرار اليومي يستبدل الإشعار السابق
 *   بدل تكديس إشعار لكل يوم.
 * - كل شيء مُغلَّف try/catch على نمط ExactAlarms: فشل الإشعار أو المنبّه
 *   لا يغلق التطبيق أبداً.
 */
object VisitReminder {

    private const val PREFS = "visit_reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_ARMED_FOR = "armedFor"

    /** فعل المنبّه — يميّز بثّنا عن أي بث آخر يصل للمتلقي */
    const val ACTION_FIRE = "com.superbiz.app.action.VISIT_REMINDER_FIRE"

    /** requestCode ثابت للمنبّه الوحيد — إعادة الجدولة تستبدل القديم بلا تراكم */
    const val REQUEST_CODE = 4711

    /** معرف الإشعار ثابت — الإطلاق المتكرر يستبدل الإشعار بدل تكديسه */
    const val NOTIF_ID = 4712

    /** [P15-c] requestCode لهدف نقرة الإشعار (فتح المفضّلات) — بعيد عن 4711 (المنبّه)
     *  و900 (هدف الأتمتة/الويدجت) و5001-3 و4712 (معرف الإشعار لا رمز طلب) */
    const val CONTENT_REQUEST_CODE = 4713

    /** العتبة الافتراضية: آخر زيارة أقدم من 14 يوماً ⇒ الطرف «متأخر» (نمط VisitReport) */
    const val DEFAULT_THRESHOLD_DAYS = 14

    // ═════════ الإعدادات — قراءة/كتابة prefs (تستدعيها بطاقة الواجهة مباشرة) ═════════

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean =
        try { prefs(context).getBoolean(KEY_ENABLED, false) } catch (_: Exception) { false }

    fun hour(context: Context): Int =
        try { prefs(context).getInt(KEY_HOUR, OverdueReminderPolicy.DEFAULT_HOUR) }
        catch (_: Exception) { OverdueReminderPolicy.DEFAULT_HOUR }

    fun minute(context: Context): Int =
        try { prefs(context).getInt(KEY_MINUTE, OverdueReminderPolicy.DEFAULT_MINUTE) }
        catch (_: Exception) { OverdueReminderPolicy.DEFAULT_MINUTE }

    fun threshold(context: Context): Int =
        try { prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_DAYS) }
        catch (_: Exception) { DEFAULT_THRESHOLD_DAYS }

    /** تفعيل/تعطيل التذكير: التفعيل يسلّح فوراً من الوقت المخزّن، والتعطيل يلغي المنبّه ويمحو موعده */
    fun setEnabled(context: Context, on: Boolean) {
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
            if (on) scheduleNext(context) else cancel(context)
        } catch (_: Exception) {
        }
    }

    /** ضبط وقت التذكير اليومي — يُخزَّن ثم يُعاد التسليح إن كان مفعّلاً */
    fun setTime(context: Context, hour: Int, minute: Int) {
        try {
            prefs(context).edit()
                .putInt(KEY_HOUR, hour.coerceIn(0, 23))
                .putInt(KEY_MINUTE, minute.coerceIn(0, 59))
                .apply()
            if (enabled(context)) scheduleNext(context)
        } catch (_: Exception) {
        }
    }

    /** ضبط عتبة التأخير بالأيام (7/14/30 من البطاقة) — لا تعيد الجدولة، تُقرأ لحظة الإطلاق */
    fun setThreshold(context: Context, days: Int) {
        try {
            prefs(context).edit().putInt(KEY_THRESHOLD, days.coerceIn(1, 365)).apply()
        } catch (_: Exception) {
        }
    }

    // ═════════ الجدولة ═════════

    /**
     * تسليح أقرب موعد قادم من OverdueReminderPolicy.nextTriggerAt.
     * - مع إذن التنبيهات الدقيقة: setExactAndAllowWhileIdle (API 31+، وأخواتها
     *   setExact قبله) — يطلق في الدقيقة المحددة تماماً حتى في Doze (نمط ExactAlarms.arm).
     * - بلا الإذن: هبوط درجة إلى setAndAllowWhileIdle — غير دقيق (قد يتأخر دقائق
     *   على الأجهزة المقيدة) لكنه يخترق Doze ويبقى يعمل بلا أي إذن إضافي. لا مسار
     *   WorkManager موازٍ هنا كي لا يتكرر الإشعار اليومي من مسارين.
     * - رفض النظام في اللحظة الأخيرة (SecurityException) يُنسكت عنه ولا يُكتب armedFor.
     */
    fun scheduleNext(context: Context) {
        try {
            val p = prefs(context)
            val at = OverdueReminderPolicy.nextTriggerAt(
                System.currentTimeMillis(),
                p.getInt(KEY_HOUR, OverdueReminderPolicy.DEFAULT_HOUR),
                p.getInt(KEY_MINUTE, OverdueReminderPolicy.DEFAULT_MINUTE)
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, VisitReminderReceiver::class.java).setAction(ACTION_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (ExactAlarms.canSchedule(context)) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                    }
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            } catch (_: Exception) {
                // SecurityException إن سُحب الإذن في هذه اللحظة — نسكت ولا نكتب موعداً كاذباً
                return
            }
            p.edit().putLong(KEY_ARMED_FOR, at).apply()
        } catch (_: Exception) {
            // أي فشل آخر (جهاز مقيّد بلا خدمة AlarmManager مثلاً) لا يغلق التطبيق
        }
    }

    /** إلغاء المنبّه ومحو موعده المسجّل (عند التعطيل) — صامت تماماً كي لا يُغلق التطبيق */
    private fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, VisitReminderReceiver::class.java).setAction(ACTION_FIRE),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let { am.cancel(it) }
            prefs(context).edit().remove(KEY_ARMED_FOR).apply()
        } catch (_: Exception) {
        }
    }

    // ═════════ لحظة الإطلاق ═════════

    /**
     * يُستدعى من VisitReminderReceiver بعد goAsync — الاستعلام والإشعار وإعادة
     * التسليح كلها في coroutine على IO (نافذة goAsync ذات الـ10 ثوانٍ تكفي على
     * قاعدتين صغيرتين)، وfinish() في finally كي يعود المتلقي للنظام مهما حدث.
     */
    fun onFired(context: Context, result: BroadcastReceiver.PendingResult?) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = AppGraph.from(app)
                val p = prefs(app)
                val threshold = p.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD_DAYS)
                val enabledNow = p.getBoolean(KEY_ENABLED, false)
                val visits = graph.visits.allOnce()
                val parties = graph.db.parties().allOnce()
                val data = VisitReport.summarize(
                    visits, parties, System.currentTimeMillis(), threshold
                )
                if (OverdueReminderPolicy.shouldNotify(enabledNow, data.overdue.size)) {
                    // العنوان يحمل العدد والعتبة (%1$d/%2$d) والمتن ثابت من الموارد
                    // [P15-c] المتن الموسّع يسرد أوائل المتأخرين بالاسم (أحدهم في كل سطر)،
                    // وإن كان هناك أكثر من الاسم الظاهر أُضيف سطر «وN عملاء آخرين…».
                    // والنقر على الإشعار يفتح شاشة المفضّلات مباشرة عبر EXTRA_ROUTE —
                    // المسار يُستهلك مرة واحدة في SuperBizRoot بعد تجاوز الترحيب/القفل.
                    val names = OverdueReminderPolicy.topOverdueNames(
                        data.overdue.map { it.name }, max = 5
                    )
                    val bigText = if (names.isEmpty()) {
                        // كل الأسماء فراغية (حالة شبه مستحيلة) ⇒ بلا توسيع، المتن الثابت يكفي
                        null
                    } else {
                        val more = data.overdue.size - names.size
                        val lines = names.map { "• $it" }.toMutableList()
                        if (more > 0) lines.add(app.getString(R.string.vr_more_names, more))
                        lines.joinToString("\n")
                    }
                    val contentPi = PendingIntent.getActivity(
                        app,
                        CONTENT_REQUEST_CODE,
                        Intent(app, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_ROUTE, Routes.FAVORITES)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    AutomationLogic.notify(
                        app,
                        NOTIF_ID,
                        app.getString(R.string.vr_notif_title, data.overdue.size, threshold),
                        app.getString(R.string.vr_notif_body),
                        bigText,
                        contentPi,
                        // [P30-B]: قناة الزيارات المتخصصة
                        channel = SuperBizApp.CHANNEL_VISITS
                    )
                }
                // إعادة تسليح موعد الغد فوراً — المنبّه الوحيد ينطفئ بعد كل إطلاق.
                // بشرط البقاء مفعّلاً: لو أوقف المستخدم الميزة أثناء سباق الإطلاق
                // (cancel لم يلحق منبّهاً أُطلق فعلاً) فلا يُعاد تسليحه من جديد
                if (enabledNow) scheduleNext(app)
            } catch (_: Exception) {
                // فشل القاعدة أو الإشعار لا يغلق شيئاً — الغد يحاول من جديد
            } finally {
                try { result?.finish() } catch (_: Exception) { }
            }
        }
    }

    // ═════════ إعادة التسليح بعد الإقلاع/بدء العملية ═════════

    /**
     * إعادة الجدولة عند بدء العملية (App.onCreate) أو بعد إعادة تشغيل الجهاز (BootReceiver).
     *
     * force=false (افتراضها للإقلاع العادي): موعد مسجّل مستقبلي يعني المنبّه حيّ
     * في AlarmManager — المنبّهات تنجو من موت العملية — فنتركه (إلغاء إلزامي).
     * force=true (للـBootReceiver): AlarmManager يموت مع إعادة تشغيل الجهاز
     * بالكامل حتى لو كان موعده المسجّل مستقبلياً، فإعادة الجدولة إلزامية كي لا
     * يموت التذكير صامتاً حتى الموعد القادم ثم إلى الأبد.
     */
    fun reattach(context: Context, force: Boolean = false) {
        try {
            if (!enabled(context)) return
            if (!force) {
                val armedFor = prefs(context).getLong(KEY_ARMED_FOR, 0L)
                if (armedFor > System.currentTimeMillis()) return
            }
            scheduleNext(context)
        } catch (_: Exception) {
        }
    }
}
