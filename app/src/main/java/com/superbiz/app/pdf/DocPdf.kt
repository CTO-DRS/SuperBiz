package com.superbiz.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.domain.TrialRow
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import java.io.File

/**
 * محرك مستندات PDF عام — A4 — بأسلوب مطابق لترويسة InvoicePdf
 * يُنتج: كشف حساب طرف + التقرير المالي الدوري
 * كل النصوص تُمرر عبر Strings محلَّلة من الموارد (عربي/إنجليزي) عند الاستدعاء
 */
object DocPdf {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val BOTTOM_LIMIT = 70f          // منطقة التذييل المحجوزة في كل صفحة

    private val slate = Color.parseColor("#334155")
    private val violet = Color.parseColor("#7C3AED")
    private val cyan = Color.parseColor("#22D3EE")
    private val ink = Color.parseColor("#111827")
    private val gray = Color.parseColor("#6B7280")
    private val line = Color.parseColor("#E5E7EB")
    private val bgSoft = Color.parseColor("#F3F0FF")
    private val green = Color.parseColor("#059669")
    private val red = Color.parseColor("#DC2626")

    /** نصوص المستند — تُبنى من موارد التطبيق لتحترم اللغة الحالية */
    data class Strings(
        val statementTitle: String, val reportTitle: String,
        val partyLabel: String, val phoneLabel: String, val dateLabel: String,
        val colDate: String, val colDesc: String, val colDebit: String,
        val colCredit: String, val colBalance: String,
        val totalDebit: String, val totalCredit: String, val finalBalance: String,
        val periodLabel: String, val revenue: String, val otherIncome: String,
        val cogs: String, val expenses: String, val netProfit: String,
        val cash: String, val topCustomers: String, val topProducts: String,
        val agingTitle: String, val trialTitle: String,
        val colAccount: String, val colDr: String, val colCr: String,
        val aging0: String, val aging1: String, val aging2: String, val aging3: String,
        val footer: String, val noData: String
    )

    /**
 * : تُحلّ كل التسميات من موارد التطبيق (عربي/إنجليزي حسب لغة السياق) —
 * كانت مضمنة عربياً فيحصل مستخدم الإنجليزية على PDF عربي.
 * المفاتيح الموجودة تُعاد استخدامها، وما لا يقابل له مفتاح أُضيف ببادئة pdf_.
*/
    fun strings(context: Context): Strings = Strings(
        statementTitle = context.getString(R.string.party_statement),
        reportTitle = context.getString(R.string.pdf_report),
        partyLabel = context.getString(R.string.party),
        phoneLabel = context.getString(R.string.phone),
        dateLabel = context.getString(R.string.date),
        colDate = context.getString(R.string.date),
        colDesc = context.getString(R.string.a4_col_desc),
        colDebit = context.getString(R.string.debit),
        colCredit = context.getString(R.string.credit),
        colBalance = context.getString(R.string.balance),
        totalDebit = context.getString(R.string.a4_sum_debit),
        totalCredit = context.getString(R.string.a4_sum_credit_tot),
        finalBalance = context.getString(R.string.pdf_final_balance),
        periodLabel = context.getString(R.string.a4_period),
        revenue = context.getString(R.string.a4_revenue),
        otherIncome = context.getString(R.string.a4_other_income),
        cogs = context.getString(R.string.a4_cogs),
        expenses = context.getString(R.string.a4_expenses),
        netProfit = context.getString(R.string.pdf_net_profit),
        cash = context.getString(R.string.pdf_cash),
        topCustomers = context.getString(R.string.pdf_top_customers),
        topProducts = context.getString(R.string.pdf_top_products),
        agingTitle = context.getString(R.string.pdf_aging_title),
        trialTitle = context.getString(R.string.rep_trial),
        colAccount = context.getString(R.string.pdf_account),
        colDr = context.getString(R.string.pdf_dr),
        colCr = context.getString(R.string.pdf_cr),
        aging0 = context.getString(R.string.pdf_age0),
        aging1 = context.getString(R.string.pdf_age1),
        aging2 = context.getString(R.string.pdf_age2),
        aging3 = context.getString(R.string.pdf_age3),
        // [P6-M34 إصلاح] كان pdf_footer صلباً «SuperBiz » في الموارد رغم أن الإصدار 5.x —
        // الإصدار الآن من BuildConfig.VERSION_NAME عبر مقام %1$s (مفتاح REPLACE في p6_strings/6-e.txt)
        footer = context.getString(R.string.pdf_footer, com.superbiz.app.BuildConfig.VERSION_NAME),
        noData = context.getString(R.string.pdf_no_data)
    )

    // ─────────────────────────── أدوات الرسم ───────────────────────────

    private fun header(c: Canvas, title: String, subtitle: String, dateLine: String, avatar: Bitmap?): Float {
        val grad = android.graphics.LinearGradient(
            0f, 0f, PAGE_W.toFloat(), 150f,
            violet, cyan, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, PAGE_W.toFloat(), 150f, Paint().apply { shader = grad })

        if (avatar != null) {
            val av = Bitmap.createScaledBitmap(avatar, 56, 56, true)
            val circle = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
            val cc = Canvas(circle)
            val pp = Paint(Paint.ANTI_ALIAS_FLAG)
            cc.drawCircle(28f, 28f, 28f, pp)
            pp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            cc.drawBitmap(av, 0f, 0f, pp)
            c.drawBitmap(circle, PAGE_W - 100f, 32f, null)
        }
        c.drawText(title, PAGE_W - MARGIN - (if (avatar != null) 70f else 0f), 56f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 24f; isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            })
        val pBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13f; textAlign = Paint.Align.RIGHT
        }
        c.drawText(subtitle, PAGE_W - MARGIN - (if (avatar != null) 70f else 0f), 84f, pBody)
        c.drawText(dateLine, PAGE_W - MARGIN - (if (avatar != null) 70f else 0f), 106f, pBody)
        return 200f
    }

    private fun footer(c: Canvas, s: Strings) {
        c.drawText(s.footer, PAGE_W / 2f, PAGE_H - 30f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gray; textSize = 11f; textAlign = Paint.Align.CENTER
            })
    }

    /**
     * [P6-M28 إصلاح] ترويسة مختصرة لصفحات التتمة مع رقم الصفحة — بنمط A4Report:
     * شريط داكن + العنوان + التسمية الفرعية + رقم الصفحة على اليسار.
     * تعيد أعلى منطقة المحتوى (y) للصفحة الجديدة.
     */
    private fun compactHeader(c: Canvas, context: Context, s: Strings, pageNo: Int, title: String, subtitle: String): Float {
        c.drawRect(0f, 0f, PAGE_W.toFloat(), 62f, Paint().apply { color = slate })
        c.drawText(title, PAGE_W - MARGIN, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT
        })
        c.drawText(subtitle, PAGE_W - MARGIN, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1"); textSize = 11f; textAlign = Paint.Align.RIGHT
        })
        c.drawText(context.getString(R.string.a4_page, pageNo), MARGIN, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f; isFakeBoldText = true; textAlign = Paint.Align.LEFT
        })
        return 104f
    }

    private fun sectionTitle(c: Canvas, y: Float, text: String): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = violet; textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT
        }
        c.drawText(text, PAGE_W - MARGIN, y, p)
        val pLine = Paint().apply { color = violet; strokeWidth = 2f; alpha = 60 }
        c.drawLine(MARGIN, y + 8f, PAGE_W - MARGIN, y + 8f, pLine)
        return y + 26f
    }

    private fun keyValue(c: Canvas, y: Float, label: String, value: String, valueColor: Int = ink, bold: Boolean = false): Float {
        val pL = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gray; textSize = 13f; textAlign = Paint.Align.RIGHT
        }
        val pV = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor; textSize = if (bold) 15f else 13f; isFakeBoldText = bold
            textAlign = Paint.Align.LEFT
        }
        c.drawText(label, PAGE_W - MARGIN, y, pL)
        c.drawText(value, MARGIN, y, pV)
        return y + 22f
    }

    private fun tableHeader(c: Canvas, y: Float, cols: List<Pair<String, Float>>, bg: Int = violet): Float {
        val rect = RectF(MARGIN, y, PAGE_W - MARGIN, y + 28f)
        c.drawRoundRect(rect, 8f, 8f, Paint().apply { color = bg })
        val pHdr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 12f; textAlign = Paint.Align.RIGHT
        }
        // cols: (اسم، مركز النص من اليمين)
        for ((name, offset) in cols) c.drawText(name, PAGE_W - MARGIN - offset, y + 19f, pHdr)
        return y + 40f
    }

    private fun write(doc: PdfDocument, page: PdfDocument.Page, dir: File, name: String): File {
        doc.finishPage(page)
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

    // [P6-M28 إصلاح] حُذف renderStatement: كان كوداً ميتاً بلا أي مستدعٍ في المشروع كله —
    // كشوف حساب الأطراف تُنتَج عبر A4Report.statement متعدد الصفحات (أكمل وموطَّن).

    // ─────────────────────────── التقرير المالي ───────────────────────────

    /**
     * [P6-M28 إصلاح] التقرير المالي — ترقيم صفحات كامل بنمط A4Report: كانت الأقسام
     * تُقتطع صامتاً عند امتلاء الصفحة الواحدة («break») بينما الزر يظل فعّالاً بجوار
     * تقرير A4 الكامل فيحصل مستخدمان على تقريرين مختلفين. الآن: قائمة الأرباح في
     * الصفحة الأولى، والعملاء والأصناف والأعمار والميزان تتدفق عبر صفحات غير محدودة
     * بترويسة مختصرة مرقّمة وتذييل لكل صفحة ورأس قسم/جدول يتكرر في صفحات التتمة.
     */
    fun renderReport(
        context: Context, businessName: String, currencySymbol: String, avatar: Bitmap?,
        periodText: String,
        // [P33-P8] المبالغ قروش Long — formatP/numP للعرض؛ الكميات/النسب تبقى Double
        incomeRevenue: Long, incomeOther: Long, incomeCogs: Long, incomeExpenses: Long,
        cash: Long,
        topCustomers: List<Pair<String, Long>>,
        topProducts: List<Pair<String, Long>>,
        aging: List<Long>,
        trial: List<TrialRow>
    ): File {
        val s = strings(context)
        val doc = PdfDocument()
        try {
            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            var c = page.canvas

            var y = header(c, s.reportTitle, businessName,
                "${s.periodLabel}: $periodText • ${Dates.short(System.currentTimeMillis())}", avatar)

            // فتح صفحة تتمة: تذييل الحالية، ترويسة مختصرة مرقّمة، ثم متابعة القسم
            fun continuationPage() {
                footer(c, s)
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                c = page.canvas
                y = compactHeader(c, context, s, pageNo, s.reportTitle, businessName)
            }

            // قائمة الأرباح — قيم ثابتة تُرسم في الصفحة الأولى دائماً
            y = sectionTitle(c, y, s.netProfit + " — " + s.periodLabel)
            val ist = com.superbiz.app.domain.IncomeStatement(incomeRevenue, incomeOther, incomeCogs, incomeExpenses)
            y = keyValue(c, y, s.revenue, Money.formatP(incomeRevenue, currencySymbol))
            if (incomeOther != 0L) y = keyValue(c, y, s.otherIncome, Money.formatP(incomeOther, currencySymbol))
            y = keyValue(c, y, s.cogs, Money.formatP(incomeCogs, currencySymbol))
            y = keyValue(c, y, s.expenses, Money.formatP(incomeExpenses, currencySymbol))
            y = keyValue(c, y, s.cash, Money.formatP(cash, currencySymbol))
            y += 6f
            y = keyValue(c, y, s.netProfit, Money.formatP(ist.netProfit, currencySymbol),
                if (ist.netProfit >= 0L) green else red, bold = true)
            y += 14f

            // أعلى العملاء — كل الصفوف عبر صفحات غير محدودة
            if (topCustomers.isNotEmpty()) {
                // [P6-M28 إصلاح] العنوان لا يتيم أسفل الصفحة: عنوان + صف واحد كحد أدنى
                if (y + 60f > PAGE_H - BOTTOM_LIMIT) continuationPage()
                y = sectionTitle(c, y, s.topCustomers)
                for ((i, cust) in topCustomers.withIndex()) {
                    if (y + 24f > PAGE_H - BOTTOM_LIMIT) {
                        continuationPage()
                        y = sectionTitle(c, y, s.topCustomers) // رأس القسم يتكرر في كل صفحة تتمة
                    }
                    y = keyValue(c, y, "${i + 1}. ${cust.first}", Money.formatP(cust.second, currencySymbol))
                }
                y += 12f
            }

            // أعلى الأصناف — الترقيم نفسه بلا اقتطاع صامت
            if (topProducts.isNotEmpty()) {
                // [P6-M28 إصلاح] العنوان لا يتيم أسفل الصفحة: عنوان + صف واحد كحد أدنى
                if (y + 60f > PAGE_H - BOTTOM_LIMIT) continuationPage()
                y = sectionTitle(c, y, s.topProducts)
                for ((i, pr) in topProducts.withIndex()) {
                    if (y + 24f > PAGE_H - BOTTOM_LIMIT) {
                        continuationPage()
                        y = sectionTitle(c, y, s.topProducts) // رأس القسم يتكرر في كل صفحة تتمة
                    }
                    y = keyValue(c, y, "${i + 1}. ${pr.first}", Money.formatP(pr.second, currencySymbol))
                }
                y += 12f
            }

            // أعمار الذمم — قسم صغير يُرسم كتلة واحدة: إن لم تسعه بقايا الصفحة فُتحت تتمة
            // [P33-P8] مبالغ الأعمار قروش Long — النسبة وعرض العمود تُحسب Double للعرض فقط
            if (aging.size == 4 && aging.any { it > 0L }) {
                if (y + 140f > PAGE_H - BOTTOM_LIMIT) continuationPage()
                y = sectionTitle(c, y, s.agingTitle)
                val labels = listOf(s.aging0, s.aging1, s.aging2, s.aging3)
                val maxA = aging.max().takeIf { it > 0L } ?: 1L
                val barMaxW = 280f
                for ((i, amt) in aging.withIndex()) {
                    c.drawText(labels[i], PAGE_W - MARGIN, y + 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = gray; textSize = 11f; textAlign = Paint.Align.RIGHT
                    })
                    val w = ((amt.toDouble() / maxA) * barMaxW).toFloat().coerceAtLeast(if (amt > 0L) 4f else 0f)
                    c.drawRoundRect(RectF((MARGIN + 70f), y, (MARGIN + 70f) + w, y + 12f), 6f, 6f,
                        Paint().apply { color = if (i >= 2) red else violet })
                    c.drawText(Money.numP(amt), MARGIN, y + 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ink; textSize = 11f; textAlign = Paint.Align.LEFT
                    })
                    y += 20f
                }
                y += 12f
            }

            // ميزان المراجعة الكامل — كل الحسابات مع إعادة رأس القسم والجدول في صفحات التتمة
            if (trial.isNotEmpty()) {
                val trialCols = listOf(s.colAccount to 0f, s.colDr to 250f, s.colCr to 350f, s.colBalance to 450f)
                // [P6-M28 إصلاح] عنوان القسم ورأس الجدول لا يُرسمان قرب التذييل ثم تتيمة بلا صفوف:
                // نطلب مساحة عنوان + رأس جدول + صف واحد قبل البدء وإلا فُتحت تتمة
                if (y + 96f > PAGE_H - BOTTOM_LIMIT) continuationPage()
                y = sectionTitle(c, y, s.trialTitle)
                y = tableHeader(c, y, trialCols, bg = slate)
                val pCell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12f; textAlign = Paint.Align.RIGHT }
                val pLine = Paint().apply { color = line; strokeWidth = 1f }
                val isAr = context.resources.configuration.locales[0].language == "ar"
                for (r in trial) {
                    if (y + 22f > PAGE_H - BOTTOM_LIMIT) {
                        continuationPage()
                        y = sectionTitle(c, y, s.trialTitle)
                        y = tableHeader(c, y, trialCols, bg = slate)
                    }
                    val nm = if (isAr) r.nameAr else r.nameEn
                    // [P33-P8] TrialRow قروش Long — العرض عبر numP
                    c.drawText(nm.take(30), PAGE_W - MARGIN - 0f, y, pCell)
                    c.drawText(if (r.debit > 0L) Money.numP(r.debit) else "—", PAGE_W - MARGIN - 250f, y, pCell)
                    c.drawText(if (r.credit > 0L) Money.numP(r.credit) else "—", PAGE_W - MARGIN - 350f, y, pCell)
                    c.drawText(Money.numP(r.balance), PAGE_W - MARGIN - 450f, y, pCell)
                    y += 5f
                    c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, pLine)
                    y += 16f
                }
            }

            footer(c, s)
            return write(doc, page, File(context.filesDir, "pdfs"), "report-${System.currentTimeMillis()}.pdf")
        } finally {
            // حتى عند فشل الرسم في منتصف الطريق يُغلق المحرك — لا تسريب أصلي
            runCatching { doc.close() }
        }
    }

    /** مشاركة PDF عبر FileProvider */
    fun share(context: Context, file: File, title: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "com.superbiz.app.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, title).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
