package com.superbiz.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.window.Dialog
import com.superbiz.app.R
import com.superbiz.app.util.startIntentSafe

/** حساب رسمي للمطور: تسمية مترجمة + رابط + لون العلامة + شعار (متجه للعلامة أو أيقونة عامة للموقع) */
internal data class DevLink(
    @StringRes val label: Int,
    val url: String,
    val brand: Color,
    @DrawableRes val glyph: Int? = null,
    val icon: ImageVector? = null
)

/** الحسابات الرسمية كما في بطاقة المطور المعتمدة — الترتيب والألوان من مرجع NeoKey */
internal val DEV_LINKS: List<DevLink> = listOf(
    DevLink(R.string.about_dev_l_whatsapp, "https://wa.me/message/MVTVWPOPFOVFF1", Color(0xFF25D366), R.drawable.ic_brand_whatsapp),
    DevLink(R.string.about_dev_l_telegram, "https://t.me/CTO_DRS", Color(0xFF229ED9), R.drawable.ic_brand_telegram),
    DevLink(R.string.about_dev_l_tiktok, "https://www.tiktok.com/@cto.drs", Color(0xFF111111), R.drawable.ic_brand_tiktok),
    DevLink(R.string.about_dev_l_instagram, "https://www.instagram.com/cto.drs", Color(0xFFE1306C), R.drawable.ic_brand_instagram),
    DevLink(R.string.about_dev_l_x, "https://x.com/cto_drs", Color(0xFF000000), R.drawable.ic_brand_x),
    DevLink(R.string.about_dev_l_youtube, "https://youtube.com/@cto-drs", Color(0xFFFF0000), R.drawable.ic_brand_youtube),
    DevLink(R.string.about_dev_l_github, "https://github.com/CTO-DRS", Color(0xFF24292F), R.drawable.ic_brand_github),
    DevLink(R.string.about_dev_l_website, "https://cto-drs.com", Color(0xFF4F46E5), null, Icons.Rounded.Language)
)

private val PanelFill = Color(0xB80B0C24)          // #0B0C24 ~72%
private val TitleColor = Color(0xFFDDE1EA)
private val Violet = Color(0xFF6D3FC9)

/**
 * قسم «عن المطوّر» بنفس مرجع NeoKey: ترويسة بشريط بنفسجي، لوحة زجاجية داكنة (حدود متدرجة + لمعة علوية)،
 * صورة بحلقة متدرجة وشارة توثيق، شبكة 8 حسابات بشعارات العلامة (4 في الصف)، وتذييل الإصدار.
 * النقر على الصورة يفتح عارضًا بملء الشاشة؛ النقر على حساب يفتح رابطه خارجيًا بأمان.
 */
@Composable
internal fun AboutDeveloperCard() {
    val context = LocalContext.current
    var showPhoto by remember { mutableStateOf(false) }
    var qrLink by remember { mutableStateOf<DevLink?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── ترويسة القسم
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(Violet))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_dev_title), color = TitleColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Color(0x40A78BFA)))
        }

        // ── اللوحة الزجاجية
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PanelFill)
                .background(Color(0x0CFFFFFF))
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color(0x5C8E5BD0), Color(0x5C5558C2), Color(0x5C2FA9C0))
                    ),
                    shape
                )
        ) {
            Box(
                Modifier.fillMaxWidth().height(60.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x1CFFFFFF), Color.Transparent)))
            )
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(4.dp))
                // الصورة: حلقة متدرجة + فجوة داكنة + صورة دائرية + شارة توثيق
                Box(Modifier.size(96.dp)) {
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape).background(
                            Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF06B6D4), Color(0xFFA855F7)))
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(92.dp).clip(CircleShape).background(Color(0xFF161437)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.dev_avatar),
                                contentDescription = stringResource(R.string.about_dev_photo_desc),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(86.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button) { showPhoto = true }
                            )
                        }
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1D9BF0))
                            .border(2.dp, Color(0xFF161437), CircleShape)
                            .semantics { contentDescription = "verified" },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Check, stringResource(R.string.about_dev_verified), tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("CTO.DRS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.about_dev_role),
                    color = Color(0xFFA5B4FC), fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))

                // شبكة الحسابات: 4 في الصف
                DEV_LINKS.chunked(4).forEachIndexed { rowIdx, row ->
                    if (rowIdx > 0) Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { link ->
                            val label = stringResource(link.label)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { qrLink = link }
                                    .semantics { contentDescription = label }
                                    .padding(vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(link.brand)
                                        .border(1.dp, Color(0x40FFFFFF), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (link.glyph != null) {
                                        Icon(painterResource(link.glyph), null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    } else if (link.icon != null) {
                                        Icon(link.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(label, color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.about_dev_footer, stringResource(R.string.app_name), com.superbiz.app.BuildConfig.VERSION_NAME),
                    color = Color(0xFF64748B), fontSize = 11.sp, textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showPhoto) {
        Dialog(onDismissRequest = { showPhoto = false }) {
            Box(
                Modifier.fillMaxSize().clickable { showPhoto = false },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.dev_avatar),
                    contentDescription = stringResource(R.string.about_dev_photo_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                )
            }
        }
    }

    qrLink?.let { link -> DevQrSheet(link, onDismiss = { qrLink = null }) }
}

/**
 * نافذة الحساب: رمز QR يُولَّد على الجهاز (zxing عبر BarcodeGen) + زرا «فتح» و«نسخ» بنفس زجاج لوحة المطور.
 * التسلسل: «فتح» يشغّل الرابط ثم يغلق النافذة؛ «نسخ» ينسخ الرابط ثم يغلقها؛ النقر خارجها يغلقها.
 */
@Composable
private fun DevQrSheet(link: DevLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val label = stringResource(link.label)
    val copiedMsg = stringResource(R.string.about_dev_copied)
    val bitmap = remember(link.url) { com.superbiz.app.util.BarcodeGen.qr(link.url, 600)?.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(0xF00B0C24))
                .background(Color(0x0CFFFFFF))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color(0x5C8E5BD0), Color(0x5C5558C2), Color(0x5C2FA9C0))),
                    shape
                )
        ) {
            Box(
                Modifier.fillMaxWidth().height(60.dp)
                    .background(Brush.verticalGradient(listOf(Color(0x1CFFFFFF), Color.Transparent)))
            )
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(link.brand)
                        .border(1.dp, Color(0x40FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (link.glyph != null) {
                        Icon(painterResource(link.glyph), null, tint = Color.White, modifier = Modifier.size(22.dp))
                    } else if (link.icon != null) {
                        Icon(link.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.size(210.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(bitmap = bitmap, contentDescription = label, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
                ) {
                    Text(
                        link.url.removePrefix("https://"),
                        color = Color(0xFFA5B4FC), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Button(
                        onClick = {
                            startIntentSafe(
                                context,
                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url)),
                                R.string.support_open_fail
                            )
                            onDismiss()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (link.brand == Color(0xFF000000) || link.brand == Color(0xFF111111)) Color(0xFF6D3FC9) else link.brand,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.about_dev_open)) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(link.url))
                            android.widget.Toast.makeText(context, copiedMsg, android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.about_dev_copy)) }
                }
            }
        }
    }
}
