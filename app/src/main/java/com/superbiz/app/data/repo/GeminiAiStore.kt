package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [F2 - ADR-005 D4] بوابة الميزة المقفولة للتحسين السحابي (نمط ZatcaEnableStore
 * + عقد سر WebhookStore AUD-7 حرفيًا):
 *
 * - **مفتاح الميزة**: لا socket واحد يُفتح إلا بعد تفعيل صريح من المالك —
 *   false افتراضيًا فيبقى التطبيق صامتًا شبكيًا كليًا كما منذ V 1.0.0.
 * - **المفتاح (API key)**: لا يُخزَّن نصًا عاريًا أبدًا — مشفَّر AES-256-GCM
 *   بمفتاح AndroidKeyStore عبر PinVault بصيغة ks: (عقد AUD-7 نفسه)، وقراءته
 *   تعيد "" صادقًا عند أي فشل (فشل مغلق يُجبر إعادة الإدخال لا إخفاء).
 * - **الموديل**: نص حر يملكه المالك — فارغًا يعود لافتراضي domain.GeminiGateway.
 * - RBAC: كل الكتابات TAX_OWNER_DATA (باب 8-ب — بيانات المالك الحساسة،
 *   نمط ZatcaEnableStore حرفيًا: قرار إخراج بيانات للسحابة قرار مالك وحده).
 *
 * عقد D4 مكتمل هنا: تفعيل التحسين يعني أن ما يخرج من الجهاز حصرًا نص OCR خام
 * (بلا أي شيء من قاعدة البيانات) وبقراءة المالك وتأكيده لكل إرسال — وهذا
 * يُوثَّق في سياسة الخصوصية وData Safety مع هذه الموجة.
 */
private val Context.geminiAiDataStore: DataStore<Preferences> by preferencesDataStore(name = "superbiz_gemini_ai")

class GeminiAiStore(private val context: Context) {

    private val kEnabled = booleanPreferencesKey("gem_ai_enabled")
    private val kModel = stringPreferencesKey("gem_ai_model")
    private val kKeyVault = stringPreferencesKey("gem_ai_key_ks")

    /** هل التحسين السحابي مفعّل؟ (البوابة الأولى — false الافتراض الآمن) */
    val enabled: Flow<Boolean> = context.geminiAiDataStore.data
        .map { runCatching { it[kEnabled] ?: false }.getOrDefault(false) }

    /** اسم الموديل كما أدخله المالك — فارغ = افتراضي domain.GeminiGateway.MODEL_DEFAULT */
    val model: Flow<String> = context.geminiAiDataStore.data
        .map { runCatching { it[kModel] ?: "" }.getOrDefault("") }

    suspend fun enabledOnce(): Boolean =
        runCatching { enabled.first() }.getOrDefault(false)

    suspend fun modelOnce(): String =
        runCatching { model.first() }.getOrDefault("")

    /** قراءة مفتاح API بعد فك التشفير — "" صادقًا عند الغياب أو فشل الفك (فشل مغلق) */
    suspend fun keyOnce(): String = runCatching {
        context.geminiAiDataStore.data.first()[kKeyVault]
            ?.let { blob -> com.superbiz.app.security.PinVault.decrypt(blob) }
            ?: ""
    }.getOrDefault("")

    /** هل المفتاح مُعدٌّ في المخزن؟ — قراءة تخطيطية تفصل «غير مُعدّ» عن «غير مقروء» */
    suspend fun keyConfiguredOnce(): Boolean = runCatching {
        context.geminiAiDataStore.data.first()[kKeyVault]?.isNotEmpty() == true
    }.getOrDefault(false)

    /** التفعيل/الإيقاف — قرار مالك حصراً (نفس بوابة ZatcaEnableStore حرفيًا) */
    suspend fun setEnabled(value: Boolean) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA
        )
        context.geminiAiDataStore.edit { it[kEnabled] = value }
    }

    /** حفظ مفتاح API — لا يلمس القرص إلا مشفَّرًا بصيغة ks (عقد AUD-7 حرفيًا)؛ الفراغ يمسح */
    suspend fun setKey(v: String) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA
        )
        val trimmed = v.trim()
        context.geminiAiDataStore.edit { p ->
            if (trimmed.isEmpty()) p.remove(kKeyVault)
            else p[kKeyVault] = com.superbiz.app.security.PinVault.encrypt(trimmed)
        }
    }

    /** حفظ اسم الموديل — نص حر مقصوص؛ القرار النهائي في GeminiApi.endpoint */
    suspend fun setModel(v: String) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA
        )
        context.geminiAiDataStore.edit { it[kModel] = v.trim().take(120) }
    }
}
