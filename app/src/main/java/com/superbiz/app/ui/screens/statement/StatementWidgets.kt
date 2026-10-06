package com.superbiz.app.ui.screens.statement

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.superbiz.app.R
import com.superbiz.app.ui.screens.stringResourceCompat
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.ui.components.BizPill
import com.superbiz.app.ui.components.PillMode
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [P17-c] عناصر واجهة مشتركة لمنظومة كشف الحساب — بلغة التصميم الزجاجية القائمة
 * (GlassCard/glassColors/رقائق FilterPill) بلا أي تبعية على العقد إلا عبر الواجهة.
 */

/** [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (SELECT) — التنفيذ الوحيد في Common.kt */
@Composable
fun StChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    BizPill(label, g.accent, mode = PillMode.SELECT, selected = selected, onClick = onClick)
}

/** [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (ACTION) — التنفيذ الوحيد في Common.kt */
@Composable
fun StAction(label: String, color: Color, onClick: () -> Unit) {
    BizPill(label, color, mode = PillMode.ACTION, onClick = onClick)
}

/** صف تلميح علوي/سفلي */
@Composable
fun StHint(text: String, tint: Color = Amber) {
    val g = glassColors()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

/** صف خطأ مع ملخص السبب — لا انهيار أبداً */
@Composable
fun StErrorRow(text: String) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RedDeep.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = RedDeep, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = RedDeep, maxLines = 3)
    }
}

/** بوابة تسلسل توليد PDF (معاينة/مصغّرات) — لا توليد متوازٍ ثقيل */
object PdfRenderGate { val mutex = Mutex() }

/**
 * مصغّرة قالب حقيقية: توليد PDF فعلي بالنمط ثم رسم أول صفحة عبر PdfRenderer.
 * تخزين مؤقت: ذاكرة LruCache + قرص cache/statement_thumbs — يظهر هيكل عظمي حتى الجهوزية.
 */
object TemplateThumbCache {
    private val memory = object : android.util.LruCache<String, Bitmap>(24) {}
    fun mem(id: String): Bitmap? = memory.get(id)
    fun put(id: String, bmp: Bitmap) { memory.put(id, bmp) }
    fun diskFile(context: android.content.Context, id: String): java.io.File =
        java.io.File(
            java.io.File(context.cacheDir, "statement_thumbs").apply { mkdirs() },
            "$id.png"
        )
}

@Composable
fun TemplateThumbnail(
    templateId: String,
    style: com.superbiz.app.pdf.statement.StatementStyle,
    sampleData: com.superbiz.app.domain.statement.StatementData?,
    logo: Bitmap?,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val g = glassColors()
    var bmp by remember(templateId) { mutableStateOf<Bitmap?>(TemplateThumbCache.mem(templateId)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(templateId, sampleData) {
        if (bmp != null || sampleData == null) return@LaunchedEffect
        launch {
            val cached = TemplateThumbCache.diskFile(context, templateId)
            if (cached.exists()) {
                BitmapFactory.decodeFile(cached.absolutePath)?.let {
                    bmp = it; TemplateThumbCache.put(templateId, it); return@launch
                }
            }
            try {
                PdfRenderGate.mutex.withLock {
                    val rendered = StatementUiFacade.renderPdf(
                        context, sampleData, style, logo, null, null, null,
                        StatementUiFacade.previewDir(context), "thumb_$templateId.pdf"
                    )
                    val page = StatementUiFacade.previewPage(rendered.file, 0, 420)
                    if (page != null) {
                        cached.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        TemplateThumbCache.put(templateId, page)
                    }
                    bmp = page
                }
            } catch (_: Exception) {
                bmp = null // الهيكل يبقى — والخطأ لا يقتل الشاشة
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(g.surfaceStrong),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = stringResource(R.string.a11y_statement_thumb), // [P39-M4-2]
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * المعاينة الحية «ما تراه هو الملف»: يولّد PDF حقيقياً بأي تغيير معاملات
 * (debounce 500ms) ثم يعرض صفحاته عبر PdfRenderer مع «1 / N» وخطأ صادق.
 *
 * @param key           بصمة المعاملات — تغيّرها يعيد التوليد
 * @param generate      توليد الملف على IO — يرمي استثناءً بملخص السبب عند الفشل
 */
@Composable
fun LivePdfPreview(
    key: Any?,
    generate: suspend () -> StatementUiFacade.RenderedPdf,
    modifier: Modifier = Modifier
) {
    val g = glassColors()
    var loading by remember { mutableStateOf(true) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    var pageCount by remember { mutableStateOf(1) }
    var page by remember { mutableStateOf(0) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val wins = remember { LatestWins() }

    // debounce 500ms — كل تغيير معاملات يؤجّل التوليد ثم يولّد مرة واحدة
    LaunchedEffect(key) {
        delay(500)
        loading = true; error = null
        val token = wins.next()
        try {
            val r = generate()
            if (wins.isLatest(token)) {
                file = r.file; pageCount = r.pageCount.coerceAtLeast(1); page = 0
            }
        } catch (e: Exception) {
            if (wins.isLatest(token)) {
                error = (e.message ?: e.javaClass.simpleName).take(140)
                file = null
            }
        } finally {
            if (wins.isLatest(token)) loading = false
        }
    }

    // رسم الصفحة المعروضة من الملف الفعلي
    LaunchedEffect(file, page) {
        val f = file
        if (f == null) { bmp = null; return@LaunchedEffect }
        val token = wins.next()
        val b = StatementUiFacade.previewPage(f, page, 900)
        if (wins.isLatest(token)) bmp = b
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(g.surfaceStrong)
                .border(1.dp, g.border, RoundedCornerShape(16.dp))
                .height(340.dp),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            when {
                error != null -> StErrorRow(error ?: "")
                b != null -> Image(
                    bitmap = b.asImageBitmap(), contentDescription = stringResource(R.string.a11y_statement_preview), // [P39-M4-2]
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // [P30-D]: من الموارد بدل if(ar) المضمّنة
                        stringResourceCompat(R.string.stmt_rendering_preview),
                        fontSize = 12.sp, color = g.textSecondary, textAlign = TextAlign.Center
                    )
                }
            }
            if (loading && error == null && b != null) {
                CircularProgressIndicator(
                    color = g.accent, strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(18.dp)
                )
            }
        }
        // تنقّل الصفحات «1 / N» — أرقام لاتينية محايدة الاتجاه
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (page > 0) page-- },
                enabled = page > 0 && !loading && error == null
            ) {
                Icon(
                    Icons.Rounded.ChevronRight, null, tint = g.textPrimary
                )
            }
            Text(
                pageLabel(page + 1, pageCount),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = g.textPrimary
            )
            IconButton(
                onClick = { if (page < pageCount - 1) page++ },
                enabled = page < pageCount - 1 && !loading && error == null
            ) {
                Icon(
                    Icons.Rounded.ChevronLeft, null, tint = g.textPrimary
                )
            }
        }
    }
}

/** مُطلق اختيار صورة SAF (OpenDocument) — يعيد Uri أو لا شيء */
@Composable
fun rememberImagePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPicked) }
    return {
        runCatching {
            launcher.launch(arrayOf("image/*"))
        }
    }
}

/** نسخ صورة مختارة إلى مجلد داخلي (identity/signatures/stamps) — يعيد المسار أو null */
fun copyUriToDir(context: android.content.Context, uri: Uri, dir: File, prefix: String): String? {
    return try {
        dir.mkdirs()
        val out = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** فك صورة بحجم أقصى (تقليل الذاكرة للمعاينات والطباعة) */
fun decodeScaled(path: String, maxSide: Int = 1024): Bitmap? = try {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide * 2) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Exception) { null }
