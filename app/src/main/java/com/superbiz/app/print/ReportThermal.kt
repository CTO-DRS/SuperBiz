package com.superbiz.app.print

import android.content.Context
import com.superbiz.app.R
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.domain.IncomeStatement
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money

/**
 * — ملخصات تقارير A4 كإيصالات حرارية (ورق 58/80 مم) عبر البلوتوث
 *
 * مكمّل للطباعة النظامية (A4Print): الطابعات الحرارية الصغيرة لا تظهر عادةً
 * في حوار طباعة النظام، لذا نعيد صياغة أهم أرقام كل تقرير كإيصال ESC/POS
 * يُطبع عبر القناة التسلسلية الموجودة منذ — بلا بيانات وهمية.
*/
object ReportReceiptFactory {

    /** ملخص التقرير المالي: قائمة الأرباح + النقدية + صافي الربح
     *  [P33-P8] المبالغ قروش Long — العرض عبر formatP (النسب/الكميات تبقى Double) */
    fun financial(
        ctx: Context, businessName: String, symbol: String, periodText: String,
        revenue: Long, otherIncome: Long, cogs: Long, expenses: Long, cash: Long
    ): EscPos.Receipt {
        val ist = IncomeStatement(revenue, otherIncome, cogs, expenses)
        val totals = mutableListOf<Pair<String, String>>(
            ctx.getString(R.string.a4_revenue) to Money.formatP(revenue, symbol)
        )
        if (otherIncome != 0L) {
            totals += ctx.getString(R.string.a4_other_income) to Money.formatP(otherIncome, symbol)
        }
        totals += ctx.getString(R.string.a4_cogs) to Money.formatP(cogs, symbol)
        totals += ctx.getString(R.string.a4_expenses) to Money.formatP(expenses, symbol)
        totals += ctx.getString(R.string.kpi_cash) to Money.formatP(cash, symbol)
        totals += ctx.getString(R.string.kpi_profit) to Money.formatP(ist.netProfit, symbol)

        return EscPos.Receipt(
            businessName = businessName.ifBlank { ctx.getString(R.string.business_default) },
            title = ctx.getString(R.string.a4_financial_title),
            dateText = Dates.short(System.currentTimeMillis()),
            partyName = periodText,
            lines = emptyList(),
            totals = totals,
            statusText = "",
            footer = ctx.getString(R.string.a4_footer_note)
        )
    }

    /** ملخص تقرير الذمم: ما لك / ما عليك / المدينون / المتأخر — [P33-P8] المبالغ قروش Long */
    fun receivables(
        ctx: Context, businessName: String, symbol: String,
        totalAr: Long, totalAp: Long, debtors: Int, overdue: Long
    ): EscPos.Receipt {
        val totals = listOf(
            ctx.getString(R.string.a4_total_ar) to Money.formatP(totalAr, symbol),
            ctx.getString(R.string.a4_total_ap) to Money.formatP(totalAp, symbol),
            ctx.getString(R.string.a4_debtors) to debtors.toString(),
            ctx.getString(R.string.a4_overdue) to Money.formatP(overdue, symbol)
        )
        return EscPos.Receipt(
            businessName = businessName.ifBlank { ctx.getString(R.string.business_default) },
            title = ctx.getString(R.string.a4_receivables_title),
            dateText = Dates.short(System.currentTimeMillis()),
            partyName = "",
            lines = emptyList(),
            totals = totals,
            statusText = "",
            footer = ctx.getString(R.string.a4_footer_note)
        )
    }

    /** ملخص كشف حساب طرف: آخر 10 حركات + المجاميع + الرصيد المستحق
     *  [P33-P8] StatementRow.debit/credit/balance قروش Long — العرض عبر numP/formatP */
    fun statement(
        ctx: Context, businessName: String, symbol: String, partyName: String,
        balance: Long, rows: List<StatementRow>
    ): EscPos.Receipt {
        val lines = rows.takeLast(10).map { r ->
            val amt = when {
                r.debit > 0L -> Money.numP(r.debit)
                r.credit > 0L -> "-" + Money.numP(r.credit)
                else -> "0"
            }
            EscPos.ItemLine(
                desc = Dates.short(r.date) + " " + r.title.take(20),
                qty = "", price = "", total = amt
            )
        }
        val totals = listOf(
            ctx.getString(R.string.a4_sum_debit) to Money.formatP(rows.sumOf { it.debit }, symbol),
            ctx.getString(R.string.a4_sum_credit_tot) to Money.formatP(rows.sumOf { it.credit }, symbol),
            ctx.getString(R.string.statement_balance_is) to Money.formatP(balance, symbol)
        )
        return EscPos.Receipt(
            businessName = businessName.ifBlank { ctx.getString(R.string.business_default) },
            title = ctx.getString(R.string.party_statement),
            dateText = Dates.short(System.currentTimeMillis()),
            partyName = partyName,
            lines = lines,
            totals = totals,
            statusText = "",
            footer = ctx.getString(R.string.a4_footer_note)
        )
    }
}
