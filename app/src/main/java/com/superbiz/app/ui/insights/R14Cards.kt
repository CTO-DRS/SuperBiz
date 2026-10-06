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
import com.superbiz.app.vm.R14InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R14 (20 ميزة عبر 8 شاشات).
 * عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)،
 * وتشرح آلية حسابها في سطر التلميح. القيم العشرية بمنسّق d1() بأرقام لاتينية
 * موحّدة (درس R13-B20) والنسبة بعلامة "٪" العربية داخل نصوص الموارد.
*/

private fun d1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

// ═══════════ الرئيسية: وينسور/هيرست/ك-س/الغطاء ═══════════

@Composable
fun HomeR14Card(app: AppVM, vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F1 المتوسط اليومي المتين (وينسور)
        st.winsor?.let { w ->
            SmartCardShell(stringResource(R.string.r14_winsor_title), stringResource(R.string.r14_winsor_note), Cyan) {
                KV(stringResource(R.string.r14_winsor_raw), m(w.rawMean), Cyan)
                KV(stringResource(R.string.r14_winsor_robust), m(w.winsorizedMean), Green)
                if (w.effectPct >= 10.0) {
                    KV(stringResource(R.string.r14_winsor_effect, d1(w.effectPct)), "", Amber)
                }
            }
        }
        // F2 مثابرة الاتجاه (هيرست)
        st.hurst?.let { h ->
            val color = when (h.verdict) {
                "TRENDING" -> Green
                "MEAN_REVERTING" -> Amber
                else -> Cyan
            }
            SmartCardShell(stringResource(R.string.r14_hurst_title), stringResource(R.string.r14_hurst_note), color) {
                KV(stringResource(R.string.r14_hurst_value, d1(h.h)), "", color)
                Text(
                    stringResource(when (h.verdict) {
                        "TRENDING" -> R.string.r14_hurst_trend
                        "MEAN_REVERTING" -> R.string.r14_hurst_revert
                        else -> R.string.r14_hurst_random
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F3 هل تغيّر توزيع الفواتير؟ (كولموغوروف-سميرنوف)
        st.ks?.let { k ->
            val color = if (k.verdict == "SHIFTED") Amber else Cyan
            SmartCardShell(stringResource(R.string.r14_ks_title), stringResource(R.string.r14_ks_note), color) {
                KV(stringResource(R.string.r14_ks_value, d1(k.d * 100.0), d1(k.critical * 100.0)), "", color)
                Text(
                    stringResource(if (k.verdict == "SHIFTED") R.string.r14_ks_shift else R.string.r14_ks_similar),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F4 غطاء التزامات 30 يوماً
        st.cover?.let { c ->
            val color = when (c.verdict) {
                "SAFE" -> Green
                "TIGHT" -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r14_cover_title), stringResource(R.string.r14_cover_note), color) {
                KV(stringResource(R.string.r14_cover_ratio, d1(c.ratio)), "", color)
                KV(stringResource(R.string.r14_cover_due), m(c.totalObligations), Vio)
                if (c.shortfall > 0.0) {
                    KV(stringResource(R.string.r14_cover_short), m(c.shortfall), Red)
                }
                Text(
                    stringResource(when (c.verdict) {
                        "SAFE" -> R.string.r14_cover_safe
                        "TIGHT" -> R.string.r14_cover_tight
                        else -> R.string.r14_cover_deficit
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ نقاط البيع: كسر الخصم/هامش القناتين/التراجع ═══════════

@Composable
fun PosR14Card(vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.pos.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F5 كسر الخصم تعادله
        st.campaign?.let { c ->
            val color = when (c.verdict) {
                "FEASIBLE" -> Green
                "HARD" -> Cyan
                "STEEP" -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r14_campaign_title), stringResource(R.string.r14_campaign_note), color) {
                if (c.requiredUpliftPct != null) {
                    KV(stringResource(R.string.r14_campaign_req, d1(c.requiredUpliftPct)), "", color)
                }
                Text(
                    stringResource(when (c.verdict) {
                        "FEASIBLE" -> R.string.r14_campaign_feasible
                        "HARD" -> R.string.r14_campaign_hard
                        "STEEP" -> R.string.r14_campaign_steep
                        else -> R.string.r14_campaign_never
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F6 هامش المحصّل كلياً مقابل المفتوح
        st.marginMix?.let { mx ->
            val color = when (mx.verdict) {
                "CASH_BETTER" -> Green
                "CREDIT_BETTER" -> Amber
                else -> Cyan
            }
            SmartCardShell(stringResource(R.string.r14_mix_title), stringResource(R.string.r14_mix_note), color) {
                KV(stringResource(R.string.r14_mix_cash, d1(mx.cashAvgPct)), "", Cyan)
                KV(stringResource(R.string.r14_mix_credit, d1(mx.creditAvgPct)), "", Vio)
                if (mx.verdict != "EQUAL") {
                    KV(stringResource(R.string.r14_mix_delta, d1(mx.deltaPct)), "", color)
                }
            }
        }
        // F7 أقصى تراجع في منحنى النقد
        st.drawdown?.let { d ->
            if (d.maxDrawdownPct >= 5.0) {
                val color = if (d.state == "UNDERWATER") Amber else Green
                SmartCardShell(stringResource(R.string.r14_dd_title), stringResource(R.string.r14_dd_note), color) {
                    KV(stringResource(R.string.r14_dd_max, d1(d.maxDrawdownPct)), "", color)
                    KV(stringResource(R.string.r14_dd_current, d1(d.currentDrawdownPct)), "", Cyan)
                    Text(
                        stringResource(if (d.state == "UNDERWATER") R.string.r14_dd_under else R.string.r14_dd_recovered),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                    )
                }
            }
        }
    }
}

// ═══════════ الفواتير: الشلّال/الجولات/الإنتروبي ═══════════

@Composable
fun InvoicesR14Card(app: AppVM, vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.invoices.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F8 توزيع أحدث دفعة على المفتوح (أقدم استحقاق أولاً)
        st.waterfall?.let { w ->
            SmartCardShell(stringResource(R.string.r14_wf_title), stringResource(R.string.r14_wf_note), Green) {
                KV(stringResource(R.string.r14_wf_party, w.partyName), m(w.payment), Green)
                w.rows.forEach { (num, amt) ->
                    KV(num, m(amt), Cyan)
                }
                if (w.closedCount > 0) {
                    KV(stringResource(R.string.r14_wf_closed, w.closedCount), "", Green)
                }
                if (w.unallocated > 0.004) {
                    KV(stringResource(R.string.r14_wf_left), m(w.unallocated), Amber)
                }
            }
        }
        // F9 اختبار الجولات للإيراد اليومي
        st.runs?.let { r ->
            val color = when (r.verdict) {
                "CLUSTERED" -> Amber
                "ALTERNATING" -> Cyan
                else -> Green
            }
            SmartCardShell(stringResource(R.string.r14_runs_title), stringResource(R.string.r14_runs_note), color) {
                KV(stringResource(R.string.r14_runs_count, r.runs, r.expectedRuns.toInt()), "", color)
                Text(
                    stringResource(when (r.verdict) {
                        "CLUSTERED" -> R.string.r14_runs_clustered
                        "ALTERNATING" -> R.string.r14_runs_alternating
                        else -> R.string.r14_runs_random
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F10 عدد الفئات الفعّال
        st.entropy?.let { e ->
            val color = when (e.verdict) {
                "CONCENTRATED" -> Amber
                "BALANCED" -> Cyan
                else -> Green
            }
            SmartCardShell(stringResource(R.string.r14_ent_title), stringResource(R.string.r14_ent_note), color) {
                KV(stringResource(R.string.r14_ent_value, d1(e.effectiveCount)), "", color)
                Text(
                    stringResource(when (e.verdict) {
                        "CONCENTRATED" -> R.string.r14_ent_conc
                        "BALANCED" -> R.string.r14_ent_bal
                        else -> R.string.r14_ent_div
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ المخزون: المصفوفة/التسرّب/النظافة/الأحزمة ═══════════

@Composable
fun InventoryR14Card(app: AppVM, vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.inventory.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F11 مصفوفة ABC×XYZ
        st.matrix?.let { mx ->
            val color = if (mx.treasures.isNotEmpty()) Green else Cyan
            SmartCardShell(stringResource(R.string.r14_abc_title), stringResource(R.string.r14_abc_note), color) {
                mx.counts.entries.sortedBy { it.key }.forEach { (cell, n) ->
                    KV(cell, n.toString(), when {
                        cell == "AX" -> Green
                        cell.endsWith("Z") -> Amber
                        else -> Cyan
                    })
                }
                if (mx.treasures.isNotEmpty()) {
                    KV(stringResource(R.string.r14_abc_treasure, mx.treasures.first()), "", Green)
                }
                if (mx.burdens.isNotEmpty()) {
                    KV(stringResource(R.string.r14_abc_burden, mx.burdens.first()), "", Amber)
                }
            }
        }
        // F12 تسرّب المخزون
        st.shrink?.let { s ->
            SmartCardShell(stringResource(R.string.r14_shrink_title), stringResource(R.string.r14_shrink_note), Red) {
                KV(stringResource(R.string.r14_shrink_value), m(s.lostValue), Red)
                s.pctOfCogs?.let {
                    KV(stringResource(R.string.r14_shrink_pct, d1(it)), "", Amber)
                }
                if (s.worstNames.isNotEmpty()) {
                    KV(stringResource(R.string.r14_shrink_worst, s.worstNames.first()), "", Red)
                }
            }
        }
        // F13 نظافة الكتالوج
        st.hygiene?.let { h ->
            val color = when {
                h.scorePct >= 90.0 -> Green
                h.scorePct >= 70.0 -> Cyan
                else -> Amber
            }
            SmartCardShell(stringResource(R.string.r14_hyg_title), stringResource(R.string.r14_hyg_note), color) {
                KV(stringResource(R.string.r14_hyg_score, d1(h.scorePct)), "", color)
                if (h.missingBarcode > 0) KV(stringResource(R.string.r14_hyg_bar, h.missingBarcode), "", Amber)
                if (h.missingCategory > 0) KV(stringResource(R.string.r14_hyg_cat, h.missingCategory), "", Amber)
                if (h.missingCost > 0) KV(stringResource(R.string.r14_hyg_cost, h.missingCost), "", Amber)
                if (h.missingPrice > 0) KV(stringResource(R.string.r14_hyg_price, h.missingPrice), "", Amber)
                if (h.negativeStock > 0) KV(stringResource(R.string.r14_hyg_neg, h.negativeStock), "", Red)
            }
        }
        // F14 أحزمة الهامش
        st.bands?.let { b ->
            val color = if (b.bands.negative + b.bands.thin > b.bands.good + b.bands.top) Amber else Green
            SmartCardShell(stringResource(R.string.r14_band_title), stringResource(R.string.r14_band_note), color) {
                KV(stringResource(R.string.r14_band_top, b.bands.top), "", Green)
                KV(stringResource(R.string.r14_band_good, b.bands.good), "", Green)
                KV(stringResource(R.string.r14_band_ok, b.bands.ok), "", Cyan)
                KV(stringResource(R.string.r14_band_low, b.bands.low), "", Vio)
                KV(stringResource(R.string.r14_band_thin, b.bands.thin), "", Amber)
                KV(stringResource(R.string.r14_band_neg, b.bands.negative), "", Red)
                if (b.worstNames.isNotEmpty()) {
                    KV(stringResource(R.string.r14_band_worst, b.worstNames.first()), "", Red)
                }
            }
        }
    }
}

// ═══════════ التقارير: بورا/المتحركون/التنبؤ المركّب ═══════════

@Composable
fun ReportsR14Card(app: AppVM, vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.reports.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F15 الترتيب المركّب (بورا)
        st.borda?.let { b ->
            if (b.names.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r14_borda_title), stringResource(R.string.r14_borda_note), Vio) {
                    b.names.take(5).forEachIndexed { i, (name, score) ->
                        KV(stringResource(R.string.r14_borda_rank, i + 1), "$name · $score", Vio)
                    }
                }
            }
        }
        // F16 صاعدو وهابطو الرتب
        st.movers?.let { mv ->
            if (mv.first.isNotEmpty() || mv.second.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r14_mover_title), stringResource(R.string.r14_mover_note), Cyan) {
                    mv.first.forEach { u ->
                        KV(u.name, stringResource(R.string.r14_mover_up, u.delta), Green)
                    }
                    mv.second.forEach { u ->
                        KV(u.name, stringResource(R.string.r14_mover_down, u.delta), Red)
                    }
                }
            }
        }
        // F17 تنبؤ مركّب موزون بالخطأ
        st.blend?.let { b ->
            SmartCardShell(stringResource(R.string.r14_blend_title), stringResource(R.string.r14_blend_note), Cyan) {
                b.forecast.forEachIndexed { i, v ->
                    KV(stringResource(R.string.r14_blend_week, i + 1), m(v), Cyan)
                }
                KV(stringResource(R.string.r14_blend_w, (b.wHolt * 100).toInt()), "", Vio)
            }
        }
    }
}

// ═══════════ الذمم: عمر الذمم المرجّح ═══════════

@Composable
fun DebtsR14Card(vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.debts.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F18 عمر الذمم المرجّح بالمبلغ
        st.staleness?.let { s ->
            val color = when (s.verdict) {
                "FRESH" -> Green
                "AGING" -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r14_stale_title), stringResource(R.string.r14_stale_note), color) {
                KV(stringResource(R.string.r14_stale_mean, s.weightedMeanDays.toInt()), "", color)
                KV(stringResource(R.string.r14_stale_med, s.weightedMedianDays.toInt()), "", Cyan)
                Text(
                    stringResource(when (s.verdict) {
                        "FRESH" -> R.string.r14_stale_fresh
                        "AGING" -> R.string.r14_stale_aging
                        else -> R.string.r14_stale_stale
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ الشيكات: أفق التأجيل ═══════════

@Composable
fun ChecksR14Card(vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.checks.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F19 أفق تأجيل الشيكات
        st.terms?.let { t ->
            val color = when (t.verdict) {
                "SHORT" -> Green
                "MEDIUM" -> Cyan
                else -> Amber
            }
            SmartCardShell(stringResource(R.string.r14_terms_title), stringResource(R.string.r14_terms_note), color) {
                KV(stringResource(R.string.r14_terms_med, t.medianDays.toInt()), "", color)
                KV(stringResource(R.string.r14_terms_p90, t.p90Days.toInt()), "", Cyan)
                KV(stringResource(R.string.r14_terms_max, t.maxDays.toInt()), "", Amber)
            }
        }
    }
}

// ═══════════ المصروفات: احتياطي الثوابت ═══════════

@Composable
fun ExpensesR14Card(app: AppVM, vm: R14InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.expenses.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F20 احتياطي الثوابت اليومي
        st.reserve?.let { r ->
            val color = when (r.verdict) {
                "SAFE" -> Green
                "TIGHT" -> Amber
                else -> Red
            }
            SmartCardShell(stringResource(R.string.r14_res_title), stringResource(R.string.r14_res_note), color) {
                KV(stringResource(R.string.r14_res_day), m(r.perWorkingDay), color)
                KV(stringResource(R.string.r14_res_cov, d1(r.coverage)), "", Cyan)
                Text(
                    stringResource(when (r.verdict) {
                        "SAFE" -> R.string.r14_res_safe
                        "TIGHT" -> R.string.r14_res_tight
                        else -> R.string.r14_res_strained
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}
