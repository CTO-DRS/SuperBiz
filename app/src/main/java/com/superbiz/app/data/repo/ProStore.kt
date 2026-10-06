package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [W1] مخزن Pro — DataStore منفصل صغير (بلا مساس بمخطط الإعدادات القائم
 * ولا بترحيلاته): نسخة سريعة محلية لاستحقاق Pro + أهداف لوحة المؤشرات.
 *
 * عقد الصحة: هذه القيم **ليست** مصدر الحقيقة للاستحقاق — Google Play هو.
 * تُكتب فقط من رصد فعلي لمشتريات Play (عقد ProEntitlement.localWriteAllowed)
 * وتُصحّح هبوطاً فوراً إن قال Play «مجاني».
 */
private val Context.proDataStore: DataStore<Preferences> by preferencesDataStore(name = "superbiz_pro")

class ProStore(private val context: Context) {

    private val kPro = booleanPreferencesKey("pro_unlocked")
    private val kTargetSales = doublePreferencesKey("kpi_target_sales")
    private val kTargetProfit = doublePreferencesKey("kpi_target_profit")
    private val kTargetExpenses = doublePreferencesKey("kpi_target_expenses")
    private val kTargetOverdue = doublePreferencesKey("kpi_target_overdue")

    /** استحقاق Pro كما يعرفه الجهاز (نسخة سريعة — Play يصححها عند الاتصال) */
    val unlocked: Flow<Boolean> = context.proDataStore.data
        .map { runCatching { it[kPro] ?: false }.getOrDefault(false) }

    /** أهداف اللوحة الأربعة (بالريال — 0 = بلا هدف) */
    data class KpiTargets(
        val sales: Double = 0.0,
        val profit: Double = 0.0,
        val expenses: Double = 0.0,
        val overdue: Double = 0.0
    )

    val targets: Flow<KpiTargets> = context.proDataStore.data.map { p ->
        val safe = { key: Preferences.Key<Double> ->
            runCatching { p[key] ?: 0.0 }.getOrDefault(0.0)
        }
        KpiTargets(
            sales = safe(kTargetSales),
            profit = safe(kTargetProfit),
            expenses = safe(kTargetExpenses),
            overdue = safe(kTargetOverdue)
        )
    }

    /** قراءة متزامنة سريعة للاستحقاق (حارس: أي فشل = مجاني — الافتراض الآمن) */
    suspend fun unlockedOnce(): Boolean =
        runCatching { unlocked.first() }.getOrDefault(false)

    suspend fun setUnlocked(value: Boolean) {
        context.proDataStore.edit { it[kPro] = value }
    }

    suspend fun setTarget(key: com.superbiz.app.domain.KpiBoardP47.Key, value: Double) {
        val k = when (key) {
            com.superbiz.app.domain.KpiBoardP47.Key.SALES -> kTargetSales
            com.superbiz.app.domain.KpiBoardP47.Key.PROFIT -> kTargetProfit
            com.superbiz.app.domain.KpiBoardP47.Key.EXPENSES -> kTargetExpenses
            com.superbiz.app.domain.KpiBoardP47.Key.OVERDUE -> kTargetOverdue
        }
        context.proDataStore.edit { it[k] = value.coerceAtLeast(0.0) }
    }

    suspend fun targetsOnce(): KpiTargets = runCatching { targets.first() }
        .getOrDefault(KpiTargets())
}
