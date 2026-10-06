package com.superbiz.app.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** أمر تصدير جاهز: اسم الملف + الصيغة + رأس الجدول + الصفوف (نص أو رقم) */
/**Serializable حتى يبقى الطلب المعلّق عبر rememberSaveable عند إعادة إنشاء النشاط */
data class ExportRequest(
    val fileName: String,
    val isXlsx: Boolean,
    val header: List<String>,
    val rows: List<List<Any?>>
) : java.io.Serializable { companion object { private const val serialVersionUID = 1L } }

const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val MIME_CSV = "text/csv"

// كان التصدير كله (فتح SAF + بناء CSV/xlsx + الكتابة) على الخيط الرئيسي —
// تصدير كبير يعني ANR؛ الكتابة الآن على Dispatchers.IO والتنبيه يعود للخيط الرئيسي
private fun runExport(scope: CoroutineScope, ctx: Context, uri: Uri, req: ExportRequest) {
    scope.launch {
        val result: Boolean? = try {
            withContext(Dispatchers.IO) {
                val out = ctx.contentResolver.openOutputStream(uri)
                if (out == null) false else out.use {
                    if (req.isXlsx) DataExport.writeXlsx(it, req.fileName.substringBeforeLast('.'), req.header, req.rows)
                    else DataExport.writeCsv(it, req.header, req.rows)
                    true
                }
            }
        } catch (ce: java.util.concurrent.CancellationException) {
            // [P20-FIX agent17]: كان يُبلع CancellationException (فئة Exception) فيُظهر «فشل
            // التصدير» بينما الملف كُتب فعلاً على القرص — أعد الرمي احتراماً للبنية التنظيمية
            throw ce
        } catch (e: Exception) {
            null
        }
        when (result) {
            true -> android.widget.Toast.makeText(ctx, ctx.getString(R.string.export_done), android.widget.Toast.LENGTH_SHORT).show()
            false -> android.widget.Toast.makeText(ctx, ctx.getString(R.string.export_failed), android.widget.Toast.LENGTH_SHORT).show()
            else -> android.widget.Toast.makeText(ctx, ctx.getString(R.string.export_fail), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * متحكم تصدير قابل للتركيب: يستدعيه القائم بالعرض بـ exporter(request) فيظهر
 * حوار اختيار الصيغة (Excel/CSV) ثم نافذة حفظ SAF — دون أي إذن تخزين.
 */
@Composable
fun ExportController(title: String): (ExportRequest) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // [P20-FIX agent17]: الطلب الكامل كان داخل rememberSaveable — onSaveInstanceState يُسلسل
    // كل الصفوف في Bundle أثناء نافذة SAF فتنفجر حدود الـBinder (TransactionTooLargeException)
    // مع آلاف الصفوف. مُسك: علم صغير قابل للحفظ + الطلب نفسه في مخزن على مستوى العملية
    var pending by rememberSaveable { mutableStateOf(false) }
    var choose by remember { mutableStateOf<ExportRequest?>(null) }

    fun stash(req: ExportRequest?): ExportRequest? {
        PendingExportStore.req = req
        pending = req != null
        return req
    }

    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_XLSX)
    ) { uri: Uri? ->
        val req = PendingExportStore.req
        stash(null)
        if (uri != null && req != null) runExport(scope, ctx, uri, req)
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_CSV)
    ) { uri: Uri? ->
        val req = PendingExportStore.req
        stash(null)
        if (uri != null && req != null) runExport(scope, ctx, uri, req)
    }

    choose?.let {
        ExportFormatDialog(
            title = title,
            onDismiss = { choose = null },
            onPick = { isXlsx ->
                // يُحسب اسم الملف من جديد بلحظة الاختيار بامتداد الصيغة الفعلية —
                // كان الاسم يُطلَب بامتداد .xlsx ثابتاً حتى عند اختيار CSV
                val prefix = it.fileName.substringBeforeLast('.').replace(Regex("-\\d{8}-\\d{4}$"), "")
                val req = it.copy(isXlsx = isXlsx, fileName = DataExport.fileName(prefix, isXlsx))
                choose = null
                stash(req)
                if (isXlsx) xlsxLauncher.launch(req.fileName) else csvLauncher.launch(req.fileName)
            }
        )
    }

    return { req -> choose = req }
}

/** [P20-FIX agent17] مخزن الطلب المعلّق على مستوى العملية — بدل تسلسل كل الصفوف في Bundle */
private object PendingExportStore {
    @Volatile var req: ExportRequest? = null
}

/** نافذة اختيار صيغة التصدير: Excel أو CSV */
@Composable
private fun ExportFormatDialog(title: String, onPick: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val g = glassColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(title, color = g.textPrimary) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(true) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        "Excel (.xlsx)",
                        color = g.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(false) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        "CSV (UTF-8) (.csv)",
                        color = g.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = g.textSecondary)
            }
        }
    )
}
