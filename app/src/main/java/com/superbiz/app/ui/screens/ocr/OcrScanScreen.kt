package com.superbiz.app.ui.screens.ocr

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.SuperBizApp
import com.superbiz.app.domain.GeminiGateway
import com.superbiz.app.domain.ocr.OcrDraftToEditor
import com.superbiz.app.domain.ocr.OcrInvoiceParser
import com.superbiz.app.domain.ocr.OcrPrefillBus
import com.superbiz.app.domain.ocr.OcrProductMatcher
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.vision.MlKitOcr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * [F1/F2 - ADR-005] شاشة القراءة الذكية للفواتير — التقاط/اختيار صورة فاتورة
 * ورقية، تعرف نصي محلي بـ ML Kit (دون إنترنت)، ثم تحليل نقي عبر
 * OcrInvoiceParser وعرض مسودة قابلة للمراجعة مع مطابقة المنتجات.
 *
 * [F2] الموجتان الجديدتان فوق مسودة F1:
 * - **تعبئة المحرر من المسودة**: زر «إنشاء فاتورة من المسودة» يحوّل المسودة عبر
 *   المحرك النقي OcrDraftToEditor إلى حزمة تعبئة (طرف + بنود نصوص عرض + نسبة)،
 *   تحال عبر OcrPrefillBus (نمط ReorderBus) إلى شاشة الفواتير فتفتح المحرر —
 *   وما بعد ذلك مسار الحفظ الذرّي القائم بكامل حراسه، بلا أي لمس هنا.
 * - **التحسين السحابي المقفول (D4)**: زر التحسين يعرض نص OCR الخام المرسل في
 *   حوار قراءة صريح ويطلب تأكيد المالك **قبل كل إرسال** — لا إرسال خلفي صامت
 *   أبدًا؛ المخرج يعود للمحلل النقي المحلي حصرًا (لا اعتماد مالي مباشر).
 *   الباب مقفول بالكامل خارج تفعيل المالك + مفتاح API (فشل مغلق في GeminiAiStore).
 */
@Composable
fun OcrScanScreen(appVM: com.superbiz.app.vm.AppVM, nav: NavHostController) {
    val context = LocalContext.current
    val g = glassColors()
    val symbol by appVM.symbol.collectAsState()
    val scope = rememberCoroutineScope()

    // حالة المسح: idle → working → done / failed
    var phase by remember { mutableStateOf("idle") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<OcrInvoiceParser.OcrInvoiceDraft?>(null) }
    var rawText by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }
    // مطابقات المنتجات لكل بند (null = بلا مطابقة فوق حد القرار)
    var matches by remember { mutableStateOf<List<OcrProductMatcher.Match?>>(emptyList()) }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    // [F2] التحسين السحابي المقفول — حالة الحوار والإرسال (D4: قراءة وتأكيد قبل كل إرسال)
    var showCloudConfirm by remember { mutableStateOf(false) }
    var cloudWorking by remember { mutableStateOf(false) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudApplied by remember { mutableStateOf(false) }

    // [F2] حزمة التعبئة تُشتق حتميًا من المسودة والمطابقات الحالية (محرك نقي مختبر)
    val prefill = remember(draft, matches) {
        draft?.let { OcrDraftToEditor.toPrefill(it, matches) }
    }

    // ── ملتقطا الصورة: كاميرا النظام عبر FileProvider (الإذن عنده) أو منتقي الوسائط ──
    fun newCaptureUri(): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // تنظيف لقطات سابقة — لا تراكم ملفات في cache
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ocr_capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", file)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCapture
        if (ok && uri != null) scope.launch { doScan(uri) }
    }
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { doScan(uri) }
    }

    fun setState(
        p: String, e: String?, d: OcrInvoiceParser.OcrInvoiceDraft?,
        raw: String, m: List<OcrProductMatcher.Match?>
    ) {
        phase = p; errorMessage = e; draft = d; rawText = raw; matches = m
        // كل مسح جديد يُصفّر حالة الموجة السحابية — الملاحظة تخص النص الحالي حصرًا
        cloudApplied = false
        cloudError = null
    }

    /** [F2-D4] نداء التحسين الوحيد — يدوي بعد تأكيد الحوار، والمخرج يعود للمحلل المحلي حصرًا */
    fun requestCloudEnhance() {
        val app = context.applicationContext as SuperBizApp
        val raw = rawText
        scope.launch {
            cloudWorking = true
            cloudError = null
            try {
                when (val r = app.graph.geminiEnhancer.enhance(GeminiGateway.EnhanceRequest(raw))) {
                    is GeminiGateway.GatewayResult.Accepted -> {
                        val reparsed = OcrInvoiceParser.parse(r.text)
                        setState("done", null, reparsed, r.text, emptyList())
                        cloudApplied = true
                    }
                    is GeminiGateway.GatewayResult.Rejected -> cloudError = r.reason
                    is GeminiGateway.GatewayResult.TransientFailure -> cloudError = r.message
                }
            } catch (e: Exception) {
                cloudError = e.message ?: "enhance"
            } finally {
                cloudWorking = false
                showCloudConfirm = false
            }
        }
    }

    /** دورة المسح كاملة: تعرف محلي ← تحليل نقي ← حالة — الثقيل على IO عبر MlKitOcr */
    suspend fun doScan(uri: Uri) {
        setState("working", null, null, "", emptyList())
        try {
            val result = MlKitOcr.recognize(context, uri)
            val parsed = OcrInvoiceParser.parse(result.text)
            setState("done", null, parsed, result.text, emptyList())
        } catch (e: Exception) {
            setState("failed", e.message ?: "OCR", null, "", emptyList())
        }
    }

    // ── مطابقة المنتجات بعد نجاح التحليل (قراءة كتالوج واحدة خلف المراجعة) ──
    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        if (d.lines.isEmpty()) { matches = emptyList(); return@LaunchedEffect }
        val app = context.applicationContext as SuperBizApp
        val products = app.graph.inventory.products().first().filter { !it.archived }
        val catalog = products.map { it.id to it.name }
        matches = d.lines.map { OcrProductMatcher.match(it.desc, catalog) }
    }

    // ملخص نصي للنسخ/المشاركة — دالة عادية فتستخدم context.getString (لا composable داخلها)
    fun summaryText(): String {
        val d = draft ?: return ""
        val sb = StringBuilder()
        sb.append(context.getString(R.string.ocr_title)).append('\n')
        d.merchant?.let { sb.append(context.getString(R.string.ocr_merchant)).append(": ").append(it).append('\n') }
        d.dateMillis?.let { sb.append(context.getString(R.string.ocr_date)).append(": ").append(Dates.short(it)).append('\n') }
        sb.append("──\n")
        d.lines.forEachIndexed { i, l ->
            sb.append("${i + 1}. ${l.desc} · ")
                .append(Money.numP(l.unitPriceQirush)).append(' ')
                .append(symbol).append(" × ").append(l.qty).append(" = ")
                .append(Money.numP(l.lineTotalQirush)).append(' ').append(symbol).append('\n')
        }
        sb.append("──\n")
        d.subtotalQirush?.let { sb.append(context.getString(R.string.ocr_subtotal)).append(": ").append(Money.formatP(it, symbol)).append('\n') }
        d.vatQirush?.let {
            val rate = d.vatRate
            sb.append(
                if (rate != null) context.getString(R.string.ocr_vat, Money.num(rate))
                else context.getString(R.string.ocr_vat_plain)
            ).append(": ").append(Money.formatP(it, symbol)).append('\n')
        }
        d.totalQirush?.let { sb.append(context.getString(R.string.ocr_total)).append(": ").append(Money.formatP(it, symbol)).append('\n') }
        return sb.toString()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(stringResourceCompat(R.string.ocr_title), onBack = { nav.popBackStack() })
        Spacer(Modifier.height(8.dp))

        when (phase) {
            "idle" -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DocumentScanner, null,
                            tint = g.accent, modifier = Modifier.height(48.dp)
                        )
                        Text(
                            stringResourceCompat(R.string.ocr_hint),
                            color = g.textPrimary, style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = {
                                val uri = newCaptureUri()
                                pendingCapture = uri
                                cameraLauncher.launch(uri)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VioDeep, contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.PhotoCamera, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResourceCompat(R.string.ocr_take_photo))
                        }
                        OutlinedButton(
                            onClick = {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, null, tint = g.textPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResourceCompat(R.string.ocr_pick_image), color = g.textPrimary)
                        }
                    }
                }
            }

            "working" -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = g.accent)
                        Text(stringResourceCompat(R.string.ocr_working), color = g.textPrimary)
                    }
                }
            }

            "failed" -> {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = RedDeep, modifier = Modifier.height(40.dp))
                        Text(
                            stringResource(R.string.ocr_failed, errorMessage ?: ""),
                            color = g.textPrimary, style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { phase = "idle" }) {
                            Text(stringResourceCompat(R.string.ocr_try_again))
                        }
                    }
                }
            }

            "done" -> {
                val d = draft
                if (d == null || (d.lines.isEmpty() && d.totalQirush == null)) {
                    EmptyState(stringResourceCompat(R.string.ocr_nothing_found), Icons.Rounded.ReceiptLong)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { phase = "idle" },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioDeep, contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResourceCompat(R.string.ocr_try_again)) }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // بطاقة الرأس: الجهة + التاريخ + الثقة
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.ReceiptLong, null, tint = g.accent)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        d.merchant ?: stringResourceCompat(R.string.ocr_unknown_merchant),
                                        color = g.textPrimary, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    ConfidenceChip(d.confidence)
                                }
                                d.dateMillis?.let {
                                    Text(
                                        stringResourceCompat(R.string.ocr_date) + ": " + Dates.short(it),
                                        color = g.textSecondary, style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!d.totalsConsistent) {
                                    Text(
                                        stringResourceCompat(R.string.ocr_inconsistent),
                                        color = RedDeep, style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                d.warnings.forEach { w ->
                                    Text(w, color = RedDeep, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // البنود + مطابقات الكتالوج
                        d.lines.forEachIndexed { i, l ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            l.desc, color = g.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            Money.numP(l.lineTotalQirush) + " " + symbol,
                                            color = g.textPrimary, fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        stringResource(
                                            R.string.ocr_line_qty_price,
                                            l.qty.toString(), Money.numP(l.unitPriceQirush) + " " + symbol
                                        ),
                                        color = g.textSecondary, style = MaterialTheme.typography.bodySmall
                                    )
                                    matches.getOrNull(i)?.let { m ->
                                        Text(
                                            stringResource(R.string.ocr_matched, m.productName),
                                            color = Green, style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        // بطاقة المجاميع
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                d.subtotalQirush?.let {
                                    TotalRow(stringResourceCompat(R.string.ocr_subtotal), Money.formatP(it, symbol), g.textPrimary)
                                }
                                d.vatQirush?.let {
                                    val label = if (d.vatRate != null)
                                        stringResource(R.string.ocr_vat, Money.num(d.vatRate))
                                    else stringResourceCompat(R.string.ocr_vat_plain)
                                    TotalRow(label, Money.formatP(it, symbol), g.textPrimary)
                                }
                                d.totalQirush?.let {
                                    TotalRow(stringResourceCompat(R.string.ocr_total), Money.formatP(it, symbol), g.textPrimary, bold = true)
                                }
                            }
                        }

                        // [F2] ملاحظات الموجة الجديدة — إسقاطات التعبئة + حال السحابي
                        if ((prefill?.skippedLines ?: 0) > 0) {
                            Text(
                                stringResource(R.string.ocr_skipped_lines, prefill!!.skippedLines),
                                color = RedDeep, style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (cloudApplied) {
                            Text(
                                stringResourceCompat(R.string.ocr_cloud_enhanced_note),
                                color = Green, style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // النص الخام — طي/فتح
                        Text(
                            (if (showRaw) "▾ " else "▸ ") + stringResourceCompat(R.string.ocr_raw_toggle),
                            color = g.accent,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .clickable { showRaw = !showRaw }
                                .padding(4.dp)
                        )
                        if (showRaw) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    rawText.take(4000),
                                    color = g.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    // شريط الإجراءات
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val clipboard = LocalClipboardManager.current
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(summaryText())) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = g.textPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResourceCompat(R.string.ocr_copy), color = g.textPrimary, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, summaryText())
                                }
                                context.startActivity(
                                    Intent.createChooser(intent, context.getString(R.string.share))
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Share, null, tint = g.textPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResourceCompat(R.string.ocr_share), color = g.textPrimary, maxLines = 1)
                        }
                    }
                    // [F2-D4] التحسين السحابي المقفول — قراءة صريحة قبل كل إرسال
                    OutlinedButton(
                        onClick = {
                            val app = context.applicationContext as SuperBizApp
                            scope.launch {
                                val gateOk = app.graph.geminiAi.enabledOnce() &&
                                    app.graph.geminiAi.keyConfiguredOnce()
                                if (gateOk) showCloudConfirm = true
                                else android.widget.Toast.makeText(
                                    context, context.getString(R.string.ocr_cloud_disabled_note),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = !cloudWorking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.CloudSync, null, tint = g.textPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResourceCompat(R.string.ocr_cloud_enhance), color = g.textPrimary)
                    }
                    cloudError?.let { err ->
                        Text(
                            stringResource(R.string.ocr_cloud_failed, err.take(160)),
                            color = RedDeep, style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // [F2] تعبئة المحرر من المسودة — عبر OcrPrefillBus النقية ثم مسار الحفظ الذرّي القائم
                    Button(
                        onClick = {
                            val pf = prefill
                            if (pf != null && pf.usable) {
                                OcrPrefillBus.post(pf)
                                nav.navigate(Routes.INVOICES)
                            }
                        },
                        enabled = prefill?.usable == true,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenDeep, contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.ReceiptLong, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResourceCompat(R.string.ocr_create_invoice))
                    }
                    Button(
                        onClick = { nav.popBackStack() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenDeep, contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResourceCompat(R.string.ocr_open_invoices))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ── [F2-D4] حوار القراءة والتأكيد — النص المرسل يُعرض للمالك قبل كل إرسال ──
    if (showCloudConfirm) {
        AlertDialog(
            onDismissRequest = { if (!cloudWorking) showCloudConfirm = false },
            title = { Text(stringResourceCompat(R.string.ocr_cloud_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResourceCompat(R.string.ocr_cloud_confirm_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            rawText.take(2000) + if (rawText.length > 2000) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                    if (cloudWorking) {
                        CircularProgressIndicator(color = g.accent, modifier = Modifier.height(24.dp))
                    }
                    cloudError?.let { err ->
                        Text(
                            stringResource(R.string.ocr_cloud_failed, err.take(160)),
                            color = RedDeep, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { requestCloudEnhance() },
                    enabled = !cloudWorking && rawText.isNotBlank()
                ) { Text(stringResourceCompat(R.string.ocr_cloud_send)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!cloudWorking) showCloudConfirm = false }
                ) { Text(stringResourceCompat(R.string.ocr_cloud_cancel)) }
            }
        )
    }
}

/** رقاقة الثقة — لونها حسب الدرجة */
@Composable
private fun ConfidenceChip(confidence: Double) {
    val g = glassColors()
    val color = when {
        confidence >= 0.75 -> Green
        confidence >= 0.45 -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else -> RedDeep
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.ocr_confidence, "${(confidence * 100).toInt()}%"),
            color = color, style = MaterialTheme.typography.bodySmall
        )
    }
}

/** صف مجاميع */
@Composable
private fun TotalRow(label: String, value: String, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
        Text(
            value, color = color, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}
