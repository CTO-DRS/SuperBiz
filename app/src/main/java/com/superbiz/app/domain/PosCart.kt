package com.superbiz.app.domain

import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Product
import com.superbiz.app.util.Money

/**
 * محرك سلة نقطة البيع — منطق خالص 100% قابل للاختبار بلا أي اعتماد على أندرويد.
 * العمليات غير مُتغيِّرة (immutable): كل دالة تُعيد نسخة جديدة من السلة.
 *
 * [P33-P8] الأسعار والتكاليف قروش صحيحة (Long) — الكميات والمخزون كميات قياس
 * قد تكون كسرية فتبقى Double، وحاصل الضرب (كمية × سعر) يُقرَّب إلى قرش واحد Math.round.
 */
data class PosCartLine(
    val productId: Long?,
    val name: String,
    val unitPrice: Long,   // [P33-P8] قروش
    val costPrice: Long,   // [P33-P8] قروش
    val qty: Double,
    val stock: Double
)

/** نتائج حساب سلة نقطة البيع عند الدفع — [P33-P8] المبالغ قروش والنسبة Double */
data class PosTotals(
    val subtotal: Long,
    val discount: Long,
    val net: Long,
    val taxRate: Double,
    val tax: Long,
    val total: Long,
    // معاينة الربح الحي — null إذا كل البنود بلا تكلفة (يُخفى السطر في الواجهة)
    val profit: Long? = null,
    // [P46-W1] جولة 7 — أرقام الولاء المرافقة للمعاينة (المعاينة تطابق المحفوظ):
    // نقاط الاستبدال النهائية المقصوصة بعد المحرك، ومعرّف الكوبون المطبَّق (0 = بلا كوبون)
    val loyaltyRedeemPoints: Long = 0L,
    val couponId: Long = 0L
)

object PosCart {

    /** إضافة منتج بكمية: يزيد كمية سطره القائم أو يفتح سطراً جديداً */
    fun add(lines: List<PosCartLine>, item: PosCartLine, step: Double = 1.0): List<PosCartLine> {
        require(step > 0 && step.isFinite()) { "step must be positive and finite" }
        val i = lines.indexOfFirst { it.productId != null && it.productId == item.productId }
        return if (i >= 0) {
            lines.mapIndexed { idx, l ->
                if (idx == i) {
                    // التثبيت عند المتاح عند الجمع أيضاً — كان زر «+» فقط
                    // يتجاوز المخزون بلا سقف (setQty تُثبَّت منذ R9-C18) فيُباع أكثر من المتاح
                    var q = Money.round2(l.qty + step)
                    if (l.stock > 0) q = q.coerceAtMost(l.stock)
                    l.copy(qty = q)
                } else l
            }
        } else {
            // السطر الجديد كذلك يُثبَّت عند المتاح
            val q = if (item.stock > 0) Money.round2(step).coerceAtMost(item.stock) else Money.round2(step)
            if (q <= 0.0) lines else lines + item.copy(qty = q)
        }
    }

    /**
 * : setQty صار للأصناف المرتبطة بمنتج حصراً — كان null == null
 * يطابق كل سطور البيع الحر دفعة واحدة فيغيّر كمياتها جميعاً (أو يحذفها كلها عند صفر).
 * سطور بلا منتج تُدار بالمؤشر عبر setQtyAt/removeAt.
*/
    fun setQty(lines: List<PosCartLine>, productId: Long?, qty: Double): List<PosCartLine> =
        lines.mapNotNull { l ->
            if (l.productId != null && l.productId == productId) {
                // قيم NaN/∞ كانت تعبر فحص ‎capped <= 0‎ (مقارنات NaN خاطئة دائماً)
                // فتدخل السلة وتسمم كل المجاميع حتى الفاتورة — تُرفض الآن صراحة
                if (!qty.isFinite()) return@mapNotNull l
                val q = Money.round2(qty)
                // التثبيت عند المتاح — سطر بلا مخزون معروف (0) لا يُقيَّد
                val capped = if (l.stock > 0) q.coerceAtMost(l.stock) else q
                if (capped <= 0.0) null else l.copy(qty = capped)
            } else l
        }

    /**
 * : تعديل كمية سطر بمؤشره في القائمة — يعمل مع سطور البيع الحر
 * (بلا منتج) التي كان لا يمكن الوصول إليها فردياً، وبلا أي مساس لبقية السطور.
 * مؤشر خارج المدى يعيد القائمة كما هي بلا أي تغيير (صدق لا انهيار).
*/
    fun setQtyAt(lines: List<PosCartLine>, index: Int, qty: Double): List<PosCartLine> {
        if (index < 0 || index >= lines.size) return lines
        return lines.mapIndexed { i, l ->
            if (i == index) {
                if (!qty.isFinite()) return@mapIndexed l
                val q = Money.round2(qty)
                val capped = if (l.stock > 0) q.coerceAtMost(l.stock) else q
                if (capped <= 0.0) null else l.copy(qty = capped)
            } else l
        }.mapNotNull { it }
    }

    /**
 * : إزالة سطر بمؤشره — كان remove يرفض سطور البيع الحر نهائياً
 * (حارس productId != null) فكان زر الحذف زراً ميتاً عليها.
 * مؤشر خارج المدى يعيد القائمة كما هي.
*/
    fun removeAt(lines: List<PosCartLine>, index: Int): List<PosCartLine> {
        if (index < 0 || index >= lines.size) return lines
        return lines.filterIndexed { i, _ -> i != index }
    }

    /**
 * : تطهير سطور سلة مستعادة من نسخة محفوظة (JSON قديم/تالف) —
 * كانت resumeHeld تضخّ السطور كما هي: سعر/كمية NaN أو كمية ≤ 0 أو اسم فارغ
 * يتسرب إلى السلة الحية. يعيد سطوراً نظيفة فقط (السطر التالف يُهمل كلياً).
 * [P33-P8] الأسعار قروش — سالب السعر هو الوحيد المستحيل (لا NaN في الصحيح).
*/
    fun sanitizeRestored(lines: List<PosCartLine>): List<PosCartLine> =
        lines.mapNotNull { l ->
            val name = l.name.trim().ifBlank { "؟" }
            val price = if (l.unitPrice >= 0L) l.unitPrice else return@mapNotNull null
            val cost = if (l.costPrice >= 0L) l.costPrice else 0L
            val qty = if (l.qty.isFinite() && l.qty > 0.0) Money.round2(l.qty) else return@mapNotNull null
            val stock = if (l.stock.isFinite() && l.stock > 0.0) l.stock else 0.0
            val cappedQty = if (stock > 0) qty.coerceAtMost(stock) else qty
            PosCartLine(l.productId, name, price, cost, cappedQty, stock)
        }

    /** إزالة سطر بالمعرّف (للأصناف المرتبطة بمنتج — سطور البيع الحر عبر removeAt) */
    fun remove(lines: List<PosCartLine>, productId: Long?): List<PosCartLine> =
        lines.filterNot { it.productId != null && it.productId == productId }

    /**
 * : تحديث المخزون المثبّت في السطور من حالة المنتجات الحيّة —
 * كان سقف الكميات يُفرض على لقطة stock مجمدة لحظة الإضافة، فبيع نفس الصنف من
 * محرر الفواتير (أو استئناف سلة معلقة بعد أيام) كان يُثبّت السقف على قديم
 * ويسمح عند الدفع بمخزون سالب فعلي عبر addQty. القيمة null (منتج محذوف/مؤرشف)
 * تُبقي المخزون كما هو — فسطر الخدمة بلا منتج لا يتأثر.
*/
    fun refreshStock(lines: List<PosCartLine>, stockOf: (Long) -> Double?): List<PosCartLine> =
        lines.map { l ->
            val pid = l.productId ?: return@map l
            val live = stockOf(pid) ?: return@map l
            if (!live.isFinite() || live < 0.0) return@map l
            val withLive = l.copy(stock = live)
            // إعادة تثبيت الكمية عند المتاح الجديد فوراً (نفس عقد R9-C18)
            if (live > 0 && withLive.qty > live) withLive.copy(qty = Money.round2(live)) else withLive
        }

    /** المجموع قبل الخصم — [P33-P8] كل سطر Math.round(كمية × سعر القروش) بلا فاصلة عائمة مالية */
    fun subtotal(lines: List<PosCartLine>): Long =
        lines.sumOf { Math.round(it.qty * it.unitPrice) }

    /** إجمالي تكلفة الأصناف (لقيود COGS) */
    fun cost(lines: List<PosCartLine>): Long =
        lines.sumOf { Math.round(it.qty * it.costPrice) }

    /**
     * الإجمالي النهائي بعد الخصم — لا ينزل تحت الصفر أبداً.
     * لا يشمل الضريبة (تُحسب على الصافي في المستوى الأعلى).
     */
    fun netTotal(lines: List<PosCartLine>, discount: Long): Long =
        (subtotal(lines) - discount).coerceAtLeast(0L)

    /** عدد الوحدات الكلي في السلة */
    fun units(lines: List<PosCartLine>): Double = Money.round2(lines.sumOf { it.qty })

    /** هل السلة فارغة؟ */
    fun isEmpty(lines: List<PosCartLine>): Boolean = lines.isEmpty()

    // ══ : معاينة الربح الحي للسلة (وظيفة P4-1 رقم 4) ══

    /**
     * الربح المتوقع للسلة = مجموع (سعر − تكلفة)×كمية للبنود ذات التكلفة المعروفة − خصم الفاتورة.
     * البنود بلا تكلفة (costPrice = 0) تُستثنى من الجمع لأن تكلفتها مجهولة لا صفرية.
     * يعيد null إذا كل البنود بلا تكلفة — إشارة للواجهة لإخفاء السطر (حالة فراغ صادقة).
     */
    fun cartProfit(lines: List<PosCartLine>, invoiceDiscount: Long): Long? {
        if (lines.isEmpty()) return null
        val withCost = lines.filter { it.costPrice > 0L }
        if (withCost.isEmpty()) return null
        return withCost.sumOf { Math.round((it.unitPrice - it.costPrice) * it.qty) } - invoiceDiscount
    }

    /**
 * : الربح المحقق لكل فاتورة (وظيفة P4-1 رقم 10).
 * الربح = مجموع (سعر البند − تكلفة المنتج الحالية)×الكمية − خصم الفاتورة.
 * costOf يعيد تكلفة المنتج الحالية (قروش) أو null إذا المنتج غير متاح (محذوف/مؤرشف).
 * البنود بلا تكلفة متاحة تُستثنى من الجمع، والنتيجة null إذا لا تكلفة لأي بند — يُخفى السطر.
*/
    fun invoiceProfit(
        items: List<InvoiceItem>,
        invoiceDiscount: Long,
        costOf: (Long?) -> Long?
    ): Long? {
        if (items.isEmpty()) return null
        var anyCost = false
        var profit = 0L
        for (it in items) {
            val c = it.productId?.let { pid -> costOf(pid) } ?: continue
            anyCost = true
            profit += Math.round((it.unitPrice - c) * it.qty)
        }
        if (!anyCost) return null
        return profit - invoiceDiscount
    }

    // ══ : البيع السريع بمبلغ حر (وظيفة P4-1 رقم 2) ══

    /**
     * بناء سطر وحيد «بيع حر»: مبلغ + وصف حري بلا منتج مرتبط (productId = null)
     * — لا يتحرك مخزون، ويدخل الإحصاءات والخزنة كأي فاتورة عبر مسار الحفظ الحقيقي.
     * المبلغ ≤ 0 مرفوض بـ require.
     */
    fun freeSaleLine(amountPiasters: Long, label: String, description: String = ""): PosCartLine {
        require(amountPiasters > 0L) { "free sale amount must be positive" }
        val name = if (description.isBlank()) label else "$label — $description"
        return PosCartLine(
            productId = null, name = name,
            unitPrice = amountPiasters, costPrice = 0L,
            qty = 1.0, stock = 0.0
        )
    }

    // ══ : تكرار فاتورة بيع → إعادة طلب (وظيفة P4-1 رقم 5) ══

    /**
     * تجهيز سلة إعادة الطلب من بنود فاتورة: الكميات من الفاتورة، والأسعار والتكاليف
     * والمخزون من حالة المنتج الحالية (خطة P4 #5). بند بلا منتج مرتبط (بيع حر قديم)
     * يُعاد بسعر الفاتورة نفسه. البنود الفارغة الوصف أو سالبة الكمية تُهمَل.
     */
    fun linesFromInvoiceItems(items: List<InvoiceItem>, productOf: (Long) -> Product?): List<PosCartLine> {
        val out = ArrayList<PosCartLine>(items.size)
        for (it in items) {
            if (!(it.qty > 0.0) || it.desc.isBlank()) continue
            val p = it.productId?.let { pid -> productOf(pid) }
            out.add(
                if (p != null) PosCartLine(
                    productId = p.id, name = p.name,
                    unitPrice = p.salePrice, costPrice = p.costPrice,
                    qty = Money.round2(it.qty), stock = p.stockQty
                )
                else PosCartLine(
                    productId = null, name = it.desc,
                    unitPrice = it.unitPrice, costPrice = 0L,
                    qty = Money.round2(it.qty), stock = 0.0
                )
            )
        }
        return out
    }
}

// ═══════════════════════════════════════════════════════════════
// خصم على مستوى الفاتورة في نقطة البيع (وظيفة P4-1 رقم 1)
// ═══════════════════════════════════════════════════════════════

object InvoiceDiscount {

    /** وضع مدخل الخصم: مبلغ ثابت أو نسبة مئوية من المجموع */
    const val MODE_AMOUNT = 0
    const val MODE_PERCENT = 1

    /**
     * تفسير مدخل الخصم ورفض غير الصالح — بديل القبول الصامت القديم:
     * يعيد المبلغ بالقروش، أو null إذا المُدخل مرفوض
     * (سالب، غير نهائي، مبلغ أكبر من الإجمالي، أو نسبة أكبر من 100).
     * [P33-P8] MODE_AMOUNT: raw ريال (Double من حقل الإدخال) → toPiasters داخل هنا
     * — نقطة التحويل الوحيدة تبقى Money — والتحقق مقابل الإجمالي بالقروش.
     */
    fun resolve(raw: Double, mode: Int, subtotal: Long): Long? {
        if (!raw.isFinite() || raw < 0.0) return null
        return when (mode) {
            MODE_PERCENT -> if (raw > 100.0) null else Math.round(subtotal * raw / 100.0)
            else -> {
                val p = Money.toPiasters(raw)
                if (p > subtotal) null else p
            }
        }
    }

    /**
     * توزيع الخصم على البنود قرشاً بقرش: حصة كل بند بتناسب قيمته مع التقريب لأسفل،
     * ثم قروش البقايا تُعطى قرشاً بقرش لأكبر حصة (عند التعادل: السطر الأكبر قيمة).
     * مجموع الحصص يساوي الخصم تماماً — لا فرق تقريب مهما كانت البنود.
     * يعيد null إذا لا بنود أو الخصم غير صالح.
     * [P33-P8] كان يجري الضرب في 100 يدوياً على قيم ريال — صار الجمع والبقايا بالقروش مباشرة.
     */
    fun distribute(lines: List<PosCartLine>, discount: Long): List<Long>? {
        if (lines.isEmpty()) return null
        if (discount < 0L) return null
        val sub = PosCart.subtotal(lines)
        if (discount > sub) return null
        if (discount <= 0L) return List(lines.size) { 0L }
        val lineValues = lines.map { Math.max(0L, Math.round(it.qty * it.unitPrice)) }
        val total = lineValues.sumOf { it }
        if (total <= 0L) return List(lines.size) { 0L }
        val floorShares = lineValues.map { it * discount / total }
        var leftover = discount - floorShares.sumOf { it }
        val shares = floorShares.toMutableList()
        // أكبر حصة أولاً (عند التعادل: السطر الأكبر قيمة) — قرشاً بقرش حتى يستوي المجموع
        val order = lineValues.indices.sortedWith(
            compareByDescending<Int> { floorShares[it] }.thenByDescending { lineValues[it] }
        )
        var turn = 0
        while (leftover > 0 && turn < 10_000) {
            shares[order[turn % order.size]] += 1
            leftover--
            turn++
        }
        return shares
    }
}

// ═══════════════════════════════════════════════════════════════
// حالة معلقة لإحالة «إعادة الطلب» من شاشة الفواتير إلى نقطة البيع
// (وظيفة P4-1 رقم 5) — أبسط آلية متوافقة مع المعمارية: بلا تعديل تنقل ولا VM مشترك
// ═══════════════════════════════════════════════════════════════

object ReorderBus {
    @Volatile
    var pendingLines: List<PosCartLine>? = null
        private set

    /** حجز سلة معلقة — تُستهلك مرة واحدة عند فتح نقطة البيع */
    fun post(lines: List<PosCartLine>) { pendingLines = lines }

    /** الإخلاء بعد الاستهلاك (أو لإبطال طلب قديم غير مستهلك) */
    fun clear() { pendingLines = null }
}
