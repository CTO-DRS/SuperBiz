package com.superbiz.app.domain.algo

import com.superbiz.app.data.db.AccountSum
import com.superbiz.app.data.db.LedgerExportRow

/**
 * [D5] تصدير محاسبي موسع: دفتر أستاذ تفصيلي + ميزان مراجعة CSV.
 * نقي (JVM) — يمر كل حقل عبر CsvKit.escape (حرس حقن الصيغ الموحد) والملف UTF-8 مع BOM.
 * المبالغ عشرية بخانتين من القروش الصحيحة (بلا فاصل آلاف) كي تبقى أرقاماً في Excel.
 */
object AccountingCsv {

    val LEDGER_HEADERS = listOf(
        "date", "entry_id", "memo", "ref_type", "ref_id", "account_code", "account_name", "debit", "credit", "party_id"
    )
    val TRIAL_HEADERS = listOf("account_code", "account_name", "debit", "credit", "balance")

    /** قروش → «123.45» (سالب «-0.05» مدعوم) */
    fun amount(piasters: Long): String {
        val neg = piasters < 0
        val abs = if (neg) -piasters else piasters
        return (if (neg) "-" else "") + (abs / 100).toString() + "." + (abs % 100).toString().padStart(2, '0')
    }

    fun ledger(rows: List<LedgerExportRow>, name: (String) -> String, date: (Long) -> String): String =
        CsvKit.buildCsv(
            LEDGER_HEADERS,
            rows.map {
                listOf(
                    date(it.date), it.entryId.toString(), it.memo, it.refType ?: "", it.refId?.toString() ?: "",
                    it.account, name(it.account), amount(it.debit), amount(it.credit), it.partyId?.toString() ?: ""
                )
            },
            bom = true
        )

    /** ميزان المراجعة: صف لكل حساب بترتيب الرمز + صف إجمالي (المدين = الدائن في دفاتر متوازنة) */
    fun trialBalance(sums: List<AccountSum>, name: (String) -> String): String {
        val sorted = sums.sortedBy { it.account }
        val rows = sorted.map {
            listOf(it.account, name(it.account), amount(it.d), amount(it.c), amount(it.d - it.c))
        } + listOf(
            listOf("TOTAL", "", amount(sorted.sumOf { it.d }), amount(sorted.sumOf { it.c }),
                amount(sorted.sumOf { it.d - it.c }))
        )
        return CsvKit.buildCsv(TRIAL_HEADERS, rows, bom = true)
    }
}
