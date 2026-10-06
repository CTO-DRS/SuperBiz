package com.superbiz.app.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.R9InsightsVM

/**
 * : منسّقات الأرقام بأرقام لاتينية Locale.US — كانت تتبع لغة النظام
 * فتظهر أرقام عربية-هندية داخل بطاقة وأرقام لاتينية في جارتها (نفس فئة R13-B20).
*/
/**
 * — بطاقات الميزات الذكية للموجة R9.
 * كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ) وتشرح آلية حسابها.
 *
 * [P36-M4-9 مُغلق في P39]: AppVM باراميتر صريح إلزامي في كل البطاقات الست — كان يُجلَب
 * داخلياً عبر viewModel(key="r9app") فتُنشأ نسخة AppVM إضافية لكل شاشة بدل نسخة النشاط
 * الواحدة. حُذف الافتراضي المؤقت بعد إكمال المواضع الستة كلها (Debts/Checks/Inventory/
 * Reports/Expenses/Home — بلوحات P36-M4-9) فلا نسخ AppVM إضافية من هذا المسار بعد اليوم.
 *
 * [P36-M4-10]: أثناء أول جولة حساب (VM.ready=false) تُرسم InsightCardSkeleton زجاجية
 * بدل الغياب الصامت؛ بعد الجاهزية عقد الصدق كما هو: بلا بيانات ⇐ لا بطاقة.
*/

// ═══════════ الرئيسية: السيولة تحت الضغط + الاحتياطي + الولاء + الاتجاه + الاحتفاظ ═══════════

@Composable
fun HomeR9Card(app: AppVM, r9: R9InsightsVM) {
    val st by r9.home.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1) السيولة تحت الضغط (B6)
        if (st.scenarios.isNotEmpty() && st.scenarios.any { it.runwayDays != 9999 }) {
            val worst = st.scenarios.last()
            SmartCardShell(stringResource(R.string.r9_stress_title), stringResource(R.string.r9_hint_generic), if (worst.runwayDays < 60) Red else Amber) {
                st.scenarios.forEach { sc ->
                    val label = when (sc.label) {
                        "BASE" -> stringResource(R.string.r9_stress_base)
                        "STRESS" -> stringResource(R.string.r9_stress_stress)
                        else -> stringResource(R.string.r9_stress_severe)
                    }
                    val value = if (sc.runwayDays >= 9999) "✓" else stringResource(R.string.r9_days_unit, sc.runwayDays)
                    KV(label, value, if (sc.runwayDays >= 9999) Green else if (sc.runwayDays < 60) Red else Amber)
                }
            }
        }
        // 2) الاحتياطي الموسمي (B7)
        if (st.reserve > 0) {
            SmartCardShell(stringResource(R.string.r9_reserve_title), stringResource(R.string.r9_hint_generic), Cyan) {
                KV(stringResource(R.string.r9_reserve_amount), m(st.reserve), Cyan)
            }
        }
        // 3) إشارة الاتجاه (B2)
        if (st.trendSignal != 0) {
            val up = st.trendSignal > 0
            SmartCardShell(stringResource(R.string.r9_trend_title), stringResource(R.string.r9_hint_generic), if (up) Green else Red) {
                Text(
                    stringResource(if (up) R.string.r9_trend_up else R.string.r9_trend_down),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = if (up) Green else Red,
                )
            }
        }
        // 4) مؤشر الولاء (B18)
        if (st.loyaltyIndex != null && st.loyaltyIndex != 0) {
            SmartCardShell(stringResource(R.string.r9_loyalty_title), stringResource(R.string.r9_hint_generic), if (st.loyaltyIndex!! >= 20) Green else if (st.loyaltyIndex!! <= -20) Red else Amber) {
                KV(stringResource(R.string.r9_loyalty_score), "${st.loyaltyIndex}", if (st.loyaltyIndex!! >= 0) Green else Red)
            }
        }
        // 5) منحنى الاحتفاظ (B17)
        val ret = st.retention
        if (ret != null && ret.cohortSize >= 3) {
            SmartCardShell(stringResource(R.string.r9_retention_title), stringResource(R.string.r9_retention_hint), Vio) {
                KV(stringResource(R.string.r9_retention_m1), "${(ret.m1 * 100).toInt()}%", if (ret.m1 >= 0.4) Green else Amber)
                KV(stringResource(R.string.r9_retention_m2), "${(ret.m2 * 100).toInt()}%", if (ret.m2 >= 0.3) Green else Amber)
            }
        }
    }
}

// ═══════════ المخزون: مخزون الأمان + الطلب المتقطع + تقادم الدفعات ═══════════

// [P36-M4-9]: AppVM باراميتر صريح إلزامي — كل المواضع الستة تمرره صراحة (الافتراضي المؤقت حُذف بعد إكمال المواقع)
@Composable
fun InventoryR9Card(r9: R9InsightsVM, app: AppVM) {
    val st by r9.inventory.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1) مخزون الأمان (B10)
        if (st.safety.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_safety_title), stringResource(R.string.r9_safety_hint), Amber) {
                st.safety.forEach { r ->
                    KV(r.name, stringResource(R.string.r9_safety_row, String.format(java.util.Locale.US, "%.0f", r.safety), String.format(java.util.Locale.US, "%.0f", -r.gap)), Amber)
                }
            }
        }
        // 2) الطلب المتقطع (B11)
        if (st.slow.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_croston_title), stringResource(R.string.r9_croston_hint), Cyan) {
                st.slow.forEach { r ->
                    KV(r.name, stringResource(R.string.r9_croston_row, String.format(java.util.Locale.US, "%.2f", r.forecast), r.saleDays), Cyan)
                }
            }
        }
        // 3) تقادم الدفعات (B12)
        if (st.aging.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_aging_title), stringResource(R.string.r9_aging_hint), Red) {
                st.aging.forEach { r ->
                    val label = if (r.band == "CRITICAL") stringResource(R.string.r9_aging_critical) else stringResource(R.string.r9_aging_risk)
                    KV(r.name, "$label · ${m(r.capital)}", Red)
                }
                KV(stringResource(R.string.r9_aging_total), m(st.agingCapital), Red)
            }
        }
    }
}

// ═══════════ التقارير: السعر الأمثل + الحزمة + VaR + الدقة + توازن الفئات + Holt + ساعات العمل ═══════════

// [P36-M4-9]: AppVM باراميتر صريح إلزامي — كل المواضع الستة تمرره صراحة (الافتراضي المؤقت حُذف بعد إكمال المواقع)
@Composable
fun ReportsR9Card(r9: R9InsightsVM, app: AppVM) {
    val st by r9.reports.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1) السعر الأمثل (B14)
        if (st.optimalPrices.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_optimal_title), stringResource(R.string.r9_optimal_hint), Vio) {
                st.optimalPrices.forEach { r ->
                    KV(r.name, stringResource(R.string.r9_optimal_row, m(r.current), m(r.optimal)), Vio)
                }
            }
        }
        // 2) عرض الحزمة (B15)
        if (st.bundle != null) {
            val b = st.bundle!!
            SmartCardShell(stringResource(R.string.r9_bundle_title), stringResource(R.string.r9_hint_generic), Green) {
                KV(b.names, stringResource(R.string.r9_bundle_row, m(b.price), m(b.listSum)), Green)
            }
        }
        // 3) الربح المعرض للخطر (B16)
        if (st.varLoss != null && st.varLoss!! > 0) {
            SmartCardShell(stringResource(R.string.r9_var_title), stringResource(R.string.r9_var_hint), Red) {
                KV(stringResource(R.string.r9_var_amount), m(st.varLoss!!), Red)
            }
        }
        // 4) دقة التنبؤ (B3)
        if (st.accuracyGrade != null && st.accuracyGrade!! > 0) {
            SmartCardShell(stringResource(R.string.r9_accuracy_title), stringResource(R.string.r9_hint_generic), Cyan) {
                val grade = st.accuracyGrade!!
                Text(
                    stringResource(
                        when (grade) {
                            4 -> R.string.r9_accuracy_4; 3 -> R.string.r9_accuracy_3
                            2 -> R.string.r9_accuracy_2; else -> R.string.r9_accuracy_1
                        }
                    ),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = if (grade >= 3) Green else Amber,
                )
            }
        }
        // 5) توازن الفئات (B13)
        if (st.catBalance.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_cats_title), stringResource(R.string.r9_cats_hint), Amber) {
                st.catBalance.forEach { c ->
                    val verdict = when (c.verdict) {
                        "OVER" -> stringResource(R.string.r9_cats_over)
                        "UNDER" -> stringResource(R.string.r9_cats_under)
                        else -> stringResource(R.string.r9_cats_ok)
                    }
                    KV(c.category, verdict, if (c.verdict == "OK") Green else Amber)
                }
            }
        }
        // 6) توقع الشهر القادم (B1)
        if (st.nextMonth != null && st.nextMonth!! > 0) {
            SmartCardShell(stringResource(R.string.r9_holt_title), stringResource(R.string.r9_hint_generic), Vio) {
                KV(stringResource(R.string.r9_holt_value), m(st.nextMonth!!), Vio)
            }
        }
        // 7) خطة ساعات العمل (B20)
        if (st.shifts.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_shifts_title), stringResource(R.string.r9_shifts_hint), Cyan) {
                st.shifts.take(5).forEach { (h, share) ->
                    KV(stringResource(R.string.r9_shifts_hour, h), stringResource(R.string.r9_shifts_val, String.format(java.util.Locale.US, "%.1f", share)), Cyan)
                }
            }
        }
    }
}

// ═══════════ الذمم: المسح الذكي + الحدود الائتمانية + الإجراء التالي ═══════════

// [P36-M4-9]: AppVM باراميتر صريح إلزامي — كل المواضع الستة تمرره صراحة (الافتراضي المؤقت حُذف بعد إكمال المواقع)
@Composable
fun DebtsR9Card(r9: R9InsightsVM, app: AppVM) {
    val st by r9.debts.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1) المسح الذكي (B5)
        if (st.sweep != null) {
            val s = st.sweep!!
            SmartCardShell(stringResource(R.string.r9_sweep_title), stringResource(R.string.r9_sweep_hint), Green) {
                KV(stringResource(R.string.r9_sweep_count), "${s.count}", Green)
                KV(stringResource(R.string.r9_sweep_used), m(s.usedCash), Green)
                if (s.numbers.isNotEmpty())
                    Text(s.numbers.take(6).joinToString(" · "), fontSize = 11.sp)
            }
        }
        // 2) الحدود الائتمانية (B8+B9)
        if (st.credits.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_credit_title), stringResource(R.string.r9_credit_hint), Vio) {
                st.credits.forEach { c ->
                    KV(c.name, stringResource(R.string.r9_credit_row, c.score, m(c.limit)), Vio)
                }
            }
        }
        // 3) الإجراء التالي (B19)
        if (st.actions.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r9_action_title), stringResource(R.string.r9_hint_generic), Amber) {
                st.actions.take(4).forEach { a ->
                    val label = when (a.action) {
                        "COLLECT" -> stringResource(R.string.r9_action_collect)
                        "WIN_BACK" -> stringResource(R.string.r9_action_winback)
                        "NURTURE" -> stringResource(R.string.r9_action_nurture)
                        "UPSELL" -> stringResource(R.string.r9_action_upsell)
                        else -> stringResource(R.string.r9_action_ok)
                    }
                    KV(a.name, label, Amber)
                }
            }
        }
    }
}

// ═══════════ الشيكات: تغطية المستحقات ═══════════

// [P36-M4-9]: AppVM باراميتر صريح إلزامي — كل المواضع الستة تمرره صراحة (الافتراضي المؤقت حُذف بعد إكمال المواقع)
@Composable
fun ChecksR9Card(r9: R9InsightsVM, app: AppVM) {
    val cov by r9.checkCoverage.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    // [P36-M4-10]: سكيلتون أثناء أول حساب، ثم عقد الصدق (لا تغطية ⇐ لا بطاقة)
    if (!ready) { InsightCardSkeleton(); return }
    if (cov == null) return
    val c = cov!!
    SmartCardShell(stringResource(R.string.r9_cover_title), stringResource(R.string.r9_cover_hint), if (c.ratioPct >= 100) Green else if (c.ratioPct >= 60) Amber else Red) {
        KV(stringResource(R.string.r9_cover_in), Money.format(c.incoming, symbol), Green)
        KV(stringResource(R.string.r9_cover_out), Money.format(c.outgoing, symbol), Red)
        KV(stringResource(R.string.r9_cover_ratio), "${c.ratioPct}%", if (c.ratioPct >= 100) Green else if (c.ratioPct >= 60) Amber else Red)
    }
}

// ═══════════ المصروفات: توقع بقية الشهر ═══════════

// [P36-M4-9]: AppVM باراميتر صريح إلزامي — كل المواضع الستة تمرره صراحة (الافتراضي المؤقت حُذف بعد إكمال المواقع)
@Composable
fun ExpensesR9Card(r9: R9InsightsVM, app: AppVM) {
    val st by r9.expenseOutlook.collectAsState()
    val ready by r9.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    // [P36-M4-10]: سكيلتون أثناء أول حساب، ثم عقد الصدق (لا توقع ⇐ لا بطاقة)
    if (!ready) { InsightCardSkeleton(); return }
    if (st == null || st!!.projectedRest <= 0) return
    val e = st!!
    SmartCardShell(stringResource(R.string.r9_expout_title), stringResource(R.string.r9_expout_hint), Amber) {
        KV(stringResource(R.string.r9_expout_sofar), Money.format(e.monthSoFar, symbol))
        KV(stringResource(R.string.r9_expout_rest), Money.format(e.projectedRest, symbol), Amber)
    }
}
