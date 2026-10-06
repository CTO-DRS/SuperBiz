package com.superbiz.app.print

/**
 * : وظيفة 36 — معاينة نصية لإيصال ESC/POS قبل الطباعة.
 * يعيد النص المنطقي كما سيُطبع من المولّد القائم EscPos.build — نفس الأسطر
 * والتبطين بعرض الورق، بلا أوامر التحكم الثنائية.
 * ملاحظة صدق: المعاينة تعرض ترتيب القراءة الطبيعي للنص؛ انعكاس مقاطع العربية
 * وترميزها (CP1256/UTF-8) يحدث وقت توليد بايتات الطباعة حسب وضع الجهاز.
 * خالص بلا اعتماد Android — قابل للاختبار وحداتياً.
*/
object ReceiptPreview {

    /** النص المنطقي للإيصال بعرض ورق محدد (32 لورق 58مم أو 48 لورق 80مم) */
    fun text(r: EscPos.Receipt, width: Int = 32): String {
        val w = width.coerceIn(20, 64)
        val sep = "-".repeat(w)
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        fun center(s: String) {
            if (s.isBlank()) return
            val pad = ((w - s.length) / 2).coerceAtLeast(0)
            line(" ".repeat(pad) + s)
        }
        // الترويسة ممركزة كما في build
        center(r.businessName)
        center(r.title)
        center(r.dateText)
        if (r.partyName.isNotBlank()) center(r.partyName)
        line(sep)
        // البنود — نفس تفريعات build: سطر مفرد «بيان ..... قيمة» أو (وصف ثم كمية × سعر)
        r.lines.forEach { l ->
            if (l.qty.isBlank() && l.price.isBlank()) {
                line(EscPos.row(l.desc, l.total, w))
            } else {
                line(l.desc)
                line(EscPos.row(l.qty + " × " + l.price, l.total, w))
            }
        }
        line(sep)
        // الإجماليات — آخرها يُطبع عريضاً (المعاينة نصية فتظهر كأي سطر)
        r.totals.forEach { (label, value) -> line(EscPos.row(label, value, w)) }
        if (r.statusText.isNotBlank()) center(r.statusText)
        line(sep)
        center(r.footer)
        return sb.toString().trimEnd('\n')
    }
}
