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
 * [H4-3][ADR-002 D2/D5] — بوابة تفعيل المزامنة مزدوجة الأبواب (نمط ZatcaEnableStore حرفياً).
 * الصمت الشبكي هو الافتراض: لا حزمة واحدة تغادر الجهاز قبل تفعيل صريح من المالك
 * مع ضبط نقطة نهاية يملكها هو (خادم ترحيل عمياء يرى كتلاً مشفرة حصرياً).
 *
 * deviceId: هوية هذا الجهاز في التوأمة — UUID عشوائي يُولَّد عند أول قراءة ويبقى.
 * seq: عدّاد تسلسلي أحادي الاتجاه لكتل هذا الجهاز (يمنع الالتفاف — D4).
 */
private val Context.syncDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> by preferencesDataStore(name = "superbiz_sync")

class SyncEnableStore(private val context: Context) : SyncClock {
    private val kEnabled = booleanPreferencesKey("sync_enabled")
    private val kEndpoint = stringPreferencesKey("sync_endpoint")
    private val kDeviceId = stringPreferencesKey("sync_device_id")
    private val kSeq = longPreferencesKey("sync_seq")
    private val kFullPushed = booleanPreferencesKey("sync_full_pushed")
    private val kLastSyncAt = longPreferencesKey("sync_last_at")

    val enabled: Flow<Boolean> = context.syncDataStore.data
        .map { runCatching { it[kEnabled] ?: false }.getOrDefault(false) }

    val endpoint: Flow<String> = context.syncDataStore.data
        .map { runCatching { it[kEndpoint] ?: "" }.getOrDefault("") }

    suspend fun enabledOnce(): Boolean = runCatching { enabled.first() }.getOrDefault(false)
    suspend fun endpointOnce(): String = runCatching { endpoint.first() }.getOrDefault("")

    suspend fun setEnabled(value: Boolean) { context.syncDataStore.edit { it[kEnabled] = value } }
    suspend fun setEndpoint(value: String) { context.syncDataStore.edit { it[kEndpoint] = value.trim() } }

    /** معرّف الجهاز — UUID يولَّد كسولاً ويثبت للأبد. */
    suspend fun deviceIdOnce(): String {
        var id = ""
        context.syncDataStore.edit { prefs ->
            id = prefs[kDeviceId] ?: ""
            if (id.isEmpty()) {
                id = java.util.UUID.randomUUID().toString()
                prefs[kDeviceId] = id
            }
        }
        return id
    }

    /** العدّاد التسلسلي — قيمة جديدة أحادية التصاعد لكل كتلة دفع. */
    override suspend fun nextSeq(): Long {
        var seq = 0L
        context.syncDataStore.edit { prefs ->
            seq = (prefs[kSeq] ?: 0L) + 1L
            prefs[kSeq] = seq
        }
        return seq
    }

    suspend fun fullPushedOnce(): Boolean = runCatching {
        context.syncDataStore.data.map { it[kFullPushed] ?: false }.first()
    }.getOrDefault(false)

    suspend fun setFullPushed() { context.syncDataStore.edit { it[kFullPushed] = true } }

    suspend fun lastSyncAtOnce(): Long = runCatching {
        context.syncDataStore.data.map { it[kLastSyncAt] ?: 0L }.first()
    }.getOrDefault(0L)

    suspend fun setLastSyncAt(at: Long) { context.syncDataStore.edit { it[kLastSyncAt] = at } }

    // ─── [H4-3] تنفيذ SyncClock + تخزين غلاف المفتاح وبادئة nonce ومؤشرات السحب ───

    private val kNoncePrefix = stringPreferencesKey("sync_nonce_prefix")
    private val kPullCursors = stringPreferencesKey("sync_pull_cursors")
    private val kWrappedKek = stringPreferencesKey("sync_wrapped_kek")

    override suspend fun deviceId(): String = deviceIdOnce()

    override suspend fun enabled(): Boolean = enabledOnce()

    override suspend fun fullPushed(): Boolean = fullPushedOnce()

    override suspend fun markFullPushed() = setFullPushed()

    override suspend fun noncePrefixB64(): String {
        var out = ""
        context.syncDataStore.edit { prefs ->
            out = prefs[kNoncePrefix] ?: ""
            if (out.isEmpty()) {
                out = com.superbiz.app.domain.sync.SyncCrypto.toBase64(
                    com.superbiz.app.domain.sync.SyncCrypto.newNoncePrefix()
                )
                prefs[kNoncePrefix] = out
            }
        }
        return out
    }

    override suspend fun pullCursors(): Map<String, Long> {
        val raw = runCatching {
            context.syncDataStore.data.map { it[kPullCursors] ?: "" }.first()
        }.getOrDefault("")
        if (raw.isEmpty()) return emptyMap()
        return raw.split(',').mapNotNull { pair ->
            val sep = pair.indexOf('=')
            if (sep <= 0) null else pair.substring(0, sep) to (pair.substring(sep + 1).toLongOrNull() ?: 0L)
        }.toMap()
    }

    override suspend fun setPullCursors(cursors: Map<String, Long>) {
        val raw = cursors.entries.joinToString(",") { "${it.key}=${it.value}" }
        context.syncDataStore.edit { it[kPullCursors] = raw }
    }

    /** غلاف KEK المغلَّف بمفتاح Keystore — Base64، لا يُقرأ إلا عبر [com.superbiz.app.security.SyncKeyVault]. */
    suspend fun wrappedKekOnce(): String = runCatching {
        context.syncDataStore.data.map { it[kWrappedKek] ?: "" }.first()
    }.getOrDefault("")

    suspend fun setWrappedKek(b64: String) { context.syncDataStore.edit { it[kWrappedKek] = b64 } }

    suspend fun clearWrappedKek() { context.syncDataStore.edit { it.remove(kWrappedKek) } }
}
