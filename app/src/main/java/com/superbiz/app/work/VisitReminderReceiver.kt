package com.superbiz.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [P14-a] متلقي تذكير «العملاء الذين لم يُزاروا» — منبّه يومي وحيد ثابت.
 *
 * مسؤوليته أصغر ما يمكن (onReceive يجب أن يعيد بسرعة): مطابقة الفعل ثم تمرير
 * الحدية إلى VisitReminder.onFired مع goAsync — نافذة الـ10 ثوانٍ تُصرف داخل
 * coroutine في VisitReminder (استعلام الزيارات والأطراف + إشعار واحد + إعادة
 * تسليح موعد الغد) ثم finish() في finally يعيد التحكم للنظام مهما حدث.
 *
 * مسجّل في AndroidManifest بوصفه exported="false" — داخلي للتطبيق فقط
 * (نمط ExactReminderReceiver المجاور).
 */
class VisitReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VisitReminder.ACTION_FIRE) return
        VisitReminder.onFired(context, goAsync())
    }
}
