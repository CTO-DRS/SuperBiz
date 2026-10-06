package com.superbiz.app

import com.superbiz.app.data.db.Party
import com.superbiz.app.domain.algo.PartyDistance
import com.superbiz.app.domain.algo.PartyGeo
import com.superbiz.app.domain.algo.sortPartiesByDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P13-a] اختبارات ترتيب «الأقرب أولاً» — منطق نقي بلا أندرويد (JUnit4، بدون Robolectric).
 *
 * مرجعيات جغرافية حقيقية (مثل PartyGeoTest):
 * - الرياض 24.7136/46.6753 وجدة 21.4858/39.1925 (≈845 كم — نفس مرجعية PartyGeoTest)
 * - مكة 21.4225/39.8262 (من جدة ≈66 كم، ومن الرياض ≈790 كم بالهافرساين على الكرة نفسها)
 * - الدمام 26.4207/50.0888 (من الرياض ≈390 كم)
 * المدى المتروك للتأكيدات واسع كفاية لاختلافات التقريب لا لبس فيها.
 */
class RouteOrderTest {

    private fun party(id: Long, name: String, lat: Double? = null, lng: Double? = null) =
        Party(id = id, name = name, lat = lat, lng = lng)

    private val riyadh = 24.7136 to 46.6753
    private val jeddah = 21.4858 to 39.1925

    // ───────── الترتيب التصاعدي بالمسافة ─────────

    @Test
    fun sort_ascendingByDistanceFromMyLocation() {
        val parties = listOf(
            party(1, "جدة", jeddah.first, jeddah.second),        // ≈845 كم
            party(2, "الدمام", 26.4207, 50.0888),                // ≈390 كم
            party(3, "ضاحية قريبة", 24.8, 46.7)                  // ≈10 كم
        )
        val sorted = sortPartiesByDistance(parties, riyadh.first, riyadh.second)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.party.id })
        // كل الصفوف ذات موقع، والمسافات محسوبة لا null
        assertTrue(sorted.all { it.hasLocation && it.distanceMeters != null })
    }

    // ───────── بلا إحداثيات: الذيل مع الحفاظ على الترتيب النسبي ─────────

    @Test
    fun sort_missingLocationGoesLastAndKeepsRelativeOrder() {
        val noLoc1 = party(10, "بلا موقع ١")
        val noLoc2 = party(20, "بلا موقع ٢")
        val parties = listOf(
            noLoc1,
            party(2, "الدمام", 26.4207, 50.0888),
            noLoc2,
            party(3, "ضاحية قريبة", 24.8, 46.7)
        )
        val sorted = sortPartiesByDistance(parties, riyadh.first, riyadh.second)
        // الموقعان صعدا للأمام تصاعدياً، والاثنان بلا موقع في الذيل بترتيبهما الأصلي
        assertEquals(listOf(3L, 2L, 10L, 20L), sorted.map { it.party.id })
        assertNull(sorted[2].distanceMeters)
        assertNull(sorted[3].distanceMeters)
        assertFalse(sorted[2].hasLocation)
        assertFalse(sorted[3].hasLocation)
        assertTrue(sorted[2].party === noLoc1) // بلا نسخ — نفس الكائن الأصلي
        assertTrue(sorted[3].party === noLoc2)
    }

    // ───────── المسافات المتساوية: استقرار الترتيب الأصلي ─────────

    @Test
    fun sort_equalDistancesKeepOriginalOrder_stable() {
        // توأمان بالإحداثيات نفسها تماماً ⇒ المسافة نفسها بتّياً (حساب حتمي بالمدخلات نفسها)،
        // وثالث أبعد — التعادل بين التوأمين يجب أن يحفظ ترتيبهما الأصلي (sortBy مستقر)
        val p1 = party(1, "توأم ١", 24.7, 46.6)
        val p2 = party(2, "توأم ٢", 24.7, 46.6)
        val p3 = party(3, "أبعد", 25.7, 47.6)
        val sorted = sortPartiesByDistance(listOf(p1, p2, p3), 24.7, 46.6)
        // المسافة نفسها ⇒ sortBy المستقر يحافظ على الترتيب النسبي الأصلي
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.party.id })
        assertEquals(sorted[0].distanceMeters, sorted[1].distanceMeters) // تعادل حقيقي
        assertEquals(0.0, sorted[0].distanceMeters!!, 1e-9) // نقطة موقعي نفسها = صفر تماماً
        assertTrue(sorted[2].distanceMeters!! > 0.0)
    }

    // ───────── موقعي غير صالح: الترتيب الأصلي بلا مسافات ─────────

    @Test
    fun fallback_nullMyLocation_wrapsOriginalOrderWithoutDistances() {
        val withLoc = party(1, "بموقع", 24.7, 46.6)
        val withoutLoc = party(2, "بلا موقع")
        val parties = listOf(withLoc, withoutLoc)
        val sorted = sortPartiesByDistance(parties, null, null)
        assertEquals(listOf(1L, 2L), sorted.map { it.party.id })
        assertNull(sorted[0].distanceMeters)
        assertNull(sorted[1].distanceMeters)
        assertTrue(sorted[0].hasLocation) // إحداثيات الطرف نفسها تُبلَّغ بصدق
        assertFalse(sorted[1].hasLocation)
    }

    @Test
    fun fallback_outOfRangeOrNaNMyLocation_fallsBackToo() {
        val parties = listOf(party(1, "أ", 24.7, 46.6))
        // خط عرض خارج المدى
        val s1 = sortPartiesByDistance(parties, 95.0, 46.6)
        assertEquals(listOf(1L), s1.map { it.party.id })
        assertNull(s1[0].distanceMeters)
        // NaN ليس موقعاً
        val s2 = sortPartiesByDistance(parties, Double.NaN, 46.6)
        assertNull(s2[0].distanceMeters)
        // خط طول خارج المدى
        val s3 = sortPartiesByDistance(parties, 24.7, 181.0)
        assertNull(s3[0].distanceMeters)
        // لانهائية
        val s4 = sortPartiesByDistance(parties, Double.POSITIVE_INFINITY, 46.6)
        assertNull(s4[0].distanceMeters)
    }

    // ───────── الحالات الحدّية ─────────

    @Test
    fun sort_emptyListReturnsEmptyList() {
        assertTrue(sortPartiesByDistance(emptyList(), riyadh.first, riyadh.second).isEmpty())
        assertTrue(sortPartiesByDistance(emptyList(), null, null).isEmpty())
    }

    @Test
    fun sort_singleElement_withAndWithoutLocation() {
        val same = party(1, "نفس النقطة", riyadh.first, riyadh.second)
        val one = sortPartiesByDistance(listOf(same), riyadh.first, riyadh.second)
        assertEquals(1, one.size)
        assertEquals(0.0, one[0].distanceMeters!!, 1e-9) // النقطة نفسها = صفر تماماً

        val missing = sortPartiesByDistance(listOf(party(2, "بلا موقع")), riyadh.first, riyadh.second)
        assertNull(missing[0].distanceMeters)
        assertFalse(missing[0].hasLocation)
    }

    // ───────── إحداثيات مختلطة نصف الكرة: الترتيب الجغرافي صحيح ─────────

    @Test
    fun sort_mixedHemispheres_geographicOrderIsCorrect() {
        // انطلاق من جدة: مكة ≈66 كم ثم الرياض ≈845 كم، ونقطة نصف الكرة الجنوبي أبعد من الاثنين
        val mecca = party(1, "مكة", 21.4225, 39.8262)
        val riyadhP = party(2, "الرياض", riyadh.first, riyadh.second)
        val southern = party(3, "جنوبي", -21.4225, -39.8262)
        val sorted = sortPartiesByDistance(listOf(riyadhP, southern, mecca), jeddah.first, jeddah.second)
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.party.id })
        // نقطة نصف الكرة الآخر تُعامل حسابياً بلا أي قيمة زائفة
        assertTrue(sorted[2].distanceMeters!! > sorted[1].distanceMeters!!)
    }

    // ───────── صدق القيم: تطابق الهافرساين ومدى معقول ─────────

    @Test
    fun sort_distanceValuesMatchHaversineAndAreSane() {
        val mecca = party(1, "مكة", 21.4225, 39.8262)
        val sorted = sortPartiesByDistance(listOf(mecca), riyadh.first, riyadh.second)
        val d = sorted[0].distanceMeters!!
        // القيمة نفسها هي هافرساين PartyGeo حرفياً (نفس الصيغة والمرجع)
        assertEquals(
            PartyGeo.haversineMeters(riyadh.first, riyadh.second, 21.4225, 39.8262), d, 1e-6
        )
        // الرياض→مكة على الكرة نفسها ≈790 كم (شخصية PartyGeoTest: الرياض→جدة ≈845 كم
        // ومكة تبعد ≈66 كم عن جدة في اتجاه الرياض) — المدى 700..850 كم تسامحاً واسعاً
        assertTrue("المسافة $d خارج المدى المتوقع", d in 700_000.0..850_000.0)
    }
}
