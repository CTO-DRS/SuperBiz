package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [Z2-أ V 1.5.0] بوابة الميزة المزدوجة للربط الضريبي (D2 من ADR-001):
 *
 * - **مفتاح الميزة**: لا socket واحد يُفتح إلا بعد تفعيل صريح من المستخدم
 *   في شاشة ZATCA — خارجَه يبقى التطبيق صامتاً شبكياً كلياً كما منذ V 1.0.0.
 * - **بيئة الربط**: simulation (sandbox) أم producer (إنتاج) — قاعدة عنوان
 *   واحدة لعميل واحد (D4)؛ الانتقال بينهما قرار تشغيلي في شاشة ZATCA.
 * - مفصول كلياً عن استحقاق Pro (ProStore مستقل) وعن مخطط الإعدادات القائم
 *   (DataStore منفصل — لا ترحيل إعدادات).
 *
 * عقد D5 مكتمل هنا لا لاحقاً: تفعيل الربط يعني أن ما يخرج من الجهاز حصراً
 * مستند UBL + بصمته + بيانات الاعتماد نحو منصة فاتورة — لا شيء آخر، وهذا
 * موثق في سياسة الخصوصية وData Safety مع هذا الإصدار.
 */
private val Context.zatcaLinkDataStore: DataStore<Preferences> by preferencesDataStore(name = "superbiz_zatca_link")

class ZatcaEnableStore(private val context: Context) {

    private val kEnabled = booleanPreferencesKey("zatca_link_enabled")
    private val kProduction = booleanPreferencesKey("zatca_link_production")

    /** هل الربط مفعّل؟ (البوابة الأولى — false الافتراض الآمن) */
    val enabled: Flow<Boolean> = context.zatcaLinkDataStore.data
        .map { runCatching { it[kEnabled] ?: false }.getOrDefault(false) }

    /** بيئة الربط: false = simulation (sandbox) / true = production — false افتراضاً */
    val production: Flow<Boolean> = context.zatcaLinkDataStore.data
        .map { runCatching { it[kProduction] ?: false }.getOrDefault(false) }

    suspend fun enabledOnce(): Boolean =
        runCatching { enabled.first() }.getOrDefault(false)

    suspend fun productionOnce(): Boolean =
        runCatching { production.first() }.getOrDefault(false)

    /** التفعيل يتطلب تأكيداً صريحاً — يُستدعى من شاشة ZATCA بعد تحذير D5 */
    suspend fun setEnabled(value: Boolean) {
        context.zatcaLinkDataStore.edit { it[kEnabled] = value }
    }

    suspend fun setProduction(value: Boolean) {
        context.zatcaLinkDataStore.edit { it[kProduction] = value }
    }
}
