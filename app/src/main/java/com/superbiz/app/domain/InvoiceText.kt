package com.superbiz.app.domain

import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.domain.algo.TextMath
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money

// ═══════════════════════════════════════════════════════════════
// بنّاء نص ملخص الفاتورة للمشاركة (وظيفة P4-1 رقم 8)
// ═══════════════════════════════════════════════════════════════

/**
 * بنّاء نقي (بلا أندرويد) لنص عربي منسق يُشارك عبر ACTION_SEND:
 * رقم الفاتورة / التاريخ / الطرف / النوع / البنود سطراً سطراً / المجموع والخصم والضريبة والإجمالي.
 */
object InvoiceText {

    /**
     * [P30-C] تسميات قابلة للتوطين — نص الفاتورة المُشارَك للعملاء عبر SMS/واتساب
     * كان عربياً صلباً حتى بلغة التطبيق الإنجليزية. الافتراضي يبقى العربية لتوافق
     * الاستدعاءات والاختبارات القائمة (نمط Labels المثبت في ShoppingList
     * وReportSummaryText)، والواجهة تمرر النصوص من الموارد حسب لغة التطبيق.
     */
    data class Labels(
        val invoice: String = "فاتورة",
        val date: String = "التاريخ",
        val party: String = "الطرف",
        val type: String = "النوع",
        val sale: String = "بيع",
        val purchase: String = "شراء",
        val item: String = "بند",
        val subtotal: String = "المجموع",
        val discount: String = "الخصم",
        val tax: String = "الضريبة",
        val total: String = "الإجمالي",
        val remaining: String = "المتبقي",
        // [H4-1 V 2.5.0] سطر الفئة الأصلية — يظهر فقط للفواتير المختومة بعملة أجنبية
        val originalAmount: String = "الفئة الأصلية"
    )

    fun summary(inv: Invoice, items: List<InvoiceItem>, partyName: String?, labels: Labels = Labels()): String {
        val sb = StringBuilder()
        sb.appendLine("🧾 ${labels.invoice} ${inv.number}")
        sb.appendLine("${labels.date}: ${Dates.short(inv.date)}")
        partyName?.takeIf { it.isNotBlank() }?.let { sb.appendLine("${labels.party}: $it") }
        sb.appendLine("${labels.type}: ${if (inv.isSale) labels.sale else labels.purchase}")
        sb.appendLine("—————————")
        // [P33-P8] الأسعار والإجماليات قروش عبر numP؛ الكمية Double تبقى num — والعتبات مساواة صحيحة
        for (it in items) {
            sb.appendLine("• ${it.desc.ifBlank { labels.item }} — ${Money.num(it.qty)} × ${Money.numP(it.unitPrice)} = ${Money.numP(it.lineTotal)}")
        }
        sb.appendLine("—————————")
        sb.appendLine("${labels.subtotal}: ${Money.numP(inv.subtotal)}")
        if (inv.discount > 0L) sb.appendLine("${labels.discount}: -${Money.numP(inv.discount)}")
        if (inv.taxAmount > 0L) sb.appendLine("${labels.tax}: ${Money.numP(inv.taxAmount)}")
        sb.appendLine("${labels.total}: ${Money.numP(inv.total)} ${inv.currency}")
        // [H4-1 V 2.5.0] الفئة الأصلية بسعرها التاريخي — وثيقة عرض من الختم المخزّن
        // (origTotal بوحدات 2dp عالمية، origFxMicros عقد R17) — لا إعادة حساب من أي سعر حالي
        if (inv.origCurrency.isNotBlank()) {
            val rateText = com.superbiz.app.domain.algo.FxStampMath.formatRate(inv.origFxMicros)
            sb.appendLine("${labels.originalAmount}: ${Money.numP(inv.origTotal)} ${inv.origCurrency} @ $rateText")
        }
        if (inv.status < 3 && inv.open > 0L) sb.appendLine("${labels.remaining}: ${Money.numP(inv.open)}")
        return sb.toString().trimEnd()
    }
}

// ═══════════════════════════════════════════════════════════════
// مُرشِّح الفواتير (وظيفة P4-1 رقم 6) — الحالة + النطاق + البحث
// منطق نقي يُستخدم في InvoicesVM ويُختبر بلا أندرويد
// ═══════════════════════════════════════════════════════════════

object InvoiceFilters {

    const val STATUS_ALL = -1
    const val RANGE_ALL = 0
    const val RANGE_TODAY = 1
    const val RANGE_WEEK = 2
    const val RANGE_MONTH = 3

    /**
     * بداية النطاق الزمني: اليوم = بداية يوم اليوم؛ الأسبوع = آخر 7 أيام شاملة اليوم؛
     * الشهر = بداية الشهر الحالي؛ الكل = 0 (بلا حد أدنى).
     */
    fun rangeStart(range: Int, now: Long): Long = when (range) {
        RANGE_TODAY -> Dates.startOfDay(now)
        // [P20-FIX agent13]: طرح 6 أيام عبر التقويم — كان طرح 6×86400000 ثابتاً (انزياح DST)
        RANGE_WEEK -> java.util.Calendar.getInstance().apply {
            timeInMillis = Dates.startOfDay(now); add(java.util.Calendar.DAY_OF_MONTH, -6)
        }.timeInMillis
        RANGE_MONTH -> Dates.monthStart(now)
        else -> 0L
    }

    /**
     * ترشيح قائمة الفواتير: الحالة (0 غير مدفوعة، 1 جزئية، 2 مدفوعة، 3 ملغاة، -1 الكل)
     * + نطاق زمني + بحث برقم الفاتورة أو اسم الطرف بتطبيع عربي بسيط (arabicNormalize)
     * يتجاهل الهمزات والتاء المربوطة والتشكيل وحالة الأحرف اللاتينية.
     */
    fun apply(
        list: List<Invoice>,
        partyNames: Map<Long, String>,
        status: Int,
        range: Int,
        query: String,
        now: Long
    ): List<Invoice> {
        var r = list
        if (status >= 0) r = r.filter { it.status == status }
        val from = rangeStart(range, now)
        // [P20-FIX agent13]: الحدّ الأعلى = الآن (كان now+24h ففاتورة بتاريخ الغد تدخل «اليوم»/«هذا الشهر»)
        if (from > 0L) r = r.filter { it.date in from..now }
        val q = query.trim()
        if (q.isNotEmpty()) {
            val nq = TextMath.arabicNormalize(q)
            r = r.filter { inv ->
                TextMath.arabicNormalize(inv.number).contains(nq, ignoreCase = true) ||
                    (partyNames[inv.partyId]?.let { TextMath.arabicNormalize(it) } ?: "")
                        .contains(nq, ignoreCase = true)
            }
        }
        return r
    }
}
