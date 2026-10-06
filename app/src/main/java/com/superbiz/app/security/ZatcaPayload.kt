package com.superbiz.app.security

import android.content.Context

/**
 * [P38-Z1] بنّاء حمولة QR الضريبي الموحَّد — نقطة حقيقة واحدة لكل مسارات الطباعة.
 *
 * حتى هذه الموجة كان منطق «المرحلة-1 افتراضياً، والترقية للمرحلة-2 عند التفعيل»
 * منسوخاً حصرياً داخل InvoicePdf.Engine.stampedQrPayload. مع وصول QR الضريبي
 * للإيصال الحراري [P38-Z2] صار مساران إنتاجيان يحتاجان العقد نفسه حرفياً —
 * والنسخ المزدوجة تنحرف بصمت عند أول تعديل. الصندوق هنا يوحِّدهما
 *
 * - الرقم الضريبي فارغ ⇒ null (منشأة غير مسجلة — لا QR إطلاقاً).
 * - المرحلة-1 افتراضياً: ZatcaQr.qrPayload بوسومه الخمسة (عقد بايتاً ببايت).
 * - المرحلة-2 فقط عند: ZatcaPrefs.enabled + مُوقِّع AndroidKeyStore متاح
 * + نقطة مفتاح عام — وإلا فمرحلة-1 بصمت (سلوك الفشل الصامت المجرَّب).
 * - عقد التوقيع الموحَّد (ZatcaStamp): التوقيع على بايتات XML القانوني نفسها،
 * ووسم 6 يحمل sha256Base64(bytes(xml)) — فالتحقق verify(sig, pub, bytes(xml))
 * يطابق الوسمين معاً.
 * - أي استثناء ⇒ حمولة المرحلة-1 المحسوبة أولاً — لا يُرمى شيء ولا تُسقط
 * فاتورة صالحة أبداً (قاعدة P9-9a).
 *
 * مدخلات المبالغ ريال Double (عقد ZatcaQr/ZatcaStamp — خارج نطاق ترحيل القروش)،
 * والمستدعون يمرّرون الحدود عبر Money.fromPiasters من لقطاتهم القروشية.
 * الكلاس يعتمد Context (ZatcaPrefs) وAndroidKeyStore (ZatcaKeys) فليس نقياً —
 * أما كل الحساب الرمزي فمحصور في ZatcaQr/ZatcaStamp النقيين المختبرين مجرداً.
*/
object ZatcaPayload {

    /**
     * حمولة QR الضريبي النهائية (Base64 لسلسلة TLV خمسة أو ثمانية وسوم).
     * حتمية لكل لقطة فاتورة واحدة: الطابع الزمني وحقول المبالغ من لقطة الفاتورة
     * نفسها لا من الزمن الحالي — ف QR المطبوع على PDF والإيصال الحراري لفاتورة
     * واحدة متطابقان بايتاً ببايت [P38-Z1 عقد التوحد].
     */
    fun qr(
        ctx: Context,
        businessName: String,
        vatNumber: String,
        timestampMs: Long,
        invoiceTotalRiyal: Double,
        vatTotalRiyal: Double,
        lineCount: Int,
        invoiceNumber: String,
    ): String? {
        if (vatNumber.isBlank()) return null
        val stage1 = com.superbiz.app.domain.algo.ZatcaQr.qrPayload(
            businessName, vatNumber, timestampMs, invoiceTotalRiyal, vatTotalRiyal
        )
        return try {
            if (!ZatcaPrefs.enabled(ctx)) return stage1
            val signer = ZatcaKeys.signer() ?: return stage1
            val pub = ZatcaKeys.publicKeyPoint() ?: return stage1
            val xml = com.superbiz.app.domain.algo.ZatcaStamp.canonicalXml(
                businessName, vatNumber, timestampMs,
                invoiceTotalRiyal, vatTotalRiyal, lineCount, invoiceNumber
            )
            val xmlBytes = xml.toByteArray(Charsets.UTF_8)
            val sig = signer.sign(xmlBytes)
            val stamped = com.superbiz.app.domain.algo.ZatcaStamp.stampedPayloadBytes(
                com.superbiz.app.domain.algo.ZatcaQr.qrPayloadBytes(
                    businessName, vatNumber, timestampMs, invoiceTotalRiyal, vatTotalRiyal
                ),
                com.superbiz.app.domain.algo.ZatcaStamp.sha256Base64(xmlBytes),
                sig, pub
            )
            com.superbiz.app.domain.algo.ZatcaQr.toBase64(stamped)
        } catch (_: Exception) {
            stage1 // توقيع فاشل/مفتاح طلب مصادقة لحظية — نُكمل بمرحلة-1 بصمت
        }
    }
}
