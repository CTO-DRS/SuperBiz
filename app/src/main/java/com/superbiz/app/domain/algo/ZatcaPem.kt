package com.superbiz.app.domain.algo

import java.security.MessageDigest

/**
 * [P16-a] أدوات تصدير المفتاح العام ZATCA: تغليف DER بغلاف PEM وبصمة SHA-256
 * بنمط openssl (hex صغير مفصولاً بنقطتين) — نقي تماماً بلا أي اعتماد Android،
 * قابل للاختبار بـ JUnit المجرد (نمط ZatcaStamp/ZatcaQr)، ويُستهلك من
 * ZatcaCard (ui) عبر ZatcaKeys.publicKeySpki كي يستطيع مُتحقِّق خارجي
 * (عميل/محاسب) مطابقة توقيع QR محلياً.
 *
 * قرارات التصميم المُثبَّتة:
 * - إدخال فارغ ⇒ سلسلة فارغة من كلتي الدالتين (عقد «لا بيانات ⇒ لا ناتج»
 *   متسق بينهما): PEM بلا جسم يزعم كذباً أنه مفتاح عام، فالأصدق ألّا يُنتَج
 *   أصلاً — والمُستهلك في الواجهة يعامله كـ«لا يوجد» مباشرة. مُثبَّت باختبار.
 * - Base64 مُنفَّذ محلياً (RFC 4648 بحشو «=») ثم يُقسَّم إلى أسطر 64 محرفاً،
 *   لا عبر java.util.Base64 (غير متاح قبل API 26 بينما minSdk 24 وبلا
 *   coreLibraryDesugaring في هذا المشروع — المرجع: رأس ZatcaQr)، ولا عبر
 *   إعادة استخدام ZatcaQr.toBase64 (ذلك سطر واحد بلا لفّ أسطر — دلالة مختلفة،
 *   والفصل مقصود كي يتطور ترميز QR وPEM باستقلال).
 * - كل الدوال دفاعية: لا استثناءات للخارج إطلاقاً.
 */
object ZatcaPem {

    private const val DEFAULT_LABEL = "PUBLIC KEY"
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * تغليف DER بغلاف PEM قياسي:
     * «-----BEGIN {label}-----\n» + Base64 بأسطر 64 محرفاً بالضبط (السطر الأخير
     * بأي طول ≤64) + «-----END {label}-----\n» — الفاصل \n وحده (لا \r)،
     * والمخرَج ينتهي بمنزلة واحدة بالضبط. إدخال فارغ ⇒ سلسلة فارغة (انظر الرأس).
     * التسمية تُنظَّف دفاعياً: الفراغ يُستبدل بالافتراضي، وCR/LF داخلها يُستبدل
     * بمسافة كي لا تكسر بنية الغلاف مهما كان المُدخل.
     */
    fun toPem(der: ByteArray, label: String = DEFAULT_LABEL): String {
        if (der.isEmpty()) return ""
        val lbl = label.trim()
            .replace("\r", " ")
            .replace("\n", " ")
            .ifBlank { DEFAULT_LABEL }
        // chunked(64) على Base64 السطر الواحد ⇒ كل الأسطر 64 محرفاً عدا الأخير
        val body = base64(der).chunked(64).joinToString("\n")
        return "-----BEGIN $lbl-----\n$body\n-----END $lbl-----\n"
    }

    /**
     * بصمة SHA-256 للـ DER بنمط openssl: hex صغير مقسوماً أزواجاً بنقطتين —
     * 32 بايتاً ⇒ 64 محرف hex + 31 نقطتين = 95 محرفاً. إدخال فارغ ⇒ سلسلة فارغة.
     */
    fun fingerprintSha256(der: ByteArray): String {
        if (der.isEmpty()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val sb = StringBuilder(digest.size * 3 - 1)
            for ((i, b) in digest.withIndex()) {
                if (i > 0) sb.append(':')
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            sb.toString()
        } catch (_: Exception) {
            "" // مستحيل عملياً (SHA-256 قياسي) — الدفاعية عقيدة الملف
        }
    }

    /**
     * Base64 سطر واحد بحشو «=» (RFC 4648) — تنفيذ محلي مستقل قياسي
     * (الحلقة الثلاثية المعروفة + بقايا 1/2 بايت)، سبب عدم الاعتماد على
     * منفّذَي المنصة/المشروع موثّق في رأس الملف.
     */
    private fun base64(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF) shl 16 or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(B64[(n shr 18) and 63])
                .append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63])
                .append(B64[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF) shl 16 or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(B64[(n shr 18) and 63])
                .append(B64[(n shr 12) and 63])
                .append(B64[(n shr 6) and 63])
                .append('=')
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
