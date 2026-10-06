package com.superbiz.app.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lockoutStore by preferencesDataStore(name = "superbiz_lockout")

/**
 * سياسة قفل المحاولات الفاشلة — منطق خالص بلا أي اعتماد على Android،
 * قابل للاختبار الوحداتي المباشر.
 *
 * 1-4 محاولات فاشلة: حرية كاملة (تسامح مع خطأ الذاكرة).
 * من الخامسة: قفل تصاعدي 30ث ← 60 ← 120 ← 240 ← 480 ← 900 (15 دقيقة حد أقصى).
 * كل نجاح يصفّر العدّاد بالكامل.
*/
object LockoutPolicy {

    const val FREE_ATTEMPTS = 5
    const val BASE_LOCK_SECONDS = 30
    const val MAX_LOCK_SECONDS = 900

    /** مدة قفل الثواني بعد المحاولة الفاشلة رقم n (n = عدد المحاولات الكلي) */
    fun lockSecondsFor(failCount: Int): Int {
        if (failCount < FREE_ATTEMPTS) return 0
        val exp = failCount - FREE_ATTEMPTS
        if (exp >= 6) return MAX_LOCK_SECONDS
        return minOf(BASE_LOCK_SECONDS * (1 shl exp), MAX_LOCK_SECONDS)
    }
}

/** حالة قفل المحاولات — تُستهلك من شاشة القفل مباشرة */
data class LockStatus(val fails: Int, val lockUntil: Long) {

    /** الثواني المتبقية من القفل — صفر إن انتهى القفل */
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Int {
        val rem = ((lockUntil - now) / 1000L).toInt()
        return if (rem > 0) rem else 0
    }
}

/**
 * حارس المحاولات — يخزّن عدّاد المحاولات وموعد نهاية القفل في DataStore
 * مستقل، فالقفل يصمد أمام إعادة تشغيل التطبيق أو الجهاز (عكس المتغيرات العابرة).
*/
class LockoutGuard(private val context: Context) {

    private object K {
        val fails = intPreferencesKey("pin_fails")
        val until = longPreferencesKey("pin_lock_until")
    }

    suspend fun status(): LockStatus {
        val p = context.lockoutStore.data.first()
        return LockStatus(p[K.fails] ?: 0, p[K.until] ?: 0L)
    }

    /** يُستدعى بعد PIN خاطئ — يحدّث العدّاد ويحسب موعد القفل القادم */
    suspend fun onFailed(): LockStatus {
        val now = System.currentTimeMillis()
        val cur = status()
        val lockedNow = cur.lockUntil > now
        val newFails = if (lockedNow) cur.fails else cur.fails + 1
        val seconds = LockoutPolicy.lockSecondsFor(newFails)
        val newUntil = if (seconds > 0) now + seconds * 1000L else 0L
        context.lockoutStore.edit {
            it[K.fails] = newFails
            it[K.until] = newUntil
        }
        return LockStatus(newFails, newUntil)
    }

    /** يُستدعى بعد أي نجاح مصادقة — تصفير كامل للعدّاد والقفل */
    suspend fun onSuccess() {
        context.lockoutStore.edit {
            it.remove(K.fails)
            it.remove(K.until)
        }
    }
}
