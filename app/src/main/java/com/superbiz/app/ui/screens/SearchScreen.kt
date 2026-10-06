package com.superbiz.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.GlobalSearchVM

/**
 * — شاشة البحث الشامل
 *
 * بحث موحّد رتبيّ في المنتجات والأطراف والفواتير والشيكات عبر GlobalSearchVM
 * (TextMath.fuzzyScore + حدّ حساسية من الإعدادات). النقر على النتيجة ينقل
 * إلى الشاشة صاحبة السجل ويسجّل الاستعلام في آخر عمليات البحث.
 *
 * [P36-M4-7] تعميق التنقل: النقلة صارت موجّهة إلى السجل نفسه لا إلى جذر الشاشة —
 * مصفوفة الوجهات في deepRouteFor أدناه (منتج/طرف/فاتورة/شيك).
*/
@Composable
fun SearchScreen(appVM: AppVM, nav: NavHostController, onBack: () -> Unit) {
    val g = glassColors()
    val vm: GlobalSearchVM = viewModel()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val recent by vm.recent.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SubHeader(stringResource(R.string.search_title), onBack = onBack)

        // حقل البحث — أي كتابة ≥ حرفين تُفعّل الترتيب الضبابي فوراً
        com.superbiz.app.ui.components.BizField(
            value = query,
            onValue = { vm.query.value = it },
            label = stringResource(R.string.search_hint_all),
            leading = {
                Icon(Icons.Rounded.Search, null, tint = g.textSecondary)
            }
        )

        Spacer(Modifier.height(8.dp))

        // رقائق آخر عمليات البحث — إعادة سريعة بنقرة + زر مسح السجل
        if (recent.isNotEmpty() && query.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.History, null,
                    tint = g.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                recent.forEach { term ->
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(g.surface)
                            .border(1.dp, g.border, RoundedCornerShape(999.dp))
                            .clickable { vm.query.value = term }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(term, fontSize = 12.sp, color = g.textPrimary, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.search_clear_recent),
                    fontSize = 12.sp,
                    color = g.red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { vm.clearRecent() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (query.trim().length >= 2 && results.isEmpty()) {
            EmptyState(stringResource(R.string.search_no_results), Icons.Rounded.Search)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                // مفاتيح مشتقة من النوع+المعرّف+الدرجة: فريدة لكل نتيجة فعلاً ولا تتقاطع بين الأنواع
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(results, key = { _, h -> "${h.kind}_${h.id}" }) { _, hit ->
                    SearchHitRow(hit, onClick = {
                        vm.record(vm.query.value)
                        // [P36-M4-7] نقل موجّه إلى السجل المستهدف لا إلى جذر الشاشة —
                        // المصفوفة الكاملة موثّقة في deepRouteFor أدناه
                        nav.navigate(deepRouteFor(hit)) { launchSingleTop = true }
                    })
                }
            }
        }
    }
}

/**
 * [P36-M4-7] مصفوفة التنقل العميق لنتائج البحث (M4-7): كل نوع نتيجة يقفز إلى السجل
 * المستهدف عبر أرخص آلية قائمة — بلا كسر أي مسار أو deep-link قائم:
 * - منتج (0) → "inventory?focusProduct=<اسم المنتج>": شاشة المخزون بحقل البحث مضبوطاً
 *   مسبقاً فتُرشَّح القائمة إلى المنتج (وتعديله بلمسة من بطاقته عبر ProductEditor).
 * - طرف (1) → "statement/<id>": كشف الحساب الكامل للطرف — المسار الموجّه القائم منذ
 *   P17-c (نمط DebtsScreen نفسه: STATEMENT.replace("{partyId}", …)).
 * - فاتورة (2) → "invoices?focusInvoice=<رقم الفاتورة>": شاشة الفواتير ببحث مُعدّ
 *   مسبقاً على رقم الفاتورة (المحرر إنشاء-فقط في InvoicesVM — قيد موثّق هناك).
 * - شيك (3) → "checks": لا ترشيح نصي ولا حالة تحرير لشيك قائم في ChecksVM؛
 *   أي تعميق أكبر يتطلب تعديل ChecksScreen.kt/ChecksVM خارج نطاق ملكية هذه الموجة.
 * القيم النصية تُرمَّز بـ Uri.encode (أسماء عربية/مسافات) والتنقل يفكّها ضمنياً.
 */
private fun deepRouteFor(hit: GlobalSearchVM.Hit): String = when (hit.kind) {
    0 -> Routes.INVENTORY + "?focusProduct=" + Uri.encode(hit.title.trim())
    1 -> Routes.STATEMENT.replace("{partyId}", hit.id.toString())
    2 -> Routes.INVOICES + "?focusInvoice=" + Uri.encode(hit.title.trim())
    else -> Routes.CHECKS
}

@Composable
private fun SearchHitRow(hit: GlobalSearchVM.Hit, onClick: () -> Unit) {
    val g = glassColors()
    // [P6-M52 إصلاح] توطين تسميات دور الطرف الصلبة القادمة من GlobalSearchVM (عميل/مورد/؟)
    // الـVM خارج نطاق ملكية هذه الموجة، فالتوطين يحدث في طبقة العرض بمطابقة القيم المرسلة
    val subtitle = when (hit.kind) {
        1 -> when (hit.subtitle) {
            "عميل" -> stringResource(R.string.party_role_customer)
            "مورد" -> stringResource(R.string.party_role_supplier)
            else -> hit.subtitle
        }
        2, 3 -> if (hit.subtitle.startsWith("؟"))
            stringResource(R.string.party_role_unknown) + hit.subtitle.removePrefix("؟")
        else hit.subtitle
        else -> hit.subtitle
    }
    val (label, tint) = when (hit.kind) {
        0 -> stringResource(R.string.search_type_product) to Color(0xFF22D3EE)
        1 -> stringResource(R.string.search_type_party) to Color(0xFFA78BFA)
        2 -> stringResource(R.string.search_type_invoice) to Color(0xFF34D399)
        else -> stringResource(R.string.search_type_check) to Color(0xFFFBBF24)
    }
    GlassCard(corner = 16.dp, modifier = Modifier.clickable { onClick() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(label, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hit.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary,
                    maxLines = 1
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary,
                    maxLines = 1
                )
            }
            Text(
                "${(hit.score * 100).toInt()}%",
                fontSize = 12.sp,
                color = g.textSecondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
