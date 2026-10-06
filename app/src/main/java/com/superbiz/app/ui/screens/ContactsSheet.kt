package com.superbiz.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.superbiz.app.data.db.Party
import com.superbiz.app.export.DeviceContactsExporter
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ═════════ تطبيع البحث العربي — دوال نقية قابلة للاختبار بلا أندرويد ═════════

/**
 * : تطبيع نص عربي — همزات الألف (أ/إ/آ) إلى ألف، التاء المربوطة هاء،
 * الواو بالهمزة واو، الياء بالهمزة ياء، حذف التشكيل (\u064B-\u0652)،
 * وتصغير الحروف اللاتينية. تحويل حرفي نقي بلا اعتماد على أي سياق.
 *
 * [P7-L3 إصلاح] صارت مفوَّضة إلى TextMath.arabicNormalize — التطبيع القياسي
 * الموحّد للمشروع — بعد أن كان عرفاً موازياً بفجوة ى/ٱ/التطويل: جهة باسم
 * «على» أو «شــركة» كانت لا تُلتقط بالبحث عن «علي»/«شركة» رغم تكافؤها.
 * الدلتا الموثقة عن العرف القديم المحلي: كان يصغّر كل محرف بـlowercaseChar
 * بينما القياسي يصغّر A-Z فقط — متطابقان عملياً على أسماء جهات الاتصال
 * (عربية أو لاتينية قياسية)، وأرقام الهاتف تمر بدالة مرقمنة مستقلة كما هي.
*/
internal fun normalizeArabicText(s: String): String =
    com.superbiz.app.domain.algo.TextMath.arabicNormalize(s)

/**أرقام الهاتف فقط — تحويل الأرقام العربية-الهندية (٠-٩) والفارسية (۰-۹) وحذف كل ما عداهما */
internal fun normalizePhoneDigits(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when {
            c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
            c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
            c in '0'..'9' -> sb.append(c)
        }
    }
    return sb.toString()
}

/**مفتاح موحّد للمقارنة والبحث — «الاسم|الرقم» بعد التطبيع */
internal fun normalizeContactKey(name: String, phone: String): String =
    normalizeArabicText(name) + "|" + normalizePhoneDigits(phone)

/**جهة اتصال من الجهاز — الرقم كما ورد + نسخته المرقمنة للمقارنة السريعة */
internal data class DeviceContact(val name: String, val phone: String) {
    val digits: String = normalizePhoneDigits(phone)
}

/**تصفية جهات الاتصال بالاسم أو أرقام الهاتف بعد التطبيع العربي */
internal fun filterContacts(list: List<DeviceContact>, query: String): List<DeviceContact> {
    if (query.isBlank()) return list
    val qn = normalizeArabicText(query)
    val qd = normalizePhoneDigits(query)
    if (qn.isBlank() && qd.isBlank()) return list
    return list.filter { c ->
        (qn.isNotBlank() && normalizeArabicText(c.name).contains(qn)) ||
            (qd.isNotBlank() && c.digits.contains(qd))
    }
}

/**فحص مباشر لإذن قراءة جهات الاتصال (نمط PermissionsScreen) */
private fun contactsPermissionGranted(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/** [P11-b] فحص مباشر لإذن كتابة جهات الاتصال — التصدير العكسي (نفس النمط) */
private fun writeContactsPermissionGranted(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * : قراءة جهات الاتصال ذات الأرقام مرة واحدة — الاسم والرقم فقط.
 * إزالة التكرار بـ distinctBy قبل العرض كخط دفاع ثانٍ؛ أما خط الدفاع الأول
 * فمفاتيح LazyColumn موضعية ("c$i") إجبارية — تكرار الاسم+الرقم بين جهتين
 * كان أطاح بالتطبيق سابقاً بمفتاح مكرر (IllegalArgumentException) ولا يعود.
*/
private fun loadDeviceContacts(ctx: Context): List<DeviceContact> {
    val out = ArrayList<DeviceContact>()
    try {
        val pUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val pName = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val pNum = ContactsContract.CommonDataKinds.Phone.NUMBER
        ctx.contentResolver.query(
            pUri,
            arrayOf(pName, pNum),
            null, null,
            pName + " COLLATE NOCASE ASC"
        )?.use { c ->
            val ni = c.getColumnIndexOrThrow(pName)
            val pi = c.getColumnIndexOrThrow(pNum)
            while (c.moveToNext()) {
                val name = c.getString(ni)?.trim().orEmpty()
                val num = c.getString(pi)?.trim().orEmpty()
                if (num.isNotBlank()) out.add(DeviceContact(name.ifBlank { num }, num))
            }
        }
    } catch (e: Exception) {
        // لا انهيار — مزوّد جهات الاتصال المعطّل/المقيّد يرمي استثناءً فنُرجع ما جمعناه فقط
    }
    return out.distinctBy { it.name to it.phone }
}

/**
 * : لوحة سفلية لاستيراد جهات اتصال الجهاز كأطراف (عملاء/موردين).
 * نفس أسلوب الزجاج المعتمد في بقية لوحات التطبيق (غطاء + GlassCard أسفل الشاشة).
 * [P10] الاستيراد عبر AppGraph مباشرة (upsertAll دفعة واحدة) — وتدفق Room التفاعلي
 * يحدّث قوائم الأطراف في الذمم ونقطة البيع والفواتير تلقائياً بلا أي refresh يدوي،
 * لذا لا تحتاج اللوحة أي ViewModel: توقيتها صار (onImported, onDismiss) فقط —
 * نقاط الدخول: الذمم + منتقي العميل في نقطة البيع + منتقي الطرف في محرر الفواتير.
 *
 * [P11-b] وضع ثانٍ مضاف باللوحة نفسها: التصدير العكسي — حفظ أطراف التطبيق في
 * جهات اتصال الجهاز (الاسم والرقم والملاحظة فقط) عبر DeviceContactsExporter،
 * ببوابة WRITE_CONTACTS بنفس نمط الإذن القائم (منح + إعادة فحص + رفض مرتين → إعدادات).
 * الاستيراد يبقى الوضع الافتراضي والسلوك فيه لم يُمس.
*/
@Composable
fun ContactsSheet(
    onImported: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val g = glassColors()

    // ─── [P11-b] وضع اللوحة: 0 استيراد (الافتراضي) / 1 تصدير عكسي ───
    var mode by remember { mutableStateOf(0) }

    // ─── حالة الإذن (استيراد: قراءة) ───
    var permGranted by remember { mutableStateOf(contactsPermissionGranted(context)) }
    var deniedCount by remember { mutableStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permGranted = ok
        if (!ok) deniedCount++
    }

    // ─── [P11-b] حالة الإذن (تصدير: كتابة) — نفس نمط القراءة تماماً ───
    var writeGranted by remember { mutableStateOf(writeContactsPermissionGranted(context)) }
    var wDeniedCount by remember { mutableStateOf(0) }
    val wPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        writeGranted = ok
        if (!ok) wDeniedCount++
    }

    // إعادة الفحص الحي عند العودة من إعدادات النظام (نمط PermissionsScreen)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permGranted = contactsPermissionGranted(context)
                // [P11-b] إذن الكتابة يُعاد فحصه معه
                writeGranted = writeContactsPermissionGranted(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ─── تحميل جهات الاتصال مرة واحدة على الخيط الخلفي (استيراد) ───
    var contacts by remember { mutableStateOf<List<DeviceContact>?>(null) }
    LaunchedEffect(permGranted) {
        if (permGranted && contacts == null) {
            contacts = withContext(Dispatchers.IO) { loadDeviceContacts(context) }
        }
    }

    // ─── [P11-b] أطراف التطبيق للتصدير — الحيون بلا أرشفة ومنهم من لديه رقم ───
    var parties by remember { mutableStateOf<List<Party>?>(null) }
    LaunchedEffect(writeGranted) {
        if (writeGranted && parties == null) {
            parties = withContext(Dispatchers.IO) {
                com.superbiz.app.AppGraph.from(context.applicationContext)
                    .db.parties().allOnce()
                    .filter { !it.archived && it.phone.isNotBlank() }
            }
        }
    }

    // ─── حالة الواجهة ───
    var query by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(0) } // 0 عميل 1 مورد 2 كلاهما
    val selected = remember { mutableStateListOf<DeviceContact>() }
    // [P11-b] تحديد أطراف التصدير
    val selectedExport = remember { mutableStateListOf<Party>() }
    // [P6-M49 إصلاح] علم تنفيذ الاستيراد — يمنع النقر المزدوج ويعكس الحالة أثناء الدفعة
    var importing by remember { mutableStateOf(false) }
    // [P11-b] علم تنفيذ التصدير — النمط نفسه
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { onDismiss() }

    // النصوص تُحل في السياق القابل للتركيب
    val titleStr = stringResourceCompat(R.string.contacts_title)
    val explainStr = stringResourceCompat(R.string.contacts_explain)
    val grantStr = stringResourceCompat(R.string.contacts_grant)
    val deniedHintStr = stringResourceCompat(R.string.contacts_denied_hint)
    val openSettingsStr = stringResourceCompat(R.string.perm_open_settings)
    val searchHintStr = stringResourceCompat(R.string.contacts_search_hint)
    val importStr = stringResourceCompat(R.string.contacts_import)
    val noResultsStr = stringResourceCompat(R.string.contacts_no_results)
    val noPhoneStr = stringResourceCompat(R.string.contacts_empty_phone)
    val noneStr = stringResourceCompat(R.string.contacts_none)
    // [P11-b] نصوص وضع التصدير العكسي
    val modeImportStr = stringResourceCompat(R.string.contacts_mode_import)
    val modeExportStr = stringResourceCompat(R.string.contacts_mode_export)
    val wExportRationaleStr = stringResourceCompat(R.string.contacts_wexport_rationale)
    val exportBtnStr = stringResourceCompat(R.string.contacts_export_btn)
    val exportNoneStr = stringResourceCompat(R.string.contacts_export_none)
    val roleStrs = listOf(
        stringResourceCompat(R.string.contacts_role_customer),
        stringResourceCompat(R.string.contacts_role_supplier),
        stringResourceCompat(R.string.contacts_role_both)
    )

    fun importSelected() {
        if (selected.isEmpty() || importing) return
        importing = true
        // [P6-M49 إصلاح] عاصفة refresh: كان كل طرف يستدعي vm.saveParty (تحديث كامل لقائمة
        // الذمم مع risk N+1 لكل إدراج) ثم refresh ختامي ≈ N×(M+1)×2 استعلاماً. الآن:
        // 1) الأطراف الموجودون تُحمّل دفعة واحدة قبل الحلقة (خريطة هاتف مرقمن ← معرف)
        // 2) الجديد يُدرج دفعة واحدة عبر PartyDao.upsertAll على IO
        // 3) تحديث واحد بعد اكتمال الاستيراد عبر onImported() القائم
        val toImport = selected.toList()
        val roleValue = role
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val graph = com.superbiz.app.AppGraph.from(context.applicationContext)
                    val phoneToId = graph.db.parties().allOnce()
                        .map { normalizePhoneDigits(it.phone) to it.id }
                        .toMap()
                    val seenInBatch = HashSet<String>()
                    val fresh = ArrayList<Party>()
                    var dups = 0
                    for (c in toImport) {
                        if (c.name.isBlank()) { dups++; continue } // مرفوض في مسار الحفظ أصلاً
                        if (c.digits.isNotBlank() && (c.digits in phoneToId || c.digits in seenInBatch)) {
                            dups++; continue
                        }
                        if (c.digits.isNotBlank()) seenInBatch.add(c.digits)
                        fresh.add(
                            Party(id = 0, name = c.name.trim(), phone = c.phone.trim(), type = roleValue, note = "")
                        )
                    }
                    if (fresh.isNotEmpty()) graph.db.parties().upsertAll(fresh)
                    fresh.size to dups
                }.getOrDefault(0 to toImport.size)
            }
            val (imported, dups) = result
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.contacts_imported_summary, imported, dups),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            importing = false
            onImported()
            onDismiss()
        }
    }

    /**
     * [P11-b] تصدير المحدد إلى جهات اتصال الجهاز — الاسم والرقم والملاحظة فقط.
     * كل العمل على IO عبر DeviceContactsExporter (مكتفٍ بذاته)، والملخص يذكر
     * المصدر والمكرر، وفشل جزئي يُعلن بتنبيه ثانٍ صادق.
     */
    fun exportSelected() {
        if (selectedExport.isEmpty() || exporting) return
        exporting = true
        val toExport = selectedExport.toList()
        scope.launch {
            val stats = withContext(Dispatchers.IO) {
                DeviceContactsExporter.export(context.applicationContext, toExport)
            }
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.contacts_exported_summary, stats.exported, stats.skippedExisting),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            if (stats.failed > 0) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.contacts_export_failed_count, stats.failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            exporting = false
            onImported() // تدفق Room التفاعلي يعني أن التحديث غير ضروري — نداء ودّي بلا ضرر
            onDismiss()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() }
    ) {
        GlassCard(
            corner = 28.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .height(560.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) { }
                    .padding(18.dp)
            ) {
                Text(titleStr, style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
                Spacer(Modifier.height(10.dp))

                // [P11-b] مبدّل الوضع — استيراد (افتراضي) / تصدير عكسي، بنفس FilterPill القائمة
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(modeImportStr, mode == 0) { mode = 0 }
                    FilterPill(modeExportStr, mode == 1) { mode = 1 }
                }
                Spacer(Modifier.height(10.dp))

                val loaded = contacts
                val loadedParties = parties
                when {
                    // ═══ وضع الاستيراد — السلوك القائم حرفياً ═══
                    mode == 0 -> when {
                        // ─── بلا إذن: شرح ودّي + زر منح ───
                        !permGranted -> {
                            Text(explainStr, color = g.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionPill(grantStr, Vio) {
                                    permLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                                // رُفض مرتين — الفتح من إعدادات النظام هو السبيل الوحيد
                                if (deniedCount >= 2) {
                                    ActionPill(openSettingsStr, Cyan) {
                                        startIntentSafe(
                                            context,
                                            Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null)
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )
                                    }
                                }
                            }
                            if (deniedCount >= 2) {
                                Spacer(Modifier.height(10.dp))
                                Text(deniedHintStr, color = g.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }

                        // ─── أثناء التحميل ───
                        loaded == null -> {
                            Box(
                                Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        // ─── الجهاز بلا جهات اتصال ───
                        loaded.isEmpty() -> {
                            Box(
                                Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyState(noneStr, Icons.Rounded.Person)
                            }
                        }

                        // ─── القائمة: بحث + أدوار + تحديد متعدد ───
                        else -> {
                            val filtered = remember(loaded, query) { filterContacts(loaded, query) }
                            BizField(query, { query = it }, searchHintStr)
                            Spacer(Modifier.height(8.dp))
                            // صيغة الاستيراد — عميل/مورد/كلاهما (افتراضي عميل)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                roleStrs.forEachIndexed { i, label ->
                                    FilterPill(label, role == i) { role = i }
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            if (filtered.isEmpty()) {
                                Box(
                                    Modifier.fillMaxWidth().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EmptyState(noResultsStr, Icons.Rounded.Person)
                                }
                            } else {
                                LazyColumn(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // مفاتيح موضعية إجبارية — تكرار الاسم+الرقم بين جهتين كان يطيح بمفتاح مكرر
                                    itemsIndexed(filtered, key = { i, _ -> "c$i" }) { _, contact ->
                                        val checked = contact in selected
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(g.textSecondary.copy(alpha = 0.06f))
                                                .clickable {
                                                    if (checked) selected.remove(contact) else selected.add(contact)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = {
                                                    if (checked) selected.remove(contact) else selected.add(contact)
                                                }
                                            )
                                            IconChip(Icons.Rounded.Person, Color.White, Vio)
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    contact.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = g.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    contact.phone.ifBlank { noPhoneStr },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = g.textSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ═══ [P11-b] وضع التصدير العكسي — بوابة إذن الكتابة بنفس النمط ═══
                    !writeGranted -> {
                        Text(wExportRationaleStr, color = g.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill(grantStr, Vio) {
                                wPermLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                            }
                            // رُفض مرتين — الفتح من إعدادات النظام هو السبيل الوحيد (نمط الاستيراد)
                            if (wDeniedCount >= 2) {
                                ActionPill(openSettingsStr, Cyan) {
                                    startIntentSafe(
                                        context,
                                        Intent(
                                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    )
                                }
                            }
                        }
                        if (wDeniedCount >= 2) {
                            Spacer(Modifier.height(10.dp))
                            Text(deniedHintStr, color = g.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }

                    // ─── أثناء تحميل الأطراف ───
                    loadedParties == null -> {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    // ─── لا أطراف لديهم أرقام لتصديرها ───
                    loadedParties.isEmpty() -> {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(exportNoneStr, Icons.Rounded.Person)
                        }
                    }

                    // ─── قائمة الأطراف: تحديد متعدد (نفس تخطيط صفوف الاستيراد) ───
                    else -> {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // معرف قاعدة البيانات فريد — المفتاح الموضعي آمن
                            itemsIndexed(loadedParties, key = { _, party -> "p${party.id}" }) { _, party ->
                                val checked = party in selectedExport
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(g.textSecondary.copy(alpha = 0.06f))
                                        .clickable {
                                            if (checked) selectedExport.remove(party) else selectedExport.add(party)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            if (checked) selectedExport.remove(party) else selectedExport.add(party)
                                        }
                                    )
                                    IconChip(Icons.Rounded.Person, Color.White, Cyan)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            party.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = g.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            party.phone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = g.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // زر الاستيراد — يظهر بعد الإذن فقط، ويُفعَّل عند التحديد
                if (mode == 0 && permGranted && contacts != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected.isEmpty()) g.accent.copy(alpha = 0.25f) else g.accent)
                            .clickable(enabled = selected.isNotEmpty()) { importSelected() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            importStr,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                // [P11-b] زر التصدير — وضع التصدير فقط، نفس نمط زر الاستيراد
                if (mode == 1 && writeGranted && parties != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedExport.isEmpty()) g.accent.copy(alpha = 0.25f) else g.accent)
                            .clickable(enabled = selectedExport.isNotEmpty() && !exporting) { exportSelected() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            exportBtnStr,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
