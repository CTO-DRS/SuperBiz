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
import com.superbiz.app.vm.R13InsightsVM

/**
 * — بطاقات الميزات الذكية للموجة R13 (20 ميزة عبر 8 شاشات).
 * عقد العرض نفسه: كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)،
 * وتشرح آلية حسابها في سطر التلميح.
 *
 * : منسّق d1() — القيم العشرية إلى %s كانت تُطبع بدقة النظام الثنائي الكاملة
*/

private fun d1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

// ═══════════ الرئيسية: الميل المتين/جيني/التفاعل/المئينات ═══════════

@Composable
fun HomeR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F1 ميل الإيراد المتين (ثيل-سين)
        st.trend?.let { t ->
            val color = when (t.verdict) { "RISING" -> Green; "FALLING" -> Red; else -> Cyan }
            SmartCardShell(stringResource(R.string.r13_trend_title), stringResource(R.string.r13_trend_note), color) {
                KV(stringResource(R.string.r13_trend_slope, d1(t.relSlopePct)), "", color)
                Text(
                    stringResource(when (t.verdict) {
                        "RISING" -> R.string.r13_trend_up
                        "FALLING" -> R.string.r13_trend_down
                        else -> R.string.r13_trend_flat
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F2 تفاوت قيم الفواتير (جيني)
        st.gini?.let { gv ->
            val color = when {
                gv >= 0.6 -> Amber
                gv >= 0.35 -> Cyan
                else -> Green
            }
            SmartCardShell(stringResource(R.string.r13_gini_title), stringResource(R.string.r13_gini_note), color) {
                KV(stringResource(R.string.r13_gini_value, (gv * 100).toInt()), "", color)
                Text(
                    stringResource(when {
                        gv >= 0.6 -> R.string.r13_gini_high
                        gv >= 0.35 -> R.string.r13_gini_mid
                        else -> R.string.r13_gini_low
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F3 أتفاعل العملاء (عمر النصف)
        if (st.engaged.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r13_engaged_title), stringResource(R.string.r13_engaged_note), Vio) {
                st.engaged.forEach { e ->
                    KV(e.name, m(e.score), Vio)
                }
            }
        }
        // F4 مئينات قيمة الفاتورة
        st.percentiles?.let { ps ->
            SmartCardShell(stringResource(R.string.r13_pctl_title), stringResource(R.string.r13_pctl_note), Cyan) {
                ps.forEach { (p, v) ->
                    KV(stringResource(R.string.r13_pctl_row, p.toInt()), m(v), Cyan)
                }
            }
        }
    }
}

// ═══════════ نقاط البيع: الانتظام/الجفاف/الدرج ═══════════

@Composable
fun PosR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.pos.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F5 انتظام وتيرة الزبائن الدائمين
        if (st.regulars.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r13_regular_title), stringResource(R.string.r13_regular_note), Cyan) {
                st.regulars.forEach { r ->
                    val color = when (r.verdict) {
                        "REGULAR" -> Green
                        "NORMAL" -> Cyan
                        else -> Amber
                    }
                    KV(r.name, stringResource(when (r.verdict) {
                        "REGULAR" -> R.string.r13_regular_on
                        "NORMAL" -> R.string.r13_regular_ok
                        else -> R.string.r13_regular_off
                    }, d1(r.cv)), color)
                }
            }
        }
        // F6 أيام الجفاف البيعي
        st.dry?.let { d ->
            if (d.dryDays > 0) {
                val color = if (d.longestDry >= 5) Amber else Cyan
                SmartCardShell(stringResource(R.string.r13_dry_title), stringResource(R.string.r13_dry_note), color) {
                    KV(stringResource(R.string.r13_dry_longest, d.longestDry), "", color)
                    KV(stringResource(R.string.r13_dry_count, d.dryDays, d.dryPct.toInt()), "", Cyan)
                    if (d.currentDry >= 2) {
                        KV(stringResource(R.string.r13_dry_current, d.currentDry), "", Amber)
                    }
                }
            }
        }
        // F7 فكّ درج النقدية
        st.drawer?.let { d ->
            if (d.totalPieces > 0) {
                SmartCardShell(stringResource(R.string.r13_drawer_title), stringResource(R.string.r13_drawer_note), Green) {
                    d.pieces.forEach { (denom, count) ->
                        KV(m(denom), "×$count", Green)
                    }
                    if (d.leftover > 0.0) {
                        // كانت stringResource بلا وسيط فتُطبع %1$s حرفياً
                        KV(stringResource(R.string.r13_drawer_left, m(d.leftover)), "", Amber)
                    }
                }
            }
        }
    }
}

// ═══════════ الفواتير: التوأم/الرتبة/CCC ═══════════

@Composable
fun InvoicesR13Card(vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.invoices.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F8 عملاء برقم جوال متطابق
        if (st.twins.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r13_twins_title), stringResource(R.string.r13_twins_note), Amber) {
                st.twins.forEach { t ->
                    KV(t.a, stringResource(R.string.r13_twins_row, t.b), Amber)
                }
            }
        }
        // F9 رتبة أحدث فاتورة مئينياً
        st.lastRank?.let { rk ->
            val color = when {
                rk >= 90.0 -> Vio
                rk <= 10.0 -> Amber
                else -> Cyan
            }
            SmartCardShell(stringResource(R.string.r13_rank_title), stringResource(R.string.r13_rank_note), color) {
                KV(stringResource(R.string.r13_rank_row, rk.toInt()), "", color)
            }
        }
        // F10 دورة التحول النقدي
        st.ccc?.let { c ->
            if (c.ccc != null) {
                val color = when (c.verdict) { "FAST" -> Green; "OK" -> Cyan; else -> Amber }
                SmartCardShell(stringResource(R.string.r13_ccc_title), stringResource(R.string.r13_ccc_note), color) {
                    c.dso?.let { KV(stringResource(R.string.r13_ccc_dso, it.toInt()), "", Cyan) }
                    c.dio?.let { KV(stringResource(R.string.r13_ccc_dio, it.toInt()), "", Vio) }
                    c.dpo?.let { KV(stringResource(R.string.r13_ccc_dpo, it.toInt()), "", Green) }
                    KV(stringResource(R.string.r13_ccc_total, c.ccc!!.toInt()), "", color)
                }
            }
        }
    }
}

// ═══════════ المخزون: شواذ/تحت التكلفة/الأسهم/التشتت ═══════════

@Composable
fun InventoryR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.inventory.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F11 شواذ إيراد المنتجات (MAD)
        st.mad?.let { mo ->
            if (mo.outlierIndices.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r13_mad_title), stringResource(R.string.r13_mad_note), Vio) {
                    KV(stringResource(R.string.r13_mad_count, mo.outlierIndices.size), "", Vio)
                }
            }
        }
        // F12 أسطر بيع تحت التكلفة
        if (st.below.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r13_below_title), stringResource(R.string.r13_below_note), Red) {
                st.below.forEach { b ->
                    // الاسم بدل #المعرّف الخام
                    // [P5-C1 إصلاح]: السلسلة تتوقع 3 مقاسم (%1$s الاسم) — كان يمرر وسيطين فقط
                    // فينهار العرض بـMissingFormatArgumentException بمجرد وجود بيع تحت التكلفة.
                    // الاسم يمرر أولاً كما صُمم، والمبلغ المفقود صار عمود القيمة الملون بدلاً من تكرار الاسم.
                    KV(
                        stringResource(R.string.r13_below_row_named, b.name, b.linesBelow, b.qtyBelow.toInt()),
                        m(b.lost), Red
                    )
                }
            }
        }
        // F13 سهم كل فئة من قيمة المخزون
        st.shares?.let { shares ->
            if (shares.isNotEmpty()) {
                SmartCardShell(stringResource(R.string.r13_shares_title), stringResource(R.string.r13_shares_note), Cyan) {
                    shares.sortedByDescending { it.second }.take(5).forEach { (cat, pct) ->
                        KV(cat, stringResource(R.string.r13_shares_row, d1(pct)), Cyan)
                    }
                }
            }
        }
        // F14 تشتت أسعار المنتج الأكثر بيعاً
        st.spread?.let { sp ->
            if (sp.verdict != "UNIFORM") {
                val color = when (sp.verdict) { "TIGHT" -> Green; "LOOSE" -> Amber; else -> Red }
                SmartCardShell(stringResource(R.string.r13_spread_title), stringResource(R.string.r13_spread_note), color) {
                    KV(stringResource(R.string.r13_spread_product, vm.spreadProduct), "", color)
                    KV(stringResource(R.string.r13_spread_row, sp.distinctPrices, d1(sp.spreadPct)), "", color)
                }
            }
        }
    }
}

// ═══════════ التقارير: المرونتان + انجراف الصرف ═══════════

@Composable
fun ReportsR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.reports.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F15 مرونة الخصم (بيرسون)
        st.pearson?.let { r ->
            val color = when {
                r >= 0.5 -> Green
                r <= -0.5 -> Red
                else -> Cyan
            }
            SmartCardShell(stringResource(R.string.r13_pearson_title), stringResource(R.string.r13_pearson_note), color) {
                KV(stringResource(R.string.r13_corr_row, d1(r)), "", color)
                Text(
                    stringResource(when {
                        r >= 0.5 -> R.string.r13_corr_positive
                        r <= -0.5 -> R.string.r13_corr_negative
                        else -> R.string.r13_corr_weak
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
        // F16 صلابة العلاقة رتبياً (سبيرمان)
        st.spearman?.let { r ->
            if (kotlin.math.abs(r) >= 0.4) {
                val color = if (r > 0) Green else Red
                SmartCardShell(stringResource(R.string.r13_spearman_title), stringResource(R.string.r13_spearman_note), color) {
                    KV(stringResource(R.string.r13_corr_row, d1(r)), "", color)
                    Text(
                        stringResource(if (r > 0) R.string.r13_spearman_holds else R.string.r13_spearman_breaks),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                    )
                }
            }
        }
        // F17 خطة قصّ المصروفات 10٪ (توزيع البواقي الكبرى)
        if (st.cuts.isNotEmpty()) {
            SmartCardShell(stringResource(R.string.r13_cut_title), stringResource(R.string.r13_cut_note), Amber) {
                st.cuts.forEach { c ->
                    KV(c.category, stringResource(R.string.r13_cut_row, m(c.cut)), Amber)
                }
            }
        }
    }
}

// ═══════════ الذمم: تفاوت توزيع الذمم ═══════════

@Composable
fun DebtsR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.debts.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F18 تفاوت توزيع الذمم على العملاء (جيني)
        st.balGini?.let { gv ->
            val color = when {
                gv >= 0.6 -> Red
                gv >= 0.35 -> Amber
                else -> Green
            }
            SmartCardShell(stringResource(R.string.r13_balgini_title), stringResource(R.string.r13_balgini_note), color) {
                KV(stringResource(R.string.r13_balgini_value, (gv * 100).toInt()), "", color)
                Text(
                    stringResource(when {
                        gv >= 0.6 -> R.string.r13_balgini_high
                        gv >= 0.35 -> R.string.r13_balgini_mid
                        else -> R.string.r13_balgini_low
                    }),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                )
            }
        }
    }
}

// ═══════════ الشيكات: سرعة التحصيل ═══════════

@Composable
fun ChecksR13Card(app: AppVM, vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.checks.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F19 سرعة تحصيل الشيكات الواردة
        st.float?.let { f ->
            val color = when (f.verdict) { "FAST" -> Green; "NORMAL" -> Cyan; else -> Amber }
            SmartCardShell(stringResource(R.string.r13_float_title), stringResource(R.string.r13_float_note), color) {
                KV(stringResource(R.string.r13_float_med, f.medianDays.toInt()), "", color)
                KV(stringResource(R.string.r13_float_p90, f.p90Days.toInt()), "", Cyan)
                KV(stringResource(R.string.r13_float_max, f.maxDays.toInt()), "", Amber)
            }
        }
    }
}

// ═══════════ المصروفات: الفئة الأسرع صعوداً ═══════════

@Composable
fun ExpensesR13Card(vm: R13InsightsVM) {
    // [P39-M4-10]: سكيلتون زجاجي أثناء أول حساب بدل غياب صامت (عقد R9/R10 نفسه)
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.expenses.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // F20 الفئة المصرفية الأسرع صعوداً
        st.riser?.let { r ->
            if (r.verdict != "STABLE") {
                val color = if (r.verdict == "SHIFT") Red else Amber
                SmartCardShell(stringResource(R.string.r13_riser_title), stringResource(R.string.r13_riser_note), color) {
                    KV(r.category, stringResource(R.string.r13_riser_row, d1(r.shareBeforePct), d1(r.shareAfterPct)), color)
                    Text(
                        stringResource(if (r.verdict == "SHIFT") R.string.r13_riser_shift else R.string.r13_riser_climb),
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = color,
                    )
                }
            }
        }
    }
}
