package com.superbiz.app.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.superbiz.app.R
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.core.ErrorEvent
import com.superbiz.app.core.ErrorLevel
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * — عارض سجل الأخطاء الداخلي (مركز الإعدادات ← متقدم ← سجل الأخطاء)
 *
 * يعرض آخر 200 حدث رصدها مركز الأخطاء الموحّد مع مستواها ومصدرها وزمنها —
 * أداة تشخيص ذاتي حقيقية بدل الاصطدام الصامت.
*/
@Composable
fun ErrorLogScreen(onBack: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current
    val history by ErrorCenter.history.collectAsState()
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Rounded.ArrowBackIos,
                    null, tint = g.textPrimary
                )
            }
            Text(
                // [P20-FIX agent6]: كانت عربية صلبة — مُستخرجة إلى الموارد
                stringResource(R.string.err_log_title, history.size),
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary,
                modifier = Modifier.weight(1f)
            )
            // مشاركة السجل كنص خام — تقرير تشخيصي يُرسل لأي تطبيق
            androidx.compose.material3.TextButton(onClick = {
                val hist = history
                if (hist.isEmpty()) {
                    Toast.makeText(context, R.string.err_share_none, Toast.LENGTH_SHORT).show()
                } else {
                    val body = hist.joinToString("\n") { ev ->
                        val lvl = when (ev.level) {
                            ErrorLevel.WARN -> "WARN"
                            ErrorLevel.ERROR -> "ERROR"
                            else -> "INFO"
                        }
                        fmt.format(Date(ev.ts)) + " [" + lvl + "] " + ev.tag + ": " +
                            (ev.userMessage ?: ev.message)
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    startIntentSafe(context, Intent.createChooser(send, null))
                }
            }) {
                Text(stringResource(R.string.err_share), color = g.accent)
            }
            androidx.compose.material3.TextButton(onClick = { ErrorCenter.clearHistory() }) {
                Text(stringResource(R.string.err_log_clear), color = g.red)
            }
        }

        if (history.isEmpty()) {
            EmptyState(stringResource(R.string.err_log_empty), null)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                // مفاتيح موضعية — السجل يُلحق في النهاية فقط فالمفاتيح الموضعية آمنة
                // وموثوقة هنا (نفس دفاع ضد مفاتيح مكررة)
            ) {
                itemsIndexed(history, key = { i, _ -> "err$i" }) { _, ev ->
                    ErrorRow(ev, fmt)
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(ev: ErrorEvent, fmt: SimpleDateFormat) {
    val g = glassColors()
    val levelColor = when (ev.level) {
        ErrorLevel.ERROR -> Red
        ErrorLevel.WARN -> g.amber
        else -> Green
    }
    GlassCard(corner = 14.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(levelColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ev.tag,
                    style = MaterialTheme.typography.labelLarge,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    fmt.format(Date(ev.ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.textSecondary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                ev.userMessage ?: ev.message,
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}
