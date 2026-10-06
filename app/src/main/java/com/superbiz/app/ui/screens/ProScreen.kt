package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.ProVM

/**
 * [W1] شاشة SuperBiz Pro — فريميوم بلا خادم عبر Play Billing 
 *
 * الصدق أولاً: كل قدرات تبقى مجانية بالكامل (عقد الترقية فوق بلا
 * جدار على الموجود) — Pro يفتح أبواباً جديدة فقط، وأولها لوحة المؤشرات.
 * الشراء يعمل عبر نافذة Google Play عند توفر الاتصال؛ التطبيق نفسه
 * يعمل دون اتصال كما هو — والاستعادة زر صريح دائم الظهور.
*/
@Composable
fun ProScreen(
    onBack: () -> Unit,
    proVM: ProVM = viewModel()
) {
    val g = glassColors()
    val pro by proVM.pro.collectAsState()
    val ready by proVM.billingReady.collectAsState()
    val price by proVM.price.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SubHeader(stringResource(R.string.pro_title), onBack = onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // ══ بطاقة الحالة ══
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WorkspacePremium, null,
                            tint = if (pro) Amber else Vio,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (pro) stringResource(R.string.pro_status_active)
                                else stringResource(R.string.pro_status_free),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                stringResource(R.string.pro_onetime_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ══ ماذا يفتح Pro؟ ══
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        stringResource(R.string.pro_unlocks),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    ProBenefit(
                        Icons.Rounded.Insights,
                        stringResource(R.string.pro_benefit_kpi),
                        stringResource(R.string.pro_benefit_kpi_sub)
                    )
                    Spacer(Modifier.height(10.dp))
                    ProBenefit(
                        Icons.Rounded.CheckCircle,
                        stringResource(R.string.pro_benefit_future),
                        stringResource(R.string.pro_benefit_future_sub)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.pro_free_forever),
                        style = MaterialTheme.typography.bodySmall,
                        color = Green
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ══ منطقة الشراء (تختفي عند الاستحقاق) ══
            if (!pro) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            price ?: stringResource(R.string.pro_price_pending),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                (context as? android.app.Activity)?.let { act ->
                                    proVM.purchase(act) { }
                                }
                            },
                            enabled = ready,
                            colors = ButtonDefaults.buttonColors(containerColor = Vio),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.pro_buy),
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        if (!ready) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pro_billing_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.textSecondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ══ الاستعادة ══
            OutlinedButton(
                onClick = { proVM.restore { } },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pro_restore))
            }

            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.pro_offline_honesty),
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProBenefit(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    val g = glassColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(sub, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
        }
    }
}
