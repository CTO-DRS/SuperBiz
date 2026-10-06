package com.superbiz.app.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.superbiz.app.domain.DebtPlan
import com.superbiz.app.domain.algo.ExactAlarmPolicy
import com.superbiz.app.R
import java.util.concurrent.TimeUnit

/**
 * — تذكيرات خطة سداد الذمم (وظيفة 21).
 *
 * ملاحظة صدق معمارية: المخطط الحالي (14 كياناً، إصدار 4) لا يحتوي ReminderEntity
 * أصلاً، ولا يجوز إضافة كيان (قاعدة عدم تعديل المخطط). أقرب مسار تذكيرات قائم
 * وحقيقي في التطبيق هو إشعارات AutomationLogic — لذا تُنشأ التذكيرات كأعمال
 * WorkManager مؤجلة (OneTime + initialDelay بموعد كل دفعة): تُحفظ في مخزن
 * WorkManager الداخلي، تصمد بعد إغلاق التطبيق وإعادة تشغيل الجهاز، وتطلق
 * إشعاراً نظامياً في يوم استحقاق كل دفعة عبر AutomationLogic.notify نفسه.
 *
 * كل دفعة = عمل مستقل باسم فريد (payplan-party-seq) فلا تتضارب مع تذكيرات
 * الأتمتة الدورية، ولا تلمس AutomationWorker/ReportSchedule (ملفات موجة 4).
 *
 * [P11-b] مسار موازٍ اختياري: مع منح SCHEDULE_EXACT_ALARM تُسلَّح لكل دفعة مستقبلية
 * تنبيه دقيق (ExactAlarms) يطلق في الدقيقة المحددة تماماً؛ بلا الإذن يبقى مسار
 * WorkManager وحده كما كان — الإشعار والنصان والمعرف واحدة في المسارين.
*/
class PaymentPlanWorker(ctx: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(ctx, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val title = inputData.getString(KEY_TITLE) ?: return androidx.work.ListenableWorker.Result.success()
        val body = inputData.getString(KEY_BODY) ?: ""
        val id = inputData.getInt(KEY_NOTIF_ID, 6000)
        // [P5-H14 إصلاح]: كان العمل يُختم success() دائماً — حتى عند رفض إذن
        // POST_NOTIFICATIONS يُحذف العمل من WorkManager ويفقد التذكير إلى الأبد.
        // الآن الفشل في التسليم يعيد retry() بتراجع أُسّي: التذكير يبقى حياً
        // ويُسلَّم تلقائياً بمجرد منح الإذن لاحقاً بدل ضياعه نهائياً
        // [P30-A]: نقرة تذكير القسط تفتح شاشة الأقساط مباشرة — القناة الافتراضية للتنبيهات (P30-B)
        val delivered = AutomationLogic.notify(
            applicationContext, id, title, body,
            contentIntent = AutomationLogic.routeIntent(
                applicationContext, com.superbiz.app.ui.nav.Routes.INSTALLMENTS, id
            )
        )
        return if (delivered) androidx.work.ListenableWorker.Result.success()
        else androidx.work.ListenableWorker.Result.retry()
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_NOTIF_ID = "notifId"
    }
}

object PaymentPlanReminders {

    /** وسم مشترك لكل تذكيرات طرف واحد — للإلغاء الشامل عند إعادة التخطيط */
    fun tagFor(partyId: Long): String = "payplan-$partyId"

    /**
 * إلغاء كل تذكيرات خطة طرف — : كانت الأعمال القديمة تبقى حيّة
 * بعد إعادة التخطيط بعدد دفعات أقل أو بعد حذف/سداد الخطة كاملة، فتطلق إشعارات
 * لدفعات لم تعد موجودة. يستدعى قبل إعادة الجدولة وعند حذف الخطة.
*/
    fun cancelFor(context: Context, partyId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(partyId))
        // [P11-b] تنبيهات دقيقة الطرف نفسه تُلغى معها — لا تذكير يتيم من خطة سابقة
        ExactAlarms.cancelFor(context, partyId)
    }

    /**
 * [P11-b] صيغة معرف الإشعار الوحيدة — يستهلكها مسارا WorkManager والتنبيه الدقيق معاً.
 * : صيغة (partyId*17+seq) % 3000 كانت تتصادم لأزواج
 * (طرف، قسط) كثيرة فتُستبدل تذكيرات بأخرى بصمت. المساحة الآن فريدة
 * لكل (partyId < 400k, seq < 4000) ضمن حدود int الإيجابية.
 * : القاعدة 6000 كانت تجتاح نطاقات الأتمتة الأربعة
 * (100k/200k/300k/400k) — طرف 24 وحده يولّد 102001=100000+pid!
 * القاعدة الآن فوق 10 ملايين بعيداً عن كل النطاقات المسجلة.
*/
    private fun notifIdFor(partyId: Long, seq: Int): Int =
        10_000_000 + ((partyId % 400_000L).toInt() * 4_000) + seq.coerceAtMost(3_999)

    /**
 * جدولة تذكير واحد لكل دفعة في الخطة — يعيد عدد ما جُدول فعلاً.
 * الدفعات المستحقة اليوم أو في الماضي تُجدول فوراً (تأخير 0) بدل تجاهلها.
 * : يُلغي أولاً كل أعمال الطرف القديمة بالوسم المشترك ثم
 * يعيد الجدولة من الصفر — فلا بقايا لخطة أطول سابقة (REPLACE يغطي نفس seq فقط).
*/
    fun schedule(
        context: Context,
        partyId: Long,
        partyName: String,
        payments: List<DebtPlan.PlanPayment>,
        now: Long = System.currentTimeMillis()
    ): Int {
        val wm = WorkManager.getInstance(context)
        cancelFor(context, partyId)
        if (payments.isEmpty()) return 0
        val total = payments.size
        val title = context.getString(R.string.debt_plan_notif_title, partyName)
        var enqueued = 0
        // [P11-b] لقطة لكل دفعة (دفعة، معرف الإشعار، متن) — يستأنفها مسار التنبيه الدقيق بعد الحلقة
        val armed = ArrayList<Triple<DebtPlan.PlanPayment, Int, String>>(payments.size)
        for (p in payments) {
            val body = context.getString(
                R.string.debt_plan_notif_body,
                p.seq, total,
                com.superbiz.app.util.Money.num(DebtPlan.riyals(p.halalas))
            )
            val notifId = notifIdFor(partyId, p.seq)
            armed.add(Triple(p, notifId, body))
            val delay = (p.dueDate - now).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<PaymentPlanWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(tagFor(partyId))
                .setInputData(
                    workDataOf(
                        "title" to title,
                        "body" to body,
                        "notifId" to notifId
                    )
                )
                .build()
            wm.enqueueUniqueWork(
                "payplan-$partyId-${p.seq}",
                ExistingWorkPolicy.REPLACE,
                request
            )
            enqueued++
        }
        // [P11-b] التنبيهات الدقيقة: مستقبلية فقط (الماضية/اليوم يسلّمها مسار WorkManager
        // فوراً)، وبلا إذن النظام يمرّ هنا بلا فعل — نفس السلوك القائم بالضبط.
        if (ExactAlarms.canSchedule(context)) {
            val futureDues = ExactAlarmPolicy.schedulable(payments.map { it.dueDate }, now).toHashSet()
            for ((p, notifId, body) in armed) {
                if (p.dueDate !in futureDues) continue
                ExactAlarms.arm(context, partyId, p.seq, p.dueDate, notifId, title, body)
            }
        }
        return enqueued
    }
}
