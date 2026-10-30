package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors

/**
 * — عارض المستندات القانونية داخل التطبيق (V 3.6.0)
 *
 * سياسة الخصوصية وشروط الاستخدام يُقرآن **دون إنترنت** حصراً — النصان
 * مشحونان في `res/raw` (عربي + إنجليزي، واللغات الأخرى تسقط على الإنجليزية
 * وهو المعيار المتبع في المستندات القانونية)، ولا يُجري هذا العارض أي
 * اتصال: لا WebView، لا تحميل، لا تتبّع — اتساقاً مع وعد «دون اتصال 100٪»
 * في وثيقة الخصوصية نفسها ([docs/PRIVACY_POLICY.md]).
 *
 * لغة العرض: العربية للجهاز/التطبيق العربي، والإنجليزية لغير ذلك.
 * التنسيق: محرك blocks بسيط (# عنوان / ## قسم / - نقطة / **غامق**).
*/

/** كتلة مستند قانوني بعد التحليل */
private sealed interface LegalBlock {

    /** عنوان الوثيقة الرئيسي (#) — يُتجاهل في العرض لأن الشاشة تحمل العنوان */
    data object DocTitle : LegalBlock

    /** عنوان قسم (##) */
    data class Heading(val text: String) : LegalBlock

    /** فقرة نصية */
    data class Paragraph(val text: String) : LegalBlock

    /** نقطة تعداد (-) */
    data class Bullet(val text: String) : LegalBlock
}

/** تحليل النص الخام إلى كتل — كل سطر كتلة مستقلة، الأسطر الفارغة فاصل بصري */
private fun parseLegalDoc(raw: String): List<LegalBlock> = raw
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        when {
            line.startsWith("# ") -> LegalBlock.DocTitle
            line.startsWith("## ") -> LegalBlock.Heading(line.drop(3).trim())
            line.startsWith("- ") -> LegalBlock.Bullet(line.drop(2).trim())
            else -> LegalBlock.Paragraph(line)
        }
    }

/** يحوّل علامات **الغامق** في النص إلى AnnotatedString غامق بلون أساسي */
private fun boldAwareText(text: String, baseColor: Color, boldColor: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (m in Regex("\\*\\*(.+?)\\*\\*").findAll(text)) {
            append(text.substring(last, m.range.first))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = boldColor))
            append(m.groupValues[1])
            pop()
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

/**
 * اختيار مصدر المستند حسب لغة الواجهة الحالية: عربي → النص العربي،
 * وغيره → الإنجليزي (السقوط القياسي للمستندات القانونية).
 * minSdk 24 → Configuration.locales (LocaleList) متاح دائماً.
 */
@Composable
private fun legalRawForLocale(privacy: Boolean): Int {
    val lang = LocalConfiguration.current.locales.get(0).language
    return when {
        privacy && lang == "ar" -> R.raw.legal_privacy_ar
        privacy -> R.raw.legal_privacy_en
        lang == "ar" -> R.raw.legal_terms_ar
        else -> R.raw.legal_terms_en
    }
}

/** العارض الموحّد — نفس الهيكل لسياسة الخصوصية وشروط الاستخدام */
@Composable
private fun LegalDocScreen(
    title: String,
    rawRes: Int,
    onBack: () -> Unit
) {
    val g = glassColors()
    val context = LocalContext.current
    // القراءة مرة واحدة في عمر الشاشة — لا إعادة قراءة مع كل recomposition
    val blocks = remember(rawRes) {
        parseLegalDoc(context.resources.openRawResource(rawRes).bufferedReader().use { it.readText() })
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // شريط علوي مطابق لبقية شاشات المركز
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = g.textPrimary)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 90.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is LegalBlock.Heading -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            boldAwareText(block.text, g.accent, g.accent),
                            style = MaterialTheme.typography.titleMedium,
                            color = g.accent
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    is LegalBlock.Paragraph -> Text(
                        boldAwareText(block.text, g.textSecondary, g.textPrimary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary
                    )
                    is LegalBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.accent,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            boldAwareText(block.text, g.textSecondary, g.textPrimary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = g.textSecondary
                        )
                    }
                    LegalBlock.DocTitle -> Unit // عنوان الوثيقة مغطى بعنوان الشاشة
                }
                Spacer(Modifier.height(6.dp))
            }

            // خاتمة: بطاقة توثيق المصدر — الاتساق مع مستندات المستودع
            Spacer(Modifier.height(10.dp))
            GlassCard(corner = 18.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.legal_source_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
            }
        }
    }
}

/** سياسة الخصوصية — من مركز الإعدادات ← الدعم والتواصل */
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    LegalDocScreen(
        title = stringResource(R.string.support_row_privacy),
        rawRes = legalRawForLocale(privacy = true),
        onBack = onBack
    )
}

/** شروط الاستخدام — من مركز الإعدادات ← الدعم والتواصل */
@Composable
fun TermsOfUseScreen(onBack: () -> Unit) {
    LegalDocScreen(
        title = stringResource(R.string.support_row_terms),
        rawRes = legalRawForLocale(privacy = false),
        onBack = onBack
    )
}
