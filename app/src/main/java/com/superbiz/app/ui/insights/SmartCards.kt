package com.superbiz.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.ui.components.BizPill
import com.superbiz.app.ui.components.PillMode

/**
 * — مكتبة البطاقات الذكية المشتركة بين الشاشات.
 * كل بطاقة: عنوان + محتوى حقيقي + حالة فراغ صادقة (لا بطاقة بلا بيانات)
 * + سطر تلميح يشرح آلية الحساب — شفافية كاملة للمستخدم.
 * الألوان الدلالية من الثيم الحقيقي (theme/Theme.kt).
*/

@Composable
fun SmartCardShell(
    title: String,
    hint: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(accent, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            content()
            Spacer(Modifier.height(8.dp))
            Text(hint, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
    }
}

/** صف مفتاح/قيمة داخل البطاقات */
@Composable
fun KV(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

/** [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (BADGE) — التنفيذ الوحيد في Common.kt */
@Composable
fun Chip(text: String, color: Color, bgAlpha: Float = 0.14f) {
    BizPill(text, color, mode = PillMode.BADGE, badgeAlpha = bgAlpha)
}
