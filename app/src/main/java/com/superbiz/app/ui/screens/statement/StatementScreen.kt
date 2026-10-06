package com.superbiz.app.ui.screens.statement

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SectionTitle
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.vm.AppVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P17-c] شاشة كشف الحساب الكاملة (المسار: statement/{partyId}) — RTL أصلي.
 *
 * كل البيانات من عقد 17-a عبر StatementUiFacade، وكل توليد عبر 17-b —
 * المعاينة الحية أسفلها ملف PDF حقيقي يُرسم بـ android.graphics.pdf.PdfRenderer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatementScreen(appVM: AppVM, partyId: Long, nav: NavHostController) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val g = glassColors()
    val scope = rememberCoroutineScope()
    val settings by appVM.settings.collectAsState()
    val ar = settings.language == "ar"
    val symbol by appVM.symbol.collectAsState()

    // ── حالة الشاشة ──
    var party by remember { mutableStateOf<com.superbiz.app.data.db.Party?>(null) }
    var txCount by remember { mutableStateOf(-1) } // -1 = قيد القياس
    var preset by remember { mutableStateOf(UiPeriodPreset.THIS_MONTH) }
    var customFrom by remember { mutableStateOf(0L) }
    var customTo by remember { mutableStateOf(0L) }
    var lang by remember {
        mutableStateOf(if (settings.language == "ar") StatementUiFacade.UiLang.AR else StatementUiFacade.UiLang.EN)
    }
    var templateId by remember { mutableStateOf(StatementUiFacade.defaultTemplateId) }
    var showElements by remember { mutableStateOf(StatementUiFacade.QUICK_ELEMENT_NAMES.toSet()) }
    var note by remember { mutableStateOf("") }
    var signatureId by remember { mutableStateOf<Long?>(null) }
    var stampId by remember { mutableStateOf<Long?>(null) }
    var signatureBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var stampBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var sigList by remember { mutableStateOf(listOf<StatementUiFacade.SignatureUi>()) }
    var stampList by remember { mutableStateOf(listOf<StatementUiFacade.StampUi>()) }
    var noteTemplates by remember { mutableStateOf(listOf<StatementUiFacade.NoteTemplateUi>()) }
    var history by remember { mutableStateOf(listOf<StatementUiFacade.HistoryUi>()) }
    // [P17-c] القوالب الظاهرة: مدمجة + مخصصة — تُحمَّل مرة واحدة ثم يُبنى عليها كل شيء
    var allDefs by remember { mutableStateOf(StatementUiFacade.allTemplates()) }
    var historyOpen by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var lastIssued by remember { mutableStateOf<StatementUiFacade.HistoryUi?>(null) }
    var dupDialog by remember { mutableStateOf(false) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val logo = appVM.avatarBitmap()

    val (fromTs, toTs) = resolvePeriod(preset, java.util.Calendar.getInstance(), customFrom, customTo)

    // ── تحميل أولي: الطرف + الإعدادات المحفوظة + قوائم التوقيع/الختم/الملاحظات ──
    LaunchedEffect(partyId) {
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            party = StatementUiFacade.partyBrief(ctx, partyId)
            txCount = try {
                com.superbiz.app.AppGraph.from(ctx).ledger.statement(partyId).size
            } catch (_: Exception) { 0 }
            allDefs = StatementUiFacade.allTemplatesOf(ctx)
            templateId = StatementUiFacade.defaultTemplateIdPref(ctx)
                ?: StatementUiFacade.defaultTemplateId
            note = StatementUiFacade.defaultNote(ctx)
            runCatching { sigList = StatementUiFacade.signatures(ctx) }
            runCatching { stampList = StatementUiFacade.stamps(ctx) }
            runCatching { noteTemplates = StatementUiFacade.noteTemplates(ctx) }
            runCatching { history = StatementUiFacade.historyFor(ctx, partyId) }
            signatureId = sigList.firstOrNull { it.isDefault }?.id ?: sigList.firstOrNull()?.id
            stampId = stampList.firstOrNull { it.isDefault }?.id ?: stampList.firstOrNull()?.id
        }
    }

    // عناصر show من نمط القالب الحالي عند تغيير القالب أو اكتمال قائمة القوالب
    LaunchedEffect(templateId, allDefs) {
        val def = allDefs.firstOrNull { it.id == templateId }
        if (def != null) showElements = StatementUiFacade.styleUiOf(def.style).show
    }

    // قالب مُختار من الاستوديو (نتيجة عبر savedStateHandle)
    val backEntry = nav.currentBackStackEntry
    val pickedTemplate: StateFlow<String>? =
        remember { backEntry?.savedStateHandle?.getStateFlow("st_selected_template", "") }
    LaunchedEffect(pickedTemplate) {
        pickedTemplate?.collect { id ->
            if (id.isNotBlank()) {
                templateId = id
                StatementUiFacade.setDefaultTemplateIdPref(context.applicationContext, id)
                nav.currentBackStackEntry?.savedStateHandle?.set("st_selected_template", "")
            }
        }
    }

    // تحميل صور التوقيع/الختم عند تغيير الاختيار
    LaunchedEffect(signatureId, sigList) {
        val path = sigList.firstOrNull { it.id == signatureId }?.filePath
        signatureBmp = withContext(Dispatchers.IO) { path?.let { decodeScaled(it) } }
    }
    LaunchedEffect(stampId, stampList) {
        val path = stampList.firstOrNull { it.id == stampId }?.filePath
        stampBmp = withContext(Dispatchers.IO) { path?.let { decodeScaled(it) } }
    }

    val curDef = allDefs.firstOrNull { it.id == templateId }

    // بناء النمط الفعلي من قالب + عناصر المستخدم — مسار واحد لكل من المعاينة والإصدار
    fun buildStyle(): com.superbiz.app.pdf.statement.StatementStyle {
        val def = curDef
            ?: allDefs.firstOrNull { it.id == StatementUiFacade.defaultTemplateId }
            ?: StatementUiFacade.allTemplates().first()
        return StatementUiFacade.applyStyleUi(
            def.style,
            StatementUiFacade.styleUiOf(def.style).copy(show = showElements)
        )
    }

    val debounceKey = remember(
        partyId, fromTs, toTs, lang, note, templateId, showElements, signatureId, stampId
    ) { Any() }

    fun shareFile(path: String, chooserTitle: String) {
        val file = java.io.File(path)
        if (!file.exists()) {
            Toast.makeText(context, context.getString(R.string.st_file_missing), Toast.LENGTH_SHORT).show()
            return
        }
        // [P17-c] نمط InvoicePdf.share — FileProvider بسلطان com.superbiz.app.fileprovider
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.superbiz.app.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        com.superbiz.app.util.startIntentSafe(
            context, Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    // الجسم: Box يتضمن العمود + مضيف Snackbar أسفله
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        SubHeader(stringResourceCompat(R.string.st_title), onBack = { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate(Routes.STATEMENT_TEMPLATES + "?select=0") }) {
                Icon(Icons.Rounded.Palette, null, tint = Vio)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── الترويسة: اسم العميل + الهاتف + سطر فراغ الحركات ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    party?.name ?: "…",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    party?.phone?.ifBlank { "" } ?: "",
                                    fontSize = 12.sp, color = g.textSecondary
                                )
                            }
                            history.firstOrNull()?.let { h ->
                                Icon(Icons.Rounded.Verified, null, tint = GreenDeep, modifier = Modifier.width(18.dp).height(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(h.number, fontSize = 11.sp, color = g.textSecondary, maxLines = 1)
                            }
                        }
                        // [P17-c] صدر عن فراغ الحركات: كشف صالح بجدول فارغ ومجاميع صفر — الإنتاج غير معطّل
                        if (txCount == 0) StHint(stringResourceCompat(R.string.st_empty_tx))
                    }
                }
            }

            // ── الفترة ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResourceCompat(R.string.st_period))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            UiPeriodPreset.entries.forEach { p ->
                                StChip(presetLabel(p, ar), preset == p) {
                                    preset = p
                                }
                            }
                        }
                        if (preset == UiPeriodPreset.CUSTOM) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StChip(
                                    stringResourceCompat(R.string.stmt_from_prefix) + " " + Dates.short(fromTs), false
                                ) { showFromPicker = true }
                                StChip(
                                    stringResourceCompat(R.string.stmt_to_prefix) + " " + Dates.short(toTs), false
                                ) { showToPicker = true }
                            }
                        }
                        Text(
                            stringResourceCompat(R.string.st_period_range, Dates.short(fromTs), Dates.short(toTs)),
                            fontSize = 11.sp, color = g.textSecondary
                        )
                    }
                }
            }

            // ── القالب ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResourceCompat(R.string.st_template))
                        Text(
                            (if (ar) curDef?.nameAr else curDef?.nameEn) ?: templateId,
                            fontWeight = FontWeight.Bold, color = g.textPrimary, fontSize = 14.sp
                        )
                        Text(
                            (if (ar) curDef?.descAr else curDef?.descEn) ?: "",
                            fontSize = 12.sp, color = g.textSecondary, maxLines = 2
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StAction(stringResourceCompat(R.string.st_change_template), Vio) {
                                nav.navigate(Routes.STATEMENT_TEMPLATES + "?select=1")
                            }
                            StAction(stringResourceCompat(R.string.st_edit_copy), Cyan) {
                                dupDialog = true
                            }
                        }
                    }
                }
            }

            // ── التوقيع/الختم/الشعار + عناصر PDF ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResourceCompat(R.string.st_elements))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            // التوقيع
                            SelectorChip(
                                label = sigList.firstLabel(signatureId, stringResourceCompat(R.string.stmt_signature_none)),
                                options = sigList.map { (it.name + if (it.isDefault) " ★" else "") to it.id },
                                noneLabel = stringResourceCompat(R.string.st_none),
                                onPick = { signatureId = it }
                            )
                            // الختم
                            SelectorChip(
                                label = stampList.firstStampLabel(stampId, stringResourceCompat(R.string.stmt_stamp_none)),
                                options = stampList.map { (it.name + if (it.isDefault) " ★" else "") to it.id },
                                noneLabel = stringResourceCompat(R.string.st_none),
                                onPick = { stampId = it }
                            )
                            // الشعار
                            StChip(
                                if (logo != null) stringResourceCompat(R.string.stmt_logo_from_profile)
                                else stringResourceCompat(R.string.stmt_no_logo), false
                            ) {
                                nav.navigate(Routes.SETTINGS)
                            }
                            // الإدارة
                            StAction(stringResourceCompat(R.string.st_manage), Cyan) {
                                nav.navigate(Routes.SIGNATURES)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // الثمانية السريعة — البقية في محرر القالب المتقدم
                            StatementUiFacade.QUICK_ELEMENT_NAMES.forEach { name ->
                                val id = StatementUiFacade.elementOf(name)?.name ?: return@forEach
                                StChip(elementLabel(name, ar), id in showElements) {
                                    showElements = toggleElement(showElements, id)
                                }
                            }
                        }
                    }
                }
            }

            // ── الملاحظات + اللغة ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResourceCompat(R.string.st_notes), Modifier.weight(1f))
                            if (noteTemplates.isNotEmpty()) {
                                NoteTemplateMenu(
                                    items = noteTemplates,
                                    onPick = { note = it.body }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = note, onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2, maxLines = 4,
                            placeholder = { Text(stringResourceCompat(R.string.st_notes_hint), fontSize = 13.sp) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResourceCompat(R.string.st_lang),
                                fontSize = 13.sp, color = g.textPrimary,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                StatementUiFacade.UiLang.AR to stringResourceCompat(R.string.st_lang_ar),
                                StatementUiFacade.UiLang.EN to stringResourceCompat(R.string.st_lang_en),
                                StatementUiFacade.UiLang.BILINGUAL to stringResourceCompat(R.string.st_lang_bi)
                            ).forEach { (l, label) ->
                                StChip(label, lang == l) { lang = l }
                            }
                        }
                    }
                }
            }

            // ── المعاينة الحية — ملف حقيقي يُولَّد بعد debounce ثم يُرسم صفحة صفحة ──
            item {
                SectionTitle(stringResourceCompat(R.string.st_preview))
                LivePdfPreview(
                    key = debounceKey,
                    generate = {
                        withContext(Dispatchers.IO) {
                            val ctx = context.applicationContext
                            val data = StatementUiFacade.assemble(
                                ctx, partyId, fromTs, toTs, lang, note.ifBlank { null }
                            )
                            StatementUiFacade.renderPdf(
                                ctx, data, buildStyle(), logo, signatureBmp, stampBmp, null,
                                StatementUiFacade.previewDir(ctx),
                                "preview_$partyId.pdf"
                            )
                        }
                    }
                )
            }

            // ── شريط الإجراءات ──
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actionError?.let { StErrorRow(it) }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            StAction(
                                if (generating) stringResourceCompat(R.string.st_saving)
                                else stringResourceCompat(R.string.st_generate_save),
                                GreenDeep
                            ) {
                                if (generating) return@StAction
                                generating = true; actionError = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val ctx = context.applicationContext
                                        val data = StatementUiFacade.assemble(
                                            ctx, partyId, fromTs, toTs, lang, note.ifBlank { null }
                                        )
                                        val style = buildStyle()
                                        val name = StatementUiFacade.fileNameFor(
                                            party?.name ?: "party", data.statementNumber, System.currentTimeMillis()
                                        )
                                        val rendered = StatementUiFacade.renderPdf(
                                            ctx, data, style, logo, signatureBmp, stampBmp, null,
                                            StatementUiFacade.statementsDir(ctx), name
                                        )
                                        val issued = StatementUiFacade.issue(
                                            ctx, data, templateId, rendered.file
                                        )
                                        withContext(Dispatchers.Main) {
                                            lastIssued = issued
                                            history = listOf(issued) + history
                                            launch {
                                                snackbar.showSnackbar(
                                                    context.getString(R.string.st_saved, issued.verificationId)
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            actionError = context.getString(
                                                R.string.st_fail_reason, (e.message ?: e.javaClass.simpleName).take(80)
                                            )
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) { generating = false }
                                    }
                                }
                            }
                            val shareStr = stringResourceCompat(R.string.st_share) // [P17-integration] رفع الاستدعاء التركيبي خارج اللامدا
                            StAction(shareStr, Cyan) {
                                lastIssued?.filePath?.let { shareFile(it, shareStr) }
                                    ?: run { actionError = context.getString(R.string.st_generate_first) }
                            }
                            StAction(stringResourceCompat(R.string.st_print), Vio) {
                                val path = lastIssued?.filePath
                                if (path != null && java.io.File(path).exists() && activity != null) {
                                    // [P17-c] طباعة حقيقية عبر PrintManager — نمط A4Print القائم
                                    com.superbiz.app.print.A4Print.print(
                                        activity, java.io.File(path),
                                        (party?.name ?: "statement") + ".pdf"
                                    )
                                } else actionError = context.getString(R.string.st_generate_first)
                            }
                            StAction(stringResourceCompat(R.string.st_send), GreenDeep) {
                                sendStatement(context, lastIssued?.filePath, party?.phone, ar)
                            }
                            StChip(
                                (if (historyOpen) "▾ " else "▸ ") + stringResourceCompat(R.string.st_history) +
                                    " (${history.size})", historyOpen
                            ) { historyOpen = !historyOpen }
                        }
                    }
                }
            }

            // ── السجل ──
            if (historyOpen) {
                items(history.size) { i ->
                    val h = history[i]
                    val shareStr = stringResourceCompat(R.string.st_share) // [P17-integration] رفع خارج اللامدا
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { shareFile(h.filePath, shareStr) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "#" + h.number,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp, color = g.textPrimary
                                )
                                Text(
                                    stringResourceCompat(R.string.st_verification_short) + " " + h.verificationId,
                                    fontSize = 11.sp, color = g.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    Dates.short(h.fromTs) + " → " + Dates.short(h.toTs) +
                                        " • " + Dates.short(h.createdAt),
                                    fontSize = 11.sp, color = g.textSecondary
                                )
                            }
                            StAction(shareStr, Cyan) {
                                shareFile(h.filePath, shareStr)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
            }
        }

        // Snackbar محلي — نجاح الإصدار مع رقم التحقق
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    // منتقيا التاريخ المخصص (M3 DatePickerDialog ×2)
    if (showFromPicker) StDatePicker(
        initial = fromTs, onPick = { customFrom = it; showFromPicker = false },
        onDismiss = { showFromPicker = false }
    )
    if (showToPicker) StDatePicker(
        initial = toTs, onPick = { customTo = it; showToPicker = false },
        onDismiss = { showToPicker = false }
    )

    // حوار تسمية نسخة القالب ثم فتح المحرر
    if (dupDialog && curDef != null) {
        // [P30-D]: القراءة في نطاق Composable ثم الالتقاط في remember
        val copySuffix = stringResourceCompat(R.string.stmt_copy_suffix)
        var newName by remember { mutableStateOf((if (ar) curDef.nameAr else curDef.nameEn) + " " + copySuffix) }
        AlertDialog(
            onDismissRequest = { dupDialog = false },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.st_duplicate), color = g.textPrimary) },
            text = {
                BizField(newName, { newName = it }, stringResourceCompat(R.string.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    dupDialog = false
                    val src = templateId
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            val newId = StatementUiFacade.duplicateTemplate(
                                context.applicationContext, src, newName
                            )
                            withContext(Dispatchers.Main) {
                                nav.navigate(Routes.STATEMENT_TEMPLATE_EDIT + "/" + newId)
                            }
                        }
                    }
                }) { Text(stringResourceCompat(R.string.st_edit), color = g.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { dupDialog = false }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }
}

// ── مساعدات محلية ──

// [P30-D]: الدالتان خاليتان بلا تركيب — النص الموطَّن يُمرر من الموقع Composable
private fun List<StatementUiFacade.SignatureUi>.firstLabel(id: Long?, fallback: String): String =
    firstOrNull { it.id == id }?.let { it.name + (if (it.isDefault) " ★" else "") } ?: fallback

private fun List<StatementUiFacade.StampUi>.firstStampLabel(id: Long?, fallback: String): String =
    firstOrNull { it.id == id }?.let { it.name + (if (it.isDefault) " ★" else "") } ?: fallback

/** تسميات الفترات — رقائق عربية/إنجليزية */
@Composable
private fun presetLabel(p: UiPeriodPreset, ar: Boolean): String {
    val key = when (p) {
        UiPeriodPreset.TODAY -> R.string.st_p_today
        UiPeriodPreset.THIS_WEEK -> R.string.st_p_this_week
        UiPeriodPreset.LAST_WEEK -> R.string.st_p_last_week
        UiPeriodPreset.THIS_MONTH -> R.string.st_p_this_month
        UiPeriodPreset.LAST_MONTH -> R.string.st_p_last_month
        UiPeriodPreset.LAST_3_MONTHS -> R.string.st_p_l3m
        UiPeriodPreset.LAST_6_MONTHS -> R.string.st_p_l6m
        UiPeriodPreset.THIS_YEAR -> R.string.st_p_this_year
        UiPeriodPreset.LAST_YEAR -> R.string.st_p_last_year
        UiPeriodPreset.CUSTOM -> R.string.st_p_custom
    }
    return stringResourceCompat(key)
}

/** تسمية عنصر PDF للرقائق السريعة (المفاتيح st_el_*) — [contract ADAPT] أسماء PdfElement من 17-b */
@Composable
private fun elementLabel(name: String, ar: Boolean): String {
    val key = when (name) {
        "LOGO" -> R.string.st_el_logo
        "PARTY_INFO" -> R.string.st_el_party
        "PERIOD" -> R.string.st_el_period
        "TX_TABLE" -> R.string.st_el_table
        "FINAL_BALANCE" -> R.string.st_el_summary
        "NOTES" -> R.string.st_el_notes
        "SIGNATURE" -> R.string.st_el_signature
        "STAMP" -> R.string.st_el_stamp
        else -> return name.lowercase().replace('_', ' ')
    }
    return stringResourceCompat(key)
}

/** رقاقة منتقي بقائمة منسدلة (+ خيار بلا) */
@Composable
private fun SelectorChip(
    label: String,
    options: List<Pair<String, Long?>>,
    noneLabel: String,
    onPick: (Long?) -> Unit
) {
    val g = glassColors()
    var open by remember { mutableStateOf(false) }
    Box {
        StChip(label, selected = false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel, color = g.textSecondary, fontSize = 13.sp) },
                onClick = { onPick(null); open = false }
            )
            options.forEach { (name, id) ->
                DropdownMenuItem(
                    text = { Text(name, color = g.textPrimary, fontSize = 13.sp) },
                    onClick = { onPick(id); open = false }
                )
            }
        }
    }
}

/** قائمة قوالب الملاحظات السريعة */
@Composable
private fun NoteTemplateMenu(items: List<StatementUiFacade.NoteTemplateUi>, onPick: (StatementUiFacade.NoteTemplateUi) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val g = glassColors()
    Box {
        StChip(stringResourceCompat(R.string.st_note_templates), selected = false) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.title, color = g.textPrimary, fontSize = 13.sp) },
                    onClick = { onPick(t); open = false }
                )
            }
        }
    }
}

/** منتقي تاريخ M3 واحد */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // [P17-integration] DatePickerDialog تجريبي
@Composable
private fun StDatePicker(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let(onPick); onDismiss()
            }) { Text(stringResourceCompat(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceCompat(R.string.cancel)) }
        }
    ) {
        DatePicker(state = state)
    }
}

/**
 * [P17-c] الإرسال عبر قنوات حقيقية:
 *  - واتساب: قناة مباشرة package=com.whatsapp بملف PDF؛ غير مثبّت يرمي
 *    ActivityNotFoundException فتعود startIntentSafe بـ false ثم chooser عام + تنبيه صادق
 *    (بلا <queries> في المانيفست — startActivity معفى من قيود رؤية الحزم).
 *  - بريد: ACTION_SEND بملف + EXTRA_STREAM، وmailto نصي بلا ملف.
 */
private fun sendStatement(context: android.content.Context, filePath: String?, phone: String?, ar: Boolean) {
    val file = filePath?.let { java.io.File(it) }?.takeIf { it.exists() }
    val chooserTitle = context.getString(R.string.st_send)
    val waMissing = context.getString(R.string.st_whatsapp_missing)
    val wa = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, file?.let {
            androidx.core.content.FileProvider.getUriForFile(
                context, "com.superbiz.app.fileprovider", it
            )
        })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!phone.isNullOrBlank()) putExtra("jid", phone.replace(Regex("[^0-9]"), "") + "@s.whatsapp.net")
        setPackage("com.whatsapp")
    }
    // المحاولة المباشرة أولاً — فشلها يعرض تنبيه «واتساب غير مثبّت» ولا يغلق التطبيق
    val waOk = com.superbiz.app.util.startIntentSafe(context, wa, errorRes = R.string.st_whatsapp_missing)
    if (waOk) return
    // بلا واتساب: بريد بالملف إن وُجد، وإلا مُشغّل بريد نصي — ثم تنبيه صادق
    if (file != null) {
        val email = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                context, "com.superbiz.app.fileprovider", file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.st_title))
        }
        com.superbiz.app.util.startIntentSafe(
            context, Intent.createChooser(email, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    } else {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        com.superbiz.app.util.startIntentSafe(
            context, Intent.createChooser(mailto, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
    Toast.makeText(context, waMissing, Toast.LENGTH_SHORT).show()
}
