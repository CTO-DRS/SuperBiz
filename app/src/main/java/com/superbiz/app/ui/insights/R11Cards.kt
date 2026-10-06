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
import com.superbiz.app.vm.R11InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R11 (20 ميزة عبر 8 شاشات،
 * أول مرة تشمل POS والفواتير). عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب
 * بياناتها (صدق الفراغ)، وتشرح آلية حسابها في سطر التلميح.
*/

@Composable
private fun runwayColor(days: Double?) = when {
    days == null -> Green
    days < 30.0 -> Red
    days < 60.0 -> Amber
    else -> Green
}

@Composable
private fun paceColor(verdict: String) = when (verdict) {
    "AHEAD" -> Green
    "ON_TREND" -> Cyan
    "SLOW" -> Amber
    else -> Red
}

// ═══════════ الرئيسية: صمود/ضريبة/زخم/عملات ═══════════

@Composable
fun HomeR11Card(app: AppVM, vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B1 معدل الحرق وأيام الصمود
        st.runway?.let { r ->
            SmartCardShell(stringResource(R.string.r11_runway_title), stringResource(R.string.r11_hint_generic), runwayColor(r.runwayDays)) {
                KV(stringResource(R.string.r11_runway_burn), m(r.dailyBurn), runwayColor(r.runwayDays))
                if (r.verdict == "SURPLUS") {
                    Text(stringResource(R.string.r11_runway_surplus), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Green)
                } else {
                    r.runwayDays?.let { d ->
                        KV(stringResource(R.string.r11_runway_days, d.toInt()), "", runwayColor(r.runwayDays))
                        Text(
                            stringResource(when {
                                d < 30.0 -> R.string.r11_runway_critical
                                d < 60.0 -> R.string.r11_runway_tight
                                else -> R.string.r11_runway_ok
                            }),
                            fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = runwayColor(r.runwayDays),
                        )
                    }
                }
            }
        }
        // B2 موقع الضريبة
        st.vat?.let { v ->
            SmartCardShell(stringResource(R.string.r11_vat_title), stringResource(R.string.r11_hint_generic), if (v.position == "REFUND") Cyan else if (v.position == "PAYABLE") Amber else Green) {
                KV(stringResource(R.string.r11_vat_net), m(v.net), if (v.position == "PAYABLE") Amber else Green)
                Text(
                    stringResource(when (v.position) {
                        "PAYABLE" -> R.string.r11_vat_payable
                        "REFUND" -> R.string.r11_vat_refund
                        else -> R.string.r11_vat_neutral
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (v.position == "PAYABLE") Amber else Green,
                )
            }
        }
        // B3 زخم الإيراد
        st.momentum?.let { mom ->
            val color = when {
                mom > 2.0 -> Green
                mom < -2.0 -> Red
                else -> Cyan
            }
            SmartCardShell(stringResource(R.string.r11_momentum_title), stringResource(R.string.r11_hint_generic), color) {
                KV(stringResource(R.string.r11_momentum_value, mom), "", color)
                Text(
                    stringResource(when {
                        mom > 2.0 -> R.string.r11_momentum_up
                        mom < -2.0 -> R.string.r11_momentum_down
                        else -> R.string.r11_momentum_flat
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // B4 التعرض للعملات
        st.fx?.let { fx ->
            SmartCardShell(stringResource(R.string.r11_fx_title), stringResource(R.string.r11_hint_generic), if (fx.verdict == "CONCENTRATED") Amber else Cyan) {
                KV(stringResource(R.string.r11_fx_total), m(fx.totalBase), if (fx.verdict == "CONCENTRATED") Amber else Cyan)
                fx.rows.take(2).forEach { r ->
                    KV(r.currency, stringResource(R.string.r11_fx_row, r.sharePct.toInt()), Cyan)
                }
            }
        }
    }
}

// ═══════════ نقاط البيع: إيقاع اليوم/تسريب الخصم ═══════════

@Composable
fun PosR11Card(app: AppVM, vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.pos.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B5 إيقاع اليوم
        st.pace?.let { p ->
            SmartCardShell(stringResource(R.string.r11_pace_title), stringResource(R.string.r11_hint_generic), paceColor(p.verdict)) {
                KV(stringResource(R.string.r11_pace_expected), m(p.expectedSoFar), paceColor(p.verdict))
                KV(stringResource(R.string.r11_pace_pct, p.pacePct), "", paceColor(p.verdict))
                Text(
                    stringResource(when (p.verdict) {
                        "AHEAD" -> R.string.r11_pace_ahead
                        "ON_TREND" -> R.string.r11_pace_on_trend
                        "SLOW" -> R.string.r11_pace_slow
                        else -> R.string.r11_pace_critical
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = paceColor(p.verdict),
                )
            }
        }
        // B6 تسريب الخصم
        st.leak?.let { lk ->
            if (lk.rows.isNotEmpty() && (lk.overallPct ?: 0.0) > 0.0) {
                SmartCardShell(stringResource(R.string.r11_leak_title), stringResource(R.string.r11_hint_generic), Amber) {
                    KV(stringResource(R.string.r11_leak_overall, lk.overallPct ?: 0.0), "", Amber)
                    lk.rows.forEach { r ->
                        KV(r.party, stringResource(R.string.r11_leak_row, r.discountPct.toInt()), Amber)
                    }
                }
            }
        }
    }
}

// ═══════════ الفواتير: متوسط الفاتورة/تكرار/انحراف ═══════════

@Composable
fun InvoicesR11Card(app: AppVM, vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.invoices.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B7 اتجاه متوسط الفاتورة
        st.aov?.let { a ->
            val color = when (a.direction) { "RISING" -> Green; "FALLING" -> Red; else -> Cyan }
            SmartCardShell(stringResource(R.string.r11_aov_title), stringResource(R.string.r11_hint_generic), color) {
                KV(stringResource(R.string.r11_aov_latest), m(a.latest), color)
                a.momentumPct?.let { mom ->
                    KV(stringResource(R.string.r11_aov_momentum, mom), "", color)
                }
            }
        }
        // B8 اشتباه تكرار
        if (st.duplicates.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r11_dup_title), stringResource(R.string.r11_dup_note), Vio) {
                st.duplicates.forEach { s ->
                    KV(stringResource(R.string.r11_dup_row, s.partyName, s.dayGap.toInt()), m(s.total), Vio)
                }
            }
        }
        // B9 انحراف التقريب
        st.drift?.let { d ->
            if (kotlin.math.abs(d.totalDrift) > 0.005 || d.skewedRows > 0) {
                SmartCardShell(stringResource(R.string.r11_drift_title), stringResource(R.string.r11_drift_note), if (kotlin.math.abs(d.totalDrift) > 1.0) Amber else Cyan) {
                    KV(stringResource(R.string.r11_drift_total), m(d.totalDrift), Cyan)
                    KV(stringResource(R.string.r11_drift_rows, d.skewedRows), "", Cyan)
                }
            }
        }
    }
}

// ═══════════ المخزون: إعادة الطلب/مردود الفئات ═══════════

@Composable
fun InventoryR11Card(vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.inventory.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B10 خطة إعادة الطلب
        if (st.reorder.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r11_reorder_title), stringResource(R.string.r11_reorder_note), Vio) {
                st.reorder.forEach { r ->
                    val color = when (r.verdict) { "OUT" -> Red; "URGENT" -> Amber; else -> Cyan }
                    KV(r.name, stringResource(R.string.r11_reorder_row,
                        // [P20-FIX agent16]: orderQty Double بعد round2 كان يعرض «12.0» — صيغة صحيحة بلا كسور
                        String.format(java.util.Locale.US, "%.0f", r.orderQty),
                        when (r.verdict) {
                        "OUT" -> stringResource(R.string.r11_reorder_out)
                        "URGENT" -> stringResource(R.string.r11_reorder_urgent)
                        else -> stringResource(R.string.r11_reorder_soon)
                    }), color)
                }
            }
        }
        // B11 مردود الفئات
        if (st.gmroi.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r11_gmroi_title), stringResource(R.string.r11_gmroi_note), Cyan) {
                st.gmroi.forEach { r ->
                    KV(r.category, stringResource(R.string.r11_gmroi_row, r.gmroiAnnual.toInt()), if (r.gmroiAnnual >= 12.0) Green else Amber)
                }
            }
        }
    }
}

// ═══════════ التقارير: اتجاه/هامش/انصراف ═══════════

@Composable
fun ReportsR11Card(vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.reports.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B12 اتجاه الإيراد الشهري
        st.trend?.let { t ->
            val color = when (t.verdict) { "RISING" -> Green; "FALLING" -> Red; else -> Cyan }
            SmartCardShell(stringResource(R.string.r11_trend_title), stringResource(R.string.r11_hint_generic), color) {
                Text(
                    stringResource(when (t.verdict) {
                        "RISING" -> R.string.r11_trend_rising
                        "FALLING" -> R.string.r11_trend_falling
                        else -> R.string.r11_trend_stable
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
                KV(stringResource(R.string.r11_trend_r2, (t.r2 * 100).toInt()), "", color)
            }
        }
        // B13 تدقيق أرضية الهامش
        st.marginAudit?.let { a ->
            if (a.belowCount > 0) {
                SmartCardShell(stringResource(R.string.r11_marginaudit_title), stringResource(R.string.r11_marginaudit_note), Amber) {
                    KV(stringResource(R.string.r11_marginaudit_count, a.belowCount, a.validCount), "", Amber)
                    a.offenders.take(3).forEach { o ->
                        KV(o.name, stringResource(R.string.r11_marginaudit_row, o.marginPct.toInt()), Red)
                    }
                }
            }
        }
        // B14 عملاء صامتون
        if (st.churn.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r11_churn_title), stringResource(R.string.r11_hint_generic), Red) {
                st.churn.forEach { c ->
                    KV(c.name, stringResource(R.string.r11_churn_row, c.silenceDays.toInt(), c.score), if (c.verdict == "HIGH") Red else Amber)
                }
            }
        }
    }
}

// ═══════════ الذمم: تأخر الأقساط/شرائح العملاء ═══════════

@Composable
fun DebtsR11Card(vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.debts.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B15 شرائح تأخر الأقساط
        st.delinquency?.let { d ->
            if (d.buckets.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r11_delinq_title), stringResource(R.string.r11_delinq_note), Amber) {
                    KV(stringResource(R.string.r11_delinq_rate, d.ratePct.toInt()), "", if (d.ratePct >= 30.0) Red else Amber)
                    d.buckets.forEach { b ->
                        val color = when (b.label) { "1-15" -> Amber; else -> Red }
                        KV(stringResource(R.string.r11_delinq_row, b.label, b.count), "", color)
                    }
                }
            }
        }
        // B16 شرائح RFM
        if (st.segments.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r11_segments_title), stringResource(R.string.r11_hint_generic), Vio) {
                st.segments.take(4).forEach { s ->
                    KV(stringResource(when (s.segment) {
                        "CHAMPION" -> R.string.r11_seg_champion
                        "LOYAL" -> R.string.r11_seg_loyal
                        "NEW" -> R.string.r11_seg_new
                        "PROMISING" -> R.string.r11_seg_promising
                        "AT_RISK" -> R.string.r11_seg_at_risk
                        "SLEEPING" -> R.string.r11_seg_sleeping
                        else -> R.string.r11_seg_lost
                    }), stringResource(R.string.r11_segments_row, s.count), Vio)
                }
            }
        }
    }
}

// ═══════════ الشيكات: ارتجاع/فجوة نقدية ═══════════

@Composable
fun ChecksR11Card(app: AppVM, vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.checks.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B17 إحصاء الارتجاع
        st.bounce?.let { b ->
            if (b.rows.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r11_bounce_title), stringResource(R.string.r11_hint_generic), Red) {
                    b.overallPct?.let { o ->
                        KV(stringResource(R.string.r11_bounce_overall, o.toInt()), "", if (o >= 10.0) Red else Amber)
                    }
                    b.rows.forEach { r ->
                        KV(r.party, stringResource(R.string.r11_bounce_row, r.bounced, r.issued), Red)
                    }
                }
            }
        }
        // B18 الفجوة النقدية
        st.gap?.let { gp ->
            if (gp.minBalance < 0.0 || gp.deficitDays > 0) {
                SmartCardShell(stringResource(R.string.r11_gap_title), stringResource(R.string.r11_gap_note), if (gp.minBalance < 0.0) Red else Amber) {
                    KV(stringResource(R.string.r11_gap_min), m(gp.minBalance), if (gp.minBalance < 0.0) Red else Amber)
                    KV(stringResource(R.string.r11_gap_end), m(gp.endBalance), Cyan)
                    KV(stringResource(R.string.r11_gap_deficit, gp.deficitDays), "", Amber)
                }
            }
        }
    }
}

// ═══════════ المصروفات: موازنة/ثابت-متغير ═══════════

@Composable
fun ExpensesR11Card(app: AppVM, vm: R11InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.expenses.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B19 الموازنة الضمنية
        st.budget?.let { b ->
            val overs = b.rows.filter { it.over }
            if (b.rows.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r11_budget_title), stringResource(R.string.r11_budget_note), if (overs.isNotEmpty()) Amber else Green) {
                    overs.take(3).forEach { r ->
                        val pct = r.overPct
                        KV(r.category, if (pct != null) stringResource(R.string.r11_budget_row, pct.toInt()) else stringResource(R.string.r11_budget_new), Amber)
                    }
                    KV(stringResource(R.string.r11_budget_total), m(b.totalActual), if (b.totalActual > b.totalBaseline) Amber else Green)
                }
            }
        }
        // B20 ثابت/متغير
        st.split?.let { s ->
            SmartCardShell(stringResource(R.string.r11_split_title), stringResource(R.string.r11_split_note), Cyan) {
                KV(stringResource(R.string.r11_split_fixed), stringResource(R.string.r11_split_pct, s.fixedSharePct.toInt()), Cyan)
                KV(stringResource(R.string.r11_split_variable), stringResource(R.string.r11_split_pct, s.variableSharePct.toInt()), Vio)
            }
        }
    }
}
