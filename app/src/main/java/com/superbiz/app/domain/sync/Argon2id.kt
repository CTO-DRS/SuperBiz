package com.superbiz.app.domain.sync

/**
 * [H4-3][ADR-002 D1] — Argon2id نقي (RFC 9106) فوق [Blake2b] المحلي.
 *
 * معاملات الإنتاج معلنة في ADR-002 D1 حرفياً: ذاكرة 64MiB، تكرارات 3، توازٍ 1،
 * وسم 256 بت. الملح ثابت معزول النطاق «superbiz-sync-v1» (16 بايت) — قرار موثق:
 * الجهازان يشتقان KEK مطابقاً من العبارة نفسها (شرط بصمة الإقران D5)، والقوة
 * الحقيقية في صعوبة Argon2id نفسها وتعقيد عبارة المرور — لا في سرية الملح.
 *
 * مطابقة معيارية كاملة: متجه RFC 9106 §5.3 (م=32KiB، ت=3، ع=4) بوسميه الوسيط
 * (H0) والنهائي مثبّت في [Argon2idTest]، مع متجه تصادمي بمعاملات الإنتاج
 * مولَّد من مكتبة مرجعية (argon2-cffi) ويُثبَّت كعقد.
 */
internal object Argon2id {

    const val MEMORY_KIB: Int = 65536      // 64 MiB — D1
    const val ITERATIONS: Int = 3          // D1
    const val PARALLELISM: Int = 1         // D1
    const val TAG_LEN: Int = 32            // 256 بت

    /** ثابت اشتقاق المزامنة — يضمن تطابق KEK بين الأجهزة المقترنة. */
    val DOMAIN_SALT: ByteArray = "superbiz-sync-v1".toByteArray(Charsets.UTF_8)

    private const val VERSION = 0x13
    private const val TYPE_ARGON2ID = 2
    private const val BLOCK_QWORDS = 128
    private const val SYNC_POINTS = 4

    /**
     * اشتقاق KEK من عبارة المرور. [memoryKib]/[iterations]/[parallelism] بمعاملاتها
     * الافتراضية للاستخدام الإنتاجي؛ الاختبارات قد تُصغّر الذاكرة لتسريع الدورة.
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray = DOMAIN_SALT,
        tagLen: Int = TAG_LEN,
        memoryKib: Long = MEMORY_KIB.toLong(),
        iterations: Int = ITERATIONS,
        parallelism: Int = PARALLELISM,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(parallelism >= 1) { "p >= 1" }
        require(memoryKib >= 8L * parallelism) { "m >= 8*p" }
        require(tagLen in 4..1024) { "tag length" }

        val mPrime = 4L * parallelism * (memoryKib / (4L * parallelism))
        val q = mPrime / parallelism
        val mem = LongArray((mPrime * BLOCK_QWORDS).toInt())

        // 1) H0 — الشكل 1
        val h0Input = object : java.io.ByteArrayOutputStream() {}.apply {
            le32(parallelism); le32(tagLen); le32(memoryKib.toInt()); le32(iterations)
            le32(VERSION); le32(TYPE_ARGON2ID)
            le32(password.size); write(password, 0, password.size)
            le32(salt.size); write(salt, 0, salt.size)
            // K وX اختياريان — حقل الطول يبقى حتى عند الغياب (نص RFC حرفياً)
            le32(secret?.size ?: 0); secret?.let { write(it, 0, it.size) }
            le32(associatedData?.size ?: 0); associatedData?.let { write(it, 0, it.size) }
        }.toByteArray()
        val h0 = Blake2b.digest(h0Input, 64)

        // 3/4) كتلتا بداية كل مسار — الشكلان 3/4
        val initTail = ByteArray(8)
        for (lane in 0 until parallelism) {
            for (idx in 0..1) {
                writeLe32(initTail, 0, idx)
                writeLe32(initTail, 4, lane)
                val input = h0 + initTail
                val blockBytes = hPrime(input, 1024)
                var off = (lane * q + idx).toInt() * BLOCK_QWORDS
                for (w in 0 until BLOCK_QWORDS) mem[off + w] = readLe64(blockBytes, w * 8)
            }
        }

        // 5/6) الملء شريحياً — §3.3/§3.4
        val segLen = (q / SYNC_POINTS).toInt()
        val zero = LongArray(BLOCK_QWORDS)
        val prevBlock = LongArray(BLOCK_QWORDS)
        val refBlock = LongArray(BLOCK_QWORDS)
        val outBlock = LongArray(BLOCK_QWORDS)
        val addressInput = ByteArray(1024)
        val addressBlockA = LongArray(BLOCK_QWORDS)
        val addressBlock = LongArray(BLOCK_QWORDS)

        for (pass in 0 until iterations) {
            for (slice in 0 until SYNC_POINTS) {
                val dataIndependent = (pass == 0 && slice < 2)
                for (lane in 0 until parallelism) {
                    var addresses: LongArray? = null
                    if (dataIndependent) {
                        java.util.Arrays.fill(addressInput, 0)
                        // Z = LE64(r)||LE64(l)||LE64(sl)||LE64(m')||LE64(t)||LE64(y) — الشكل 11
                        writeLe64(addressInput, 0, pass.toLong())
                        writeLe64(addressInput, 8, lane.toLong())
                        writeLe64(addressInput, 16, slice.toLong())
                        writeLe64(addressInput, 24, mPrime)
                        writeLe64(addressInput, 32, iterations.toLong())
                        writeLe64(addressInput, 40, TYPE_ARGON2ID.toLong())
                        addresses = generateAddresses(addressInput, zero, addressBlockA, addressBlock, segLen)
                    }
                    val startJ = if (pass == 0 && slice == 0) 2 else 0
                    for (j in startJ until segLen) {
                        val blockIndex = lane * q + slice * segLen + j
                        val withinLane = slice * segLen + j
                        // الكتلة السابقة: التفاف عند بداية المسار فقط (شرط C المرجعي)
                        val prevIndex = if (withinLane == 0) lane * q + q - 1 else blockIndex - 1
                        var j1: Long; var j2: Long
                        if (dataIndependent) {
                            val x = addresses!![j]
                            j1 = x and 0xFFFFFFFFL
                            j2 = x ushr 32
                        } else {
                            val pw = (prevIndex * BLOCK_QWORDS).toInt()
                            j1 = mem[pw] and 0xFFFFFFFFL
                            j2 = mem[pw] ushr 32
                        }
                        val ref = referenceBlock(pass, slice, lane, j, j1, j2, parallelism, q.toInt(), segLen)
                        System.arraycopy(mem, (prevIndex * BLOCK_QWORDS).toInt(), prevBlock, 0, BLOCK_QWORDS)
                        System.arraycopy(mem, (ref * BLOCK_QWORDS).toInt(), refBlock, 0, BLOCK_QWORDS)
                        val withXor = pass > 0
                        compressG(prevBlock, refBlock, outBlock, withXor, mem, (blockIndex * BLOCK_QWORDS).toInt())
                    }
                }
            }
        }

        // 7) الكتلة النهائية — XOR العمود الأخير — الشكل 7
        val finalBlock = LongArray(BLOCK_QWORDS)
        for (lane in 0 until parallelism) {
            val off = ((lane * q + q - 1) * BLOCK_QWORDS).toInt()
            for (w in 0 until BLOCK_QWORDS) finalBlock[w] = finalBlock[w] xor mem[off + w]
        }
        // 8) الوسم = H'^T(C)
        val finalBytes = ByteArray(1024)
        for (w in 0 until BLOCK_QWORDS) writeLe64(finalBytes, w * 8, finalBlock[w])
        return hPrime(finalBytes, tagLen)
    }

    /** مولّد العناوين لوضع Argon2i/id — الشكل 11: G(ZERO, G(ZERO, Z||LE64(i)||ZERO(968))).
     *  العدّاد i يبدأ من 1 (نص RFC حرفياً)، وعدد الكتل = ceil(segLen/128) ليشمل الذاكرة الصغيرة. */
    private fun generateAddresses(
        input: ByteArray, zero: LongArray, tmp: LongArray, out: LongArray, segLen: Int
    ): LongArray {
        val blocks = (segLen + 127) / 128
        val result = LongArray(segLen)
        for (k in 0 until blocks) {
            writeLe64(input, 48, (k + 1).toLong())
            // G(ZERO, input) ثم G(ZERO, ناتجها) — كل كتلة عناوين تطبيقان
            load(out, input)
            compressG(zero, out, tmp, false, null, -1)
            compressG(zero, tmp, out, false, null, -1)
            val remaining = segLen - k * 128
            val take = if (remaining < 128) remaining else 128
            for (e in 0 until take) result[k * 128 + e] = out[e]
        }
        return result
    }

    /**
     * فهرس الكتلة المرجعية — §3.4.2 حرفياً: l = J2 mod p، ومجموعة W بترتيب الحساب
     * أقدم→أحدث (ثلاث شرائح منتهية + كتل الشريحة الحالية ما عدا السابقة)، ثم
     * zz = |W| - 1 - y حيث y = (|W|·(J1²/2^32))/2^32.
     */
    private fun referenceBlock(
        pass: Int, slice: Int, lane: Int, j: Int,
        j1: Long, j2: Long, p: Int, q: Int, segLen: Int
    ): Long {
        val refLane = if (pass == 0 && slice == 0) lane else (j2 % p.toLong()).toInt()
        // الشرائح المنتهية — أقدم→أحدث
        val finished = ArrayList<Int>(3)  // أرقام شرائح مطلقة داخل هذا المسار المرجعي (بترتيب)
        if (pass > 0) {
            for (k in 3 downTo 1) {
                val s = slice - k
                finished.add((s + SYNC_POINTS) % SYNC_POINTS)
            }
        } else {
            for (s in 0 until slice) finished.add(s)
        }
        var wSize = finished.size * segLen
        if (refLane == lane && j >= 1) wSize += j - 1
        if (j == 0 && wSize > 0) wSize -= 1
        val x = (j1 * j1) ushr 32
        val y = ((wSize.toLong() * x) shr 32).toInt()
        var zz = wSize - 1 - y
        if (zz < 0) zz = 0
        return if (zz < finished.size * segLen) {
            val fs = finished[zz / segLen]
            refLane.toLong() * q + fs * segLen + (zz % segLen)
        } else {
            lane.toLong() * q + slice * segLen + (zz - finished.size * segLen)
        }
    }

    /**
     * دالة الضغط G — §3.5: R = X⊕Y؛ P صفّياً (16 كلمة متتالية ×8) ثم عمودياً
     * (الكلمات 2c,2c+1,2c+16,2c+17,...)؛ الناتج Z⊕R (مع XOR على المحتوى القديم عند [withXor]).
     */
    private fun compressG(x: LongArray, y: LongArray, out: LongArray, withXor: Boolean, mem: LongArray?, outMemOff: Int) {
        val r = LongArray(BLOCK_QWORDS)
        for (w in 0 until BLOCK_QWORDS) r[w] = x[w] xor y[w]
        // صفوف: 8 جولات على 16 كلمة متتالية
        for (row in 0 until 8) {
            blamkaRound(r, row * 16)
        }
        // أعمدة: تجميع كلمات كل عمود (2c,2c+1,2c+16,2c+17,...) في مواضع متتالية
        val col = LongArray(BLOCK_QWORDS)
        for (c in 0 until 8) {
            for (i in 0 until 8) {
                col[16 * c + 2 * i] = r[2 * c + 16 * i]
                col[16 * c + 2 * i + 1] = r[2 * c + 16 * i + 1]
            }
        }
        for (row in 0 until 8) {
            blamkaRound(col, row * 16)
        }
        // إعادة توزيع الأعمدة إلى التخطيط المسطح ثم Rالأصلية⊕Z
        val z = LongArray(BLOCK_QWORDS)
        for (c in 0 until 8) {
            for (i in 0 until 8) {
                z[2 * c + 16 * i] = col[16 * c + 2 * i]
                z[2 * c + 16 * i + 1] = col[16 * c + 2 * i + 1]
            }
        }
        for (w in 0 until BLOCK_QWORDS) {
            var v = x[w] xor y[w] xor z[w]   // R الأصلية (قبل الجولات) ⊕ Z
            if (withXor) {
                if (mem != null) v = v xor mem[outMemOff + w] else v = v xor out[w]
            }
            out[w] = v
        }
        if (mem != null && outMemOff >= 0) {
            for (w in 0 until BLOCK_QWORDS) mem[outMemOff + w] = out[w]
        }
    }

    /** جولة fBlaMka — الشكلان 18/19 (Blake2b round بضرب 32×32). */
    private fun blamkaRound(v: LongArray, off: Int) {
        val b = off
        gb(v, b + 0, b + 4, b + 8, b + 12)
        gb(v, b + 1, b + 5, b + 9, b + 13)
        gb(v, b + 2, b + 6, b + 10, b + 14)
        gb(v, b + 3, b + 7, b + 11, b + 15)
        gb(v, b + 0, b + 5, b + 10, b + 15)
        gb(v, b + 1, b + 6, b + 11, b + 12)
        gb(v, b + 2, b + 7, b + 8, b + 13)
        gb(v, b + 3, b + 4, b + 9, b + 14)
    }

    private fun gb(v: LongArray, a: Int, bb: Int, c: Int, d: Int) {
        var va = v[a]; var vb = v[bb]; var vc = v[c]; var vd = v[d]
        va = va + vb + (((va and 0xFFFFFFFFL) * (vb and 0xFFFFFFFFL)) shl 1)
        vd = rotr64(vd xor va, 32)
        vc = vc + vd + (((vc and 0xFFFFFFFFL) * (vd and 0xFFFFFFFFL)) shl 1)
        vb = rotr64(vb xor vc, 24)
        va = va + vb + (((va and 0xFFFFFFFFL) * (vb and 0xFFFFFFFFL)) shl 1)
        vd = rotr64(vd xor va, 16)
        vc = vc + vd + (((vc and 0xFFFFFFFFL) * (vd and 0xFFFFFFFFL)) shl 1)
        vb = rotr64(vb xor vc, 63)
        v[a] = va; v[bb] = vb; v[c] = vc; v[d] = vd
    }

    private fun rotr64(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))

    /** H' — §3.3: T<=64 ملخص مباشر؛ وإلا سلسلة V مع اقتطاع 32 بايت. */
    private fun hPrime(a: ByteArray, t: Int): ByteArray {
        return if (t <= 64) {
            Blake2b.digest(le32Bytes(t) + a, t)
        } else {
            val r = (t + 31) / 32 - 2
            val out = ByteArray(t)
            var v = Blake2b.digest(le32Bytes(t) + a, 64)
            var written = 0
            for (k in 1..r) {
                System.arraycopy(v, 0, out, written, 32)
                written += 32
                if (k < r) v = Blake2b.digest(v, 64)
            }
            // V_{r+1} = H^(T-32r)(V_r)
            val lastLen = t - 32 * r
            v = Blake2b.digest(v, lastLen)
            System.arraycopy(v, 0, out, written, lastLen)
            out
        }
    }

    private fun load(dst: LongArray, src: ByteArray) {
        for (w in 0 until BLOCK_QWORDS) dst[w] = readLe64(src, w * 8)
    }

    private fun le32Bytes(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte()
    )

    private fun writeLe32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    private fun writeLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
    }

    private fun readLe64(b: ByteArray, off: Int): Long {
        var w = 0L
        for (i in 7 downTo 0) w = (w shl 8) or (b[off + i].toLong() and 0xFF)
        return w
    }

    private fun java.io.ByteArrayOutputStream.le32(v: Int) {
        write(v); write(v ushr 8); write(v ushr 16); write(v ushr 24)
    }
}
