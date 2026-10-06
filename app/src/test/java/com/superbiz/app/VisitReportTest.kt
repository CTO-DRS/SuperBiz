package com.superbiz.app

import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Visit
import com.superbiz.app.domain.algo.PartyGeo
import com.superbiz.app.domain.algo.VisitReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P12-b] اختبارات VisitReport — منطق نقي بلا أندرويد (JUnit4، بدون Robolectric).
 * الكيانات Visit/Party تُنشأ مباشرة كبيانات بسيطة، وnow ثابت في كل الاختبارات
 * فلا اعتماد على ساعة الحائط. المسافة الرياضية جدة↔الرياض مدى 830..870 كم
 * (نفس مرجعية PartyGeoTest — محسوبة مسبقاً بنفس الصيغة).
 */
class VisitReportTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L // لحظة ثابتة — لا ساعة حائط في الاختبارات

    private fun party(id: Long, name: String, lat: Double? = null, lng: Double? = null) =
        Party(id = id, name = name, lat = lat, lng = lng)

    private fun visit(partyId: Long, at: Long, lat: Double? = null, lng: Double? = null) =
        Visit(partyId = partyId, visitedAt = at, lat = lat, lng = lng)

    // ───────── daysSince ─────────

    @Test
    fun daysSince_sameDayIsZero() {
        assertEquals(0, VisitReport.daysSince(now, now))
        // ساعات داخل اليوم نفسه لا تُعدّ يوماً (قطع لأسفل)
        assertEquals(0, VisitReport.daysSince(now - 5 * 3_600_000L, now))
        assertEquals(0, VisitReport.daysSince(now - (day - 1), now))
    }

    @Test
    fun daysSince_twoAndHalfDaysFloorsToTwo() {
        assertEquals(2, VisitReport.daysSince(now - (2 * day + 12 * 3_600_000L), now))
        // بالضبط يومان = 2، وبالضبط 3 أيام = 3
        assertEquals(2, VisitReport.daysSince(now - 2 * day, now))
        assertEquals(3, VisitReport.daysSince(now - 3 * day, now))
    }

    @Test
    fun daysSince_futureTimestampNeverNegative() {
        assertEquals(0, VisitReport.daysSince(now + day, now))
        assertEquals(0, VisitReport.daysSince(now + 30 * day, now))
    }

    // ───────── summarize: الإجماليات ─────────

    @Test
    fun summarize_totalsAndPerPartyCounts() {
        val parties = listOf(party(1, "عميل أول"), party(2, "عميل ثان"))
        val visits = listOf(
            visit(1, now - day),
            visit(1, now - 3 * day),
            visit(2, now - 2 * day)
        )
        val d = VisitReport.summarize(visits, parties, now)
        assertEquals(3, d.totalVisits)
        assertEquals(2, d.rows.size)
        assertEquals(2, d.rows.first { it.partyId == 1L }.count)
        assertEquals(1, d.rows.first { it.partyId == 2L }.count)
    }

    @Test
    fun summarize_last30Boundary_excludes31Includes29() {
        val parties = listOf(party(1, "عميل"))
        // 29 يوماً ⇒ داخل النافذة، 31 يوماً ⇒ خارجها
        val d29 = VisitReport.summarize(listOf(visit(1, now - 29 * day)), parties, now)
        assertEquals(1, d29.last30)
        val d31 = VisitReport.summarize(listOf(visit(1, now - 31 * day)), parties, now)
        assertEquals(0, d31.last30)
        // بالضبط 30 يوماً: الحد شامل (توثيق السلوك المختار)
        val d30 = VisitReport.summarize(listOf(visit(1, now - 30 * day)), parties, now)
        assertEquals(1, d30.last30)
        // أقدم من 31 يوماً لا يسقط من الإجمالي الصادق (يسقط من last30 فقط)
        assertEquals(1, d31.totalVisits)
    }

    // ───────── summarize: الربط بالأطراف ─────────

    @Test
    fun summarize_joinSkipsDeletedParty() {
        // الطرف 99 محذوف (ليس في parties) — زياراته يتيمة
        val parties = listOf(party(1, "عميل حي"))
        val visits = listOf(
            visit(1, now - day),
            visit(99, now - 2 * day) // يتيمة
        )
        val d = VisitReport.summarize(visits, parties, now)
        assertEquals(1, d.rows.size)
        assertEquals(1L, d.rows[0].partyId)
        // العدّ الإجمالي صادق: اليتيمة سجل حقيقي في الدفتر فتُحتسب في المجاميع لا في الصفوف
        assertEquals(2, d.totalVisits)
        assertEquals(1, d.rows[0].count)
    }

    @Test
    fun summarize_rowsSortedByLastVisitDescending() {
        val parties = listOf(party(1, "قديم"), party(2, "حديث"))
        val visits = listOf(
            visit(1, now - 5 * day),   // آخر زيارة أقدم
            visit(2, now - day)        // آخر زيارة أحدث
        )
        val d = VisitReport.summarize(visits, parties, now)
        assertEquals(listOf(2L, 1L), d.rows.map { it.partyId })
    }

    @Test
    fun summarize_lastVisitFieldsComeFromMostRecentVisit() {
        val parties = listOf(party(1, "عميل", lat = 24.7136, lng = 46.6753))
        // الأقدم بإحداثيات، والأحدث بلا إحداثيات ⇒ آخر زيارة بلا موقع ⇒ لا مسافة
        val d1 = VisitReport.summarize(
            listOf(
                visit(1, now - 5 * day, lat = 21.4858, lng = 39.1925),
                visit(1, now - day) // أحدث بلا موقع
            ),
            parties, now
        )
        val row1 = d1.rows.single()
        assertEquals(now - day, row1.lastVisitAt)
        assertNull(row1.lastLat)
        assertNull(row1.distanceMeters)
        // والأحدث بإحداثيات ⇒ المسافة تُحسب منها
        val d2 = VisitReport.summarize(
            listOf(
                visit(1, now - 5 * day),
                visit(1, now - day, lat = 21.4858, lng = 39.1925)
            ),
            parties, now
        )
        val row2 = d2.rows.single()
        assertEquals(now - day, row2.lastVisitAt)
        assertTrue(row2.distanceMeters!! in 830_000.0..870_000.0)
    }

    // ───────── المسافة: تُحسب فقط بإحداثيات صالحة مكتملة ─────────

    @Test
    fun distance_computedWhenBothPairsValid() {
        val parties = listOf(party(1, "الرياض", lat = 24.7136, lng = 46.6753))
        val d = VisitReport.summarize(
            listOf(visit(1, now - day, lat = 21.4858, lng = 39.1925)), // جدة
            parties, now
        )
        val dist = d.rows.single().distanceMeters
        assertTrue("المسافة $dist خارج المدى المتوقع", dist!! in 830_000.0..870_000.0)
        // النقطة نفسها ⇒ صفر تماماً
        val dSame = VisitReport.summarize(
            listOf(visit(1, now - day, lat = 24.7136, lng = 46.6753)),
            parties, now
        )
        assertEquals(0.0, dSame.rows.single().distanceMeters!!, 1e-9)
    }

    @Test
    fun distance_nullWhenAnyPairMissingOrInvalid() {
        val visits = listOf(visit(1, now - day, lat = 24.7, lng = 46.6))
        // طرف بلا موقع محفوظ ⇒ null
        val d1 = VisitReport.summarize(visits, listOf(party(1, "بلا موقع")), now)
        assertNull(d1.rows.single().distanceMeters)
        // آخر زيارة بلا موقع وطرف بموقع ⇒ null (لا يُستخدم موقع أقدم)
        val d2 = VisitReport.summarize(
            listOf(visit(1, now - day)), listOf(party(1, "عميل", 24.7, 46.6)), now
        )
        assertNull(d2.rows.single().distanceMeters)
        // إحداثيات غير صالحة (خارج المدى) ⇒ null لا استثناء ولا قيمة زائفة
        val d3 = VisitReport.summarize(
            listOf(visit(1, now - day, lat = 999.0, lng = 46.6)),
            listOf(party(1, "عميل", 24.7, 46.6)), now
        )
        assertNull(d3.rows.single().distanceMeters)
        val d4 = VisitReport.summarize(
            listOf(visit(1, now - day, lat = 24.7, lng = 46.6)),
            listOf(party(1, "عميل", lat = Double.NaN, lng = 46.6)), now
        )
        assertNull(d4.rows.single().distanceMeters)
    }

    // ───────── المتأخرة عن الزيارة (عتبة 14 يوماً) ─────────

    @Test
    fun overdue_thresholdIsStrictlyMoreThan14Days() {
        val parties = listOf(party(1, "متأخر"), party(2, "على الحد"), party(3, "حديث"))
        val visits = listOf(
            visit(1, now - 15 * day), // 15 > 14 ⇒ متأخر
            visit(2, now - 14 * day), // 14 > 14 خاطئ ⇒ ليس متأخراً
            visit(3, now - day)
        )
        val d = VisitReport.summarize(visits, parties, now, overdueDays = 14)
        assertEquals(listOf(1L), d.overdue.map { it.partyId })
        assertEquals(3, d.rows.size)
        // الافتراضي 14 بلا تمرير صريح
        val dDefault = VisitReport.summarize(visits, parties, now)
        assertEquals(listOf(1L), dDefault.overdue.map { it.partyId })
        assertEquals(14, dDefault.overdueDays)
    }

    // ───────── مدخلات فارغة ─────────

    @Test
    fun summarize_emptyInputsGiveHonestZeros() {
        val d = VisitReport.summarize(emptyList(), emptyList(), now)
        assertEquals(0, d.totalVisits)
        assertEquals(0, d.last30)
        assertTrue(d.rows.isEmpty())
        assertTrue(d.overdue.isEmpty())
        assertEquals(now, d.now)
        assertEquals(14, d.overdueDays)
    }

    @Test
    fun summarize_visitsWithoutAnyParties_allOrphansSkipped() {
        val d = VisitReport.summarize(listOf(visit(7, now - day)), emptyList(), now)
        // العدّ الإجمالي يظل صادقاً والصفوف فارغة
        assertEquals(1, d.totalVisits)
        assertTrue(d.rows.isEmpty())
        assertTrue(d.overdue.isEmpty())
    }

    // ───────── أداة الواجهة ─────────

    @Test
    fun relativeDays_isIdentityPassthrough() {
        assertEquals(0, VisitReport.relativeDays(0))
        assertEquals(14, VisitReport.relativeDays(14))
    }
}
