package com.superbiz.app.domain

import com.superbiz.app.domain.algo.QueryParseMath

/**
 * [W3] محرك الإجابات النقي للمنسّق الذكي — الأفق الثالث من الخارطة:
 *
 * يربط التحليل الحتمي (QueryParseMath) بلقطة الأعمال المبنية من المستودع
 * ويعيد إجابة بنيوية صادقة: مفتاح سلسلة + أرقام مرتبة + الأساس الخام.
 * لا قراءة قاعدة/شبكة/ساعة هنا — الـVM يبني اللقطات ويمرر «اليوم» صريحاً،
 * فتبقى الإجابة قابلة للاختبار حرفاً-بحرف على JVM خالص.
 *
 * عقد الصدق: أي نية بلا بياناتها في اللقطة ⇒ إجابة «لا بيانات كافية»
 * بمفتاح معلن — لا أرقام مزيّفة ولا تخمين، وكل إجابة تحمل أساسها.
 */
object SmartChat {

    /** لقطة الفترة الزمنية المحلولة (مبيعات/أرباح/مصروفات/عدد فواتير) */
    data class RangedFacts(
        val salesTotal: Double,
        val profitTotal: Double,     // صافي الربح (إيراد − تكلفة − مصروفات)
        val expensesTotal: Double,
        val invoicesCount: Int
    )

    /** لقطة عامة لا تعتمد على الفترة (نقد، متأخرات، تقادم، مخزون) */
    data class GlobalFacts(
        val cashNow: Double,
        val overdueTotal: Double,
        val aging: List<Double>,         // [0-30, 30-60, 60-90, +90] ريال
        val lowStockCount: Int,          // أصناف تحت حد الطلب (isLow)
        val stockValue: Double           // قيمة المخزون بالتكلفة
    )

    /** لقطة موضوع المخزون (صنف مذكور بالاسم في السؤال) */
    data class ProductFacts(
        val name: String,
        val stockQty: Double,
        val soldInRange: Double,     // الكمية المبيعة داخل الفترة
        val avgDaily: Double         // متوسط الطلب اليومي داخل الفترة
    )

    /** لقطة موضوع الطرف (رصيده من دفتر اليومية) */
    data class PartyFacts(
        val name: String,
        val balance: Double          // موجب = عليه (ذمم)، سالب = له (دائن)
    )

    /** الإجابة البنيوية — الواجهة تترجم المفتاح وتنسّق الأرقام */
    data class ChatAnswer(
        val key: String,             // ans_sales / ans_profits / ans_receivables / ans_inventory / ans_inventory_item / ans_party / ans_nodata
        val numbers: List<Double>,   // وسائط التنسيق بالترتيب
        val basis: String            // الأساس الخام (يُعرض في سطر المصدر)
    )

    /**
     * الإجابة الحتمية: النية تحدد اللقطة المطلوبة، والموضوع يحوله إن وُجد.
     * عقد: فترة بلا حركة مع نية مالية ⇒ ans_nodata صادق؛ موضوع صنف بلا
     * لقطته ⇒ ans_nodata؛ موضوع طرف بلا رصيده ⇒ ans_nodata.
     */
    fun answer(
        parsed: QueryParseMath.Parsed,
        ranged: RangedFacts?,
        global: GlobalFacts?,
        product: ProductFacts?,
        party: PartyFacts?
    ): ChatAnswer {
        // الموضوع يتقدم على النية العامة (سؤال عن صنف = سؤال مخزون عنه)
        if (parsed.subject != null) {
            when (parsed.subject.kind) {
                QueryParseMath.Subject.Kind.PRODUCT ->
                    return product?.let {
                        ChatAnswer(
                            "ans_inventory_item",
                            listOf(it.stockQty, it.avgDaily, it.soldInRange),
                            basis = "product '${it.name}' stock=${trim(it.stockQty)} avgDaily=${trim(it.avgDaily)} soldInRange=${trim(it.soldInRange)}"
                        )
                    } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no product snapshot")
                QueryParseMath.Subject.Kind.PARTY ->
                    return party?.let {
                        ChatAnswer(
                            "ans_party",
                            listOf(it.balance),
                            basis = "party '${it.name}' balance=${trim(it.balance)}"
                        )
                    } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no party snapshot")
            }
        }
        return when (parsed.intent) {
            QueryParseMath.Intent.SALES -> ranged?.let {
                if (it.invoicesCount == 0 && it.salesTotal <= 0.0)
                    ChatAnswer("ans_nodata", emptyList(), basis = "no sales in range")
                else ChatAnswer(
                    "ans_sales",
                    listOf(it.salesTotal, it.invoicesCount.toDouble()),
                    basis = "sales=${trim(it.salesTotal)} invoices=${it.invoicesCount}"
                )
            } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no ranged snapshot")
            QueryParseMath.Intent.PROFITS -> ranged?.let {
                if (it.salesTotal <= 0.0 && it.expensesTotal <= 0.0)
                    ChatAnswer("ans_nodata", emptyList(), basis = "no profit data in range")
                else ChatAnswer(
                    "ans_profits",
                    listOf(it.profitTotal, it.salesTotal, it.expensesTotal),
                    basis = "profit=${trim(it.profitTotal)} sales=${trim(it.salesTotal)} expenses=${trim(it.expensesTotal)}"
                )
            } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no ranged snapshot")
            QueryParseMath.Intent.RECEIVABLES -> global?.let {
                if (it.overdueTotal <= 0.0 && it.aging.all { a -> a <= 0.0 })
                    ChatAnswer("ans_nodata", emptyList(), basis = "no open receivables")
                else ChatAnswer(
                    "ans_receivables",
                    listOf(it.overdueTotal) + it.aging,
                    basis = "overdue=${trim(it.overdueTotal)} aging=${it.aging.joinToString("/") { a -> trim(a) }}"
                )
            } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no global snapshot")
            QueryParseMath.Intent.INVENTORY -> global?.let {
                ChatAnswer(
                    "ans_inventory",
                    listOf(it.lowStockCount.toDouble(), it.stockValue),
                    basis = "lowStock=${it.lowStockCount} stockValue=${trim(it.stockValue)}"
                )
            } ?: ChatAnswer("ans_nodata", emptyList(), basis = "no global snapshot")
        }
    }

    private fun trim(v: Double): String {
        val r2 = Math.round(v * 100.0) / 100.0
        return if (r2 == Math.round(r2).toDouble()) Math.round(r2).toString()
        else String.format(java.util.Locale.US, "%.2f", r2)
    }
}
