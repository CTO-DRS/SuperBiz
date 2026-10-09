package com.superbiz.app.domain.sync

/**
 * [H4-3][ADR-002 D3/D4] — عقود الدمج الحتمي والمظروف، بلا أي تبعية أندرويد.
 *
 * D3: لكل كيان هوية أصلية (tableName, originDeviceId, originId) — الفائز آخر كاتب
 * (LWW) بالساعة، وكسر التعادل معجمياً على deviceId — النتيجة نفسها على الجهازين
 * بأي ترتيب وصول. الحذف شاهد لا غياب صامت.
 *
 * D4: الحمولة غلاف بروح BackupKit (format/version) مضغوطاً ومشفَّراً في الطبقة
 * العليا — هنا العقد النقي فقط: الصفوف، التجزئة إلى كتل ≤ الحجم، والبصمة.
 *
 * ملاحظة هوية: لا يُدمج قط ما يلي (موقوف تصميمياً — D3): journal/journal_lines/
 * payments/invoices/invoice_items/zatca_docs/audit_log/users/user_secrets —
 * الجداول القابلة للمزامنة التسعة كلها كتالوجات وCRM بلا قيود مالية.
 */
object SyncMerge {

    const val SYNC_FORMAT = "superbiz-sync"
    const val SYNC_VERSION = 1

    /** الجداول القابلة للمزامنة — كتالوجات وCRM حصراً (D3). */
    val SYNCABLE_TABLES: List<String> = listOf(
        "parties", "products", "currencies", "visits", "coupons",
        "statement_templates", "signatures", "stamps", "note_templates"
    )

    /**
     * قرار LWW الحتمي — هل يفوز الوارد على الحالة المحلية؟
     * فوز صارم بالساعة؛ التعادل (نفس المللي ثانية على جهازين) يُكسر معجمياً
     * بمعرّف الجهاز — الجهازان يحسمان النتيجة نفسها بأي ترتيب.
     * [remoteDevice] معرّف جهاز أصل التغيير الوارد، [localDevice] معرّف جهازي.
     */
    fun remoteWins(remoteAt: Long, localAt: Long, remoteDevice: String, localDevice: String): Boolean {
        if (remoteAt > localAt) return true
        if (remoteAt < localAt) return false
        return remoteDevice > localDevice
    }

    /**
     * تجزئة صفوف JSON إلى كتل لا يتجاوز وزن كل واحدة [maxBytes] تقريباً
     * (ADR D4: سقف 8MB للدفعة — التجاوز يُجزَّأ لا يُخمَّن). الصف الواحد
     * الأضخم من السقف يبقى كتلةً وحيدة (لا يُقص محتوى قط).
     */
    fun chunkWeights(weights: List<Int>, maxBytes: Int): List<List<Int>> {
        require(maxBytes > 0) { "maxBytes" }
        val batches = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var acc = 0
        for (i in weights.indices) {
            val w = weights[i]
            if (current.isNotEmpty() && acc + w > maxBytes) {
                batches.add(current); current = ArrayList<Int>(); acc = 0
            }
            current.add(i); acc += w
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /**
     * بصمة الإقران (D5) — أول 8 بايتات من SHA256(KEK) بست عشريات كبيرة
     * في أربع مجموعات "XXXX-XXXX-XXXX-XXXX" تُقارن وجهاً لوجه بين الجهازين.
     */
    fun fingerprint(kek: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest(kek)
        val sb = StringBuilder()
        for (i in 0 until 8) {
            if (i == 2 || i == 4 || i == 6) sb.append('-')
            sb.append(String.format("%02X", d[i]))
        }
        return sb.toString()
    }

    /** ختم زمني موحّد للمظروف (حقل واحد فقط يُقرأ في كل الملف). */
    fun envelopeJson(deviceId: String, seq: Long, sentAt: Long, rowsJson: List<String>): String {
        val sb = StringBuilder()
        sb.append("{\"format\":\"").append(SYNC_FORMAT).append('"')
        sb.append(",\"version\":").append(SYNC_VERSION)
        sb.append(",\"deviceId\":\"").append(escape(deviceId)).append('"')
        sb.append(",\"seq\":").append(seq)
        sb.append(",\"sentAt\":").append(sentAt)
        sb.append(",\"rows\":[")
        rowsJson.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append(r)
        }
        sb.append("]}")
        return sb.toString()
    }

    /** صف مظروف: {"t":جدول,"k":هوية,"at":ساعة,"d":شاهد?,"r":{...}} — الشاهد بلا جسد. */
    fun rowJson(table: String, key: String, at: Long, deleted: Boolean, bodyJson: String?): String {
        val sb = StringBuilder()
        sb.append("{\"t\":\"").append(escape(table)).append('"')
        sb.append(",\"k\":\"").append(escape(key)).append('"')
        sb.append(",\"at\":").append(at)
        sb.append(",\"d\":").append(if (deleted) "1" else "0")
        if (bodyJson != null) sb.append(",\"r\":").append(bodyJson)
        sb.append('}')
        return sb.toString()
    }

    private fun escape(s: String): String = com.superbiz.app.domain.backup.escapeJson(s)
}
