package com.superbiz.app.domain

/**
 * خطة الحسابات الثابتة — قيد مزدوج حقيقي
 * [P33-P8] كل المبالغ قروش صحيحة (Long) — التوازن مساواة تامة بلا عتبات فاصلة عائمة
 * (كان الحرس abs(totalDebit − totalCredit) < 0.005 وسائر حروق نصف القرش مُبعثراً).
 */
object Accounts {
    const val CASH = "1010"
    const val RECEIVABLE = "1100"
    // [P6-M2 إصلاح]: حساب ضريبة المدخلات (أصل — قابلة للخصم من ضريبة المخرجات) —
    // كان شراء الرصيد يدخل ضريبة الشراء ضمن تكلفة المخزون فترسمل وتضخّم COGS لاحقاً
    const val INPUT_VAT = "1150"
    const val INVENTORY = "1200"
    const val PAYABLE = "2000"
    const val TAX_PAYABLE = "2100"
    const val EQUITY = "3010"
    const val SALES = "4010"
    const val OTHER_INCOME = "4020"
    const val COGS = "5010"
    const val EXPENSE = "5020"

    val names: Map<String, Pair<String, String>> = mapOf(
        CASH to ("النقد في الصندوق" to "Cash on hand"),
        RECEIVABLE to ("ذمم العملاء" to "Accounts receivable"),
        INPUT_VAT to ("ضريبة مدخلات" to "Input VAT"),
        INVENTORY to ("المخزون" to "Inventory"),
        PAYABLE to ("ذمم الموردين" to "Accounts payable"),
        TAX_PAYABLE to ("ضريبة مستحقة" to "Tax payable"),
        EQUITY to ("رأس المال" to "Equity"),
        SALES to ("المبيعات" to "Sales"),
        OTHER_INCOME to ("إيرادات أخرى" to "Other income"),
        COGS to ("تكلفة المبيعات" to "Cost of goods sold"),
        EXPENSE to ("المصروفات" to "Expenses")
    )
}

/** سطر قيد واحد — [P33-P8] قروش */
data class Line(
    val account: String,
    val debit: Long = 0L,
    val credit: Long = 0L,
    val partyId: Long? = null
)

/** مسودة قيد قبل حفظها في قاعدة البيانات */
data class JournalDraft(
    val date: Long,
    val memo: String,
    val refType: String? = null,
    val refId: Long? = null,
    val lines: List<Line>
) {
    val totalDebit: Long get() = lines.sumOf { it.debit }
    val totalCredit: Long get() = lines.sumOf { it.credit }
    // [P33-P8] مساواة تامة — القرش الواحد غير المتوازن يُرفض
    val balanced: Boolean get() = totalDebit == totalCredit
}

data class TrialRow(
    val account: String,
    val nameAr: String,
    val nameEn: String,
    val debit: Long,
    val credit: Long
) {
    val balance: Long get() = debit - credit
    // (M-2.10 توحيد): رصيد صفري لم يعد يُوسم بطبيعة مدينة — الطبيعة فقط لرصيد حقيقي
    val isDebitNature: Boolean get() = balance > 0L
}

data class IncomeStatement(
    val revenue: Long,
    val otherIncome: Long,
    val cogs: Long,
    val expenses: Long
) {
    val grossProfit: Long get() = revenue - cogs
    val netProfit: Long get() = revenue + otherIncome - cogs - expenses
    val margin: Double get() = if (revenue + otherIncome > 0) netProfit.toDouble() / (revenue + otherIncome) else 0.0
}

object AccountingEngine {

    /** فاتورة بيع: مدين ذمم العميل بالإجمالي / دائن المبيعات بالصافي + الضريبة، وقيود التكلفة */
    fun saleInvoice(
        subtotal: Long, discount: Long, taxAmount: Long, total: Long,
        cogs: Long, partyId: Long, date: Long, invoiceId: Long
    ): JournalDraft {
        val net = subtotal - discount
        val lines = mutableListOf(
            Line(Accounts.RECEIVABLE, debit = total, partyId = partyId),
            Line(Accounts.SALES, credit = net)
        )
        if (taxAmount > 0L) lines += Line(Accounts.TAX_PAYABLE, credit = taxAmount)
        if (cogs > 0L) {
            lines += Line(Accounts.COGS, debit = cogs)
            lines += Line(Accounts.INVENTORY, credit = cogs)
        }
        return JournalDraft(date, "فاتورة بيع #$invoiceId", "invoice", invoiceId, lines)
    }

    /**
     * فاتورة شراء: مدين المخزون بالصافي + ضريبة المدخلات بحسابها المستقل / دائن ذمم الموردين.
     * [P6-M2 إصلاح]: كانت الضريبة ترسمل داخل المخزون (net+tax في سطر واحد) فتتدفق لاحقاً
     * إلى COGS وتضخّمه. الآن: المخزون بالصافي فقط، والضريبة إلى INPUT_VAT (أصل مستقل يُقاص
     * مع ضريبة المخرجات) — مجموع المدين (net + tax) يبقى مطابقاً للدائن (total) كما كان،
     * فلا يتغير توازن القيد ولا دين الطرف (total) إطلاقاً.
     */
    fun purchaseInvoice(
        subtotal: Long, discount: Long, taxAmount: Long, total: Long,
        partyId: Long, date: Long, invoiceId: Long
    ): JournalDraft {
        val net = subtotal - discount
        val lines = mutableListOf(
            Line(Accounts.INVENTORY, debit = net),
            Line(Accounts.PAYABLE, credit = total, partyId = partyId)
        )
        if (taxAmount > 0L) lines += Line(Accounts.INPUT_VAT, debit = taxAmount)
        return JournalDraft(
            date, "فاتورة شراء #$invoiceId", "invoice", invoiceId,
            lines
        )
    }

    /** تحصيل من عميل: مدين النقد / دائن ذمم العميل */
    fun receipt(partyId: Long, amount: Long, date: Long, refId: Long? = null): JournalDraft =
        JournalDraft(date, "تحصيل دفعة", "payment", refId, listOf(
            Line(Accounts.CASH, debit = amount),
            Line(Accounts.RECEIVABLE, credit = amount, partyId = partyId)
        ))

    /** سداد لمورد: مدين الذمم / دائن النقد */
    fun supplierPayment(partyId: Long, amount: Long, date: Long, refId: Long? = null): JournalDraft =
        JournalDraft(date, "سداد لمورد", "payment", refId, listOf(
            Line(Accounts.PAYABLE, debit = amount, partyId = partyId),
            Line(Accounts.CASH, credit = amount)
        ))

    /**
     * [P6-M1 إصلاح]: سداد الضريبة المستحقة — كان TAX_PAYABLE يتراكم للأبد بلا مسار سداد
     * (فاتورة بيع تدّين ضريبة على المبيعات لا يوجد لها قيد سداد) فأي إبلاغ ضريبي كان سيتبالغ.
     * قيد مزدوج متوازن بمساواة تامة (P33-P8): مدين TAX_PAYABLE / دائن CASH.
     * الشروط: amount > 0، ولا يتجاوز رصيد TAX_PAYABLE الحالي (الرصيد يقرؤه المستدعي
     * من الدفتر: مجموع المدين − مجموع الدائن للحساب).
     * لا يغيّر بنية الجداول — قيد عادي عبر postJournal كبقية المسارات.
     */
    fun settleTaxPayable(
        amount: Long,
        date: Long,
        taxPayableBalance: Long,
        memo: String = "سداد الضريبة المستحقة",
        refId: Long? = null
    ): JournalDraft {
        require(amount > 0L) {
            "settlement must be positive: $amount"
        }
        require(amount <= taxPayableBalance) {
            "settlement $amount exceeds current TAX_PAYABLE balance $taxPayableBalance"
        }
        return JournalDraft(date, memo, "tax", refId, listOf(
            Line(Accounts.TAX_PAYABLE, debit = amount),
            Line(Accounts.CASH, credit = amount)
        ))
    }

    /** دين جديد لعميل (بيع آجل دون فاتورة): مدين ذمم / دائن المبيعات */
    fun newCustomerDebt(partyId: Long, amount: Long, date: Long, refId: Long? = null): JournalDraft =
        JournalDraft(date, "دين جديد", "debt", refId, listOf(
            Line(Accounts.RECEIVABLE, debit = amount, partyId = partyId),
            Line(Accounts.SALES, credit = amount)
        ))

    /** دين جديد من مورد (شراء آجل): مدين المخزون / دائن ذمم الموردين */
    fun newSupplierDebt(partyId: Long, amount: Long, date: Long, refId: Long? = null): JournalDraft =
        JournalDraft(date, "دين مورد جديد", "debt", refId, listOf(
            Line(Accounts.INVENTORY, debit = amount),
            Line(Accounts.PAYABLE, credit = amount, partyId = partyId)
        ))

    /** قبض نقدي عام: مدين النقد / دائن إيرادات أخرى */
    fun cashIn(amount: Long, date: Long, memo: String = "قبض نقدي"): JournalDraft =
        JournalDraft(date, memo, "cash", null, listOf(
            Line(Accounts.CASH, debit = amount),
            Line(Accounts.OTHER_INCOME, credit = amount)
        ))

    /** صرف نقدي: مدين المصروفات / دائن النقد */
    fun cashOut(amount: Long, date: Long, memo: String = "صرف نقدي"): JournalDraft =
        JournalDraft(date, memo, "cash", null, listOf(
            Line(Accounts.EXPENSE, debit = amount),
            Line(Accounts.CASH, credit = amount)
        ))

    /** تحصيل شيك وارد عند التحصيل: مدين النقد / دائن ذمم العميل */
    fun checkClearedIn(partyId: Long, amount: Long, date: Long, checkId: Long): JournalDraft =
        JournalDraft(date, "تحصيل شيك", "check", checkId, listOf(
            Line(Accounts.CASH, debit = amount),
            Line(Accounts.RECEIVABLE, credit = amount, partyId = partyId)
        ))

    /** ارتجاع شيك وارد: عكس القيد */
    fun checkBouncedIn(partyId: Long, amount: Long, date: Long, checkId: Long): JournalDraft =
        JournalDraft(date, "ارتجاع شيك", "check", checkId, listOf(
            Line(Accounts.RECEIVABLE, debit = amount, partyId = partyId),
            Line(Accounts.CASH, credit = amount)
        ))

    /** صفاء شيك صادر: مدين ذمم المورد / دائن النقد */
    fun checkClearedOut(partyId: Long, amount: Long, date: Long, checkId: Long): JournalDraft =
        JournalDraft(date, "صفاء شيك صادر", "check", checkId, listOf(
            Line(Accounts.PAYABLE, debit = amount, partyId = partyId),
            Line(Accounts.CASH, credit = amount)
        ))

    /**ارتجاع شيك صادر: عكس قيد الصفاء — مدين النقد / دائن ذمم المورد */
    fun checkBouncedOut(partyId: Long, amount: Long, date: Long, checkId: Long): JournalDraft =
        JournalDraft(date, "ارتجاع شيك صادر", "check", checkId, listOf(
            Line(Accounts.PAYABLE, credit = amount, partyId = partyId),
            Line(Accounts.CASH, debit = amount)
        ))

    /** فتح خطة تقسيط لعميل (بيع بالتقسيط): مدين ذمم العميل بالإجمالي / دائن المبيعات */
    fun installmentSale(partyId: Long, amount: Long, date: Long, planId: Long): JournalDraft =
        JournalDraft(date, "بيع بالتقسيط", "plan", planId, listOf(
            Line(Accounts.RECEIVABLE, debit = amount, partyId = partyId),
            Line(Accounts.SALES, credit = amount)
        ))

    /** فتح خطة تقسيط على المورد (شراء بالتقسيط): مدين المخزون / دائن ذمم الموردين */
    fun installmentPurchase(partyId: Long, amount: Long, date: Long, planId: Long): JournalDraft =
        JournalDraft(date, "شراء بالتقسيط", "plan", planId, listOf(
            Line(Accounts.INVENTORY, debit = amount),
            Line(Accounts.PAYABLE, credit = amount, partyId = partyId)
        ))

    /** دفعة مقدمة/تحصيل قسط من عميل: مدين النقد / دائن ذمم العميل */
    fun installmentPaidIn(partyId: Long, amount: Long, date: Long, planId: Long): JournalDraft =
        JournalDraft(date, "تحصيل قسط", "plan", planId, listOf(
            Line(Accounts.CASH, debit = amount),
            Line(Accounts.RECEIVABLE, credit = amount, partyId = partyId)
        ))

    /** سداد قسط للمورد: مدين ذمم المورد / دائن النقد */
    fun installmentPaidOut(partyId: Long, amount: Long, date: Long, planId: Long): JournalDraft =
        JournalDraft(date, "سداد قسط", "plan", planId, listOf(
            Line(Accounts.PAYABLE, debit = amount, partyId = partyId),
            Line(Accounts.CASH, credit = amount)
        ))

    /**
 * : إعداب خصم السداد المبكر — إغلاق بقاء الذمم بعد تحصيل المبلغ
 * المخفَّض. لعميل (اتجاه 0): مدين مصروف الخصم / دائن ذمم العملاء.
 * لمورد (اتجاه 1): مدين ذمم الموردين / دائن إيرادات أخرى (مكسب التسوية).
*/
    fun installmentEarlyWriteOff(partyId: Long, amount: Long, date: Long, planId: Long, customerPlan: Boolean): JournalDraft =
        if (customerPlan) JournalDraft(date, "خصم سداد مبكر", "plan", planId, listOf(
            Line(Accounts.EXPENSE, debit = amount),
            Line(Accounts.RECEIVABLE, credit = amount, partyId = partyId)
        ))
        else JournalDraft(date, "خصم سداد مبكر", "plan", planId, listOf(
            Line(Accounts.PAYABLE, debit = amount, partyId = partyId),
            Line(Accounts.OTHER_INCOME, credit = amount)
        ))

    /** تجميع سطور القيود في ميزان مراجعة */
    fun trialBalance(grouped: Map<String, Pair<Long, Long>>): List<TrialRow> =
        Accounts.names.map { (code, names) ->
            val sum = grouped[code] ?: (0L to 0L)
            TrialRow(code, names.first, names.second, sum.first, sum.second)
        }.filter { it.debit != 0L || it.credit != 0L }
}
