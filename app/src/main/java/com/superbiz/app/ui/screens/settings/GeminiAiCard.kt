package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [F2 - ADR-005 D4] بطاقة «الذكاء السحابي المقفول (Gemini)» — بوابة الميزة
 * ولوحة إدارتها في مكان واحد (نمط ZatcaLinkCard/WebhookCard حرفيًا):
 *
 * - **مفتاح الميزة** (افتراضه مغلق): تفعيله يقر بعقد D4 الصريح — نص OCR الخام
 *   فقط يُرسل يدويًا بعد قراءة المالك وتأكيده لكل إرسال، لا إرسال خلفي صامت
 *   ولا بيانات من قاعدة البيانات إطلاقًا؛ إيقافه يعيد الصمت الشبكي الكلي فورًا.
 * - **مفتاح API**: لا يُخزَّن نصًا عاريًا — يُشفَّر بمفتاح AndroidKeyStore عبر
 *   PinVault (عقد AUD-7) ولا يُعرض بعد الحفظ (قناع قراءة فقط).
 * - **الموديل**: نص حر يملكه المالك — فارغًا يعود لافتراضي domain.GeminiGateway.
 * - RBAC: كل الكتابات TAX_OWNER_DATA في المتجر حصرًا — البطاقة تحرس زرها
 *   بنفس البوابة قبل النداء (نمط حارس Switch في المركز حرفيًا).
 * - بلا VM موازٍ — المتجر مصدر الحقيقة الوحيد (نمط البطاقات الذاتية).
 */
@Composable
fun GeminiAiCard() {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // (مفعّل، مفتاح مُعدٌّ، موديل، محمَّل)
    val state = produceState(initialValue = Quadruple(false, false, "", false), context) {
        val graph = withContext(Dispatchers.IO) { AppGraph.from(context.applicationContext) }
        value = Quadruple(
            graph.geminiAi.enabledOnce(),
            graph.geminiAi.keyConfiguredOnce(),
            graph.geminiAi.modelOnce(),
            true
        )
    }
    val enabled = state.value.first
    val keyConfigured = state.value.second
    val modelText = state.value.third
    val loaded = state.value.fourth

    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(modelText) }

    // الاسم المحفوظ يصل لاحقًا — يُملأ الحقل عند اكتمال التحميل فقط إن كان فارغًا
    androidx.compose.runtime.LaunchedEffect(loaded, modelText) {
        if (loaded && modelInput.isBlank()) modelInput = modelText
    }

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {

            // ─── المفتاح + العنوان ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.gem_title),
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.gem_desc),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    enabled = loaded,
                    onCheckedChange = { on ->
                        // [E0.4] حارس البوابة قبل النداء — نفس نمط حارس Switch في المركز:
                        // قرار إخراج بيانات للسحابة قرار مالك حصراً (TAX_OWNER_DATA)
                        if (!com.superbiz.app.domain.rbac.SessionState.effective()
                            .can(com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA)
                        ) return@Switch
                        scope.launch(Dispatchers.IO) {
                            val graph = AppGraph.from(context.applicationContext)
                            graph.geminiAi.setEnabled(on)
                            withContext(Dispatchers.Main) { /* التحديث يلتقطه الإنتاج عند العودة */ }
                        }
                    },
                )
            }

            // ─── قراءة D4 الصريحة — دائمًا تحت المفتاح ───
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gem_enabled_warn),
                color = g.amber,
                style = MaterialTheme.typography.bodySmall,
            )

            if (enabled) {
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.gem_key_label)) },
                    placeholder = { Text(stringResource(R.string.gem_key_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        // [E0.4] نفس بوابة المالك قبل كتابة السر
                        if (!com.superbiz.app.domain.rbac.SessionState.effective()
                            .can(com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA)
                        ) return@TextButton
                        scope.launch(Dispatchers.IO) {
                            val graph = AppGraph.from(context.applicationContext)
                            graph.geminiAi.setKey(keyInput)
                            keyInput = ""
                            withContext(Dispatchers.Main) { }
                        }
                    }) { Text(stringResource(R.string.gem_key_save)) }
                    if (keyConfigured) {
                        Text(
                            stringResource(R.string.gem_key_saved),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (!com.superbiz.app.domain.rbac.SessionState.effective()
                                .can(com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA)
                            ) return@TextButton
                            scope.launch(Dispatchers.IO) {
                                AppGraph.from(context.applicationContext).geminiAi.setKey("")
                                withContext(Dispatchers.Main) { }
                            }
                        }) { Text(stringResource(R.string.gem_key_clear)) }
                    }
                }

                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    label = { Text(stringResource(R.string.gem_model_label)) },
                    placeholder = { Text(stringResource(R.string.gem_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    if (!com.superbiz.app.domain.rbac.SessionState.effective()
                        .can(com.superbiz.app.domain.rbac.Op.TAX_OWNER_DATA)
                    ) return@TextButton
                    scope.launch(Dispatchers.IO) {
                        AppGraph.from(context.applicationContext).geminiAi.setModel(modelInput)
                        withContext(Dispatchers.Main) { }
                    }
                }) { Text(stringResource(R.string.gem_model_save)) }
            }
        }
    }
}

/** رباعية حالة البطاقة — بلا صنف زائد خارج الملف */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
