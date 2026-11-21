package com.superbiz.app.network

import com.superbiz.app.domain.GeminiGateway
import org.json.JSONArray
import org.json.JSONObject

/**
 * [F2 - ADR-005 D4] خريطة Gemini API النقية — بناء الطلب وتحليل الرد
 * (على نمط ZatcaApi: كل القراءات والتأليفات هنا بلا socket ولا أندرويد —
 * ملف JVM صرف يُختبر وحدويًا في GeminiApiTest).
 *
 * ─── العقد ───
 * - نقطة النهاية: POST https://{HOST}/v1beta/models/{model}:generateContent —
 *   **المفتاح لا يدخل URL أبدًا** (الترويسة x-goog-api-key في GeminiOcrClient):
   * لا مفتاح في URL قد يُسجَّل في سجلات الوسطاء أو يظهر في الأخطاء.
 * - جسم الطلب: contents[0].parts[0].text = تعليمة إصلاح ثابتة + نص OCR الخام
 *   المقصوص للحد الصلب [MAX_PROMPT_CHARS] — وgenerationConfig.temperature = 0
 *   (أقل عشوائية ممكنة لمهمة تصحيح نصي؛ لا أطوار إبداعية هنا).
 * - تحليل الرد بدلالة ثلاثية مطابقة لعقد ZatcaGateway (Accepted/Rejected/Transient)
 *   وفشل مغلق: أي رد غير مفهوم عابر بلا نص مالي زائف، ولا استثناء يهرب أبدًا.
 */
object GeminiApi {

    /** مضيف API واحد مقيَّد — لا مضيف آخر يلمسه العميل أبدًا (D4 عميل واحد) */
    const val HOST = "generativelanguage.googleapis.com"

    /**
     * الحد الصلب لطول نص OCR المرسل — فوقه يُقص قبل البناء (ما يخرج من الجهاز
     * محصور حصرًا بهذا الحد؛ نافذة سياق الموديل أكبر من حاجة الإيصالات بكثير).
     */
    const val MAX_PROMPT_CHARS = 12_000

    /** تعليمة الإصلاح الثابتة — إنجليزية (استقرار الالتزام بنموذج الموديل) وبلا وسائط متغيرة */
    const val ENHANCE_INSTRUCTION =
        "You are an OCR correction engine for paper receipts and invoices. " +
            "Fix obvious OCR recognition mistakes only: misread characters, digits and spacing. " +
            "Keep the exact same line structure: never add, remove, reorder or merge lines. " +
            "Never invent amounts; correct a digit only when it is clearly misrecognized. " +
            "Return ONLY the corrected receipt text as plain text: no commentary, no markdown, no quotes."

    /** الفاصل بين التعليمة والنص الخام داخل نص الجزء الواحد */
    const val ENHANCE_INSTRUCTION_SUFFIX = "\n\n--- OCR RECEIPT TEXT ---\n"

    /** نقطة النهاية — الموديل يُمرَّر كما هو، والمفتاح غائب عن URL حرفيًا (يذهب للترويسة) */
    fun endpoint(model: String): String {
        val m = model.trim().ifBlank { com.superbiz.app.domain.GeminiGateway.MODEL_DEFAULT }
        return "https://$HOST/v1beta/models/$m:generateContent"
    }

    /** جسم الطلب — تعليمة ثابتة + النص المقصوص، temperature=0 */
    fun requestBody(rawOcrText: String): String {
        val text = ENHANCE_INSTRUCTION +
            ENHANCE_INSTRUCTION_SUFFIX +
            rawOcrText.take(MAX_PROMPT_CHARS)
        return JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", text))
                    )
                )
            )
            put("generationConfig", JSONObject().put("temperature", 0))
        }.toString()
    }

    /**
     * تحليل الرد بالدلالة الثلاثية — لا استثناءات تهرَّب، كل شذوذ يُصنَّف:
     * - 5xx / 429 ⇒ عابر (خادم أو حد معدل — إعادة مشروعة).
     * - promptFeedback.blockReason ⇒ رفض نهائي بالسبب (حجب سلامة).
     * - error.message مع 4xx ⇒ رفض نهائي (مفتاح/موديل/طلب) — السبب حرفي.
     * - 200 بلا مرشحين أو بنص فارغ أو جسم مشوه ⇒ عابر — لا نص مالي زائف أبدًا.
     */
    fun parseResponse(body: String, status: Int): GeminiGateway.GatewayResult {
        if (status in 500..599) return GeminiGateway.GatewayResult.TransientFailure("server error ($status)")
        if (status == 429) return GeminiGateway.GatewayResult.TransientFailure("rate limited (429)")

        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return GeminiGateway.GatewayResult.TransientFailure("malformed response")
        }

        val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
        if (blockReason.isNotBlank()) return GeminiGateway.GatewayResult.Rejected(blockReason)

        val err = json.optJSONObject("error")
        if (err != null) {
            val msg = err.optString("message", "").ifBlank { "error ($status)" }
            return if (status in 400..499) GeminiGateway.GatewayResult.Rejected(msg)
            else GeminiGateway.GatewayResult.TransientFailure(msg)
        }

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return GeminiGateway.GatewayResult.TransientFailure("empty response")
        }
        val sb = StringBuilder()
        for (c in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(c)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (p in 0 until parts.length()) {
                sb.append(parts.optJSONObject(p)?.optString("text", "") ?: "")
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return GeminiGateway.GatewayResult.TransientFailure("empty response")
        return GeminiGateway.GatewayResult.Accepted(text)
    }
}
