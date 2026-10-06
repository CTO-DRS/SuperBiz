package com.superbiz.app.ui.screens.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.superbiz.app.R
import com.superbiz.app.domain.algo.ZatcaPem
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.security.ZatcaKeys
import com.superbiz.app.security.ZatcaPrefs

/**
 * [P15-a] بطاقة «بصمة ZATCA للفاتورة (مرحلة-2)» في مركز الإعدادات — قاربة ذاتية
 * تماماً بلا معاملات (نمط VisitReminderCard/VisitsSection ذاته): يستدعيها
 * 15-b من SettingsHubScreen بسطر وحيد `ZatcaCard()` دون تمرير أي اعتماد.
 *
 * - الحالة (stamped) تُقرأ من prefs «zatca» عبر ZatcaPrefs مباشرة، والكتابة عبر
 *   ميزة ZatcaPrefs نفسها — لا نسخة موازية ولا VM؛ مفتاح الحقيقة واحد.
 * - التفعيل يمر عبر ZatcaKeys.ensureKeyPair داخل ZatcaPrefs.setEnabled: تعذّر
 *   تجهيز مفتاح الجهاز ⇒ لا يُفعَّل (المفتاح يبقى مطفأً) ويظهر صف خطأ عنبري.
 * - التوقيع ذاته يحدث لحظة توليد PDF الفاتورة (مفتاح الجهاز EC P-256) — البطاقة
 *   مجرد مفتاح تشغيل وتعريف، وهي صادقة في توضيح أن التوثيق الرسمي لدى ZATCA
 *   يتطلب تكاملاً مع منصة «فاتورة» خارج نطاق التطبيق الأوفلاين.
 *
 * [P16-a] قسم التصدير (يظهر فقط والبصمة مفعّلة): صف بصمة SHA-256 للمفتاح العام
 * (SPKI عبر ZatcaKeys.publicKeySpki ثم ZatcaPem.fingerprintSha256) بخط ثابت
 * العرض واتجاه LTR إلزامي (hex محايد الاتجاه ينكسر تحت RTL)، وصف «تصدير» ينسخ
 * PEM كاملاً إلى الحافظة (Toast تأكيد) ثم يُطلق مشاركة نصية بـ PEM + سطر البصمة؛
 * غياب مُشغّل مشاركة يُتجاهل بصمت لأن النسخ سبق وأن نجح. PEM الطويل (~400 محرف)
 * لا يُرسَم في البطاقة إطلاقاً — البصمة والعملية فقط.
 */
@Composable
fun ZatcaCard() {
    val g = glassColors()
    val context = LocalContext.current

    // ─── حالة الإعداد من prefs «zatca» (قراءة أولى مباشرة، والكتابة تُحدّثها محلياً) ───
    var enabled by remember { mutableStateOf(ZatcaPrefs.enabled(context)) }
    // فشل تجهيز مفتاح التوقيع في آخر محاولة تفعيل — صف خطأ لحظي يختفي عند الإيقاف/النجاح
    var keyFailed by remember { mutableStateOf(false) }

    // ─── [P16-a] المفتاح العام وبصمته — قراءة واحدة لكل تغيير حالة التفعيل فقط ───
    // SPKI من KeyStore وSHA-256 على ~91 بايت: كلفة ضئيلة على الخيط الرئيسي،
    // ومتوقفة على «enabled» كي لا تُقرأ مفتاح KeyStore إطلاقاً والبصمة مطفأة.
    val spki = remember(enabled) { if (enabled) ZatcaKeys.publicKeySpki() else null }
    val fingerprint = remember(spki) { spki?.let { ZatcaPem.fingerprintSha256(it) } }
    // فشل قراءة المفتاح عند آخر محاولة تصدير — صف عنبري لحظي (نمط keyFailed نفسه)
    var exportFailed by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val fpLabelText = stringResource(R.string.zat_fp_label)
    val copiedMsg = stringResource(R.string.zat_copied)
    val chooserTitle = stringResource(R.string.zat_share_chooser)
    // ─── [P16-a] التصدير: نسخ PEM كاملاً للحافظة (أولاً والأهم) ثم مشاركة نصية ───
    val exportKey: () -> Unit = {
        // قراءة حية وقت الضغط (لا اعتماد على النسخة المُحفظة للتركيب) — تعذّرها
        // صف خطأ، ونجاحها يلغيه
        val der = ZatcaKeys.publicKeySpki()
        if (der == null) {
            exportFailed = true
        } else {
            exportFailed = false
            val pem = ZatcaPem.toPem(der)
            clipboard.setText(AnnotatedString(pem))
            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            // نص المشاركة: PEM كاملاً + سطر البصمة (إن توفّرت) — كي يطابق المُتحقِّق
            // المفتاح بصرياً قبل الاستخدام
            val fpLine = fingerprint?.takeIf { it.isNotBlank() }
                ?.let { "\n\n" + fpLabelText + ": " + it } ?: ""
            // مُشغّل مشاركة غير متاح ⇒ تجاهل بصمت — الحافظة سبق وأن نجحت
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, pem + fpLine)
                }
                context.startActivity(Intent.createChooser(send, chooserTitle))
            } catch (_: Exception) {
                // مقصود: بلا Toast ولا سجل — النسخ إلى الحافظة هو الضمانة
            }
        }
    }

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {

            // العنوان + الوصف + مفتاح التفعيل
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.zat_title),
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.zat_desc),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on) {
                            // التفعيل يجهّز مفتاح AndroidKeyStore أولاً — فشله يعني
                            // بقاء المفتاح مطفأً مع إظهار سبب الفشل (بلا تفعيل كاذب)
                            val ok = ZatcaPrefs.setEnabled(context, true)
                            enabled = ok
                            keyFailed = !ok
                        } else {
                            ZatcaPrefs.setEnabled(context, false)
                            enabled = false
                            keyFailed = false
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            // سطر الحالة: خطأ المفتاح / المفتاح جاهز / موقوف
            Text(
                when {
                    keyFailed -> stringResource(R.string.zat_key_fail)
                    enabled -> stringResource(R.string.zat_on_note)
                    else -> stringResource(R.string.zat_status_off)
                },
                color = if (keyFailed) g.amber else g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            // ─── [P16-a] بصمة المفتاح العام — تظهر فقط مفعّلة وبنجاح القراءة ───
            if (enabled && !fingerprint.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.zat_fp_label),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    fingerprint,
                    color = g.textPrimary,
                    // خط ثابت العرض لمطابقة الأزواج بالنظر + LTR إلزامي: hex مع
                    // نقطتين نص محايد الاتجاه تُعيد ترتيبه خوارزمية RTL
                    // [P16-integration] textDirection خاصية TextStyle لا وسيط للـ Text
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // ─── [P16-a] صف «تصدير المفتاح العام» — نسخ + مشاركة ───
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { exportKey() }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.zat_export_t),
                            color = g.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            stringResource(R.string.zat_export_d),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (exportFailed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.zat_fp_fail),
                        color = g.amber,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
