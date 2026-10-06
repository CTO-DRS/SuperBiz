package com.superbiz.app.domain.algo

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [P15-a] ZATCA المرحلة الثانية — البصمة الرقمية الكاملة لرمز QR الضريبي.
 *
 * فوق حقول المرحلة الأولى (الوسوم 1-5 من [ZatcaQr]) تُضاف ثلاثة وسوم:
 *   6) هاش SHA-256 لـ XML القانوني (القانوني = canonical) للفاتورة، بترميز Base64
 *   7) توقيع ECDSA — على بايتات XML القانوني نفسها (منفّذ التوقيع SHA256withECDSA
 *      يهشّم داخلياً قبل التوقيع)، ووسم 6 يحمّل sha256Base64(bytes(xml)) نفسها —
 *      فالتحقق verify(data = bytes(xml), sig, pub) يطابق الوسمين معاً (قاعدة موحّدة).
 *   8) المفتاح العام — نقطة SEC1 غير مضغوطة 65 بايت (0x04||X||Y لمنحنى P-256)
 *
 * ─── إقرار صادق ───
 * هذا التنفيذ يُنتج QR ذا بنية مرحلة-2 مكتملة الشكل، وسلسلة توقيعه قابلة للتحقق
 * محلياً بالكامل (دالة verify أدناه تعمل على JVM وعلى الجهاز بلا أي اعتماد خارجي).
 * أما التوثيق الرسمي لدى ZATCA (الإصدار/الشهادة/CSID من منصة «فاتورة» Fatoora
 * والربط عبر API الرسمي) فهو يتطلب تكاملاً مع أنظمة الهيئة نفسها — خارج نطاق
 * تطبيق يعمل دون خادم. الالتزام المنفَّذ هنا هو مواصفة ترميز TLV/Base64 ذاتها
 * التي تشترطها المرحلة الثانية، وبنيتها مطابقة: Base64 لسلسلة TLV ثمانية الحقول.
 *
 * الملف نقي بلا أي اعتماد Android — قابل للاختبار بـ JUnit المجرد (نمط ZatcaQr)،
 * وMessageDigest متاح منذ API 24 (java.util.Base64 محظور قبل API 26 — نستخدم
 * ZatcaQr.toBase64 المحلي بعد إفصاحه internal في [P15-a]).
 */
object ZatcaStamp {

    /**
     * تجريد التوقيع: على JVM الاختبارات يستخدم زوج مفاتيح برمجي، وعلى الجهاز
     * يوفر ZatcaKeys تنفيذاً فوق AndroidKeyStore (توقيع داخل البيئة الآمنة).
     * العقد: sign تستقبل البيانات الخام وتعيد توقيع DER لـ ECDSA بعد تهشيمها
     * داخلياً (عقد SHA256withECDSA القياسي).
     */
    fun interface Signer { fun sign(data: ByteArray): ByteArray }

    /** مزوّد المفتاح العام: 65 بايت نقطة غير مضغوطة (0x04||X||Y) أو لا شيء عند الفشل */
    fun interface PublicKeyProvider { fun publicKey(): ByteArray }

    // ═════════ الهاش ═════════

    /** SHA-256 ثم Base64 (RFC 4648 بحشو «=») — لوسم 6 ومقارنات التحقق */
    fun sha256Base64(data: ByteArray): String =
        ZatcaQr.toBase64(MessageDigest.getInstance("SHA-256").digest(data))

    // ═════════ XML القانوني ═════════

    /**
     * XML قانوني مبسّط (شكل UBL مختصر) — بناء حتمي بالكامل:
     * ترتيب الحقول ثابت، المبالغ بخانتين عشريتين (عرف money2 في ZatcaQr)،
     * الطابع الزمني ISO-8601 UTC (نفس صيغة وسم 3 في المرحلة-1)، وبلا أي
     * فراغات بين العناصر كي يبقى البايت-ب-بايت متطابقاً للمدخلات نفسها.
     * المدخلات قيم scalar مجرّدة (لا تعتمد كيان Invoice) — استخراجها مسؤولية
     * مستدعيها من اللقطة ذاتها التي تُرسم بها الفاتورة.
     */
    fun canonicalXml(
        sellerName: String,
        vatNumber: String,
        timestampMs: Long,
        invoiceTotal: Double,
        vatTotal: Double,
        lineNumberCount: Int,
        invoiceNumber: String,
    ): String = "<Invoice>" +
        "<InvoiceNumber>" + xmlEscape(invoiceNumber) + "</InvoiceNumber>" +
        "<SellerName>" + xmlEscape(sellerName) + "</SellerName>" +
        "<VATNumber>" + xmlEscape(vatNumber) + "</VATNumber>" +
        "<TimeStamp>" + isoUtc(timestampMs) + "</TimeStamp>" +
        "<InvoiceTotal>" + money2(invoiceTotal) + "</InvoiceTotal>" +
        "<VATTotal>" + money2(vatTotal) + "</VATTotal>" +
        "<LineCount>" + lineNumberCount + "</LineCount>" +
        "</Invoice>"

    /**
     * تهريب XML للكيانات الخمسة — «&» أولاً حتماً كي لا تُهرب تهريباً مزدوجاً؛
     * الحروف العربية وأي محارف خارج ASCII تمر كما هي (UTF-8 يكفي).
     */
    fun xmlEscape(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** الطابع الزمني ISO-8601 UTC — يطابق حرفياً صيغة isoUtc في ZatcaQr لوسم 3
     *  (مكرر هنا عمداً: ZatcaQr.isoUtc private ونطاق موجة [P15-a] يسمح بإفصاح
     *  toBase64 فقط، والمطابقة بين وسم 3 و«TimeStamp» مضمونة باختبار مقارن) */
    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    /** منسّق المبالغ بخانتين عشريتين — نفس عرف ZatcaQr.money2 حرفياً */
    private fun money2(v: Double): String = String.format(Locale.US, "%.2f", v)

    // ═════════ تركيب الحمولة ═════════

    /**
     * الحمولة الكاملة للمرحلة-2 قبل ترميز Base64:
     * p1to5 (ناتج ZatcaQr.qrPayloadBytes كما هو) + وسم 6 (الهاش نصّ Base64
     * ببايتات ASCII) + وسم 7 (التوقيع) + وسم 8 (نقطة المفتاح العام).
     * حدود الوسم/الطول (1..255 / 0..255 بايت) مفروضة من ZatcaQr.tlv نفسه —
     * اختبارات حدّ الوسم والطول موجودة في ZatcaQrTest ولا تُكرَّر هنا.
     */
    fun stampedPayloadBytes(
        p1to5: ByteArray,
        invoiceXmlHash: String,
        signature: ByteArray,
        publicKey: ByteArray,
    ): ByteArray =
        p1to5 +
            ZatcaQr.tlv(6, invoiceXmlHash.toByteArray(Charsets.US_ASCII)) +
            ZatcaQr.tlv(7, signature) +
            ZatcaQr.tlv(8, publicKey)

    // ═════════ التحقق المحلي ═════════

    /**
     * تحقق ECDSA قياسي (SHA256withECDSA) — يعمل على JVM وعلى الجهاز بمزوّد
     * النظام: يبني PublicKey من نقطة 65 بايت عبر غلاف SubjectPublicKeyInfo
     * يدوي (بلا BouncyCastle) ثم يفحص التوقيع على البيانات الخام.
     * أي فشل (نقطة مشوّهة، منحنى غير P-256، توقيع تالف) ⇒ false بلا استثناءات.
     */
    fun verify(sig: ByteArray, pubPoint: ByteArray, data: ByteArray): Boolean = try {
        val pub = p256PublicKeyFromPoint(pubPoint) ?: return false
        val v = Signature.getInstance("SHA256withECDSA")
        v.initVerify(pub)
        v.update(data)
        v.verify(sig)
    } catch (_: Exception) {
        false
    }

    /**
     * بناء SubjectPublicKeyInfo (X.509) يدوياً من نقطة SEC1 غير مضغوطة —
     * SPKI = SEQUENCE { AlgId, BIT STRING { 0x00-unused-bits || point } }،
     * ومعرّفا OID الثابتان لـ ecPublicKey (1.2.840.10045.2.1) وprime256v1
     * (1.2.840.10045.3.1.7). الأطوال هنا قصيرة (‏< 128) فتكفي الصيغة القصيرة
     * لطول DER. نقطة غير 65 بايت أو بلا بادئة 0x04 ⇒ null (منحنى آخر/بيانات تالفة).
     */
    private fun p256PublicKeyFromPoint(point: ByteArray): PublicKey? {
        if (point.size != 65 || point[0] != 0x04.toByte()) return null
        val bitString = ByteArray(1 + point.size)
        bitString[0] = 0x00 // «unused bits» في BIT STRING
        point.copyInto(bitString, 1)
        val algId = byteArrayOf(
            0x30, 0x13,                                                    // SEQUENCE (21 بايت محتوى)
            0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01, // OID ecPublicKey
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07 // OID prime256v1
        )
        val spki = derTlv(0x30, algId + derTlv(0x03, bitString))
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
    }

    /** TLV لـ DER بصيغة الطول القصيرة — كل أطوالنا هنا < 128 بايت */
    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        require(content.size <= 127) { "DER long length not needed/supported here" }
        return byteArrayOf(tag.toByte(), content.size.toByte()) + content
    }
}
