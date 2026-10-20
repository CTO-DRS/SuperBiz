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
    // [AUD-7 إصلاح] المفتاح المشفّر بصيغة ks: من PinVault — كان السر يُخزَّن نصاً عارياً
    // في DataStore (نفس صنف مادة smtpPass التي عولجت في تدقيق H-6): مُوقّع HMAC قادر
    // على تزوير X-SuperBiz-Signature لا يجوز أن يقبع بجانب نظيره المحمي بهذه الصفة
    private val kSecretVault = stringPreferencesKey("wh_secret_ks")
    private val kLastAt = longPreferencesKey("wh_last_at")
    private val kLastOk = booleanPreferencesKey("wh_last_ok")

    val enabled: Flow<Boolean> = context.webhookDataStore.data
        .map { runCatching { it[kEnabled] ?: false }.getOrDefault(false) }

    suspend fun enabledOnce(): Boolean = runCatching { enabled.first() }.getOrDefault(false)
    suspend fun urlOnce(): String = runCatching {
        context.webhookDataStore.data.map { it[kUrl] ?: "" }.first()
    }.getOrDefault("")

    /**
     * [AUD-7 إصلاح] قراءة السر — نمط readPassword في StatementPrefs حرفياً:
     * ① صيغة ks: فك عبر PinVault والفشل يعيد "" صادقاً (لا إخفاء) — يُعاد الإدخال
     * ② ترحيل شفاف عند القراءة: نص عاري قديم يُشفَّر في wh_secret_ks ويُحذف العاري فوراً
     */
    suspend fun secretOnce(): String = runCatching {
        val prefs = context.webhookDataStore.data.first()
        prefs[kSecretVault]?.let { blob -> return com.superbiz.app.security.PinVault.decrypt(blob) ?: "" }
        val legacy = prefs[kSecret] ?: ""
        if (legacy.isEmpty()) return ""
        try {
            val encrypted = com.superbiz.app.security.PinVault.encrypt(legacy)
            context.webhookDataStore.edit { prefs2 ->
                prefs2[kSecretVault] = encrypted
                prefs2.remove(kSecret)
            }
            legacy
        } catch (_: Exception) {
            // فشل مغلق في الترقية: نجاح فارغ يُجبر إعادة الإدخال وترقية نظيفة في الحفظ التالي
            ""
        }
    }.getOrDefault("")

    /**
     * [E0.2 V3.3.0] هل سر التوقيع مُعدٌّ في المخزن؟ — قراءة تخطيطية صِرفة تفصل
     * حالتين كانتا تندمجان في "" : «لم يُعدِ المالك سراً أصلاً» (التوقيع اختياري
     * بالتصميم — التسليم غير الموقَّع اختيار صريح) و«سر مُعدٌّ لكن غير مقروء»
     * (بصمة ks حاضرة والفك فاشل — استعادة جهاز مثلاً). الثانية هي الفشل الصامت
     * الذي أغلق حسم المالك (FINDINGS §13-ه الخيار أ): لا تسليم غير موقَّع.
     */
    suspend fun secretConfiguredOnce(): Boolean = runCatching {
        val prefs = context.webhookDataStore.data.first()
        !prefs[kSecretVault].isNullOrEmpty() || !prefs[kSecret].isNullOrEmpty()
    }.getOrDefault(false)

    // [RVW-13 V 3.2.4] العنوان/التفعيل/السر ملك المالك حصراً (Op.BACKUP_RESTORE — عقد البوابة نفسه
    // المطبق على مفتاح التفعيل في البطاقة). البوابة هنا في طبقة البيانات لا الواجهة:
    // إعادة توجيه عنوان HTTPS مفعّل تُخرج الأحداث الموقّعة بسر المالك إلى مستلم غريب.
    suspend fun setEnabled(v: Boolean) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
        )
        context.webhookDataStore.edit { it[kEnabled] = v }
    }

    suspend fun setUrl(v: String) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
        )
        context.webhookDataStore.edit { it[kUrl] = v.trim() }
    }

    /**
     * [AUD-7 إصلاح] الحفظ — لا يلمس القرص إلا مشفّراً بصيغة ks: (فشل مغلق:
     * PinVaultException يُرمى ولا يُخزَّن بديل عاري)، والسر المُصفّى يمسح كلا المفتاحين،
     * والعاري القديم يُحذف مع كل حفظ ناجح (طهارة نهائية لأي تثبيت قديم)
     */
    suspend fun setSecret(v: String) {
        // [RVW-13 V 3.2.4] نفس بوابة العنوان والتفعيل — سر توقيع HMAC مادة مالك
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
        )
        val trimmed = v.trim()
        context.webhookDataStore.edit { p ->
            if (trimmed.isEmpty()) {
                p.remove(kSecretVault); p.remove(kSecret)
            } else {
                p[kSecretVault] = com.superbiz.app.security.PinVault.encrypt(trimmed)
                p.remove(kSecret)
            }
        }
    }

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
