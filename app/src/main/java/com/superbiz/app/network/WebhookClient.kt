package com.superbiz.app.network

import com.superbiz.app.data.repo.WebhookStore
import com.superbiz.app.domain.export.WebhookKit
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [H4-6][ADR-001] — عميل الويب هوك خلف الحائط (نمط SyncClient): HTTPS حصراً،
 * بوابة قبل أي socket، فشل الإرسال عابر مسجَّل لا يُعاد محاولته آلياً في هذه
 * الدفعة (الحدث القادم يعيد المحاولة عملياً).
 */
class WebhookClient(private val store: WebhookStore) {

    /** إرسال إطفائي — يعود (نجح؟) ويحفظ نتيجة آخر محاولة. لا استثناءات تُهرَّب. */
    suspend fun send(payload: String): Boolean {
        if (!store.enabledOnce()) return false
        val url = store.urlOnce()
        if (!url.startsWith("https://")) return false
        val ok = try {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val sig = WebhookKit.sign(payload, store.secretOnce())
            if (sig.isNotEmpty()) conn.setRequestProperty(WebhookKit.SIGNATURE_HEADER, sig)
            conn.setFixedLengthStreamingMode(payload.toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            code in 200..299
        } catch (e: Exception) {
            false
        }
        store.setLastResult(System.currentTimeMillis(), ok)
        return ok
    }

    /** إرسال فحص يدوي من البطاقة — بحمولة تجريبية معلنة. */
    suspend fun testSend(): Boolean =
        send("""{"event":"webhook.test","sentAt":${System.currentTimeMillis()},"data":{}}""")
}
