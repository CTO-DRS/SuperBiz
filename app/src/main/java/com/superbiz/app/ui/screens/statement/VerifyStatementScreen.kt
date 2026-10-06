package com.superbiz.app.ui.screens.statement

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [P19] شاشة التحقق من كشف الحساب — الواجهة الحقيقية وراء QR المطبوع في الكشف
 * (superbiz://verify/<verificationId>) ووراء رقم التحقق SB-ST-YYYYMMDD-NNNNNN.
 *
 * ما تتحقق منه فعلاً (بلا تلميع):
 *  ① رقم التحقق مسجل في قاعدة البيانات (أي الكشف أصدره التطبيق فعلاً) — رقم
 *     مكتوب يدوياً أو مزوّر لا يطابق أي سجل.
 *  ② بيانات الكشف المخزنة عند الإصدار (الطرف، الفترة، العملة، رقم الكشف، لغة،
 *     ختم الإصدار) تُعرض كما سُجلت — أي عبث لاحق بالدفتر لا يغيّر هذا السجل.
 *  ③ ملف PDF على القرص: موجود؟ حجمه؟ وبصمة SHA-256 لبايتات الملف كما هي الآن
 *     — أي تعديل للملف بعد الإصدار يغيّر البصمة (كشف تلاخف مادي بالملف).
 *
 * لا ادعاء «صالح/غير صالح» عن أرصدة الدفتر الحالية: الرصيد الحي قد يتغير بعد
 * الإصدار بطبيعة العمل — البصمة والسجل هما الدليل الحقيقي، فنعرضهما كما هما.
 */
@Composable
fun VerifyStatementScreen(initialVid: String, nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()
    val appCtx = context.applicationContext

    var input by remember { mutableStateOf(initialVid) }
    var loading by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    var result by remember {
        mutableStateOf<com.superbiz.app.data.db.StatementEntity?>(null)
    }
    var partyName by remember { mutableStateOf<String?>(null) }
    var fileInfo by remember { mutableStateOf<FileInfo?>(null) }

    fun verify() {
        val q = input.trim().uppercase(Locale.US)
        if (q.isEmpty() || loading) return
        loading = true
        notFound = false
        result = null
        scope.launch {
            val row = withContext(Dispatchers.IO) {
                runCatching {
                    val gr = AppGraph.from(appCtx)
                    val r = gr.statements.byVerificationId(q)
                    if (r != null) {
                        partyName = gr.ledger.party(r.partyId)?.name
                        val f = File(r.filePath)
                        if (f.exists() && f.length() > 0) {
                            val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                            fileInfo = FileInfo(f.length(), digest.toHex())
                        } else {
                            fileInfo = null
                        }
                    }
                    r
                }.getOrNull()
            }
            loading = false
            if (row == null) notFound = true else result = row
        }
    }

    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBackIos, null,
                    tint = g.textPrimary, modifier = Modifier.padding(4.dp)
                )
            }
            Text(
                stringResourceCompat(R.string.st4_verify_title),
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary
            )
        }

        Text(
            stringResourceCompat(R.string.st4_verify_hint),
            color = g.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))

        // حقل رقم التحقق — LTR صريح لأن الرقم لاتيني/أرقام
        androidx.compose.material3.OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace
            ),
            label = { Text(stringResourceCompat(R.string.st4_verify_label)) },
            placeholder = { Text("SB-ST-20260924-000001") }
        )
        Spacer(Modifier.height(10.dp))

        androidx.compose.material3.Button(
            onClick = { verify() },
            enabled = !loading && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (loading) stringResourceCompat(R.string.st4_verify_checking)
                else stringResourceCompat(R.string.st4_verify_button),
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(14.dp))

        if (notFound) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(g.surface)
                    .padding(16.dp)
            ) {
                Text(
                    stringResourceCompat(R.string.st4_verify_notfound),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        result?.let { r ->
            Spacer(Modifier.height(6.dp))
            // شارة التحقق الناجح
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(g.surface)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Verified, null,
                    tint = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    modifier = Modifier.height(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        stringResourceCompat(R.string.st4_verify_ok),
                        style = MaterialTheme.typography.titleSmall,
                        color = g.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        r.verificationId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = g.textSecondary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            InfoCard(
                title = stringResourceCompat(R.string.st4_sec_record),
                rows = listOf(
                    stringResourceCompat(R.string.st4_statement_no) to r.statementNumber,
                    stringResourceCompat(R.string.st4_party) to (partyName ?: "#${r.partyId}"),
                    stringResourceCompat(R.string.st4_period) to
                        (df.format(Date(r.fromTs)) + " ← " + df.format(Date(r.toTs))),
                    stringResourceCompat(R.string.st4_currency) to r.currency,
                    stringResourceCompat(R.string.st4_lang) to r.lang,
                    stringResourceCompat(R.string.st4_template) to r.templateId,
                    stringResourceCompat(R.string.st4_issued_at) to df.format(Date(r.createdAt))
                )
            )
            Spacer(Modifier.height(10.dp))

            InfoCard(
                title = stringResourceCompat(R.string.st4_sec_integrity),
                rows = listOf(
                    stringResourceCompat(R.string.st4_content_hash) to r.contentHash,
                    (if (fileInfo != null) stringResourceCompat(R.string.st4_file_present)
                    else stringResourceCompat(R.string.st4_file_missing)) to
                        (fileInfo?.let { fmtSize(it.size) + "  •  SHA-256 " + it.sha256 }
                            ?: stringResourceCompat(R.string.st4_file_none))
                )
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResourceCompat(R.string.st4_verify_footer),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    val g = glassColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(g.surface)
            .padding(14.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = g.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        rows.forEach { (k, v) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    k,
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    v,
                    color = g.textPrimary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** بصمة ملف PDF كما هي الآن — أي تعديل بايت واحد يغيّرها (قرار تصميم موثق في KDoc أعلاه) */
private data class FileInfo(val size: Long, val sha256: String)

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
