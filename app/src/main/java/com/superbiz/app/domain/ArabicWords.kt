package com.superbiz.app.domain

/**
 * — تفقيط المبالغ: تحويل رقم إلى كلمات عربية فصيحة
 * («فقط أربعة عشر ريالاً وثمانية وثلاثون هللة لا غير»).
 *
 * خوارزمية نقية بلا اعتماديات — تغطي 0..999,999,999 مع الكسور (هللات).
 * تُستخدم في الفواتير والسندات المطبوعة لزيادة موثوقية المستند المالي.
 *
 * القواعد المطبقة: مفرد/مثنى/جمع لمئة وألف ومليون، و«و» الوصل بين المقاطع،
 * واسم العملة بصيغة تمييز صحيحة (ريالاً/ريالات، هللة/هللات).
*/
object ArabicWords {

    private val ones = arrayOf(
        "", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة",
        "ستة", "سبعة", "ثمانية", "تسعة", "عشرة",
        "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر",
        "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"
    )

    private val tens = arrayOf(
        "", "عشرة", "عشرون", "ثلاثون", "أربعون", "خمسون",
        "ستون", "سبعون", "ثمانون", "تسعون"
    )

    private val hundreds = arrayOf(
        "", "مئة", "مئتان", "ثلاثمئة", "أربعمئة", "خمسمئة",
        "ستمئة", "سبعمئة", "ثمانمئة", "تسعمئة"
    )

    /** صيغة المضاعفات: مئتان، 3-10 جمع، 11-99 مفرد */
    private fun below1000(n: Int): String {
        require(n in 0..999)
        if (n == 0) return ""
        val h = n / 100
        val r = n % 100
        val parts = ArrayList<String>(3)
        if (h > 0) parts.add(hundreds[h])
        if (r > 0) {
            parts.add(
                when {
                    r < 20 -> ones[r]
                    r % 10 == 0 -> tens[r / 10]
                    else -> ones[r % 10] + " و" + tens[r / 10]
                }
            )
        }
        return parts.joinToString(" و")
    }

    private fun scaleWord(count: Int, one: String, two: String, plural: String): String = when {
        count == 1 -> one
        count == 2 -> two
        count in 3..10 -> plural
        // [P20-FIX agent13]: الباقي 11..99 ممنوص منصوب (وثيقة الدالة نفسها: أحد عشر ألفاً) —
        // كان يُطبع «خمسة عشر ألف» بلا تنوين على كل فاتورة. %100: مئة وأحد عشر ألفاً
        // (مخالف لـ 100 و200 البحتين اللتين تبقيان مضافاً: مئة ألف)
        count % 100 in 11..99 -> one + "اً"
        else -> one
    }

    /** تفقيط عدد صحيح غير سالب حتى 999,999,999 */
    fun integerToWords(n0: Long): String {
        require(n0 in 0..999_999_999L) { "المدى المدعوم 0..999,999,999" }
        var n = n0
        if (n == 0L) return "صفر"
        val parts = ArrayList<String>(5)

        val millions = (n / 1_000_000L).toInt()
        n %= 1_000_000L
        val thousands = (n / 1_000L).toInt()
        n %= 1_000L
        val units = n.toInt()

        if (millions > 0) {
            val w = scaleWord(millions, "مليون", "مليونان", "ملايين")
            parts.add(if (millions in 1..2) w else below1000(millions) + " " + w)
        }
        if (thousands > 0) {
            val w = scaleWord(thousands, "ألف", "ألفان", "آلاف")
            parts.add(if (thousands in 1..2) w else below1000(thousands) + " " + w)
        }
        if (units > 0) parts.add(below1000(units))
        return parts.joinToString(" و")
    }

    /**
     * تفقيط مبلغ مالي: الجزء الصحيح + كسور من مئة مع أسماء العملة
     * @param unit اسم العملة المفرد (ريال، درهم، دينار...)
     * @param fraction اسم وحدة الكسر (هللة، فلس، درهم...)
     */
    /**
 * : صيغة النصب للعدد 11-99 — تُلحق «اً» للأسماء الساكنة النهاية (ريال←ريالاً)
 * أما المنتهية بـ ة/ا/ى فتبقى كما هي (هللة، دقيقة) لأن التنوين عليها بخطِ الهمزة خطأ شائع
*/
    private fun accusative(unit: String): String {
        val last = unit.lastOrNull() ?: return unit
        return if (last == 'ة' || last == 'ا' || last == 'ى' || last == 'أ' || last == 'ء') unit
        else unit + "اً"
    }

    /** [P5-H13 إصلاح]: صيغة المثنى الصحيحة — المنتهية بـ ة تُستبدل تاؤها تاء المثنى
     *  (هللة ← هللتان لا «هللةان»)، وما دونها تلحق ألفان (ريال ← ريالان) */
    private fun dual(word: String): String =
        if (word.lastOrNull() == 'ة') word.dropLast(1) + "تان" else word + "ان"

    fun amountInWords(amount: Double, unit: String, fraction: String): String {
        require(amount >= 0 && amount.isFinite()) { "مبلغ غير صالح" }
        // مبالغ ≥ مليار كانت تُسقط مسار مشاركة PDF كله (require داخلي في
        // integerToWords) — نص رقمي واضح بدل الانهيار، وحد التفقيط اللفظي نفسه كما هو
        if (amount >= 1_000_000_000.0) {
            // [P20-FIX agent13]: valueOf لا المُغلّف الثنائي — كان ينحرف سنتاً عن Money.round2 لنفس القيمة
            val plain = java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
            return "فقط $plain $unit لا غير"
        }
        // التقريب عبر BigDecimal — كان 1.005×100 يعطي 100.4999 بسبب خطأ الفاصلة العائمة الثنائي
        val totalCents = java.math.BigDecimal.valueOf(amount)
            .movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        val whole = totalCents / 100
        val cents = (totalCents % 100).toInt()

        val sb = StringBuilder("فقط ")
        if (whole == 0L && cents == 0) return sb.append("صفر $unit لا غير").toString()

        if (whole > 0) {
            // المثنى بلا عدد — «ريالان» لا «اثنان ريالان»
            if (whole != 2L) { sb.append(integerToWords(whole)); sb.append(' ') }
            // تمييز اسم العملة: 1 مفرد، 2 مثنى، 3-10 جمع، 11-99 منصوب بتنوين، 100+ مفرد مجرور
            sb.append(
                when {
                    whole == 1L -> unit
                    whole == 2L -> dual(unit)   // ريالان/هللتان — كان «هللةان» على كل فاتورة بكسرين
                    whole in 3..10 -> unit + "ات"  // ريالات
                    whole in 11..99 -> accusative(unit)  // أربعة عشر ريالاً
                    else -> unit                   // مئة ريال
                }
            )
        }
        if (cents > 0) {
            if (whole > 0) sb.append(" و")
            if (cents != 2) { sb.append(integerToWords(cents.toLong())); sb.append(' ') }
            sb.append(
                when {
                    cents == 1 -> fraction
                    cents == 2 -> dual(fraction)   // هللتان — كان «هللةان» يُطبع على PDF كل فاتورة يكسرها 2 هللة
                    cents in 3..10 -> fraction + "ات"
                    cents in 11..99 -> accusative(fraction)
                    else -> fraction
                }
            )
        }
        sb.append(" لا غير")
        return sb.toString()
    }
}
