package com.superbiz.app.domain.sync

/**
 * [H4-3][ADR-002 D1] — Blake2b نقي (RFC 7693) بلا أي تبعية خارجية.
 *
 * لماذا يدوياً؟ سقف الجودة 10MB وميزانية D6 في ADR-002 (~120KB لطبقة المزامنة كاملة)
 * يمنعان إدخال BouncyCastle (~5-8MB) لمجرّد Argon2id. نفس تقليد المشروع:
 * ZatcaCsr (DER يدوي) وZatcaUbl (XML حتمي) — تعريفات معيارية مكتوبة نقية.
 *
 * العقد:
 * - طول ملخص متغير 1..64 بايت (يحتاجه Argon2id حرفياً في H0/H'/الختم النهائي).
 * - بلا مفتاح (keyed mode غير مستخدم — صفر مساحة هجوم زائدة).
 * - حتمي بالكامل، كل استدعاء [digest] مستقل بلا حالة مشتركة.
 *
 * التحقق: متجه RFC 7693 («abc» بطول 512) + متجه H0 الرسمي لـArgon2id (RFC 9106 §5.3)
 * في [Blake2bTest] — وكلاهما يقطع أي انحراف بنيوي.
 */
internal object Blake2b {

    private val IV = longArrayOf(
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bUL.toLong(), 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1UL.toLong(),
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fUL.toLong(), 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    )

    // 12 جولة × 16 فهرساً (RFC 7693 §2.7)
    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
    )

    private const val BLOCK = 128

    /** ملخص Blake2b بطول [outLen] بايت (1..64) على المدخل [data]. */
    fun digest(data: ByteArray, outLen: Int = 64): ByteArray {
        require(outLen in 1..64) { "blake2b outLen 1..64" }
        var h = IV.clone()
        // كتلة المعاملات (little-endian): digest_len=outLen، key_len=0، fanout=1، depth=1
        h[0] = h[0] xor (outLen.toLong() or (1L shl 16) or (1L shl 24))
        val len = data.size
        if (len == 0) {
            // رسالة فارغة: كتلة نهائية وحيدة بأصفار مع عدّاد 0
            h = compress(h, data, 0, 0L, true)
            return finish(h, outLen)
        }
        val fullBlocks = len / BLOCK
        for (i in 0 until fullBlocks) {
            val isLast = (i == fullBlocks - 1) && (len % BLOCK == 0)
            val counter = if (isLast) len.toLong() else ((i + 1) * BLOCK).toLong()
            h = compress(h, data, i * BLOCK, counter, isLast)
        }
        if (len % BLOCK != 0) {
            // الكتلة الأخيرة الناقصة (تشمل حالة رسالة أقصر من 128)
            h = compress(h, data, fullBlocks * BLOCK, len.toLong(), true)
        }
        return finish(h, outLen)
    }

    private fun finish(h: LongArray, outLen: Int): ByteArray {
        val out = ByteArray(outLen)
        for (i in 0 until outLen) out[i] = (h[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    /** ضغط كتلة تبدأ عند [offset] (تُقرأ 128 بايت، والباقي خارجهما أصفار ضمنية). */
    private fun compress(h: LongArray, data: ByteArray, offset: Int, counter: Long, last: Boolean): LongArray {
        val m = LongArray(16)
        for (i in 0 until 16) {
            var w = 0L
            val base = offset + i * 8
            for (j in 7 downTo 0) {
                val idx = base + j
                val b = if (idx < data.size) data[idx].toLong() and 0xFF else 0L
                w = (w shl 8) or b
            }
            m[i] = w
        }
        val v = LongArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[8 + i] = IV[i]
        v[12] = v[12] xor counter
        if (last) v[14] = v[14].inv()

        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        return h
    }

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        var va = v[a] + v[b] + x
        var vd = rotr64(v[d] xor va, 32)
        var vc = v[c] + vd
        var vb = rotr64(v[b] xor vc, 24)
        va += vb + y
        vd = rotr64(vd xor va, 16)
        vc += vd
        vb = rotr64(vb xor vc, 63)
        v[a] = va; v[b] = vb; v[c] = vc; v[d] = vd
    }

    private fun rotr64(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))
}
