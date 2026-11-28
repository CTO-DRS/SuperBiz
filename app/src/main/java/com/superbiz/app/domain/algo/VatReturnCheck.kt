package com.superbiz.app.domain.algo

import com.superbiz.app.domain.ZatcaReturnP38

/**
 * فحص اتساق الإقرار الضريبي قبل التقديم (نقي). يعيد رموز المشاكل؛ القائمة الفارغة = متسق.
 * - SALES_SPLIT: خاضعة + صفرية + معفاة ≠ إجمالي المبيعات
 * - OUTPUT_VAT: مجموع ضريبة النسب ≠ ضريبة المخرجات
 * - NET_VAT: الصافي ≠ المخرجات − المدخلات
 * - NEGATIVE: مبلغ سالب في خانة لا تقبل السالب
 * - RATE_MISMATCH: ضريبة صف نسبة تبتعد عن صافيه × النسبة بأكثر من قرش لكل صفّ مُجمّع بحدود 1% من الصافي
 */
object VatReturnCheck {
    fun issues(r: ZatcaReturnP38.Report): List<String> {
        val out = mutableListOf<String>()
        val taxableNet = r.byRate.sumOf { it.net }
        if (taxableNet + r.salesZeroNet + r.salesExemptNet != r.salesNet) out.add("SALES_SPLIT")
        if (r.byRate.sumOf { it.vat } != r.outputVat) out.add("OUTPUT_VAT")
        if (r.netVat != r.outputVat - r.inputVat) out.add("NET_VAT")
        if (r.purchasesNet < 0L || r.inputVat < 0L || r.outputVat < 0L) out.add("NEGATIVE")
        if (r.byRate.any { it.net > 0L && Math.abs(it.vat - Math.round(it.net * it.rate / 100.0)) > maxOf(1L, it.net / 100L) }) out.add("RATE_MISMATCH")
        return out
    }
}
