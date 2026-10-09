package com.superbiz.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// فهرس المؤرشف — قوائم الأطراف تُرشّح بـ archived في كل استعلامات القوائم
// (M-3.6 توحيد): فهرس الاسم من خط التدقيق (قوائم الأطراف ترتب بالاسم) بجانب فهرس المؤرشف من خط التطوير
/**
 * [H4-3][v16] حقول المزامنة للجداول القابلة للمزامنة (9 جداول — ADR-002 D3):
 * - syncUpdatedAt: ساعة LWW تحفظها مشغّلات SQLite (MAX(ساعة+1, الآن)) — لا يعدها كود التطبيق.
 * - originDeviceId/originId: هوية الأصل — '' يعني صفاً أصلياً محلياً، وإلا صفاً مستورَداً.
 * تُضاف إلى: parties/products/visits/coupons/statement_templates/signatures/stamps/note_templates
 * (العملات بعمود الساعة حصراً — الرمز نفسه هوية).
 */
@Entity(tableName = "parties", indices = [Index("archived"), Index("name")])
data class Party(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val type: Int = 0,              // 0 عميل، 1 مورد، 2 كلاهما
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    // [P11-a] المفضّل + بطاقة الموقع الجغرافي — قيم افتراضية تحفظ كل مواضع الإنشاء القائمة
    // (وإلا لكسرت كل استدعاءات Party(...))، والترحيل 6→7 يضيف الأعمدة بقيم افتراضية مطابقة
    val favorite: Boolean = false,   // [P11-a] مفضّل
    val lat: Double? = null,         // [P11-a] خط العرض لبطاقة الموقع الجغرافي
    val lng: Double? = null,         // [P11-a] خط الطول
    // [P17-a] أعمدة كشف الحساب PDF — كلها اختيارية (NULL) حفاظاً على كل مواضع الإنشاء القائمة،
    // وترحيل 8→9 يضيفها ALTER TABLE ADD COLUMN بلا إعادة بناء ولا قيم افتراضية SQL.
    val email: String? = null,       // [P17-a] بريد الطرف (مستلم كشف البريد)
    val address: String? = null,     // [P17-a] عنوان الطرف المطبوع على الكشف
    val taxNumber: String? = null,   // [P17-a] الرقم الضريبي للطرف
    val crNumber: String? = null,    // [P17-a] السجل التجاري للطرف
    val city: String? = null,        // [P17-a] المدينة
    val country: String? = null,     // [P17-a] الدولة
    val website: String? = null,     // [P17-a] الموقع الإلكتروني
    val accountNumber: String? = null, // [P17-a] رقم الحساب/الملف لدى التاجر
    // [H4-3][v16] مزامنة — انظر توثيق الحقول أعلاه
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
) {
    val isCustomer: Boolean get() = type == 0 || type == 2
    val isSupplier: Boolean get() = type == 1 || type == 2
}

@Entity(
    tableName = "journal",
    // فهرسا المرجع والتاريخ — كان unpost/deleteEntriesByRef وaccountSumsBetween يفحصان الجدول كاملاً
    indices = [Index("refType", "refId"), Index("date")]
)
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val memo: String,
    val refType: String? = null,
    val refId: Long? = null
)

@Entity(
    tableName = "journal_lines",
    indices = [Index("entryId"), Index("account"), Index("partyId")],
    // (M-3.3): مفاتيح أجنبية — سطر بلا قيد مستحيل (CASCADE)، وطرف محذوف يُنزّل مرجعه (SET NULL)
    foreignKeys = [
        ForeignKey(
            entity = JournalEntry::class, parentColumns = ["id"], childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class JournalLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val account: String,
    // [P33-P8] قروش صحيحة — التوازن المحاسبي مساواة تامة بلا عتبات فاصلة عائمة
    val debit: Long = 0L,
    val credit: Long = 0L,
    val partyId: Long? = null,
    val currency: String = "SAR",
    val fxRate: Double = 1.0
)

@Entity(tableName = "products", indices = [Index("barcode"), Index("archived")])
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sku: String = "",
    val barcode: String = "",
    val unit: String = "قطعة",
    // [P33-P8] الأسعار قروش — الكميات تبقى Double (وحدات قياس قد تكون كسرية)
    val costPrice: Long = 0L,
    val salePrice: Long = 0L,
    val stockQty: Double = 0.0,
    val reorderLevel: Double = 0.0,
    val category: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    // [H4-3][v16] مزامنة — stockQty مستثنى من الدمج (ملك الجهاز المحلي — ADR-002 D3)
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
) {
    val stockValue: Long get() = Math.round(stockQty * costPrice)
    val isLow: Boolean get() = reorderLevel > 0 && stockQty <= reorderLevel
}

@Entity(
    tableName = "stock_moves",
    // [P6-M13 إصلاح]: فهرس المرجع — كان deleteByRef(refType, refId) عند إلغاء الفواتير
    // يمسح جدول stock_moves كاملاً لغياب أي فهرس على (refType, refId). فهرس غير فريد
    // (لا خطر على بيانات قائمة) باسم Room القياسي index_stock_moves_refType_refId.
    indices = [Index("productId"), Index("date"), Index("refType", "refId")],
    // (M-3.3): حركة بلا منتج مستحيلة — الأرشفة هي مسار الإزالة (RESTRICT يمنع الحذف الخام)
    foreignKeys = [
        ForeignKey(
            entity = Product::class, parentColumns = ["id"], childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class StockMove(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val qty: Double,                // + إدخال / - إخراج
    val reason: String,             // PURCHASE / SALE / ADJUST / CHECK
    val date: Long,
    val refType: String? = null,
    val refId: Long? = null,
    val note: String = ""
)

@Entity(
    tableName = "invoices",
    indices = [Index("partyId"), Index("date"), Index("status"), Index("number")],
    // (M-3.3): فاتورة بلا طرف مستحيلة — الأطراف تُؤرشف ولا تُحذف (RESTRICT)
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val partyId: Long,
    val type: Int = 0,              // 0 بيع، 1 شراء
    val date: Long,
    val dueDate: Long,
    // [P33-P8] المبالغ قروش — taxRate/fxRate نسب تبقى Double
    val subtotal: Long,
    val discount: Long = 0L,
    val taxRate: Double = 0.0,
    val taxAmount: Long = 0L,
    val total: Long,
    val paid: Long = 0L,
    val costTotal: Long = 0L,    // مجموع تكلفة الأصناف لقيود COGS
    val status: Int = 0,            // 0 غير مدفوعة 1 جزئية 2 مدفوعة 3 ملغاة
    val currency: String = "SAR",
    val fxRate: Double = 1.0,
    val note: String = "",
    // [H1-3][v13] أعمدة هوية ZATCA مرحلة-2 — الترحيل المشترك مع RBAC في ترحيل واحد
    // (عقد ZATCA2_WAVE2_PLAN §2: «الترحيلان يُنفَّذان معاً في v13») — بذور آمنة
    // محايدة دلالياً، والاستهلاك الفعلي في موجة الربط الرسمي V 1.5.0 (دفعة Z2-أ):
    // كل عمود مُضاف بـALTER ADD COLUMN NOT NULL DEFAULT (سابقة 10→11 حرفياً)
    val uuid: String = "",              // UUID للفاتورة — يُولد وقت الإصدار في موجة الربط
    val icv: Long = 0,                  // عدّاد الفاتورة التزايدي (سلسلة السلامة ICV)
    val pih: String = "",              // بصمة الفاتورة السابقة PIH ("" = غير محددة بعد)
    val zatcaSubtype: String = "",      // نوع المستند: 0100000 قياسية / 0200000 مبسطة / دائنة...
    val deliveryDate: Long = 0,         // تاريخ التوريد المنفصل (0 = غير محدد)
    val buyerName: String = "",         // بيانات المشتري المقنة للفواتير القياسية B2B
    val buyerVat: String = "",
    val buyerAddress: String = "",
    val zatcaStatus: Int = 0,           // حالة الربط: 0 غير مطبق ← 1 بالقائمة ← 2 مبلغة/مخلصة
    // [H4-1 V 2.5.0] ختم الفئة الأصلية — الأفق الرابع (عملات متعددة):
    // الأعمدة المالية أعلاه تبقى قروش الأساس حصراً (وحدة القياس الموحدة P33-P8)،
    // وهذه الأعمدة الثلاثة توثّق الفئة الأصلية بسعرها التاريخي المختوم عند الإدخال:
    // origCurrency: رمز العملة الأصلية ("" = الفئة بالأساس نفسه — كل الصفوف التاريخية)
    // origTotal: الإجمالي الأصلي بوحدات 2dp عالمية من العملة الأصلية (ليس minorUnits كتالوجها)
    // origFxMicros: سعر الختم — قروش الأساس لكل وحدة أجنبية × 1e6 (عقد R17 بند 2)
    val origCurrency: String = "",
    val origTotal: Long = 0,
    val origFxMicros: Long = 0
) {
    val open: Long get() = total - paid  // [P33-P8] مساواة تامة — لا تقريب
    val isSale: Boolean get() = type == 0
}

@Entity(
    tableName = "invoice_items",
    indices = [Index("invoiceId"), Index("productId")],
    // (M-3.3): بند يتيم بلا فاتورة مستحيل (CASCADE)، وحذف منتج يُنزّل مرجعه من البنود
    foreignKeys = [
        ForeignKey(
            entity = Invoice::class, parentColumns = ["id"], childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Product::class, parentColumns = ["id"], childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class InvoiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val productId: Long? = null,
    val desc: String,
    val qty: Double,
    // [P33-P8] السعر والخصم قروش — lineTotal = round(qty × السعر) − الخصم
    val unitPrice: Long,
    val discount: Long = 0L,
    // [P41-L1] v11 — الحقول الضريبية على مستوى السطر (ترحيل 10→11 ببذرتين محايدتين):
    //  taxKind: 0 قياسية (S) / 1 صفرية (Z) / 2 معفاة (E) — الصفوف التاريخية كلها 0
    //  taxRate: النسبة الصريحة للسطر — -1.0 = وراثة نسبة الرأس (السلوك التاريخي الحرفي)
    // المسار الواعي بالسطر لا يُفتح إلا بأي سطر صريح — وإلا ضريبة الرأس كما كانت (LineTaxP41)
    val taxKind: Int = 0,
    val taxRate: Double = -1.0
) {
    val lineTotal: Long get() = Math.round(qty * unitPrice) - discount
}

// (M-3.3/M-4.9): فهرس checkId (حذف دفعات الشيك كان يمسح الجدول كاملاً) + عمود planId يربط
// دفعات الأقساط بخططها (كانت مجهولة الهوية فيُسرب حذف الخطة دفعاتها إلى تقارير الخزنة)
// + مفاتيح أجنبية SET NULL لكل المراجع الاختيارية
@Entity(
    tableName = "payments",
    indices = [Index("partyId"), Index("invoiceId"), Index("date"), Index("checkId"), Index("planId")],
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Invoice::class, parentColumns = ["id"], childColumns = ["invoiceId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CheckEntity::class, parentColumns = ["id"], childColumns = ["checkId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = InstallmentPlan::class, parentColumns = ["id"], childColumns = ["planId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partyId: Long? = null,
    val invoiceId: Long? = null,
    val checkId: Long? = null,
    val amount: Long,  // [P33-P8] قروش
    val date: Long,
    val direction: Int = 0,         // 0 وارد (تحصيل)، 1 صادر (سداد)
    val method: String = "CASH",    // CASH / CHECK / OTHER
    val note: String = "",
    val planId: Long? = null        // (M-4.9): خطة التقسيط المالكة للدفعة (إن وُجدت)
)

@Entity(
    tableName = "checks",
    indices = [Index("partyId"), Index("dueDate"), Index("status")],
    // (M-3.3): شيك بلا طرف مستحيل — الأطراف تُؤرشف ولا تُحذف
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val partyId: Long,
    val bank: String = "",
    val amount: Long,  // [P33-P8] قروش
    val issueDate: Long,
    val dueDate: Long,
    val direction: Int = 0,         // 0 وارد، 1 صادر
    val status: Int = 0,            // 0 قيد التحصيل 1 مودع 2 محصّل 3 مرتجع 4 ملغى
    val note: String = ""
)

/**مصروف حقيقي بمبلغ وفئة وتاريخ — يُقيَّد مزدوجاً عبر ExpenseRepo */
@Entity(tableName = "expenses", indices = [Index("date"), Index("category")])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,  // [P33-P8] قروش الأساس دائماً — وحدة القياس الموحدة
    val category: String = "",
    val note: String = "",
    val date: Long,
    val createdAt: Long = System.currentTimeMillis(),
    // [H4-1 V 2.5.0] ختم الفئة الأصلية — نفس عقد أعمدة invoices الثلاثة أعلاه
    val origCurrency: String = "",
    val origTotal: Long = 0,
    val origFxMicros: Long = 0
)

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,               // DUE_REMIND / CHECK_REMIND / LOW_STOCK / AUTO_BACKUP
    val enabled: Boolean = true,
    val daysBefore: Int = 3,
    val lastRun: Long = 0
)

@Entity(tableName = "currencies")
data class Currency(
    @PrimaryKey val code: String,
    val nameAr: String,
    val nameEn: String,
    val symbol: String,
    val rateToBase: Double = 1.0,   // [P20-FIX agent10] وحدات هذه العملة لكل 1 من الأساسية (1 ريال = 0.2665 دولار حسب SeedDefaults) — المعادل الأساسي = المبلغ ÷ rate
    val isBase: Boolean = false,
    // [H4-3][v16] مزامنة — الرمز نفسه هوية، بلا أعمدة أصل
    val syncUpdatedAt: Long = 0
)

@Entity(
    tableName = "installment_plans",
    indices = [Index("partyId"), Index("direction")],
    // (M-3.3): خطة بلا طرف مستحيلة
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class InstallmentPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val partyId: Long,
    val direction: Int = 0,         // 0 لي (عميل يسدد لي)، 1 عليّ (أسدد لمورد)
    val total: Long,              // المبلغ الكلي للاتفاق [P33-P8] قروش
    val downPayment: Long = 0L,  // الدفعة المقدمة عند الفتح
    val financed: Long,           // المبلغ المُجدول فعلياً = total - down
    val months: Int,                // عدد الأقساط
    val startDate: Long,            // تاريخ استحقاق القسط الأول
    val currency: String = "SAR",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false
) {
    val isCustomerPlan: Boolean get() = direction == 0
}

@Entity(
    tableName = "installments",
    indices = [Index("planId"), Index("dueDate"), Index("status")],
    // (M-3.3): قسط يتيم بلا خطة مستحيل (CASCADE مع حذف الخطة)
    foreignKeys = [
        ForeignKey(
            entity = InstallmentPlan::class, parentColumns = ["id"], childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Installment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val seq: Int,                   // رقم القسط 1..n
    val amount: Long,  // [P33-P8] قروش
    val dueDate: Long,
    val paidAmount: Long = 0L,
    val paidDate: Long? = null,
    val status: Int = 0             // 0 مستحق 1 مدفوع 2 متأخر 3 جزئي (محسوب ديناميكياً أيضاً)
) {
    val open: Long get() = amount - paidAmount  // [P33-P8] مساواة تامة
}

/**
 * [P12-b] زيارة عميل: سجل GPS مؤرَّخ لطرف — جدول visits (ترحيل 7→8 في SuperBizApp).
 *
 * قرار تصميمي صادق: بلا مفاتيح أجنبية (no ForeignKey) عن قصد — الزيارة سجل تاريخي
 * يبقى بعد حذف طرفه (deleteRow في مسار الدمج فقط). أطراف محذوفة لا تظهر في التقرير
 * (VisitReport يربط بالربط بالمفتاح ويتخطى اليتامى)، فالصفوف اليتيمة بلا ضرر ولا
 * تمنع حذف الطرف (مقابل RESTRICT الذي كان سيمنع مسار الدمج القائم).
 * فهارس partyId (استعلام forParty) وvisitedAt (ترتيب القوائم تنازلياً).
 */
@Entity(
    tableName = "visits",
    indices = [Index("partyId"), Index("visitedAt")]
)
data class Visit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partyId: Long,
    val visitedAt: Long = System.currentTimeMillis(),
    val lat: Double? = null,        // موقع اللحظة عند التسجيل (قد يكون غائباً = زيارة بلا موقع)
    val lng: Double? = null,
    val note: String = "",
    // [H4-3][v16] مزامنة — partyRef هوية الطرف الأصلية "od|oid" (فارغة للأصلية المحلية)
    val partyRef: String = "",
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

// ═══════════════════════════════════════════════════════════════════════════
// [P17-a] كشف الحساب PDF — 8 جداول جديدة (ترحيل 8→9 في SuperBizApp):
// قوالب الكشف، التواقيع، الأختام، قوالب الملاحظات، الكشوف الصادرة،
// سجل إرسالها، قواعد التوليد التلقائي، وسجل التدقيق.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * [P17-a] قالب كشف محفوظ — إما نسخة معدَّلة من قالب مدمج (baseTemplateId = معرّف المدمج)
 * أو قالب مستقل تماماً (baseTemplateId = "CUSTOM"). إعدادات الشكل كلها JSON كي تبقى
 * الشاشة (17-b) والعارض (17-c) حُرَّين بتطوير الحقول دون ترحيلات جديدة.
 */
@Entity(tableName = "statement_templates")
data class StatementTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseTemplateId: String,        // معرّف القالب المدمج المنسوخ منه أو "CUSTOM"
    val configJson: String,            // إعدادات العرض (ألوان/أعمدة/شعار/حواشي) — JSON
    val isDefault: Boolean = false,    // القالب المطبق افتراضياً عند الإصدار
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // [H4-3][v16] مزامنة
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

/** [P17-a] تواقيع مالك العمل — صورة تُطبع أسفل الكشف؛ واحدة افتراضية بمعاملة Room (أول clearance) */
@Entity(tableName = "signatures")
data class SignatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val jobTitle: String? = null,      // المسمى الوظيفي المطبوع تحت الاسم
    val imagePath: String,             // ملف داخل filesDir
    val isDefault: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // [H4-3][v16] مزامنة
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

/** [P17-a] أختام الشركة — صورة تُطبع على الكشف؛ ختم افتراضي واحد بمعاملة Room */
@Entity(tableName = "stamps")
data class StampEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val imagePath: String,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // [H4-3][v16] مزامنة
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

/** [P17-a] قوالب ملاحظات جاهزة تُدرَج في حقل ملاحظات الكشف */
@Entity(tableName = "note_templates")
data class NoteTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val isDefault: Boolean = false,     // الملاحظة المعبأة افتراضياً في كشف جديد
    // [H4-3][v16] مزامنة
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

/**
 * [P17-a] كشف صادر — سجل غير قابل للتعديل بعد الإصدار (سند مالي):
 * statementNumber و verificationId فريدان بفهرس UNIQUE (التحقق المزدوج في شاشة 17-c)،
 * وcontentHash بصمة المحتوى (SHA-256 عبر StatementService) لا تخصّ الـPDF المرسوم
 * بل المحتوى المنطقي — رقم التحقق يقارنها لاحقاً. partyId بمفتاح RESTRICT لأن
 * الأطراف تُؤرشف ولا تُحذف (نفس عرف invoices).
 */
@Entity(
    tableName = "statements",
    indices = [
        Index(value = ["statementNumber"], unique = true),
        Index(value = ["verificationId"], unique = true),
        Index("partyId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class StatementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statementNumber: String,       // "STATEMENT-2026-000001"
    val verificationId: String,        // "SB-ST-20260924-000001"
    val partyId: Long,
    val fromTs: Long,                  // بداية الفترة (شاملة)
    val toTs: Long,                    // نهاية الفترة (شاملة)
    val templateId: String,            // القالب المستخدم عند الإصدار ("CUSTOM" أو معرّف مدمج أو "<id>")
    val currency: String,
    val contentHash: String,           // SHA-256 hex للمحتوى المنطقي — يُحسب عند issue لا عند assemble
    val filePath: String,              // ملف PDF داخل filesDir/pdfs
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lang: String                   // AR / EN / BILINGUAL
)

/**
 * [P17-a] سجل إرسال كشف — سطر واحد لكل (كشف، قناة): المحاولات تتزايد على السطر نفسه،
 * وdedupKey فريد يمنع ازدواج السجلات عند إعادة الجدولة. الحالة آلة حالات نصية:
 * PENDING → PROCESSING → SENT | FAILED؛ FAILED → RETRYING → PROCESSING؛ CANCELLED نهائية.
 * statementId بمفتاح CASCADE — حذف الكشف يمحو سجل إرساله (سجل تابع لا مستند مستقل).
 */
@Entity(
    tableName = "statement_deliveries",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index("statementId"),
        Index("status")
    ],
    foreignKeys = [
        ForeignKey(
            entity = StatementEntity::class, parentColumns = ["id"], childColumns = ["statementId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class StatementDeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statementId: Long,
    val channel: String,               // WHATSAPP / EMAIL / PRINT / SHARE / SMTP
    val status: String,                // PENDING/PROCESSING/SENT/FAILED/RETRYING/CANCELLED
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
    val scheduledFor: Long? = null,    // موعد مجدول (قواعد 17-c) — فوري إن كان null
    val dedupKey: String,              // "delivery:<statementId>:<channel>" — فريد
    val lastAttemptAt: Long? = null
)

/**
 * [P17-a] قاعدة توليد كشف تلقائي (جدولة 17-c عبر WorkManager):
 * partyMode ALL/SELECTED/SINGLE مع partyIdsJson، وfrequency دورية أو حدثية
 * (EVENT + eventFlagsJson = سداد دفعة/انتهاء فاتورة/تجاوز حد). weekday/dayOfMonth
 * اختياريان حسب التكرار، وsignatureId/stampId/templateId اختيارية (فارغة = الافتراضي).
 * nextRunAt مفهرس — استعلام المستجدات النصف ساعي في العامل يفحص enabled=1 AND nextRunAt<=now.
 */
@Entity(
    tableName = "statement_rules",
    indices = [Index("enabled"), Index("nextRunAt")]
)
data class StatementRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val partyMode: String,             // ALL / SELECTED / SINGLE
    val partyIdsJson: String,          // "[1,2,3]" لـSELECTED — يبقى "" لـALL/SINGLE
    val frequency: String,             // DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY/CUSTOM/EVENT
    val weekday: Int? = null,          // 1..7 (Calendar.DAY_OF_WEEK) لـWEEKLY
    val dayOfMonth: Int? = null,       // 1..31 (يُقص لطول الشهر) لـMONTHLY/QUARTERLY/YEARLY
    val hour: Int,                     // ساعة التوليد 0..23
    val minute: Int,                   // دقيقة التوليد 0..59
    val periodPreset: String,          // اسم StatementPeriodPreset للفترة المُكشَفة
    val templateId: String? = null,
    val signatureId: Long? = null,
    val stampId: Long? = null,
    val channel: String,               // قناة التسليم الافتراضية للقاعدة
    val eventFlagsJson: String? = null,// أعلام الأحداث لـEVENT — JSON
    val threshold: Long? = null,     // حد الرصيد لتوليد عند التجاوز (EVENT) [P33-P8] قروش
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null
)

/**
 * [P17-a] سجل تدقيق — إلحاق فقط (لا تعديل ولا حذف): إصدار كشف، إرسال، تعديل قاعدة.
 * actor حالياً "owner" دائماً (تطبيق مفرد المالك — 17-scan: لا نظام أدوار) لكن الحقل
 * محفوظ من اليوم كي لا يصبح الترحيل لازماً يوم يُضاف تعدد المستخدمين.
 *
 * [H1-4][v13] إسناد التدقيق للمستخدم — عمودان nullable مُلحقان بترحيل 12→13
 * (ALTER ADD بلا قيمة افتراضية — NULL دلالته «حدث قبل تبنّي RBAC أو حدث نظام»):
 *  actorId   = users.id للجلسة الصريحة لحظة الحدث
 *  actorRole = لقطة الدور لحظة الحدث (نسخة ملتصقة بلا join — عقد التصميم §4.1)
 * actor النصي يبقى اسم المستخدم للجلسات الجديدة و"owner" للتاريخية.
 */
@Entity(tableName = "audit_log", indices = [Index("ts"), Index("action")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actor: String,
    val action: String,                // STATEMENT_ISSUE / STATEMENT_SEND / STATEMENT_RULE_...
    val details: String,
    val ts: Long = System.currentTimeMillis(),
    val actorId: Long? = null,         // [H1-4][v13] users.id — NULL = قبل v13 / نظام
    val actorRole: Int? = null         // [H1-4][v13] لقطة الدور (0..3) لحظة الحدث
)

/** صف جدول جاهز لعرضه — يطبق InstallmentEngine على أسطر قاعدة البيانات */
data class InstallmentRow(
    val id: Long,
    val planId: Long,
    override val seq: Int,
    override val amount: Long,
    override val dueDate: Long,
    override val paidAmount: Long,
    val paidDate: Long?
) : com.superbiz.app.domain.InstallmentEngine.ScheduleRow

/** صف حركة للكشف: يمثل قيداً أو دفعة أو فاتورة موحدة العرض */
data class StatementRow(
    val date: Long,
    val title: String,
    // التعليق كان مقلوباً فيفسر التسميات بالمقلوب — القيد المدين
    // على حساب الطرف يزيد دينه (عليه)، والدائن ينقصه (له)؛ الرصيد موجب = يدين لك
    val debit: Long,   // عليه (يزيد دينه) [P33-P8] قروش
    val credit: Long,  // له (ينقص دينه)
    val balance: Long,
    val refType: String? = null,
    val refId: Long? = null
)

// ═══════════════════════════════════════════════════════════════════════
// [P46-W1] جولة 7 — نقاط الولاء والكوبونات (مخطط v12): جدولان جديدان
// بإنشاء فقط — لا جدول قائم يُمس ولا عمود يُضاف، أهدأ ترحيل بعد 10→11.
// ═══════════════════════════════════════════════════════════════════════

/**
 * دفتر نقاط الولاء — إلحاق فقط (لا تعديل ولا حذف صفوف تاريخية):
 * الكسب delta موجب وreason=EARN، والاستبدال delta سالب وreason=REDEEM،
 * وإلغاء فاتورة يكتب صفاً معوضاً delta=-مجموع أثرها وreason=VOID.
 * الرصيد = مجموع الدلتا (عقد LoyaltyP46: لا جدول أرصدة ينحرف عن دفتره).
 */
@Entity(
    tableName = "loyalty_entries",
    indices = [Index("partyId"), Index("invoiceId")],
    foreignKeys = [
        ForeignKey(
            entity = Party::class, parentColumns = ["id"], childColumns = ["partyId"],
            onDelete = ForeignKey.CASCADE   // نقاط الطرف بلا طرف بلا معنى — تذهب معه
        ),
        ForeignKey(
            entity = Invoice::class, parentColumns = ["id"], childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE   // الفواتير لا تُحذف عملياً (تُلغى) — الحارس بقي حارساً
        )
    ]
)
data class LoyaltyEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partyId: Long,
    val invoiceId: Long? = null,       // null = منح/استبدال يدوي بلا فاتورة
    val delta: Long,                   // + كسب / − استبدال / − تعويض إلغاء
    val reason: String,                // EARN / REDEEM / MANUAL_GRANT / MANUAL_REDEEM / VOID
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * كوبون خصم — مستقل بذاته: الثابت amountPiasters قروش (عقد P33) أو نسبة percent،
 * maxUses = 0 يعني بلا حد (عقد LoyaltyP46.checkSpec)، وexpiresAt = 0 يعني لا انتهاء.
 * usedCount يُرفع بتحديث ذرّي مشروط (CouponDao.consume) فلا يتجاوز الحد مهما تسابقت القراءات.
 */
@Entity(tableName = "coupons", indices = [Index(value = ["code"], unique = true)])
data class CouponEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,                  // فريد — البوابة LoyaltyP46.checkCoupon تبحث به
    val kind: Int = LoyaltyP46Kinds.KIND_FIXED, // 0 ثابت / 1 نسبة (ثوابت KIND_* أدناه)
    val amountPiasters: Long = 0L,     // KIND_FIXED — قروش
    val percent: Double = 0.0,         // KIND_PERCENT — 0..100
    val expiresAt: Long = 0L,          // 0 = لا انتهاء
    val maxUses: Int = 0,              // 0 = بلا حد
    val usedCount: Int = 0,
    val active: Boolean = true,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // [H4-3][v16] مزامنة
    val syncUpdatedAt: Long = 0,
    val originDeviceId: String = "",
    val originId: Long = 0
)

/** ثوابت نوع الكوبون — مرآة ثوابت LoyaltyP46 لاستخدامها في قيم افتراضية بلا استيراد حلقي */
object LoyaltyP46Kinds {
    const val KIND_FIXED = 0
    const val KIND_PERCENT = 1
}

// ═══════════════════════════════════════════════════════════════════════════
// [H1-3][H1-4][v13] هوية المستخدمين المحلية — RBAC على الجهاز الواحد
// (تصميم RBAC_V13_DESIGN.md §4 — جداول جديدة فقط، ترحيل إلحاقي ذرّي 12→13)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * مستخدم محلي للجهاز — هوية العرض والإسناد (لا كلمات مرور عارية هنا أبداً:
 * مادة الدخول في user_secrets مغلّفة كـ PinVault).
 *
 * role: ثابت الدور الرقمي (0 مالك / 1 مدير / 2 محاسب / 3 كاشير — Role.fromId
 * يحوّله في حدود التطبيق، وأي قيمة غريبة تعيد الأدنى صلاحية فشلاً مغلقاً).
 * الافتراض Kotlin 3 = الأدنى صلاحية (عقد «الافتراض مغلق» §2) — وهو نفسه لا
 * يظهر في مخطط SQL لأنRoom يولّد CREATE بلا DEFAULT (سابقة 11→12 حرفياً).
 *
 * هذه الجداول لا تُصدَّر في نسخ احتياطية JSON/Excel في الموجة الأولى (عقد
 * التصميم §4.3-5 + §6-5: أسرار المستخدمين لا تخرج من الجهاز إطلاقاً).
 */
@Entity(tableName = "users", indices = [Index("role"), Index("active")])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: Int = 3,                 // 0 مالك / 1 مدير / 2 محاسب / 3 كاشير — الافتراض الأدنى
    val active: Int = 1,               // تعطيل بلا حذف — التاريخ المحاسبي لا يُمس
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = 0
)

/**
 * سر دخول المستخدم — نفس صيغة PinVault حرفياً لكل مستخدم:
 *  pinWrapped = "ks:<ivHex>:<ctHex>" مغلّف بمفتاح Keystore (alias لكل مستخدم
 *               `superbiz_pin_u<id>` منذ v13؛ بذرة المالك المُرحّلة تبقى
 *               مغلّفة بالمفتاح الرئيسي ويُقرأ بتراجعٍ موثق في UserAuth).
 *  pinSalt    = ملح PBKDF2 الخاص بالمستخدم — داخل القاعدة لا DataStore لأن
 *               DataStore مخزن أحادي المالك لا يمكن تعميمه على N مستخدم
 *               (قرار تنفيذ موثق: امتداد عمودين على تصميم §4.1 — انظر
 *               ملاحظة التوثيق في RBAC_V13_DESIGN.md §4.1b).
 *  pinIters   = دورات PBKDF2 المخزنة (0 = 600k الحالية — نفس دلالة DataStore).
 *  biometricAllowed = 1 يسمح بفتح البصمة لهذا المستخدم (المالك فقط افتراضاً).
 *
 * صف بلا مستخدم مستحيل (CASCADE)، ومستخدم بلا صف سر = مستخدم لم يُنشأ له
 * رمز بعد (بذرة المالك بلا حماية قائمة — أول دخول يطلب إنشاء PIN).
 */
@Entity(
    tableName = "user_secrets",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE   // حذف المستخدم يمحو سره — لا أيتام أسرار
        )
    ]
)
data class UserSecretEntity(
    @PrimaryKey val userId: Long,
    val pinWrapped: String,
    val pinSalt: String,
    val pinIters: Int = 0,
    val biometricAllowed: Int = 0
)

/**
 * [Z2-أ V 1.5.0] أرشيف مستند ZATCA-2 — مستند UBL الرسمي للفاتورة كما صدر
 * أول مرة (O3 من تحليل الفجوات: أرشفة 6 سنوات داخل النسخ الاحتياطية القائمة).
 *
 * عقد الأرشيف الأول: صف الفاتورة لا يُستبدل أبداً (insert IGNORE) — سلسلة PIH
 * تتبع الهاش المحفوظ لحظة الإصدار، وتعديل الفاتورة لاحقاً لا يعيد كتابة التاريخ
 * (تصحيحات الامتثال بمستند دائن/مدين لاحقاً لا بإعادة كتابة الأرشيف).
 * بلا صف أرشيف = فاتورة غير مختومة (الإصدارات قبل V 1.5.0 أو بيانات البائع
 * غير مكتملة لحظة الإصدار).
 */
@Entity(
    tableName = "zatca_docs",
    foreignKeys = [
        ForeignKey(
            entity = Invoice::class, parentColumns = ["id"], childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE   // حذف الفاتورة (نظرياً عبر دمج/تنظيف) يمحو أرشيفها
        )
    ],
    indices = [Index("invoiceId")]
)
data class ZatcaDocEntity(
    @PrimaryKey val invoiceId: Long,
    val xml: String,               // مستند UBL 2.1 كامل بايتات الإصدار الأولى
    val xmlHash: String,           // Base64(SHA256(bytes(xml))) — مرجع PIH للفاتورة التالية
    val subtype: String,           // «0100000» قياسية / «0200000» مبسطة
    val issuedAt: Long,            // لحظة الإصدار الحائطية
    val reportedAt: Long = 0,      // لحظة آخر إبلاغ/تخليص ناجح (0 = لم يُبلَّغ)
    val rejectReason: String = "", // آخر سبب رفض معياري (فارغ = لا رفض)
    val attemptCount: Int = 0,     // محاولات فاشلة عابرة — يتتصفح التراجع الأسّي
    val clearedXml: String = ""    // [O3] النسخة المخلّصة الموقعة من الهيئة (القياسية) — فارغة للمبسطة
)

/**
 * [H4-3][v16] دفتر تغييرات المزامنة (ADR-002 D4) — سجل التغييرات المحلية الجاهزة
 * للدفع (imported=0، تكتبه مشغّلات SQLite) وذاكرة ساعات الشواهد المستوردة
 * (imported=1, deleted=1) لردّ الصفوف اليتيمة المتأخرة. يُقَطَّع ما دُفع (imported=0)
 * بعد نجاح الدفعة؛ الشواهد تبقى — هي صغيرة وتحمس بقاء قرار الحذف.
 * originDeviceId: '' لأصل محلي (يُستبدل بمعرّف الجهاز عند التصدير) — وللعملات: رمز العملة.
 */
@Entity(
    tableName = "sync_log",
    indices = [Index("imported"), Index("updatedAt")]
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tableName: String,
    val originDeviceId: String,
    val originId: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val imported: Boolean = false,
    val at: Long = System.currentTimeMillis()
)
