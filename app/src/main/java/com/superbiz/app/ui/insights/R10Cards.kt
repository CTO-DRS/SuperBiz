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
import com.superbiz.app.vm.R10InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R10 (20 ميزة عبر 6 شاشات).
 * عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)،
 * وتشرح آلية حسابها في سطر التلميح.
 *
 * [P36-M4-9]: موحّد بالفعل — AppVM باراميتر صريح في كل بطاقة تحتاجه (Home/Debts/
 * Expenses عبر الرمز symbol)، ولا تجلب داخلياً عبر viewModel() إطلاقاً؛ بطاقات
 * المخزون/التقارير/الشيكات لا تحتاج AppVM لأن بياناتها بلا مبالغ مالية.
 *
 * [P36-M4-10]: أثناء أول جولة حساب (VM.ready=false) تُرسم InsightCardSkeleton زجاجية
 * بدل الغياب الصامت؛ بعد الجاهزية عقد الصدق كما هو: بلا بيانات ⇐ لا بطاقة.
*/

@Composable
private fun bandColor(score: Int) = when {
    score >= 80 -> Green
    score >= 60 -> Cyan
    score >= 40 -> Amber
    else -> Red
}

@Composable
private fun verdictColor(verdict: String) = when (verdict) {
    "HIGH" -> Red
    "MED" -> Amber
    else -> Green
}

// ═══════════ الرئيسية: صحة/تركّز/أفضل يوم/وتيرة/شواذ مصروفات ═══════════

@Composable
fun HomeR10Card(app: AppVM, vm: R10InsightsVM) {
    val st by vm.home.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B1 درجة الصحة
        st.health?.let { h ->
            SmartCardShell(stringResource(R.string.r10_health_title), stringResource(R.string.r10_hint_generic), bandColor(h.score)) {
                KV(stringResource(R.string.r10_health_score), "${h.score}", bandColor(h.score))
                Text(
                    stringResource(
                        when (h.band) {
                            "HEALTHY" -> R.string.r10_band_healthy
                            "OK" -> R.string.r10_band_ok
                            "WATCH" -> R.string.r10_band_watch
                            else -> R.string.r10_band_risk
                        }
                    ),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = bandColor(h.score),
                )
            }
        }
        // B2 تركّز العملاء
        st.concentration?.let { c ->
            if (c.hhi > 0) {
                SmartCardShell(stringResource(R.string.r10_conc_title), stringResource(R.string.r10_hint_generic), verdictColor(c.verdict)) {
                    KV(stringResource(R.string.r10_conc_top), "${c.topSharePct.toInt()}%", verdictColor(c.verdict))
                    KV(stringResource(R.string.r10_conc_hhi), "${c.hhi.toInt()}", verdictColor(c.verdict))
                    Text(
                        stringResource(when (c.verdict) {
                            "HIGH" -> R.string.r10_conc_high
                            "MED" -> R.string.r10_conc_med
                            else -> R.string.r10_conc_low
                        }),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = verdictColor(c.verdict),
                    )
                }
            }
        }
        // B3 أفضل يوم بيع
        st.bestWeekday?.let { d ->
            SmartCardShell(stringResource(R.string.r10_weekday_title), stringResource(R.string.r10_hint_generic), if (d.indexPct >= 120) Green else Cyan) {
                val dayName = stringResource(when (d.weekday) {
                    1 -> R.string.r10_dow_1; 2 -> R.string.r10_dow_2; 3 -> R.string.r10_dow_3
                    4 -> R.string.r10_dow_4; 5 -> R.string.r10_dow_5; 6 -> R.string.r10_dow_6
                    else -> R.string.r10_dow_7
                })
                KV(dayName, stringResource(R.string.r10_weekday_value, d.indexPct), if (d.indexPct >= 120) Green else Cyan)
            }
        }
        // B4 وتيرة الهدف
        st.pace?.let { p ->
            if (p.verdict != "DONE") {
                val color = when (p.verdict) { "ON_TRACK" -> Green; "EXPIRED" -> Red; else -> Amber }
                SmartCardShell(stringResource(R.string.r10_pace_title), stringResource(R.string.r10_hint_generic), color) {
                    KV(stringResource(R.string.r10_pace_required), m(p.requiredPerDay), color)
                    Text(
                        stringResource(when (p.verdict) {
                            "ON_TRACK" -> R.string.r10_pace_on_track
                            "NEEDS_BOOST" -> R.string.r10_pace_boost
                            else -> R.string.r10_pace_expired
                        }),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                    )
                }
            }
        }
        // B5 شواذ المصروفات IQR
        if (st.expenseIqrCount > 0) {
            SmartCardShell(stringResource(R.string.r10_iqr_title), stringResource(R.string.r10_hint_generic), Amber) {
                KV(
                    stringResource(R.string.r10_iqr_count, st.expenseIqrCount),
                    st.expenseIqrUpper?.let { m(it) } ?: "",
                    Amber,
                )
            }
        }
    }
}

// ═══════════ المخزون: ABC/كفاءة/تخفيض/تشابه ═══════════

@Composable
fun InventoryR10Card(vm: R10InsightsVM) {
    val st by vm.inventory.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B6 تصنيف ABC
        if (st.abcRows.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_abc_title), stringResource(R.string.r10_hint_generic), Vio) {
                st.abcRows.forEach { r ->
                    KV(r.name, stringResource(R.string.r10_abc_row, r.klass, r.cumSharePct.toInt()), if (r.klass == 'A') Green else Cyan)
                }
            }
        }
        // B7 كفاءة رأس المال
        if (st.efficiency.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_eff_title), stringResource(R.string.r10_hint_generic), Cyan) {
                st.efficiency.forEach { r ->
                    KV(r.name, stringResource(R.string.r10_eff_row, "${r.profitPerHundred.toInt()}%"), Cyan)
                }
            }
        }
        // B8 سلّم التخفيض
        if (st.markdown.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_md_title), stringResource(R.string.r10_md_age_note), Amber) {
                st.markdown.forEach { r ->
                    KV(r.name, stringResource(R.string.r10_md_row, r.ageDays), Amber)
                }
            }
        }
        // B9 أسماء متشابهة
        if (st.dups.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_dup_title), stringResource(R.string.r10_hint_generic), Vio) {
                st.dups.forEach { d ->
                    KV("${d.a} / ${d.b}", stringResource(R.string.r10_dup_score, d.score), Vio)
                }
            }
        }
    }
}

// ═══════════ التقارير: محفظة/رفع/DSO/شواذ/التالي ═══════════

@Composable
fun ReportsR10Card(vm: R10InsightsVM) {
    val st by vm.reports.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B10 هامش المحفظة
        st.portfolio?.let { p ->
            SmartCardShell(stringResource(R.string.r10_portfolio_title), stringResource(R.string.r10_hint_generic), verdictColor(p.verdict)) {
                KV(stringResource(R.string.r10_portfolio_margin), "${p.marginPct.toInt()}%", if (p.marginPct >= 20) Green else Amber)
                KV(stringResource(R.string.r10_portfolio_hhi), "${p.hhi.toInt()}", verdictColor(p.verdict))
            }
        }
        // B11 أزواج الرفع
        if (st.lift.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_lift_title), stringResource(R.string.r10_hint_generic), Green) {
                st.lift.forEach { l ->
                    KV("${l.aName} + ${l.bName}", stringResource(R.string.r10_lift_row, l.lift.toInt(), l.confidencePct.toInt()), Green)
                }
            }
        }
        // B12 اتجاه DSO
        st.dso?.let { d ->
            // قيمة محلية بدل !! — حماية من سباق الحالة
            d.latest?.let { latestDays ->
                val color = when (d.direction) { 1 -> Red; -1 -> Green; else -> Cyan }
                SmartCardShell(stringResource(R.string.r10_dso_title), stringResource(R.string.r10_hint_generic), color) {
                    KV(stringResource(R.string.r10_dso_latest, latestDays.toInt()), when (d.direction) {
                        1 -> stringResource(R.string.r10_dso_up)
                        -1 -> stringResource(R.string.r10_dso_down)
                        else -> stringResource(R.string.r10_dso_flat)
                    }, color)
                }
            }
        }
        // B13 فواتير شاذة
        if (st.invoiceOutliers > 0) {
            SmartCardShell(stringResource(R.string.r10_invout_title), stringResource(R.string.r10_hint_generic), Amber) {
                KV(stringResource(R.string.r10_invout_count, st.invoiceOutliers), "", Amber)
            }
        }
        // B14 المنتج التالي
        if (st.next.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_next_title), stringResource(R.string.r10_hint_generic), Vio) {
                st.next.forEach { n ->
                    KV(stringResource(R.string.r10_next_row, n.fromName, n.nextName, n.count), "", Vio)
                }
            }
        }
    }
}

// ═══════════ الذمم: أعمار/توقع/تركّز ═══════════

@Composable
fun DebtsR10Card(app: AppVM, vm: R10InsightsVM) {
    val st by vm.debts.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)
    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B15 أعمار الذمم
        if (st.buckets.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_aging_title), stringResource(R.string.r10_hint_generic), Amber) {
                st.buckets.forEach { b ->
                    val color = when (b.label) { "0-30" -> Green; "31-60" -> Amber; else -> Red }
                    KV(stringResource(R.string.r10_aging_row, b.label, b.count), m(b.amount), color)
                }
            }
        }
        // B16 توقع التحصيل
        st.forecast14?.let { f ->
            if (f > 0) {
                SmartCardShell(stringResource(R.string.r10_fc_title), stringResource(R.string.r10_fc_note), Cyan) {
                    KV(stringResource(R.string.r10_fc_value), m(f), Cyan)
                }
            }
        }
        // B17 تركّز المديونين
        st.exposure?.let { c ->
            if (c.hhi > 0 && c.verdict != "LOW") {
                SmartCardShell(stringResource(R.string.r10_expo_title), stringResource(R.string.r10_hint_generic), verdictColor(c.verdict)) {
                    KV(stringResource(R.string.r10_conc_top), "${c.topSharePct.toInt()}%", verdictColor(c.verdict))
                }
            }
        }
    }
}

// ═══════════ الشيكات: سلّم الاستحقاق/تركّز البنوك ═══════════

@Composable
fun ChecksR10Card(vm: R10InsightsVM) {
    val st by vm.checks.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B18 سلّم الاستحقاق
        if (st.ladder.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_ladder_title), stringResource(R.string.r10_hint_generic), Vio) {
                st.ladder.forEach { w ->
                    val color = if (w.week == 1) Red else if (w.week <= 3) Amber else Cyan
                    KV(stringResource(R.string.r10_ladder_row, w.week, w.count), "", color)
                }
            }
        }
        // B19 تركّز البنوك
        st.bankConc?.let { c ->
            if (c.verdict != "LOW" && c.hhi > 0) {
                SmartCardShell(stringResource(R.string.r10_bank_title), stringResource(R.string.r10_hint_generic), verdictColor(c.verdict)) {
                    KV(stringResource(R.string.r10_conc_top), "${c.topSharePct.toInt()}%", verdictColor(c.verdict))
                }
            }
        }
    }
}

// ═══════════ المصروفات: شواذ MAD ═══════════

@Composable
fun ExpensesR10Card(app: AppVM, vm: R10InsightsVM) {
    val st by vm.expenses.collectAsState()
    val ready by vm.ready.collectAsState() // [P36-M4-10]
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)
    // [P36-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت
    if (!ready) { InsightCardSkeleton(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // B20 شواذ MAD المنيعة
        if (st.mad.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r10_mad_title), stringResource(R.string.r10_hint_generic), Red) {
                st.mad.forEach { a ->
                    KV(a.category, stringResource(R.string.r10_mad_row, m(a.amount), a.z.toInt()), Red)
                }
            }
        }
    }
}
