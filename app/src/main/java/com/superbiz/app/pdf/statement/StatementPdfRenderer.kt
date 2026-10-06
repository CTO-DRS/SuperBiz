package com.superbiz.app.pdf.statement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.util.BarcodeGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * [P17-b] StatementPdfRenderer — الطبقة الأندرويد الرقيقة فوق المحرك الهندسي النقي.
 *
 * - الهندسة كلها تُحسب في [StatementLayout] (JVM نقي قابل للاختبار) ثم تُرسم هنا
 *   على Canvas داخل PdfDocument — نفس خط الإنتاج المجرَّب في InvoicePdf/A4Report
 *   (A4 72dpi، Paint ANTI_ALIAS، العربية تُرسم مباشرة عبر النص الكِيتي للنظام).
 * - الصور (الشعار/التوقيع/الخاتم/صورة الشركة) تمرَّر جاهزة من المستدعي — فكّها
 *   بيد المستدعي (17-c)؛ هنا الرسم فقط، وأي صورة null يُتخطى حيّزها بهدوء
 *   (المحرك حجّز الحيّز أصلاً).
 * - QR يُولَّد هنا من [StatementLayout.qrPayload] — مؤشر تحقق فقط بلا بيانات مالية.
 * - الكتابة ذرّية: .part ثم rename (نفس نمط InvoicePdf.kt:320-335).
 * - إحداثيات المحرك pt (72dpi) — marginDp تُحوَّل عبر [StatementLayout.DP2PT].
 */
object StatementPdfRenderer {

    data class RenderResult(val file: File, val pageCount: Int)

    // A4 72dpi — نفس ثوابت InvoicePdf
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f

    /** مقيّس نص فعلي بـ Paint.measureText — النظير الإنتاجي لمقايّس الاختبار */
    private class PaintMeasurer : TextMeasurer {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun width(text: String, sizeSp: Float, bold: Boolean): Float {
            p.textSize = sizeSp
            p.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            return p.measureText(text)
        }
    }

    suspend fun render(
        context: Context,
        d: StatementData,
        s: StatementStyle,
        logo: Bitmap?,
        signature: Bitmap?,
        stamp: Bitmap?,
        companyPhoto: Bitmap?,
        outDir: File,
        fileName: String
    ): RenderResult = withContext(Dispatchers.IO) {
        // landscape يبدّل البعدين — المحرك يستقبل الأبعاد الفيزيائية النهائية
        val pageW = if (s.landscape) PAGE_H else PAGE_W
        val pageH = if (s.landscape) PAGE_W else PAGE_H
        val margin = s.marginDp * StatementLayout.DP2PT

        val pages = StatementLayout.layout(d, s, pageW, pageH, margin, PaintMeasurer())

        outDir.mkdirs()
        val safeName = fileName.takeLast(120)
        val final = File(outDir, safeName)
        val part = File(outDir, "$safeName.part")

        val doc = PdfDocument()
        try {
            val measurer = PaintMeasurer()
            for (page in pages) {
                val info = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), page.index + 1).create()
                val p = doc.startPage(info)
                drawPage(p.canvas, page, d, logo, signature, stamp, companyPhoto, measurer)
                doc.finishPage(p)
            }
            FileOutputStream(part).use { doc.writeTo(it) }
        } catch (t: Throwable) {
            try { doc.close() } catch (_: Throwable) {}
            part.delete()
            throw t
        } finally {
            try { doc.close() } catch (_: Throwable) {}
        }
        if (!part.renameTo(final)) {
            // fallback: نسخ ثم حذف (نادر — عبر أنظمة ملفات مختلفة)
            part.copyTo(final, overwrite = true)
            part.delete()
        }
        RenderResult(final, pages.size)
    }

    // ─────────────────────────────── الرسم ───────────────────────────────

    private fun drawPage(
        c: Canvas,
        page: LPage,
        d: StatementData,
        logo: Bitmap?,
        signature: Bitmap?,
        stamp: Bitmap?,
        companyPhoto: Bitmap?,
        measurer: PaintMeasurer
    ) {
        // قواعد/خلفيات أولاً ثم الصور ثم النص — ترتيب طبقات ثابت
        for (r in page.rules) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = r.color }
            val rect = RectF(r.x, r.y, r.x + r.w, r.y + r.h)
            if (r.fill) {
                paint.style = Paint.Style.FILL
                c.drawRect(rect, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                c.drawRect(rect, paint)
            }
        }
        for (b in page.bitmaps) {
            val src = when (b.kind) {
                BitmapKind.LOGO -> logo
                BitmapKind.PHOTO -> companyPhoto
                BitmapKind.SIGNATURE -> signature
                BitmapKind.STAMP -> stamp
                BitmapKind.QR -> BarcodeGen.qr(StatementLayout.qrPayload(d.verificationId), 400)
            } ?: continue
            drawFitted(c, src, b.rect, b.opacity)
        }
        for (t in page.elements) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = t.sizeSp
                color = t.color
                typeface = if (t.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }
            // المحاذاة مخبوزة في rect.x (عقد المحرك) — النص يُرسم من حافته اليسرى
            // والأساس يوسّط العمودياً داخل صندوق السطر (نفس نتيجة textAlign في النمطين)
            val fm = paint.fontMetrics
            val baseline = t.rect.y + (t.rect.h - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.drawText(t.text, t.rect.x, baseline, paint)
        }
    }

    /** fit-center داخل المستطيل — بلا تشويه للنسب، مع alpha للشفافية */
    private fun drawFitted(c: Canvas, src: Bitmap, rect: LRect, opacity: Int) {
        if (rect.w <= 0f || rect.h <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = opacity.coerceIn(0, 255)
        }
        val scale = minOf(rect.w / src.width, rect.h / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = rect.x + (rect.w - w) / 2f
        val top = rect.y + (rect.h - h) / 2f
        c.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
    }
}
