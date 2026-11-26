package com.superbiz.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * [OCR عربي] جسر Tesseract على الجهاز — نماذج ara+eng مضمنة في assets/tessdata.
 * الطبقة الوحيدة التي تلمس TessBaseAPI. بلا شبكة إطلاقًا؛ يعيد نصًا خامًا فقط
 * وكل التحليل المالي بعده في OcrInvoiceParser النقي.
 */
object TesseractOcr {

    private const val LANGS = "ara+eng"
    private const val MAX_SIDE = 2400

    /** نسخ النماذج من assets إلى filesDir/tessdata مرة واحدة (Tesseract يقرأ من مسار ملفات) */
    private fun ensureModels(context: Context): File {
        val dir = File(context.filesDir, "tessdata").apply { mkdirs() }
        for (name in listOf("ara.traineddata", "eng.traineddata")) {
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
        return context.filesDir
    }

    /** فك الصورة بحد أقصى للضلع — يحفظ الذاكرة ويحترم اتجاه EXIF على API 28+ */
    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val f = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                }
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > MAX_SIDE) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
            ?: throw java.io.IOException("تعذّر فتح الصورة")
    }

    /** يُستدعى من خيط IO — يعيد النص الخام (قد يكون فارغًا) */
    fun recognize(context: Context, uri: Uri): String {
        val dataPath = ensureModels(context).absolutePath
        val bmp = decode(context, uri)
        val api = TessBaseAPI()
        try {
            if (!api.init(dataPath, LANGS)) return ""
            api.setImage(bmp)
            return api.utF8Text.orEmpty()
        } finally {
            api.recycle()
            bmp.recycle()
        }
    }
}
