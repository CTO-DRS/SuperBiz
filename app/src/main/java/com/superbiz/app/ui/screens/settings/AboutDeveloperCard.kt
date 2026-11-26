package com.superbiz.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe

/** رابط حساب رسمي للمطور: اسم العلامة (لا يُترجم) + الرابط + الأيقونة أو حرف بديل */
internal data class DevLink(
    val label: String,
    val url: String,
    val icon: ImageVector?,
    val glyph: String? = null
)

/** الحسابات الرسمية كما في بطاقة المطور المعتمدة — لا تُضف قناة غير موثّقة هنا */
internal val DEV_LINKS: List<DevLink> = listOf(
    DevLink("Website", "https://cto-drs.com", Icons.Rounded.Language),
    DevLink("GitHub", "https://github.com/CTO-DRS", Icons.Rounded.Code),
    DevLink("X", "https://x.com/cto_drs", null, "𝕏"),
    DevLink("Instagram", "https://www.instagram.com/cto.drs", Icons.Rounded.PhotoCamera),
    DevLink("TikTok", "https://www.tiktok.com/@cto.drs", Icons.Rounded.MusicNote),
    DevLink("YouTube", "https://youtube.com/@cto-drs", Icons.Rounded.SmartDisplay),
    DevLink("Telegram", "https://t.me/CTO_DRS", Icons.AutoMirrored.Rounded.Send),
    DevLink("WhatsApp", "https://wa.me/message/MVTVWPOPFOVFF1", Icons.AutoMirrored.Rounded.Chat)
)

/**
 * بطاقة «عن المطور»: صورة دائرية + الاسم + سطر هادئ + صف روابط الحسابات الرسمية.
 * تتبع لغة التصميم (GlassCard / glassColors) فتتأقلم مع الوضع الداكن واتجاه RTL تلقائيًا.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutDeveloperCard() {
    val g = glassColors()
    val context = LocalContext.current
    GlassCard(corner = 18.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.about_dev_title),
                style = MaterialTheme.typography.labelLarge,
                color = g.textSecondary
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .border(2.dp, Brush.linearGradient(listOf(Cyan, VioDeep)), CircleShape)
                    .padding(3.dp)
                    .clip(CircleShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.dev_avatar),
                    contentDescription = stringResource(R.string.about_dev_photo_desc),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "CTO: DRS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = g.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.about_dev_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = g.accent,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DEV_LINKS.forEach { link ->
                    Column(
                        Modifier
                            .width(64.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) {
                                startIntentSafe(
                                    context,
                                    Intent(Intent.ACTION_VIEW, Uri.parse(link.url)),
                                    R.string.support_open_fail
                                )
                            }
                            .semantics { contentDescription = link.label }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(g.accent.copy(alpha = 0.12f))
                                .border(BorderStroke(1.dp, g.accent.copy(alpha = 0.35f)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (link.icon != null) {
                                Icon(link.icon, null, tint = g.accent, modifier = Modifier.size(22.dp))
                            } else {
                                Text(link.glyph.orEmpty(), color = g.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            link.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = g.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
