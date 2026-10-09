package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.JournalLine
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.StockMove
import com.superbiz.app.domain.AccountingEngine
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

class InvoiceRepo(
    private val db: AppDatabase,
    private val loyalty: LoyaltyRepo? = null,
    // [Z2-أ V 1.5.0] خاتم ZATCA-2 — null يبقي كل السلوك القائم حرفياً (نمط loyalty)
    private val zatca: ZatcaStamper? = null,
) {

    // أي تغيير على الفواتير (حفظ/إلغاء) يجدد ويدجات الشاشة الرئيسية — نفس نمط LedgerRepo
    var onMutate: (() -> Unit)? = null

    /** [H4-6][V 3.0.0] إشعار فاتورة جديدة — يُستدعى بعد نجاح الحفظ بمعرّفها (ويب هوك). */
    var onInvoiceCreated: ((Long) -> Unit)? = null

    fun invoices(): Flow<List<Invoice>> = db.invoices().all()

    // قائمة تشمل الفواتير الملغاة — لرقاقة فلتر «ملغاة» الصادقة في شاشة الفواتير
    fun invoicesIncludingVoid(): Flow<List<Invoice>> = db.invoices().allIncludingVoid()

    suspend fun invoice(id: Long): Invoice? = db.invoices().byId(id)
    suspend fun items(invoiceId: Long): List<InvoiceItem> = db.invoiceItems().forInvoice(invoiceId)

    // [P33-P8] كل الحقول مبالغ قروش Long — cogs = costTotal قروش
    data class Totals(
        val subtotal: Long, val discount: Long,
        val taxAmount: Long, val total: Long, val cogs: Long
    )

    // [P33-P8] قروش صحيحة: unitPrice/discount/cogs قروش Long، taxRate نسبة Double،
    // line لكل بند = Math.round(qty × unitPrice) — الضريبة Math.round(net × rate / 100)
    fun computeTotals(
        items: List<Triple<Double, Long, Long>>, // (qty, unitPrice, discount)
        taxRate: Double, cogsPerItem: List<Long>
    ): Totals {
        val subtotal = items.sumOf { (q, p, _) -> Math.round(q * p) }
        val discount = items.sumOf { (_, _, d) -> d }
        val net = subtotal - discount
        val tax = Math.round(net * taxRate / 100.0)
        val cogs = cogsPerItem.sum()
        return Totals(subtotal, discount, tax, net + tax, cogs)
    }

    /**
     * حفظ فاتورة كاملة: الأصناف + حركات المخزون + القيد المزدوج — ذرّي بالكامل.
     * فاتورة بيع: تخفض المخزون وتقيد COGS. فاتورة شراء: ترفع المخزون.
     * أي فشل في أي خطوة يتراجع عن كل شيء — لا فواتير بلا قيود ولا عكس.
     */
    suspend fun save(
        invoice: Invoice,
        items: List<InvoiceItem>,
        productCogs: Map<Long?, Long> = emptyMap(),
        moveStock: Boolean = true,
        // تحصيل نقدي فوري داخل نفس المعاملة — كان البيع النقدي معاملتين
        // (حفظ ثم تحصيل) فموت العملية بينهما يترك بيعاً نقدياً ذمّمة معلّقة
        collectCashDirection: Int? = null,
        // [P46-W1] جولة 7 — الولاء والكوبونات داخل نفس المعاملة الذرّية:
        // كسب النقاط (محسوب لدى المستدعي بمحرك LoyaltyP46 من صافي البيع والإعدادات)،
        // واستبدال نقاط (يتحقق الرصيد حياً داخل المعاملة)، واستهلاك كوبون (تحديث
        // ذرّي مشروط) — أي فشل يتراجع عن الفاتورة وقيودها ومخزونها ونقاطها معاً.
        // الافتراضي الصفري/الصفر يعني بلا ولاء — كل المواضع القائمة سلوكها حرفياً كما كان.
        loyaltyEarn: Long = 0L,
        loyaltyRedeem: Long = 0L,
        couponId: Long = 0L
    ): Long {
        require(items.isNotEmpty()) { "no items" }
        // حراسة القيم على مستوى المستودع — فاتورة سالبة
        // كانت تُرحّل قيوداً سامة تفسد الخزنة والتقارير، وبند بكمية NaN يفسد COGS
        // [P33-P8] المبالغ قروش — حراسة صحيحة تامة (isFinite محذوف: Long لا يكون NaN)
        require(invoice.total >= 0L) { "invoice total must be >= 0" }
        require(invoice.subtotal >= 0L) { "invoice subtotal must be >= 0" }
        for (it in items) {
            require(it.qty.isFinite() && it.qty > 0.0) { "item qty must be finite > 0" }
            require(it.unitPrice >= 0L) { "item unitPrice must be >= 0" }
        }
        // [P5-H7 إصلاح]: مسار التعديل (id != 0) كان يزدد الأثر — upsert ثم دلتا مخزون جديدة
        // وقيد جديد دون عكس القديم: ازدواج محاسبي وتضخيم مخزون. الآن يُعكس أثر الفاتورة
        // القديمة (مخزون + قيود) داخل نفس المعاملة قبل تطبيق الجديد — نفس منطق voidInvoice —
        // مع منع التحصيل الفوري على التعديل ومن تعديل فاتورة ملغاة، والحفاظ على رقم الفاتورة
        val isUpdate = invoice.id != 0L
        var invId = 0L
        db.withTransaction {
            // [P5-H7 إصلاح]: الفاتورة القديمة تُقرأ أولاً في مسار التعديل — وهي أساس
            // عكس الأثر وقرار الاحتفاظ بالرقم
            val prev = if (isUpdate) db.invoices().byId(invoice.id) else null
            if (isUpdate) {
                require(prev != null) { "edit target invoice not found: ${invoice.id}" }
                require(prev.status < 3) { "cannot edit a voided invoice" }
                require(collectCashDirection == null) { "collect-on-save applies to new invoices only" }
            }
            // ترقيم آمن: لا يتضارب مع فاتورة قائمة () — وفي التعديل: رقم الفاتورة
            // نفسه يُحفظ، والرقم الحر الجديد يُقبل، وغير ذلك يُولَّد التالي المتاح
            // (كان استدعاء uniqueNumber دائماً يعيد توليد الرقم عند كل تعديل)
            // [P6-M5 إصلاح]: سباق الترقيم — توليد الرقم (countByType وfindNumber في uniqueNumber)
            // يجب أن يقع داخل withTransaction نفسها وقبل الإدراج، لأن معاملات الكتابة في SQLite
            // متسلسلة فلا يقرأ مستندان العدّاد نفسه ويُدرجان رقماً مكرراً. هذا هو الواقع فعلاً
            // (الاستدعاء هنا داخل المعاملة منذ وأكّده P5-H7) — الوسم يثبّت العقد
            // ممنوع تحريك توليد الرقم خارج هذه المعاملة، وفحص findNumber + الإدراج ذرّيان فيها.
            // (‏nextNumber() خارج المعاملة يبقى تلميح عرضٍ للمحرر فقط — الحفظ يعيد ضمان التفرد دائماً)
            val withNumber = when {
                !isUpdate -> invoice.copy(number = uniqueNumber(invoice.number, invoice.isSale))
                prev!!.number == invoice.number -> invoice
                db.invoices().findNumber(invoice.number) == null -> invoice
                else -> invoice.copy(number = uniqueNumber(invoice.number, invoice.isSale))
            }
            if (isUpdate) {
                if (moveStock) {
                    // عكس أثر المخزون للبنود القديمة باتجاه الفاتورة القديمة — قبل حذفها
                    for (it in db.invoiceItems().forInvoice(invoice.id)) {
                        val pid = it.productId ?: continue
                        val delta = if (prev!!.isSale) it.qty else -it.qty   // عكس الأثر القديم
                        db.products().addQty(pid, delta)
                        db.stockMoves().insert(
                            StockMove(
                                productId = pid, qty = delta, reason = "ADJUST",
                                date = System.currentTimeMillis(), refType = "edit", refId = invoice.id,
                                note = "تعديل ${prev.number}"
                            )
                        )
                    }
                }
                // حذف قيود الفاتورة القديمة — قيود التحصيل/الدفعات (refType=payment) تبقى
                // سليمة: سجل السداد الحقيقي لا يُمحى عند تعديل الرأس
                db.journal().deleteLinesByRef("invoice", invoice.id)
                db.journal().deleteEntriesByRef("invoice", invoice.id)
            }
            // @Upsert في مسار التحديث يعيد -1 — نعوّض بمعرّف الفاتورة الصريح
            // وإلا انكسر تعديل الفاتورة (بنود تُدرج بمعرّف -1 وتيمة)
            // [Z2-أ V 1.5.0]: الختم قبل الإدراج (قراءة السلسلة + هوية جديدة) —
            // كل قراءاته وإسناده داخل هذه المعاملة نفسها فلا سباق عدّاد.
            val stamped = zatca?.stamp(withNumber, items, prev)
            val toInsert = stamped?.invoice ?: withNumber
            invId = db.invoices().upsert(toInsert)
                .let { if (it == -1L && toInsert.id != 0L) toInsert.id else it }
            db.invoiceItems().deleteForInvoice(invId)
            db.invoiceItems().insertAll(items.map { it.copy(invoiceId = invId) })
            // [Z2-أ V 1.5.0]: أرشفة مستند UBL — أول إصدار فقط (IGNORE) فلا
            // تعديل لاحق يعيد كتابة مرجع PIH للفواتير التالية
            stamped?.doc?.let { db.zatcaDocs().archiveFirst(it.copy(invoiceId = invId)) }

            if (moveStock) {
                for (it in items) {
                    val pid = it.productId ?: continue
                    val delta = if (withNumber.isSale) -it.qty else it.qty
                    db.products().addQty(pid, delta)
                    db.stockMoves().insert(
                        StockMove(
                            productId = pid, qty = delta,
                            reason = if (withNumber.isSale) "SALE" else "PURCHASE",
                            date = withNumber.date, refType = "invoice", refId = invId,
                            note = withNumber.number
                        )
                    )
                }
            }

            val draft = if (withNumber.isSale)
                AccountingEngine.saleInvoice(
                    withNumber.subtotal, withNumber.discount, withNumber.taxAmount, withNumber.total,
                    withNumber.costTotal, withNumber.partyId, withNumber.date, invId
                )
            else AccountingEngine.purchaseInvoice(
                withNumber.subtotal, withNumber.discount, withNumber.taxAmount, withNumber.total,
                withNumber.partyId, withNumber.date, invId
            )
            require(draft.balanced)
            postJournal(draft)

            // التحصيل الفوري داخل نفس المعاملة — قيد السند + صف الدفعة + تحديث الحالة
            // [P33-P8] مقارنة قروش صحيحة تامة
            if (collectCashDirection != null && withNumber.total > 0L) {
                val cashDraft = if (collectCashDirection == 0)
                    AccountingEngine.receipt(withNumber.partyId, withNumber.total, withNumber.date, invId)
                else
                    AccountingEngine.supplierPayment(withNumber.partyId, withNumber.total, withNumber.date, invId)
                require(cashDraft.balanced)
                postJournal(cashDraft)
                db.payments().insert(
                    com.superbiz.app.data.db.Payment(
                        partyId = withNumber.partyId, invoiceId = invId,
                        amount = withNumber.total, date = withNumber.date,
                        direction = collectCashDirection, method = "CASH", note = "POS"
                    )
                )
                db.invoices().updatePaid(invId, withNumber.total, 2)
            }
            // [P46-W1] الولاء والكوبون داخل نفس المعاملة — بعد اكتمال القيد والتحصيل:
            // الكسب ثم الاستبدال ثم الاستهلاك — وأي require هنا يرجع الفاتورة كلها
            // (بنوداً وقيوداً ومخزوناً ونقاطاً) إلى ما قبل المعاملة.
            if (loyalty != null && (loyaltyEarn > 0L || loyaltyRedeem > 0L || couponId > 0L)) {
                val note = withNumber.number
                loyalty.earnInTransaction(withNumber.partyId, loyaltyEarn, invId, note)
                loyalty.redeemInTransaction(withNumber.partyId, loyaltyRedeem, invId, note)
                if (couponId > 0L) loyalty.consumeCouponInTransaction(couponId, withNumber.date)
            }
        }
        onMutate?.invoke()
        onInvoiceCreated?.invoke(invId)
        return invId
    }

    /** يضمن تفرد رقم الفاتورة: إن وُجد الرقم يُولّد التالي المتاح —
     *  [P6-M5 إصلاح]: تُستدعى حصراً داخل withTransaction في save (انظر الوسم هناك) —
     *  countByType وfindNumber والإدراج داخل نفس معاملة الكتابة لا سباق فيها */
    private suspend fun uniqueNumber(base: String, isSale: Boolean): String {
        var candidate = base
        var n = db.invoices().countByType(if (isSale) 0 else 1) + 1
        var guard = 0
        while (db.invoices().findNumber(candidate) != null && guard < 10_000) {
            candidate = (if (isSale) "INV-" else "PUR-") + n.toString().padStart(4, '0')
            n++
            guard++
        }
        return candidate
    }

    private suspend fun postJournal(draft: com.superbiz.app.domain.JournalDraft): Long {
        require(draft.balanced)
        val entryId = db.journal().insertEntry(
            com.superbiz.app.data.db.JournalEntry(
                date = draft.date, memo = draft.memo,
                refType = draft.refType, refId = draft.refId
            )
        )
        db.journal().insertLines(draft.lines.map {
            // [P33-P8] قروش صحيحة — القيد يُخزَّن كما هو بلا round2
            JournalLine(
                entryId = entryId, account = it.account,
                debit = it.debit,
                credit = it.credit,
                partyId = it.partyId
            )
        })
        return entryId
    }

    /** حذف (إلغاء) فاتورة: عكس القيد + عكس المخزون — ذرّي بالكامل */
    suspend fun voidInvoice(inv: Invoice) {
        db.withTransaction {
            // حرس إعادة الإلغاء — قراءة حديثة داخل المعاملة؛
            // فاتورة ملغاة سابقاً أو غير موجودة لا تُعكس مرتين (كان الإلغاء
            // المزدوج يعكس المخزون مرتين فيضخم الرصيد)
            val fresh = db.invoices().byId(inv.id) ?: return@withTransaction
            if (fresh.status >= 3) return@withTransaction
            db.invoices().voidInvoice(fresh.id)
            db.journal().deleteLinesByRef("invoice", fresh.id)
            db.journal().deleteEntriesByRef("invoice", fresh.id)
            // إلغاء فاتورة مدفوعة كان يترك قيد سند التحصيل (refType=payment)
            // وصفوف الدفعات حية — تضخّم النقد وتُبقي الذمم سالبة. تُعكس الآن داخل نفس المعاملة.
            db.journal().deleteLinesByRef("payment", fresh.id)
            db.journal().deleteEntriesByRef("payment", fresh.id)
            db.payments().deleteByInvoiceId(fresh.id)
            // [Z2-أ V 1.5.0]: الفاتورة الملغاة تخرج من قائمة الربط (zatcaStatus=0)
            // — الهوية والأرشيف يبقيان (تدقيق)؛ تصحيح الامتثال الكامل بمستند دائن موجة قادمة
            db.invoices().updateZatcaStatus(fresh.id, 0)
            // [P46-W1] عكس أثر نقاط الفاتورة الملغاة — صف تعويض reason=VOID داخل نفس
            // المعاملة: إلغاء بيع كسب نقاطاً يسترجعها، وإلغاء بيع استبدل نقاطاً يعيدها —
            // فلا نقاط أشباح تُكسب بإلغاء وإعادة إنشاء الفاتورة نفسها
            loyalty?.reverseInvoiceInTransaction(fresh.id, "إلغاء ${fresh.number}")
            val items = db.invoiceItems().forInvoice(fresh.id)
            for (it in items) {
                val pid = it.productId ?: continue
                val delta = if (fresh.isSale) it.qty else -it.qty   // عكس الأثر
                db.products().addQty(pid, delta)
                db.stockMoves().insert(
                    StockMove(productId = pid, qty = delta, reason = "ADJUST",
                        date = System.currentTimeMillis(), refType = "void", refId = fresh.id,
                        note = "إلغاء ${fresh.number}")
                )
            }
        }
        onMutate?.invoke()
    }

    /** ترقيم تلقائي حقيقي حسب النوع: INV-0001 / PUR-0001 — يُعاد ضمان التفرد عند الحفظ */
    suspend fun nextNumber(isSale: Boolean): String {
        val n = db.invoices().countByType(if (isSale) 0 else 1) + 1
        return (if (isSale) "INV-" else "PUR-") + n.toString().padStart(4, '0')
    }

    suspend fun fullInvoice(id: Long): Pair<Invoice, List<InvoiceItem>>? {
        val inv = invoice(id) ?: return null
        return inv to items(id)
    }

    // [P33-P8] الأسعار قروش — الإرجاع Long
    fun productPriceSuggestion(p: Product, isSale: Boolean): Long =
        if (isSale) p.salePrice else p.costPrice
}
