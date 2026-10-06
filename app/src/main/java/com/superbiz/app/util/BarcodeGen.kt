package com.superbiz.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

object BarcodeGen {

    /** توليد باركود EAN-13 أو CODE_128 كصورة */
    fun ean13(content: String, width: Int = 600, height: Int = 200): Bitmap? =
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = MultiFormatWriter().encode(
                content, BarcodeFormat.EAN_13, width, height, hints
            )
            toBitmap(matrix, width, height)
        } catch (e: Exception) {
            code128(content, width, height)
        }

    /**
 * : كان الالتقاط WriterException فقط — بينما zxing يرمي
 * IllegalArgumentException للنصوص غير ASCII (اسم منتج عربي ملصوقاً في حقل الباركود)
 * أو الفارغة، والاستثناء يُفلت من هنا فيُسقط شاشة المخزون كلها. أي فشل ترميز
 * الآن ⇒ null صادق (تُعرض القيمة النصية بلا صورة) بدل انهيار التركيب.
*/
    fun code128(content: String, width: Int = 600, height: Int = 200): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(
            content, BarcodeFormat.CODE_128, width, height, hints
        )
        toBitmap(matrix, width, height)
    } catch (e: Exception) {
        null
    }

    fun qr(content: String, size: Int = 400): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints
        )
        toBitmap(matrix, size, size)
    } catch (e: Exception) {
        null
    }

    private fun toBitmap(m: com.google.zxing.common.BitMatrix, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        // مصفوفة ألوان واحدة ومكالمة setPixels واحدة — كانت ~120,000 مكالمة setPixel عبر JNI
        val px = IntArray(w * h)
        for (x in 0 until w) for (y in 0 until h) {
            px[y * w + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** حساب رقم تحقق EAN-13 من 12 رقماً */
    fun ean13Checksum(first12: String): Int {
        var sum = 0
        for ((i, ch) in first12.withIndex()) {
            val d = ch - '0'
            sum += if (i % 2 == 0) d else d * 3
        }
        return (10 - (sum % 10)) % 10
    }

    /** توليد باركود داخلي جديد: 200 + تسلسل */
    fun nextInternal(seq: Long): String {
        val base = "200" + seq.toString().padStart(9, '0')
        return base + ean13Checksum(base)
    }
}
