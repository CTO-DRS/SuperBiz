package com.superbiz.app.domain.statement

/**
 * [P17-a] نماذج كشف الحساب PDF — بيانات نقية بلا أي اعتماد أندرويد (JVM فقط).
 *
 * عقد ثابت لتمويج 17-b (الشاشة) و17-c (الجدولة/الإرسال): الأسماء والأنواع هنا
 * مرجعية — أي تغيير فيها يكسرهم، فلا يُعدَّل شيء بعد التثبيت إلا بتوافقهم.
 *
 * لا java.time (minSdk 24 بلا desugaring — نفس مبرر OverdueReminderPolicy/TimeMath):
 * كل التوقيتات epoch millis والتقويم java.util.Calendar بالمنطقة الافتراضية.
 */

/** فترات كشف جاهزة — CUSTOM يعني أن المستدعي يمرر نافذته الخاصة عبر periodRange */
enum class StatementPeriodPreset {
    TODAY,
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    LAST_3_MONTHS,
    LAST_6_MONTHS,
    THIS_YEAR,
    LAST_YEAR,
    CUSTOM
}

/**
 * اتجاه الرصيد النهائي — موجب يعني العميل مدين لك (DUE_ON_CUSTOMER)،
 * سالب يعني لك رصيداً في حساب العميل (IN_FAVOR_OF_CUSTOMER، مورد أو دفعة زائدة).
 */
enum class BalanceDirection { DUE_ON_CUSTOMER, IN_FAVOR_OF_CUSTOMER, BALANCED }

/** لغة الكشف — isRtl محسوب على StatementData: AR/BILINGUAL = true */
enum class StatementLang { AR, EN, BILINGUAL }

/**
 * مفاتيح أنواع سطور الكشف — قيم مكتوبة (string literals) لا enum كي تبقى JSON/SQL-صديقة،
 * ولأنها تصبر عبر حدود الاستعلام (وسم refType من journal يتحول إليها في repo).
 *
 * الخريطة من refType الدفتري (AccountingEngine — كلها بأحرف صغيرة):
 *   "invoice" → INVOICE ، "payment" → PAYMENT ، "debt" → DEBT ، "check" → CHECK ،
 *   "plan" → INSTALLMENT ، وأي شيء آخر ("tax"/"cash"/"expense"/"stock"/"edit"/"void"/null) → ADJUST.
 */
object StatementTxTypes {
    const val INVOICE = "INVOICE"
    const val PAYMENT = "PAYMENT"
    const val DEBT = "DEBT"
    const val CHECK = "CHECK"
    const val INSTALLMENT = "INSTALLMENT"
    const val ADJUST = "ADJUST"
}

/** سطر حركة في كشف الحساب — balance هو الرصيد الجاري بعد هذا السطر (متراكم لا مجموع جزئي) */
data class StatementTxRow(
    val ts: Long,
    val ref: String,          // مرجع عرض: "invoice#12" من refType+refId الدفتري (قد يكون "")
    val typeKey: String,      // من StatementTxTypes
    val desc: String,         // memo القيد كما هو (عربي غالباً) — الترجمة على العارض 17-b/c
    val debit: Long,          // عليه (يزيد دينه) [P33-P8] قروش
    val credit: Long,         // له (ينقص دينه)
    val balance: Long
)

/**
 * ملخص الكشف — علاقة الأرقام (موثقة ومحصورة في StatementService.summarize):
 *   totalInvoices  = Σ debit للسطور typeKey=INVOICE فقط
 *   totalPayments  = Σ credit للسطور typeKey=PAYMENT فقط
 *   totalDebit     = Σ كل المدين (يشمل DEBT/CHECK المرتجع/ADJUST المدين)
 *   totalCredit    = Σ كل الدائن
 *   due = final = opening + totalDebit - totalCredit
 * الافتتاحي (opening) رصيد ما قبل بداية الفترة؛ وdue/final يكملانه بالحركة داخل
 * الفترة فليسا «مجموع فواتير نظري» بل صافي الموقف بعد الفتح والإغلاق.
 */
data class StatementSummary(
    val opening: Long,          // [P33-P8] قروش
    val totalDebit: Long,
    val totalCredit: Long,
    val totalPayments: Long,
    val totalInvoices: Long,
    val totalDiscounts: Long,
    val due: Long,
    val final: Long,
    val direction: BalanceDirection
)

/** بطاقة الطرف على الكشف — الأعمدة الثمانية الجديدة [P17-a] اختيارية هنا كذلك */
data class StatementPartyInfo(
    val id: Long,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val taxNumber: String?,
    val crNumber: String?,
    val city: String?,
    val country: String?,
    val website: String?,
    val accountNumber: String?,
    val partyNo: String        // "P-000001" عبر StatementService.partyNumber
)

/** بطاقة شركة المالك — لقطة من DataStore لحظة التجميع (لا مرجع حي) */
data class StatementCompanyInfo(
    val businessName: String,
    val ownerName: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val city: String?,
    val country: String?,
    val website: String?,
    val taxNumber: String?,
    val crNumber: String?,
    val companyNo: String,     // معرّف عرض ثابت — لا يوجد نظام شركات متعدد (17-scan)
    val logoPath: String?,     // avatarPath من الإعدادات
    val photoPath: String?     // companyPhotoPath [P17-a] — صورة الشركة/السجل
)

/** حزمة الكشف الكاملة — كل ما يحتاجه العارض (17-b) والمدقق (17-c) */
data class StatementData(
    val party: StatementPartyInfo,
    val company: StatementCompanyInfo,
    val fromTs: Long,
    val toTs: Long,
    val currency: String,
    val rows: List<StatementTxRow>,
    val summary: StatementSummary,
    val statementNumber: String,
    val verificationId: String,
    val createdAt: Long,
    val note: String?,
    val lang: StatementLang
) {
    /** العربي والثنائي يُرسمان RTL — الإنجليزي وحده LTR */
    val isRtl: Boolean get() = lang != StatementLang.EN
}
