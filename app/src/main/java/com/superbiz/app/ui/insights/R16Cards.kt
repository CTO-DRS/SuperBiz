package com.superbiz.app.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.ProVM
import com.superbiz.app.vm.SmartCoordinatorVM

/**
 * — بطاقات المنسّق الذكي للموجة R16 (الأفق الثالث «المنسّق الذكي»):
 *
 * عقد العرض نفسه (R9..R15): كل بطاقة تُخفى كلياً عند غياب بياناتها (صدق الفراغ)
 * وتشرح آلية حسابها في سطر التلميح. حدود الفريميوم (H3-7) معلنة في التلميحات:
 * - مجاني: إعادة الطلب الذكية + التنبيهات الاستباقية + مدخل الدردشة (يفتح باب Pro).
 * - Pro: مسار التدفق النقدي 90 يوماً + الدردشة الكاملة + روايات KPI.
 * كل توصية تفتح شاشة إجرائها مباشرة (بوابة H3-3: كل تنبيه مسار).
 */
private fun d1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

// ═══════════ مجموعة r16 في الرئيسية ═══════════

@Composable
fun HomeR16Card(app: AppVM, vm: SmartCoordinatorVM, nav: androidx.navigation.NavHostController) {
    val ready by vm.ready.collectAsState()
    if (!ready) { InsightCardSkeleton(); return }
    val st by vm.home.collectAsState()
    val symbol by app.symbol.collectAsState()
    fun m(v: Double) = Money.format(v, symbol)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── التنبيهات الاستباقية (مجاني) — كل تنبيه يفتح مسار الإجراء ──
        if (st.alerts.isNotEmpty()) {
            SmartCardShell(
                stringResource(R.string.r16_alerts_title),
                stringResource(R.string.r16_alerts_note), Amber
            ) {
                st.alerts.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { nav.navigate(a.actionRoute) { launchSingleTop = true } }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(when (a.streamKey) {
                                "sales" -> R.string.r16_stream_sales
                                "collections" -> R.string.r16_stream_collections
                                else -> R.string.r16_stream_outflows
                            }) + " · " + stringResource(
                                if (a.severity >= 3) R.string.r16_sev_critical else R.string.r16_sev_warn),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (a.severity >= 3) Red else Amber,
                            modifier = Modifier.weight(1f)
                        )
                        Text("σ " + d1(a.dev), fontSize = 11.sp, color = Vio)
                    }
                }
            }
        }

        // ── إعادة الطلب الذكية (مجاني) — تفتح المخزون ──
        if (st.reorders.isNotEmpty()) {
            SmartCardShell(
                stringResource(R.string.r16_reorder_title),
                stringResource(R.string.r16_reorder_note), Cyan
            ) {
                st.reorders.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { nav.navigate(com.superbiz.app.ui.nav.Routes.INVENTORY) { launchSingleTop = true } }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.productName, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.r16_reorder_point, d1(r.rop)) + " · " +
                                    stringResource(
                                        if (r.leadVerdict == "OBSERVED" || r.leadVerdict == "SMALL")
                                            R.string.r16_lead_observed else R.string.r16_lead_default),
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        val urgent = r.daysOfSafety <= 0.0
                        Text(
                            if (urgent) stringResource(R.string.r16_reorder_now)
                            else stringResource(R.string.r16_reorder_days, d1(r.daysOfSafety)),
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (urgent) Red else Amber
                        )
                    }
                }
            }
        }

        // ── مسار النقد 90 يوماً (Pro) — قفل صادق يشرح ما خلفه ──
        Cash90Card(st, ::m, onOpenPro = { nav.navigate(com.superbiz.app.ui.nav.Routes.PRO) { launchSingleTop = true } })

        // ── مدخل الدردشة المحلية ──
        ChatEntryPill(onOpen = { nav.navigate(com.superbiz.app.ui.nav.Routes.SMART) { launchSingleTop = true } })
    }
}

// ═══════════ بطاقة النقد 90 يوماً (باب Pro) ═══════════

@Composable
private fun Cash90Card(
    st: SmartCoordinatorVM.HomeCards,
    m: (Double) -> String,
    onOpenPro: () -> Unit
) {
    val proVM: ProVM = viewModel()
    val pro by proVM.pro.collectAsState()

    if (!pro) {
        // القفل الصادق — نمط KpiBoard نفسه: يشرح ما خلفه ولا يزيّف
        SmartCardShell(
            stringResource(R.string.r16_cash_title),
            stringResource(R.string.r16_cash_locked_note), Vio
        ) {
            Text(
                stringResource(R.string.r16_cash_locked_cta),
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Vio,
                modifier = Modifier.clickable { onOpenPro() }
            )
        }
        return
    }

    val monthEnd = st.monthEnd ?: return // بلا بيانات كافية ⇒ تختفي (صدق الفراغ)
    val color = when (st.cashVerdict) {
        "DRY" -> Red
        "TIGHT" -> Amber
        else -> Green
    }
    SmartCardShell(
        stringResource(R.string.r16_cash_title),
        stringResource(R.string.r16_cash_note), color
    ) {
        KV(stringResource(R.string.r16_cash_month_end, m(monthEnd)), "", color)
        st.monthLower?.let {
            KV(stringResource(R.string.r16_cash_lower, m(it)), "", Vio)
        }
        st.monthNet?.let {
            KV(stringResource(R.string.r16_cash_net, m(it)), "", if (it >= 0) Green else Red)
        }
        st.dryDayWeek?.let { wk ->
            Text(
                stringResource(R.string.r16_cash_dry, wk),
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Red
            )
        }
    }
}

// ═══════════ مدخل الدردشة المحلية ═══════════

@Composable
private fun ChatEntryPill(onOpen: () -> Unit) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.ChatBubbleOutline, null,
            tint = g.accent, modifier = Modifier.height(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                stringResource(R.string.r16_chat_entry),
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = g.accent
            )
            Text(
                stringResource(R.string.r16_chat_entry_note),
                fontSize = 10.5.sp, color = g.textSecondary
            )
        }
    }
}

// ═══════════ روايات KPI — بطاقة لوحة المؤشرات (Pro) ═══════════

/** سطر رواية مُنسَّق جاهز للعرض: نص + أساسه الخام */
data class StoryView(val text: String, val basis: String)

/** تُنسِّق روايات المحرك النقي إلى أسطر بالرمز النقدي — الواجهة تترجم فقط */
@Composable
fun narrativeStoryViews(stories: List<com.superbiz.app.domain.algo.NarrativeMath.Story>, symbol: String): List<StoryView> {
    fun m(v: Double) = Money.format(v, symbol)
    fun p(v: Double): String = d1(v) + "%"
    return stories.map { s ->
        val text = when (s.key) {
            "r16_story_sales_up" -> stringResource(R.string.r16_story_sales_up, p(s.args[0]), m(s.args[1]), m(s.args[2]))
            "r16_story_sales_down" -> stringResource(R.string.r16_story_sales_down, p(s.args[0]), m(s.args[1]), m(s.args[2]))
            "r16_story_sales_flat" -> stringResource(R.string.r16_story_sales_flat, p(s.args[0]), m(s.args[1]), m(s.args[2]))
            "r16_story_margin" -> stringResource(R.string.r16_story_margin, p(s.args[0]), m(s.args[1]))
            "r16_story_expenses_up" -> stringResource(R.string.r16_story_expenses_up, p(s.args[0]), p(s.args[1]))
            "r16_story_expenses_down" -> stringResource(R.string.r16_story_expenses_down, p(s.args[0]), p(s.args[1]))
            "r16_story_overdue_up" -> stringResource(R.string.r16_story_overdue_up, p(s.args[0]), m(s.args[1]))
            "r16_story_overdue_down" -> stringResource(R.string.r16_story_overdue_down, p(s.args[0]), m(s.args[1]))
            "r16_story_overdue_flat" -> stringResource(R.string.r16_story_overdue_flat, m(s.args[1]))
            "r16_story_overdue_new" -> stringResource(R.string.r16_story_overdue_new, m(s.args[0]))
            "r16_story_target" -> stringResource(R.string.r16_story_target, p(s.args[0]), m(s.args[1]))
            else -> s.key
        }
        StoryView(text, s.basis)
    }
}

/** بطاقة «روايات الأداء» في لوحة المؤشرات — Pro فقط، وتختفي بلا روايات (صدق الفراغ) */
@Composable
fun KpiNarrativesCard(stories: List<com.superbiz.app.domain.algo.NarrativeMath.Story>, symbol: String) {
    if (stories.isEmpty()) return
    SmartCardShell(
        stringResource(R.string.r16_stories_title),
        stringResource(R.string.r16_stories_note), Green
    ) {
        narrativeStoryViews(stories, symbol).forEach { sv ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(sv.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                // الشفافية الكاملة: الأرقام الخام خلف كل رواية (بوابة H3-5)
                Text(sv.basis, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}
