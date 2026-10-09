package com.superbiz.app.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.superbiz.app.R
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import java.io.File

/** مولّد PDF أصلي 100% عبر android.graphics.pdf.PdfDocument — A4 متعدد الصفحات */
object InvoicePdf {

    private const val PAGE_W = 595   // A4 72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val BOTTOM_LIMIT = 78f          // منطقة التذييل المحجوزة في كل صفحة

    private val violet = Color.parseColor("#7C3AED")
    private val cyan = Color.parseColor("#22D3EE")
    private val ink = Color.parseColor("#111827")
    private val gray = Color.parseColor("#6B7280")
    private val lineC = Color.parseColor("#E5E7EB")
    private val bgSoft = Color.parseColor("#F3F0FF")
    private val slate = Color.parseColor("#334155")
    private val white = Color.WHITE

    // عرض أعمدة جدول البنود (عمود الإجمالي يأخذ ما تبقى)
    private const val COL_ITEM_W = 250f
    private const val COL_QTY_W = 70f
    private const val COL_PRICE_W = 90f

    /**
     * [P6-M26 إصلاح] كانت البنود الزائدة عن صفحة واحدة تُسقَط مع سطر تنبيه فقط —
     * وثيقة مالية ناقصة يظهر إجماليها الكامل وبنودها جزئياً. الآن ترقيم صفحات كامل
     * بنمط A4Report: جدول بنود مواصل يعيد رأسه في كل صفحة، ترويسة كاملة في الأولى
     * ومختصرة مرقّمة للبقية، تذييل لكل صفحة، والإجماليات في الصفحة الأخيرة بعد
     * طباعة كل البنود بلا أي اقتطاع.
     */
    fun render(
        context: Context, invoice: Invoice, items: List<InvoiceItem>,
        party: Party?, businessName: String, currencySymbol: String, avatar: Bitmap?,
        vatNumber: String = ""
    ): File {
        // [P6-M27 إصلاح] كل النصوص تُحلّ من موارد التطبيق (مفاتيح inv_pdf_*) —
        // كانت عربية صلبة فيحصل مستخدم الإنجليزية على فاتورة PDF عربية
        // [P9-9a-ZATCA] الرقم الضريبي يمر للمحرك لرسم QR الزكاة عند وجوده
        // [P15-a] lineCount = عدد بنود الفاتورة (items.size) — يدخل XML القانوني
        // الموقَّع (وسم LineCount)؛ يُشتق من items هنا فلا يتغيّر توقيع render
        // ولا يحتاج مُستدعياها أي تعديل
        val e = Engine(context, businessName, invoice, avatar, vatNumber, items.size)
        e.partyBlock(party)
        e.itemsTable(items)
        // الإجماليات والتفقيط — دائماً في الصفحة الأخيرة بعد طباعة كل البنود
        // [P33-P8] المبالغ قروش Long — العرض عبر formatP/numP؛ taxRate نسبة Double تبقى num
        e.totalsBlock(
            subtotalLine = context.getString(R.string.inv_pdf_subtotal, Money.formatP(invoice.subtotal, currencySymbol)),
            discountLine = if (invoice.discount > 0L)
                context.getString(R.string.inv_pdf_discount, Money.formatP(invoice.discount, currencySymbol)) else null,
            taxLine = if (invoice.taxAmount > 0L)
                context.getString(R.string.inv_pdf_tax, Money.num(invoice.taxRate), Money.formatP(invoice.taxAmount, currencySymbol)) else null,
            totalLine = context.getString(R.string.inv_pdf_total, Money.formatP(invoice.total, currencySymbol)),
            paidLine = context.getString(
                R.string.inv_pdf_paid,
                Money.formatP(invoice.paid, currencySymbol), Money.formatP(invoice.open, currencySymbol)
            ),
            tafqitLine = if (com.superbiz.app.print.InvoiceTemplate.fromId(
                    com.superbiz.app.core.AppPrefs.invoiceTemplate
                ).showTafqit
            ) com.superbiz.app.domain.ArabicWords.amountInWords(
                // [P33-P8] التفقيط يستقبل ريال Double (خارج نطاق هذه الموجة) — الحدود via fromPiasters
                Money.fromPiasters(invoice.total), tafqitUnit(currencySymbol), tafqitFraction(currencySymbol)
            ) else ""
        )
        // [P20-FIX agent15]: رقم الفاتورة من استيراد قديم قد يحمل / \ : * ? — كان يُبنى به مسار
        // في مجلد غير موجود فيفشل التصدير نهائياً لكل فاتورة مصابة (FileNotFoundException)
        val safeNumber = invoice.number.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return e.finish(File(context.filesDir, "pdfs"), "invoice-$safeNumber.pdf")
    }

    /**
     * [P6-M26 إصلاح] محرك المستند متعدد الصفحات — بنفس بنية A4Report.Doc:
     * startPage/ensure يفتحان تتمة تلقائياً عند الحاجة ولا يُقتطع صفّ أبداً.
     */
    private class Engine(
        private val ctx: Context,
        private val businessName: String,
        private val invoice: Invoice,
        private val avatar: Bitmap?,
        // [P9-9a-ZATCA] الرقم الضريبي — فارغ = لا QR (منشأة غير مسجلة)
        private val vatNumber: String,
        // [P15-a] عدد بنود الفاتورة — حقل LineCount في XML القانوني الموقَّع
        private val lineCount: Int
    ) {
        private val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        private var c: Canvas? = null
        private var y = 0f
        private var pageNo = 0

        /** بالنمط ذاته: لوحة الرسم تفحص مرة واحدة برسالة واضحة */
        private fun cv(): Canvas = requireNotNull(c) { "InvoicePdf: canvas not attached" }

        private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.RIGHT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; textSize = size; textAlign = align; isFakeBoldText = bold
            }

        /** ترويسة كاملة متدرجة للصفحة الأولى (نفس تخطيط الفاتورة الأصلي) */
        private fun fullHeader() {
            val canvas = cv()
            val grad = LinearGradient(0f, 0f, PAGE_W.toFloat(), 150f, violet, cyan, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 150f, Paint().apply { shader = grad })
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
            val xR = PAGE_W - MARGIN - (if (avatar != null) 70f else 0f)
            canvas.drawText(businessName, xR, 52f, paint(24f, white, bold = true))
            // [H5-3 V 3.2.0] ملصق «فاتورة ضريبية» — إلزامي للفواتير الضريبية B2B في
            // ولايات الخليج الثلاث (SA/AE/BH) عند وجود الرقم الضريبي للبائع
            if (vatNumber.isNotBlank()) {
                canvas.drawText(ctx.getString(R.string.invoice_tax_caption), xR, 70f, paint(12f, white, bold = true))
                canvas.drawText(ctx.getString(R.string.inv_pdf_title, invoice.number), xR, 88f, paint(13f, white))
                canvas.drawText(Dates.short(invoice.date), xR, 106f, paint(13f, white))
                canvas.drawText(ctx.getString(R.string.inv_pdf_due_date, Dates.short(invoice.dueDate)), xR, 124f, paint(13f, white))
            } else {
                canvas.drawText(ctx.getString(R.string.inv_pdf_title, invoice.number), xR, 78f, paint(13f, white))
                canvas.drawText(Dates.short(invoice.date), xR, 98f, paint(13f, white))
                canvas.drawText(ctx.getString(R.string.inv_pdf_due_date, Dates.short(invoice.dueDate)), xR, 118f, paint(13f, white))
            }
        }

        /** ترويسة مختصرة + ترقيم لصفحات التتمة */
        private fun compactHeader() {
            val canvas = cv()
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 62f, Paint().apply { color = slate })
            canvas.drawText(businessName, PAGE_W - MARGIN, 30f, paint(15f, white, bold = true))
            canvas.drawText(
                ctx.getString(R.string.inv_pdf_title, invoice.number), PAGE_W - MARGIN, 50f,
                paint(11f, Color.parseColor("#CBD5E1"))
            )
            canvas.drawText(
                ctx.getString(R.string.a4_page, pageNo), MARGIN, 38f,
                paint(12f, white, bold = true, align = Paint.Align.LEFT)
            )
        }

        /** تذييل كل صفحة — رقم الصفحة + الإصدار من BuildConfig (وليس نصاً صلباً) */
        private fun footer() {
            val canvas = cv()
            val pLine = Paint().apply { color = lineC; strokeWidth = 1f }
            val fy = PAGE_H - 34f
            canvas.drawLine(MARGIN, fy - 12f, PAGE_W - MARGIN, fy - 12f, pLine)
            val text = ctx.getString(R.string.a4_page, pageNo) + "  •  " +
                ctx.getString(R.string.inv_pdf_footer, com.superbiz.app.BuildConfig.VERSION_NAME)
            canvas.drawText(text, PAGE_W / 2f, fy + 6f, paint(9.5f, gray, align = Paint.Align.CENTER))
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

        /** الطرف — اسم وهاتف في أعلى الصفحة الأولى */
        fun partyBlock(party: Party?) {
            ensure(52f)
            cv().drawText(party?.name ?: "—", PAGE_W - MARGIN, y, paint(15f, ink, bold = true))
            y += 20f
            // بلا !! — سلسلة نداء آمنة مكافئة تماماً
            party?.phone?.takeIf { it.isNotBlank() }?.let {
                cv().drawText(it, PAGE_W - MARGIN, y, paint(13f, gray)); y += 20f
            }
            y += 12f
        }

        /** جدول البنود — كل الصفوف عبر الصفحات مع إعادة رسم الرأس في كل صفحة جديدة */
        fun itemsTable(items: List<InvoiceItem>) {
            fun drawHeader() {
                ensure(34f)
                val colRight = PAGE_W - MARGIN
                cv().drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + 30f), 8f, 8f, Paint().apply { color = violet })
                val pHdr = paint(13f, white)
                cv().drawText(ctx.getString(R.string.inv_pdf_col_item), colRight - 12f, y + 20f, pHdr)
                cv().drawText(ctx.getString(R.string.inv_pdf_col_qty), colRight - COL_ITEM_W, y + 20f, pHdr)
                cv().drawText(ctx.getString(R.string.inv_pdf_col_price), colRight - COL_ITEM_W - COL_QTY_W, y + 20f, pHdr)
                cv().drawText(ctx.getString(R.string.inv_pdf_col_total), colRight - COL_ITEM_W - COL_QTY_W - COL_PRICE_W, y + 20f, pHdr)
                y += 42f
            }

            if (items.isEmpty()) {
                // [P6-M27 إصلاح] حالة الفاتورة بلا بنود برسالة من الموارد بدل جدول فارغ
                ensure(30f)
                cv().drawText(ctx.getString(R.string.inv_pdf_no_items), PAGE_W - MARGIN, y, paint(13f, gray))
                y += 26f
                return
            }

            drawHeader()
            val pCell = paint(13f, ink)
            val pLine = Paint().apply { color = lineC; strokeWidth = 1f }
            val colRight = PAGE_W - MARGIN
            for (it in items) {
                ensure(34f)
                if (pageNo > 1 && y <= 130f) drawHeader() // رأس الجدول يتكرر بعد فتح صفحة تتمة
                cv().drawText(it.desc.take(30), colRight - 12f, y, pCell)
                // [P33-P8] الكمية Double تبقى num؛ السعر وإجمالي السطر قروش عبر numP
                cv().drawText(Money.num(it.qty), colRight - COL_ITEM_W, y, pCell)
                cv().drawText(Money.numP(it.unitPrice), colRight - COL_ITEM_W - COL_QTY_W, y, pCell)
                cv().drawText(Money.numP(it.lineTotal), colRight - COL_ITEM_W - COL_QTY_W - COL_PRICE_W, y, pCell)
                y += 8f
                cv().drawLine(MARGIN, y, PAGE_W - MARGIN, y, pLine)
                y += 22f
            }
            y += 6f
        }

        /** صندوق الإجماليات + سطر التفقيط — كتلة واحدة مضمونة المكان في الصفحة الأخيرة */
        fun totalsBlock(
            subtotalLine: String, discountLine: String?, taxLine: String?,
            totalLine: String, paidLine: String, tafqitLine: String
        ) {
            val rows = listOfNotNull(subtotalLine, discountLine, taxLine)
            val boxH = 26f + rows.size * 20f + 40f
            // الصندوق وسطر التفقيط كتلة واحدة لا تنقسم بين صفحتين
            ensure(boxH + 44f)
            y += 10f
            val boxW = 240f
            val boxL = PAGE_W - MARGIN - boxW
            cv().drawRoundRect(RectF(boxL, y, PAGE_W - MARGIN, y + boxH), 12f, 12f, Paint().apply { color = bgSoft })
            var yy = y + 26f
            val xR = PAGE_W - MARGIN - 14f
            for (r in rows) {
                cv().drawText(r, xR, yy, paint(13f, ink))
                yy += 20f
            }
            cv().drawText(totalLine, xR, yy, paint(17f, violet, bold = true))
            yy += 24f
            cv().drawText(paidLine, xR, yy, paint(13f, gray))
            y += boxH + 8f
            // [P9-9a-ZATCA] رمز QR الضريبي (مرحلة-1) يسار صندوق الإجماليات عند وجود الرقم الضريبي —
            // حمولة TLV/Base64 من ZatcaQr (اسم البائع، الرقم الضريبي، طابع الفاتورة نفسه لا الزمن الحالي،
            // الإجمالي مع الضريبة، مبلغ الضريبة) — يرسم بحجم 84pt وسط شريط الصندوق مع إطار رفيع،
            // ويُتخطى بصمت عند تعذر توليد الصورة كي لا يُسقط فشل QR فاتورة صالحة
            // [P15-a] الحمولة ترتفع إلى مرحلة-2 (وسوم 6/7/8: هاش XML القانوني + توقيع EC
            // + المفتاح العام) عند تفعيل البصمة من الإعدادات وتوفر مفتاح الجهاز —
            // وإلا تبقى مرحلة-1 بايتاً ببايت كما في. أي فشل في أي حلقة
            // (المفتاح، التوقيع، التركيب) يعود للمرحلة-1 بصمت كي لا يُسقط فشل
            // التوقيع فاتورة صالحة.
            if (vatNumber.isNotBlank()) {
                // [P38-Z1] الحمولة String? (null نظرياً فقط عند رقم ضريبي فارغ — الحارس أعلاه
                // يمنعه، والفحص الصريح يحفظ النمط بلا انهيار) — والرسم يُتخطى بصمت كما كان
                val payload = stampedQrPayload()
                val qrBmp = payload?.let { com.superbiz.app.util.BarcodeGen.qr(it, 168) } // 2× للحدة
                if (qrBmp != null) {
                    val qrSize = 84f
                    val qrTop = y - boxH + (boxH - qrSize) / 2f
                    val qrRect = RectF(MARGIN, qrTop, MARGIN + qrSize, qrTop + qrSize)
                    cv().drawRoundRect(qrRect, 6f, 6f, Paint().apply { color = white })
                    cv().drawBitmap(qrBmp, null, qrRect, Paint().apply { isFilterBitmap = true })
                    cv().drawRoundRect(qrRect, 6f, 6f, Paint().apply { color = lineC; style = Paint.Style.STROKE; strokeWidth = 1f })
                }
            }
            // سطر التفقيط — «فقط... لا غير» يزيد موثوقية المستند المالي ويمنع التلاعب
            // أسماء العملة/الكسر مشتقة من رمز عملة الفاتورة (انظر tafqitUnit أدناه)
            ensure(24f)
            cv().drawText(tafqitLine, xR, y, paint(13f, ink))
            y += 24f
        }

        /**
 * [P15-a] حمولة QR الضريبي — المرحلة-1 افتراضياً (مطابقة بايتاً ببايت
 * مسار ZatcaQr.qrPayload نفسه دون أي تغليف منطقي إضافي)، وتُرفع للمرحلة-2
 * فقط عند: تفعيل ZatcaPrefs + توفر مُوقِّع AndroidKeyStore + نقطة مفتاح عام.
 *
 * [P38-Z1] المنطق كله وُحِّد في security/ZatcaPayload.qr — نقطة حقيقة واحدة
 * يشترك فيها PDF الفاتورة والإيصال الحراري، فلا تنحرف المسارات عند أول تعديل.
 * التوكيل هنا سلوكي حرفياً: نفس الترتيب والشروط وسلوك الفشل (مرحلة-1 بصمت)،
 * والعقد الحتمي (لقطات الفاتورة لا الزمن الحالي) محفوظ بتمرير invoice.date
 * وحقول القروش عبر Money.fromPiasters كما كان حرفياً.
*/
        private fun stampedQrPayload(): String? =
            // [P33-P8] ZatcaQr/ZatcaStamp تستقبل ريال Double (خارج نطاق هذه الموجة) — الحدود via fromPiasters
            com.superbiz.app.security.ZatcaPayload.qr(
                ctx, businessName, vatNumber, invoice.date,
                Money.fromPiasters(invoice.total), Money.fromPiasters(invoice.taxAmount),
                lineCount, invoice.number
            )

        /** إنهاء المستند وكتابته — كتابة إلى ملف مؤقت ثم إعادة تسمية (فشل الكتابة لا يترك PDF ناقصاً) */
        fun finish(dir: File, name: String): File {
            page?.let { doc.finishPage(it) }
            dir.mkdirs()
            val f = File(dir, name)
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

    /** مشاركة الملف عبر FileProvider */
    fun share(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "com.superbiz.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // كان عنوان نافذة المشاركة عربياً صلباً — من موارد اللغة الآن
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_invoice)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // أسماء التفقيط حسب رمز العملة — وحدها العملات المُهيّأة في التطبيق
    // قيد موثق: أسماء الوحدات هنا جزء من منطق التفقيط العربي (ArabicWords) ولا تُترجم حتى
    // يصبح المحرك نفسه متعدد اللغات
    private fun tafqitUnit(symbol: String): String = when (symbol) {
        "$" -> "دولار"
        "€" -> "يورو"
        "د.إ" -> "درهم"
        "ج.م" -> "جنيه"
        "ر.ي" -> "ريال"
        // [H5-3 V 3.2.0] البحرين — دينار بفلس مئوي (عقد P50-5 في ADR-002)
        "د.ب" -> "دينار"
        else -> "ريال"
    }

    private fun tafqitFraction(symbol: String): String = when (symbol) {
        "$", "€" -> "سنت"
        "د.إ" -> "فلس"
        "ج.م" -> "قرش"
        "ر.ي" -> "فلس"
        "د.ب" -> "فلس"
        else -> "هللة"
    }
}
