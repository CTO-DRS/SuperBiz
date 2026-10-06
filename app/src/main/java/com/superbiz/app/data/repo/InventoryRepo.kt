package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.StockMove
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.JournalDraft
import com.superbiz.app.domain.Line
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

/**
 * مستودع المخزون — : حركة المخزون (الكمية + سجل الحركة + القيد) ذرّية واحدة،
 * والترحيل المحاسبي عبر LedgerRepo الموحد (إزالة التكرار الثلاثي لمنطق القيود).
*/
class InventoryRepo(private val db: AppDatabase, private val ledger: LedgerRepo) {

    /** يُستدعى بعد أي حركة مخزون أو تعديل منتج — لتجديد ويدجت المخزون المنخفض فوراً */
    var onMutate: (() -> Unit)? = null

    fun products(): Flow<List<Product>> = db.products().all()

    // [P5-H10 إصلاح]: قائمة كاملة تشمل المؤرشف — لرقاقة «المؤرشفة» في شاشة المخزون
    fun productsIncludingArchived(): Flow<List<Product>> = db.products().allIncludingArchived()

    // [P5-H10 إصلاح]: أرشفة/استرجاع صريحان — كان archive() يستدعى من حذف بلا واجهة
    // والمؤرشف بلا أي سبيل للاسترجاع
    suspend fun setArchived(id: Long, value: Boolean) {
        val p = db.products().byId(id) ?: return
        if (p.archived == value) return
        db.products().upsert(p.copy(archived = value))
        onMutate?.invoke()
    }
    suspend fun product(id: Long): Product? = db.products().byId(id)
    suspend fun saveProduct(p: Product): Long {
        // رفض الباركود المكرر — كان يُقبل بلا فحص فيلتقط POS أول مطابقة
        // LIMIT 1 فيُباع منتج غير المقصود. الباركود الفارغ مستثنى (غير مفهرس للبحث).
        if (p.barcode.isNotBlank()) {
            val clash = db.products().byBarcodeExcluding(p.barcode, p.id)
            require(clash == null) { "barcode already used by product ${clash!!.id}" }
        }
        // @Upsert في مسار التحديث يعيد -1 — نجيب معرّف الكيان الصريح حفاظاً على
        // دلالة الإرجاع عند التعديل (كان REPLACE يعيد rowId جديداً في الحالتين)
        val id = db.products().upsert(p)
        onMutate?.invoke()
        return if (id == -1L && p.id != 0L) p.id else id
    }

    suspend fun deleteProduct(id: Long) {
        db.products().archive(id)
        onMutate?.invoke()
    }
    suspend fun byBarcode(bc: String): Product? = db.products().byBarcode(bc)

    fun moves(productId: Long): Flow<List<StockMove>> = db.stockMoves().forProduct(productId)

    /** إدخال/إخراج/تسوية مخزون مع قيد — الكمية والحركة والقيد معاً ذرّياً */
    suspend fun moveStock(
        product: Product, qty: Double, reason: String,
        date: Long, note: String, postJournal: Boolean
    ): Long {
        // رفض الكميات غير المنتهية — NaN/∞ عبر addQty كانت تُفسد
        // stockQty نهائياً بلا أثر يمكن تتبّعه في حركات المخزون
        require(qty.isFinite()) { "qty must be finite" }
        // كمية ≤0 في PURCHASE/SALE كانت تُسجَّل حركة سالبة وتتخطى القيد
        // (عتبة qty>0 في الترحيل) فينحرف المخزون عن حساب المخزون. ADJUST يبقى حرّاً بالإشارة.
        if (reason == "PURCHASE" || reason == "SALE") require(qty > 0.0) { "PURCHASE/SALE qty must be > 0" }
        var moveId = 0L
        db.withTransaction {
            db.products().addQty(product.id, qty)
            moveId = db.stockMoves().insert(
                StockMove(productId = product.id, qty = qty, reason = reason,
                    date = date, refType = "stock", refId = product.id, note = note)
            )
            // كانت الحركات اليدوية غير الشرائية بلا قيد أصلاً — بيع يدوي
            // يُنقص المخزون بلا COGS، ومخزون افتتاحي/تسوية جرد تُغيّر قيمة المخزون الفعلية
            // بلا مقابل في الحساب 1200 فيتناقض ميزان المراجعة مع قيمة المخزون. الآن كل
            // مسار يُقيَّد بمقابله الصحيح (المبيعات من الفواتير تمر بمسارها الخاص فلا ازدواج).
            if (postJournal && qty != 0.0) {
                // [P33-P8] cost قروش: Math.round(qty × costPrice) — بلا round2
                val cost = Math.round(kotlin.math.abs(qty) * product.costPrice)
                if (cost > 0L) {
                    val draft = when {
                        reason == "PURCHASE" && qty > 0.0 -> JournalDraft(
                            date, "توريد مخزون: ${product.name}", "stock", moveId,
                            listOf(
                                Line(Accounts.INVENTORY, debit = cost),
                                Line(Accounts.CASH, credit = cost)
                            )
                        )
                        reason == "SALE" && qty < 0.0 -> JournalDraft(
                            date, "صرف يدوي من المخزون: ${product.name}", "stock", moveId,
                            listOf(
                                Line(Accounts.COGS, debit = cost),
                                Line(Accounts.INVENTORY, credit = cost)
                            )
                        )
                        reason == "ADJUST" && qty > 0.0 -> JournalDraft(
                            // إدخال جرد/مخزون افتتاحي: مساهمة مالك — لا نقدي
                            date, "إدخال جرد: ${product.name}", "stock", moveId,
                            listOf(
                                Line(Accounts.INVENTORY, debit = cost),
                                Line(Accounts.EQUITY, credit = cost)
                            )
                        )
                        reason == "ADJUST" && qty < 0.0 -> JournalDraft(
                            date, "عجز جرد: ${product.name}", "stock", moveId,
                            listOf(
                                Line(Accounts.EXPENSE, debit = cost),
                                Line(Accounts.INVENTORY, credit = cost)
                            )
                        )
                        else -> null
                    }
                    if (draft != null) ledger.postInternal(draft)
                }
            }
        }
        onMutate?.invoke()
        return moveId
    }

    // [P33-P8] قيمة المخزون قروش Long — Product.stockValue قروش محسوبة
    suspend fun stockValue(): Long =
        db.products().allOnce().sumOf { it.stockValue }

    /**
 * : استبعاد المؤرشف — كان isLow يفحص الحد والرصيد فقط، فبقى
 * المنتج المؤرشف (الذي أرشفه المستخدم عمداً) يظهر في تنبيهات الأتمتة وفي عدّ
 * الصفحة الرئيسية للأبد، بينما ويدجت المخزون يفلتره صراحة (StockMath.low) —
 * دلالتان متناقضتان لنفس المفهوم؛ تُوحِّدان الآن على استبعاد المؤرشف.
*/
    suspend fun lowStock(): List<Product> =
        db.products().allOnce().filter { !it.archived && it.isLow }

    // ═══ : وظيفة 20 — منتجات بلا بيع إطلاقاً ═══

    /**
     * منتجات بلا أي سطر فاتورة في التاريخ كله (مختلفة عن «الراكد» 30 يوماً).
     * من استعلام DAO إضافي واحد — بلا أي تعديل على المخطط أو الإصدار.
     */
    suspend fun neverSoldProducts(): List<Product> {
        val soldIds = db.invoiceItems().distinctSoldProductIds().toSet()
        return db.products().allOnce().filter { it.id !in soldIds }
    }
}
