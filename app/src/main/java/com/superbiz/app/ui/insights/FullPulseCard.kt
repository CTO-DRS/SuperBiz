package com.superbiz.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.R
import com.superbiz.app.domain.algo.R18Kpi
import com.superbiz.app.ui.theme.glassColors

/**
 * [H4-7][V 3.0.0] بطاقة المؤشرات الكاملة — نبض العمل (0..100) بمكوّناته الستة
 * المعلنة وأحكامها الأربع. تُعرض أعلى لوحة KPI (اللوحة نفسها باب Pro — الصدق
 * نفسه: بلا مبيعات هذا الشهر لا نبض والبطاقة تختفي (صدق الفراغ R16)).
 */
@Composable
fun FullPulseCard(pulse: R18Kpi.Pulse, symbol: String) {
    val g = glassColors()
    val verdictText = when (pulse.verdict) {
        0 -> stringResource(R.string.pulse_verdict_excellent)
        1 -> stringResource(R.string.pulse_verdict_stable)
        2 -> stringResource(R.string.pulse_verdict_attention)
        else -> stringResource(R.string.pulse_verdict_risk)
    }
    val verdictColor = when (pulse.verdict) {
        0 -> com.superbiz.app.ui.theme.Green
        1 -> g.accent
        2 -> com.superbiz.app.ui.theme.Amber
        else -> com.superbiz.app.ui.theme.Red
    }

    SmartCardShell(
        title = stringResource(R.string.pulse_title),
        hint = verdictText,
        accent = verdictColor
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                pulse.score.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = verdictColor
            )
            Spacer(Modifier.height(0.dp))
            Text(
                " / 100 — " + verdictText,
                style = MaterialTheme.typography.bodyMedium,
                color = g.textSecondary
            )
        }
        Spacer(Modifier.height(10.dp))
        pulse.components.forEach { c ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(componentLabel(c.key), style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                    Text(
                        componentRaw(c.key, c.rawPiasters, symbol),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
                Spacer(Modifier.height(3.dp))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(g.surface, RoundedCornerShape(3.dp))
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth(fraction = (c.score / 100f).coerceIn(0.05f, 1f))
                            .height(5.dp)
                            .background(verdictColor, RoundedCornerShape(3.dp))
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun componentLabel(key: String): String = when (key) {
    "pulse_comp_sales" -> stringResource(R.string.pulse_comp_sales)
    "pulse_comp_profit" -> stringResource(R.string.pulse_comp_profit)
    "pulse_comp_expenses" -> stringResource(R.string.pulse_comp_expenses)
    "pulse_comp_overdue" -> stringResource(R.string.pulse_comp_overdue)
    "pulse_comp_stock" -> stringResource(R.string.pulse_comp_stock)
    else -> stringResource(R.string.pulse_comp_cash)
}

@Composable
private fun componentRaw(key: String, raw: Long, symbol: String): String {
    if (key == "pulse_comp_stock") return stringResource(R.string.pulse_raw_count, raw.toInt())
    if (key == "pulse_comp_cash") return com.superbiz.app.util.Money.formatP(raw, symbol)
    return com.superbiz.app.util.Money.formatP(raw, symbol)
}
