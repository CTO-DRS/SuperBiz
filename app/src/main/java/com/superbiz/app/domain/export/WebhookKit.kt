package com.superbiz.app.domain.export

import com.superbiz.app.domain.backup.escapeJson

/**
 * [H4-6][V 3.0.0] — حمولات الويب هوك النقية (ADR-001 D1: صيغة ثابتة قابلة للاختبار).
 * عقد الحمولة: JSON واحد مستقر النسخ — أي حقل مستقبلي يُضاف فقط (لا يُحذف ولا يُعاد
 * تسميته) كي لا تنكسر مستقبِلات أصحاب المتاجر.
 */
object WebhookKit {

    const val EVENT_INVOICE_CREATED = "invoice.created"
    const val EVENT_EXPENSE_CREATED = "expense.created"
    const val SIGNATURE_HEADER = "X-SuperBiz-Signature"

    /** حمولة فاتورة جديدة — المبالغ قروش (P33-P8) مع ختم الفئة الأصلية عند وجودها. */
    fun invoiceCreated(
        invoiceId: Long,
        invoiceNumber: String,
        totalPiasters: Long,
        taxPiasters: Long,
        partyName: String?,
        currencyCode: String,
        origCurrency: String?,
        origTotal: Long?,
        at: Long
    ): String {
        val sb = StringBuilder()
        sb.append("{\"event\":\"").append(EVENT_INVOICE_CREATED).append('"')
        sb.append(",\"sentAt\":").append(at)
        sb.append(",\"data\":{\"invoiceId\":").append(invoiceId)
        sb.append(",\"number\":\"").append(escapeJson(invoiceNumber)).append('"')
        sb.append(",\"totalPiasters\":").append(totalPiasters)
        sb.append(",\"taxPiasters\":").append(taxPiasters)
        sb.append(",\"party\":").append(if (partyName == null) "null" else "\"" + escapeJson(partyName) + "\"")
        sb.append(",\"currency\":\"").append(escapeJson(currencyCode)).append('"')
        if (origCurrency != null && origCurrency.isNotEmpty()) {
            sb.append(",\"origCurrency\":\"").append(escapeJson(origCurrency)).append('"')
            sb.append(",\"origTotalPiasters\":").append(origTotal ?: 0L)
        }
        sb.append("}}")
        return sb.toString()
    }

    /** توقيع تسليم اختياري: HMAC-SHA256 مقتطع (16 بايت hex) بمفتاح يختاره المالك. */
    fun sign(payload: String, secret: String): String {
        if (secret.isEmpty()) return ""
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val d = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(String.format("%02x", d[i]))
        return sb.toString()
    }
}
