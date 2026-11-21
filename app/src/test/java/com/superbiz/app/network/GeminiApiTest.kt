package com.superbiz.app.network

import com.superbiz.app.domain.GeminiGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * [F2 - ADR-005 D4] اختبارات عقد Gemini API النقي — بناء الطلب وتحليل الرد
 * (نقل فعلي بلا شبكة؛ هذا ملف JVM صرف على نمط اختبارات واجهات ZATCA).
 *
 * العقد المختبر:
 * - نقطة النهاية تُبنى بالموديل ولا تحمل المفتاح أبدًا (المفتاح في الترويسة
 *   x-goog-api-key — لا مفتاح في URL قد يُسجَّل في سجلات الوسطاء).
 * - جسم الطلب يحمل نص OCR الخام بعد قصّه للحد الصلب + تعليمة إصلاح ثابتة +
 *   temperature=0 (أقل عشوائية ممكنة لمهمة تصحيح نصي).
 * - دلالة الرد ثلاثية مطابقة لعقد ZatcaGateway: Accepted/Rejected/TransientFailure.
 *   - الرفض = قرار نهائي لا يُعاد بمجرده (مفتاح/موديل/حجب سلامة) — رسالة السبب تعاد.
 *   - العابر = شبكة/خادم/حد معدل/رد مشوه — إعادة المحاولة مشروعة.
 * - فشل مغلق: أي رد غير مفهوم يبقى عابرًا بلا نص مالي زائف — لا استثناء يهرب أبدًا.
 */
class GeminiApiTest {

    // ── 1) نقطة النهاية: الموديل مدمج والمفتاح غائب عن URL ──

    @Test
    fun endpoint_embeds_model_and_never_carries_the_key() {
        val url = GeminiApi.endpoint("gemini-2.5-flash")
        assertTrue(url.startsWith("https://" + GeminiApi.HOST + "/"))
        assertTrue(url.contains("/v1beta/models/gemini-2.5-flash:generateContent"))
        assertFalse(url.contains("key="))
        assertFalse(url.contains("apikey"))
    }

    @Test
    fun endpoint_with_arbitrary_owner_model_is_injected_verbatim() {
        val url = GeminiApi.endpoint("gemini-flash-latest")
        assertTrue(url.contains("/v1beta/models/gemini-flash-latest:generateContent"))
    }

    // ── 2) جسم الطلب: بنية contents/parts + تعليمة + temperature=0 ──

    @Test
    fun request_body_carries_instruction_and_raw_text_with_zero_temperature() {
        val body = GeminiApi.requestBody("TOTAL 45.00\nRICE 3 x 15.00")
        val json = JSONObject(body)
        val text = json.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("TOTAL 45.00"))
        assertTrue(text.contains("RICE 3 x 15.00"))
        assertTrue(text.contains(GeminiApi.ENHANCE_INSTRUCTION))
        assertEquals(0.0, json.getJSONObject("generationConfig").getDouble("temperature"), 1e-9)
    }

    // ── 3) النص فوق الحد الصلب يُقص قبل الإرسال — لا بايت زائد يغادر ──

    @Test
    fun request_body_truncates_raw_text_to_hard_cap() {
        val raw = "أ".repeat(GeminiApi.MAX_PROMPT_CHARS + 500)
        val body = GeminiApi.requestBody(raw)
        val text = JSONObject(body).getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        // التعليمة + النص المقصوص فقط — لا شيء من ما بعد الحد
        assertTrue(text.endsWith(GeminiApi.ENHANCE_INSTRUCTION_SUFFIX + "أ".repeat(GeminiApi.MAX_PROMPT_CHARS)))
    }

    // ── 4) رد 200 بجزء واحد → Accepted بالنص ──

    @Test
    fun parse_200_single_part_is_accepted() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"مصحح\nالإجمالي: 45.00"}]}}]}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 200)
        assertTrue(r is GeminiGateway.GatewayResult.Accepted)
        assertEquals("مصحح\nالإجمالي: 45.00", (r as GeminiGateway.GatewayResult.Accepted).text)
    }

    // ── 5) أجزاء متعددة تُلتحم بالترتيب ──

    @Test
    fun parse_200_multiple_parts_are_joined_in_order() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"أ"},{"text":"ب"},{"text":"ج"}]}}]}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 200)
        assertTrue(r is GeminiGateway.GatewayResult.Accepted)
        assertEquals("أبج", (r as GeminiGateway.GatewayResult.Accepted).text)
    }

    // ── 6) حجب السلامة (promptFeedback.blockReason) → رفض نهائي بالسبب ──

    @Test
    fun parse_200_with_block_reason_is_rejected_with_reason() {
        val body = """
            {"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 200)
        assertTrue(r is GeminiGateway.GatewayResult.Rejected)
        assertTrue((r as GeminiGateway.GatewayResult.Rejected).reason.contains("SAFETY"))
    }

    // ── 7) رد 200 بلا مرشحين ولا نص — عابر بلا نص مالي زائف ──

    @Test
    fun parse_200_empty_candidates_is_transient_not_fake_text() {
        val body = """
            {"candidates":[]}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 200)
        assertTrue(r is GeminiGateway.GatewayResult.TransientFailure)
    }

    @Test
    fun parse_200_blank_part_text_is_transient() {
        val body = """
            {"candidates":[{"content":{"parts":[{"text":"  "}]},"finishReason":"STOP"}]}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 200)
        assertTrue(r is GeminiGateway.GatewayResult.TransientFailure)
    }

    // ── 8) رد 200 مشوه — عابر، لا استثناء يهرب ──

    @Test
    fun parse_200_malformed_body_is_transient() {
        val r = GeminiApi.parseResponse("{not json at all", 200)
        assertTrue(r is GeminiGateway.GatewayResult.TransientFailure)
    }

    // ── 9) خطأ 400 مع رسالة السبب → رفض نهائي بالرسالة ──

    @Test
    fun parse_400_with_error_message_is_rejected() {
        val body = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}
        """.trimIndent()
        val r = GeminiApi.parseResponse(body, 400)
        assertTrue(r is GeminiGateway.GatewayResult.Rejected)
        assertTrue((r as GeminiGateway.GatewayResult.Rejected).reason.contains("API key not valid"))
    }

    // ── 10) حد المعدل 429 → عابر (إعادة المحاولة مشروعة) ──

    @Test
    fun parse_429_is_transient_failure() {
        val body = """{"error":{"code":429,"message":"Resource has been exhausted"}}"""
        val r = GeminiApi.parseResponse(body, 429)
        assertTrue(r is GeminiGateway.GatewayResult.TransientFailure)
    }

    // ── 11) خلل خادم 5xx → عابر ──

    @Test
    fun parse_5xx_is_transient_failure() {
        val r = GeminiApi.parseResponse("""{"error":{"code":500,"message":"internal"}}""", 500)
        assertTrue(r is GeminiGateway.GatewayResult.TransientFailure)
    }

    // ── 12) 404 (موديل غير موجود) → رفض نهائي — إعادة المحاولة بلا فائدة ──

    @Test
    fun parse_404_unknown_model_is_rejected() {
        val body = """{"error":{"code":404,"message":"models/xyz is not found"}}"""
        val r = GeminiApi.parseResponse(body, 404)
        assertTrue(r is GeminiGateway.GatewayResult.Rejected)
        assertTrue((r as GeminiGateway.GatewayResult.Rejected).reason.contains("not found"))
    }
}
