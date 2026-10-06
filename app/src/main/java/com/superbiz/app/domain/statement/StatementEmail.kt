package com.superbiz.app.domain.statement

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * [P18-a] عميل بريد SMTP نقي 100% (JVM بلا أي اعتماد Android) + باني MIME + نموذج إعداد —
 * القناة الحقيقية لإرسال «كشف الحساب PDF» بالبريد مباشرة من الجهاز، يستهلكها تمويج 18-b/18-c
 * عبر واجهة [EmailSender] فقط، ويُختبر بالكامل بذاكرة جامدة (لا شبكة) انظر StatementEmailTest.
 *
 * قرارات التصميم المُثبَّتة (عقد لا يُخترق بصمت):
 *
 * 1) حظر السطر الواحد (one-liner) للإرسال: [SmtpSession] هي آلة الحالات الكاملة
 *    (greeting → EHLO → [STARTTLS] → AUTH LOGIN → MAIL → RCPT×n → DATA → QUIT) وتُحقن
 *    فيها أي Reader/Writer — الاختبارات تشغّلها على أنابيب نصية بلا socket، بينما
 *    [SmtpClient] غلاف رفيع وحيد يفتح Socket/SSLSocket الحقيقي ويغلفه.
 *
 * 2) STARTTLS: نصل نصاً مكشوفاً ← EHLO ← إن أعلن الخادم STARTTLS أرسلناها ثم استدعينا
 *    خطّاف [SmtpSession.sslUpgrade] (العميل الحقيقي يرفع اليد عبر SSLSocketFactory +
 *    startHandshake ثم rewrap للقنوات) ← EHLO ثانية على القناة المعمّاة. خادم لا يعلنها
 *    والإعداد يفرضها ⇒ استثناء صريح وليس تراجعاً صامتاً إلى النص المكشوف (السلامة أولاً:
 *    كشوف الحساب بيانات مالية). SSL_TLS يفتح TLS من أول بايت. NONE نص مكشوف للاختبار
 *    والشبكات الداخلية فقط — كلمات السر تُرسل base64 لكنها قابلة للفك، والمرفق المالي
 *    مكشوف؛ التوثيق الصادق: لا تستخدمها في الإنتاج.
 *
 * 3) Base64 محلي [Base64Codec] لا java.util.Base64: الأخير غير متاح قبل API 26 بينما
 *    minSdk 24 بلا coreLibraryDesugaring (نفس مبرر رأس ZatcaPem/ZatcaQr). تنفيذ
 *    RFC 4648 قياسي مستقل — الاختبارات تستخدم java.util.Base64 كسلّطة خارجية مستقلة.
 *
 * 4) الترميز: الموضوع والأسماء العربية عبر encoded-word بنمط RFC 2047 (=?UTF-8?B?..?=)
 *    مقسّمة كلمات ≤75 محرفاً منطق RFC بطيّ «\r\n »، والجسم UTF-8 خام مع تصريح
 *    Content-Transfer-Encoding: 8bit (خوادم اليوم تقبل 8BITMIME عملياً). المرفق
 *    application/pdf بترميز base64 ملفوف 76 محرفاً (RFC 2045).
 *
 * 5) بلا java.time (minSdk 24 بلا desugaring — نفس مبرر StatementModels): ترويسة Date
 *    بصيغة RFC 5322 عبر Calendar + SimpleDateFormat بالمنطقة الافتراضية وLocale.US.
 *
 * 6) maxAttempts حقل إعداد لطبقة الجدولة/إعادة المحاولة (18-c) — هذه القناة محاولة واحدة
 *    حاجبة لكل نداء؛ المتصل يلفّها بـDispatchers.IO ويدير الحالات PENDING→PROCESSING→SENT|FAILED.
 *
 * كل شيء مغطى في StatementEmailTest (JVM نقي، ≥22 اختباراً، بلا mockito).
 */

/** أنماط أمان قناة SMTP — لكل نمط منفذ افتراضي عبر [SmtpConfig.defaultPort] */
enum class SmtpSecurity { SSL_TLS, STARTTLS, NONE }

/**
 * إعداد إرسال البريد — يُبنى من مفاتيح DataStore في تمويج 18-b، والتحقق هنا في init
 * يرمي IllegalArgumentException عند أي قيمة غير صالحة (فشل مبكر لا صمت).
 * fromAddress/fromName تظهران في ترويسة From؛ username/password يستخدمان فقط
 * حين authEnabled=true؛ maxAttempts للجدولة 18-c؛ timeoutMs يُطبق soTimeout
 * على القناة ومهلة الاتصال معاً.
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val security: SmtpSecurity = SmtpSecurity.SSL_TLS,
    val username: String = "",
    val password: String = "",
    val fromAddress: String,
    val fromName: String = "",
    val authEnabled: Boolean = true,
    val maxAttempts: Int = 3,
    val timeoutMs: Int = 15_000
) {
    init {
        require(host.isNotBlank()) { "[P18-a] مضيف SMTP فارغ" }
        require(port in 1..65535) { "[P18-a] منفذ SMTP غير صالح: $port" }
        require(fromAddress.contains('@') && fromAddress.none { it.isWhitespace() }) {
            "[P18-a] عنوان المرسل غير صالح: \"$fromAddress\""
        }
        require(maxAttempts >= 1) { "[P18-a] maxAttempts يجب أن يكون 1 على الأقل: $maxAttempts" }
        require(timeoutMs > 0) { "[P18-a] timeoutMs يجب أن يكون موجباً: $timeoutMs" }
        if (authEnabled) {
            require(username.isNotBlank()) { "[P18-a] authEnabled مفعّل واسم المستخدم فارغ" }
            require(password.isNotEmpty()) { "[P18-a] authEnabled مفعّل وكلمة السر فارغة" }
        }
    }

    companion object {
        /** المنفذ الافتراضي المعتاد لكل نمط أمان: 465 / 587 / 25 */
        fun defaultPort(security: SmtpSecurity): Int = when (security) {
            SmtpSecurity.SSL_TLS -> 465
            SmtpSecurity.STARTTLS -> 587
            SmtpSecurity.NONE -> 25
        }
    }
}

/**
 * Base64 محلي (RFC 4648 بحشو «=») — لا يعتمد java.util.Base64 (API 26+ بينما minSdk 24،
 * مرجع الرأس ③). مرئي داخلية للاختبارات ولمستهلكات الحزمة (توقيع AUTH LOGIN وMIME).
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** ترميز سطر واحد — الحلقة الثلاثية القياسية + بقايا 1/2 بايت بحشو «=» */
    fun encode(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF) shl 16 or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 63])
                .append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63])
                .append(ALPHABET[n and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append("==")
        } else if (rem == 2) {
            val n = (data[i].toInt() and 0xFF) shl 16 or ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(n shr 18) and 63])
                .append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63])
                .append('=')
        }
        return out.toString()
    }
}

/**
 * باني رسالة MIME نقي (دوال غير حالية) — ينتج رسالة CRLF صحيحة البنية:
 * - بلا مرفق: رسالة text/plain; charset="UTF-8" أحادية (قرار مُثبَّت باختبار:
 *   «التدهور» إلى جزء واحد أنظف وأتوافق من multipart شبه فارغ).
 * - مع مرفق: multipart/mixed بجزأين — نص ثم application/pdf بترميز base64 ملفوف
 *   76 محرفاً. المرفق الفارغ (null أو مصفوفة 0) يُعامَل كلا مرفق.
 * - الموضوع العربي encoded-word قابل للفكّ عبر [encodeSubjectRfc2047]، والمرفق
 *   قابل للاستعادة بايتاً بايت عبر فك base64 — الاثنان مقفولان باختبارات.
 * - حقن boundary/messageId/dateMs اختياري للاختبارات الحتمية، والإعدادات الافتراضية
 *   عشوائية آمنة (SecureRandom) والمعرف مستمد من نطاق عنوان المرسل.
 */
object MimeBuilder {

    /** حدّ حِمل الـbase64 داخل encoded-word الواحد: 45 بايتاً ⇒ 60 محرف base64 ⇒ كلمة ≤75 محرفاً (RFC 2047) */
    private const val MAX_ENCODED_PAYLOAD_BYTES = 45

    /** لفّ أسطر base64 للمرفق — RFC 2045 */
    private const val B64_LINE = 76

    /** فاصل طيّ الترويسات بين encoded-words متجاورة — يُكتفى به عند فكّ الرسالة */
    private const val FOLD = "\r\n "

    private const val HEX = "0123456789abcdef"

    private val RANDOM = SecureRandom()

    /**
     * بناء الرسالة كاملة كبايتات UTF-8 بنهايات أسطر CRLF حصراً.
     * يرمي IllegalArgumentException على مستلمين/مرسل/حدود/معرف غير صالحين — فشل مبكر.
     */
    fun build(
        fromName: String,
        fromAddress: String,
        to: List<String>,
        subject: String,
        bodyText: String,
        attachmentName: String?,
        attachmentBytes: ByteArray?,
        boundary: String = randomBoundary(),
        messageId: String = randomMessageId(fromAddress),
        dateMs: Long = System.currentTimeMillis()
    ): ByteArray {
        val recipients = normalizeEmailAddresses(to)
        val addr = fromAddress.trim()
        require(addr.contains('@') && addr.none { it.isWhitespace() }) {
            "[P18-a] عنوان المرسل في MIME غير صالح: \"$fromAddress\""
        }
        require(boundary.isNotBlank() && boundary.none { it == '"' || it == '\r' || it == '\n' }) {
            "[P18-a] حدّ MIME غير صالح: \"$boundary\""
        }
        require(messageId.isNotBlank() && messageId.none { it.isWhitespace() || it == '<' || it == '>' }) {
            "[P18-a] معرف الرسالة غير صالح: \"$messageId\""
        }
        val hasAttachment = attachmentBytes != null && attachmentBytes.isNotEmpty()
        val name = oneLine(fromName)
        val sb = StringBuilder(1024)

        // الترويسات — الترتيب مقفول باختبار: From/To/Subject/Date/MIME-Version/Message-ID/Content-Type
        sb.append("From: ").append(formatFrom(name, addr)).append("\r\n")
        sb.append("To: ").append(recipients.joinToString(", ")).append("\r\n")
        sb.append("Subject: ").append(encodeSubjectRfc2047(oneLine(subject))).append("\r\n")
        sb.append("Date: ").append(formatRfc5322Date(dateMs)).append("\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Message-ID: <").append(messageId).append(">\r\n")

        if (hasAttachment) {
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n")
            sb.append("\r\n")
            // الجزء الأول: النص
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            sb.append(bodyText).append("\r\n")
            // الجزء الثاني: مرفق PDF — base64 ملفوف 76 محرفاً
            val fileName = oneLine(attachmentName ?: "").ifBlank { "attachment" }
            val safeName = if (isAsciiPrintable(fileName)) fileName else encodedWord(fileName)
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Type: application/pdf; name=\"").append(safeName).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n")
            sb.append("\r\n")
            sb.append(base64Wrap(attachmentBytes)).append("\r\n")
            sb.append("--").append(boundary).append("--\r\n")
        } else {
            // قرار «التدهور» الموثق: بلا مرفق ⇒ رسالة نصية أحادية بلا multipart إطلاقاً
            sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            sb.append(bodyText).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * ترميز الموضوع encoded-word بنمط RFC 2047: موضوع ASCII طباعي يمرّ كما هو،
     * وغيره يُقسَّم على حدود المحارف (لا يُشطر محرف UTF-8 ولا زوجاً بديلاً) إلى حِمل
     * ≤45 بايتاً فتصدر كلمات ≤75 محرفاً تُضم بطيّ «\r\n » — الفكّ بالتسلسل يعيد النص الأصلي.
     */
    fun encodeSubjectRfc2047(subject: String): String {
        val s = oneLine(subject)
        if (s.isEmpty() || isAsciiPrintable(s)) return s
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        var curBytes = 0
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val unitBytes: Int
            val step: Int
            if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                unitBytes = 4
                step = 2
            } else {
                unitBytes = utf8Length(ch)
                step = 1
            }
            if (curBytes + unitBytes > MAX_ENCODED_PAYLOAD_BYTES && cur.isNotEmpty()) {
                chunks += cur.toString()
                cur.setLength(0)
                curBytes = 0
            }
            cur.append(s, i, i + step)
            curBytes += unitBytes
            i += step
        }
        if (cur.isNotEmpty()) chunks += cur.toString()
        return chunks.joinToString(FOLD) { chunk ->
            "=?UTF-8?B?" + Base64Codec.encode(chunk.toByteArray(Charsets.UTF_8)) + "?="
        }
    }

    /** base64 المرفق ملفوفاً أسطر 76 محرفاً بفواصل CRLF (آخر سطر بأي طول، بلا سطر خالٍ زائد) */
    fun base64Wrap(bytes: ByteArray): String =
        Base64Codec.encode(bytes).chunked(B64_LINE).joinToString("\r\n")

    // ───────── أدوات داخلية ─────────

    /** كلمة واحدة encoded-word للأسماء القصيرة (اسم المرسل/اسم الملف) */
    private fun encodedWord(text: String): String =
        "=?UTF-8?B?" + Base64Codec.encode(oneLine(text).toByteArray(Charsets.UTF_8)) + "?="

    /** ترويسة From: بلا اسم ⇒ العنوان وحده؛ ASCII ⇒ اسم مقتبس؛ غير ASCII ⇒ encoded-word */
    private fun formatFrom(name: String, address: String): String {
        if (name.isEmpty()) return address
        return if (isAsciiPrintable(name)) {
            "\"" + name.replace("\"", "'") + "\" <" + address + ">"
        } else {
            encodedWord(name) + " <" + address + ">"
        }
    }

    /** RFC 5322 "EEE, dd MMM yyyy HH:mm:ss Z" بالمنطقة الافتراضية وLocale.US (مرجع الرأس ⑤) */
    private fun formatRfc5322Date(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(c.time)
    }

    /** خط دفاع وحيد ضد حقن الترويسات: CR/LF تُستبدل بمسافة في كل قيمة ترويسة */
    private fun oneLine(s: String): String = s.replace("\r", " ").replace("\n", " ").trim()

    private fun isAsciiPrintable(s: String): Boolean = s.all { it.code in 32..126 }

    /** طول ترميز UTF-8 لمحرف BMP وحيد (الأزواج البديلة تُعالج كوحدة 4 بايتات في الأعلى) */
    private fun utf8Length(ch: Char): Int = when {
        ch.code < 0x80 -> 1
        ch.code < 0x800 -> 2
        else -> 3
    }

    private fun hexToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        RANDOM.nextBytes(bytes)
        val sb = StringBuilder(byteCount * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** حدّ multipart عشوائي آمن — يبدأ بـ«=_» كي لا يتصادم مع نص عملياً */
    private fun randomBoundary(): String = "=_sb_" + hexToken(12)

    /** معرف رسالة افتراضي من طابع زمني + عشوائية + نطاق عنوان المرسل */
    private fun randomMessageId(fromAddress: String): String {
        val domain = fromAddress.substringAfterLast('@', "").trim()
        val host = if (domain.isBlank()) "superbiz.local" else domain
        return System.currentTimeMillis().toString() + "." + hexToken(6) + "@" + host
    }
}

/** فشل قناة SMTP — smtpCode يحمل رمز الخادم 3-أرقام حين ردّ الخادم، وnull لأخطاء الشبكة/البروتوكول */
class SmtpException(val smtpCode: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * قناة إرسال البريد — حاجبة متعمدة؛ المتصل (18-c) يلفّها بـDispatchers.IO ويدير
 * حالة التسليم PENDING→PROCESSING→SENT|FAILED. أي فشل يظهر كـ[SmtpException].
 */
interface EmailSender {
    fun send(
        config: SmtpConfig,
        to: List<String>,
        subject: String,
        body: String,
        attachmentName: String?,
        attachmentBytes: ByteArray?
    )
}

/**
 * آلة حالات SMTP الكاملة — القلب القابل للاختبار: تُحقن فيها أي Reader/Writer فيشغّل
 * الاختبار النص الكامل بذاكرة جامدة، بينما يستعملها [SmtpClient] بقنوات socket حقيقية.
 *
 * تعاقد الأكواد (كل ردّ يُدقّق أول 3 أرقام):
 *   greeting=220 ، EHLO=250 ، STARTTLS=220 ، AUTH=334/334/235 ،
 *   MAIL=250 ، RCPT=250|251 ، DATA=354 ، نهاية البيانات=250 ، QUIT يُقرأ بلا إصرار
 *   (الرسالة مقبولة أصلاً فلا يُرمى خطأ على ردّ وداع غريب).
 * أي ردّ آخر (5xx رفض نهائي أو 4xx مؤقت) ⇒ [SmtpException] حامل الرمز — هذا ما
 * تغذّي به حالة التسليم FAILED في StatementDeliveryEntity.
 *
 * سجّل [log] يستقبل أسطر البروتوكول مع إخفاء بيانات اعتماد AUTH («***») — لا تسريب بالسجلات.
 */
class SmtpSession(
    private var reader: BufferedReader,
    private var writer: PrintWriter,
    private val sslUpgrade: (() -> Unit)?,
    private val config: SmtpConfig,
    private val log: (String) -> Unit = {}
) {

    /**
     * جولة إرسال كاملة على القناة المحقونة — ترمي IllegalArgumentException على
     * مستلمين غير صالحين (قبل أي كتابة على القناة)، و[SmtpException] على أي رفض بروتوكولي.
     */
    fun send(to: List<String>, subject: String, body: String, attachmentName: String?, attachmentBytes: ByteArray?) {
        val recipients = normalizeEmailAddresses(to)
        log("SMTP: بدء الجلسة (security=${config.security.name} auth=${config.authEnabled} host=${config.host}:${config.port})")

        // 1) التحية
        expect(readReply("greeting"), "greeting", 220)

        // 2) EHLO الأولى
        var capabilities = ehlo()

        // 3) STARTTLS إن كان الإعداد يفرضه — رفض صريح لعدم الإعلان، ثم EHLO ثانية على القناة المعمّاة
        if (config.security == SmtpSecurity.STARTTLS) {
            if (capabilities.none { it.equals("STARTTLS", ignoreCase = true) }) {
                throw SmtpException(
                    null,
                    "[P18-a] الخادم لا يعلن دعم STARTTLS بينما الإعداد يفرضه — رفض الإرسال بنص مكشوف"
                )
            }
            command("STARTTLS")
            expect(readReply("STARTTLS"), "STARTTLS", 220)
            val upgrade = sslUpgrade
                ?: throw SmtpException(null, "[P18-a] STARTTLS مطلوب بلا طبقة ترقية محقونة — خطأ تركيب")
            upgrade()
            capabilities = ehlo()
        }

        // 4) AUTH LOGIN (base64، ثلاث خطوات) — فقط حين authEnabled
        if (config.authEnabled) {
            command("AUTH LOGIN")
            expect(readReply("AUTH LOGIN"), "AUTH LOGIN", 334)
            command(Base64Codec.encode(config.username.toByteArray(Charsets.UTF_8)), hide = true)
            expect(readReply("AUTH username"), "AUTH username", 334)
            command(Base64Codec.encode(config.password.toByteArray(Charsets.UTF_8)), hide = true)
            expect(readReply("AUTH password"), "AUTH password", 235)
        }

        // 5) MAIL FROM
        command("MAIL FROM:<" + config.fromAddress.trim() + ">")
        expect(readReply("MAIL FROM"), "MAIL FROM", 250)

        // 6) RCPT TO — مستلم بمستلم؛ 550 إلخ تُرمى حاملة الرمز
        for (rcpt in recipients) {
            command("RCPT TO:<" + rcpt + ">")
            expect(readReply("RCPT TO"), "RCPT TO", 250, 251)
        }

        // 7) DATA — الرسالة MIME كاملة مع حشو النقطة سطراً بسطر ثم النهاية «.»
        command("DATA")
        expect(readReply("DATA"), "DATA", 354)

        val mime = MimeBuilder.build(
            fromName = config.fromName,
            fromAddress = config.fromAddress,
            to = recipients,
            subject = subject,
            bodyText = body,
            attachmentName = attachmentName,
            attachmentBytes = attachmentBytes
        )
        val payload = String(mime, Charsets.UTF_8)
        val stuffed = SmtpSession.dotStuff(payload)
        writeRaw(stuffed)
        if (!stuffed.endsWith("\r\n")) writeRaw("\r\n")
        writeRaw(".\r\n")
        expect(readReply("message (end of DATA)"), "message (end of DATA)", 250)

        // 8) QUIT — بلا إصرار: الرسالة قُبلت أعلاه فلا يفسد ردّ وداع غريب نتيجة الإرسال
        command("QUIT")
        try {
            val bye = readReply("QUIT")
            if (bye.code != 221) log("SMTP: ردّ QUIT ${bye.code} — الرسالة مقبولة أصلاً فلا يُرمى خطأ")
        } catch (_: SmtpException) {
            log("SMTP: تعذّر قراءة ردّ QUIT — الرسالة مقبولة أصلاً فلا يُرمى خطأ")
        }
        log("SMTP: اكتملت الجلسة بنجاح")
    }

    /**
     * تبديل القنوات بعد ترقية TLS — تسميه طبقة الاتصال الحقيقية من داخل خطّاف
     * sslUpgrade بعد startHandshake كي تكمل الجلسة ذاتها فوق القناة المعمّاة.
     * (ملاحظة صادقة: قارئ ما قبل TLS قد يحتفظ ببايتات مقروءة مسبقاً نظرياً؛ خوادم SMTP
     * لا ترسل شيئاً بغير طلب بعد «220 go ahead» فعملياً لا يوجد فوق-قراءة.)
     */
    internal fun rewrap(newReader: BufferedReader, newWriter: PrintWriter) {
        reader = newReader
        writer = newWriter
    }

    // ───────── خطوات البروتوكول ─────────

    /** EHLO وإرجاع قائمة القدرات المعلنة (نص الأسطر بعد الرمز والفاصل) */
    private fun ehlo(): List<String> {
        command("EHLO superbiz")
        val reply = readReply("EHLO")
        expect(reply, "EHLO", 250)
        return reply.lines.mapNotNull { line ->
            if (line.length > 4) line.substring(4).trim().takeIf { it.isNotEmpty() } else null
        }
    }

    /** قراءة ردّ متعدد الأسطر («250-...» يواصل و«250 ...» ختامي) مع تدقيق الرمز */
    private fun readReply(stage: String): SmtpReply {
        val lines = mutableListOf<String>()
        while (true) {
            val line = reader.readLine()
                ?: throw SmtpException(null, "[P18-a] انقطع الاتصال أثناء انتظار ردّ $stage")
            log("S: $line")
            lines += line
            val code = (if (line.length >= 3) line.substring(0, 3) else line).toIntOrNull()
            if (code == null) {
                throw SmtpException(null, "[P18-a] ردّ غير مفهوم من الخادم في مرحلة $stage: \"$line\"")
            }
            if (!(line.length > 3 && line[3] == '-')) return SmtpReply(code, lines)
        }
    }

    private fun expect(reply: SmtpReply, stage: String, vararg accepted: Int) {
        if (reply.code !in accepted) {
            throw SmtpException(
                reply.code,
                "[P18-a] رفض الخادم في مرحلة $stage — الرمز ${reply.code} والردّ: ${reply.lines.lastOrNull() ?: ""}"
            )
        }
    }

    private fun command(cmd: String, hide: Boolean = false) {
        log("C: " + if (hide) "***" else cmd)
        writeRaw(cmd + "\r\n")
    }

    /** PrintWriter يبتلع IOException لذا نفحص checkError بعد كل غسل — لا فشل صامت */
    private fun writeRaw(text: String) {
        writer.print(text)
        writer.flush()
        if (writer.checkError()) {
            throw SmtpException(null, "[P18-a] فشل كتابة البيانات إلى قناة SMTP")
        }
    }

    private data class SmtpReply(val code: Int, val lines: List<String>)

    companion object {

        /**
         * حشو النقطة (RFC 5321 §4.5.2): كل سطر يبدأ بـ«.» يضاف إليه «.» أخرى —
         * يشمل السطر الأول من الحمولة وأسطر الجسم والمرفق. internal للاختبار المباشر،
         * والاختبار الطرف-إلى-طرف يقفلها عبر قناة الذاكرة أيضاً.
         */
        internal fun dotStuff(payload: String): String {
            val sb = StringBuilder(payload.length + 16)
            var lineStart = true
            for (ch in payload) {
                if (lineStart && ch == '.') sb.append('.')
                sb.append(ch)
                lineStart = ch == '\n'
            }
            return sb.toString()
        }
    }
}

/**
 * تنفيذ القناة الحقيقي فوق Socket/SSLSocket — غلاف رفيع فقط: يفتح الاتصال ويسلّم
 * القنوات إلى [SmtpSession] ويحوّل أي فشل شبكة إلى [SmtpException] (smtpCode=null).
 * ملاحظات الاتصال:
 * - SSL_TLS: socket خام بمهلة اتصال ثم SSLSocketFactory + startHandshake — مهلة
 *   الاتصال محترمة بخلاف createSocket(host,port) المباشر الذي يتصل بلا مهلة.
 * - STARTTLS: نص مكشوف أولاً وخطّاف الترقية يرفع القناة ويعيد تغليف قارئ/كاتب
 *   الجلسة ذاتها عبر [SmtpSession.rewrap] فتبقى آلة الحالات واحدة.
 * - NONE: نص مكشوف — للاختبار والشبكات الداخلية فقط (مرجع الرأس ②).
 * - IOException سوكت التحقق المبكر (المستلمون) تبقى IllegalArgumentException كما هي.
 */
object SmtpClient : EmailSender {

    override fun send(
        config: SmtpConfig,
        to: List<String>,
        subject: String,
        body: String,
        attachmentName: String?,
        attachmentBytes: ByteArray?
    ) {
        // التحقق المبكر خارج try كي يبقى خطأ الاستدعاء IllegalArgumentException لا SmtpException
        val recipients = normalizeEmailAddresses(to)

        var socket: Socket? = null
        try {
            val raw = Socket()
            raw.connect(InetSocketAddress(config.host, config.port), config.timeoutMs)
            // [P18-int-fix] صيغة تتابعية صريحة بدل when+apply فوق SocketFactory —
            // cast إلى SSLSocket يزيل غموض الاستنتاج الذي فجّر ترجمة 18-a
            var active: Socket = raw
            if (config.security == SmtpSecurity.SSL_TLS) {
                // [P18-int-fix] getDefault() يعيد SocketFactory بلا overload فوق socket —
                // cast إلى SSLSocketFactory لكشف overload createSocket(Socket,…) الحقيقي
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                // توقيع SSLSocketFactory يعيد Socket ساكنًا — cast للنتيجة كي يتاح startHandshake
                val ssl = factory.createSocket(raw, config.host, config.port, true) as SSLSocket
                ssl.startHandshake()
                active = ssl
            }
            socket = active
            active.soTimeout = config.timeoutMs

            // الخطّاف يرفع القناة ويعيد تغليف الجلسة نفسها — مرجعها يُملأ بعد البناء مباشرة
            var session: SmtpSession? = null
            val upgrade: (() -> Unit)? = if (config.security == SmtpSecurity.STARTTLS) {
                val hook: () -> Unit = {
                    val tlsFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val tls = tlsFactory.createSocket(active, config.host, config.port, true) as SSLSocket
                    tls.soTimeout = config.timeoutMs
                    tls.startHandshake()
                    socket = tls
                    session?.rewrap(
                        BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.UTF_8)),
                        PrintWriter(OutputStreamWriter(tls.getOutputStream(), Charsets.UTF_8), false)
                    )
                }
                hook
            } else {
                null
            }

            val s = SmtpSession(
                reader = BufferedReader(InputStreamReader(active.getInputStream(), Charsets.UTF_8)),
                writer = PrintWriter(OutputStreamWriter(active.getOutputStream(), Charsets.UTF_8), false),
                sslUpgrade = upgrade,
                config = config
            )
            session = s
            s.send(recipients, subject, body, attachmentName, attachmentBytes)
        } catch (e: SmtpException) {
            throw e
        } catch (e: Exception) {
            throw SmtpException(
                null,
                "[P18-a] فشل قناة SMTP نحو ${config.host}:${config.port} — ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
                // الإغلاق الأفضل-سعياً — لا يطمس فشلاً أصلياً
            }
        }
    }
}

/** تطبيع قائمة المستلمين: قصّ + رفض الفراغ/عديم-@/فضاءات/أقواس زاوية + إزالة التكرار بالترتيب */
internal fun normalizeEmailAddresses(to: List<String>): List<String> {
    if (to.isEmpty()) throw IllegalArgumentException("[P18-a] قائمة مستلمي البريد فارغة")
    val out = mutableListOf<String>()
    for (raw in to) {
        val a = raw.trim()
        require(a.contains('@')) { "[P18-a] عنوان بريد غير صالح: \"$raw\"" }
        require(a.none { it.isWhitespace() || it == '<' || it == '>' || it == ',' }) {
            "[P18-a] عنوان بريد يحوي محارف ممنوعة: \"$raw\""
        }
        out += a
    }
    return out.distinct()
}
