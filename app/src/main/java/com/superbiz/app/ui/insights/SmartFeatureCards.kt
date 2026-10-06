package com.superbiz.app.ui.insights

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.SmartInsightsVM

/**
 * — بطاقات الميزات الذكية العشرون
 * كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ) وتشرح آلية حسابها.
*/

// ═══════════ الرئيسية: فجوة نقدية + قفزة مصروفات + محاكي الهدف + تركيب الصحة + عملاء معرضون للفقد ═══════════

@Composable
fun HomeSmartCard(app: AppVM, smart: SmartInsightsVM) {
    val st by smart.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1) الفجوة النقدية القادمة (6 أسابيع)
        // قيمة محلية بدل !!--حالة الحالة قد تتغير بين الفحص والاستخدام
        st.firstNegWeek?.let { negWeek ->
            SmartCardShell(stringResource(R.string.ins_cashgap_title), stringResource(R.string.ins_hint_generic), Red) {
                KV(stringResource(R.string.ins_cashgap_week, negWeek), stringResource(R.string.ins_cashgap_low, m(-st.lowestBalance)), Red)
            }
        }
        // 2) قفزة مصروفات
        if (st.jumpCategory != null) {
            SmartCardShell(stringResource(R.string.ins_jump_title), stringResource(R.string.ins_hint_generic), Amber) {
                // [P20-FIX agent16]: jumpRatio مضاعف (2.35 = ×2.35) وليس نسبة مئوية — كان يُعرض
                // «أعلى بـ2%» بينما الصحيح +135% = (نسبة−1)×100
                val jumpPct = ((st.jumpRatio - 1.0) * 100.0).toInt().coerceAtLeast(0)
                KV(st.jumpCategory ?: "", stringResource(R.string.ins_jump_line, jumpPct), Amber)
            }
        }
        // 3) محاكي الهدف
        if (st.goalEtaDays != null || st.goalRequiredPace > 0) {
            SmartCardShell(stringResource(R.string.ins_goal_title), stringResource(R.string.ins_hint_generic), if (st.goalWillFinish) Green else Amber) {
                // قيمة محلية بدل !! — نفس الحماية ضد سباق الحالة
                if (st.goalWillFinish) {
                    st.goalEtaDays?.let { eta ->
                        KV(stringResource(R.string.ins_goal_days, eta), "✓", Green)
                    }
                } else {
                    KV(stringResource(R.string.ins_goal_late, m(st.goalRequiredPace)), "⚠", Amber)
                }
            }
        }
        // 4) تركيب درجة الصحة
        if (st.healthContribs.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.ins_health_title), stringResource(R.string.ins_hint_generic), Cyan) {
                st.healthContribs.forEach { c ->
                    val label = when (c.component) {
                        "margin" -> stringResource(R.string.ins_health_margin)
                        "runway" -> stringResource(R.string.ins_health_runway)
                        else -> stringResource(R.string.ins_health_collection)
                    }
                    val col = if (c.delta >= 0) Green else Red
                    KV(label, "+${String.format(java.util.Locale.US, "%.1f", c.delta)}".replace("+−", "−"), col)
                }
            }
        }
        // 5) عملاء معرضون للفقد
        if (st.atRiskCount > 0) {
            SmartCardShell(stringResource(R.string.ins_atrisk_title), stringResource(R.string.ins_hint_generic), Red) {
                Text(stringResource(R.string.ins_atrisk_count, st.atRiskCount), fontSize = 12.5.sp)
            }
        }
    }
}

// ═══════════ المخزون: خطة إعادة الطلب + الراكد ═══════════

@Composable
fun InventorySmartCard(smart: SmartInsightsVM) {
    val st by smart.inventory.collectAsState()
    val app = androidx.lifecycle.viewmodel.compose.viewModel<AppVM>(key = "smartApp")
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.plan.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.ins_inv_title), stringResource(R.string.ins_hint_generic), Vio) {
                st.plan.take(5).forEach { r ->
                    KV(stringResource(R.string.ins_inv_row, r.name, Money.format(r.orderQty, symbol, showDecimals = false).trim()), r.name.let { "" } + stringResource(R.string.ins_inv_cover, String.format(java.util.Locale.US, "%.0f", r.daysCover)), if (r.daysCover < 3.0) Red else Amber)
                }
            }
        }
        if (st.dead.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.ins_dead_title), stringResource(R.string.ins_hint_generic), Red) {
                st.dead.take(4).forEach { d ->
                    KV(d.name, m(d.capital), Red)
                }
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.ins_inv_dead, m(st.deadCapital), st.dead.size), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Red)
            }
        }
    }
}

// ═══════════ التقارير: التعادل + الاستقرار + الذروة ═══════════

@Composable
fun ReportsSmartCard(smart: SmartInsightsVM) {
    val st by smart.reports.collectAsState()
    val app = androidx.lifecycle.viewmodel.compose.viewModel<AppVM>(key = "smartApp")
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val beu = st.breakEvenUnits
        if (beu != null && beu > 0) {
            SmartCardShell(stringResource(R.string.ins_be_title), stringResource(R.string.ins_hint_generic), Cyan) {
                KV(stringResource(R.string.ins_be_units, String.format(java.util.Locale.US, "%.1f", beu)), stringResource(R.string.ins_be_rev, m(st.breakEvenRevenue ?: 0.0)))
            }
        }
        if (st.stabilityLevel > 0) {
            val (label, col) = when (st.stabilityLevel) {
                4 -> stringResource(R.string.ins_stab_4) to Green
                3 -> stringResource(R.string.ins_stab_3) to Cyan
                2 -> stringResource(R.string.ins_stab_2) to Amber
                else -> stringResource(R.string.ins_stab_1) to Red
            }
            SmartCardShell(stringResource(R.string.ins_stab_title), stringResource(R.string.ins_hint_generic), col) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = col)
            }
        }
        if (st.windows.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.ins_peak_title), stringResource(R.string.ins_hint_generic), Vio) {
                st.windows.forEach { w ->
                    Text(stringResource(R.string.ins_peak_win, w.startHour, w.endHour, w.sharePct.toInt()), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

// ═══════════ الذمم: أولوية التحصيل ═══════════

@Composable
fun CollectionPriorityCard(smart: SmartInsightsVM) {
    val list by smart.collections.collectAsState()
    if (list.isEmpty()) return
    val app = androidx.lifecycle.viewmodel.compose.viewModel<AppVM>(key = "smartApp")
    val symbol by app.symbol.collectAsState()
    SmartCardShell(stringResource(R.string.ins_coll_title), stringResource(R.string.ins_hint_generic), Red) {
        list.take(5).forEach { r ->
            KV(stringResource(R.string.ins_coll_row, r.name, Money.format(r.amount, symbol), r.daysOverdue), "${(r.score * 100).toInt()}%", if (r.score > 0.66) Red else Amber)
        }
    }
}

// ═══════════ الشيكات: شارة المخاطرة ═══════════

@Composable
fun CheckRiskChip(pct: Int) {
    val col = if (pct >= 50) Red else if (pct >= 25) Amber else Green
    Chip(stringResource(R.string.ins_check_risk, pct), col)
}

// ═══════════ جهات الاتصال: ذكاء العميل ═══════════

@Composable
fun CustomerIntelCard(intel: SmartInsightsVM.CustomerIntel, symbol: String) {
    val churnCol = if (intel.churn >= 0.6) Red else if (intel.churn >= 0.3) Amber else Green
    SmartCardShell(stringResource(R.string.ins_cust_title), stringResource(R.string.ins_hint_generic), Vio) {
        KV(stringResource(R.string.ins_cust_ltv), Money.format(intel.ltv, symbol), Green)
        KV(stringResource(R.string.ins_cust_churn), "${(intel.churn * 100).toInt()}%", churnCol)
        // [P20-FIX agent16]: الرمز الآلي (CHAMPION/AT_RISK…) كان يظهر خاماً — نفس خريطة
        // r11_seg_* الموجودة في R11Cards
        KV(stringResource(R.string.ins_cust_segment), stringResource(when (intel.segment) {
            "CHAMPION" -> R.string.r11_seg_champion
            "LOYAL" -> R.string.r11_seg_loyal
            "NEW" -> R.string.r11_seg_new
            "PROMISING" -> R.string.r11_seg_promising
            "AT_RISK" -> R.string.r11_seg_at_risk
            "SLEEPING" -> R.string.r11_seg_sleeping
            else -> R.string.r11_seg_lost
        }), Vio)
        KV(stringResource(R.string.ins_cust_eta), stringResource(R.string.ins_cust_eta_days, intel.etaDays), Cyan)
    }
}

// ═══════════ نقطة البيع: حارس هامش الخصم ═══════════

@Composable
fun PosDiscountGuard(exceededPct: Int?) {
    if (exceededPct == null || exceededPct <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Chip(stringResource(R.string.ins_pos_guard, exceededPct), Red, bgAlpha = 0.18f)
    }
}

private fun Modifier.padding() = this
