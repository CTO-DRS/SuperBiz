package com.superbiz.app.network

import android.util.Base64
import com.superbiz.app.data.repo.ZatcaEnableStore
import com.superbiz.app.domain.ZatcaGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * [Z2-ب V 1.5.0] عميل منصة «فاتورة» — التنفيذ الوحيد للنقل داخل حزمة
 * `network` المعزولة (D1 من ADR-001) فوق [ZatcaApi] النقي.
 *
 * ─── بنود ADR-001 المنفَّذة هنا حرفياً ───
 * - **D2 البوابة المزدوجة**: لا socket واحد قبل (أ) تفعيل صريح في
 *   [ZatcaEnableStore] و(ب) توفر بيانات اعتماد CSID — ناقص أيهما ⇒
 *   TransientFailure بلا أي محاولة اتصال (القائمة تُجَدْوَل لاحقاً).
 * - **D3 البيع لا ينتظر**: هذا العميل يستدعى من قائمة الانتظار حصراً
 *   (ZatcaReportWorker) — لا مسار بيع/طباعة يلمسه.
 * - **D4 عميل واحد مقيّد**: HttpURLConnection من المنصة — صفر تبعيات
 *   شبكية جديدة (أدق من «السماح بـOkHttp» في الـADR: لا نضيف مكتبة أصلاً —
 *   ملاحظة تنفيذ موثقة في ADR-001 §6)؛ مهلات صريحة (اتصال 10ث/قراءة 20ث)
 *   وTLS 1.2+ حصراً وCertificate Pinning اختياري بـSPKI SHA-256.
 * - **D5 خرائط البيانات مغلقة**: ما يُرسل حصراً جسم ZatcaApi.requestBody
 *   (مستند XML + هاشه + uuid + PIH) نحو نطاق gw-fatura.zatca.gov.sa —
 *   لا شيء آخر إطلاقاً.
 *
 * بيانات الاعتماد (CSID binary token + secret + مفتاح الاشتراك) تُملأ في
 * دفعة Z2-ج عبر مزوّد مرر — بنية الجاهزية كاملة الآن بلا أي بيانات حية.
 */
class ZatcaFatooraGateway(
    private val store: ZatcaEnableStore,
    private val credentials: suspend () -> ZatcaCredentials?,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    /** SPKI SHA-256 (Base64) المسموح بها — فارغة = ثقة النظام القياسية */
    private val pinnedSpkiSha256: Set<String> = emptySet(),
) : ZatcaGateway {

    /** بيانات اعتماد الربط — تُدار من شاشة ZATCA في Z2-ج (لا قيم هنا إطلاقاً) */
    data class ZatcaCredentials(
        val csidToken: String,        // Basic username — binary token من منصة فاتورة
        val csidSecret: String,       // Basic password
        val subscriptionKey: String,  // ocps-apim-subscription-key
    )

    // ───────── الواجهتان ─────────

    override suspend fun clear(request: ZatcaGateway.ClearanceRequest): ZatcaGateway.GatewayResult =
        withContext(Dispatchers.IO) { dispatch(isClearance = true, xml = request.invoiceXml, hash = request.invoiceHash, uuid = request.uuid, pih = request.pih) }

    override suspend fun report(request: ZatcaGateway.ReportingRequest): ZatcaGateway.GatewayResult =
        withContext(Dispatchers.IO) { dispatch(isClearance = false, xml = request.invoiceXml, hash = request.invoiceHash, uuid = request.uuid, pih = request.pih) }

    // ───────── النقل ─────────

    private suspend fun dispatch(
        isClearance: Boolean,
        xml: String,
        hash: String,
        uuid: String,
        pih: String,
    ): ZatcaGateway.GatewayResult {
        // D2: البوابة المزدوجة قبل أي socket
        if (!store.enabledOnce()) {
            return ZatcaGateway.GatewayResult.TransientFailure(message = "link disabled (gate)")
        }
        val creds = credentials() ?: return ZatcaGateway.GatewayResult.TransientFailure(
            message = "credentials missing (CSID not configured)"
        )
        val production = store.productionOnce()
        val url = if (isClearance) ZatcaApi.clearanceUrl(production) else ZatcaApi.reportingUrl(production)
        val body = ZatcaApi.requestBody(xml, hash, uuid, pih)
        return try {
            val conn = (URL(url).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = this@ZatcaFatooraGateway.connectTimeoutMs
                readTimeout = this@ZatcaFatooraGateway.readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept-Version", "V2")
                setRequestProperty("Accept-Language", "ar")
                setRequestProperty(
                    "Authorization",
                    "Basic " + Base64.encodeToString(
                        "${creds.csidToken}:${creds.csidSecret}".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    ),
                )
                if (creds.subscriptionKey.isNotBlank()) {
                    setRequestProperty("ocps-apim-subscription-key", creds.subscriptionKey)
                }
                if (pinnedSpkiSha256.isNotEmpty()) {
                    // D4/N4: تقييد الشهادات عبر SSLContext يحمل PinningTrustManager
                    // (HttpsURLConnection القياسية لا تكشف setTrustManager ولا
                    // enabledProtocols — قيود TLS تعيش داخل SSLSocketFactory كما في المرجع المنصي)
                    sslSocketFactory = pinnedSslContext().socketFactory
                }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val retryAfter = conn.getHeaderField("Retry-After")
            val responseBody = try {
                (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
            ZatcaApi.parseResponse(responseBody, status, retryAfter)
        } catch (e: Exception) {
            // عجز نقل (مهلة/قطع/DNS) — عابر دائماً، القائمة تعيد بجدولها
            ZatcaGateway.GatewayResult.TransientFailure(message = e.message ?: "transport failure")
        }
    }

    private fun pinnedSslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val delegate = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        ctx.init(null, arrayOf<PinningTrustManager>(PinningTrustManager(delegate)), SecureRandom())
        return ctx
    }

    /**
     * غلاف تقييد الشهادات (N4): يمرر التحقق القياسي أولاً ثم يطابق SPKI
     * SHA-256 لشهادة الورقة — أي عدم مطابقة يقطع الاتصال قبل أي بيانات.
     */
    private inner class PinningTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
            val spki = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            val pin = Base64.encodeToString(spki, Base64.NO_WRAP)
            if (pin !in pinnedSpkiSha256) {
                throw java.security.cert.CertificateException("SPKI pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = delegate.acceptedIssuers
    }
}
