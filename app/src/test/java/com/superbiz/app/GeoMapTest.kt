package com.superbiz.app

import com.superbiz.app.data.db.Party
import com.superbiz.app.domain.algo.GeoMap
import com.superbiz.app.domain.algo.GeoPoint
import com.superbiz.app.domain.algo.MapLayout
import com.superbiz.app.domain.algo.PartyGeo
import com.superbiz.app.domain.algo.sortPartiesByDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P16-c] اختبارات GeoMap — منطق نقي بلا أندرويد (JUnit4 JVM خالص):
 * الإسقاط الإيزومتري بنكماش cos(lat0)، الحفاظ على نسبة الأبعاد، انعكاس y
 * (الشمال أعلى)، حدود الحشو، الحالات المنحلّة، وnearest بهافرساين PartyGeo
 * (المصدر الواحد مع RouteOrder — قفله باختبار التوافق الأخير).
 * المسافات المرجعية محسوبة مسبقاً يدوياً بنفس الصيغة (رياض-جدة ≈ 845.1 كم
 * كما في PartyGeoTest، ودرجة عرض واحدة ≈ 111.2 كم).
 */
class GeoMapTest {

    private fun p(id: Long, lat: Double, lng: Double, label: String = "ن$id") =
        GeoPoint(id, lat, lng, label)

    // ───────── project: الحالات المنحلّة ─────────

    @Test
    fun project_emptyListReturnsEmptyPositionsAndNullSpan() {
        val layout = GeoMap.project(emptyList())
        assertTrue(layout.positions.isEmpty())
        assertNull(layout.spanMeters)
    }

    @Test
    fun project_singlePointAtCenterAndNullSpan() {
        val layout = GeoMap.project(listOf(p(1, 24.7136, 46.6753)))
        assertEquals(1, layout.positions.size)
        val pos = layout.positions[1]!!
        assertEquals(0.5f, pos.x, 1e-6f)
        assertEquals(0.5f, pos.y, 1e-6f)
        assertNull(layout.spanMeters) // نقطة واحدة: لا معنى لامتداد
    }

    @Test
    fun project_allCoincidentPointsAllAtCenterAndNullSpan() {
        val layout = GeoMap.project(
            listOf(p(1, 24.7, 46.7), p(2, 24.7, 46.7), p(3, 24.7, 46.7))
        )
        assertEquals(3, layout.positions.size)
        layout.positions.values.forEach { pos ->
            assertEquals(0.5f, pos.x, 1e-6f)
            assertEquals(0.5f, pos.y, 1e-6f)
        }
        assertNull(layout.spanMeters) // صندوق مساحته صفر ⇒ منحلّ
    }

    // ───────── project: الترتيب والاتجاه ─────────

    @Test
    fun project_twoPointsLngDiff_xOrderedWithinPadding() {
        val layout = GeoMap.project(listOf(p(1, 24.7, 45.0), p(2, 24.7, 47.0)))
        val west = layout.positions[1]!!
        val east = layout.positions[2]!!
        // الشرق يمين: خط الطول الأكبر ⇒ x أكبر
        assertTrue(east.x > west.x)
        // المحور الوحيد الممتد يملأ المدى كاملاً [padding, 1-padding]
        assertEquals(0.08f, west.x, 1e-3f)
        assertEquals(0.92f, east.x, 1e-3f)
        // خط العرض متماثل ⇒ y يتمركز في الوسط
        assertEquals(0.5f, west.y, 1e-6f)
        assertEquals(0.5f, east.y, 1e-6f)
    }

    @Test
    fun project_twoPointsLatDiff_northIsUpSmallerY() {
        val layout = GeoMap.project(listOf(p(1, 23.0, 46.0), p(2, 25.0, 46.0)))
        val south = layout.positions[1]!!
        val north = layout.positions[2]!!
        // y يتزايد نحو الأسفل ⇒ الشمال (خط العرض الأكبر) y أصغر
        assertTrue(north.y < south.y)
        assertEquals(0.08f, north.y, 1e-3f) // الشمال أعلى اللوحة
        assertEquals(0.92f, south.y, 1e-3f) // الجنوب أسفلها
        // خط الطول متماثل ⇒ x يتمركز في الوسط (محور منحلّ منفرد)
        assertEquals(0.5f, north.x, 1e-6f)
        assertEquals(0.5f, south.x, 1e-6f)
    }

    // ───────── project: نسبة الأبعاد وinkماش cos(lat0) ─────────

    @Test
    fun project_aspectRatioPreserved_oneDegLatTwoDegLng() {
        // صندوق 1° عرض × 2° طول عند lat0=0 ⇒ spanX = 2 × spanY
        val layout = GeoMap.project(
            listOf(p(1, 0.5, -1.0), p(2, -0.5, 1.0), p(3, 0.0, 0.0))
        )
        val xs = layout.positions.values.map { it.x }
        val ys = layout.positions.values.map { it.y }
        val xSpan = (xs.max() - xs.min()).toDouble()
        val ySpan = (ys.max() - ys.min()).toDouble()
        assertEquals(2.0, xSpan / ySpan, 0.01) // لا تمديد ولا تشويه
    }

    @Test
    fun project_cosLat0ShrinksLongitudeSpanAtHighLatitude() {
        // الشكل نفسه (2° طول × 2° عرض) عند مركز 60° مقابل مركز 0°
        val at60 = GeoMap.project(
            listOf(p(1, 59.0, 0.0), p(2, 61.0, 2.0), p(3, 60.0, 1.0))
        )
        val at0 = GeoMap.project(
            listOf(p(1, -1.0, 0.0), p(2, 1.0, 2.0), p(3, 0.0, 1.0))
        )
        fun xSpan(l: MapLayout): Double {
            val xs = l.positions.values.map { it.x }
            return (xs.max() - xs.min()).toDouble()
        }
        val span60 = xSpan(at60)
        val span0 = xSpan(at0)
        // cos(60°)=0.5 ⇒ الامتداد المطبع ينكمش إلى النصف تقريباً
        assertEquals(0.5, span60 / span0, 0.02)
        assertTrue(span60 < span0)
    }

    // ───────── project: حدود الحشو والدفاع ─────────

    @Test
    fun project_allPositionsInsidePaddingBounds() {
        val spread = listOf(
            p(1, 24.0, 46.0), p(2, 25.0, 47.0), p(3, 24.0, 47.0),
            p(4, 25.0, 46.0), p(5, 24.5, 46.5), p(6, 24.2, 46.8)
        )
        val eps = 1e-4f
        fun assertInside(layout: MapLayout, pad: Float) {
            layout.positions.values.forEach { pos ->
                assertTrue(
                    "x=${pos.x} خارج [${pad}، ${1f - pad}]",
                    pos.x >= pad - eps && pos.x <= 1f - pad + eps
                )
                assertTrue(
                    "y=${pos.y} خارج [${pad}، ${1f - pad}]",
                    pos.y >= pad - eps && pos.y <= 1f - pad + eps
                )
            }
        }
        assertInside(GeoMap.project(spread), 0.08f) // الحشو الافتراضي
        assertInside(GeoMap.project(spread, padding = 0.2f), 0.2f) // حشو مخصص
    }

    @Test
    fun project_dropsNonFinitePointsDefensively() {
        // العقد: صلاحية النطاق مسؤولية المتصل (PartyGeo.isValid) — استبعاد
        // غير المنتهي دفاعي فقط كي لا يفسد التخطيط صمتاً
        val layout = GeoMap.project(
            listOf(p(1, 24.7, 46.7), GeoPoint(2, Double.NaN, 46.0, "x"), GeoPoint(3, 24.0, Double.POSITIVE_INFINITY, "y"))
        )
        assertEquals(setOf(1L), layout.positions.keys)
    }

    // ───────── nearest ─────────

    @Test
    fun nearest_exactHitReturnsThatPoint() {
        val pts = listOf(p(1, 24.7136, 46.6753), p(2, 21.4858, 39.1925))
        val hit = GeoMap.nearest(pts, 21.4858, 39.1925)
        assertEquals(2L, hit?.id)
    }

    @Test
    fun nearest_beyondMaxMetersReturnsNull() {
        val pts = listOf(p(1, 24.7136, 46.6753), p(2, 21.4858, 39.1925))
        // ~90 كم عن أول نقطة — يتجاوز الحد الافتراضي 2000م ⇒ null (منع مسكة خاطئة)
        assertNull(GeoMap.nearest(pts, 24.0, 46.0))
        // الحد الواسع يُرجع الأقرب فعلاً
        assertEquals(1L, GeoMap.nearest(pts, 24.0, 46.0, maxMeters = Double.MAX_VALUE)?.id)
    }

    @Test
    fun nearest_emptyListReturnsNull() {
        assertNull(GeoMap.nearest(emptyList(), 24.0, 46.0))
    }

    @Test
    fun nearest_tieReturnsFirstInList() {
        val pts = listOf(p(7, 24.0, 46.0), p(8, 24.0, 46.0), p(9, 24.0, 46.0))
        assertEquals(7L, GeoMap.nearest(pts, 24.0, 46.0)?.id)
    }

    @Test
    fun nearest_nonFiniteQueryReturnsNullWithoutThrowing() {
        val pts = listOf(p(1, 24.7, 46.7), p(2, 21.4, 39.1))
        // مرجع غير منطقي ⇒ كل المسافات NaN ⇒ لا فائز (بلا استثناء)
        assertNull(GeoMap.nearest(pts, Double.NaN, 46.0))
    }

    // ───────── spanMeters: مقارنة بحساب يدوي ─────────

    @Test
    fun spanMeters_matchesHandComputedHaversineWithinOnePercent() {
        // رياض ↔ جدة: القطر الجغرافي للصندوق المحيط ≈ 845.1 كم (حساب يدوي بنفس الصيغة)
        val rj = GeoMap.project(
            listOf(p(1, 24.7136, 46.6753), p(2, 21.4858, 39.1925))
        )
        val spanRj = rj.spanMeters!!
        assertEquals(845_100.0, spanRj, 845_100.0 * 0.01)

        // درجة عرض واحدة على خط الاستواء ≈ 111.2 كم (قيمة معروفة)
        val oneDeg = GeoMap.project(listOf(p(1, 0.0, 0.0), p(2, 1.0, 0.0)))
        assertEquals(111_195.0, oneDeg.spanMeters!!, 1_112.0)
    }

    // ───────── التوافق مع RouteOrder (مصدر المسافة الواحد) ─────────

    @Test
    fun routeOrder_andGeoMap_agreeOnClosestPartyAndDistance() {
        val myLat = 24.7
        val myLng = 46.7
        val parties = listOf(
            Party(id = 11, name = "متوسط", lat = 23.6, lng = 44.0),
            Party(id = 12, name = "بعيد", lat = 21.5, lng = 39.2),
            Party(id = 13, name = "قريب", lat = 24.75, lng = 46.72)
        )
        val ordered = sortPartiesByDistance(parties, myLat, myLng)
        val closestBySort = ordered.first { it.distanceMeters != null }
        val pts = parties.map { GeoPoint(it.id, it.lat!!, it.lng!!, it.name) }
        // nearest (بنفس هافرساين PartyGeo) يختار الأقرب نفسه الذي رتّبه RouteOrder أولاً
        val closestByGeo = GeoMap.nearest(pts, myLat, myLng, maxMeters = Double.MAX_VALUE)
        assertEquals(closestBySort.party.id, closestByGeo?.id)
        // والمسافة مطابقة تماماً للمسافة التي تعرضها بطاقات «الأقرب أولاً»
        assertEquals(
            closestBySort.distanceMeters!!,
            PartyGeo.haversineMeters(myLat, myLng, 24.75, 46.72),
            1e-9
        )
    }
}
