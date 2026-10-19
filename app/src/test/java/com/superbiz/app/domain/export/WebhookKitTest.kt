package com.superbiz.app.domain.export

import com.superbiz.app.domain.backup.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-23][Wave R10] قفل عقد الحمولة والتوقيع — WebhookKit
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (H4-6 / ADR-001 D1 كما وثّقه رأس WebhookKit):
 *  • الحمولة JSON واحد مستقر النسخ: أي حقل مستقبلي يُضاف فقط (لا يُحذف ولا
 *    يُعاد تسميته) — المستقبِلات الخارجية لأصحاب المتاجر تعتمد أسماء الحقول
 *    حرفياً، فهي عقد عامة لا تفاصيل داخلية. القفل الذهبي هنا يثبّت الأسماء
 *    والترتيب والقيم لمدخلات مرجعية — أي تغيير شكل يفشل الاختبار عمداً
 *    (الاحتكاك المقصود: تعديل العقد يستوجب مراجعة واعية لا انزلاقاً صامتاً).
 *  • النصوص المعادية (اقتباسات، مائل عكسي، أسطر، محارف تحكم، عربية) تُهرَّب
 *    عبر escapeJson في «number» و«party» وتدور الوثيقة عبر المحلل الذاتي
 *    MiniJson دون انكسار ودون تحريف قيمة.
 *  • كتلة العملة الأصلية اختيارية: بلا origCurrency لا مفتاحان أصلاً،
 *    وبوجودها يظهر origTotalPiasters (والاحتياط الموثق في الكود: قيمة غائبة = 0).
 *  • التوقيع HMAC-SHA256 مقتطع إلى 16 بايت hex — متجه RFC 4231 (الحالة 2)
 *    مرجعاً مستقلاً عن التنفيذ نفسه، والسر الفارغ يعود "" صادقاً (بلا ترويسة
 *    عند الاستخدام — عقد WebhookClient الموثق).
 *  • سداسي التوقيع ASCII دائماً تحت أي إعدادات محلية (تحليل RVW-24 المرفوض:
 *    Formatter لا يعرّب تحويلات x/o — قفل رجعي في حال انحرفت منصة يوماً).
 */
class WebhookKitTest {

    // ── عقد الحمولة: القفل الذهبي لمدخلات مرجعية ──────────────────────────

    @Test
    fun payloadGoldenContract() {
        val payload = WebhookKit.invoiceCreated(
            invoiceId = 42L,
            invoiceNumber = "INV-2026-001",
            totalPiasters = 15000L,
            taxPiasters = 2250L,
            partyName = "مؤسسة النور",
            currencyCode = "SAR",
            origCurrency = null,
            origTotal = null,
            at = 1760000000000L
        )
        // الأسماء والترتيب والقيم مثبتة حرفياً — العقد العام للمستقبِلات
        assertEquals(
            "{\"event\":\"invoice.created\"," +
                "\"sentAt\":1760000000000," +
                "\"data\":{\"invoiceId\":42," +
                "\"number\":\"INV-2026-001\"," +
                "\"totalPiasters\":15000," +
                "\"taxPiasters\":2250," +
                "\"party\":\"مؤسسة النور\"," +
                "\"currency\":\"SAR\"}}",
            payload
        )
        // القفل الذهبي وحده لا يكفي: الوثيقة تحلل كـ JSON سليم (قيمة واحدة لا تسمج)
        val parsed = parseJson(payload) as Map<*, *>
        assertEquals("invoice.created", parsed["event"])
        val data = parsed["data"] as Map<*, *>
        assertEquals(42L, data["invoiceId"])
        assertEquals(15000L, data["totalPiasters"])
    }

    @Test
    fun payloadEscapesHostileStrings() {
        val hostileNumber = "IN\"V\\2026\n\tX\u0001"
        val hostileParty = "شركة <ال>&\"عمق\"\n\\تاج"
        val payload = WebhookKit.invoiceCreated(
            invoiceId = 7L,
            invoiceNumber = hostileNumber,
            totalPiasters = 100L,
            taxPiasters = 15L,
            partyName = hostileParty,
            currencyCode = "SAR",
            origCurrency = null,
            origTotal = null,
            at = 1760000000001L
        )
        // تدوير كامل عبر المحلل الذاتي: لا انكسار ولا تحريف — التهريب سليم في الحقلين
        val parsed = parseJson(payload) as Map<*, *>
        val data = parsed["data"] as Map<*, *>
        assertEquals(hostileNumber, data["number"])
        assertEquals(hostileParty, data["party"])
    }

    @Test
    fun partyNullStaysNull() {
        val payload = WebhookKit.invoiceCreated(
            invoiceId = 9L,
            invoiceNumber = "N-1",
            totalPiasters = 1L,
            taxPiasters = 0L,
            partyName = null,
            currencyCode = "SAR",
            origCurrency = null,
            origTotal = null,
            at = 1760000000002L
        )
        val data = (parseJson(payload) as Map<*, *>)["data"] as Map<*, *>
        assertTrue(data.containsKey("party"))
        assertEquals(null, data["party"])
    }

    // ── كتلة العملة الأصلية: اختيارية بالكامل مع احتياطها الموثق ──────────

    @Test
    fun origBlockOmittedWhenAbsent() {
        val payload = WebhookKit.invoiceCreated(
            invoiceId = 1L, invoiceNumber = "N", totalPiasters = 1L, taxPiasters = 0L,
            partyName = null, currencyCode = "SAR",
            origCurrency = null, origTotal = null, at = 1L
        )
        assertFalse(payload.contains("origCurrency"))
        assertFalse(payload.contains("origTotalPiasters"))
    }

    @Test
    fun origBlockPresentAndZeroFallbackPinned() {
        // قيمة موجودة: تمر كما هي
        val with = WebhookKit.invoiceCreated(
            invoiceId = 1L, invoiceNumber = "N", totalPiasters = 1L, taxPiasters = 0L,
            partyName = null, currencyCode = "SAR",
            origCurrency = "USD", origTotal = 12345L, at = 1L
        )
        val dataWith = (parseJson(with) as Map<*, *>)["data"] as Map<*, *>
        assertEquals("USD", dataWith["origCurrency"])
        assertEquals(12345L, dataWith["origTotalPiasters"])
        // عملة بلا مبلغ: الاحتياط الموثق في الكود (origTotal ?: 0L) — مثبّت هنا
        val zero = WebhookKit.invoiceCreated(
            invoiceId = 1L, invoiceNumber = "N", totalPiasters = 1L, taxPiasters = 0L,
            partyName = null, currencyCode = "SAR",
            origCurrency = "USD", origTotal = null, at = 1L
        )
        val dataZero = (parseJson(zero) as Map<*, *>)["data"] as Map<*, *>
        assertEquals(0L, dataZero["origTotalPiasters"])
    }

    // ── التوقيع: متجه مستقل + السر الفارغ + سداسي ASCII تحت أي محلية ──────

    @Test
    fun signMatchesRfc4231VectorTruncated() {
        // RFC 4231 الحالة 2 (مفتاح "Jefe") — مرجع مستقل عن التنفيذ:
        // HMAC-SHA256 الكامل 5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843
        // والاقتطاع إلى 16 بايت = أول 32 محرفاً سداسياً
        assertEquals(
            "5bdcc146bf60754e6a042426089575c7",
            WebhookKit.sign("what do ya want for nothing?", "Jefe")
        )
    }

    @Test
    fun signEmptySecretReturnsEmptyHonestly() {
        assertEquals("", WebhookKit.sign("any payload", ""))
    }

    @Test
    fun signAsciiHexUnderArabicLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "SA"))
            val sig = WebhookKit.sign("what do ya want for nothing?", "Jefe")
            assertEquals("5bdcc146bf60754e6a042426089575c7", sig)
            // سداسي ASCII صرف — ترويسة X-SuperBiz-Signature صالحة بيتياً تحت أي محلية
            assertEquals(32, sig.length)
            assertTrue(sig.all { it in '0'..'9' || it in 'a'..'f' })
        } finally {
            Locale.setDefault(original)
        }
    }
}
