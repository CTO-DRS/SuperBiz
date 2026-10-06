package com.superbiz.app

import com.superbiz.app.domain.algo.PartyGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P11-a] اختبارات PartyGeo — منطق نقي بلا أندرويد (JUnit4).
 * المسافة الرياضية-جدة محسوبة مسبقاً بنفس الصيغة ونصف القطر وتقاطعت يدوياً
 * (‎845.1 كم تقريباً)، فالتأكيد مجال 830..870 كم تسامحاً لاختلافات التقريب.
 */
class PartyGeoTest {

    // ───────── isValid ─────────

    @Test
    fun isValid_acceptsRiyadhCoords() {
        assertTrue(PartyGeo.isValid(24.7, 46.6))
        assertTrue(PartyGeo.isValid(0.0, 0.0))
        assertTrue(PartyGeo.isValid(-90.0, 180.0)) // الحدود مسموحة
        assertTrue(PartyGeo.isValid(90.0, -180.0))
    }

    @Test
    fun isValid_rejectsOutOfRangeNullAndNaN() {
        assertFalse(PartyGeo.isValid(91.0, 0.0))        // خط عرض خارج المدى
        assertFalse(PartyGeo.isValid(0.0, 181.0))       // خط طول خارج المدى
        assertFalse(PartyGeo.isValid(null, 0.0))        // قيمة مفقودة
        assertFalse(PartyGeo.isValid(24.0, null))
        assertFalse(PartyGeo.isValid(Double.NaN, 46.0)) // NaN ليس موقعاً
        assertFalse(PartyGeo.isValid(24.0, Double.NaN))
        assertFalse(PartyGeo.isValid(Double.POSITIVE_INFINITY, 0.0))
    }

    // ───────── formatLatLng ─────────

    @Test
    fun formatLatLng_fourDecimalsWithDegreeSign() {
        assertEquals("24.7136°, 46.6753°", PartyGeo.formatLatLng(24.7136, 46.6753))
        // حشو الأصفار حتى 4 منازل
        assertEquals("24.7000°, 46.6000°", PartyGeo.formatLatLng(24.7, 46.6))
        // السالب والصفر المعنبر
        assertEquals("-21.4858°, -39.1925°", PartyGeo.formatLatLng(-21.4858, -39.1925))
        assertEquals("0.0000°, 0.0000°", PartyGeo.formatLatLng(-0.00001, -0.00001))
    }

    // ───────── haversineMeters ─────────

    @Test
    fun haversine_riyadhToJeddahIsAbout850km() {
        val d = PartyGeo.haversineMeters(24.7136, 46.6753, 21.4858, 39.1925)
        assertTrue("المسافة $d خارج المدى المتوقع", d in 830_000.0..870_000.0)
    }

    @Test
    fun haversine_samePointIsZeroAndSymmetric() {
        assertEquals(0.0, PartyGeo.haversineMeters(24.7, 46.6, 24.7, 46.6), 1e-9)
        val ab = PartyGeo.haversineMeters(24.7, 46.6, 21.4, 39.1)
        val ba = PartyGeo.haversineMeters(21.4, 39.1, 24.7, 46.6)
        assertEquals(ab, ba, 1e-6)
    }

    // ───────── geoUri ─────────

    @Test
    fun geoUri_containsCoordsAndEncodedLabel() {
        val uri = PartyGeo.geoUri(24.7136, 46.6753, "Riyadh Branch")
        assertTrue(uri.startsWith("geo:24.7136,46.6753?q=24.7136,46.6753("))
        assertTrue(uri.contains("(Riyadh+Branch)"))
        // تسمية عربية تُرمَّز UTF-8 بلا أحرف خام
        val ar = PartyGeo.geoUri(24.7136, 46.6753, "عميل الرياض")
        assertTrue(ar.contains("(" + java.net.URLEncoder.encode("عميل الرياض", "UTF-8") + ")"))
    }

    @Test
    fun geoUri_nullOrBlankLabelHasNoLabelSuffix() {
        assertEquals("geo:24.7136,46.6753?q=24.7136,46.6753", PartyGeo.geoUri(24.7136, 46.6753, null))
        assertEquals("geo:24.7136,46.6753?q=24.7136,46.6753", PartyGeo.geoUri(24.7136, 46.6753, "  "))
    }

    // ───────── deltaLabel ─────────

    @Test
    fun deltaLabel_metersAndKilometersWithWesternDigits() {
        assertEquals("650 م", PartyGeo.deltaLabel(650.0))
        assertEquals("999 م", PartyGeo.deltaLabel(999.4))
        assertEquals("1.5 كم", PartyGeo.deltaLabel(1500.0))
        assertEquals("12.3 كم", PartyGeo.deltaLabel(12_300.0))
    }
}
