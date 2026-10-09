package com.superbiz.app.domain.algo

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * موجة R17 «العملات المتعددة» — الأفق الرابع (H4-1) — V 2.5.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * عقد الموجة (عدم تقاطع مع R7..R16 — تُمدَّد بلا تعديل):
 * 1) **صفر Double في مسار التحويل**: كل الدوال الرياضية Long/BigDecimal-عدد صحيح حصراً.
 *    الـDouble الوحيد المقبول هو «قيمة rateToBase المخزّنة في كتالوج العملات» (إرث تخزين
 *    منذ v1) — ويُقرأ عبر BigDecimal(rateToBase.toString()) فتُلتقط حروفه العشرية المكتوبة
 *    حرفياً بلا تمثيل ثنائي، ثم لا يعود Double إلى الحياة في أي حساب بعدها.
 * 2) **دلالة السعر (rateMicros)**: قروش الأساس (P33-P8) لكل «وحدة واحدة» من العملة
 *    الأجنبية، مضروبة بمليون — أي دقة 1e-6 قرش = 1e-8 ريال لكل وحدة أجنبية.
 *    مثال: دولار بـ3.75234522 ريال = 375.234522 قرشاً = rateMicros 375_234_522.
 *    «3.75» حرفياً = 375_000_000. SAR (الأساس) = 100_000_000 دائماً (1.0).
 * 3) **دلالة foreignMinor**: وحدات فرعية بمقياس 2dp عالمي (نفس ما يجمعه
 *    Money.parseToPiasters من حقل إدخال نصي) — عمداً لا نستعمل minorUnits كتالوج
 *    العملة الأجنبية في مسار الإدخال (الدينار بثلاث خانات والين بلا خانات يُدخلان
 *    «مبلغاً بخانتين عشريتين» كما يكتبه التاجر)، والمعامل minorUnits يبقى في العقد
 *    للاستخدام المستقبلي ومختبَر JVM بقيم 0 و3 حرفياً.
 * 4) **الإخفاق مغلَق (fail-closed)**: كل دالة تحويل تعيد Long? — null عند أي مدخل
 *    غير صالح (سعر ≤0 أو غير منتهٍ، مبلغ سالب، فيض Long، وحدات فرعية خارج 0..4).
 *    null يعني «ارفض الحفظ برسالة» ولا يعني «حوّل إلى صفر» — صفر مبلغ مالي زائف.
 * 5) **التحقق التاريخي**: السعر يُختم عند الإدخال على الصف نفسه (invoices.origFxMicros /
 *    expenses.origFxMicros) — التقرير الموحد يقرأ قروش الأساس المخزّنة، والفئة
 *    الأصلية بسعرها التاريخي وثيقة عرض لا إعادة حساب. لا ننادي أي سعر «حالي» على صف قديم.
 * 6) **الحد الأقصى**: rateMicros ∈ [1, 1e15] (أي سعر ≤ 10 ملايين ريال للوحدة —
 *    سقف عبثي مقصود يصد خطأ الإدخال)، foreignMinor ∈ [0, 1e15].
 *
 * المرجع: خارطة الطريق الرئيسية H4-1 — بوابة القبول: «توازن محاسبي كامل عبر عملات
 * مختلطة في الاختبارات + الانحدار بلا Double في أي مسار مالي».
 */
object FxStampMath {

    /** وحدات الأساس الفرعية — عالم القروش P33-P8 ثابت منذ ترحيل 9→10 */
    const val BASE_MINOR_UNITS = 2

    /** micros لكل قرش أجنبي — انظر بند (2) من عقد الموجة */
    const val RATE_MICROS_PER_PIASTER = 1_000_000L

    /** micros العملة الأساس نفسها (سعر الهوية 1.0) */
    const val BASE_RATE_MICROS = 100_000_000L

    /** سقف السعر المقبول — بند (6) */
    const val MAX_RATE_MICROS = 1_000_000_000_000_000L

    /** سقف المبلغ الأجنبي المقبول — بند (6) */
    const val MAX_FOREIGN_MINOR = 1_000_000_000_000_000L

    private val RATE_SCALE_BD = BigDecimal(RATE_MICROS_PER_PIASTER)

    /** أقصى منازل عشرية مقبولة في نص سعر مُدخل (دقة micros = 8 منازل ريال) */
    const val MAX_RATE_DECIMALS = 8

    // ─────────────────────────── 1) من الكتالوج إلى micros ───────────────────────────

    /**
     * اشتقاق سعر micros حتمي من rateToBase الكتالوج (وحدات أجنبية لكل 1 أساس).
     * العلاقة: direct = 1/rateToBase (ريال لكل وحدة أجنبية) ⇒ micros = 1e8 / rateToBase.
     * 0.2665 → 1e8/0.2665 = 375234521.576… → HALF_UP = 375_234_522 (حتمية بايتية).
     * يعيد null عند سعر غير نهائي أو ≤0 — لا استثناءات تُرمى للمستدعي.
     */
    fun rateMicrosFromCatalog(rateToBase: Double): Long? {
        if (!rateToBase.isFinite() || rateToBase <= 0.0) return null
        return try {
            // 1e8 / rateToBase — HALF_UP على القسمة نفسها (لا Double وسيط)
            val micros = BigDecimal(100_000_000L)
                .divide(BigDecimal(rateToBase.toString()), 0, RoundingMode.HALF_UP)
                .longValueExact()
            if (micros < 1L || micros > MAX_RATE_MICROS) null else micros
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    // ─────────────────────────── 2) التحويل الأمامي (إدخال → أساس) ───────────────────────────

    /**
     * مبلغ أجنبي (2dp عالمي) → قروش أساس، بسعر مختوم. HALF_UP على ناتج الضرب الدقيق.
     * basePiasters = HALF_UP( foreignMinor × rateMicros / (1e6 × 10^minorUnits) )
     * أمثلة مثبتة اختباراً: (10000¢، 3.75$) = 37500 قرشاً بالضبط؛ (1250 fils كويتي 3 خانات،
     * 3.125) = 391 قرشاً (390.625 HALF_UP)؛ (1000 ين 0 خانات، 0.025) = 2500 قرش.
     */
    fun toBasePiasters(foreignMinor: Long, minorUnits: Int, rateMicros: Long): Long? {
        if (foreignMinor < 0L || foreignMinor > MAX_FOREIGN_MINOR) return null
        if (minorUnits !in 0..4) return null
        if (!isValidRateMicros(rateMicros)) return null
        return return try {
            val divisor = RATE_SCALE_BD.multiply(BigDecimal.TEN.pow(minorUnits))
            BigDecimal(foreignMinor)
                .multiply(BigDecimal(rateMicros))
                .divide(divisor, 0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: ArithmeticException) {
            null // فيض Long — مبلغ عبثي يُرفض لا يُقص
        }
    }

    // ─────────────────────────── 3) التحويل العكسي (عرض فقط) ───────────────────────────

    /**
     * قروش أساس → مبلغ أجنبي 2dp — للعرض والوثائق حصراً (لا يُخزَّن ناتجها مطلقاً
     * في عمود مالي، وإلا انكسرت أولوية القروش الأساسية P33-P8).
     * foreignMinor = HALF_UP( basePiasters × (1e6 × 10^minorUnits) / rateMicros )
     */
    fun fromBasePiasters(basePiasters: Long, minorUnits: Int, rateMicros: Long): Long? {
        if (basePiasters < 0L) return null
        if (minorUnits !in 0..4) return null
        if (!isValidRateMicros(rateMicros)) return null
        return try {
            val scale = RATE_SCALE_BD.multiply(BigDecimal.TEN.pow(minorUnits))
            BigDecimal(basePiasters)
                .multiply(scale)
                .divide(BigDecimal(rateMicros), 0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    // ─────────────────────────── 4) نص السعر ↔ micros ───────────────────────────

    /**
     * تحليل صارم لنص سعر يدوي → micros، بلا Double إطلاقاً (تفكيك حروف يدوي):
     * • يطبّع الأرقام العربية-الهندية (٠-٩ و۰-۹) وفاصلة عشرية عربية (٫) أولاً.
     * • الفاصلة العشرية: نقطة أو ٫ حصراً — الفاصلة «,» تُرفض (خطر «3,75» = 375).
     * • الصيغة المقبولة: 1..9 خانات صحيحة اختيارياً تتبعها نقطة و1..8 خانات عشرية.
     * • يُرفض: سالب، صفر، أحرف غريبة، أكثر من 8 منازل، قيمة فوق السقف.
     */
    fun parseRateToMicros(text: String): Long? {
        if (text.length > 32) return null
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '٠'..'٩' -> sb.append(('0' + (ch - '٠')))
                ch in '۰'..'۹' -> sb.append(('0' + (ch - '۰')))
                ch == '٫' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        val cleaned = sb.toString().trim().replace(" ", "")
        if (cleaned.isEmpty()) return null
        val dot = cleaned.indexOf('.')
        val intPart: String
        val fracPart: String
        if (dot < 0) {
            intPart = cleaned; fracPart = ""
        } else {
            if (cleaned.indexOf('.', dot + 1) >= 0) return null // نقطتان
            intPart = cleaned.substring(0, dot)
            fracPart = cleaned.substring(dot + 1)
        }
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (intPart.any { it !in '0'..'9' }) return null
        if (fracPart.any { it !in '0'..'9' }) return null
        if (fracPart.length > MAX_RATE_DECIMALS) return null
        if (intPart.length > 9) return null
        val intVal = if (intPart.isEmpty()) 0L else intPart.toLongOrNull() ?: return null
        if (intVal <= 0L && fracPart.none { it != '0' }) return null // صفر حرفياً
        val fracMicros = if (fracPart.isEmpty()) 0L
        else fracPart.padEnd(MAX_RATE_DECIMALS, '0').toLongOrNull() ?: return null
        val micros = try {
            BigDecimal(intVal).multiply(BigDecimal(100_000_000L)).add(BigDecimal(fracMicros))
                .longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return if (isValidRateMicros(micros)) micros else null
    }

    /** micros → نص عشري بخانة النقطة، بلا Double — الأصفار الزائدة تُقتطع (3.750 → 3.75) */
    fun formatRate(micros: Long): String {
        if (micros < 0L) return ""
        val whole = micros / 100_000_000L
        val frac = micros % 100_000_000L
        if (frac == 0L) return whole.toString()
        var fracText = frac.toString().padStart(8, '0')
        while (fracText.endsWith('0')) fracText = fracText.dropLast(1)
        return "$whole.$fracText"
    }

    /** بند (6) — مدخل السعر صالح؟ (مفردة الحقيقة لكل الدوال) */
    fun isValidRateMicros(micros: Long): Boolean =
        micros in 1..MAX_RATE_MICROS
}
