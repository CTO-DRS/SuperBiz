package com.superbiz.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * [P11-b] متلقي التنبيه الدقيق — يطلق في الدقيقة المحددة تماماً.
 *
 * مسؤوليته أصغر ما يمكن (onReceive يجب أن يعيد بسرعة):
 * 1) إطلاق الإشعار عبر AutomationLogic.notify — نفس قناة تذكيرات الأتمتة،
 *    وتتولى هو POST_NOTIFICATIONS داخلياً (تعيد false بلا إذن فلا يُرمى استثناء).
 * 2) محو صف الدفعة من دفتر ExactAlarms فلا يُعاد تسليحه بعد الإقلاع على الفاضي.
 * 3) لا إعادة جدولة: تذكيرات ما بعد الدفعة هي أعمال WorkManager القائمة
 *    (PaymentPlanWorker) فتتكفل بها كما كانت.
 *
 * مسجل في AndroidManifest بوصفه exported="false" — داخلي للتطبيق فقط.
 */
class ExactReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(ExactAlarms.EXTRA_NOTIF_ID, -1)
        val title = intent.getStringExtra(ExactAlarms.EXTRA_TITLE)
        val body = intent.getStringExtra(ExactAlarms.EXTRA_BODY) ?: ""
        val partyId = intent.getLongExtra(ExactAlarms.EXTRA_PARTY_ID, -1L)
        val dueAt = intent.getLongExtra(ExactAlarms.EXTRA_DUE_AT, 0L)

        if (notifId > 0 && !title.isNullOrBlank()) {
            // [P30-A]: نقرة التنبيه الدقيق تفتح شاشة الأقساط مباشرة
            AutomationLogic.notify(
                context, notifId, title, body,
                contentIntent = AutomationLogic.routeIntent(
                    context, com.superbiz.app.ui.nav.Routes.INSTALLMENTS, notifId
                )
            )
        }
        if (partyId >= 0) {
            ExactAlarms.journalDone(context, partyId, dueAt)
        }
    }
}
