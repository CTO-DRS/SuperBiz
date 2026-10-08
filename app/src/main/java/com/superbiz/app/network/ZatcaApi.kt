package com.superbiz.app.network

import com.superbiz.app.domain.ZatcaGateway
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Z2-ب V 1.5.0] خريطة واجهات منصة «فاتورة» الرسمية — الدوال النقية للطلب
 * والاستجابة: بناء جسم الطلب JSON وتفسير استجابة الهيئة — بلا أي نوع شبكي
 * فتُختبر مجردة على JVM (عقد D6: الحزمة الشبكية لا تولّد شيئاً، تنقل فقط).
 *
 * المواضع الرسمية (واجهة الإصدار V2):
 * - Clearance (تخليص القياسية): POST {base}/invoices/clearance/single
 * - Reporting (إبلاغ المبسطة): POST {base}/invoices/reporting/single
 * - جسم الطلب: invoice (Base64 لبايتات XML) + invoiceHash + uuid + previousInvoiceHash
 * - الاستجابة: clearanceStatus/reportingStatus (CLEARED|CLEARED_WITH_WARNINGS|
 *   REPORTED|REPORTED_WITH_WARNINGS) + clearedInvoice + warnings[] + errors[]
 *
 * دلالة النتائج (عقد ZatcaGateway):
 * - حالة قبول ⇒ Accepted مع النسخة المخلَّصة (تخليص) والتحذيرات نصاً
 * - errors غير فارغة بلا حالة قبول ⇒ Rejected (رفض معياري — لا يُعاد حرفياً)
 * - رموز HTTP للعطل (5xx/429/408) ⇒ TransientFailure مع تلميح Retry-After،
 *   وجسم غير متوقع ⇒ TransientFailure كذلك (لا رفض على تخمين) — الاستثناءات
 *   النقلية (مهلة/قطع) تعالجها طبقة النقل إلى الدلالة نفسها.
 */
object ZatcaApi {

    /** قاعدة العنوان لكل بيئة — نطاق منصة فاتورة حصراً (D4: عميل واحد مقيّد) */
    const val BASE_SIMULATION = "https://gw-fatura.zatca.gov.sa/e-invoicing/simulation"
    const val BASE_PRODUCTION = "https://gw-fatura.zatca.gov.sa/e-invoicing/producer"

    fun baseUrl(production: Boolean): String =
        if (production) BASE_PRODUCTION else BASE_SIMULATION

    fun clearanceUrl(production: Boolean): String = baseUrl(production) + "/invoices/clearance/single"
    fun reportingUrl(production: Boolean): String = baseUrl(production) + "/invoices/reporting/single"

    /** بناء جسم الطلب — الحقول الأربعة كما تشترطها الواجهة الرسمية */
    fun requestBody(
        invoiceXml: String,
        invoiceHash: String,
        uuid: String,
        pih: String,
    ): String = JSONObject().apply {
        put("invoice", ZatcaB64.encode(invoiceXml.toByteArray(Charsets.UTF_8)))
        put("invoiceHash", invoiceHash)
        put("uuid", uuid)
        put("previousInvoiceHash", pih)
    }.toString()

    /** تفسير استجابة الهيئة — كل المسارات مغلقة بلا استثناءات للخارج */
    fun parseResponse(
        body: String,
        httpStatus: Int,
        retryAfterHeader: String?,
    ): ZatcaGateway.GatewayResult {
        // أعطال الخادم/المعدل/المهلة = عابرة مهما كان الجسم
        if (httpStatus >= 500 || httpStatus == 429 || httpStatus == 408) {
            return ZatcaGateway.GatewayResult.TransientFailure(
                retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull(),
                message = "http $httpStatus",
            )
        }
        val json = try { JSONObject(body) } catch (_: Exception) {
            // جسم غير JSON — عطل غير متوقع: عابر (لا نرفض على تخمين)
            return ZatcaGateway.GatewayResult.TransientFailure(message = "non-JSON body (http $httpStatus)")
        }
        val status = json.optString("clearanceStatus", json.optString("reportingStatus", ""))
        val accepted = setOf("CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS")
        val errors = parseItems(json.optJSONArray("errors"))
        val warnings = parseItems(json.optJSONArray("warnings")).map { "${it.code}: ${it.message}" }

        return when {
            status in accepted -> ZatcaGateway.GatewayResult.Accepted(
                clearedXml = json.optString("clearedInvoice", "").takeIf { it.isNotBlank() }
                    ?.let { ZatcaB64.decodeToString(it) },
                warnings = warnings,
            )
            errors.isNotEmpty() -> ZatcaGateway.GatewayResult.Rejected(errors)
            httpStatus in 400..499 -> ZatcaGateway.GatewayResult.Rejected(
                listOf(
                    ZatcaGateway.GatewayError(
                        code = "HTTP$httpStatus", category = "CLIENT",
                        message = "rejected (http $httpStatus)", status = httpStatus,
                    )
                )
            )
            else -> ZatcaGateway.GatewayResult.TransientFailure(message = "unexpected body (http $httpStatus)")
        }
    }

    private fun parseItems(arr: JSONArray?): List<ZatcaGateway.GatewayError> {
        if (arr == null) return emptyList()
        val out = ArrayList<ZatcaGateway.GatewayError>(arr.length())
        for (i in 0 until arr.length()) {
            val o = try { arr.getJSONObject(i) } catch (_: Exception) { continue }
            out.add(
                ZatcaGateway.GatewayError(
                    code = o.optString("code", ""),
                    category = o.optString("type", ""),
                    message = o.optString("message", ""),
                    status = 0,
                )
            )
        }
        return out
    }
}

/**
 * [Z2-ب V 1.5.0] Base64 محلي (RFC 4648 بحشو «=») — ترميز وفك بتنفيذ واحد
 * بلا شرطيات إصدار: java.util.Base64 محظور قبل API 26 (minSdk 24 وبلا
 * desugaring — المرجع رأس ZatcaQr)، والفاحص في CI يمنع استيرادات خارج الحزمة
 * فلا إعادة استخدام ZatcaQr.internal من هنا. نقي وقابل للاختبار المجرد.
 */
object ZatcaB64 {

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF) shl 16 or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63]).append(B64[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF) shl 16 or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63]).append('=')
        }
        return out.toString()
    }

    /** فك تسامح: يحذف المحارف البيضاء والحشو ويهمل غير جدول RFC — تالف كلياً ⇒ فارغة */
    fun decodeToString(text: String): String {
        val clean = StringBuilder(text.length)
        for (raw in text) {
            if (raw == '\n' || raw == '\r' || raw == ' ' || raw == '\t' || raw == '=') continue
            clean.append(raw)
        }
        if (clean.isEmpty()) return ""
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (k in 0 until clean.length) {
            val v = B64.indexOf(clean[k])
            if (v < 0) return "" // محرف خارج الجدول ⇒ تالف كلياً (لا نصف ناتج)
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toString("UTF-8")
    }
}
