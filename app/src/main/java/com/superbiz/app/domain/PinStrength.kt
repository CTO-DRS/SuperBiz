package com.superbiz.app.domain

/**
 * — مقياس قوة رمز الحماية (وظيفة 37) — دالة نقية تُرجع مستوى 0..3
 * 0 = أقل من سياسة الطول (أقل من 6 محارف)
 * 1 = ضعيف (يستوفي الطول فقط، أو نمط ضعيف معروف)
 * 2 = متوسط (طول ≥8 أو تنوّع فئتين)
 * 3 = قوي (طول ≥8 وتنوّع فئتين أو أكثر: أرقام/حروف/رموز)
 *
 * النمط الضعيف المعروف يُسقّط المستوى إلى 1 مهما كان الطول: كل المحارف متطابقة،
 * أو أرقام متتالية صاعدة/هابطة (123456 / 87654321).
 * لا يتغير أي منطق تحقق/تخزين للرمز — هذا المقياس إرشادي للعرض فقط.
*/
object PinStrength {

    /** هل الرمز يطابق نمطاً ضعيفاً معروفاً؟ */
    fun weakPattern(pin: String): Boolean {
        if (pin.isEmpty()) return false
        if (pin.all { it == pin[0] }) return true
        if (pin.all { it.isDigit() }) {
            val asc = pin.zipWithNext().all { (a, b) -> b.code == a.code + 1 }
            val desc = pin.zipWithNext().all { (a, b) -> b.code == a.code - 1 }
            if (asc || desc) return true
        }
        return false
    }

    /** عدد فئات المحارف الموجودة: أرقام / حروف / غير ذلك (رموز) */
    fun classes(pin: String): Int = listOf(
        pin.any { it.isDigit() },
        pin.any { it.isLetter() },
        pin.any { !it.isDigit() && !it.isLetter() }
    ).count { it }

    /** مستوى القوة 0..3 */
    fun level(pin: String): Int {
        if (pin.length < 6) return 0
        val c = classes(pin)
        val base = when {
            pin.length >= 8 && c >= 2 -> 3
            pin.length >= 8 || c >= 2 -> 2
            else -> 1
        }
        return if (weakPattern(pin)) minOf(base, 1) else base
    }
}
