package com.superbiz.app.domain.algo

/**
 * [Z2-أ V 1.5.0] مولّد طلب توقيع الشهادة PKCS#10 بصيغة ZATCA — نقي تماماً
 * بلا أي اعتماد Android (نمط ZatcaStamp/ZatcaQr)، وJUnit مجرد يغطيه.
 *
 * ─── عقد CSR حسب مواصفة منصة «فاتورة» ───
 * الموضوع (Subject) بترتيب حتمي ثابت — كل حقله يُخزَّن UTF8String أو
 * PrintableString حسب محتواه (ASCII ⇒ PrintableString — ما يناسب قوالب
 * OpenSSL الرسمية؛ أي محرف خارجها ⇒ UTF8String بلا استثناء):
 *   CN (2.5.4.3)  = الرقم الضريبي للبائع (VAT)
 *   SN (2.5.4.5)  = رمز نوع الفاتورة: «0100000» قياسية / «0200000» مبسطة
 *   OU (2.5.4.11) = اسم الحل («SuperBiz» افتراضياً)
 *   O  (2.5.4.10) = اسم الفرع/المؤسسة
 *   C  (2.5.4.6)  = «SA»
 * الامتدادات (extensionRequest 1.2.840.113549.1.9.14):
 *   keyUsage (2.5.29.15) — حاسم: digitalSignature + nonRepudiation
 *   extKeyUsage (2.5.29.37) — clientAuth (1.3.6.1.5.5.7.3.2)
 *   basicConstraints (2.5.29.19) — SEQUENCE فارغ = CA:FALSE
 * الخوارزمية: EC P-256 (secp256r1) + SHA256withECDSA — المفتاح نفسه الذي
 * يديره ZatcaKeys داخل AndroidKeyStore؛ ما يُوقَّع هنا هو CertificationRequestInfo
 * كامل البايتات (لا يُصنع مفتاح جديد ولا يُصدَّر الخاص).
 *
 * ─── قرارات تنفيذ مُثبَّتة ───
 * - ترميز DER يدوي كامل (TLV بأطوال قصيرة وطويلة — الأسماء العربية قد تتجاوز
 *   127 بايتاً فيترجع طولها طويلاً 0x81/0x82) بلا BouncyCastle: تبعية جديدة
 *   تقيد حجم APK وتبطل مبدأ «التبعيات الصغرى»، والمطلوب هنا مجموعة DER صغيرة
 *   مغلقة معروفة البنية كلياً.
 * - توقيع «SHA256withECDSA» يعيد DER للقيمة (r,s) في كل مزودي المنصة (SunEC
 *   على JVM، وAndroidKeyStore على الجهاز) — يُغلف BIT STRING كما هو.
 * - SPKI يُبنى من نقطة SEC1 الـ65 بايتة (نفس بنية ZatcaKeys.publicKeySpki
 *   وZatcaStamp.p256PublicKeyFromPoint: AlgId { ecPublicKey, prime256v1 } +
 *   BIT STRING) — مصدر واحد للحقيقة هو بنية SPKI هذه في الملفات الثلاثة،
 *   والمطابقة بينها محكومة باختبارات مستقلة لكل ملف.
 * - أزمنة/متغيرات عشوائية: لا شيء في البنية إطلاقاً — المدخلات نفسها ⇒ بايتات
 *   CSR نفسها ما عدا التوقيع (غير حتمي بطبيعته ECDSA) — والاختبار يثبت
 *   تطابق CertificationRequestInfo بايتاً ببايت بين نداءين متتاليين.
 */
object ZatcaCsr {

    /** مدخلات الطلب — كل الحقول مهربة ضمنياً عبر ترميز DER لا عبر تهريب نصي */
    data class CsrInput(
        val vatNumber: String,       // CN — الرقم الضريبي
        val invoiceType: String,     // SN — «0100000» قياسية / «0200000» مبسطة
        val solutionName: String,    // OU — اسم الحل
        val branchName: String,      // O — اسم الفرع/المؤسسة
        val country: String = "SA",  // C — رمز الدولة
    )

    // ───────── نقاط الدخول ─────────

    /**
     * بناء الطلب الكامل وإعادة DER (ثنائي) — المدخل:
     * @param publicKeyPoint نقطة SEC1 غير مضغوطة 65 بايت (0x04||X||Y) كما تعيدها
     *        ZatcaKeys.publicKeyPoint()
     * @param signer مغلِّف التوقيع بنفس عقد ZatcaStamp.Signer — يوقّع
     *        CertificationRequestInfo (SHA256withECDSA على بايتاته نفسها)
     * @throws IllegalArgumentException إذا كانت النقطة غير 65 بايت أو بلا بادئة 0x04
     */
    fun buildDer(input: CsrInput, publicKeyPoint: ByteArray, signer: ZatcaStamp.Signer): ByteArray {
        require(publicKeyPoint.size == 65 && publicKeyPoint[0] == 0x04.toByte()) {
            "ZATCA CSR expects a 65-byte uncompressed SEC1 point"
        }
        val cri = certificationRequestInfo(input, publicKeyPoint)
        val signature = signer.sign(cri)
        val sigAlgId = derTlv(
            0x30,
            derOid(OID_ECDSA_SHA256) + derTlv(0x05, ByteArray(0)) // NULL
        )
        return derTlv(0x30, cri + sigAlgId + derTlv(0x03, ByteArray(1) + signature))
        // BIT STRING: بايت «unused bits» = 0 قبل محتوى التوقيع
    }

    /** DER ثم تغليف PEM بتسمية «CERTIFICATE REQUEST» — الشكل الذي ترفعه منصة فاتورة */
    fun buildPem(input: CsrInput, publicKeyPoint: ByteArray, signer: ZatcaStamp.Signer): String =
        ZatcaPem.toPem(buildDer(input, publicKeyPoint, signer), "CERTIFICATE REQUEST")

    /**
     * CertificationRequestInfo ::= SEQUENCE { version, subject, subjectPKInfo, attributes[0] }
     * مكشوف للاختبارات (التحقق الحرفي من الحتمية ومطابقة الموضوع) — ليس للخارج.
     */
    internal fun certificationRequestInfo(input: CsrInput, point: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
        val subject = derTlv(0x30, subjectRdns(input))
        val spki = spkiFromPoint(point)
        val attributes = derTlv(0xA0, extensionRequestAttribute()) // [0] IMPLICIT SET OF
        return derTlv(0x30, version + subject + spki + attributes)
    }

    // ───────── الموضوع: RDNs ─────────

    /** SEQUENCE OF RDN — الترتيب الحتمي: CN, SN, OU, O, C (موثق في رأس الملف) */
    private fun subjectRdns(input: CsrInput): ByteArray {
        val cn = derOid(OID_CN)
        val sn = derOid(OID_SERIALNUMBER)
        val ou = derOid(OID_OU)
        val o = derOid(OID_O)
        val c = derOid(OID_C)
        return rdnsOf(
            cn to input.vatNumber,
            sn to input.invoiceType,
            ou to input.solutionName,
            o to input.branchName,
            c to input.country,
        )
    }

    private fun rdnsOf(vararg pairs: Pair<ByteArray, String>): ByteArray =
        pairs.fold(ByteArray(0)) { acc, (oid, value) ->
            // RDN = SET { ATV = SEQUENCE { OID, value } }
            acc + derTlv(0x31, derTlv(0x30, oid + nameValue(value)))
        }

    /** PrintableString (0x13) للـASCII المطبوع، وإلا UTF8String (0x0C) — قرار الرأس */
    private fun nameValue(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val printable = value.isNotEmpty() && value.all { it.code in 0x20..0x7E }
        val tag = if (printable) 0x13 else 0x0C
        return derTlv(tag, bytes)
    }

    // ───────── الامتدادات ─────────

    /**
     * Attribute extensionRequest: SEQUENCE { OID 1.2.840.113549.1.9.14,
     * SET { Extensions = SEQUENCE OF Extension } }
     */
    private fun extensionRequestAttribute(): ByteArray {
        val keyUsageValue = byteArrayOf(0x03, 0x02, 0x06, 0xC0.toByte()) // BIT STRING: bits 0,1
        val keyUsage = derTlv(0x30, derOid(OID_KEY_USAGE) +
            byteArrayOf(0x01, 0x01, 0x01) +                   // BOOLEAN TRUE (critical)
            derTlv(0x04, keyUsageValue))                      // OCTET STRING
        val extKeyUsage = derTlv(0x30, derOid(OID_EXT_KEY_USAGE) +
            derTlv(0x04, derTlv(0x30, derOid(OID_CLIENT_AUTH))))
        val basicConstraints = derTlv(0x30, derOid(OID_BASIC_CONSTRAINTS) +
            derTlv(0x04, derTlv(0x30, ByteArray(0))))         // SEQUENCE {} = CA:FALSE
        val extensions = derTlv(0x30, keyUsage + extKeyUsage + basicConstraints)
        return derTlv(0x30, derOid(OID_EXTENSION_REQUEST) + derTlv(0x31, extensions))
    }

    // ───────── SPKI ─────────

    /** SubjectPublicKeyInfo من نقطة 65 بايت — البنية نفسها في ZatcaKeys/ZatcaStamp */
    private fun spkiFromPoint(point: ByteArray): ByteArray {
        val bitString = ByteArray(1 + point.size)
        bitString[0] = 0x00 // unused bits
        point.copyInto(bitString, 1)
        val algId = byteArrayOf(
            0x30, 0x13,
            0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01, // ecPublicKey
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07 // prime256v1
        )
        return derTlv(0x30, algId + derTlv(0x03, bitString))
    }

    // ───────── ترميز DER العام ─────────

    /** TLV بأي طول (قصير <128 أو طويل 0x81/0x82 — الأسماء العربية طويلة حتماً) */
    internal fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val head = when {
            content.size < 128 -> byteArrayOf(tag.toByte(), content.size.toByte())
            content.size <= 0xFF -> byteArrayOf(tag.toByte(), 0x81.toByte(), content.size.toByte())
            content.size <= 0xFFFF -> byteArrayOf(
                tag.toByte(), 0x82.toByte(),
                (content.size shr 8).toByte(), (content.size and 0xFF).toByte()
            )
            else -> throw IllegalArgumentException("DER content too large: ${content.size}")
        }
        return head + content
    }

    /** OID بصيغة base-128 القياسية — المدخلات هنا ثوابت الملف فقط */
    private fun derOid(oid: String): ByteArray {
        val parts = oid.split('.').map { it.toLong() }
        require(parts.size >= 2 && parts[0] in 0..2 && parts[1] < 40) { "bad OID: $oid" }
        val body = ArrayList<Byte>()
        fun write7(v: Long) {
            if (v < 0x80) {
                body.add(v.toByte())
            } else {
                val stack = ArrayList<Byte>()
                var x = v
                stack.add((x and 0x7F).toByte())
                x = x ushr 7
                while (x > 0) {
                    stack.add(((x and 0x7F) or 0x80L).toByte())
                    x = x ushr 7
                }
                for (i in stack.indices.reversed()) body.add(stack[i])
            }
        }
        write7(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) write7(parts[i])
        return derTlv(0x06, body.toByteArray())
    }

    // ───────── ثوابت OIDs ─────────

    private const val OID_CN = "2.5.4.3"
    private const val OID_SERIALNUMBER = "2.5.4.5"
    private const val OID_OU = "2.5.4.11"
    private const val OID_O = "2.5.4.10"
    private const val OID_C = "2.5.4.6"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
    private const val OID_EXTENSION_REQUEST = "1.2.840.113549.1.9.14"
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
}
