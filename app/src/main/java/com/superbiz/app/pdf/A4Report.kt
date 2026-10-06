package com.superbiz.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.domain.IncomeStatement
import com.superbiz.app.domain.TrialRow
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * — تقارير A4 عربية كاملة متعددة الصفحات
 *
 * على عكس DocPdf (صفحة واحدة تقتطع الزيادة) هذا المحرك يرصد كل الصفوف عبر
 * عدد صفحات غير محدود، مع ترويسة كاملة في الصفحة الأولى وترويسة مختصرة
 * (تتمة + ترقيم) لكل صفحة لاحقة، وتذييل بالتاريخ في كل صفحة.
 * كل النصوص تُقرأ من موارد التطبيق — عربي/إنجليزي حسب لغة التطبيق.
*/
object A4Report {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val BOTTOM_LIMIT = 58f          // منطقة التذييل المحجوزة

    private val violet = Color.parseColor("#7C3AED")
    private val cyan = Color.parseColor("#22D3EE")
    private val ink = Color.parseColor("#111827")
    private val gray = Color.parseColor("#6B7280")
    private val lineC = Color.parseColor("#E5E7EB")
    private val zebra = Color.parseColor("#F8F7FF")
    private val green = Color.parseColor("#059669")
    private val red = Color.parseColor("#DC2626")
    private val soft = Color.parseColor("#F3F0FF")
    private val slate = Color.parseColor("#334155")
    private val white = Color.WHITE

    /** صف ذمم في تقرير الأطراف — [P33-P8] الرصيد قروش Long */
    data class DebtorRow(val name: String, val phone: String, val kind: String, val balance: Long)

    // ═══════════════════ محرك المستند ═══════════════════

    private class Doc(
        private val ctx: Context,
        private val title: String,
        private val subtitle: String,
        private val dateLine: String,
        private val avatar: Bitmap?
    ) {
        private val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var c: Canvas? = null
        private var y = 0f
        private var pageNo = 0

        private fun t(res: Int) = ctx.getString(res)
        private fun t(res: Int, arg: Any) = ctx.getString(res, arg)

        /**لوحة الرسم تفحص مرة واحدة برسالة واضحة بدل !! متكرر بلا سياق */
        private fun cv(): android.graphics.Canvas = requireNotNull(c) { "A4Report: canvas not attached" }

        private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.RIGHT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; textAlign = align; isFakeBoldText = bold
            }

        /** ترويسة كاملة متدرجة للصفحة الأولى */
        private fun fullHeader() {
            val canvas = cv()
            val grad = LinearGradient(0f, 0f, PAGE_W.toFloat(), 150f, violet, cyan, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 150f, Paint().apply { shader = grad })
            val xR = PAGE_W - MARGIN - (if (avatar != null) 70f else 0f)
            canvas.drawText(title, xR, 56f, paint(24f, white, bold = true))
            canvas.drawText(subtitle, xR, 84f, paint(13f, white))
            canvas.drawText(dateLine, xR, 106f, paint(13f, white))
            if (avatar != null) {
                val av = Bitmap.createScaledBitmap(avatar, 56, 56, true)
                val circle = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
                val cc = Canvas(circle)
                val pp = Paint(Paint.ANTI_ALIAS_FLAG)
                cc.drawCircle(28f, 28f, 28f, pp)
                pp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                cc.drawBitmap(av, 0f, 0f, pp)
                canvas.drawBitmap(circle, PAGE_W - 100f, 32f, null)
            }
        }

        /** ترويسة مختصرة + ترقيم لصفحات التتمة */
        private fun compactHeader() {
            val canvas = cv()
            val grad = LinearGradient(0f, 0f, PAGE_W.toFloat(), 62f, slate, slate, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 62f, Paint().apply { shader = grad })
            val gradAcc = LinearGradient(
                PAGE_W - 150f, 0f, PAGE_W.toFloat(), 62f, violet, cyan, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, 110f, 62f, Paint().apply { shader = gradAcc })
            canvas.drawText(title, PAGE_W - MARGIN, 30f, paint(15f, white, bold = true))
            canvas.drawText(subtitle, PAGE_W - MARGIN, 50f, paint(11f, Color.parseColor("#CBD5E1")))
            canvas.drawText(t(R.string.a4_page, pageNo), MARGIN, 38f, paint(12f, white, bold = true, align = Paint.Align.LEFT))
        }

        /** تذييل كل صفحة — يُرسم لحظة فتح الصفحة (منطقة محجوزة لا يتجاوزها المحتوى) */
        private fun footer() {
            val canvas = cv()
            val pLine = Paint().apply { color = lineC; strokeWidth = 1f }
            val fy = PAGE_H - 34f
            canvas.drawLine(MARGIN, fy - 12f, PAGE_W - MARGIN, fy - 12f, pLine)
            val stamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
            val text = t(R.string.a4_page, pageNo) + "  •  " + t(R.string.a4_generated, stamp)
            canvas.drawText(text, PAGE_W / 2f, fy + 6f, paint(9.5f, gray, align = Paint.Align.CENTER))
            canvas.drawText(t(R.string.a4_footer_note), PAGE_W - MARGIN, fy + 6f, paint(9.5f, gray))
        }

        private fun startPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            c = p.canvas
            if (pageNo == 1) fullHeader() else compactHeader()
            footer()
            y = if (pageNo == 1) 190f else 104f
        }

        /** ضمان توفر مساحة عمودية — يفتح صفحة تتمة عند الحاجة */
        fun ensure(needed: Float) {
            if (c == null || y + needed > PAGE_H - BOTTOM_LIMIT) startPage()
        }

        fun sectionTitle(text: String): Float {
            ensure(52f)
            cv().drawText(text, PAGE_W - MARGIN, y, paint(15f, violet, bold = true))
            val pLine = Paint().apply { color = violet; strokeWidth = 2f; alpha = 60 }
            cv().drawLine(MARGIN, y + 8f, PAGE_W - MARGIN, y + 8f, pLine)
            y += 28f
            return y
        }

        fun keyValue(label: String, value: String, valueColor: Int = ink, bold: Boolean = false): Float {
            ensure(26f)
            cv().drawText(label, PAGE_W - MARGIN, y, paint(13f, gray))
            cv().drawText(value, MARGIN, y, paint(if (bold) 15f else 13f, valueColor, bold = bold, align = Paint.Align.LEFT))
            y += 22f
            return y
        }

        fun gap(h: Float): Float { y += h; return y }

        /** شريط أعمار الذمم — [P33-P8] المبالغ قروش Long؛ النسب وعرض الأعمدة تُحسب Double للعرض فقط */
        fun agingBar(labels: List<String>, amounts: List<Long>) {
            val maxA = amounts.maxOrNull()?.takeIf { it > 0L } ?: 1L
            val barMaxW = 260f
            val colors = listOf(green, cyan, Color.parseColor("#F59E0B"), red)
            for (i in amounts.indices) {
                ensure(26f)
                cv().drawText(labels[i], PAGE_W - MARGIN, y + 10f, paint(11f, gray))
                val w = ((amounts[i].toDouble() / maxA) * barMaxW).toFloat().coerceAtLeast(if (amounts[i] > 0L) 4f else 0f)
                cv().drawRoundRect(RectF(MARGIN + 76f, y, MARGIN + 76f + w, y + 12f), 6f, 6f, Paint().apply { color = colors[i] })
                val total = amounts.sum().coerceAtLeast(1L)
                val pct = if (amounts[i] > 0L) " (${Money.num(amounts[i].toDouble() / total * 100)}%)" else ""
                cv().drawText(Money.numP(amounts[i]) + pct, MARGIN, y + 10f, paint(10.5f, ink, align = Paint.Align.LEFT))
                y += 21f
            }
            y += 12f
        }

        /** صندوق إجماليات على يمين الصفحة */
        fun totalsBox(rows: List<Triple<String, String, Int>>, boldLast: Boolean = true) {
            val boxW = 320f
            ensure(30f + rows.size * 24f + 18f)
            val boxL = PAGE_W - MARGIN - boxW
            val boxH = 26f + rows.size * 24f + 14f
            cv().drawRoundRect(RectF(boxL, y, PAGE_W - MARGIN, y + boxH), 12f, 12f, Paint().apply { color = soft })
            var yy = y + 28f
            val xR = PAGE_W - MARGIN - 16f
            rows.forEachIndexed { i, (label, value, color) ->
                val isLast = i == rows.size - 1
                cv().drawText("$label:", xR, yy, paint(if (isLast && boldLast) 13.5f else 12.5f, if (isLast && boldLast) ink else gray, bold = isLast && boldLast))
                cv().drawText(value, boxL + 14f, yy, paint(if (isLast && boldLast) 15.5f else 12.5f, color, bold = isLast && boldLast, align = Paint.Align.LEFT))
                yy += if (isLast) 24f else 24f
            }
            y += boxH + 18f
        }

        // ── جدول كامل مع ترقيم صفحات وإعادة رسم رأس الجدول ──

        data class TCell(val text: String, val color: Int = ink, val bold: Boolean = false, val center: Boolean = false)
        data class TCol(val title: String, val width: Float, val center: Boolean = false)

        fun table(cols: List<TCol>, rows: List<List<TCell>>) {
            val tableW = cols.sumOf { it.width.toDouble() }.toFloat()
            val right = PAGE_W - MARGIN
            val left = right - tableW

            fun drawHeader() {
                val rect = RectF(left, y, right, y + 26f)
                cv().drawRoundRect(rect, 8f, 8f, Paint().apply { color = violet })
                var x = right
                for (col in cols) {
                    val cx = if (col.center) x - col.width / 2f else x - 10f
                    cv().drawText(
                        col.title, cx, y + 18f,
                        paint(11.5f, white, bold = true, align = if (col.center) Paint.Align.CENTER else Paint.Align.RIGHT)
                    )
                    x -= col.width
                }
                y += 34f
            }

            ensure(80f)
            drawHeader()
            val pLine = Paint().apply { color = lineC; strokeWidth = 1f }
            for ((idx, row) in rows.withIndex()) {
                ensure(34f)
                if (y <= 130f && pageNo > 1) drawHeader() // احتياط — رأس جدول بعد فتح صفحة جديدة
                if (idx % 2 == 1) {
                    cv().drawRect(RectF(left, y - 13f, right, y + 15f), Paint().apply { color = zebra })
                }
                var x = right
                for ((ci, cell) in row.withIndex()) {
                    val col = cols[ci]
                    val cx = if (col.center) x - col.width / 2f else x - 10f
                    cv().drawText(
                        cell.text, cx, y,
                        paint(11.5f, cell.color, bold = cell.bold, align = if (col.center || cell.center) Paint.Align.CENTER else Paint.Align.RIGHT)
                    )
                    x -= col.width
                }
                y += 6f
                cv().drawLine(left, y, right, y, pLine)
                y += 17f
            }
            y += 6f
        }

        fun finish(dir: File, name: String): File {
            page?.let { doc.finishPage(it) }
            dir.mkdirs()
            val f = File(dir, name)
            // كتابة إلى ملف مؤقت ثم إعادة تسمية — فشل الكتابة لا يترك PDF ناقصاً في files/pdfs
            val tmp = File(dir, "$name.part")
            try {
                tmp.outputStream().use { doc.writeTo(it) }
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
            } finally {
                // الإغلاق دائماً حتى مع فشل الكتابة — يمنع تسريب الذاكرة الأصلية
                runCatching { doc.close() }
                tmp.delete()
            }
            return f
        }

        init { startPage() }
    }

    // ═══════════════════ 1) تقرير الذمم ═══════════════════

    fun receivables(
        context: Context, businessName: String, currencySymbol: String, avatar: Bitmap?,
        // [P33-P8] المبالغ قروش Long — formatP/numP للعرض
        rows: List<DebtorRow>, totalAr: Long, totalAp: Long,
        debtors: Int, overdue: Long, aging: List<Long>
    ): File {
        val d = Doc(
            context,
            title = context.getString(R.string.a4_receivables_title),
            subtitle = businessName,
            dateLine = context.getString(R.string.date) + ": " + Dates.short(System.currentTimeMillis()),
            avatar = avatar
        )

        // الملخص
        d.sectionTitle(context.getString(R.string.a4_summary))
        d.keyValue(context.getString(R.string.a4_total_ar), Money.formatP(totalAr, currencySymbol), green, bold = true)
        d.keyValue(context.getString(R.string.a4_total_ap), Money.formatP(totalAp, currencySymbol), red, bold = true)
        d.keyValue(context.getString(R.string.a4_debtors), debtors.toString())
        d.keyValue(context.getString(R.string.a4_overdue), Money.formatP(overdue, currencySymbol), red)
        d.gap(10f)

        // أعمار الذمم
        d.sectionTitle(context.getString(R.string.rep_aging))
        d.agingBar(
            listOf(
                context.getString(R.string.a4_age0), context.getString(R.string.a4_age1),
                context.getString(R.string.a4_age2), context.getString(R.string.a4_age3)
            ),
            aging
        )

        // تفصيل أرصدة الأطراف — كل الصفوف عبر صفحات متعددة
        d.sectionTitle(context.getString(R.string.a4_parties_detail))
        val debtorC = context.getString(R.string.a4_status_debtor)
        val creditorC = context.getString(R.string.a4_status_creditor)
        val table = rows
            // [P33-P8] مساواة صحيحة بلا عتبة فاصلة عائمة
            .filter { kotlin.math.abs(it.balance) > 0L }
            .sortedByDescending { kotlin.math.abs(it.balance) }
        val cellRows = table.map { r ->
            listOf(
                Doc.TCell(r.name.take(24)),
                Doc.TCell(r.phone.take(15), gray),
                Doc.TCell(r.kind, gray, center = true),
                Doc.TCell(
                    if (r.balance > 0L) debtorC else creditorC,
                    if (r.balance > 0L) green else red, center = true
                ),
                Doc.TCell(Money.numP(r.balance), if (r.balance > 0L) green else red, bold = true, center = true)
            )
        }
        d.table(
            listOf(
                Doc.TCol(context.getString(R.string.name), 150f),
                Doc.TCol(context.getString(R.string.phone), 92f, center = true),
                Doc.TCol(context.getString(R.string.a4_col_kind), 74f, center = true),
                Doc.TCol(context.getString(R.string.a4_col_status), 66f, center = true),
                Doc.TCol(context.getString(R.string.balance), 113f, center = true)
            ),
            cellRows
        )

        val sumDebt = table.filter { it.balance > 0L }.sumOf { it.balance }
        val sumCredit = table.filter { it.balance < 0L }.sumOf { -it.balance }
        d.totalsBox(
            listOf(
                Triple(context.getString(R.string.a4_count_parties), table.size.toString(), ink),
                Triple(context.getString(R.string.a4_sum_debt), Money.formatP(sumDebt, currencySymbol), green),
                Triple(context.getString(R.string.a4_sum_credit), Money.formatP(sumCredit, currencySymbol), red)
            )
        )

        return d.finish(File(context.filesDir, "pdfs"), "receivables-${System.currentTimeMillis()}.pdf")
    }

    // ═══════════════════ 2) التقرير المالي الكامل ═══════════════════

    fun financial(
        context: Context, businessName: String, currencySymbol: String, avatar: Bitmap?,
        periodText: String,
        // [P33-P8] المبالغ قروش Long — formatP/numP للعرض؛ الكميات/النسب تبقى Double
        revenue: Long, otherIncome: Long, cogs: Long, expenses: Long,
        cash: Long,
        topCustomers: List<Pair<String, Long>>,
        topProducts: List<com.superbiz.app.data.repo.ReportsRepo.ProductSales>,
        aging: List<Long>,
        trial: List<TrialRow>
    ): File {
        val d = Doc(
            context,
            title = context.getString(R.string.a4_financial_title),
            subtitle = businessName,
            dateLine = context.getString(R.string.a4_period) + " " + periodText +
                "  •  " + context.getString(R.string.date) + ": " + Dates.short(System.currentTimeMillis()),
            avatar = avatar
        )

        // قائمة الأرباح
        val ist = IncomeStatement(revenue, otherIncome, cogs, expenses)
        d.sectionTitle(context.getString(R.string.a4_income_title))
        d.keyValue(context.getString(R.string.a4_revenue), Money.formatP(revenue, currencySymbol))
        if (otherIncome != 0L) d.keyValue(context.getString(R.string.a4_other_income), Money.formatP(otherIncome, currencySymbol))
        d.keyValue(context.getString(R.string.a4_cogs), Money.formatP(cogs, currencySymbol))
        d.keyValue(context.getString(R.string.a4_expenses), Money.formatP(expenses, currencySymbol))
        d.keyValue(context.getString(R.string.kpi_cash), Money.formatP(cash, currencySymbol))
        d.gap(6f)
        d.keyValue(context.getString(R.string.kpi_profit), Money.formatP(ist.netProfit, currencySymbol), if (ist.netProfit >= 0L) green else red, bold = true)
        d.gap(12f)

        // أعلى العملاء — كل الصفوف
        if (topCustomers.isNotEmpty()) {
            d.sectionTitle(context.getString(R.string.rep_top_customers))
            val rows = topCustomers.map { i ->
                listOf(
                    Doc.TCell(i.first.take(30)),
                    Doc.TCell(Money.numP(i.second), ink, bold = true, center = true)
                )
            }
            d.table(
                listOf(
                    Doc.TCol(context.getString(R.string.name), 380f),
                    Doc.TCol(context.getString(R.string.balance), 125f, center = true)
                ),
                rows
            )
        }

        // أعلى الأصناف — كل الصفوف
        if (topProducts.isNotEmpty()) {
            d.sectionTitle(context.getString(R.string.rep_top_products))
            val rows = topProducts.map { p ->
                listOf(
                    Doc.TCell(p.name.take(30)),
                    // [P33-P8] الكمية Double تبقى num؛ الإجمالي قروش عبر numP
                    Doc.TCell(Money.num(p.qty), gray, center = true),
                    Doc.TCell(Money.numP(p.total), ink, bold = true, center = true)
                )
            }
            d.table(
                listOf(
                    Doc.TCol(context.getString(R.string.item), 300f),
                    Doc.TCol(context.getString(R.string.qty), 100f, center = true),
                    Doc.TCol(context.getString(R.string.balance), 105f, center = true)
                ),
                rows
            )
        }

        // أعمار الذمم
        if (aging.size == 4 && aging.any { it > 0L }) {
            d.sectionTitle(context.getString(R.string.rep_aging))
            d.agingBar(
                listOf(
                    context.getString(R.string.a4_age0), context.getString(R.string.a4_age1),
                    context.getString(R.string.a4_age2), context.getString(R.string.a4_age3)
                ),
                aging
            )
        }

        // ميزان المراجعة الكامل
        if (trial.isNotEmpty()) {
            d.sectionTitle(context.getString(R.string.rep_trial))
            val isAr = context.resources.configuration.locales[0].language == "ar"
            val rows = trial.map { r ->
                listOf(
                    Doc.TCell((if (isAr) r.nameAr else r.nameEn).take(30)),
                    Doc.TCell(if (r.balance >= 0L) Money.numP(r.balance) else "—", center = true),
                    Doc.TCell(if (r.balance < 0L) Money.numP(-r.balance) else "—", center = true)
                )
            }
            // [P33-P8] مجاميع الميزان قروش Long — مساواة صحيحة بلا 0.0
            val dTot = trial.sumOf { if (it.balance >= 0L) it.balance else 0L }
            val cTot = trial.sumOf { if (it.balance < 0L) -it.balance else 0L }
            val allRows = rows.toMutableList()
            allRows += listOf(
                Doc.TCell("∑ " + context.getString(R.string.a4_totals), violet, bold = true),
                Doc.TCell(Money.numP(dTot), violet, bold = true, center = true),
                Doc.TCell(Money.numP(cTot), violet, bold = true, center = true)
            )
            d.table(
                listOf(
                    Doc.TCol(context.getString(R.string.item), 300f),
                    Doc.TCol(context.getString(R.string.debit), 102f, center = true),
                    Doc.TCol(context.getString(R.string.credit), 103f, center = true)
                ),
                allRows
            )
        }

        return d.finish(File(context.filesDir, "pdfs"), "financial-${System.currentTimeMillis()}.pdf")
    }

    // ═══════════════════ 3) كشف حساب طرف كامل ═══════════════════

    fun statement(
        context: Context, party: Party, rows: List<StatementRow>, balance: Long,
        businessName: String, currencySymbol: String, avatar: Bitmap?
    ): File {
        val d = Doc(
            context,
            title = context.getString(R.string.party_statement),
            subtitle = businessName,
            dateLine = context.getString(R.string.date) + ": " + Dates.short(System.currentTimeMillis()),
            avatar = avatar
        )

        // بيانات الطرف
        d.ensure(70f)
        d.gap(2f)
        d.keyValue(context.getString(R.string.name), party.name, violet, bold = true)
        if (party.phone.isNotBlank()) d.keyValue(context.getString(R.string.phone), party.phone)
        d.gap(8f)

        // جدول الحركات — كل الصفوف
        // [P33-P8] StatementRow قروش Long — العرض عبر numP
        val cellRows = rows.map { r ->
            listOf(
                Doc.TCell(Dates.short(r.date), gray, center = true),
                Doc.TCell(r.title.take(30)),
                Doc.TCell(if (r.debit > 0L) Money.numP(r.debit) else "—", center = true),
                Doc.TCell(if (r.credit > 0L) Money.numP(r.credit) else "—", center = true),
                Doc.TCell(Money.numP(r.balance), if (r.balance > 0L) green else if (r.balance < 0L) red else gray, bold = true, center = true)
            )
        }
        d.table(
            listOf(
                Doc.TCol(context.getString(R.string.date), 84f, center = true),
                Doc.TCol(context.getString(R.string.a4_col_desc), 178f),
                Doc.TCol(context.getString(R.string.debit), 84f, center = true),
                Doc.TCol(context.getString(R.string.credit), 84f, center = true),
                Doc.TCol(context.getString(R.string.balance), 75f, center = true)
            ),
            cellRows
        )

        val totD = rows.sumOf { it.debit }
        val totC = rows.sumOf { it.credit }
        d.totalsBox(
            listOf(
                Triple(context.getString(R.string.a4_sum_debit), Money.formatP(totD, currencySymbol), green),
                Triple(context.getString(R.string.a4_sum_credit_tot), Money.formatP(totC, currencySymbol), red),
                Triple(context.getString(R.string.statement_balance_is), Money.formatP(balance, currencySymbol), violet)
            )
        )

        return d.finish(File(context.filesDir, "pdfs"), "statement-${party.id}-${System.currentTimeMillis()}.pdf")
    }
}
