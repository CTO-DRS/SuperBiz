package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [H4-6][V 3.0.0] — بوابة الويب هوك (نمط البوابات نفسه): صمت كامل افتراضاً،
 * وعنوان HTTPS يملكه المالك، وتوقيع اختياري بمفتاح خاص — الإرسال عند حدث
 * فاتورة جديدة حصراً في هذه الدفعة.
 */
private val Context.webhookDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(name = "superbiz_webhook")

class WebhookStore(private val context: Context) {

    private val kEnabled = booleanPreferencesKey("wh_enabled")
    private val kUrl = stringPreferencesKey("wh_url")
    private val kSecret = stringPreferencesKey("wh_secret")
    private val kLastAt = longPreferencesKey("wh_last_at")
    private val kLastOk = booleanPreferencesKey("wh_last_ok")

    val enabled: Flow<Boolean> = context.webhookDataStore.data
        .map { runCatching { it[kEnabled] ?: false }.getOrDefault(false) }

    suspend fun enabledOnce(): Boolean = runCatching { enabled.first() }.getOrDefault(false)
    suspend fun urlOnce(): String = runCatching {
        context.webhookDataStore.data.map { it[kUrl] ?: "" }.first()
    }.getOrDefault("")
    suspend fun secretOnce(): String = runCatching {
        context.webhookDataStore.data.map { it[kSecret] ?: "" }.first()
    }.getOrDefault("")

    suspend fun setEnabled(v: Boolean) { context.webhookDataStore.edit { it[kEnabled] = v } }
    suspend fun setUrl(v: String) { context.webhookDataStore.edit { it[kUrl] = v.trim() } }
    suspend fun setSecret(v: String) { context.webhookDataStore.edit { it[kSecret] = v.trim() } }

    /** آخر نتيجة إرسال — تُعرض على البطاقة (شفافية التشغيل). */
    suspend fun lastResult(): Pair<Long, Boolean> {
        val at = runCatching { context.webhookDataStore.data.map { it[kLastAt] ?: 0L }.first() }.getOrDefault(0L)
        val ok = runCatching { context.webhookDataStore.data.map { it[kLastOk] ?: false }.first() }.getOrDefault(false)
        return at to ok
    }

    suspend fun setLastResult(at: Long, ok: Boolean) {
        context.webhookDataStore.edit { it[kLastAt] = at; it[kLastOk] = ok }
    }
}
