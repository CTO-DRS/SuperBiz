package com.superbiz.app.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.superbiz.app.R
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.R15InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R15 (20 ميزة عبر 8 شاشات).
 * عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)،
 * وتشرح آلية حسابها في سطر التلميح. النسب والأرقام بمنسّق d1/d2 بأرقام لاتينية
 * موحّدة (درس R13-B20).
*/

private fun d1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
private fun d2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

// ═══════════ الرئيسية: بايز/بِنفورد/كوزوم ═══════════

@Composable
fun HomeR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F1 احتمال عودة المشتري (بايز)
        st.bayes?.let { b ->
            SmartCardShell(stringResource(R.string.r15_bayes_title), stringResource(R.string.r15_bayes_note), Cyan) {
                KV(stringResource(R.string.r15_bayes_prior, d1(b.prior * 100.0)), "", Cyan)
                KV(stringResource(R.string.r15_bayes_post, d1(b.posterior * 100.0)), "", Green)
            }
        }
        // F2 قانون بِنفورد للرقم الأول
        st.benford?.let { b ->
            val color = if (b.deviation <= 0.15) Green else Amber
            SmartCardShell(stringResource(R.string.r15_benford_title), stringResource(R.string.r15_benford_note), color) {
                KV(stringResource(R.string.r15_benford_dev, d1(b.deviation * 100.0)), "", color)
                Text(
                    stringResource(if (b.deviation <= 0.15) R.string.r15_benford_ok else R.string.r15_benford_review),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F3 كشف انحراف المبيعات (كوزوم)
        st.cusum?.let { c ->
            val alarmed = c.firstAlarmIndex != null
            SmartCardShell(stringResource(R.string.r15_cusum_title), stringResource(R.string.r15_cusum_note), if (alarmed) Amber else Cyan) {
                KV(stringResource(R.string.r15_cusum_mean, d1(c.mean)), "", Cyan)
                Text(
                    stringResource(if (alarmed) R.string.r15_cusum_alarm else R.string.r15_cusum_stable),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (alarmed) Amber else Cyan,
                )
            }
        }
    }
}

// ═══════════ نقاط البيع: المرونة/زخم الأيام/أفق الاحتراق ═══════════

@Composable
fun PosR15Card(vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.pos.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F4 مرونة السعر القوسية
        st.elasticity?.let { e ->
            val color = when (e.elasticity.verdict) {
                "ELASTIC" -> Green
                "UNIT" -> Cyan
                else -> Amber
            }
            SmartCardShell(stringResource(R.string.r15_elast_title, e.productName), stringResource(R.string.r15_elast_note), color) {
                KV(stringResource(R.string.r15_elast_value, d2(e.elasticity.value)), "", color)
                Text(
                    stringResource(when (e.elasticity.verdict) {
                        "ELASTIC" -> R.string.r15_elast_elastic
                        "UNIT" -> R.string.r15_elast_unit
                        else -> R.string.r15_elast_inelastic
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F5 زخم الأيام صاعد/هابط (ماركوف)
        st.markov?.let { mk ->
            val color = if ((mk.stationaryUp) >= 0.5) Green else Amber
            SmartCardShell(stringResource(R.string.r15_markov_title), stringResource(R.string.r15_markov_note), color) {
                mk.upToUp?.let { KV(stringResource(R.string.r15_markov_uu, d1(it * 100.0)), "", Cyan) }
                mk.downToUp?.let { KV(stringResource(R.string.r15_markov_du, d1(it * 100.0)), "", Cyan) }
                KV(stringResource(R.string.r15_markov_pi, d1(mk.stationaryUp * 100.0)), "", color)
            }
        }
        // F6 أفق احتراق النقد
        st.runway?.let { r ->
            val color = when {
                r.burnDays >= 60 -> Green
                r.burnDays >= 30 -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r15_runway_title), stringResource(R.string.r15_runway_note), color) {
                KV(stringResource(R.string.r15_runway_days, r.burnDays), "", color)
                KV(stringResource(R.string.r15_runway_sigma, d1(r.bufferSigma)), "", Vio)
            }
        }
    }
}

// ═══════════ الفواتير: جريبس/IQR/مان-ويتني ═══════════

@Composable
fun InvoicesR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.invoices.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F7 الفاتورة الشاذة (جريبس)
        st.grubbs?.let { gr ->
            if (gr.isOutlier) {
                SmartCardShell(stringResource(R.string.r15_grubbs_title), stringResource(R.string.r15_grubbs_note), Amber) {
                    KV(stringResource(R.string.r15_grubbs_g, d2(gr.g), d2(gr.critical)), "", Amber)
                }
            }
        }
        // F8 سياج IQR وفواتير الحواف
        st.iqr?.let { f ->
            if (f.outliers.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r15_iqr_title), stringResource(R.string.r15_iqr_note), Amber) {
                    KV(stringResource(R.string.r15_iqr_high, m(f.highFence)), "", Vio)
                    KV(stringResource(R.string.r15_iqr_count, f.outliers.size), "", Amber)
                }
            }
        }
        // F9 مقارنة الشهرين (مان-ويتني)
        st.utest?.let { u ->
            val color = if (u.verdict == "DIFFERENT") Amber else Cyan
            SmartCardShell(stringResource(R.string.r15_utest_title), stringResource(R.string.r15_utest_note), color) {
                Text(
                    stringResource(if (u.verdict == "DIFFERENT") R.string.r15_utest_diff else R.string.r15_utest_same),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ المخزون: الشراء المشترك/80-20/زيبف ═══════════

@Composable
fun InventoryR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.inventory.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F10 أزواج الشراء المشترك
        st.coPurchase?.let { pairs ->
            if (pairs.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r15_copair_title), stringResource(R.string.r15_copair_note), Cyan) {
                    pairs.forEach { (a, b, n) ->
                        KV("$a + $b", "×$n", Vio)
                    }
                }
            }
        }
        // F11 عتبة 80/20
        st.pareto?.let { p ->
            SmartCardShell(stringResource(R.string.r15_pareto_title), stringResource(R.string.r15_pareto_note), Cyan) {
                KV(stringResource(R.string.r15_pareto_head, p.headCount, d1(p.headShare * 100.0)), "", Cyan)
                Text(
                    stringResource(if (p.headCount <= 5) R.string.r15_pareto_conc else R.string.r15_pareto_broad),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Cyan,
                )
            }
        }
        // F12 تركّز الرأس (زيبف)
        st.zipf?.let { z ->
            val excessive = z.headShare > z.expectedShare * 1.3
            SmartCardShell(stringResource(R.string.r15_zipf_title), stringResource(R.string.r15_zipf_note), if (excessive) Amber else Cyan) {
                KV(stringResource(R.string.r15_zipf_head, d1(z.headShare * 100.0), d1(z.expectedShare * 100.0)), "", if (excessive) Amber else Cyan)
            }
        }
    }
}

// ═══════════ التقارير: الدورة/نيلسون/ثيل-سن ═══════════

@Composable
fun ReportsR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.reports.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F13 الدورة السائدة
        st.cycle?.let { c ->
            SmartCardShell(stringResource(R.string.r15_cycle_title), stringResource(R.string.r15_cycle_note), Cyan) {
                KV(stringResource(R.string.r15_cycle_days, c.period), "", Cyan)
                KV(stringResource(R.string.r15_cycle_strength, d1(c.strength)), "", Vio)
            }
        }
        // F14 قواعد نيلسون
        st.nelson?.let { n ->
            val total = n.beyond3Sigma.size + n.run9SameSide.size + n.trend6.size
            if (total > 0) {
                val color = if (n.beyond3Sigma.isNotEmpty()) Amber else Cyan
                SmartCardShell(stringResource(R.string.r15_nelson_title), stringResource(R.string.r15_nelson_note), color) {
                    if (n.beyond3Sigma.isNotEmpty()) KV(stringResource(R.string.r15_nelson_r1, n.beyond3Sigma.size), "", Amber)
                    if (n.run9SameSide.isNotEmpty()) KV(stringResource(R.string.r15_nelson_r2, n.run9SameSide.size), "", Cyan)
                    if (n.trend6.isNotEmpty()) KV(stringResource(R.string.r15_nelson_r3, n.trend6.size), "", Cyan)
                }
            }
        }
        // F15 ميل ثيل-سن المتين
        st.theil?.let { t ->
            if (kotlin.math.abs(t.slope) > 0.0) {
                val color = if (t.slope > 0) Green else Amber
                SmartCardShell(stringResource(R.string.r15_theil_title), stringResource(R.string.r15_theil_note), color) {
                    KV(stringResource(R.string.r15_theil_slope, d2(t.slope)), "", color)
                }
            }
        }
    }
}

// ═══════════ الذمم: كابا/التسرب/RFM ═══════════

@Composable
fun DebtsR15Card(vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.debts.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F16 توافق الالتزام (كابا)
        st.kappa?.let { k ->
            val color = when (k.verdict) {
                "STRONG" -> Green
                "MODERATE" -> Cyan
                else -> Amber
            }
            SmartCardShell(stringResource(R.string.r15_kappa_title), stringResource(R.string.r15_kappa_note), color) {
                KV(stringResource(R.string.r15_kappa_value, d2(k.kappa)), "", color)
                Text(
                    stringResource(when (k.verdict) {
                        "STRONG" -> R.string.r15_kappa_strong
                        "MODERATE" -> R.string.r15_kappa_moderate
                        else -> R.string.r15_kappa_weak
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F17 احتمالية التسرب (لوجيت)
        st.churn?.let { ch ->
            val color = when (ch.verdict) {
                "LOW" -> Green
                "MEDIUM" -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r15_churn_title, ch.partyName), stringResource(R.string.r15_churn_note), color) {
                KV(stringResource(R.string.r15_churn_prob, d1(ch.probability * 100.0)), "", color)
            }
        }
        // F18 تسجيل RFM لأفضل عميل
        st.rfm?.let { r ->
            SmartCardShell(stringResource(R.string.r15_rfm_title), stringResource(R.string.r15_rfm_note), Cyan) {
                KV(stringResource(R.string.r15_rfm_scores, r.r, r.f, r.m), "", Cyan)
                KV(stringResource(R.string.r15_rfm_comp, d1(r.composite)), "", Vio)
            }
        }
    }
}

// ═══════════ الشيكات: VaR التاريخي ═══════════

@Composable
fun ChecksR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.checks.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F19 أسوأ خسارة يومية (VaR تاريخي 95%)
        st.var95?.let { v ->
            val color = when {
                v.varValue >= -0.004 -> Cyan
                v.varValue >= -200.0 -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r15_var_title), stringResource(R.string.r15_var_note), color) {
                KV(stringResource(R.string.r15_var_value, m(v.varValue)), "", color)
                KV(stringResource(R.string.r15_var_cvar, m(v.cvar)), "", Vio)
            }
        }
    }
}

// ═══════════ المصروفات: الكفاءة المركّبة ═══════════

@Composable
fun ExpensesR15Card(app: AppVM, vm: R15InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.expenses.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F20 الكفاءة الإجمالية المركّبة (تعبئة×تصريف×هامش)
        st.oee?.let { o ->
            val color = when {
                o.oee >= 0.4 -> Green
                o.oee >= 0.2 -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r15_oee_title), stringResource(R.string.r15_oee_note), color) {
                KV(stringResource(R.string.r15_oee_fill, d1(o.fillRate * 100.0)), "", Cyan)
                KV(stringResource(R.string.r15_oee_sell, d1(o.sellThrough * 100.0)), "", Cyan)
                KV(stringResource(R.string.r15_oee_margin, d1(o.marginPct * 100.0)), "", Vio)
                KV(stringResource(R.string.r15_oee_total, d1(o.oee * 100.0)), "", color)
            }
        }
    }
}
