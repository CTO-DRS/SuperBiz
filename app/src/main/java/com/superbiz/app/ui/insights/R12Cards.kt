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
import com.superbiz.app.vm.R12InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R12 (20 ميزة عبر 8 شاشات).
 * عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)،
 * وتشرح آلية حسابها في سطر التلميح.
*/

// ═══════════ الرئيسية: مقارنة الفترات/مزيج الدفع/EWMA/الدوران ═══════════

@Composable
fun HomeR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F1 مقارنة الفترات
        st.compare?.let { c ->
            val color = when (c.verdict) { "UP" -> Green; "DOWN" -> Red; else -> Cyan }
            SmartCardShell(stringResource(R.string.r12_compare_title), stringResource(R.string.r12_hint_generic), color) {
                KV(stringResource(R.string.r12_compare_current), m(c.current), color)
                c.momPct?.let { KV(stringResource(R.string.r12_compare_mom, it), "", color) }
                c.yoyPct?.let { KV(stringResource(R.string.r12_compare_yoy, it), "", Cyan) }
                Text(
                    stringResource(when (c.verdict) {
                        "UP" -> R.string.r12_compare_up
                        "DOWN" -> R.string.r12_compare_down
                        "NEW" -> R.string.r12_compare_new
                        else -> R.string.r12_compare_flat
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F2 مزيج طرق التحصيل
        st.mix?.let { mx ->
            SmartCardShell(stringResource(R.string.r12_mix_title), stringResource(R.string.r12_mix_note), if (mx.verdict == "CHECK_HEAVY") Amber else Cyan) {
                KV(stringResource(R.string.r12_mix_cash), stringResource(R.string.r12_pct, mx.cashPct.toInt()), Cyan)
                KV(stringResource(R.string.r12_mix_check), stringResource(R.string.r12_pct, mx.checkPct.toInt()), if (mx.verdict == "CHECK_HEAVY") Amber else Cyan)
                KV(stringResource(R.string.r12_mix_other), stringResource(R.string.r12_pct, mx.otherPct.toInt()), Vio)
            }
        }
        // F3 استقرار الإيراد EWMA
        st.ewma?.let { w ->
            if (w.verdict == "SHIFT") {
                SmartCardShell(stringResource(R.string.r12_ewma_title), stringResource(R.string.r12_ewma_note), Amber) {
                    w.breachIndex?.let { KV(stringResource(R.string.r12_ewma_breach, it + 1), "", Amber) }
                    Text(stringResource(R.string.r12_ewma_shift), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Amber)
                }
            } else {
                SmartCardShell(stringResource(R.string.r12_ewma_title), stringResource(R.string.r12_ewma_note), Green) {
                    Text(stringResource(R.string.r12_ewma_stable), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            }
        }
        // F4 دوران المخزون
        st.turn?.let { t ->
            val color = when (t.verdict) { "FAST" -> Green; "OK" -> Cyan; else -> Amber }
            SmartCardShell(stringResource(R.string.r12_turn_title), stringResource(R.string.r12_turn_note), color) {
                KV(stringResource(R.string.r12_turn_turns, t.turns), "", color)
                KV(stringResource(R.string.r12_turn_days, t.daysOnHand.toInt()), "", color)
                Text(
                    stringResource(when (t.verdict) {
                        "FAST" -> R.string.r12_turn_fast
                        "OK" -> R.string.r12_turn_ok
                        else -> R.string.r12_turn_slow
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ نقاط البيع: نافذة النشاط/حجم السلة ═══════════

@Composable
fun PosR12Card(vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.pos.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F5 نافذة ساعات النشاط
        st.span?.let { s ->
            val color = when (s.verdict) { "FOCUSED" -> Green; "NORMAL" -> Cyan; else -> Amber }
            SmartCardShell(stringResource(R.string.r12_span_title), stringResource(R.string.r12_span_note), color) {
                KV(stringResource(R.string.r12_span_row, s.startHour, s.endHour, s.spanHours), "", color)
                Text(
                    stringResource(when (s.verdict) {
                        "FOCUSED" -> R.string.r12_span_focused
                        "NORMAL" -> R.string.r12_span_normal
                        else -> R.string.r12_span_spread
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F6 اتجاه حجم السلة
        st.cart?.let { c ->
            val color = when (c.direction) { "GROWING" -> Green; "SHRINKING" -> Red; else -> Cyan }
            SmartCardShell(stringResource(R.string.r12_cart_title), stringResource(R.string.r12_cart_note), color) {
                KV(stringResource(R.string.r12_cart_latest, c.latest), "", color)
                c.momentumPct?.let { KV(stringResource(R.string.r12_cart_momentum, it), "", color) }
            }
        }
    }
}

// ═══════════ الفواتير: الإلغاء/الزيادة/التوقيت ═══════════

@Composable
fun InvoicesR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.invoices.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F7 اتجاه إلغاء الفواتير
        st.voids?.let { v ->
            if (v.recent.voided > 0 || v.rising) {
                val color = when (v.verdict) { "ALARM" -> Red; "WATCH" -> Amber; else -> Cyan }
                SmartCardShell(stringResource(R.string.r12_void_title), stringResource(R.string.r12_void_note), color) {
                    KV(stringResource(R.string.r12_void_pct, v.recent.voided, v.recent.total, v.recent.pct), "", color)
                    Text(
                        stringResource(if (v.rising) R.string.r12_void_rising else R.string.r12_void_calm),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                    )
                }
            }
        }
        // F8 فواتير مسددة زيادة
        if (st.overpaid.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_overpaid_title), stringResource(R.string.r12_overpaid_note), Amber) {
                st.overpaid.forEach { o ->
                    KV(stringResource(R.string.r12_overpaid_row, o.invoiceId), m(o.excess), Amber)
                }
            }
        }
        // F9 انتظام التحصيل
        st.timing?.let { t ->
            val color = when (t.verdict) { "PROMPT" -> Green; "NORMAL" -> Cyan; else -> Amber }
            SmartCardShell(stringResource(R.string.r12_timing_title), stringResource(R.string.r12_timing_note), color) {
                KV(stringResource(R.string.r12_timing_early), stringResource(R.string.r12_pct, t.earlyPct.toInt()), Green)
                KV(stringResource(R.string.r12_timing_ontime), stringResource(R.string.r12_pct, t.onTimePct.toInt()), Cyan)
                KV(stringResource(R.string.r12_timing_late), stringResource(R.string.r12_pct, t.latePct.toInt()), if (t.latePct > 30.0) Red else Cyan)
            }
        }
    }
}

// ═══════════ المخزون: أرباع/لم تُبع/بطيء/فئات ═══════════

@Composable
fun InventoryR12Card(vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.inventory.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F10 أرباع إيراد المنتجات
        if (st.quartiles.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_quart_title), stringResource(R.string.r12_quart_note), Vio) {
                st.quartiles.filter { it.quartile == 1 }.take(2).forEach { r ->
                    KV(r.name, stringResource(R.string.r12_quart_top), Green)
                }
                st.quartiles.filter { it.quartile == 4 }.take(2).forEach { r ->
                    KV(r.name, stringResource(R.string.r12_quart_bottom), Red)
                }
            }
        }
        // F11 منتجات لم تُبع أبداً
        if (st.neverSold.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_never_title), stringResource(R.string.r12_never_note), Amber) {
                st.neverSold.forEach { r ->
                    KV(r.name, stringResource(R.string.r12_never_qty, r.stockQty.toInt()), Amber)
                }
            }
        }
        // F12 أبطأ الواردات بيعاً
        if (st.stale.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_stale_title), stringResource(R.string.r12_stale_note), Red) {
                st.stale.forEach { r ->
                    KV(r.name, stringResource(R.string.r12_stale_pct, r.soldPct.toInt()), Red)
                }
            }
        }
        // F13 فئات بلا مبيعات
        if (st.gaps.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_gaps_title), stringResource(R.string.r12_gaps_note), Vio) {
                st.gaps.forEach { gp ->
                    KV(gp.category, stringResource(R.string.r12_gaps_row, gp.products), Vio)
                }
            }
        }
    }
}

// ═══════════ التقارير: زحف التكلفة/أثر السعر/التركّز ═══════════

@Composable
fun ReportsR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.reports.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F14 زحف تكلفة الشراء
        st.creep?.let { c ->
            val color = when (c.verdict) { "SPIKE" -> Red; "CREEPING" -> Amber; "FALLING" -> Cyan; else -> Green }
            SmartCardShell(stringResource(R.string.r12_creep_title), stringResource(R.string.r12_creep_note), color) {
                KV(stringResource(R.string.r12_creep_first), m(c.first), Cyan)
                KV(stringResource(R.string.r12_creep_last), m(c.last), color)
                KV(stringResource(R.string.r12_creep_slope, c.slopePctPerMonth), "", color)
                Text(
                    stringResource(when (c.verdict) {
                        "SPIKE" -> R.string.r12_creep_spike
                        "CREEPING" -> R.string.r12_creep_creeping
                        "FALLING" -> R.string.r12_creep_falling
                        else -> R.string.r12_creep_stable
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F15 أثر تغيير السعر
        if (st.uplifts.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_uplift_title), stringResource(R.string.r12_uplift_note), Vio) {
                st.uplifts.forEach { u ->
                    val color = when (u.verdict) { "WIN" -> Green; "LOSS" -> Red; else -> Cyan }
                    KV(u.name, stringResource(when (u.verdict) {
                        "WIN" -> R.string.r12_uplift_win
                        "LOSS" -> R.string.r12_uplift_loss
                        else -> R.string.r12_uplift_neutral
                    }, u.delta), color)
                }
            }
        }
        // F16 انجراف تركّز العملاء
        st.drift?.let { d ->
            val color = when (d.verdict) { "CONCENTRATING" -> Amber; "DIVERSIFYING" -> Green; else -> Cyan }
            SmartCardShell(stringResource(R.string.r12_drift_title), stringResource(R.string.r12_drift_note), color) {
                // [P20-FIX agent16]: HHI معياري 0..1 — العرض ×100 كان يعطي «33 نقطة» بجانب
                // نفس المقياس في R10 (Σpct² = 0..10000) — وحّدنا على المقياس القياسي ×10000
                KV(stringResource(R.string.r12_drift_hhi, (d.hhiNow * 10000).toInt(), (d.hhiBefore * 10000).toInt()), "", color)
                KV(stringResource(R.string.r12_drift_top, d.topShareNowPct.toInt(), d.topShareBeforePct.toInt()), "", color)
                Text(
                    stringResource(when (d.verdict) {
                        "CONCENTRATING" -> R.string.r12_drift_concentrating
                        "DIVERSIFYING" -> R.string.r12_drift_diversifying
                        else -> R.string.r12_drift_stable
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ الذمم: التسوية/سلّم المطالبة ═══════════

@Composable
fun DebtsR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.debts.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F17 أشهر التسوية
        st.payoff?.let { p ->
            val color = when (p.verdict) { "SHORT" -> Green; "MEDIUM" -> Amber; else -> Red }
            SmartCardShell(stringResource(R.string.r12_payoff_title), stringResource(R.string.r12_payoff_note), color) {
                KV(stringResource(R.string.r12_payoff_open), m(p.totalOpen), Cyan)
                KV(stringResource(R.string.r12_payoff_months, p.months), "", color)
                KV(stringResource(R.string.r12_payoff_monthly), m(p.avgMonthlyPay), Cyan)
            }
        }
        // F18 سلّم المطالبة
        st.dunning?.let { d ->
            if (d.rows.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r12_dunning_title), stringResource(R.string.r12_dunning_note), if (d.worst != null) Amber else Green) {
                    d.rows.forEach { r ->
                        val color = when (r.stage) {
                            "CURRENT" -> Green
                            "REMIND" -> Cyan
                            "URGE" -> Amber
                            else -> Red
                        }
                        KV(stringResource(when (r.stage) {
                            "CURRENT" -> R.string.r12_stage_current
                            "REMIND" -> R.string.r12_stage_remind
                            "URGE" -> R.string.r12_stage_urge
                            "FINAL" -> R.string.r12_stage_final
                            else -> R.string.r12_stage_collect
                        }), stringResource(R.string.r12_dunning_row, r.count, m(r.amount)), color)
                    }
                }
            }
        }
    }
}

// ═══════════ الشيكات: مدفوعات يتيمة ═══════════

@Composable
fun ChecksR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.checks.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F19 مدفوعات تشير لفواتير مفقودة
        if (st.orphans.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r12_orphan_title), stringResource(R.string.r12_orphan_note), Red) {
                st.orphans.forEach { o ->
                    KV(stringResource(R.string.r12_orphan_row, o.paymentId, o.invoiceId), m(o.amount), Red)
                }
            }
        }
    }
}

// ═══════════ المصروفات: انزياح CUSUM ═══════════

@Composable
fun ExpensesR12Card(app: AppVM, vm: R12InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.expenses.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F20 انزياح منحنى المصروفات
        st.cusum?.let { c ->
            val color = when (c.verdict) { "RISING" -> Red; "FALLING" -> Green; else -> Cyan }
            SmartCardShell(stringResource(R.string.r12_cusum_title), stringResource(R.string.r12_cusum_note), color) {
                Text(
                    stringResource(when (c.verdict) {
                        "RISING" -> R.string.r12_cusum_rising
                        "FALLING" -> R.string.r12_cusum_falling
                        else -> R.string.r12_cusum_stable
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}
