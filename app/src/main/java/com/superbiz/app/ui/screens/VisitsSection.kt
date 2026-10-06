package com.superbiz.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.superbiz.app.R
import com.superbiz.app.domain.algo.PartyGeo
import com.superbiz.app.domain.algo.VisitReport
import com.superbiz.app.ui.components.Badge
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P12-b] قسم «تقرير زيارات العملاء» — قسم مكتفٍ بذاته يدمَج داخل شاشة المفضّلات
 * (القسم تملكه موجة P12: الشاشة الأم يملكها P12-a ويدمج القسم باستدعاء واحد
 * VisitsSection() بلا أي وسائط — هذا عقد ثابت).
 *
 * ماذا يعرض:
 * - ترويسة + ثلاث رقاقات ملخص: إجمالي الزيارات · آخر 30 يوماً · متأخرة عن الزيارة (عنبري).
 * - صف لكل طرف لديه زيارة واحدة فأكثر، مرتباً بآخر زيارة تنازلياً (حد عرض 15 صفاً
 *   + «وN أطراف أخرى…») داخل Column عادي — ممنوع LazyColumn لأن القسم يجلس داخل
 *   تمرير الشاشة الأم (verticalScroll) وLazy متداخل داخل تمرير أب ينهار/يقيد الارتفاع.
 * - لكل صف: اسم الطرف + شارة عدد الزيارات + «قبل X يوم/أمس/اليوم» + سطر المسافة
 *   عن الموقع المحفوظ (هافرساين عبر PartyGeo) حين يمكن حسابها بصدق، وتمييز عنبري
 *   للأطراف التي مضى على آخر زيارتها أكثر من 14 يوماً.
 * - رقاقة «زيارة» لكل صف: تلتقط موقع اللحظة وتسجّل زيارة للطرف.
 *
 * [P13-a] حذف سجل الزيارة: كل صف له زر حذف (IconButton 48dp في نهاية الصف — يُقلب
 * تلقائياً لآخر الصف في RTL) يفتح تأكيد AlertDialog؛ التنفيذ يحذف «أحدث» زيارة للطرف
 * (الصف مجمّع لكل طرف، وسجل الزيارة المعروض هو الأحدث) عبر طبقة الزيارات
 * (VisitsRepo.deleteVisit على Dispatchers.IO — نمط recordVisit نفسه في هذا القسم
 * المكتفٍ بذاته، عقد استدعائه بلا وسائط ثابت من P12). التقرير مبني فوق تدفق Room
 * حي فيتحدّث تلقائياً (المجاميع والشارات و«آخر زيارة») بلا أي refresh يدوي،
 * ومع علم in-flight يمنع النقر المزدوج أثناء الحذف.
 *
 * تسجيل الزيارة (عقد LocationCapture من P12-a — نفس الحزمة، يكتمل الربط بعد الدمج):
 * - إذن ACCESS_FINE_LOCATION يُدار هنا بنمط ContactsSheet حرفياً:
 *   rememberLauncherForActivityResult(RequestPermission) + عدّاد رفض
 *   (نص تعليل بعد أول رفض، ورقاقة إعدادات التطبيق بعد رفضين) + إعادة فحص ON_RESUME.
 * - خدمات الموقع معطلة (قبل الالتقاط أو عبر نتيجة GpsOff) ⇒ سطر تنبيه عنبري
 *   + رقاقة فتح ACTION_LOCATION_SOURCE_SETTINGS عبر startIntentSafe، وتُرفع
 *   الحالة تلقائياً عند العودة من الإعدادات إن أُفعّلت (ON_RESUME).
 * - نتيجة Fixed ⇒ الكتابة على Dispatchers.IO ثم Toast على الخيط الرئيسي
 *   (الترتيب كما في العقد: سجّل ثم أخبر)، وFailed ⇒ Toast تعذر الالتقاط.
 *
 * صدق البيانات (موثق أيضاً عند كيان Visit وفي VisitReport):
 * - الزيارة سجل تاريخي بلا مفتاح أجنبي: حذف طرف لا يمس سجل زياراته، والصفوف
 *   اليتيمة بلا ضرر على أي استعلام — التقرير يتخطى الأطراف المحذوفة عند الربط
 *   فقط، لذا قد يظهر الإجمالي أعلى من مجموع الصفوف في حالة الأيتام النادرة
 *   (عدّ صادق للدفتر لا تزيين).
 * - الحالة الفارغة (لا صفوف ظاهرة) تعرض نص «لا زيارات مسجلة بعد» بصادق.
 */
@Composable
fun VisitsSection() {
    val context = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()

    // عقد التكوين: AppGraph من سياق التطبيق (مرة واحدة لعمر التركيب)
    val graph = remember { com.superbiz.app.AppGraph.from(context.applicationContext) }

    // تدفقات حية تُجمَع مرة واحدة لعمر التركيب (remember يمنع إعادة إنشاء Flow كل recomposition)
    val partiesFlow = remember(graph) { graph.db.parties().all() }
    val visitsFlow = remember(graph) { graph.visits.all() }
    val parties by partiesFlow.collectAsState(initial = emptyList())
    val visits by visitsFlow.collectAsState(initial = emptyList())

    // بناء التقرير عند تغيّر المدخلات فقط — الآن لحظة البناء (كفاية لعرض الأيام النسبية)
    val report = remember(visits, parties) {
        VisitReport.summarize(visits, parties, System.currentTimeMillis())
    }

    // ─── إذن الموقع الدقيق + حالة خدمات الموقع (نمط ContactsSheet/GeoCard) ───
    var permGranted by remember { mutableStateOf(fineLocationGranted(context)) }
    var deniedCount by remember { mutableStateOf(0) }
    var gpsOff by remember { mutableStateOf(false) }
    var pendingPartyId by remember { mutableStateOf<Long?>(null) }

    // [P13-a] حذف سجل زيارة: الصف المطلوب حذف أحدث زياراته (بانتظار التأكيد) + الطرف قيد الحذف
    var pendingDelete by remember { mutableStateOf<VisitReport.Row?>(null) }
    var deletingPartyId by remember { mutableStateOf<Long?>(null) }

    // إعادة الفحص الحي عند العودة من إعدادات النظام (الإذن أو خدمات الموقع قد تغيّرت)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permGranted = fineLocationGranted(context)
                // نرفع تنبيه GpsOff فقط إن كان ظاهراً (لا نُظهره من تلقاء أنفسنا دون محاولة)
                if (gpsOff) gpsOff = !LocationCapture.isAnyProviderEnabled(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // الالتقاط والتسجيل — يُستدعى فقط بعد تأكيد توفر الإذن
    fun captureAndRecord(partyId: Long) {
        if (!LocationCapture.isAnyProviderEnabled(context)) {
            gpsOff = true
            return
        }
        LocationCapture.capture(context) { res ->
            when (res) {
                is LocationCapture.Result.Fixed -> scope.launch {
                    // التسجيل على IO ثم Toast على الخيط الرئيسي (launch النطاق رئيسي)
                    withContext(Dispatchers.IO) {
                        graph.visits.recordVisit(partyId, res.lat, res.lng, "")
                    }
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.visit_logged_toast),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                LocationCapture.Result.GpsOff -> gpsOff = true
                LocationCapture.Result.Failed ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.fav_location_failed), // مفتاح قائم من P11-a
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permGranted = ok
        if (!ok) deniedCount++
        val pid = pendingPartyId
        pendingPartyId = null
        if (ok && pid != null) captureAndRecord(pid)
    }

    // بوابة التسجيل: إذن ممنوح ⇒ التقط فوراً، وإلا اطلب الإذن ثم أكمل لطرفه المعلّق
    fun requestLog(partyId: Long) {
        if (fineLocationGranted(context)) {
            captureAndRecord(partyId)
        } else {
            pendingPartyId = partyId
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // [P13-a] تنفيذ الحذف بعد التأكيد: أحدث زيارة للطرف (السجل المعروض في الصف) على IO
    // — منع التكرار بعلم in-flight، والشارات/المجاميع تتحدّث تلقائياً من تدفق Room
    fun performDelete(row: VisitReport.Row) {
        if (deletingPartyId != null) return
        deletingPartyId = row.partyId
        scope.launch {
            withContext(Dispatchers.IO) {
                graph.visits.forParty(row.partyId).firstOrNull()?.let { latest ->
                    graph.visits.deleteVisit(latest.id)
                }
            }
            deletingPartyId = null
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ─── الترويسة ───
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResourceCompat(R.string.visit_report_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = g.textPrimary
                )
                Text(
                    stringResourceCompat(R.string.visit_report_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary,
                    lineHeight = 17.sp
                )
            }

            // ─── رقاقات الملخص الثلاث ───
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SumChip(
                    Modifier.weight(1f),
                    Icons.Rounded.EventNote,
                    report.totalVisits.toString(),
                    stringResourceCompat(R.string.visit_sum_total),
                    g.vio, g.chipVio
                )
                SumChip(
                    Modifier.weight(1f),
                    Icons.Rounded.History,
                    report.last30.toString(),
                    stringResourceCompat(R.string.visit_sum_30),
                    g.cyan, g.chipCyan
                )
                SumChip(
                    Modifier.weight(1f),
                    Icons.Rounded.WarningAmber,
                    report.overdue.size.toString(),
                    stringResourceCompat(R.string.visit_sum_overdue),
                    g.amber, g.chipAmber
                )
            }

            // ─── تنبيه خدمات الموقع المعطلة (يظهر عند المحاولة أو نتيجة GpsOff) ───
            if (gpsOff) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.chipAmber)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.WarningAmber, null,
                        tint = g.amber, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResourceCompat(R.string.fav_gps_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.amber,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionPill(stringResourceCompat(R.string.fav_open_location_settings), g.amber) {
                        startIntentSafe(
                            context,
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }

            // ─── تعليل الإذن بعد أول رفض + إعدادات التطبيق بعد رفضين (نمط ContactsSheet) ───
            if (deniedCount >= 2) {
                ActionPill(stringResourceCompat(R.string.perm_open_settings), g.cyan) {
                    startIntentSafe(
                        context,
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            if (deniedCount >= 1 && !permGranted) { // التعليل يختفي تلقائياً بعد المنح (ON_RESUME/اللاحق)
                Text(
                    stringResourceCompat(R.string.fav_loc_rationale),
                    fontSize = 11.sp, color = g.textSecondary, lineHeight = 16.sp
                )
            }

            // ─── الصفوف أو الحالة الفارغة ───
            if (report.rows.isEmpty()) {
                EmptyState(stringResourceCompat(R.string.visit_empty), Icons.Rounded.EventAvailable)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // حد عرض 15 صفاً — القسم داخل تمرير الأب فلا LazyColumn، و«وN المزيد» صادقة
                    report.rows.take(15).forEach { row ->
                        val days = VisitReport.daysSince(row.lastVisitAt, report.now)
                        val overdueRow = days > report.overdueDays
                        VisitRowItem(
                            row = row,
                            lastLabel = lastVisitLabel(days),
                            overdue = overdueRow,
                            overdueDays = report.overdueDays,
                            deleteEnabled = deletingPartyId != row.partyId,
                            onLog = { pid -> requestLog(pid) },
                            onDeleteVisit = { pendingDelete = row }
                        )
                    }
                    val hidden = report.rows.size - 15
                    if (hidden > 0) {
                        Text(
                            stringResourceCompat(R.string.visit_more_rows, hidden),
                            style = MaterialTheme.typography.labelMedium,
                            color = g.textSecondary
                        )
                    }
                }
            }
        }
    }

    // [P13-a] تأكيد حذف سجل الزيارة — نفس نمط AlertDialog في ExpensesScreen
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { if (deletingPartyId == null) pendingDelete = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.visit_delete_title), color = g.textPrimary) },
            text = { Text(stringResourceCompat(R.string.visit_delete_body), color = g.textPrimary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        performDelete(row)
                    },
                    enabled = deletingPartyId == null
                ) {
                    Text(
                        stringResourceCompat(R.string.delete),
                        color = RedDeep,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }
}

/** [P12-b] فحص إذن الموقع الدقيق — نفس أسلوب ContactsSheet/GeoCard */
private fun fineLocationGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

/**
 * [P12-b] نص «آخر زيارة» النسبي: اليوم/أمس/قبل N يوم —
 * daysSince لا يعيد سالباً أبداً فالصفر يعني اليوم.
 */
@Composable
private fun lastVisitLabel(daysRaw: Int): String {
    val days = VisitReport.relativeDays(daysRaw)
    return when {
        days <= 0 -> stringResourceCompat(R.string.visit_last_today)
        days == 1 -> stringResourceCompat(R.string.visit_last_yesterday)
        else -> stringResourceCompat(R.string.visit_last_days, days)
    }
}

/** [P12-b] رقاقة ملخص مصغّرة — نفس لغة IconChip الزجاجية */
@Composable
private fun SumChip(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color
) {
    val g = glassColors()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(g.textSecondary.copy(alpha = 0.06f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(icon, tint, bg, size = 30.dp, iconSize = 15.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = g.textPrimary,
                maxLines = 1
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = g.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * [P12-b] صف طرف في التقرير: الاسم + شارة العدد، سطر آخر زيارة (عنبري إن كان
 * متأخراً + تلميح العتبة)، وسطر المسافة عن الموقع المحفوظ حين تكون الحساب ممكناً،
 * ورقاقة «زيارة» الختامية لتسجيل زيارة جديدة لطرفه.
 *
 * [P13-a] زر حذف سجل الزيارة في نهاية الصف (IconButton الافتراضي 48dp فوق حد
 * الـ44dp، يُقلب تلقائياً لآخر الصف في RTL) — يفتح تأكيد الحذف في القسم الأب،
 * ويُعطَّل أثناء حذف هذا الصف (منع النقر المزدوج).
 */
@Composable
private fun VisitRowItem(
    row: VisitReport.Row,
    lastLabel: String,
    overdue: Boolean,
    overdueDays: Int,
    deleteEnabled: Boolean,
    onLog: (Long) -> Unit,
    onDeleteVisit: () -> Unit
) {
    val g = glassColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                // شارة عدد الزيارات لهذا الطرف
                Badge(row.count.toString(), g.cyan, g.chipCyan)
            }
            Text(
                lastLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) g.amber else g.textSecondary
            )
            if (overdue) {
                Text(
                    stringResourceCompat(R.string.visit_overdue_hint, overdueDays),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.amber
                )
            }
            // المسافة تظهر فقط حين تكتمل الرباعية الصالحة (صدق VisitReport.Row.distanceMeters)
            row.distanceMeters?.let { meters ->
                Text(
                    stringResourceCompat(R.string.visit_distance_line, PartyGeo.deltaLabel(meters)),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        ActionPill(stringResourceCompat(R.string.visit_row_log), g.cyan) { onLog(row.partyId) }
        // [P13-a] حذف سجل الزيارة — نهاية الصف (RTL يقلبه تلقائياً)، معطّل أثناء الحذف
        IconButton(
            onClick = onDeleteVisit,
            enabled = deleteEnabled
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResourceCompat(R.string.visit_delete_title),
                tint = if (deleteEnabled) g.textSecondary else g.textSecondary.copy(alpha = 0.35f)
            )
        }
    }
}
