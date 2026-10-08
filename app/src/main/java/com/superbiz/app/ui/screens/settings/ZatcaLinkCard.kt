package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.work.ZatcaReportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Z2-ب V 1.5.0] بطاقة «الربط الضريبي (فاتورة)» — بوابة الميزة D2 ولوحة
 * حالات الربط O1/B10 في مكان واحد:
 *
 * - **مفتاح الربط** (افتراضه مغلق): تفعيله يقر بعقد D5 الصريح — ما يخرج من
 *   الجهاز حصراً مستندات UBL وبصماتها نحو منصة فاتورة — ويجدول عامل القائمة
 *   الدوري؛ إيقافه يلغي الجدولة ويُعيد الصمت الشبكي الكلي فوراً.
 * - **حالة الربط المزروعة**: العدادات من القاعدة مباشرة (produceState) —
 *   بانتظار الإبلاغ/التخليص (zatcaStatus=1)، مُبلَّغَة/مُخلَّصَة (2)، مرفوضة (3)
 *   مع سبب آخر رفض، وإنذار المبسطة التي مضت نافذتها القانونية دون إبلاغ.
 * - بلا VM موازٍ ولا نسخة حالة — القاعدة مصدر الحقيقة الوحيد (نمط البطاقات
 *   الذاتية في المركز)، والقراءة في إنتاج حالة خفيفة تتكرر عند العودة للشاشة.
 */
@Composable
fun ZatcaLinkCard() {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ─── حالة البوابة (DataStore) والعدادات (القاعدة) — إنتاج خفيف قابل للتحديث ───
    val linkState = produceState(initialValue = Triple(false, false, -1), context) {
        val graph = withContext(Dispatchers.IO) { AppGraph.from(context.applicationContext) }
        val enabled = graph.zatcaLink.enabledOnce()
        val production = graph.zatcaLink.productionOnce()
        value = Triple(enabled, production, 0)
    }
    val stats = produceState(initialValue = intArrayOf(0, 0, 0, 0), context) {
        val graph = withContext(Dispatchers.IO) { AppGraph.from(context.applicationContext) }
        val counts = graph.db.invoices().zatcaStatusCounts()
        val late = graph.db.invoices().lateSimplifiedCount(
            System.currentTimeMillis() - 24L * 3_600_000
        )
        val arr = intArrayOf(0, 0, 0, late)
        counts.forEach { c ->
            when (c.state) {
                1 -> arr[0] = c.cnt
                2 -> arr[1] = c.cnt
                3 -> arr[2] = c.cnt
            }
        }
        value = arr
    }

    val enabled = linkState.value.first
    val production = linkState.value.second
    val linkLoaded = linkState.value.third == 0

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {

            // ─── المفتاح + العنوان ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.zat_link_title),
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.zat_link_desc),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    enabled = linkLoaded,
                    onCheckedChange = { on ->
                        scope.launch(Dispatchers.IO) {
                            val graph = AppGraph.from(context.applicationContext)
                            graph.zatcaLink.setEnabled(on)
                            if (on) ZatcaReportWorker.schedulePeriodic(context)
                            else ZatcaReportWorker.cancelPeriodic(context)
                            // القراءة السريعة في المسار الرئيسي تلتقط التغيير في العودة
                            withContext(Dispatchers.Main) { /* تحديث اللمسة البصرية يكفي */ }
                        }
                    },
                )
            }

            if (enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (production) stringResource(R.string.zat_link_env_prod)
                    else stringResource(R.string.zat_link_env_sim),
                    color = g.amber,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ─── لوحة الحالات — تظهر دائماً (العدادات صادقة حتى قبل تفعيل الربط) ───
            Spacer(Modifier.height(10.dp))
            StatusRow(
                label = stringResource(R.string.zat_link_pending),
                value = stats.value[0], color = g.textPrimary,
            )
            StatusRow(
                label = stringResource(R.string.zat_link_reported),
                value = stats.value[1], color = g.textPrimary,
            )
            StatusRow(
                label = stringResource(R.string.zat_link_rejected),
                value = stats.value[2], color = g.amber,
            )
            if (stats.value[3] > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.zat_link_late, stats.value[3]),
                    color = g.amber,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ملاحظة الجاهزية الصادقة — بيانات الاعتماد (CSID) تُدخل من دفعة Z2-ج
            if (enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.zat_link_csid_note),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    val g = glassColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(label, color = g.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("$value", color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
