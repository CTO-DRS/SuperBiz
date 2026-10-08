package com.superbiz.app

import com.superbiz.app.domain.ZatcaGateway
import com.superbiz.app.network.ZatcaApi
import com.superbiz.app.network.ZatcaB64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [Z2-ب V 1.5.0] — خريطة واجهات منصة فاتورة (ZatcaApi) — JVM مجرد
 * مع org.json الحقيقي (تبعية اختبارية): بناء جسم الطلب بالحقول الأربعة،
 * تفسير كل مسارات الاستجابة (قبول/تحذيرات/رفض معياري/عابر/غير متوقع)،
 * URLs البيئتين، وBase64 المحلي ذهاباً وإياباً.
 */
class ZatcaApiTest {

    // ───────── 1) URLs ─────────

    @Test
    fun urls_environmentBases_andPaths() {
        assertTrue(ZatcaApi.BASE_SIMULATION.contains("simulation"))
        assertTrue(ZatcaApi.BASE_PRODUCTION.contains("producer"))
        assertEquals(
            ZatcaApi.BASE_SIMULATION + "/invoices/clearance/single",
            ZatcaApi.clearanceUrl(production = false),
        )
        assertEquals(
            ZatcaApi.BASE_PRODUCTION + "/invoices/reporting/single",
            ZatcaApi.reportingUrl(production = true),
        )
    }

    // ───────── 2) جسم الطلب ─────────

    @Test
    fun requestBody_fourOfficialFields_xmlEncodedBase64() {
        val xml = "<Invoice><x>ع</x></Invoice>"
        val body = JSONObject(ZatcaApi.requestBody(xml, "HASH", "UUID-1", "PIH-0"))
        assertEquals(4, body.length())
        assertEquals(xml, ZatcaB64.decodeToString(body.getString("invoice")))
        assertEquals("HASH", body.getString("invoiceHash"))
        assertEquals("UUID-1", body.getString("uuid"))
        assertEquals("PIH-0", body.getString("previousInvoiceHash"))
    }

    // ───────── 3) قبول تخليص: مع نسخة مخلَّصة وتحذيرات ─────────

    @Test
    fun parse_clearanceAccepted_withClearedInvoiceAndWarnings() {
        val cleared = "<Invoice>CLEARED</Invoice>"
        val body = JSONObject()
            .put("clearanceStatus", "CLEARED_WITH_WARNINGS")
            .put("clearedInvoice", ZatcaB64.encode(cleared.toByteArray(Charsets.UTF_8)))
            .put(
                "warnings", org.json.JSONArray()
                    .put(JSONObject().put("code", "W1").put("type", "WARNING").put("message", "تحذير تجريبي"))
            )
            .toString()
        val r = ZatcaApi.parseResponse(body, 200, null)
        val accepted = r as ZatcaGateway.GatewayResult.Accepted
        assertEquals(cleared, accepted.clearedXml)
        assertEquals(listOf("W1: تحذير تجريبي"), accepted.warnings)
    }

    @Test
    fun parse_reportingAccepted_withoutClearedInvoice() {
        val body = """{"reportingStatus":"REPORTED","warnings":[]}"""
        val r = ZatcaApi.parseResponse(body, 200, null)
        assertTrue(r is ZatcaGateway.GatewayResult.Accepted)
        assertNull((r as ZatcaGateway.GatewayResult.Accepted).clearedXml)
    }

    // ───────── 4) رفض معياري ─────────

    @Test
    fun parse_errorsWithoutStatus_isRejected() {
        val body = JSONObject()
            .put(
                "errors", org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("code", "2015")
                            .put("type", "VALIDATION")
                            .put("message", "invoice hash mismatch")
                    )
            )
            .toString()
        val r = ZatcaApi.parseResponse(body, 400, null)
        val rejected = r as ZatcaGateway.GatewayResult.Rejected
        assertEquals("2015", rejected.errors.single().code)
        assertEquals("VALIDATION", rejected.errors.single().category)
        assertEquals(0, rejected.errors.single().status)
    }

    @Test
    fun parse_client4xxWithoutErrors_fallsBackToRejected() {
        val r = ZatcaApi.parseResponse("""{"unexpected":true}""", 401, null)
        val rejected = r as ZatcaGateway.GatewayResult.Rejected
        assertEquals("HTTP401", rejected.errors.single().code)
    }

    // ───────── 5) عابر ─────────

    @Test
    fun parse_server5xx_isTransient_withRetryAfterHint() {
        val r = ZatcaApi.parseResponse("boom", 503, "120")
        val t = r as ZatcaGateway.GatewayResult.TransientFailure
        assertEquals(120L, t.retryAfterSeconds)
    }

    @Test
    fun parse_rateLimit429_isTransient() {
        assertTrue(ZatcaApi.parseResponse("{}", 429, null) is ZatcaGateway.GatewayResult.TransientFailure)
    }

    @Test
    fun parse_nonJsonOn2xx_isTransient_notRejected() {
        // لا نرفض على تخمين — جسم غير JSON عابر
        assertTrue(ZatcaApi.parseResponse("<html/>", 200, null) is ZatcaGateway.GatewayResult.TransientFailure)
    }

    @Test
    fun parse_unexpectedJsonOn2xx_isTransient() {
        assertTrue(ZatcaApi.parseResponse("""{"weird":1}""", 200, null) is ZatcaGateway.GatewayResult.TransientFailure)
    }

    @Test
    fun parse_emptyErrorsArray_notRejected() {
        // errors فارغة بلا حالة ⇒ غير متوقع (عابر) لا رفض مصمت
        val body = """{"errors":[]}"""
        assertTrue(ZatcaApi.parseResponse(body, 200, null) is ZatcaGateway.GatewayResult.TransientFailure)
    }

    // ───────── 6) Base64 المحلي ─────────

    @Test
    fun zatcaB64_roundtrip_textAndBinaryVectors() {
        // نصوص UTF-8 (الذهاب-والإياب عبر decodeToString دلالته UTF-8)
        for (n in 0..70) {
            val text = (0 until n).map { ('a' + (it % 26)) }.joinToString("")
            val enc = ZatcaB64.encode(text.toByteArray(Charsets.UTF_8))
            assertEquals("roundtrip n=$n", text, ZatcaB64.decodeToString(enc))
        }
        // العربية تمر UTF-8
        val s = "شركة النور &amp; أولادها"
        assertEquals(s, ZatcaB64.decodeToString(ZatcaB64.encode(s.toByteArray(Charsets.UTF_8))))
        // متجه ثنائي محسوب مستقلاً (python) — البايتات ≥0x80 لا تمر عبر String
        val binary = byteArrayOf(0x83.toByte(), 0x01, 0xFF.toByte(), 0x00, 0x7F, 0x80.toByte())
        assertEquals("gwH/AH+A", ZatcaB64.encode(binary))
        // تسامح المحارف البيضاء (أسطر مفصولة)
        val enc = ZatcaB64.encode("hello world".toByteArray(Charsets.UTF_8))
        assertEquals("hello world", ZatcaB64.decodeToString(enc.chunked(4).joinToString("\n")))
        // تالف كلياً ⇒ فارغة (لا نصف ناتج)
        assertEquals("", ZatcaB64.decodeToString("!!!!"))
    }
}
