package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.domain.sync.Argon2id
import com.superbiz.app.domain.sync.PairingSalt
import com.superbiz.app.domain.sync.SyncMerge
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [H4-3][V 3.0.0] بطاقة «المزامنة المشفرة بين الجهازين» — بوابة ADR-002 مزدوجة
 * الأبواب ولوحة الحالة في مكان واحد (نمط ZatcaLinkCard):
 *
 * - **مفتاح التفعيل** (افتراضه مغلق): فتحه يتطلب عبارة مرور + نقطة نهاية يملكها
 *   المالك. العبارة لا تُخزَّن إطلاقاً — تُشتق KEK بـArgon2id وتُغلَّف بمفتاح
 *   الجهاز، وبصمة الإقران (أول 8 بايتات SHA256) تُعرض لتأكيدها وجهاً لوجه (D5).
 * - **التحذير الصريح**: نسيان العبارة = فقدان وصول المزامنة نهائياً — نص معلن
 *   لا تحذير صغير (عقد ADR-002 §4).
 * - **الحالة**: التفعيل، نقطة النهاية (HTTPS حصراً)، التغييرات المعلقة في الدفتر،
 *   وآخر دورة مزامنة. كل شيء من البوابة والقاعدة مباشرة (produceState) — بلا VM
 *   موازٍ (نمط البطاقات الذاتية في المركز).
 */
@Composable
fun SyncLinkCard() {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state = produceState(initialValue = Quint(false, "", "", 0, -1, -1), context) {
        val graph = withContext(Dispatchers.IO) { AppGraph.from(context.applicationContext) }
        val enabled = graph.sync.enabledOnce()
        val endpoint = graph.sync.endpointOnce()
        val deviceId = runCatching { graph.sync.deviceIdOnce() }.getOrDefault("")
        val pending = runCatching { graph.syncEngine.pendingCount() }.getOrDefault(0)
        val last = graph.sync.lastSyncAtOnce()
        value = Quint(enabled, endpoint, deviceId, pending, 0, last)
    }

    val enabled = state.value.enabled
    val endpoint = state.value.endpoint
    val pending = state.value.pending
    val lastSyncAt = state.value.lastAt
    val loaded = state.value.loaded == 0

    var dialogStep by remember { mutableStateOf(0) } // 0 لا شيء / 1 عبارة ×2 / 2 نقطة نهاية

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium, color = g.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sync_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (!com.superbiz.app.domain.rbac.SessionState.effective()
                            .can(com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE)
                        ) return@Switch
                        scope.launch(Dispatchers.IO) {
                            val graph = AppGraph.from(context.applicationContext)
                            graph.sync.setEnabled(on)
                            if (on) SyncWorker.schedulePeriodic(context) else SyncWorker.cancelPeriodic(context)
                        }
                    }
                )
            }
            if (!loaded) return@Column

            if (enabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sync_endpoint_label) + " " +
                        (endpoint.ifEmpty { stringResource(R.string.sync_endpoint_unset) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sync_pending) + " " + pending,
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
                if (lastSyncAt > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.sync_last) + " " +
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastSyncAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { dialogStep = 1 }) {
                        Text(stringResource(R.string.sync_action_endpoint), color = g.accent)
                    }
                    TextButton(onClick = { dialogStep = 2 }) {
                        Text(stringResource(R.string.sync_action_passphrase), color = g.accent)
                    }
                }
            }

            if (dialogStep == 1) {
                EndpointDialog(onDismiss = { dialogStep = 0 })
            }
            if (dialogStep == 2) {
                PassphraseDialog(onDismiss = { dialogStep = 0 })
            }
        }
    }
}

/** حامل حالة البطاقة — سداسي خفيف بلا VM موازٍ. */
private data class Quint(
    val enabled: Boolean,
    val endpoint: String,
    val deviceId: String,
    val pending: Int,
    val loaded: Int,
    val lastAt: Long
)

/** [RVW-8 V 3.2.3] نتيجة تحديد ملح الاشتقاق — رمز غير صالح = فشل ظاهر لا صامت. */
private sealed class PairingResolution {
    data class Resolved(val salt: ByteArray) : PairingResolution()
    object InvalidCode : PairingResolution()
}

/**
 * [RVW-8 V 3.2.3] تحديد ملح الاشتقاق الفعلي — الترتيب يحفظ كل عقد قائم حرفياً:
 * 1. رمز مُدخل ⇒ فك صارم (null ⇒ InvalidCode) ويُحفظ ليبقى إعادة الاشتقاق متسقة.
 * 2. ملح مخزّن (إقران قائم) ⇒ يُعاد استخدامه — لا انكسار صامت لإقران جارٍ.
 * 3. بلا ملح مخزّن مع غلاف KEK قائم (إقران legacy من نسخة سابقة) ⇒ ملح المجال
 *    الثابت كما كانت النسخ القديمة حرفياً (عقد v1 محفوظ).
 * 4. إقران جديد كلياً ⇒ ملح عشوائي v2 يُولَّد ويُخزَّن ويُعرض.
 * ملح مخزّن تالف ⇒ سقوط آمن إلى v1 (سلوك النسخ السابقة) لا اشتقاق مكسور.
 */
private suspend fun resolvePairingSalt(
    graph: AppGraph,
    enteredCode: String
): PairingResolution {
    val stored = graph.sync.pairingSaltOnce()
    if (enteredCode.isNotBlank()) {
        val decoded = PairingSalt.decodeOrNull(enteredCode)
            ?: return PairingResolution.InvalidCode
        val encoded = PairingSalt.encode(decoded)
        if (stored != encoded) graph.sync.setPairingSalt(encoded)
        return PairingResolution.Resolved(decoded)
    }
    if (stored.isNotEmpty()) {
        return PairingResolution.Resolved(PairingSalt.decodeOrNull(stored) ?: Argon2id.DOMAIN_SALT)
    }
    if (graph.sync.wrappedKekOnce().isNotEmpty()) {
        return PairingResolution.Resolved(Argon2id.DOMAIN_SALT)
    }
    val fresh = PairingSalt.newSalt()
    graph.sync.setPairingSalt(PairingSalt.encode(fresh))
    return PairingResolution.Resolved(fresh)
}

/** حوار نقطة النهاية — HTTPS حصراً (العقد في SyncClient). */
@Composable
private fun EndpointDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_endpoint_title)) },
        text = {
            Column {
                Text(stringResource(R.string.sync_endpoint_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = false },
                    isError = error,
                    singleLine = true,
                    placeholder = { Text("https://") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sync_endpoint_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = url.trim()
                if (trimmed.startsWith("https://") && trimmed.length > 8) {
                    scope.launch(Dispatchers.IO) {
                        AppGraph.from(context.applicationContext).sync.setEndpoint(trimmed)
                        SyncWorker.schedulePeriodic(context)
                    }
                    onDismiss()
                } else error = true
            }) { Text(stringResource(R.string.sync_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_cancel)) } }
    )
}

/**
 * حوار عبارة المرور — إدخالان متطابقان + بصمة الإقران بعد الاشتقاق.
 * الاشتقاق Argon2id 64MiB/3/1 يجري على Dispatchers.Default (عمل ثقيل مقصود — D1).
 *
 * [RVW-8 V 3.2.3] ملح الإقران v2: الجهاز الأول يولّد ملحاً عشوائياً لكل إقران
 * ويعرضه «رمز إقران» يُدخل على الجهاز الآخر — يقتل الجدولة المسبقة المُستهلكة
 * عبر الأجهزة مع بقاء التوافق حرفياً: حقل فارغ ⇒ ملح المجال الثابت (v1) للأقران
 * القدامى، وإقران قائم يعيد ملحه المخزّن — لا انكسار صامت. الرمز المنقوح خاطئاً
 * ⇒ بصمة لا تتطابق وجهًا لوجه (فشل ظاهر لا صامت).
 */
@Composable
private fun PassphraseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf("") }
    var saltInput by remember { mutableStateOf("") }
    var saltError by remember { mutableStateOf(false) }
    var saltCode by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.sync_pass_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_pass_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass1, onValueChange = { pass1 = it; mismatch = false },
                    singleLine = true, isError = mismatch,
                    label = { Text(stringResource(R.string.sync_pass_hint)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass2, onValueChange = { pass2 = it; mismatch = false },
                    singleLine = true, isError = mismatch,
                    label = { Text(stringResource(R.string.sync_pass_hint2)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mismatch) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sync_pass_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = saltInput,
                    onValueChange = { saltInput = it; saltError = false },
                    singleLine = true,
                    isError = saltError,
                    label = { Text(stringResource(R.string.sync_pair_code_label)) },
                    supportingText = { Text(stringResource(R.string.sync_pair_code_hint), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (saltError) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.sync_pair_code_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (fingerprint.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sync_fingerprint), style = MaterialTheme.typography.bodySmall)
                    Text(fingerprint, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sync_fingerprint_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (saltCode.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.sync_pair_code_show), style = MaterialTheme.typography.bodySmall)
                        Text(saltCode, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pass1.length >= 8,
                onClick = {
                    if (pass1 != pass2) { mismatch = true; return@TextButton }
                    busy = true
                    scope.launch(Dispatchers.Default) {
                        val graph = AppGraph.from(context.applicationContext)
                        val resolution = withContext(Dispatchers.IO) {
                            resolvePairingSalt(graph, saltInput)
                        }
                        if (resolution is PairingResolution.InvalidCode) {
                            withContext(Dispatchers.Main) { busy = false; saltError = true }
                            return@launch
                        }
                        val salt = (resolution as PairingResolution.Resolved).salt
                        val kek = Argon2id.derive(pass1.toByteArray(Charsets.UTF_8), salt)
                        val fp = SyncMerge.fingerprint(kek)
                        val code = PairingSalt.encode(salt)
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (fingerprint.isEmpty()) {
                                fingerprint = fp
                                saltCode = code
                                // الاشتقاق الأول: غلّف KEK واحفظه — لم يُفعل بعد حتى الضغط الثاني
                                scope.launch(Dispatchers.IO) {
                                    com.superbiz.app.security.SyncKeyVault.wrapAndStore(kek, graph.sync)
                                }
                            } else {
                                onDismiss()
                            }
                        }
                    }
                }
            ) {
                Text(
                    if (fingerprint.isEmpty()) stringResource(R.string.sync_pass_derive)
                    else stringResource(R.string.sync_pass_confirm_fp)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_cancel)) } }
    )
}
