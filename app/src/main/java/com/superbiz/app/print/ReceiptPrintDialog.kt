package com.superbiz.app.print

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.launch

/**
 * حوار طباعة إيصال حراري عبر Bluetooth:
 * اختيار الطابعة المقترنة (مع تذكّر آخر طابعة) + عرض الورق 58/80 مم + حالة الطباعة.
 */
@Composable
fun ReceiptPrintDialog(receipt: EscPos.Receipt, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()

    var printers by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var width by remember { mutableStateOf(BluetoothPrinter.paperWidth(ctx)) }
    var printing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var permAsked by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permAsked = true
        if (!granted) {
            msg = ctx.getString(R.string.bluetooth_need_permission)
        } else {
            val list = BluetoothPrinter.bondedPrinters(ctx)
            printers = list
            val saved = BluetoothPrinter.lastPrinterAddress(ctx)
            selected = list.firstOrNull { it.address == saved } ?: list.firstOrNull()
        }
    }

    LaunchedEffect(Unit) {
        if (BluetoothPrinter.hasConnectPermission(ctx)) {
            val list = BluetoothPrinter.bondedPrinters(ctx)
            printers = list
            val saved = BluetoothPrinter.lastPrinterAddress(ctx)
            selected = list.firstOrNull { it.address == saved } ?: list.firstOrNull()
        } else {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun doPrint() {
        val dev = selected ?: return
        printing = true
        msg = ctx.getString(R.string.printing)
        scope.launch {
            try {
                BluetoothPrinter.rememberPrinter(ctx, dev.address)
                BluetoothPrinter.rememberPaperWidth(ctx, width)
                // أسلوب الطباعة العربية من مركز الإعدادات (CP1256/UTF-8) — إعداد حقيقي يُطبَّق هنا
                BluetoothPrinter.print(ctx, dev.address, EscPos.build(receipt, width, com.superbiz.app.core.AppPrefs.arabicReceiptMode))
                msg = ctx.getString(R.string.print_ok)
            } catch (e: Exception) {
                msg = ctx.getString(R.string.print_fail, e.message ?: e.javaClass.simpleName)
            } finally {
                printing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        containerColor = g.surfaceStrong,
        title = { Text(ctx.getString(R.string.print_title), color = g.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (printers.isEmpty()) {
                    Text(
                        ctx.getString(R.string.printer_none),
                        color = g.textSecondary, fontSize = 13.sp
                    )
                } else {
                    Text(
                        ctx.getString(R.string.printer_select),
                        style = MaterialTheme.typography.titleSmall, color = g.textSecondary
                    )
                    Spacer(Modifier.height(6.dp))
                    printers.forEach { dev ->
                        val isSel = selected?.address == dev.address
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSel) g.accent.copy(alpha = 0.16f)
                                    else Color.Transparent
                                )
                                .clickable { selected = dev }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val name = try { dev.name ?: dev.address } catch (e: SecurityException) { dev.address }
                            Text(
                                name, color = if (isSel) g.accent else g.textPrimary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    ctx.getString(R.string.paper_width),
                    style = MaterialTheme.typography.titleSmall, color = g.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidthPill("58 mm", width == 32) { width = 32 }
                    WidthPill("80 mm", width == 48) { width = 48 }
                }

                // وظيفة 36 — معاينة نصية قابلة للتمرير قبل زر الطباعة
                // (من مولّد الإيصال القائم نفسه — بلا أي تغيير في منطق الطباعة)
                Spacer(Modifier.height(10.dp))
                Text(
                    ctx.getString(R.string.print_preview),
                    style = MaterialTheme.typography.titleSmall, color = g.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                val preview = remember(receipt, width) { ReceiptPreview.text(receipt, width) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(g.textSecondary.copy(alpha = 0.08f))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        preview,
                        color = g.textPrimary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (msg.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(msg, color = g.accent2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && !printing,
                onClick = { doPrint() }
            ) {
                Text(
                    ctx.getString(R.string.print_now),
                    color = if (selected != null && !printing) g.accent else g.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            // [P20-FIX agent15]: كان زر الإغلاق فعالاً أثناء الطباعة — إلغاء التركيب يقطع الكوروتين
            // ويغلق مقبس البلوتوث بينما البايتات في ذاكرة الطابعة ⇒ إيصال مقطوع
            TextButton(enabled = !printing, onClick = onDismiss) {
                Text(ctx.getString(R.string.close), color = g.textSecondary)
            }
        }
    )
}

@Composable
private fun WidthPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) g.accent.copy(alpha = 0.18f) else g.textSecondary.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (selected) g.accent else g.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}
