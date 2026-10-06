package com.superbiz.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.DataHealthVM

/**
 * — شاشة فحص صحة البيانات
 *
 * ماسح سلامة حقيقي: انحراف بنفورد على المبالغ، فجوات ترقيم الفواتير، الفواتير
 * المكررة، الأطراف اليتيمة، القيم الشاذة، المؤرشف بمخزون، الأطراف المتشابهة.
 * كل النتائج من قاعدة البيانات الفعلية والتقرير قابل للمشاركة نصاً.
*/
@Composable
fun DataHealthScreen(onBack: () -> Unit) {
    val g = glassColors()
    val vm: DataHealthVM = viewModel()
    val report by vm.report.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SubHeader(stringResource(R.string.health_title), onBack = onBack)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.scan() },
                enabled = !scanning,
                colors = ButtonDefaults.buttonColors(containerColor = g.accent),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (scanning) stringResource(R.string.health_scanning) else stringResource(R.string.health_scan))
            }
            Spacer(Modifier.width(10.dp))
            if (report != null && !scanning) {
                Button(
                    onClick = {
                        val txt = vm.shareText()
                        if (txt.isNotBlank()) {
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, txt)
                            }
                            startIntentSafe(context, Intent.createChooser(i, txt.take(40)))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = g.accent2),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Share, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.health_share))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val r = report
        if (!scanning && r == null) {
            EmptyState(stringResource(R.string.health_idle), Icons.Rounded.HealthAndSafety)
        } else if (!scanning && r != null && r.findings.all { it.severity == 0 }) {
            EmptyState(stringResource(R.string.health_clean), Icons.Rounded.HealthAndSafety)
        } else if (!scanning && r != null) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        stringResource(R.string.health_items_count, r.scannedRecords),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                itemsIndexed(r.findings, key = { i, f -> "$i${f.kind}" }) { _, f ->
                    FindingRow(f)
                }
            }
        }
    }
}

@Composable
private fun FindingRow(f: DataHealthVM.Finding) {
    val g = glassColors()
    val title = when (f.kind) {
        "benford" -> stringResource(R.string.health_benford)
        "gaps" -> stringResource(R.string.health_gaps)
        "dup_inv" -> stringResource(R.string.health_dup_inv)
        "orphans" -> stringResource(R.string.health_orphans)
        "anomaly" -> stringResource(R.string.health_anomaly)
        "arch_stock" -> stringResource(R.string.health_archived_stock)
        else -> stringResource(R.string.health_dup_parties)
    }
    val (dotColor, label) = when (f.severity) {
        2 -> Red to stringResource(R.string.health_bad)
        1 -> g.amber to stringResource(R.string.health_warn)
        else -> Green to stringResource(R.string.health_ok)
    }
    GlassCard(corner = 14.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    label,
                    fontSize = 11.sp,
                    color = dotColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.health_count_prefix, f.count) +
                    if (f.detail.isNotBlank()) " — ${f.detail}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}
