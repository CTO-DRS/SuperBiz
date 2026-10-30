package com.superbiz.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe

/**
 * — قسم «الدعم والتواصل» في مركز الإعدادات (V 3.6.0)
 *
 * قنوات المطور الرسمية كما هي في بطاقة التواصل الرسمية (PDF) — بلا
 * اختلاق ولا قنوات مكررة: واتساب (حساب تجاري) · تيليجرام · جيت هب ·
 * الموقع الرسمي · يوتيوب · إكس · إنستغرام · تيك توك.
 *
 * **عقد الخصوصية**: النقر على أي قناة يفتح `ACTION_VIEW` عبر
 * [startIntentSafe] — التطبيق لا يرسل شيئاً بنفسه، بل يكلّف تطبيق القناة
 * الرسمي على الجهاز بذلك، اتساقاً مع وثيقة الخصوصية («لا تتبّع»).
 *
 * المستندات القانونية (سياسة الخصوصية + شروط الاستخدام) تُقرأ داخل
 * التطبيق دون إنترنت عبر [PrivacyPolicyScreen] و[TermsOfUseScreen].
*/

/** قناة تواصل رسمية واحدة: تسمية + تلميح + رابط + أيقونة + لون العلامة */
private data class SupportChannel(
    val labelRes: Int,
    val hintRes: Int,
    val url: String,
    val icon: ImageVector,
    val brandColor: Color
)

/** القنوات الرسمية الثماني — المصدر: بطاقة التواصل الرسمية لـCTO.DRS */
private val SUPPORT_CHANNELS: List<SupportChannel> = listOf(
    SupportChannel(
        R.string.support_ch_whatsapp, R.string.support_ch_whatsapp_hint,
        "https://wa.me/message/MVTVWPOPFOVFF1",
        Icons.AutoMirrored.Rounded.Chat, Color(0xFF25D366)
    ),
    SupportChannel(
        R.string.support_ch_telegram, R.string.support_ch_telegram_hint,
        "https://t.me/CTO_DRS",
        Icons.AutoMirrored.Rounded.Send, Color(0xFF229ED9)
    ),
    SupportChannel(
        R.string.support_ch_github, R.string.support_ch_github_hint,
        "https://github.com/CTO-DRS",
        Icons.Rounded.Code, Color(0xFF6E7681)
    ),
    SupportChannel(
        R.string.support_ch_website, R.string.support_ch_website_hint,
        "https://cto-drs.xo.je",
        Icons.Rounded.Language, Color(0xFF7C4DFF)
    ),
    SupportChannel(
        R.string.support_ch_youtube, R.string.support_ch_youtube_hint,
        "https://www.youtube.com/@cto-drs",
        Icons.Rounded.SmartDisplay, Color(0xFFFF0000)
    ),
    SupportChannel(
        R.string.support_ch_x, R.string.support_ch_x_hint,
        "https://x.com/cto_drs",
        Icons.Rounded.AlternateEmail, Color(0xFF2A3038)
    ),
    SupportChannel(
        R.string.support_ch_instagram, R.string.support_ch_instagram_hint,
        "https://www.instagram.com/cto.drs",
        Icons.Rounded.PhotoCamera, Color(0xFFE1306C)
    ),
    SupportChannel(
        R.string.support_ch_tiktok, R.string.support_ch_tiktok_hint,
        "https://www.tiktok.com/@cto.drs",
        Icons.Rounded.MusicNote, Color(0xFFEE1D52)
    )
)

/** صف قناة: شارة أيقونة بلون العلامة + التسمية + التلميح + سهم الدخول */
@Composable
private fun ChannelRow(channel: SupportChannel) {
    val g = glassColors()
    val context = LocalContext.current
    GlassCard(corner = 18.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    // [عقد الخصوصية] فتح خارجي آمن — بلا تمرير بيانات، وبلا انهيار
                    // لو ما فيه تطبيق يتعامل مع الرابط
                    startIntentSafe(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse(channel.url)),
                        R.string.support_open_fail
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(channel.brandColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(channel.icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(channel.labelRes),
                    color = g.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(channel.hintRes),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                null,
                tint = g.textSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** صف المستند القانوني: أيقونة + عنوان + تلميح — يفتح عارضاً داخل التطبيق */
@Composable
private fun LegalDocRow(
    icon: ImageVector,
    titleRes: Int,
    hintRes: Int,
    onClick: () -> Unit
) {
    val g = glassColors()
    GlassCard(corner = 18.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(titleRes),
                    color = g.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(hintRes),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                null,
                tint = g.textSecondary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * محتوى قسم «الدعم والتواصل» — بطاقة هوية المطور، ثم القنوات الثماني،
 * ثم المستندات القانونية داخل التطبيق.
 */
@Composable
internal fun SupportContactSection(
    onOpenPrivacyPolicy: () -> Unit,
    onOpenTerms: () -> Unit
) {
    val g = glassColors()
    val channels = remember { SUPPORT_CHANNELS }

    // بطاقة هوية المطور — «CD» على التدرج البنفسجي/السماوي نفسه المستخدم
    // في رقائق المركز (هوية بصرية موحّدة بلا صور خارجية)
    GlassCard(corner = 18.dp) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "CD",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.support_dev_name),
                color = g.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.support_dev_role),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.support_dev_tag),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    // القنوات الرسمية — بترتيب الأقرب للدعم الفني: واتساب ثم تيليجرام
    channels.forEach { ChannelRow(it) }

    // ملاحظة الشفافية — لماذا هذا لا يناقض «دون اتصال»
    Text(
        stringResource(R.string.support_links_note),
        color = g.textSecondary,
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(12.dp))

    // المستندات القانونية — داخل التطبيق ودون إنترنت
    Text(
        stringResource(R.string.support_legal_title),
        color = g.textPrimary,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    LegalDocRow(
        Icons.Rounded.PrivacyTip,
        R.string.support_row_privacy,
        R.string.support_row_privacy_hint,
        onClick = onOpenPrivacyPolicy
    )
    LegalDocRow(
        Icons.Rounded.Gavel,
        R.string.support_row_terms,
        R.string.support_row_terms_hint,
        onClick = onOpenTerms
    )
}
