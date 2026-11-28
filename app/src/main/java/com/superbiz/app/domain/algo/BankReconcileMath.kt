package com.superbiz.app.domain.algo

/**
 * [Phase 2] تسوية بنكية نقية (JVM): تحليل كشف الحساب CSV ومطابقته مع حركات النقدية في الدفتر.
 * كل المبالغ قروش صحيحة بإشارة: موجب = دخول للحساب، سالب = خروج. التواريخ أيام منذ 1970 (epoch day).
 * المطابقة: مبلغ مطابق تماماً وفارق تاريخ ≤ toleranceDays؛ الأقرب تاريخاً أولاً، وكل حركة تُطابَق مرة واحدة.
 */
object BankReconcileMath {
    data class BankTx(val index: Int, val day: Long, val amount: Long, val description: String)
    data class BookTx(val id: Long, val day: Long, val amount: Long, val memo: String)
    data class Match(val bank: BankTx, val book: BookTx)
    data class Result(
        val matched: List<Match>,
        val bankOnly: List<BankTx>,
        val bookOnly: List<BookTx>
    ) {
        /** صافي حركات الكشف غير المطابقة (تحتاج قيداً في الدفتر) */
        val bankOnlyTotal: Long get() = bankOnly.sumOf { it.amount }
        /** صافي حركات الدفتر غير الظاهرة بعد في الكشف (شيكات/إيداعات معلقة) */
        val bookOnlyTotal: Long get() = bookOnly.sumOf { it.amount }
    }

    fun reconcile(bank: List<BankTx>, book: List<BookTx>, toleranceDays: Int = 3): Result {
        val free = book.sortedWith(compareBy({ it.day }, { it.id })).toMutableList()
        val matched = ArrayList<Match>()
        val bankOnly = ArrayList<BankTx>()
        for (b in bank.sortedWith(compareBy({ it.day }, { it.index }))) {
            var best = -1
            var bestGap = Long.MAX_VALUE
            for (i in free.indices) {
                val k = free[i]
                if (k.amount != b.amount) continue
                val gap = Math.abs(k.day - b.day)
                if (gap <= toleranceDays && gap < bestGap) { best = i; bestGap = gap }
            }
            if (best >= 0) matched += Match(b, free.removeAt(best)) else bankOnly += b
        }
        return Result(matched, bankOnly, free)
    }

    /** حركة كشف ستُسجَّل مصروفاً: المبلغ موجب بالقروش، اليوم epoch day، والبيان نص المصروف */
    data class ExpenseDraft(val day: Long, val amount: Long, val note: String)
    data class ImportPlan(val expenses: List<ExpenseDraft>, val inflowsSkipped: Int)

    /**
     * خطة استيراد الحركات غير المطابقة: الخارج (سالب) يصبح مصروفاً؛ الداخل (موجب) يُترك لأنه يحتاج فاتورة/تحصيلاً
     * ولا يُخمَّن مصدره. إعادة المطابقة بعد الاستيراد تجد المصروف في النقدية فلا يتكرر الاستيراد.
     */
    fun importPlan(bankOnly: List<BankTx>): ImportPlan {
        val out = bankOnly.filter { it.amount < 0L }
            .sortedWith(compareBy({ it.day }, { it.index }))
            .map { ExpenseDraft(it.day, -it.amount, it.description.trim()) }
        return ImportPlan(out, bankOnly.count { it.amount > 0L })
    }

    /** رصيد الدفتر المعدّل = رصيد الدفتر + صافي غير المطابق من الكشف − صافي غير المطابق من الدفتر.
     *  يساوي رصيد الكشف حين تكتمل التسوية: الفرق المتبقي = 0. */
    fun remainingDifference(bookBalance: Long, bankBalance: Long, r: Result): Long =
        (bookBalance + r.bankOnlyTotal) - (bankBalance + r.bookOnlyTotal)

    // ───────── تحليل CSV ─────────

    /** يفكّ CSV (RFC 4180: اقتباس، "" داخلي، CRLF/LF، BOM) إلى صفوف حقول. */
    fun parseCsv(text: String): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false
                } else f.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                else -> f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    /** «1,234.56» / «-12.5» / «(12.50)» / أرقام عربية-هندية → قروش، أو null إن لم يكن رقماً. */
    fun parseAmount(raw: String): Long? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in t) sb.append(when (ch) {
            in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
            in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
            '\u066B' -> '.'
            else -> ch
        })
        t = sb.toString().replace(",", "").replace("\u066C", "").replace(" ", "")
        var neg = false
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length - 1) }
        if (t.startsWith("-")) { neg = !neg; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
        val parts = t.split(".")
        if (parts.size > 2 || parts[0].isEmpty() && parts.size == 1) return null
        val whole = if (parts[0].isEmpty()) "0" else parts[0]
        if (!whole.all { it in '0'..'9' }) return null
        val frac = if (parts.size == 2) parts[1] else ""
        if (!frac.all { it in '0'..'9' }) return null
        val cents = (frac + "00").substring(0, 2)
        val v = (whole.toLongOrNull() ?: return null) * 100 + cents.toLong()
        return if (neg) -v else v
    }

    /** «2026-03-15» أو «15/03/2026» أو «15-03-2026» → epoch day، أو null. */
    fun parseDay(raw: String): Long? {
        val t = raw.trim().take(10)
        val p = t.split('-', '/')
        if (p.size != 3 || p.any { it.isEmpty() || !it.all { c -> c in '0'..'9' } }) return null
        val (y, m, d) = if (p[0].length == 4) Triple(p[0].toInt(), p[1].toInt(), p[2].toInt())
        else if (p[2].length == 4) Triple(p[2].toInt(), p[1].toInt(), p[0].toInt()) else return null
        if (m !in 1..12 || d !in 1..31) return null
        return java.time.LocalDate.of(y, m, 1).plusDays((d - 1).toLong()).toEpochDay()
    }

    data class Parsed(val txs: List<BankTx>, val skipped: Int)

    private val DATE = listOf("date", "تاريخ", "posting date", "value date")
    private val DESC = listOf("description", "details", "memo", "narrative", "البيان", "الوصف", "التفاصيل")
    private val AMT = listOf("amount", "المبلغ")
    private val DEBIT = listOf("debit", "withdrawal", "مدين", "سحب")
    private val CREDIT = listOf("credit", "deposit", "دائن", "إيداع", "ايداع")

    private fun find(h: List<String>, names: List<String>) =
        h.indexOfFirst { c -> names.any { c.trim().lowercase() == it || c.trim().lowercase().contains(it) } }

    /** كشف بنكي: يتعرّف على الأعمدة من الترويسة (date + amount، أو date + debit/credit). صفوف غير صالحة تُحصى skipped. */
    fun parseStatement(text: String): Parsed? {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return null
        val h = rows[0]
        val di = find(h, DATE)
        val ai = find(h, AMT)
        val dbi = find(h, DEBIT)
        val cri = find(h, CREDIT)
        val ti = find(h, DESC)
        if (di < 0 || (ai < 0 && dbi < 0 && cri < 0)) return null
        val out = ArrayList<BankTx>()
        var skipped = 0
        for ((n, r) in rows.drop(1).withIndex()) {
            fun cell(i: Int) = if (i in r.indices) r[i] else ""
            val day = parseDay(cell(di))
            val amt: Long? = if (ai >= 0) parseAmount(cell(ai)) else {
                val d = parseAmount(cell(dbi)) ?: 0L
                val c = parseAmount(cell(cri)) ?: 0L
                if (cell(dbi).isBlank() && cell(cri).isBlank()) null else Math.abs(c) - Math.abs(d)
            }
            if (day == null || amt == null) { skipped++; continue }
            out += BankTx(n, day, amt, if (ti >= 0) cell(ti).trim() else "")
        }
        return Parsed(out, skipped)
    }
}
