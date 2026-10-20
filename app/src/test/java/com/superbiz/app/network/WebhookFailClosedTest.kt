package com.superbiz.app.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.repo.WebhookStore
import com.superbiz.app.domain.export.WebhookKit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E0.2 V3.3.0] عقد الفشل المغلق لسلّم الويب هوك — حسم المالك الثاني مُنفَّذاً
 * ═══════════════════════════════════════════════════════════════════════════
 * حسم unsigned-fallback من FINDINGS §13-ه (الخيار أ — فشل مغلق):
 *  • سر مُعدٌّ لكن غير مقروء (مفتاح Keystore ضاع بعد استعادة جهاز مثلاً —
 *    الفشل الإنتاجي الحقيقي يُحاكى بتدوير مفتاح الحقن بين التخزين والقراءة)
 *    ⇒ لا يُسلَّم أي حدث غير موقَّع بصمت: send=false، ولا محاولة socket أصلاً،
 *    وlastResult صادقة تكشف العطل لصاحب المتجر.
 *  • سر غير مُعدٍّ إطلاقاً ⇒ التوقيع اختياري بالتصميم (وثيقة H4-6) — التسليم
 *    غير الموقَّع هو اختيار المالك الصريح ويستمر كما هو (عقد H4-6 لا يُكسر).
 *  • سر مقروء ⇒ الترويسة بقيمة HMAC الصحيحة (سلك WebhookKit المقفول RVW-23).
 * الاتصال حقن قابل للاستبدال (openConnection) — القفل يلاحظ المحاولة بلا شبكة.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class WebhookFailClosedTest {

    /** اتصال مزيف يسجل المحاولة والترويسات — لا socket حقيقي أبداً */
    private class FakeConn(url: URL) : HttpsURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        val body = ByteArrayOutputStream()
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun addRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getRequestProperty(key: String): String? = headers[key]
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): java.io.OutputStream = body
        override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): java.io.InputStream? = null
        override fun getResponseCode(): Int = 200
        override fun getCipherSuite(): String = "TLS_FAKE"
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        override fun getServerCertificates(): Array<java.security.cert.Certificate>? = null
    }

    private class RecordingClient(store: WebhookStore) : WebhookClient(store) {
        val connections = mutableListOf<FakeConn>()
        override fun openConnection(url: String): HttpsURLConnection {
            val c = FakeConn(URL(url))
            connections.add(c)
            return c
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: WebhookStore

    @Before
    fun setUp() = runBlocking {
        store = WebhookStore(app)
        // حالة مرجعية نظيفة (المخزن المسماى يستمر داخل العملية — ترتيب مستقل)
        store.setEnabled(true)
        store.setUrl("https://webhook.test/hook")
        store.setSecret("")
    }

    @After
    fun tearDown() = runBlocking {
        store.setEnabled(false)
        store.setUrl("")
        store.setSecret("")
        com.superbiz.app.security.PinVault.keyProvider =
            { com.superbiz.app.security.PinVault.masterKey() }
        Unit
    }

    @Test
    fun configuredButUnreadableSecret_failsClosed_noDeliveryUnsigned() = runBlocking {
        // ① سر يُخزَّن بمفتاح سليم (كما الإنتاج)
        val k1 = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        com.superbiz.app.security.PinVault.keyProvider = { k1 }
        store.setSecret("سِرُّ-المالك-123")
        assertEquals(true, store.secretConfiguredOnce())

        // ② استعادة جهاز: مفتاح Keystore جديد لا يفك القديم — الفشل الإنتاجي الحقيقي
        val k2 = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        com.superbiz.app.security.PinVault.keyProvider = { k2 }
        assertEquals("السر مُعدٌّ في المخزن", true, store.secretConfiguredOnce())
        assertEquals("ولكنه غير مقروء — فراغ صادق كما توثيق secretOnce", "", store.secretOnce())

        val client = RecordingClient(store)
        val ok = client.send("""{"event":"invoice.created"}""")

        // الفشل مغلق: لا تسليم غير موقَّع، لا محاولة socket، نتيجة أخيرة صادقة
        assertFalse("غير الموقَّع لا يُسلَّم — send يعود false", ok)
        assertEquals("لا محاولة إرسال أصلاً قبل بوابة التوقيع", 0, client.connections.size)
        val (at, lastOk) = store.lastResult()
        assertTrue("طابع lastResult مكتوب", at > 0)
        assertFalse("lastResult صادقة = فشل مرئي", lastOk)
    }

    @Test
    fun noSecretConfigured_unsignedDeliveryIsOwnersExplicitChoice_stillDelivers() = runBlocking {
        // لا سر إطلاقاً — التوقيع اختياري بالتصميم (وثيقة H4-6) ولا شيء يتغير
        assertEquals(false, store.secretConfiguredOnce())

        val client = RecordingClient(store)
        val ok = client.send("""{"event":"invoice.created"}""")

        assertTrue("التسليم غير الموقَّع مشروع حين لا سر مُعداً", ok)
        assertEquals("المسار المشروع يلمس النقل مرة واحدة", 1, client.connections.size)
        assertNull("بلا سر = بلا ترويسة توقيع", client.connections[0].headers[WebhookKit.SIGNATURE_HEADER])
        val (_, lastOk) = store.lastResult()
        assertTrue(lastOk)
    }

    @Test
    fun readableSecret_signatureHeaderSetWithCorrectHmac() = runBlocking {
        val testKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        com.superbiz.app.security.PinVault.keyProvider = { testKey }
        store.setSecret("سِرُّ-المالك-123")
        assertEquals(true, store.secretConfiguredOnce())

        val client = RecordingClient(store)
        val payload = """{"event":"invoice.created","sentAt":1,"data":{}}"""
        val ok = client.send(payload)

        assertTrue(ok)
        assertEquals(1, client.connections.size)
        val expected = WebhookKit.sign(payload, "سِرُّ-المالك-123")
        assertNotNull("السر المقروء يوقّع دائماً", client.connections[0].headers[WebhookKit.SIGNATURE_HEADER])
        assertEquals(expected, client.connections[0].headers[WebhookKit.SIGNATURE_HEADER])
    }
}
