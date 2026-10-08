package com.superbiz.app.domain.algo

/**
 * [Z2-أ V 1.5.0] سلسلة سلامة فواتير ZATCA مرحلة-2: عدّاد ICV التزايدي +
 * بصمة الفاتورة السابقة PIH — نقي تماماً بلا أي اعتماد Android.
 *
 * ─── عقد السلسلة حسب مواصفة منصة «فاتورة» ───
 * - **ICV** (Invoice Counter Value): عدّاد صحيح متزايد على مستوى الجهاز،
 *   يبدأ 1 ولا يقفز ولا يُعاد ترقيمه، وكل فاتورة (قياسية/مبسطة/دائنة/مدينة)
 *   تأخذ التالي — فالإلغاء الدائن لا يعيد السلسلة بل «يقفز إلى آخر هاش»
 *   (يأخذ ICV التالي وPIH من آخر وثيقة صدرت).
 * - **PIH** (Previous Invoice Hash): Base64(SHA256(بايتات XML الفاتورة السابقة))؛
 *   وأول فاتورة في السلسلة بصمة سابقتها الثابتة الرسمية: Base64(SHA256("0"))
 *   (قيمة "0" نصاً ببايتات UTF-8/ASCII واحدة — 0x30).
 *
 * ─── عقد التكامل مع قاعدة البيانات (موثق لا مُرتجل) ───
 * الحالة المعيشة (lastIcv/lastHash) تُقرأ من صفوف الفواتير المختومة وجدول
 * أرشيف المستندات داخل معاملة الحفظ نفسها، وهذه الوحدة النقية تعرف الحالة
 * كقيمة مررة فقط — فاختبار السلسلة الكاملة (100 وثيقة مع 3 دائنة) يعمل
 * مجرداً على JVM بلا قاعدة.
 *
 * - [ChainState.lastHash] = Base64 هاش XML آخر وثيقة **أُصدِرت** (لا آخر مُبلَّغَة —
 *   السلسلة تتبع الإصدار لا الإبلاغ؛ الإلغاء والتقارير لا تعيد كتابتها).
 * - [verifyChain] يتحقق من كل حلقة: ICV تتابع 1..n، وPIH لكل وثيقة = هاش وثيقتها
 *   السابقة بالترتيب (وأولها الثابت الرسمي)، بالإضافة إلى تحقق التوقيع
 *   ECDSA المحلي ZatcaStamp.verify لكل وثيقة متى وُفرت مفاتيحها — أي انكسار
 *   يوقف التحقق ويعيد رقم الحلقة وسببه (لا استثناءات للخارج).
 */
object ZatcaChain {

    /** بذرة أول فاتورة الرسمية — قيمة "0" نصاً (مواصفة منصة فاتورة) */
    const val FIRST_INVOICE_HASH_SEED = "0"

    /** Base64(SHA256("0")) — PIH الثابت لأول وثيقة في السلسلة على الجهاز */
    fun firstPih(): String =
        ZatcaStamp.sha256Base64(FIRST_INVOICE_HASH_SEED.toByteArray(Charsets.UTF_8))

    /** حالة السلسلة المحلية — تُبنى من قاعدة البيانات داخل معاملة الإصدار */
    data class ChainState(
        val lastIcv: Long,      // أعلى ICV صادر (0 = لا وثائق مختومة بعد)
        val lastHash: String?,  // Base64 هاش XML آخر وثيقة (null = لا وثائق بعد)
    )

    val EMPTY: ChainState = ChainState(0L, null)

    /** الختم التالي: ICV التسلسلي + PIH من آخر هاش أو الثابت الرسمي لأول وثيقة */
    data class NextStamp(val icv: Long, val pih: String)

    fun nextStamp(state: ChainState): NextStamp =
        NextStamp(
            icv = state.lastIcv + 1L,
            pih = state.lastHash ?: firstPih(),
        )

    /** هاش وثيقة XML بايتاتها — القيمة التي تُخزَّن لتكون PIH الوثيقة التالية */
    fun documentHash(xmlBytes: ByteArray): String = ZatcaStamp.sha256Base64(xmlBytes)

    // ───────── تحقق سلسلة كاملة ─────────

    /** وثيقة واحدة في سجل التحقق */
    data class ChainEntry(
        val xmlBytes: ByteArray,
        val icv: Long,
        val pih: String,                 // PIH المخزَّن مع الوثيقة نفسها
        val signature: ByteArray? = null, // توقيع ECDSA على بايتات XML (وسم 7) — اختياري
        val publicKey: ByteArray? = null, // نقطة المفتاح العام (وسم 8) — اختياري
    )

    data class ChainVerifyResult(
        val ok: Boolean,
        val firstFailureIndex: Int? = null, // 0-based عند الفشل
        val reason: String? = null,
    )

    /**
     * تحقق كامل بترتيب الإصدار: ICV يبدأ 1 ويتتابع بلا قفزات، وPIH كل وثيقة
     * = هاش وثيقتها السابقة (وأولها الثابت الرسمي)، وتوقيع كل وثيقة متوفر
     * المفتاح يتحقق على بايتات XML نفسها. أي فشل ⇒ نتيجة بالمؤشر والسبب.
     */
    fun verifyChain(entries: List<ChainEntry>): ChainVerifyResult {
        var expectedPih = firstPih()
        entries.forEachIndexed { i, e ->
            if (e.icv != (i + 1).toLong()) {
                return ChainVerifyResult(false, i, "ICV break: expected ${i + 1} found ${e.icv}")
            }
            if (e.pih != expectedPih) {
                return ChainVerifyResult(false, i, "PIH break at entry ${i + 1}")
            }
            val sig = e.signature
            val pub = e.publicKey
            if (sig != null && pub != null) {
                if (!ZatcaStamp.verify(sig, pub, e.xmlBytes)) {
                    return ChainVerifyResult(false, i, "signature invalid at entry ${i + 1}")
                }
            }
            expectedPih = documentHash(e.xmlBytes)
        }
        return ChainVerifyResult(true, null, null)
    }
}
