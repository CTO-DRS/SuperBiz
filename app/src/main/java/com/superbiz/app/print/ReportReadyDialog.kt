package com.superbiz.app.print

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.pdf.DocPdf
import com.superbiz.app.ui.theme.glassColors
import java.io.File

/** نتيجة توليد تقرير A4: ملف PDF جاهز + إيصال حراري مكافئ للطباعة البلوتوث */
data class ReportReady(
    val pdf: File,
    val title: String,
    val receipt: EscPos.Receipt
)

/**
 * — حوار «التقرير جاهز»: ثلاث مسارات طباعة حقيقية للتقرير المولّد
 *
 * 1. طباعة A4 عبر النظام (PrintManager): أي طابعة يراها النظام — واي فاي أو
 * بلوتوث عبر Mopria/إضافات الشركات، أو سحابية، أو حفظ كـ PDF.
 * 2. طباعة حرارية عبر البلوتوث: ملخص التقرير كإيصال ESC/POS للطابعات الحرارية.
 * 3. مشاركة: نفس مسار المشاركة القديم (واتساب/بريد/…).
*/
@Composable
fun ReportReadyDialog(ready: ReportReady, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val g = glassColors()
    var showThermal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(ctx.getString(R.string.ready_title), color = g.textPrimary) },
        text = {
            Column {
                Text(
                    ready.title,
                    color = g.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    ready.pdf.name,
                    color = g.textSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))

                OptionRow(
                    title = ctx.getString(R.string.act_print_system),
                    sub = ctx.getString(R.string.act_print_system_sub),
                    accent = g.accent
                ) {
                    activity?.let { A4Print.print(it, ready.pdf, ready.title) }
                    onDismiss()
                }
                Spacer(Modifier.height(8.dp))
                OptionRow(
                    title = ctx.getString(R.string.act_print_thermal),
                    sub = ctx.getString(R.string.act_print_thermal_sub),
                    accent = g.accent2
                ) {
                    showThermal = true
                }
                Spacer(Modifier.height(8.dp))
                OptionRow(
                    title = ctx.getString(R.string.act_share),
                    sub = ctx.getString(R.string.act_share_sub),
                    accent = g.textPrimary
                ) {
                    activity?.let { DocPdf.share(it, ready.pdf, ready.title) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ctx.getString(R.string.close), color = g.textSecondary)
            }
        }
    )

    if (showThermal) {
        ReceiptPrintDialog(receipt = ready.receipt) { showThermal = false }
    }
}

@Composable
private fun OptionRow(title: String, sub: String, accent: Color, onClick: () -> Unit) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, color = accent,
                fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                sub, color = g.textSecondary,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
