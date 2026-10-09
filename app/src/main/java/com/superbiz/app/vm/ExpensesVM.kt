package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.Expense
import com.superbiz.app.domain.ExpenseTemplates
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * : VM المصروفات — كل شيء من قاعدة البيانات: القائمة تدفقاً حياً من جدول expenses،
 * الإضافة تقيد مزدوجاً، الحذف يعكس القيد، والفئات تجميع SQL حقيقي.
*/
class ExpensesVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    companion object {
        // [P6-M47 إصلاح] تسمية «أخرى» موحّدة بين التجميع (byCategorySince في المستودع يعيد
        // blank إلى "أخرى") والفلترة — كان الفلتر يقارن الخام فلا يطابق الفئة الفارغة أبداً
        const val OTHER_LABEL = "أخرى"
    }

    // [P33-P8] مجاميع المصروفات قروش Long (من SQL SUM على عمود INTEGER)
    val monthTotal = MutableStateFlow(0L)
    val todayTotal = MutableStateFlow(0L)
    val byCategory = MutableStateFlow<List<Pair<String, Long>>>(emptyList())

    // [H4-1 V 2.5.0] كتالوج العملات لمنتقي حوار الإضافة — التحويل عند addInCurrency
    val currencies = g.db.currencies().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 0 = الكل، وإلا فلترة فئة */
    val categoryFilter = MutableStateFlow("")

    val expenses: StateFlow<List<Expense>> = combine(
        g.expenses.all(), categoryFilter
    ) { list, cat ->
        // [P6-M47 إصلاح] الفئة الفارغة تُصنّف «أخرى» صراحة — كان فلتر cat==category
        // لا يطابق blank أبداً فتظهر قائمة فارغة رغم إجمالي غير صفري في بطاقة التوزيع
        if (cat.isBlank()) list
        else list.filter { (it.category.ifBlank { OTHER_LABEL }) == cat }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val reload = MutableStateFlow(0)

    val summary: StateFlow<Unit> = reload.flatMapLatest {
        flow { emit(loadStats()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Unit)

    private suspend fun loadStats() {
        val now = System.currentTimeMillis()
        // [P33-P8] القيم قروش من قاعدة البيانات كما هي — بلا تقريب
        monthTotal.value = try { g.expenses.monthTotal(now) } catch (e: Exception) { 0L }
        todayTotal.value = try { g.expenses.todayTotal(now) } catch (e: Exception) { 0L }
        byCategory.value = try {
            g.expenses.byCategorySince(Dates.monthStart(now))
        } catch (e: Exception) { emptyList() }
    }

    init { refresh() }

    fun refresh() {
        reload.value++
        launchSafe { loadStats() }
    }

    fun add(amount: Double, category: String, note: String, date: Long = System.currentTimeMillis()) =
        launchSafe {
            try {
                // [P33-P8] المبلغ يدخل ريالاً من المحرر → قروش عند الحدود الوحيدة (Money)
                g.expenses.add(Money.toPiasters(amount), category, note, date)
            } catch (e: Exception) {
                // فشل إضافة المصروف يُسجَّل في مركز الأخطاء بدل الصمت
                com.superbiz.app.core.ErrorCenter.warn(
                    "Expenses", "add: ${e.message}",
                    // [P6-M47 إصلاح] الفشل لم يعد صامتاً للمستخدم — رسالة مفهومة عبر ErrorCenter
                    // تظهر في الـSnackbar الموحد (نمط رسائل رفض الحفظ في بقية الـVMs)
                    getApplication<Application>().getString(com.superbiz.app.R.string.exp_save_failed)
                )
            }
            refresh()
        }

    /**
     * [H4-1 V 2.5.0] إضافة مصروف بعملة أجنبية — الأفق الرابع (عملات متعددة):
     * المبلغ يدخل وحدات 2dp من العملة المختارة، يُشتق سعر micros حتمياً من الكتالوج
     * (FxStampMath.rateMicrosFromCatalog)، ويُحوَّل قروش أساس عبر Money.foreignToBasePiasters
     * (نقطة التحويل الوحيدة)، ويُختم الصف بفئته الأصلية وسعره التاريخي. الإخفاق مغلَق:
     * عملة مجهولة/سعر غير صالح/تحويل فاشل ⇒ رفض برسالة لا صفر مالي زائف.
     */
    fun addInCurrency(foreignMinor: Long, code: String, category: String, note: String, date: Long = System.currentTimeMillis()) =
        launchSafe {
            try {
                val base = g.settings.snapshot().baseCurrency
                val micros: Long = if (code == base) {
                    com.superbiz.app.domain.algo.FxStampMath.BASE_RATE_MICROS
                } else {
                    val entry = g.db.currencies().allOnce().firstOrNull { it.code == code }
                    val m = entry?.let { com.superbiz.app.domain.algo.FxStampMath.rateMicrosFromCatalog(it.rateToBase) }
                    if (m == null) {
                        com.superbiz.app.core.ErrorCenter.warn(
                            "Expenses", "addInCurrency: invalid currency/rate $code",
                            getApplication<Application>().getString(com.superbiz.app.R.string.cur_rate_invalid)
                        )
                        return@launchSafe
                    }
                    m
                }
                val basePiasters = com.superbiz.app.util.Money.foreignToBasePiasters(foreignMinor, 2, micros)
                if (basePiasters == null || basePiasters <= 0L) {
                    com.superbiz.app.core.ErrorCenter.warn(
                        "Expenses", "addInCurrency: unconvertible amount $foreignMinor @ $micros",
                        getApplication<Application>().getString(com.superbiz.app.R.string.exp_save_failed)
                    )
                    return@launchSafe
                }
                g.expenses.addStamped(
                    basePiasters,
                    if (code == base) "" else code,
                    foreignMinor,
                    if (code == base) 0L else micros,
                    category, note, date
                )
            } catch (e: Exception) {
                com.superbiz.app.core.ErrorCenter.warn(
                    "Expenses", "addInCurrency: ${e.message}",
                    getApplication<Application>().getString(com.superbiz.app.R.string.exp_save_failed)
                )
            }
            refresh()
        }

    fun delete(expense: Expense) = launchSafe {
        g.expenses.delete(expense)
        refresh()
    }

    /** الفئات المقترحة + الفئات المستعملة فعلياً في قاعدة البيانات */
    fun suggestedCategories(): List<String> {
        val used = byCategory.value.map { it.first }
        return (ExpenseRepoDefaults.CATEGORIES + used).distinct()
    }

    // ═══ : وظيفة 31 — قوالب المصروفات المتكررة ═══
    /** قوالب «إضافة سريعة» من كل المصروفات الحية (بلا فلتر الفئة) — كاشف نقي مُختبَر */
    val templates: StateFlow<List<ExpenseTemplates.Template>> = g.expenses.all()
        .map { list ->
            ExpenseTemplates.detect(
                // [P33-P8] كيان المصروف قروش — الواجهة النقية ExpenseLike تبقى بواجهتها الريالية
                list.map { ExpenseTemplates.ExpenseLike(note = it.note, category = it.category, amount = Money.fromPiasters(it.amount), date = it.date) },
                System.currentTimeMillis()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ═══ : وظيفة 32 — حد المصروف الشهري من DataStore (0 = بلا حد) ═══
    val expenseLimit: StateFlow<Double> = g.settings.settings
        .map { it.expenseMonthlyLimit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** تعيين حد المصروف الشهري — قيمة ≤0 تمسحه (إخفاء صادق) */
    fun setExpenseLimit(v: Double) = launchSafe { g.settings.setExpenseMonthlyLimit(v) }
}

/** فصل ثوابت الفئات كي لا يعتمد الـ VM على الـ Repo في الاستيراد الدائري */
private object ExpenseRepoDefaults {
    val CATEGORIES = listOf(
        "إيجار", "رواتب", "كهرباء وماء", "نقل وشحن", "تسويق",
        "صيانة", "مستلزمات", "اتصالات وإنترنت", "ضرائب", "أخرى"
    )
}
