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
open class WebhookClient(private val store: WebhookStore) {

    /**
     * نقطة إنشاء الاتصال — فصل قابل للاستبدال في اختبارات Robolectric فقط
     * (سجل الاتصالات بلا شبكة). الإنتاج يفتح HTTPS حصراً كما في H4-6/ADR-001
     * والمسار الإنتاجي حرفياً بلا تغيير.
     */
    internal open fun openConnection(url: String): HttpsURLConnection =
        URL(url).openConnection() as HttpsURLConnection

    /** إرسال إطفائي — يعود (نجح؟) ويحفظ نتيجة آخر محاولة. لا استثناءات تُهرَّب. */
    suspend fun send(payload: String): Boolean {
        if (!store.enabledOnce()) return false
        val url = store.urlOnce()
        if (!url.startsWith("https://")) return false
        // [E0.2 V3.3.0 — حسم المالك أ من FINDINGS §13-ه] الفشل المغلق على التوقيع:
        // سر مُعدٌّ في المخزن لكن غير مقروء (مفتاح Keystore ضاع بعد استعادة جهاز)
        // كان يُسلَّم حدثه غير موقَّع بصمت — هبطة أمني صامت يقبله مستقبل يقبل أحداثاً
        // بلا توقيع دون أن يلاحظ أحد. الآن: لا socket أصلاً، send=false، وlastResult
        // صادقة تكشف العطل لصاحب المتجر حتى يعيد إدخال السر. أما «لا سر مُعداً»
        // فبقى مشروعاً حرفياً (التوقيع اختياري بعقد H4-6 — اختيار المالك الصريح).
        // قراءتان متتاليتان لا ذرتان: أسوأ سباق مع setSecret متزامن ينتهي باتجاه
        // الفشل المغلق (configured=true ثم سر فارغ = رفض) أو تسليم غير موقَّع لحالة
        // لم يكن سرها قد وُضع بعد — نفس الوضع الراهن قبل الحسم ولا يضيف سطحاً.
        val secretConfigured = store.secretConfiguredOnce()
        val sig = WebhookKit.sign(payload, store.secretOnce())
        if (secretConfigured && sig.isEmpty()) {
            store.setLastResult(System.currentTimeMillis(), false)
            return false
        }
        val ok = try {
            val conn = openConnection(url)
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (sig.isNotEmpty()) conn.setRequestProperty(WebhookKit.SIGNATURE_HEADER, sig)
            conn.setFixedLengthStreamingMode(payload.toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // [RVW-22][Wave R10] إغلاق مجرى الاستجابة في كل الحالات: كان الإغلاق
            // يلمس inputStream فقط — وعلى 4xx/5xx يرمي الوصول استثناءً يبتلعه
            // runCatching فيبقى errorStream مفتوحاً بلا قارئ، فلا تعود الاتصال
            // للمِجمع ويتسرب مقبس لكل تسليم فاشل (نقطة نهاية منقولة أو معطوبة =
            // تسريبات متراكمة على جهاز يطلق حدثاً لكل فاتورة). الاختيار حسب
            // الحالة: 2xx يغلق inputStream، وغيره يغلق errorStream (قد يكون
            // عديماً — الاستدعاء الآمن)، والفشل هنا عابر مسجَّل كما في الأعلى.
            runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.close() }
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
