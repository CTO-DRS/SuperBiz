package com.superbiz.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.ZatcaGateway
import com.superbiz.app.domain.ZatcaQueue
import java.util.concurrent.TimeUnit

/**
 * [Z2-ب V 1.5.0] عامل الإبلاغ/التخليص — الغلاف الجهازي الرقيق فوق
 * [ZatcaQueue.Processor] النقي (عقد D3/D6 من ADR-001): العامل لا يحمل أي
 * منطق قائمة — يقرأ المستحق من القاعدة، يمرّر الواجهة النقية، ويكتب النتائج
 * عبر مستدعيات المستودع داخل معاملاتها.
 *
 * الجدولة دورية كل 6 ساعات (فقط أثناء تفعيل الربط — بوابة الميزة D2)؛
 * المبسطة بؤجّلها الزمني حتى 6 ساعات داخل التراجع الأسّي فلا تفوت نافذة
 * 24 ساعة إلا بانقطاع أطول من ذلك — وحينها يُعلَّم LATE في اللوحة (O1)
 * ولا يُهمل: الإبلاغ واجب لا خيار.
 *
 * كل كتابة حالة هنا عبر AppGraph.db مباشرة داخل db.withTransaction قصيرة
 * — نفس نمط باقي العمال؛ والمعالج النقي يبقى JVM-قابلاً للاختبار بالكامل.
 */
class ZatcaReportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        // D2: الصمت الكامل خارج تفعيل الربط — العامل الدوري يستيقظ ولا يلمس socket
        if (!graph.zatcaLink.enabledOnce()) return Result.success()
        process(graph)
        return Result.success()
    }

    companion object {

        const val WORK_NAME = "zatca_report_queue"

        /** الجدولة الدورية — تُستدعى عند تفعيل الربط من شاشة ZATCA فقط */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ZatcaReportWorker>(6, TimeUnit.HOURS)
                    .build(),
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * دفعة معالجة واحدة — تُستدعى من العامل ومن الاختبارات (نفس المسار).
         * يعيد ملخص الدفعة النقي للتدقيق.
         */
        suspend fun process(graph: AppGraph): ZatcaQueue.ProcessSummary {
            val db = graph.db
            val pending = db.invoices().pendingForReport()
            val queued = pending.map { inv ->
                ZatcaQueue.Queued(
                    invoiceId = inv.id,
                    uuid = inv.uuid,
                    icv = inv.icv,
                    pih = inv.pih,
                    isStandard = inv.zatcaSubtype == "0100000",
                    attemptCount = db.zatcaDocs().byInvoice(inv.id)?.attemptCount ?: 0,
                    lastAttemptElapsedMs = 0L, // العامل الدوري: كل دورة تعيد المحاولة المستحقة
                    issuedAtMs = inv.date,
                )
            }
            val processor = ZatcaQueue.Processor(
                gateway = graph.zatcaGateway,
                loadDue = { queued },
                loadDocument = { id -> db.zatcaDocs().byInvoice(id)?.let { it.xml to it.xmlHash } },
                onAccepted = { id, clearedXml, _ ->
                    db.invoices().updateZatcaStatus(id, 2)
                    db.zatcaDocs().markReported(id, System.currentTimeMillis())
                    clearedXml?.let { db.zatcaDocs().markClearedXml(id, it) }
                },
                onRejected = { id, errors ->
                    db.invoices().updateZatcaStatus(id, 3)
                    db.zatcaDocs().markRejected(id, errors.firstOrNull()?.let { "${it.code}: ${it.message}" } ?: "rejected")
                },
                onDeferred = { id, newAttempts, _ ->
                    db.zatcaDocs().markDeferred(id, newAttempts)
                    // الحالة تبقى 1 بالقائمة — التراجع الأسّي يحكم المحاولة القادمة
                },
            )
            return processor.processOnce(maxBatch = 50)
        }
    }
}
