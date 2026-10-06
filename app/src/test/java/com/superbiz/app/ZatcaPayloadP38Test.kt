package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaQr
import com.superbiz.app.security.ZatcaPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات [P38-Z1] — بنّاء الحمولة الموحد ZatcaPayload (Robolectric لأنه يعتمد
 * Context عبر ZatcaPrefs):
 *  - الرقم الضريبي فارغ ⇒ null (لا QR لإطلاقاً — عقد الجواز)
 *  - رقم موجود + بصمة موقوفة ⇒ حمولة المرحلة-1 حرفياً = ZatcaQr.qrPayload
 *    (العقد الحتمي: نفس اللقطة تعطي نفس الحمولة في PDF والإيصال الحراري)
 *  - فشل حلقة التوقيع (لا مفتاح AndroidKeyStore على Robolectric) ⇒ مرحلة-1
 *    بصمت — سلوك الفشل الصامت المجرَّب لا يُسقط فاتورة صالحة
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ZatcaPayloadP38Test {

    private fun ctx() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun blankVatNumber_returnsNull() {
        assertNull(
            ZatcaPayload.qr(ctx(), "مؤسسة", "", 1_650_911_400_000L, 115.0, 15.0, 2, "INV-1")
        )
    }

    @Test
    fun stampDisabled_returnsStage1Exactly_deterministic() {
        // ZatcaPrefs الافتراضي stamped=false — المسار مرحلة-1 خالص
        val got = ZatcaPayload.qr(
            ctx(), "Bunnia Company Limited", "310122393500003",
            1_650_911_400_000L, 115.00, 15.00, 2, "INV-12"
        )
        val expected = ZatcaQr.qrPayload(
            "Bunnia Company Limited", "310122393500003",
            1_650_911_400_000L, 115.00, 15.00
        )
        assertEquals(expected, got)
        // الحتمية: النداء الثاني بنفس اللقطة نفس البايتات (عقد توحيد PDF/الإيصال)
        assertEquals(
            got,
            ZatcaPayload.qr(
                ctx(), "Bunnia Company Limited", "310122393500003",
                1_650_911_400_000L, 115.00, 15.00, 2, "INV-12"
            )
        )
    }

    @Test
    fun stage2EnvironmentUnavailable_fallsBackToStage1Silently() {
        // Robolectric بلا AndroidKeyStore حقيقي — signer/publicKeyPoint يفشلان أو
        // يعيدان null، والصندوق يعود للمرحلة-1 بلا استثناء (عقد الفشل الصامت)
        val got = ZatcaPayload.qr(
            ctx(), "شركة", "310000000000003", 1_650_911_400_000L, 230.0, 30.0, 3, "INV-9"
        )
        assertEquals(
            ZatcaQr.qrPayload("شركة", "310000000000003", 1_650_911_400_000L, 230.0, 30.0),
            got
        )
    }
}
