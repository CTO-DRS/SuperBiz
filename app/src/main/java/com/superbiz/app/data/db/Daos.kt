package com.superbiz.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PartyDao {
    @Query("SELECT * FROM parties WHERE archived = 0 ORDER BY name")
    fun all(): Flow<List<Party>>

    @Query("SELECT * FROM parties WHERE archived = 0 ORDER BY name")
    suspend fun allOnce(): List<Party>

    /**للنسخ الاحتياطي — يشمل المؤرشف كي لا تُفقد الصفوف المؤرشفة عند الاستعادة */
    @Query("SELECT * FROM parties ORDER BY id")
    suspend fun exportOnce(): List<Party>

    @Query("SELECT * FROM parties WHERE id = :id")
    suspend fun byId(id: Long): Party?

    @Query("SELECT * FROM parties WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Party?>

    // (M-3.3): كانت upsert بـ REPLACE (= حذف + إدراج) — مع المفاتيح الأجنبية (RESTRICT)
    // كان كل تعديل طرف سيكسر القيود. Upsert يُحدّث بلا حذف فلا مفاتيح أجنبية تنكسر.
    @Upsert
    suspend fun upsert(p: Party): Long

    /**إدراج جماعي للاستعادة */
    @Upsert
    suspend fun upsertAll(list: List<Party>)

    @Query("UPDATE parties SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    /**حذف فعلي بعد دمج الطرف المكرر — تُنقل مستنداته أولاً بمعاملة واحدة */
    @Query("DELETE FROM parties WHERE id = :id")
    suspend fun deleteRow(id: Long)
    // [تدقيق L-1] delete(id) المكررة حرفياً لdeleteRow صفر مستدعين — حُذفت

    @Query("SELECT COUNT(*) FROM parties")
    suspend fun count(): Int

    // ─── [P11-a] المفضّلون وبطاقة الموقع الجغرافي ───

    /** [P11-a] المفضّلون غير المؤرشفين مرتّبين بالاسم — تدفق حي يُحدّث مع كل تعديل */
    @Query("SELECT * FROM parties WHERE favorite = 1 AND archived = 0 ORDER BY name")
    fun favorites(): Flow<List<Party>>

    /** [P11-a] تفعيل/إلغاء تمييز الطرف كمفضّل (تحديث موضعي بلا لمس بقية الأعمدة) */
    @Query("UPDATE parties SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    /** [P11-a] حفظ إحداثيات الموقع — تمرير null للاثنين يزيل البطاقة (إزالة الموقع) */
    @Query("UPDATE parties SET lat = :lat, lng = :lng WHERE id = :id")
    suspend fun setLocation(id: Long, lat: Double?, lng: Double?)
}

@Dao
interface JournalDao {
    @Insert
    suspend fun insertEntry(e: JournalEntry): Long

    @Insert
    suspend fun insertLines(l: List<JournalLine>)

    @Query(
        """DELETE FROM journal_lines WHERE entryId IN
           (SELECT id FROM journal WHERE refType = :refType AND refId = :refId)"""
    )
    suspend fun deleteLinesByRef(refType: String, refId: Long)

    @Query("DELETE FROM journal WHERE refType = :refType AND refId = :refId")
    suspend fun deleteEntriesByRef(refType: String, refId: Long)

    @Query(
        """SELECT l.account AS account, SUM(l.debit) AS d, SUM(l.credit) AS c
           FROM journal_lines l GROUP BY l.account"""
    )
    suspend fun accountSums(): List<AccountSum>

    @Query(
        """SELECT l.account AS account, SUM(l.debit) AS d, SUM(l.credit) AS c
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE e.date >= :from AND e.date <= :to GROUP BY l.account"""
    )
    suspend fun accountSumsBetween(from: Long, to: Long): List<AccountSum>

    @Query(
        """SELECT l.* FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.partyId = :partyId ORDER BY e.date"""
    )
    suspend fun linesForParty(partyId: Long): List<JournalLine>

    @Query(
        """SELECT e.date AS date, e.memo AS memo, l.debit AS debit, l.credit AS credit,
                  l.partyId AS partyId, e.refType AS refType, e.refId AS refId
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.partyId = :partyId ORDER BY e.date, e.id"""
    )
    suspend fun partyRows(partyId: Long): List<PartyJournalRow>

    @Query(
        """SELECT e.date AS date, l.debit AS debit, l.credit AS credit
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.account = :account AND e.date BETWEEN :from AND :to"""
    )
    suspend fun accountRowsBetween(account: String, from: Long, to: Long): List<AccountDateRow>

    // [P6-M8 إصلاح]: صفوف حساب مع حصة السداد النقدي لكل قيد — يفصل المصروف غير النقدي
    // (عجز الجرد EXPENSE/INVENTORY، وخصم السداد المبكر لخطة عميل EXPENSE/RECEIVABLE)
    // عن النقدي فعلاً (صرف نقدي EXPENSE/CASH) بفحص الطرف الدائن في القيد نفسه.
    // استعلام إضافي توازي فقط — بلا أي تعديل على مخطط Room أو إصداره.
    @Query(
        """SELECT e.date AS date, l.debit AS debit,
                  COALESCE((SELECT SUM(cl.credit) FROM journal_lines cl
                            WHERE cl.entryId = l.entryId AND cl.account = :cashAccount), 0) AS cashCredit
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.account = :account AND e.date BETWEEN :from AND :to"""
    )
    suspend fun accountRowsBetweenCashSettled(account: String, cashAccount: String, from: Long, to: Long): List<CashSettledRow>

    @Query("SELECT COUNT(*) FROM journal")
    suspend fun count(): Int

    /**رصيد كل الأطراف في استعلام واحد — موجب = يدين لك، سالب = أنت مدين له */
    @Query(
        """SELECT partyId AS pid, SUM(debit - credit) AS balance
           FROM journal_lines WHERE partyId IS NOT NULL GROUP BY partyId"""
    )
    suspend fun partyBalances(): List<PartyBalance>

    /**نقل أسطر دفتر طرف مكرر إلى الطرف الموحَّد — الرصيد لا يضيع في الدمج */
    @Query("UPDATE journal_lines SET partyId = :toId WHERE partyId = :fromId")
    suspend fun movePartyLines(fromId: Long, toId: Long)

    @Query("SELECT * FROM journal ORDER BY date")
    suspend fun allEntries(): List<JournalEntry>

    @Query("SELECT * FROM journal_lines")
    suspend fun allLines(): List<JournalLine>

    // ─── [P17-a] كشف الحساب PDF — استعلامات نافذة زمنية لكل طرف ───

    /**
     * [P17-a] أسطر دفتر طرف خام بين تاريخين (شاملان) — لقراءة سطرية بلا JOIN عند الحاجة.
     * ملاحظة صادقة: النافذة على journal.date (تاريخ القيد) لا على لحق وقت الإدراج،
     * مطابقة لدلالة partyRows التاريخية في هذا الملف.
     */
    @Query(
        """SELECT l.* FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.partyId = :partyId AND e.date >= :from AND e.date <= :to
           ORDER BY e.date, e.id"""
    )
    suspend fun partyLinesBetween(partyId: Long, from: Long, to: Long): List<JournalLine>

    /**
     * [P17-a] رصيد افتتاحي لطرف قبل لحظة معينة — SUM(debit-credit) على ما قبل fromTs.
     * هو الرصيد الذي يبدأ منه تراكم سطور كشف الحساب (opening في StatementService.summarize).
     */
    @Query(
        """SELECT COALESCE(SUM(l.debit - l.credit), 0)
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.partyId = :partyId AND e.date < :beforeTs"""
    )
    suspend fun partyOpeningBalance(partyId: Long, beforeTs: Long): Long  // [P33-P8] قروش

    /**
     * [P17-a] سطور كشف طرف بين تاريخين مع وسم النوع من القيد نفسه.
     *
     * اكتشاف بنيوي مهم (موثق لغرض 17-b/17-c): جدول journal **لا يملك عمود type** —
     * ما فيه عمود refType نصي بأحرف صغيرة يحدد مصدر القيد (AccountingEngine):
     * "invoice"/"payment"/"debt"/"check"/"plan"/"tax"/"cash"/"expense"/"stock"/"edit"/"void".
     * لذلك النوع يُقرأ من refType هنا، والتحويل إلى typeKey الكشف (INVOICE/PAYMENT/DEBT/
     * CHECK/INSTALLMENT/ADJUST) يجري في repo (StatementRepo.typeKeyOf) لا في SQL —
     * كي يبقى الجدول المنطقي موحداً وقابلاً للاختبار على JVM.
     */
    @Query(
        """SELECT e.date AS ts, e.memo AS memo, e.refType AS refType, e.refId AS refId,
                  l.debit AS debit, l.credit AS credit
           FROM journal_lines l JOIN journal e ON e.id = l.entryId
           WHERE l.partyId = :partyId AND e.date >= :from AND e.date <= :to
           ORDER BY e.date, e.id"""
    )
    suspend fun partyRowsBetween(partyId: Long, from: Long, to: Long): List<PartyStatementRow>
}

data class PartyBalance(val pid: Long, val balance: Long)  // [P33-P8] قروش

data class AccountDateRow(val date: Long, val debit: Long, val credit: Long)  // [P33-P8] قروش

/** [P6-M8 إصلاح]: صف حساب مع ما قوبل نقداً من قيده — cashCredit = مجموع CASH الدائن في القيد */
data class CashSettledRow(val date: Long, val debit: Long, val cashCredit: Long)  // [P33-P8] قروش

data class AccountSum(val account: String, val d: Long, val c: Long)  // [P33-P8] قروش

data class PartyJournalRow(
    val date: Long,
    val memo: String,
    val debit: Long,   // [P33-P8] قروش
    val credit: Long,
    val partyId: Long?,
    val refType: String?,
    val refId: Long?
)

/** [P17-a] سطر كشف حساب — نتيجة partyRowsBetween المدمجة مع journal */
data class PartyStatementRow(
    val ts: Long,
    val memo: String,
    val refType: String?,
    val refId: Long?,
    val debit: Long,   // [P33-P8] قروش
    val credit: Long
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE archived = 0 ORDER BY name")
    fun all(): Flow<List<Product>>

    // [P5-H10 إصلاح]: كان لا سبيل لرؤية المؤرشف — حذف المنتج (أرشفة ناعمة) نهائي
    // بلا استرجاع، والقائمة تخفي المؤرشف بلا أي واجهة تعرضه. استعلام كامل لشاشة
    // المخزون عند تفعيل رقاقة «المؤرشفة» — استعلام إضافي بلا أي تغيير في المخطط
    @Query("SELECT * FROM products ORDER BY name")
    fun allIncludingArchived(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE archived = 0 ORDER BY name")
    suspend fun allOnce(): List<Product>

    /**
     * [P37-فحص-الصحة]: يشمل المؤرشف — فحص «مخزون غير مرئي» (arch_stock) في
     * DataHealthVM كان ميتاً لأنه يقرأ allOnce الذي يستثني المؤرشف (`archived = 0`)
     * فيصفّر العداد دائماً (اكتشفه جدار اختبارات P37 على فحص حقيقي).
     */
    @Query("SELECT * FROM products ORDER BY name")
    suspend fun allOnceIncludingArchived(): List<Product>

    /**للنسخ الاحتياطي — يشمل المؤرشف كي لا تُفقد الصفوف المؤرشفة عند الاستعادة */
    @Query("SELECT * FROM products ORDER BY id")
    suspend fun exportOnce(): List<Product>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun byId(id: Long): Product?

    @Query("SELECT * FROM products WHERE barcode = :bc LIMIT 1")
    suspend fun byBarcode(bc: String): Product?

    // فحص تكرار الباركود على منتج آخر — استعلام إضافي بلا أي تغيير في المخطط
    @Query("SELECT * FROM products WHERE barcode = :bc AND id != :excludeId LIMIT 1")
    suspend fun byBarcodeExcluding(bc: String, excludeId: Long): Product?

    @Upsert
    suspend fun upsert(p: Product): Long

    /**إدراج جماعي للاستعادة */
    @Upsert
    suspend fun upsertAll(list: List<Product>)

    @Query("UPDATE products SET stockQty = stockQty + :delta WHERE id = :id")
    suspend fun addQty(id: Long, delta: Double)

    @Query("UPDATE products SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StockMoveDao {
    @Insert
    suspend fun insert(m: StockMove): Long

    @Query("SELECT * FROM stock_moves WHERE productId = :pid ORDER BY date DESC LIMIT 100")
    fun forProduct(pid: Long): Flow<List<StockMove>>

    /**للنسخ الاحتياطي — كل حركات المخزون (كان الجدول بلا مسار تصدير/استعادة) */
    @Query("SELECT * FROM stock_moves ORDER BY id")
    suspend fun allMoves(): List<StockMove>

    @Query("DELETE FROM stock_moves WHERE refType = :refType AND refId = :refId")
    suspend fun deleteByRef(refType: String, refId: Long)
}

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices WHERE status < 3 ORDER BY date DESC")
    fun all(): Flow<List<Invoice>>

    // القوائم تشمل الملغاة لفلتر حالة «ملغاة» صادق في شاشة الفواتير
    // (استعلام إضافي فقط — بلا أي تعديل على مخطط Room أو إصداره)
    @Query("SELECT * FROM invoices ORDER BY date DESC")
    fun allIncludingVoid(): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE status < 3 ORDER BY date DESC")
    suspend fun allOnce(): List<Invoice>

    /**للنسخ الاحتياطي — تصدير كل الفواتير بلا فلترة الحالة (تشمل الملغاة) */
    @Query("SELECT * FROM invoices ORDER BY id")
    suspend fun exportOnce(): List<Invoice>

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun byId(id: Long): Invoice?

    @Query("SELECT * FROM invoices WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Invoice?>

    @Upsert
    suspend fun upsert(i: Invoice): Long

    @Query("UPDATE invoices SET paid = :paid, status = :status WHERE id = :id")
    suspend fun updatePaid(id: Long, paid: Long, status: Int)  // [P33-P8] قروش

    /**الإلغاء يصفّر المتبقي أيضاً — الفاتورة الملغاة لا تبقى «مستحقة» في أي مسار */
    // إصلاح خطأ ترجمة: «open» خاصية محسوبة في الكيان (open = total - paid)
    // وليست عموداً في الجدول — كان Room يرفض الاستعلام وقت الترجمة
    // ([SQLITE_ERROR] no such column: open). نحقق «open = 0» بصيغة SQL صالحة:
    // paid = total  →  open = total - paid = 0.
    /**نقل فواتير طرف مكرر إلى الطرف الموحَّد (دمج) */
    @Query("UPDATE invoices SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)

    @Query("UPDATE invoices SET status = 3, paid = total WHERE id = :id")
    suspend fun voidInvoice(id: Long)

    @Query("SELECT * FROM invoices WHERE partyId = :pid AND status < 3 ORDER BY date")
    suspend fun forParty(pid: Long): List<Invoice>

    // فواتير بيع نشطة منذ تاريخ — بديل قراءة الجدول كاملاً في التحليلات
    // (استعلام إضافي فقط — بلا أي تعديل على مخطط Room أو إصداره)
    @Query("SELECT * FROM invoices WHERE type = 0 AND status < 3 AND date >= :since")
    suspend fun saleInvoicesSince(since: Long): List<Invoice>

    // [P6-M10 إصلاح]: فواتير البيع النشطة كلها (بلا نافذة زمنية) — بديل قراءة الجدول كاملاً
    // (allOnce + فلتر isSale) في دوال بلا حدود تاريخية كأعمار الذمم والتوقع. استعلام
    // إضافي توازي فقط — بلا أي تعديل على أعمدة المخطط، والفلتر type = 0 status < 3
    // مكافئ حرفياً للفلترة اليدوية المستبدلة فتُحفظ الدلالة الحسابية لكل دالة.
    @Query("SELECT * FROM invoices WHERE type = 0 AND status < 3 ORDER BY date DESC")
    suspend fun saleInvoicesOnce(): List<Invoice>

    // [P38-Z3] فواتير الشراء النشطة منذ تاريخ — نافذة تقرير الضريبة الدوري
    // (استعلام إضافي توازي فقط — بلا أي تعديل على مخطط Room أو إصداره، وبنمط
    // saleInvoicesSince نفسه: الفلتر type = 1 status < 3 بديل مباشر لمسح الجدول)
    @Query("SELECT * FROM invoices WHERE type = 1 AND status < 3 AND date >= :since")
    suspend fun purchaseInvoicesSince(since: Long): List<Invoice>

    // [P41-L2] بنود فواتير البيع النشطة داخل نافذة — التصنيف الضريبي لكل سطر
    // (S/Z/E) مصدره السطر نفسه منذ v11، فالتقرير الدوري يبني صفوفه من البنود
    // للفواتير الواعية بالسطر — نفس فلاتر saleInvoicesSince حرفياً (type = 0
    // status < 3 والنافذة date >= since AND date <= to) بترتيب مستقر للتجميع
    @Query(
        "SELECT ii.* FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId " +
            "WHERE e.type = 0 AND e.status < 3 AND e.date >= :since AND e.date <= :to " +
            "ORDER BY ii.invoiceId, ii.id"
    )
    suspend fun saleItemsBetween(since: Long, to: Long): List<InvoiceItem>

    // [P43-D1] جولة 4 — بنود فواتير الشراء النشطة داخل نافذة الدفتر التفصيلي —
    // مرآة saleItemsBetween حرفياً بفلتر type = 1 (نمط P38-Z3: استعلام توازي
    // فقط بلا أي تعديل على مخطط Room أو إصداره، وترتيب مستقر للتجميع)
    @Query(
        "SELECT ii.* FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId " +
            "WHERE e.type = 1 AND e.status < 3 AND e.date >= :since AND e.date <= :to " +
            "ORDER BY ii.invoiceId, ii.id"
    )
    suspend fun purchaseItemsBetween(since: Long, to: Long): List<InvoiceItem>

    @Query("SELECT COUNT(*) FROM invoices")
    suspend fun count(): Int

    /**ترقيم حقيقي — عدّ حسب النوع كي لا يتضارب INV/PUR */
    @Query("SELECT COUNT(*) FROM invoices WHERE type = :type")
    suspend fun countByType(type: Int): Int

    /**فحص تضارب الرقم قبل الحفظ */
    @Query("SELECT number FROM invoices WHERE number = :number LIMIT 1")
    suspend fun findNumber(number: String): String?

    // ─── [P17-a] كشف الحساب PDF ───

    /** [P17-a] فواتير طرف (غير الملغاة) بين تاريخين — ملخص الكشف يحسب منها الأجماليات */
    @Query(
        """SELECT * FROM invoices WHERE partyId = :pid AND status < 3
           AND date >= :from AND date <= :to ORDER BY date, id"""
    )
    suspend fun forPartyBetween(pid: Long, from: Long, to: Long): List<Invoice>

    /**
     * [P17-a] مجموع خصومات فواتير الطرف غير الملغاة بين تاريخين — استعلام مجمّع واحد
     * (بلا فلتر type: طرف «كلاهما» قد يجمع خصم بيعاً وشراءً في كشف واحد صادق).
     * يجري تصحيحه بـCOALESCE حتى الفترة الخالية تعيد 0.0 لا NULL.
     */
    @Query(
        """SELECT COALESCE(SUM(discount), 0) FROM invoices
           WHERE partyId = :pid AND status < 3 AND date >= :from AND date <= :to"""
    )
    suspend fun discountSumForPartyBetween(pid: Long, from: Long, to: Long): Long  // [P33-P8] قروش

    // ─── [Z2-أ V 1.5.0] سلسلة ZATCA-2 ولوحة الحالات ───

    /**
     * أعلى ICV مختوم — السلسلة تتبع الإصدار (icv > 0 = مختومة)؛
     * يُستدعى حصراً داخل معاملة الحفظ (نمط P6-M5: معاملات الكتابة متسلسلة
     * فلا عدّاد مكرر بين مستندين متزامنين).
     */
    @Query("SELECT MAX(icv) FROM invoices")
    suspend fun maxIcv(): Long?

    /** فواتير بانتظار الإبلاغ/التخليص بترتيب السلسلة — غذاء قائمة الانتظار */
    @Query("SELECT * FROM invoices WHERE zatcaStatus = 1 ORDER BY icv")
    suspend fun pendingForReport(): List<Invoice>

    /** عدّاد حالات الربط للوحة ZATCA (1 بالقائمة / 2 مبلغة / 3 مرفوضة) */
    @Query(
        """SELECT zatcaStatus AS state, COUNT(*) AS cnt FROM invoices
           WHERE zatcaStatus > 0 GROUP BY zatcaStatus"""
    )
    suspend fun zatcaStatusCounts(): List<ZatcaStatusCount>

    /** مبسطة مضت نافذتها القانونية دون إبلاغ — إنذار لوحة ZATCA (O1) */
    @Query(
        """SELECT COUNT(*) FROM invoices
           WHERE zatcaStatus = 1 AND zatcaSubtype = '0200000' AND date < :cutoff"""
    )
    suspend fun lateSimplifiedCount(cutoff: Long): Int

    /** تحديث حالة الربط بعد نتيجة القائمة (2 مبلغة/مخلصة — 3 مرفوضة — 1 عودة للقائمة) */
    @Query("UPDATE invoices SET zatcaStatus = :status WHERE id = :id")
    suspend fun updateZatcaStatus(id: Long, status: Int)

    /** صف عدّ مجمّع — POJO مطابق لأسماء الأعمدة (عقد Room) */
    data class ZatcaStatusCount(val state: Int, val cnt: Int)
}

@Dao
interface InvoiceItemDao {
    @Insert
    suspend fun insertAll(items: List<InvoiceItem>)

    // [P13-a] read-all for backup (P13-b)
    @Query("SELECT * FROM invoice_items")
    suspend fun allOnce(): List<InvoiceItem>

    @Query("SELECT * FROM invoice_items WHERE invoiceId = :iid")
    suspend fun forInvoice(iid: Long): List<InvoiceItem>

    @Query("DELETE FROM invoice_items WHERE invoiceId = :iid")
    suspend fun deleteForInvoice(iid: Long)

    // معرّفات المنتجات التي لها سطر فاتورة واحد على الأقل في التاريخ كله —
    // غذاء بطاقة «لم تُبع قط» (استعلام إضافي فقط — بلا أي تعديل على مخطط Room أو إصداره)
    // [P6-M9 إصلاح]: الدلالة كانت خاطئة — المسح القديم يشمل بنود فواتير الشراء فيُعد المنتج
    // «المُشترى ولم يُبع» مباعاً ويختفي من بطاقة «لم تُبع قط». الآن: بنود فواتير بيع نشطة
    // فقط (type = 0 و status < 3 — نفس عرف saleLinesSince/saleInvoicesSince).
    // المستدعان (InventoryRepo.neverSoldProducts و R12InsightsVM) يستهلكان نفس دلالة
    // «لم يُبع أبداً» فيتصلحان معاً بلا تغيير في مستودع/VM خارج الملكية.
    @Query(
        """SELECT DISTINCT ii.productId FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId
           WHERE ii.productId IS NOT NULL AND e.type = 0 AND e.status < 3"""
    )
    suspend fun distinctSoldProductIds(): List<Long>

    // أسطر بيع حقيقية منذ تاريخ — غذاء تحليلات الطلب اليومي
    // (مرونة السعر الأمثل، كروستون، مخزون الأمان، توازن الفئات)
    // (استعلام إضافي فقط — بلا أي تعديل على مخطط Room أو إصداره)
    @Query(
        """SELECT ii.productId AS productId, ii.qty AS qty, ii.unitPrice AS unitPrice,
                  e.date AS date, ii.invoiceId AS invoiceId
           FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId
           WHERE ii.productId IS NOT NULL AND e.status < 3 AND e.type = 0 AND e.date >= :since
           ORDER BY e.date"""
    )
    suspend fun saleLinesSince(since: Long): List<SaleLineRow>

    /**أسطر شراء حقيقية منذ تاريخ — غذاء تحليل زحف تكلفة الشراء
 * (استعلام إضافي توازي فقط — بلا أي تعديل على مخطط Room أو إصداره) */
    @Query(
        """SELECT ii.productId AS productId, ii.qty AS qty, ii.unitPrice AS unitPrice,
                  e.date AS date, ii.invoiceId AS invoiceId
           FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId
           WHERE ii.productId IS NOT NULL AND e.status < 3 AND e.type = 1 AND e.date >= :since
           ORDER BY e.date"""
    )
    suspend fun purchaseLinesSince(since: Long): List<SaleLineRow>

    /**أسطر بيع غنية بين تاريخين — استعلام واحد يستبدل حلقة
 * forInvoice-per-invoice في أربعة مواقع تقارير (N+1: 2000 فاتورة = 2000 استعلام).
 * (استعلام إضافي توازي فقط — بلا أي تعديل على مخطط Room أو إصداره) */
    @Query(
        """SELECT ii.productId AS productId, ii.desc AS `desc`, ii.qty AS qty,
                  ii.unitPrice AS unitPrice, ii.discount AS discount, ii.invoiceId AS invoiceId
           FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId
           WHERE e.status < 3 AND e.type = 0 AND e.date BETWEEN :from AND :to"""
    )
    suspend fun saleLinesBetween(from: Long, to: Long): List<SaleLineRichRow>

    /**أسطر البيع المخصومة مع قيمة الخصم — غذاء ميزة مرونة الخصم (بيرسون/سبيرمان)
 * (استعلام إضافي توازي فقط — بلا أي تعديل على مخطط Room أو إصداره) */
    @Query(
        """SELECT ii.productId AS productId, ii.qty AS qty, ii.unitPrice AS unitPrice,
                  e.date AS date, ii.invoiceId AS invoiceId, ii.discount AS discount
           FROM invoice_items ii JOIN invoices e ON e.id = ii.invoiceId
           WHERE ii.productId IS NOT NULL AND e.status < 3 AND e.type = 0 AND e.date >= :since
             AND ii.discount > 0
           ORDER BY e.date"""
    )
    suspend fun discountedSaleLinesSince(since: Long): List<DiscountedLineRow>
}

/** صف سطر بيع مخصوم مسطّح لتحليلات R13 */
data class DiscountedLineRow(
    val productId: Long,
    val qty: Double,
    val unitPrice: Long,   // [P33-P8] قروش
    val date: Long,
    val invoiceId: Long,
    val discount: Long
)

/** صف سطر بيع مسطّح لتحليلات R9 */
data class SaleLineRow(
    val productId: Long,
    val qty: Double,
    val unitPrice: Long,   // [P33-P8] قروش
    val date: Long,
    val invoiceId: Long
)

/**صف سطر بيع غني — يغذي تقارير أعلى المنتجات/الأرباح باستعلام واحد */
data class SaleLineRichRow(
    val productId: Long?,
    val desc: String,
    val qty: Double,
    val unitPrice: Long,   // [P33-P8] قروش
    val discount: Long,
    val invoiceId: Long
) {
    val lineTotal: Long get() = Math.round(qty * unitPrice) - discount
}

@Dao
interface PaymentDao {
    @Insert
    suspend fun insert(p: Payment): Long

    // [P13-a] read-all for backup (P13-b)
    @Query("SELECT * FROM payments")
    suspend fun allOnce(): List<Payment>

    /** (M-4.9): دفعات خطة تقسيط تُمسح مع خطتها — كانت تبقى في الخزنة بعد حذف الخطة */
    @Query("DELETE FROM payments WHERE planId = :planId")
    suspend fun deleteByPlanId(planId: Long)

    @Query("SELECT * FROM payments ORDER BY date DESC LIMIT 200")
    fun recent(): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE date >= :from ORDER BY date")
    suspend fun since(from: Long): List<Payment>

    @Query("SELECT * FROM payments WHERE partyId = :pid ORDER BY date")
    suspend fun forParty(pid: Long): List<Payment>

    // [تدقيق L-1] receivedBetween (Double — خلط وحدات) كانت ميتة صفر مستدعين —
    // كل المستهلكين على receivedBetweenCash (قروش Long) — حُذفت

    /**المقبوض النقدي للنافذة — يستبعد قيود «دين جديد» (DEBT)
 * لأنها ليست تدفقاً نقدياً (نفس مبرر totalReceived) — كان يُحتسب الدين الائتماني
 * دخلاً نقدياً في بطاقات الفجوة النقدية ومعدل الحرق */
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE direction = 0 AND method != 'DEBT' AND date BETWEEN :from AND :to")
    suspend fun receivedBetweenCash(from: Long, to: Long): Long  // [P33-P8] قروش

    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE direction = 1 AND date BETWEEN :from AND :to")
    suspend fun paidOutBetween(from: Long, to: Long): Long  // [P33-P8] قروش

    /**المقبوض النقدي الحقيقي — يستبعد قيود «دين جديد» (DEBT) لأنها ليست تدفقاً نقدياً */
    @Query("SELECT COALESCE(SUM(amount),0) FROM payments WHERE direction = 0 AND method != 'DEBT'")
    suspend fun totalReceived(): Long  // [P33-P8] قروش

    /**حذف دفعات الشيك عند حذف الشيك نفسه (CHECK / CHECK_BOUNCE) — لا دفعات يتيمة */
    @Query("DELETE FROM payments WHERE checkId = :checkId")
    suspend fun deleteByCheckId(checkId: Long)

    /**حذف دفعات فاتورة ملغاة — كان إلغاء فاتورة مدفوعة يترك صفوف الدفعات */
    @Query("DELETE FROM payments WHERE invoiceId = :invoiceId")
    suspend fun deleteByInvoiceId(invoiceId: Long)

    /**نقل دفعات طرف مكرر إلى الطرف الموحّد (دمج) */
    @Query("UPDATE payments SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)

    /**حذف دفعات خطة أقساط محذوفة (INSTALLMENT/INSTALLMENT_DOWN)
 * حسب الطرف والعنوان وهامش يوم واحد حول تاريخ الإنشاء — كانت تبقى أشباحاً
 * تضخّم التدفقات النقدية بعد حذف الخطة وعكس قيودها
 * : ESCAPE '\' يمنع % و _ من العمل حروف بدل، ومطابقة النهاية
 * '% — title' تمنع عنواناً بادئاً من يمسح دفعات خطة أخرى عنوانها امتداد له */
    // (M-3.8 توحيد): حُذفت deleteForPlan الاستدلالية — عمود payments.planId
    // (M-4.9) + deleteByPlanId يعوضانها بمطابقة دقيقة بلا LIKE ولا نوافذ زمنية

    // ─── [P17-a] كشف الحساب PDF ───

    /** [P17-a] دفعات طرف بين تاريخين (شاملان) — لملخص الكشف والتحقق العرضي؛
     *  سطور الحركة نفسها تُقرأ من الدفتر (partyRowsBetween) فلا تُجمع مرتين أبداً */
    @Query("SELECT * FROM payments WHERE partyId = :pid AND date >= :from AND date <= :to ORDER BY date, id")
    suspend fun forPartyBetween(pid: Long, from: Long, to: Long): List<Payment>
}

@Dao
interface CheckDao {
    @Query("SELECT * FROM checks ORDER BY dueDate")
    fun all(): Flow<List<CheckEntity>>
    @Query("SELECT * FROM checks WHERE id = :id")
    suspend fun byId(id: Long): CheckEntity?

    @Upsert
    suspend fun upsert(c: CheckEntity): Long

    @Query("UPDATE checks SET status = :st WHERE id = :id")
    suspend fun setStatus(id: Long, st: Int)

    @Query("SELECT * FROM checks WHERE status IN (0,1) AND dueDate BETWEEN :from AND :to")
    suspend fun dueBetween(from: Long, to: Long): List<CheckEntity>

    /**كل الشيكات بلا استثناء — للنسخ الاحتياطي الكامل (كانت تُفقد المحصّلة والمرتجعة!) */
    @Query("SELECT * FROM checks ORDER BY dueDate")
    suspend fun allOnce(): List<CheckEntity>

    @Query("DELETE FROM checks WHERE id = :id")
    suspend fun delete(id: Long)

    /**نقل شيكات طرف مكرر إلى الطرف الموحّد (دمج) —
 * كانت تبقى على الطرف المحذوف فتعطّل تحصيلها وتظهر في أرصدة وهمية */
    @Query("UPDATE checks SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)
}

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installment_plans WHERE archived = 0 ORDER BY createdAt DESC")
    fun plans(): Flow<List<InstallmentPlan>>

    @Query("SELECT * FROM installment_plans WHERE archived = 0 ORDER BY createdAt DESC")
    suspend fun plansOnce(): List<InstallmentPlan>

    /**للنسخ الاحتياطي — تصدير كل الخطط بلا فلترة الأرشفة (تشمل المؤرشفة) */
    @Query("SELECT * FROM installment_plans ORDER BY id")
    suspend fun plansExport(): List<InstallmentPlan>

    @Query("SELECT * FROM installment_plans WHERE id = :id")
    suspend fun planById(id: Long): InstallmentPlan?

    // (M-3.3): Upsert بدل REPLACE — تعديل خطة لا يحذفها فلا يكسر قيود أبنائها
    @Upsert
    suspend fun insertPlan(p: InstallmentPlan): Long

    @Query("UPDATE installment_plans SET archived = 1 WHERE id = :id")
    suspend fun archivePlan(id: Long)

    @Query("DELETE FROM installment_plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT COUNT(*) FROM installment_plans")
    suspend fun planCount(): Int

    @Insert
    suspend fun insertInstallments(list: List<Installment>)

    @Query("SELECT * FROM installments WHERE planId = :planId ORDER BY seq")
    suspend fun installmentsOf(planId: Long): List<Installment>

    @Query("SELECT * FROM installments WHERE planId = :planId ORDER BY seq")
    fun installmentsOfFlow(planId: Long): Flow<List<Installment>>

    @Query("SELECT * FROM installments ORDER BY dueDate")
    suspend fun allInstallments(): List<Installment>

    @Query(
        """UPDATE installments SET paidAmount = :paid, paidDate = :paidDate, status = :status
           WHERE id = :id"""
    )
    suspend fun updatePaid(id: Long, paid: Long, paidDate: Long?, status: Int)  // [P33-P8] قروش

    @Query("UPDATE installments SET status = 2 WHERE id = :id AND status = 0")
    suspend fun markLateIfDue(id: Long)

    @Query("DELETE FROM installments WHERE planId = :planId")
    suspend fun deleteInstallmentsOf(planId: Long)

    // إعادة جدولة قسط متأخر (وظيفة 28) — تحديث تاريخ الاستحقاق فقط،
    // بلا أي لمس لحقول السداد أو المخطط (استعلام إضافي فقط — بلا أي تعديل على مخطط Room أو إصداره)
    @Query("UPDATE installments SET dueDate = :dueDate WHERE id = :id")
    suspend fun updateDueDate(id: Long, dueDate: Long)

    /**نقل خطط أقساط طرف مكرر إلى الطرف الموحّد (دمج) */
    @Query("UPDATE installment_plans SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)
}

// ═══════════════════════════════════════════════════════════════════════════
// [P17-a] DAOs كشف الحساب PDF — نمط الملف نفسه (سلسلات suspend + Flow حي للقوائم)
// ═══════════════════════════════════════════════════════════════════════════

@Dao
interface StatementDao {
    @Insert
    suspend fun insert(s: StatementEntity): Long

    @Upsert
    suspend fun upsert(s: StatementEntity): Long

    @Query("SELECT * FROM statements WHERE id = :id")
    suspend fun findById(id: Long): StatementEntity?

    @Query("SELECT * FROM statements WHERE statementNumber = :n LIMIT 1")
    suspend fun findByStatementNumber(n: String): StatementEntity?

    @Query("SELECT * FROM statements WHERE verificationId = :v LIMIT 1")
    suspend fun findByVerificationId(v: String): StatementEntity?

    /** [P17-a] دلالة dedup على مستوى الكشف: نفس الطرف والفترة نفسها (انظر StatementRepo.issue) */
    @Query("SELECT * FROM statements WHERE partyId = :partyId AND fromTs = :fromTs AND toTs = :toTs LIMIT 1")
    suspend fun findByPartyPeriod(partyId: Long, fromTs: Long, toTs: Long): StatementEntity?

    @Query("SELECT * FROM statements WHERE partyId = :partyId ORDER BY createdAt DESC")
    suspend fun historyForParty(partyId: Long): List<StatementEntity>

    @Query("SELECT * FROM statements ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listAll(limit: Int): List<StatementEntity>

    /** [P17-a] الأرقام كلها فقط — يغذي nextStatementSeq (MAX+1 لا count+1: أرقام مالية لا تُعاد) */
    @Query("SELECT statementNumber FROM statements")
    suspend fun allStatementNumbers(): List<String>

    @Query("DELETE FROM statements WHERE id = :id")
    suspend fun delete(id: Long)

    /**نقل كشوف طرف مكرر إلى الطرف الموحّد (دمج) —
 * كان FK=RESTRICT يرمي SQLiteConstraintException عند حذف الطرف المكرر،
 * فيفشل الدمج كلياً لزبائن صادروا كشوفاً [تدقيق H-2] */
    @Query("UPDATE statements SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)

    // ── التسليمات (statement_deliveries) — findByDedupKey يستفسر الجدول التابع بحسب العقد ──

    @Insert
    suspend fun insertDelivery(d: StatementDeliveryEntity): Long

    @Query("SELECT * FROM statement_deliveries WHERE dedupKey = :key LIMIT 1")
    suspend fun findByDedupKey(key: String): StatementDeliveryEntity?

    @Query("SELECT * FROM statement_deliveries WHERE id = :id")
    suspend fun findDeliveryById(id: Long): StatementDeliveryEntity?

    @Query("SELECT * FROM statement_deliveries WHERE statementId = :statementId ORDER BY id")
    suspend fun deliveriesFor(statementId: Long): List<StatementDeliveryEntity>

    @Query("SELECT * FROM statement_deliveries WHERE status = :status ORDER BY id")
    suspend fun deliveriesByStatus(status: String): List<StatementDeliveryEntity>

    /** [P34-M1] تصدير النسخة الكاملة 23/23 — كل التسليمات بلا فلتر حالة (نمط allOnce) */
    @Query("SELECT * FROM statement_deliveries ORDER BY id")
    suspend fun allDeliveries(): List<StatementDeliveryEntity>

    @Query(
        """UPDATE statement_deliveries SET status = :status, lastError = :error,
           attempts = :attempts, sentAt = :sentAt, lastAttemptAt = :lastAttemptAt WHERE id = :id"""
    )
    suspend fun updateDelivery(
        id: Long, status: String, error: String?,
        attempts: Int, sentAt: Long?, lastAttemptAt: Long?
    )

    /**
     * [تدقيق M-4] انتقال حالة ذري مشروط — UPDATE واحد بلا قراءة سابقة:
     * دلتا المحاولة تُحسب من الحالة الهدف عند المستدعي (عقد ثابت)، وsentAt
     * يُختم مرة واحدة فقط عبر CASE (لا يُعاد كتمه) — لا نافذة سباق بين
     * العامل المجدول وإعادة المحاولة اليدوية.
     */
    @Query(
        """UPDATE statement_deliveries SET
           status = :status, lastError = :error,
           attempts = attempts + :attemptDelta,
           sentAt = CASE WHEN :isSent AND sentAt IS NULL THEN :now ELSE sentAt END,
           lastAttemptAt = :now
           WHERE id = :id"""
    )
    suspend fun markDeliveryAtomic(
        id: Long, status: String, error: String?,
        attemptDelta: Int, isSent: Boolean, now: Long
    )
}

@Dao
interface StatementTemplateDao {
    @Upsert
    suspend fun upsert(t: StatementTemplateEntity): Long

    @Query("SELECT * FROM statement_templates ORDER BY name")
    fun all(): Flow<List<StatementTemplateEntity>>

    @Query("SELECT * FROM statement_templates ORDER BY name")
    suspend fun allOnce(): List<StatementTemplateEntity>

    @Query("SELECT * FROM statement_templates WHERE id = :id")
    suspend fun byId(id: Long): StatementTemplateEntity?

    @Query("UPDATE statement_templates SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE statement_templates SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("UPDATE statement_templates SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("DELETE FROM statement_templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SignatureDao {
    @Upsert
    suspend fun upsert(s: SignatureEntity): Long

    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    fun all(): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures ORDER BY createdAt DESC")
    suspend fun allOnce(): List<SignatureEntity>

    @Query("SELECT * FROM signatures WHERE id = :id")
    suspend fun byId(id: Long): SignatureEntity?

    @Query("SELECT * FROM signatures WHERE isDefault = 1 AND active = 1 LIMIT 1")
    suspend fun defaultActive(): SignatureEntity?

    @Query("UPDATE signatures SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE signatures SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("UPDATE signatures SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StampDao {
    @Upsert
    suspend fun upsert(s: StampEntity): Long

    @Query("SELECT * FROM stamps ORDER BY createdAt DESC")
    fun all(): Flow<List<StampEntity>>

    @Query("SELECT * FROM stamps ORDER BY createdAt DESC")
    suspend fun allOnce(): List<StampEntity>

    @Query("SELECT * FROM stamps WHERE id = :id")
    suspend fun byId(id: Long): StampEntity?

    @Query("SELECT * FROM stamps WHERE isDefault = 1 AND active = 1 LIMIT 1")
    suspend fun defaultActive(): StampEntity?

    @Query("UPDATE stamps SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE stamps SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("UPDATE stamps SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM stamps WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface NoteTemplateDao {
    @Upsert
    suspend fun upsert(t: NoteTemplateEntity): Long

    @Query("SELECT * FROM note_templates ORDER BY id")
    fun all(): Flow<List<NoteTemplateEntity>>

    @Query("SELECT * FROM note_templates ORDER BY id")
    suspend fun allOnce(): List<NoteTemplateEntity>

    @Query("SELECT * FROM note_templates WHERE id = :id")
    suspend fun byId(id: Long): NoteTemplateEntity?

    @Query("UPDATE note_templates SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE note_templates SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("DELETE FROM note_templates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StatementRuleDao {
    @Upsert
    suspend fun upsert(r: StatementRuleEntity): Long

    @Query("SELECT * FROM statement_rules ORDER BY id")
    fun all(): Flow<List<StatementRuleEntity>>

    @Query("SELECT * FROM statement_rules ORDER BY id")
    suspend fun allOnce(): List<StatementRuleEntity>

    @Query("SELECT * FROM statement_rules WHERE id = :id")
    suspend fun byId(id: Long): StatementRuleEntity?

    /** [P17-a] المستحق الآن — استعلام العامل المجدول (17-c)؛ الفهرس على nextRunAt يخدمه */
    @Query(
        """SELECT * FROM statement_rules
           WHERE enabled = 1 AND nextRunAt IS NOT NULL AND nextRunAt <= :now
           ORDER BY nextRunAt"""
    )
    suspend fun dueRules(now: Long): List<StatementRuleEntity>

    @Query("UPDATE statement_rules SET lastRunAt = :lastRunAt, nextRunAt = :nextRunAt WHERE id = :id")
    suspend fun markRun(id: Long, lastRunAt: Long, nextRunAt: Long?)

    @Query("UPDATE statement_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM statement_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

/** [P17-a] سجل تدقيق إلحاق فقط — لا تعديل ولا حذف عمداً (سند) */
@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(a: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log WHERE action = :action ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun byAction(action: String, limit: Int): List<AuditLogEntity>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    /** [P34-M1] تصدير النسخة الكاملة 23/23 — السجل كاملاً بترتيب الإلحاق (سجل للقراءة فقط يُنقل كما هو) */
    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun allLogs(): List<AuditLogEntity>
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY id")
    fun all(): Flow<List<Rule>>

    @Query("SELECT * FROM rules ORDER BY id")
    suspend fun allOnce(): List<Rule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: Rule)

    @Query("UPDATE rules SET lastRun = :ts WHERE kind = :kind")
    suspend fun markRun(kind: String, ts: Long)

    @Query("SELECT * FROM rules WHERE kind = :kind LIMIT 1")
    suspend fun byKind(kind: String): Rule?

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY isBase DESC, code")
    fun all(): Flow<List<Currency>>

    @Query("SELECT * FROM currencies ORDER BY code")
    suspend fun allOnce(): List<Currency>

    @Query("SELECT * FROM currencies WHERE isBase = 1 LIMIT 1")
    suspend fun base(): Currency?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<Currency>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: Currency)

    @Query("SELECT COUNT(*) FROM currencies")
    suspend fun count(): Int
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC, id DESC")
    fun all(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE date >= :from AND date <= :to ORDER BY date DESC")
    suspend fun between(from: Long, to: Long): List<Expense>

    @Insert
    suspend fun insert(e: Expense): Long

    // [P13-a] read-all for backup (P13-b)
    @Query("SELECT * FROM expenses")
    suspend fun allOnce(): List<Expense>

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE date >= :from AND date <= :to")
    suspend fun sumBetween(from: Long, to: Long): Long  // [P33-P8] قروش

    @Query("SELECT category AS category, COALESCE(SUM(amount),0) AS total FROM expenses WHERE date >= :from GROUP BY category ORDER BY total DESC")
    suspend fun byCategorySince(from: Long): List<CategorySum>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int
}

data class CategorySum(val category: String, val total: Long)  // [P33-P8] قروش

/**عمليات صيانة داخل معاملة واحدة — مسح كامل آمن ذرّياً */
@Dao
interface MaintenanceDao {
    @Query("DELETE FROM parties") suspend fun wipeParties()
    @Query("DELETE FROM journal") suspend fun wipeJournal()
    @Query("DELETE FROM journal_lines") suspend fun wipeJournalLines()
    @Query("DELETE FROM products") suspend fun wipeProducts()
    @Query("DELETE FROM stock_moves") suspend fun wipeStockMoves()
    @Query("DELETE FROM invoices") suspend fun wipeInvoices()
    @Query("DELETE FROM invoice_items") suspend fun wipeInvoiceItems()
    @Query("DELETE FROM payments") suspend fun wipePayments()
    @Query("DELETE FROM checks") suspend fun wipeChecks()
    @Query("DELETE FROM rules") suspend fun wipeRules()
    @Query("DELETE FROM currencies") suspend fun wipeCurrencies()
    @Query("DELETE FROM installment_plans") suspend fun wipePlans()
    @Query("DELETE FROM installments") suspend fun wipeInstallments()
    @Query("DELETE FROM expenses") suspend fun wipeExpenses()
    // [P20-FIX agent10]: جدول الزيارات (GPS) أُضيف في v8 وكان غائباً عن المسح الكامل —
    // «مسح كامل» كان يحتفظ بسجل مواقع وتواريخ المستخدم رغم طلب الحذف الصريح
    @Query("DELETE FROM visits") suspend fun wipeVisits()

    // [P36-BK] جداول منظومة الكشوف الثمانية تنضم للمسح الكامل — كانت غائبة عن wipeAll
    // منذ نشأتها (عهد 15 جدولاً)، فكانت «الاستبدال الكامل» تترك كشوفاً وتواقيع وأختاماً
    // وسجلات تدقيق قائمة ثم يفشل إدراج النسخة الجديدة بمعرفاتها الأصلية على القيد —
    // الترتيب إلزامي: التسليمات قبل الكشوف (FK CASCADE باتجاهها) والكشوف قبل الأطراف (RESTRICT)
    @Query("DELETE FROM statement_deliveries") suspend fun wipeStatementDeliveries()
    @Query("DELETE FROM statements") suspend fun wipeStatements()
    @Query("DELETE FROM statement_templates") suspend fun wipeStatementTemplates()
    @Query("DELETE FROM signatures") suspend fun wipeSignatures()
    @Query("DELETE FROM stamps") suspend fun wipeStamps()
    @Query("DELETE FROM note_templates") suspend fun wipeNoteTemplates()
    @Query("DELETE FROM statement_rules") suspend fun wipeStatementRules()
    @Query("DELETE FROM audit_log") suspend fun wipeAuditLog()
    // [P46-W1] نقاط الولاء والكوبونات — المسح الكامل لا يترك الجداول الجديدة حية
    @Query("DELETE FROM loyalty_entries") suspend fun wipeLoyaltyEntries()
    @Query("DELETE FROM coupons") suspend fun wipeCoupons()

    @androidx.room.Transaction
    suspend fun wipeAll() {
        wipeJournalLines(); wipeJournal()
        wipeInvoiceItems(); wipeInvoices()
        wipeInstallments(); wipePlans()
        wipeStockMoves(); wipeProducts()
        wipePayments(); wipeChecks()
        wipeExpenses()
        wipeVisits()
        // [P36-BK] الابن قبل الأب: التسليمات ثم الكشوف — والمستقلات كتلة واحدة
        wipeStatementDeliveries(); wipeStatements()
        wipeStatementTemplates(); wipeSignatures(); wipeStamps(); wipeNoteTemplates()
        wipeStatementRules(); wipeAuditLog()
        // [P46-W1] الولاء يُمسح قبل أبوينه (parties/invoices) — والكوبونات مستقلة
        wipeLoyaltyEntries(); wipeCoupons()
        wipeParties(); wipeRules(); wipeCurrencies()
    }
}

// [P12-b] عمليات جدول الزيارات — سجل تاريخي بلا حذف ولا تعديل (إدراج فقط + قراءة تنازلية بالتاريخ)
@Dao
interface VisitDao {
    /** تدفق حي: كل الزيارات الأحدث أولاً — مصدر تقرير قسم الزيارات في المفضّلات */
    @Query("SELECT * FROM visits ORDER BY visitedAt DESC")
    fun all(): Flow<List<Visit>>

    /** لقطة واحدة لكل الزيارات (نفس الترتيب) — للتقارير الفورية خارج التركيب */
    @Query("SELECT * FROM visits ORDER BY visitedAt DESC")
    suspend fun allOnce(): List<Visit>

    /** زيارات طرف واحد الأحدث أولاً — كشف تاريخ زيارات عميل محدد */
    @Query("SELECT * FROM visits WHERE partyId = :partyId ORDER BY visitedAt DESC")
    suspend fun forParty(partyId: Long): List<Visit>

    /** إدراج زيارة جديدة — السجل التاريخي لا يُعدَّل ولا يُحذف */
    @Insert
    suspend fun insert(v: Visit): Long

    /**
     * [P13-a] حذف سجل زيارة واحد نهائياً — الاستثناء الوحيد على قاعدة «لا حذف»:
     * حذف صريح بموافقة المستخدم (تأكيد الواجهة في VisitsSection) لتصحيح تسجيل خاطئ.
     * التقرير يُبنى فوق تدفق Room حي فيتحدّث تلقائياً بعد الحذف بلا أي refresh يدوي.
     */
    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun deleteVisit(id: Long)

    /**نقل زيارات طرف مكرر إلى الطرف الموحّد (دمج) —
 * الزيارات بلا FK عن قصد (سجل تاريخي) لكن تركها على المكرر
 * يجعل تاريخه يختفي من بطاقة الطرف الباقي بعد الدمج [تدقيق H-2] */
    @Query("UPDATE visits SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)
}

// ═══════════════════════════════════════════════════════════════════════
// [P46-W1] جولة 7 — نقاط الولاء والكوبونات (مخطط v12)
// ═══════════════════════════════════════════════════════════════════════

/** دفتر نقاط الولاء — إلحاق فقط: الرصيد مشتق بجمع الدلتا (عقد LoyaltyP46) لا بأرصدة مخزنة */
@Dao
interface LoyaltyDao {
    @Insert
    suspend fun insert(e: LoyaltyEntryEntity): Long

    /** رصيد طرف = مجموع دلتا دفتره — null على الدفتر الفارغ (المستدعي يقصره على 0) */
    @Query("SELECT SUM(delta) FROM loyalty_entries WHERE partyId = :partyId")
    suspend fun sumByParty(partyId: Long): Long?

    /** مجموع أثر فاتورة على نقاط طرف — أساس صف التعويض عند الإلغاء (reason=VOID) */
    @Query("SELECT SUM(delta) FROM loyalty_entries WHERE invoiceId = :invoiceId")
    suspend fun sumByInvoice(invoiceId: Long): Long?

    /** طرف أول صف دفتر لفاتورة — صاحب نقاط الفاتورة عند التعويض (كل صفوفها طرف واحد) */
    @Query("SELECT partyId FROM loyalty_entries WHERE invoiceId = :invoiceId LIMIT 1")
    suspend fun partyIdByInvoice(invoiceId: Long): Long?

    /** كشف نقاط طرف حي الأحدث أولاً — يغذي واجهة النقاط في بطاقة الطرف */
    @Query("SELECT * FROM loyalty_entries WHERE partyId = :partyId ORDER BY createdAt DESC, id DESC")
    fun forParty(partyId: Long): Flow<List<LoyaltyEntryEntity>>

    /** لقطة كشف نقاط طرف (نفس الترتيب) — للتصدير والنسخ */
    @Query("SELECT * FROM loyalty_entries WHERE partyId = :partyId ORDER BY createdAt DESC, id DESC")
    suspend fun forPartyOnce(partyId: Long): List<LoyaltyEntryEntity>

    @Query("SELECT COUNT(*) FROM loyalty_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM loyalty_entries")
    suspend fun allOnce(): List<LoyaltyEntryEntity>

    @Query("DELETE FROM loyalty_entries")
    suspend fun wipe()

    /**نقل نقاط ولاء طرف مكرر إلى الطرف الموحّد (دمج) —
 * كان FK=CASCADE يمحو النقاط التاريخية كلياً عند حذف الطرف المكرر بعد الدمج،
 * فيضيع رصيد الزبون بصمت رغم نجاح الدمج المُعلن [تدقيق H-1] */
    @Query("UPDATE loyalty_entries SET partyId = :toId WHERE partyId = :fromId")
    suspend fun moveParty(fromId: Long, toId: Long)
}

/** الكوبونات — إدارة كاملة (إضافة/حذف/تفعيل) واستهلاك ذرّي مشروط */
@Dao
interface CouponDao {
    @androidx.room.Upsert
    suspend fun upsert(c: CouponEntity): Long

    /** قائمة الإدارة الأحدث إنشاءً أولاً — شاشة الكوبونات في الإعدادات */
    @Query("SELECT * FROM coupons ORDER BY createdAt DESC, id DESC")
    fun all(): Flow<List<CouponEntity>>

    @Query("SELECT * FROM coupons ORDER BY createdAt DESC, id DESC")
    suspend fun allOnce(): List<CouponEntity>

    @Query("SELECT * FROM coupons WHERE code = :code LIMIT 1")
    suspend fun byCode(code: String): CouponEntity?

    @Query("SELECT * FROM coupons WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): CouponEntity?

    /** حذف إداري صريح من شاشة الإدارة — الاستهلاك لا يحذف الكوبون (usedCount يتكفل) */
    @Query("DELETE FROM coupons WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * استهلاك ذرّي مشروط: يرفع usedCount فقط إن كان الكوبون مفعّلاً وغير منتهي
     * الاستخدام — الصفوف المتغيرة (0/1) هي حكم القبول، فلا يتجاوز maxUses مهما
     * تسابقت معاملات القراءة (سطر UPDATE واحد ذرّي في SQLite).
     */
    @Query(
        "UPDATE coupons SET usedCount = usedCount + 1 " +
            "WHERE id = :id AND active = 1 AND (maxUses = 0 OR usedCount < maxUses) " +
            "AND (expiresAt = 0 OR expiresAt >= :now)"
    )
    suspend fun consume(id: Long, now: Long): Int

    @Query("SELECT COUNT(*) FROM coupons")
    suspend fun count(): Int

    @Query("SELECT * FROM coupons")
    suspend fun allRows(): List<CouponEntity>

    @Query("DELETE FROM coupons")
    suspend fun wipe()
}

// ═══════════════════════════════════════════════════════════════════════════
// [H1-3][H1-4][v13] DAOs هوية المستخدمين — RBAC على الجهاز الواحد
// ═══════════════════════════════════════════════════════════════════════════

@androidx.room.Dao
interface UserDao {

    @Query("SELECT * FROM users ORDER BY id")
    suspend fun all(): List<UserEntity>

    @Query("SELECT * FROM users WHERE active = 1 ORDER BY id")
    suspend fun activeUsers(): List<UserEntity>

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun byId(id: Long): UserEntity?

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    /** المالك الفعّال الأول — حارس «لا يبقى جهاز بلا مالك» في UsersVM */
    @Query("SELECT * FROM users WHERE role = 0 AND active = 1 ORDER BY id LIMIT 1")
    suspend fun firstActiveOwner(): UserEntity?

    @androidx.room.Insert
    suspend fun insert(u: UserEntity): Long

    @androidx.room.Update
    suspend fun update(u: UserEntity)

    /** تعطيل/تفعيل بلا حذف — التاريخ المحاسبي المُنسب له لا يُمس */
    @Query("UPDATE users SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Int)

    /** ختم آخر ظهور عند كل فتح جلسة ناجح */
    @Query("UPDATE users SET lastSeenAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    /** حذف نهائي — باب المالك وحده (HARD_DELETE عبر USERS_MANAGE في UsersVM) */
    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: Long)
}

@androidx.room.Dao
interface UserSecretDao {

    @Query("SELECT * FROM user_secrets WHERE userId = :userId")
    suspend fun byUser(userId: Long): UserSecretEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(s: UserSecretEntity)

    /** لا يُستدعى إلا عبر CASCADE حذف المستخدم — إزالة صريحة للاستخدام الإداري فقط */
    @Query("DELETE FROM user_secrets WHERE userId = :userId")
    suspend fun deleteFor(userId: Long)

    @Query("SELECT COUNT(*) FROM user_secrets")
    suspend fun count(): Int

    /** [H1-4] معرفات أصحاب الأسرار — حالة عرض شاشة القفل (من يملك رمزاً) */
    @Query("SELECT userId FROM user_secrets")
    suspend fun userIds(): List<Long>
}

/**
 * [Z2-أ V 1.5.0] DAO أرشيف ZATCA-2 — عقد الأرشيف الأول: archiveFirst بـIGNORE
 * لا يستبدل صفّاً قائماً أبداً (سلسلة PIH تتبع بايتات الإصدار الأولى)،
 * وكل كتابات نتائج القائمة تحدّث أعمدة حالة فقط لا المستند نفسه.
 */
@Dao
interface ZatcaDocDao {

    /** أرشفة أول إصدار — إن وُجد صفّ سابق يُترك كما هو ويعيد -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun archiveFirst(doc: ZatcaDocEntity): Long

    @Query("SELECT * FROM zatca_docs WHERE invoiceId = :invoiceId")
    suspend fun byInvoice(invoiceId: Long): ZatcaDocEntity?

    /** هاش آخر وثيقة مُصدَرة بترتيب معرّف الفاتورة — مرجع PIH للإصدار التالي */
    @Query("SELECT xmlHash FROM zatca_docs ORDER BY invoiceId DESC LIMIT 1")
    suspend fun latestHash(): String?

    /** عدد الأرشيف (تشخيص ونسخ احتياطي) */
    @Query("SELECT COUNT(*) FROM zatca_docs")
    suspend fun count(): Int

    /** قبول إبلاغ/تخليص — يغلق المحاولة ويمحوا سبب الرفض */
    @Query(
        """UPDATE zatca_docs SET reportedAt = :at, attemptCount = 0,
           rejectReason = '' WHERE invoiceId = :id"""
    )
    suspend fun markReported(id: Long, at: Long)

    /** رفض معياري — السبب يُعرض في لوحة ZATCA والإصلاح بمستند تصحيحي */
    @Query("UPDATE zatca_docs SET rejectReason = :reason WHERE invoiceId = :id")
    suspend fun markRejected(id: Long, reason: String)

    /** فشل عابر — عدّاد المحاولات فقط (يحدد مهلة التراجع الأسّي التالية) */
    @Query("UPDATE zatca_docs SET attemptCount = :count WHERE invoiceId = :id")
    suspend fun markDeferred(id: Long, count: Int)

    /** [O3] حفظ النسخة المخلّصة الموقعة من الهيئة — القياسية بعد تخليص ناجح */
    @Query("UPDATE zatca_docs SET clearedXml = :xml WHERE invoiceId = :id")
    suspend fun markClearedXml(id: Long, xml: String)
}
@androidx.room.Database(
    entities = [
        Party::class, JournalEntry::class, JournalLine::class, Product::class,
        StockMove::class, Invoice::class, InvoiceItem::class, Payment::class,
        CheckEntity::class, Rule::class, Currency::class,
        InstallmentPlan::class, Installment::class, Expense::class,
        Visit::class,
        // [P17-a] كشف الحساب PDF — 8 جداول جديدة (ترحيل 8→9 في SuperBizApp)
        StatementTemplateEntity::class, SignatureEntity::class, StampEntity::class,
        NoteTemplateEntity::class, StatementEntity::class, StatementDeliveryEntity::class,
        StatementRuleEntity::class, AuditLogEntity::class,
        // [P46-W1] نقاط الولاء والكوبونات — جدولان بإنشاء فقط (ترحيل 11→12 في SuperBizApp)
        LoyaltyEntryEntity::class, CouponEntity::class,
        // [H1-3][H1-4] هوية المستخدمين والأدوار — جدولان بإنشاء فقط + عمودا إسناد التدقيق
        // + أعمدة هوية ZATCA-2 على الفواتير — كلها في ترحيل مشترك واحد (ترحيل 12→13 في SuperBizApp)
        UserEntity::class, UserSecretEntity::class,
        // [Z2-أ V 1.5.0] أرشيف مستندات ZATCA-2 — جدول بإنشاء فقط
        // (ترحيل 13→14 في SuperBizApp: CREATE TABLE/INDEX فقط — لا جدول قائم يُمس)
        ZatcaDocEntity::class
    ],
    // [P11-a] عمودا المفضّلة والإحداثيات على parties (ترحيل 6→7 في SuperBizApp)
    // [P12-b] جدول الزيارات بموقعها الجغرافي (ترحيل 7→8 في SuperBizApp)
    // [P17-a] كشف الحساب PDF — أعمدة parties الإضافية + 8 جداول (ترحيل 8→9 في SuperBizApp)
    // [P33-P8] الترحيل المالي — كل أعمدة المبالغ من REAL ريال إلى INTEGER قروش
    // (ترحيل 9→10 في SuperBizApp) — التوازن المحاسبي مساواة تامة بلا عتبات
    // [P41-L1] حقول ضريبية على مستوى السطر + تمييز صفرية/معفاة
    // (ترحيل 10→11 في SuperBizApp — ALTER ADD بعمودين ببذرتين محايدتين، لا إعادة بناء)
    // [P46-W1] نقاط الولاء والكوبونات — جدولان بإنشاء فقط
    // (ترحيل 11→12 في SuperBizApp — CREATE TABLE/INDEX فقط، لا جدول قائم يُمس)
    // [H1-3][v13] RBAC + ZATCA-2 — جدولا users/user_secrets بإنشاء فقط + عمودا إسناد
    // التدقيق على audit_log (ALTER nullable) + 9 أعمدة هوية على invoices (ALTER ببذور آمنة)
    // (ترحيل 12→13 في SuperBizApp — لا جدول قائم يُعاد بناؤه ولا صف يُعاد كتابته عدا سطر المالك المزروع)
    // [Z2-أ V 1.5.0] جدول zatca_docs بإنشاء فقط (ترحيل 13→14 في SuperBizApp — إلحاقي خالص)
    version = 14,
    exportSchema = true
)

abstract class AppDatabase : androidx.room.RoomDatabase() {
    abstract fun parties(): PartyDao
    abstract fun journal(): JournalDao
    abstract fun products(): ProductDao
    abstract fun stockMoves(): StockMoveDao
    abstract fun invoices(): InvoiceDao
    abstract fun invoiceItems(): InvoiceItemDao
    abstract fun payments(): PaymentDao
    abstract fun checks(): CheckDao
    abstract fun rules(): RuleDao
    abstract fun currencies(): CurrencyDao
    abstract fun installments(): InstallmentDao
    abstract fun expenses(): ExpenseDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun visits(): VisitDao
    // [P17-a] كشف الحساب PDF
    abstract fun statements(): StatementDao
    abstract fun statementTemplates(): StatementTemplateDao
    abstract fun signatures(): SignatureDao
    abstract fun stamps(): StampDao
    abstract fun noteTemplates(): NoteTemplateDao
    abstract fun statementRules(): StatementRuleDao
    abstract fun auditLog(): AuditLogDao
    // [P46-W1] نقاط الولاء والكوبونات
    abstract fun loyalty(): LoyaltyDao
    abstract fun coupons(): CouponDao
    // [H1-3][H1-4] هوية المستخدمين والأدوار
    abstract fun users(): UserDao
    abstract fun userSecrets(): UserSecretDao
    abstract fun zatcaDocs(): ZatcaDocDao
}
