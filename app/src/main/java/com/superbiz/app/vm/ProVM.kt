package com.superbiz.app.vm

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.data.repo.ProStore
import com.superbiz.app.domain.KpiBoardP47
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [W1] VM للفريميوم ولوحة المؤشرات:
 *
 * - `pro` من مخزن ProStore (نسخة سريعة — Play يصححها عند كل اتصال).
 * - `billingReady`/`price` حالات تفاعلية تُغذّى من خطاف BillingRepo.onChanged
 *   فيُحدَّث الشاشة لحظة جهوزية المتجر أو رصد السعر بلا استطلاع (polling).
 * - أهداف اللوحة الأربعة تُحفظ في مخزن Pro المستقل (بلا مساس بمخطط الإعدادات).
 */
class ProVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    val pro: StateFlow<Boolean> = g.proStore.unlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _billingReady = MutableStateFlow(false)
    val billingReady: StateFlow<Boolean> = _billingReady

    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    val targets: StateFlow<ProStore.KpiTargets> = g.proStore.targets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProStore.KpiTargets())

    init {
        g.billing.onChanged = { syncFromBilling() }
        connectBilling()
    }

    /** اتصال كسول — آمن للاستدعاء المتكرر (شاشة Pro / لوحة KPIs / الإقلاع) */
    fun connectBilling() {
        g.billing.connect()
        syncFromBilling()
    }

    private fun syncFromBilling() {
        _billingReady.value = g.billing.billingReady
        _price.value = g.billing.formattedPrice
    }

    /** تشغيل تدفق الشراء من نافذة نشطة — الفشل يُعلن للواجهة برمز Play الصادق
     *  [H1-4][v13] تفعيل Pro باب المالك وحده (مصفوفة §3 سطر 13) */
    fun purchase(activity: Activity, onUnavailable: (Int) -> Unit) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.PRO_MANAGE
        )
        connectBilling()
        g.billing.purchase(activity, onUnavailable)
    }

    /** استعادة المشتريات (زر صريح) — النتيجة تصل عبر pro بعد التصحيح
     *  [H1-4][v13] باب المالك وحده أيضاً */
    fun restore(onDone: (Boolean) -> Unit) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.PRO_MANAGE
        )
        connectBilling()
        g.billing.restore(onDone)
    }

    fun setTarget(key: KpiBoardP47.Key, value: Double) = viewModelScope.launch {
        g.proStore.setTarget(key, value)
    }

    override fun onCleared() {
        g.billing.onChanged = null
    }
}
