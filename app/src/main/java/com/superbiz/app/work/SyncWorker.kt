package com.superbiz.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.security.SyncKeyVault
import java.util.concurrent.TimeUnit

/**
 * [H4-3][ADR-002 D2] — عامل المزامنة الدوري (نمط ZatcaReportWorker حرفياً):
 * صمت كامل خارج التفعيل (البوابة تُفحص أول السطر)، ودورة 6 ساعات بمحاولة
 * فورية عند التفعيل. الفشل العابر يترك الدفتر سليماً — الدورة القادمة تلحقه.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = AppGraph.from(applicationContext)
        if (!graph.sync.enabledOnce()) return Result.success()
        if (graph.sync.endpointOnce().isEmpty()) return Result.success()
        val round = com.superbiz.app.data.repo.SyncRound(
            graph.syncEngine,
            graph.syncClient,
            graph.sync,
            kekProvider = { SyncKeyVault.unwrap(graph.sync) }
        )
        return when (val r = round.run()) {
            is com.superbiz.app.data.repo.SyncRound.RoundResult.Done -> {
                graph.sync.setLastSyncAt(System.currentTimeMillis())
                Result.success()
            }
            is com.superbiz.app.data.repo.SyncRound.RoundResult.Failed -> {
                ErrorCenter.warn("Sync", r.reason)
                Result.retry()
            }
            else -> Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "superbiz_sync_round"

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
