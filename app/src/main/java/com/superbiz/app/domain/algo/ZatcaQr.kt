package com.superbiz.app.domain.algo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [P9-9a-ZATCA] فاتورة ZATCA الإلكترونية — المرحلة الأولى: رمز QR بترميز TLV ثم Base64.
 *
 * تنفيذ مواصفات هيئة الزكاة والضريبة والجمارك السعودية (ZATCA) للمرحلة الأولى:
 * رمز QR يحمل خمسة حقول مرقّمة بترميز Tag-Length-Value (وسم بايت واحد + طول بايت واحد + قيمة UTF-8)
 * تُدمج الحقول متتالية ثم تُرمَّز Base64 (RFC 4648 مع الحشو):
 *   1) اسم البائع
 *   2) الرقم الضريبي للبائع
 *   3) الطابع الزمني للفاتورة بصيغة ISO-8601 بتوقيت UTC (yyyy-MM-dd'T'HH:mm:ss'Z')
 *   4) إجمالي الفاتورة مع الضريبة (منسّقاً بخانتين عشريتين)
 *   5) إجمالي ضريبة القيمة المضافة (منسّقاً بخانتين عشريتين)
 *
 * المرحلة الثانية (XML + بصمة التوقيع + الشهادة) خارج نطاق هذا التنفيذ عمداً —
 * المرحلة الأولى تحمل الحقول النصية الخمسة فقط كما في المواصفة.
 *
 * الملف نقي بلا أي اعتماد Android — قابل للاختبار بـ JUnit المجرد (نمط NumText/TextMath).
 * Base64 مُنفَّذ داخلياً (RFC 4648) لتفادي kotlin.io.encoding التجريبي و java.util.Base64
 * غير المتاح قبل API 26 (minSdk 24).
 */
object ZatcaQr {

    /** جدول RFC 4648 القياسي */
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * مقطع TLV واحد: وسم بايت واحد (1..255) + طول بايت واحد (0..255 عدد بايتات القيمة) + القيمة.
     * @throws IllegalArgumentException إذا خرج الوسم أو الطول عن حدود البايت
     */
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        require(tag in 1..255) { "ZATCA TLV tag out of byte range: $tag" }
        require(value.size <= 255) { "ZATCA TLV value too long for 1-byte length: ${value.size}" }
        return ByteArray(2 + value.size).also {
            it[0] = tag.toByte()
            it[1] = value.size.toByte()
            value.copyInto(it, 2)
        }
    }

    /** الحقول الخمسة موحدة البايتات قبل ترميز Base64 — مكشوفة للاختبار الحرفي للمقاطع */
    fun qrPayloadBytes(
        sellerName: String,
        vatNumber: String,
        timestampMs: Long,
        invoiceTotal: Double,
        vatTotal: Double,
    ): ByteArray =
        tlv(1, sellerName.toByteArray(Charsets.UTF_8)) +
            tlv(2, vatNumber.toByteArray(Charsets.UTF_8)) +
            tlv(3, isoUtc(timestampMs).toByteArray(Charsets.US_ASCII)) +
            tlv(4, money2(invoiceTotal).toByteArray(Charsets.US_ASCII)) +
            tlv(5, money2(vatTotal).toByteArray(Charsets.US_ASCII))

    /** الحمولة النهائية لرمز QR: Base64 لسلسلة TLV الخمسة */
    fun qrPayload(
        sellerName: String,
        vatNumber: String,
        timestampMs: Long,
        invoiceTotal: Double,
        vatTotal: Double,
    ): String = toBase64(
        qrPayloadBytes(sellerName, vatNumber, timestampMs, invoiceTotal, vatTotal)
    )

    /** الطابع الزمني ISO-8601 بتوقيت UTC كما تشترط المواصفة */
    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    /** منسّق المبالغ: خانتان عشريتان بأرقام لاتينية ثابتة (توحيد مع عرف Money في المشروع) */
    private fun money2(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** ترميز Base64 قياسي بحشو «=» — تنفيذ محلي صغير لثباته عبر كل مستويات API
     *  [P15-a] كان private وأُفصح internal كي يعيد استخدامه ZatcaStamp (المرحلة-2)
     *  بدل نسخ المنفّذ — السلوك نفسه بايتاً ببايت ولا يغيّر شيئاً للمرحلة-1. */
    internal fun toBase64(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF) shl 16 or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(B64[(n shr 18) and 63])
                .append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63])
                .append(B64[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF) shl 16 or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(B64[(n shr 18) and 63])
                .append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63])
                .append('=')
        }
        return out.toString()
    }
}
