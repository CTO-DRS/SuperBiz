package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.superbiz.app.domain.algo.PeriodLock
import com.superbiz.app.domain.algo.PeriodLockGuard
import kotlinx.coroutines.flow.first

private val Context.periodLockDataStore: DataStore<Preferences> by preferencesDataStore(name = "superbiz_period_lock")

/**
 * يحفظ آخر يوم مقفل (epoch day) في DataStore منفصل (نمط ZatcaEnableStore — لا ترحيل إعدادات)
 * ويُبقي [PeriodLockGuard] متزامناً. غياب المفتاح = لا إقفال.
 */
class PeriodLockStore(private val context: Context) {
    private val kThrough = longPreferencesKey("locked_through_day")

    suspend fun lockedThrough(): Long? = runCatching { context.periodLockDataStore.data.first()[kThrough] }.getOrNull()

    /** يُستدعى عند الإقلاع لتحميل القيمة إلى الحارس. */
    suspend fun loadIntoGuard() { PeriodLockGuard.lockedThrough = lockedThrough() }

    suspend fun lock(newThrough: Long, today: Long): PeriodLock.Change {
        val r = PeriodLock.lock(lockedThrough(), newThrough, today)
        if (r is PeriodLock.Change.Ok) persist(r.lockedThrough)
        return r
    }

    suspend fun reopen(newThrough: Long?, isOwner: Boolean, reason: String): PeriodLock.Change {
        val r = PeriodLock.reopen(lockedThrough(), newThrough, isOwner, reason)
        if (r is PeriodLock.Change.Ok) persist(r.lockedThrough)
        return r
    }

    private suspend fun persist(v: Long?) {
        context.periodLockDataStore.edit { if (v == null) it.remove(kThrough) else it[kThrough] = v }
        PeriodLockGuard.lockedThrough = v
    }
}
