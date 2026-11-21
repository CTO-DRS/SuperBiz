package com.superbiz.app.network

import com.superbiz.app.data.repo.GeminiAiStore
import com.superbiz.app.domain.GeminiGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * [F2 - ADR-005 D4] عميل المحسّر السحابي — التنفيذ الوحيد لـ[GeminiGateway]
 * داخل حزمة `network` المعزولة (D1 من ADR-001)، على نمط ZatcaFatooraGateway حرفيًا.
 *
 * ─── بنود ADR-001/D4 المنفَّذة هنا حرفيًا ───
 * - **البوابة المزدوجة قبل أي socket**: لا اتصال واحد قبل (أ) تفعيل صريح في
 *   [GeminiAiStore] و(ب) توفر مفتاح API — ناقص أيهما ⇒ TransientFailure مغلق
 *   بلا أي محاولة اتصال وبلا رسالة سحابية زائفة.
 * - **عميل واحد مقيَّد**: HttpURLConnection من المنصة — صفر تبعيات شبكية جديدة
 *   (نمط ZatcaFatooraGateway حرفيًا)؛ مهلات صريحة (اتصال 10ث/قراءة 30ث) ومضيف
 *   واحد حصرًا (GeminiApi.HOST) والمفتاح في الترويسة لا في URL.
 * - **ما يُرسل محصور**: جسم GeminiApi.requestBody — تعليمة ثابتة + نص OCR خام
 *   مقصوص للحد الصلب — لا شيء آخر إطلاقًا.
 * - **لا خلفية ولا قائمة انتظار**: هذا العميل يُستدعى يدويًا حصرًا بعد قراءة
 *   المالك وتأكيده في الواجهة (D4 — لا إرسال صامت لبيانات مالية أبدًا).
 *
 * المخرج النصي لا يُعتمد ماليًا أبدًا — يعود للمحلل النقي المحلي
 * OcrInvoiceParser لإعادة الحساب الكنوني بقروش Long (عقد D2).
 */
class GeminiOcrClient(
    private val store: GeminiAiStore,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : GeminiGateway {

    override suspend fun enhance(request: GeminiGateway.EnhanceRequest): GeminiGateway.GatewayResult =
        withContext(Dispatchers.IO) {
            // D4: البوابة المزدوجة — قبل أي socket بلا استثناء
            if (!store.enabledOnce()) {
                return@withContext GeminiGateway.GatewayResult.TransientFailure("cloud enhance disabled (gate)")
            }
            val key = store.keyOnce()
            if (key.isBlank()) {
                return@withContext GeminiGateway.GatewayResult.TransientFailure("API key missing (not configured)")
            }
            if (request.rawOcrText.isBlank()) {
                return@withContext GeminiGateway.GatewayResult.TransientFailure("empty OCR text")
            }
            try {
                val conn = (URL(GeminiApi.endpoint(store.modelOnce())).openConnection()
                        as javax.net.ssl.HttpsURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = this@GeminiOcrClient.connectTimeoutMs
                    readTimeout = this@GeminiOcrClient.readTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    // المفتاح في الترويسة حصرًا — لا مفتاح في URL (قد يُسجَّل في سجلات الوسطاء)
                    setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use {
                    it.write(GeminiApi.requestBody(request.rawOcrText).toByteArray(Charsets.UTF_8))
                }
                val status = conn.responseCode
                val responseBody = try {
                    (if (status in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } finally {
                    conn.disconnect()
                }
                GeminiApi.parseResponse(responseBody, status)
            } catch (e: Exception) {
                // عجز نقل (مهلة/قطع/DNS/TLS) — عابر دائمًا، إعادة المحاولة مشروعة
                GeminiGateway.GatewayResult.TransientFailure(message = e.message ?: "transport failure")
            }
        }
}
