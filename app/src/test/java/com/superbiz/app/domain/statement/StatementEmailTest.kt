package com.superbiz.app.domain.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64

/**
 * [P18-tests] اختبارات قناة البريد الحقيقية — MIME + آلة حالات SMTP فوق قنوات
 * ذاكرة (StringReader/StringWriter) بلا socket ولا شبكة. السكربتات تحاكي ردود
 * الخادم بالترتيب الحرفي الذي تستهلكه [SmtpSession.readReply].
 */
class StatementEmailTest {

    private fun config(
        security: SmtpSecurity = SmtpSecurity.SSL_TLS,
        auth: Boolean = true,
        user: String = "u@x.com",
        pass: String = "secret",
        from: String = "me@x.com"
    ) = SmtpConfig(
        host = "mail.example.com", port = 465, security = security,
        username = user, password = pass, fromAddress = from, fromName = "SuperBiz",
        authEnabled = auth, maxAttempts = 3, timeoutMs = 1000
    )

    /** جلسة فوق قنوات ذاكرة — يعيد الكاتب لفحص ما كُتب ويخزن أعلام الخطّاف */
    private fun session(script: String, cfg: SmtpConfig, upgradeHook: (() -> Unit)? = null):
            Pair<SmtpSession, StringWriter> {
        val writer = StringWriter()
        val s = SmtpSession(
            reader = BufferedReader(StringReader(script)),
            writer = PrintWriter(writer),
            sslUpgrade = upgradeHook,
            config = cfg
        )
        return s to writer
    }

    /** سكربت نجاح كامل مع مصادقة وقناة SSL مباشرة */
    private fun successScript(): String = listOf(
        "220 mail.example.com ESMTP ready",
        "250-mail.example.com",
        "250 AUTH LOGIN",
        "334 VXNlcm5hbWU6",
        "334 UGFzc3dvcmQ6",
        "235 2.7.0 accepted",
        "250 2.1.0 sender ok",
        "250 2.1.5 recipient ok",
        "354 go ahead",
        "250 2.0.0 queued",
        "221 2.0.0 bye"
    ).joinToString("\r\n") + "\r\n"

    // ───── Base64Codec (RFC 4648 متجهات) ─────

    @Test fun `base64 rfc4648 known vectors`() {
        // Base64Codec الإنتاجي مباشرة — internal والاختبار بنفس الحزمة يراه
        val e = { b: ByteArray -> Base64Codec.encode(b) }
        assertEquals("", e("".toByteArray()))
        assertEquals("Zg==", e("f".toByteArray()))
        assertEquals("Zm8=", e("fo".toByteArray()))
        assertEquals("Zm9v", e("foo".toByteArray()))
        assertEquals("Zm9vYg==", e("foob".toByteArray()))
        assertEquals("Zm9vYmE=", e("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", e("foobar".toByteArray()))
    }

    @Test fun `base64Wrap folds at 76 columns`() {
        val big = ByteArray(200) { ('a' + (it % 26)).code.toByte() }
        val wrapped = MimeBuilder.base64Wrap(big)
        assertTrue(wrapped.lines().all { it.length <= 76 })
        // الفك يعيد الأصل — تحقق عكسي بفكّك جافا القياسي (اختبار فقط)
        val decoded = Base64.getMimeDecoder().decode(wrapped)
        assertTrue(big.contentEquals(decoded))
    }

    // ───── MimeBuilder ─────

    @Test fun `mime - header order and structure with attachment`() {
        val boundary = "BND_123"
        val payload = ByteArray(64) { it.toByte() }
        val text = String(
            MimeBuilder.build(
                fromName = "SuperBiz", fromAddress = "me@x.com", to = listOf("to@x.com"),
                subject = "Statement", bodyText = "مرحبا", attachmentName = "kashif.pdf",
                attachmentBytes = payload, boundary = boundary,
                messageId = "abc@x.com", dateMs = 0L
            ),
            Charsets.UTF_8
        )
        val order = listOf("From: ", "To: ", "Subject: ", "Date: ", "MIME-Version: ", "Message-ID: <")
            .map { text.indexOf(it) }
        assertEquals(order, order.sorted())
        assertTrue(order.none { it < 0 })
        assertTrue(text.contains("Content-Type: multipart/mixed; boundary=\"$boundary\""))
        // محدد الفتح والختام بالحد ذاته
        assertTrue(text.contains("--$boundary\r\n"))
        assertTrue(text.contains("--$boundary--\r\n"))
        // المرفق base64 يفكّ مطابقاً — القص بعد ترويسة التخزين (قبلها CTE base64 ثم-Disposition)
        val b64 = text.substringAfter("filename=\"kashif.pdf\"\r\n\r\n")
            .substringBefore("\r\n--$boundary--")
            .replace("\r", "").replace("\n", "")
        assertTrue(payload.contentEquals(Base64.getMimeDecoder().decode(b64)))
        // لا أسطر LF وحيدة — كل الفواصل CRLF
        assertFalse(text.contains(Regex("(?<!\r)\n")))
    }

    @Test fun `mime - no attachment degrades to plain singlepart`() {
        val text = String(
            MimeBuilder.build(
                fromName = "", fromAddress = "me@x.com", to = listOf("to@x.com"),
                subject = "hi", bodyText = "body text", attachmentName = null,
                attachmentBytes = null, boundary = "BND", messageId = "m@x.com", dateMs = 0L
            ),
            Charsets.UTF_8
        )
        assertFalse(text.contains("multipart"))
        assertTrue(text.contains("body text"))
    }

    @Test fun `mime - arabic subject encoded-word decodes back to original`() {
        val subject = "كشف حساب ٢٠٢٦"
        val text = String(
            MimeBuilder.build(
                fromName = "", fromAddress = "me@x.com", to = listOf("t@x.com"),
                subject = subject, bodyText = "b", attachmentName = null,
                attachmentBytes = null, messageId = "m@x.com", dateMs = 0L
            ),
            Charsets.UTF_8
        )
        val header = text.substringAfter("Subject: ").substringBefore("\r\n")
        // الفك: إزالة الطي، ثم فك كل encoded-word بترميز UTF-8 قاعدة-64
        val unfolded = header.replace("\r\n ", "")
        val decoded = Regex("=\\?UTF-8\\?B\\?([^?]*)\\?=").findAll(unfolded)
            .joinToString("") { String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8) }
        assertEquals(subject, decoded)
    }

    @Test fun `mime - arabic body passes through as utf-8 8bit`() {
        val text = String(
            MimeBuilder.build(
                fromName = "", fromAddress = "me@x.com", to = listOf("t@x.com"),
                subject = "s", bodyText = "تم إرفاق كشف الحساب", attachmentName = null,
                attachmentBytes = null, messageId = "m@x.com", dateMs = 0L
            ),
            Charsets.UTF_8
        )
        assertTrue(text.contains("تم إرفاق كشف الحساب"))
    }

    // ───── normalizeEmailAddresses و SmtpConfig ─────

    @Test fun `normalize recipients - trim dedupe and reject invalid`() {
        assertEquals(listOf("a@x.com", "b@x.com"), normalizeEmailAddresses(listOf(" a@x.com ", "a@x.com", "b@x.com")))
        try {
            normalizeEmailAddresses(listOf("not-an-email"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* مقصود */ }
        try {
            normalizeEmailAddresses(emptyList())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) { /* مقصود */ }
        try {
            normalizeEmailAddresses(listOf("<a@x.com>"))
            fail("expected IllegalArgumentException for angle brackets")
        } catch (e: IllegalArgumentException) { /* مقصود */ }
    }

    @Test fun `smtp config - validation rejects bad input`() {
        assertNotNull(config())
        try { config(user = "").let { fail("empty user accepted: $it") } ; } catch (e: IllegalArgumentException) { }
        try { config(from = "no-at-sign").let { fail("bad from accepted: $it") }; } catch (e: IllegalArgumentException) { }
        // ملاحظة: copy() في data class لا يمرّ عبر init — الفحص هنا ببناء صريح
        try {
            SmtpConfig("mail.example.com", 70000, SmtpSecurity.SSL_TLS, "u@x.com", "p", "me@x.com")
            fail("bad port accepted")
        } catch (e: IllegalArgumentException) { }
        try {
            SmtpConfig("mail.example.com", 465, SmtpSecurity.SSL_TLS, "u@x.com", "p", "me@x.com", maxAttempts = 0)
            fail("zero attempts accepted")
        } catch (e: IllegalArgumentException) { }
        try {
            SmtpConfig("", 465, SmtpSecurity.SSL_TLS, "u@x.com", "p", "me@x.com")
            fail("empty host accepted")
        } catch (e: IllegalArgumentException) { }
    }

    @Test fun `smtp config - default ports per security mode`() {
        assertEquals(465, SmtpConfig.defaultPort(SmtpSecurity.SSL_TLS))
        assertEquals(587, SmtpConfig.defaultPort(SmtpSecurity.STARTTLS))
        assertEquals(25, SmtpConfig.defaultPort(SmtpSecurity.NONE))
    }

    // ───── SmtpSession فوق قنوات الذاكرة ─────

    @Test fun `session - full success writes protocol in order`() {
        val (s, w) = session(successScript(), config())
        s.send(listOf("to@x.com"), "Statement COR-01", "مرحبا", "kashif.pdf", byteArrayOf(1, 2, 3))
        val out = w.toString()
        assertTrue(out.contains("EHLO superbiz\r\n"))
        assertTrue(out.contains("AUTH LOGIN\r\n"))
        assertTrue(out.contains(Base64.getEncoder().encodeToString("u@x.com".toByteArray())))
        assertTrue(out.contains("MAIL FROM:<me@x.com>\r\n"))
        assertTrue(out.contains("RCPT TO:<to@x.com>\r\n"))
        assertTrue(out.contains("DATA\r\n"))
        assertTrue(out.contains("Subject: Statement COR-01"))
        assertTrue(out.contains("مرحبا"))
        // نهاية DATA نقطة وحيدة على سطر ثم QUIT
        assertTrue(out.contains("\r\n.\r\n"))
        assertTrue(out.contains("QUIT\r\n"))
    }

    @Test fun `session - auth credentials base64-encoded on the wire`() {
        val (s, w) = session(successScript(), config())
        s.send(listOf("to@x.com"), "s", "b", null, null)
        val out = w.toString()
        assertEquals(
            Base64.getEncoder().encodeToString("u@x.com".toByteArray()),
            out.lineSequence().first { it == Base64.getEncoder().encodeToString("u@x.com".toByteArray()) }
        )
        assertTrue(out.contains(Base64.getEncoder().encodeToString("secret".toByteArray())))
    }

    @Test fun `session - bad greeting rejected with code 554`() {
        val (s, _) = session("554 no service\r\n", config())
        try {
            s.send(listOf("to@x.com"), "s", "b", null, null)
            fail("expected SmtpException")
        } catch (e: SmtpException) {
            assertEquals(554, e.smtpCode)
        }
    }

    @Test fun `session - auth failure carries code 535`() {
        val script = listOf(
            "220 ready", "250-mail.example.com", "250 AUTH LOGIN", "535 bad credentials"
        ).joinToString("\r\n") + "\r\n"
        val (s, _) = session(script, config())
        try {
            s.send(listOf("to@x.com"), "s", "b", null, null)
            fail("expected SmtpException")
        } catch (e: SmtpException) {
            assertEquals(535, e.smtpCode)
        }
    }

    @Test fun `session - rejected recipient carries code 550`() {
        val script = listOf(
            "220 ready", "250-mail.example.com", "250 AUTH LOGIN",
            "334 VXNlcm5hbWU6", "334 UGFzc3dvcmQ6", "235 ok",
            "250 sender ok", "550 no such user"
        ).joinToString("\r\n") + "\r\n"
        val (s, _) = session(script, config())
        try {
            s.send(listOf("to@x.com"), "s", "b", null, null)
            fail("expected SmtpException")
        } catch (e: SmtpException) {
            assertEquals(550, e.smtpCode)
        }
    }

    @Test fun `session - multiple recipients each get RCPT TO`() {
        val script = listOf(
            "220 ready", "250-mail.example.com", "250 AUTH LOGIN",
            "334 VXNlcm5hbWU6", "334 UGFzc3dvcmQ6", "235 ok",
            "250 sender ok", "250 r1", "250 r2", "354 go", "250 queued", "221 bye"
        ).joinToString("\r\n") + "\r\n"
        val (s, w) = session(script, config())
        s.send(listOf("a@x.com", "b@x.com"), "s", "b", null, null)
        val out = w.toString()
        assertTrue(out.contains("RCPT TO:<a@x.com>"))
        assertTrue(out.contains("RCPT TO:<b@x.com>"))
    }

    @Test fun `session - STARTTLS refused when server does not advertise it`() {
        val script = listOf(
            "220 ready", "250-mail.example.com", "250 AUTH LOGIN"
        ).joinToString("\r\n") + "\r\n"
        val (s, _) = session(script, config(security = SmtpSecurity.STARTTLS))
        try {
            s.send(listOf("to@x.com"), "s", "b", null, null)
            fail("expected SmtpException for missing STARTTLS")
        } catch (e: SmtpException) {
            assertTrue(e.message!!.contains("STARTTLS"))
        }
    }

    @Test fun `session - STARTTLS upgrade hook invoked and second EHLO issued`() {
        val script = listOf(
            "220 ready",
            "250-mail.example.com", "250 STARTTLS",      // EHLO الأولى تعلن STARTTLS
            "220 go ahead",                              // رد STARTTLS
            "250-mail.example.com", "250 STARTTLS",      // EHLO الثانية فوق القناة المعمّاة
            "250 sender ok", "250 r ok", "354 go", "250 queued", "221 bye"
        ).joinToString("\r\n") + "\r\n"
        var upgrades = 0
        val (s, w) = session(script, config(security = SmtpSecurity.STARTTLS, auth = false)) { upgrades++ }
        s.send(listOf("to@x.com"), "s", "b", null, null)
        assertEquals(1, upgrades)
        assertEquals(2, Regex("EHLO superbiz").findAll(w.toString()).count())
    }

    // ───── dot-stuffing ─────

    @Test fun `dotStuff - leading dot lines get an extra dot`() {
        assertEquals("a\r\n..b\r\nc", SmtpSession.dotStuff("a\r\n.b\r\nc"))
        assertEquals("..start", SmtpSession.dotStuff(".start"))
        assertEquals("no dot\r\nline", SmtpSession.dotStuff("no dot\r\nline"))
    }

    @Test fun `session - body line starting with dot is stuffed end-to-end`() {
        val (s, w) = session(successScript(), config())
        s.send(listOf("to@x.com"), "s", "body\r\n.dot line\r\nend", null, null)
        val out = w.toString()
        assertTrue(out.contains("\r\n..dot line\r\n"))
        assertFalse(out.contains("\r\n.dot line\r\n"))
    }
}
