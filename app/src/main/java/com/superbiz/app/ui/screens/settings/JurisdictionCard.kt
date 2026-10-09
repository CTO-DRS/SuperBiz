package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.domain.GulfTax
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.screens.FilterPill
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [H5-4 V 3.0.0] بطاقة «الولاية الضريبية الخليجية» — اختيار SA/AE/BH مع تطبيق
 * نسبة القانون بفعل صريح من المالك/المدير فقط:
 *
 * - الاختيار وحده يغيّر الولاية: بوابة ZATCA تتبعها فوراً (سعودية حصراً — عقد
 *   [P51-3] عبر GulfTax.zatcaSellerVat في AppGraph)، وملصق الفاتورة الضريبية يبقى
 *   لكل الولايات ذات الرقم الضريبي.
 * - زر «تطبيق نسبة الولاية» يكتب نسبة القانون الافتراضية (عقد [P51-2] — لا إعادة
 *   كتابة صامتة؛ الفواتير المحفوظة تحمل نسبتها اللحظية فلا يتأثر التاريخ).
 *   تبديل عملة الولاية الأساسية يبقى في «الملف الشخصي ← العملة» (موجود أصلاً
 *   بإعادة ضبط المعدلات) — بطاقة واحدة مسؤولية واحدة.
 * - بلا VM موازٍ — DataStore مصدر الحقيقة (نمط البطاقات الذاتية في المركز).
 */
@Composable
fun JurisdictionCard() {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // حالة محلية قابلة للكتابة — تُحمّل من DataStore ثم تُحدّث فوراً عند الاختيار
    var jurisdiction by remember { mutableStateOf("SA") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val graph = withContext(Dispatchers.IO) { AppGraph.from(context.applicationContext) }
        jurisdiction = graph.settings.snapshot().taxJurisdiction
        loaded = true
    }

    GlassCard(corner = 18.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.jurisdiction_title),
                color = g.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.jurisdiction_sub),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GulfTax.ALL.forEach { p ->
                    FilterPill(
                        label = stringResource(
                            when (p.code) {
                                "SA" -> R.string.jurisdiction_sa
                                "AE" -> R.string.jurisdiction_ae
                                else -> R.string.jurisdiction_bh
                            }
                        ),
                        selected = jurisdiction == p.code
                    ) {
                        scope.launch(Dispatchers.IO) {
                            val graph = AppGraph.from(context.applicationContext)
                            graph.settings.setTaxJurisdiction(p.code)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                jurisdiction = p.code
                                loaded = true
                            }
                        }
                    }
                }
            }
            if (jurisdiction != "SA") {
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.jurisdiction_zatca_note),
                    color = g.amber,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                enabled = loaded,
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val graph = AppGraph.from(context.applicationContext)
                        // [H1-4][v13] الإعدادات العامة — المالك والمدير حصراً؛
                        // الرفض استثناء RoleGate ⇒ runCatching يبقي الجلسة حية.
                        // الفعل صريح: نسبة القانون فقط — الفواتير المحفوظة لا تُمسّ [P51-2]
                        val allowed = runCatching {
                            com.superbiz.app.domain.rbac.RoleGate.require(
                                com.superbiz.app.domain.rbac.SessionState.effective(),
                                com.superbiz.app.domain.rbac.Op.GENERAL_SETTINGS
                            )
                        }.isSuccess
                        if (allowed) {
                            graph.settings.setTaxRate(GulfTax.preset(jurisdiction).defaultRate)
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Main) { /* لمسة بصرية */ }
                    }
                }
            ) {
                Text(stringResource(R.string.jurisdiction_apply_rate))
            }
            Text(
                stringResource(R.string.jurisdiction_note_history),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
