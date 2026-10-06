package com.superbiz.app.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * — طباعة A4 مباشرة عبر إطار الطباعة في أندرويد (نظام الطباعة القياسي)
 *
 * يحوّل تقارير A4 المولّدة (PDF) إلى مهمة طباعة فعلية تظهر في حوار الطباعة النظامي،
 * فتُطبع على أي طابعة تظهر للنظام: واي فاي، بلوتوث (عبر خدمة Mopria أو إضافات
 * الشركات مثل HP/Canon/Epson/Brother)، سحابية، أو حفظ كـ PDF.
 * لا WebView ولا مكتبات خارجية — فقط PrintManager القياسي.
*/
object A4Print {

    /** يشغّل حوار طباعة النظام على ملف PDF جاهز بحجم ورق A4 */
    fun print(context: Context, file: File, jobName: String) {
        val safeJob = jobName.replace(Regex("[\\\\/:*?\"<>|]"), " ").ifBlank { "SuperBiz" }
        val printService = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: return
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printService.print(safeJob, PdfPrintAdapter(file, safeJob), attrs)
    }
}

/**
 * محوّل طباعة يقدّم ملف PDF موجود كما هو لإطار الطباعة:
 * onLayout يقرأ حجم الصفحة الفعلي من PdfRenderer، وonWrite ينسخ البايتات إلى الطابعة.
 */
class PdfPrintAdapter(
    private val file: File,
    private val jobName: String
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        var pageCount = 0
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageCount = renderer.pageCount
                    if (pageCount > 0) {
                        renderer.openPage(0).use { /* يقرأ الأبعاد ضمناً في المعلومات */ }
                    }
                }
            }
        } catch (_: Exception) {
            // إن تعذّرت القراءة نُكمل بالمعلومات العامة — النسخ في onWrite هو الفيصل
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(
                if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN
            )
            .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        try {
            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
                return
            }
            // احترام نطاقات الصفحات المطلوبة — الكل يُنسخ كما هو (فيكتور سليم)،
            // وأي نطاق جزئي يُقتطع عبر PdfRenderer بكتابة الصفحات المطلوبة فقط
            val total = pdfPageCount()
            val wanted = requestedIndices(pages, total)
            if (wanted == null || wanted.size == total) {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                return
            }
            if (wanted.isEmpty()) {
                callback.onWriteFailed("Requested page range is empty")
                return
            }
            writeSlice(wanted, destination)
            callback.onWriteFinished(mergeRanges(wanted))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**عدد صفحات الملف الفعلي — 0 إن تعذّرت قراءته (نطبع الكل كخطة أمان) */
    private fun pdfPageCount(): Int = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (_: Exception) {
        0
    }

    /**
 * : تحويل نطاقات الطباعة (1-based) إلى فهارس 0-based مرتبة بلا تكرار.
 * null = نطبع الكل (طلبات ALL_PAGES أو عدّ صفحات مجهول أو لا نطاقات).
*/
    private fun requestedIndices(pages: Array<out PageRange>?, total: Int): List<Int>? {
        if (total <= 0 || pages.isNullOrEmpty()) return null
        val out = sortedSetOf<Int>()
        for (r in pages) {
            if (r == PageRange.ALL_PAGES) return null
            val from = r.start.coerceIn(1, total)
            val to = r.end.coerceIn(1, total)
            for (p in from..to) out.add(p - 1)
        }
        return out.toList()
    }

    /**اقتطاع الصفحات المطلوبة إلى الوجهة — إعادة رسم كل صفحة عبر PdfRenderer في PdfDocument جديد */
    private fun writeSlice(indices: List<Int>, destination: ParcelFileDescriptor) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { src ->
            PdfRenderer(src).use { renderer ->
                val doc = PdfDocument()
                try {
                    for (idx in indices) {
                        renderer.openPage(idx).use { page ->
                            // نرسم بدقة مضاعفة ثم نضغط النتيجة إلى مقاس النقطة الأصلية للصفحة
                            val scale = 2
                            val bmp = Bitmap.createBitmap(
                                page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                            )
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val info = PdfDocument.PageInfo.Builder(
                                page.width, page.height, doc.pages.size + 1
                            ).create()
                            val p = doc.startPage(info)
                            p.canvas.drawBitmap(
                                bmp, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()),
                                Paint(Paint.FILTER_BITMAP_FLAG)
                            )
                            doc.finishPage(p)
                            bmp.recycle()
                        }
                    }
                    FileOutputStream(destination.fileDescriptor).use { doc.writeTo(it) }
                } finally {
                    // إغلاق المستند الوسيط دائماً حتى عند فشل الكتابة
                    runCatching { doc.close() }
                }
            }
        }
    }

    /**دمج الفهارس المكتوبة فعلاً إلى نطاقات PageRange متجاورة — تبليغ صادق عن المكتوب */
    private fun mergeRanges(indices: List<Int>): Array<PageRange> {
        val ranges = ArrayList<PageRange>()
        var start = indices.first() + 1
        var prev = start
        for (idx in indices.drop(1)) {
            val p = idx + 1
            if (p == prev + 1) {
                prev = p
                continue
            }
            ranges.add(PageRange(start, prev))
            start = p
            prev = p
        }
        ranges.add(PageRange(start, prev))
        return ranges.toTypedArray()
    }
}
