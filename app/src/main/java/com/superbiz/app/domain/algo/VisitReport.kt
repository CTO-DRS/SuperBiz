package com.superbiz.app.domain.algo

import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Visit

/**
 * [P12-b] منطق تقرير زيارات العملاء — كائن نقي بلا أي استيراد أندرويد
 * (قابل للاختبار على JVM مباشرة عبر VisitReportTest).
 *
 * يُغذّي قسم «تقرير زيارات العملاء» (VisitsSection) في شاشة المفضّلات:
 * يجمع الزيارات بأطرافها (ربط بالمفتاح partyId = id الطرف)، ويحسب آخر زيارة
 * لكل طرف وعددها، والمسافة بين آخر موقع زيارة وموقع الطرف المحفوظ (هافرساين)،
 * والأطراف المتأخرة عن الزيارة أكثر من N يوماً.
 *
 * لماذا يبقى قابلاً للاختبار على JVM؟ لأن summarize يتعامل مع كيانات
 * com.superbiz.app.data.db (Visit / Party) وهي data classes بسيطة بلا أي
 * تبعية أندرويد في توقيعاتها أو حقولها (تعليقات Room التوضيحية لا تُنَفَّذ
 * في وقت التشغيل)، ولا يلمس هذا الملف Room ولا Context إطلاقاً.
 *
 * صدق الجمع: الزيارات يتيمة (طرفها محذوف) تُحتسب في الإجماليات (totalVisits/last30)
 * لأنها سجلات فعلية في الدفتر، لكنها لا تُنتج صفاً في الجدول (rows) — الأطراف
 * المحذوفة تُتخطى عند الربط. جدول visits بلا مفاتيح أجنبية عمداً (توثيق القرار
 * عند كيان Visit)، فالصفوف اليتيمة بلا ضرر على أي استعلام.
 */
object VisitReport {

    /** يوم كامل بالمللي ثانية — وحدة حساب last30 وdaysSince */
    private const val DAY_MS = 86_400_000L

    /** نافذة «آخر 30 يوماً» للملخص */
    private const val LAST30_DAYS = 30L

    /**
     * صف تقرير لطرف واحد لديه زيارة واحدة على الأقل.
     * lastLat/lastLng إحداثيات آخر زيارة (الأحدث زمنياً)، وpartyLat/partyLng
     * الموقع المحفوظ للطرف — المسافة تُحسب فقط حين تكتمل الرباعية وتكون صالحة.
     */
    data class Row(
        val partyId: Long,
        val name: String,
        val count: Int,
        val lastVisitAt: Long,
        val lastLat: Double?,
        val lastLng: Double?,
        val partyLat: Double?,
        val partyLng: Double?
    ) {
        /**
         * المسافة بين آخر موقع زيارة وموقع الطرف المحفوظ بالمتر —
         * null حين لا يمكن الحساب بصدق: أي زوج ناقص أو غير صالح
         * (PartyGeo.isValid) يعني «لا مسافة معروفة» لا «صفر مسافة».
         */
        val distanceMeters: Double?
            get() {
                val pLat = partyLat
                val pLng = partyLng
                val vLat = lastLat
                val vLng = lastLng
                if (pLat == null || pLng == null || vLat == null || vLng == null) return null
                if (!PartyGeo.isValid(pLat, pLng) || !PartyGeo.isValid(vLat, vLng)) return null
                return PartyGeo.haversineMeters(pLat, pLng, vLat, vLng)
            }
    }

    /**
     * خلاصة التقرير: الإجماليات + صفوف الأطراف مرتبة تنازلياً بآخر زيارة + المتأخرة.
     * totalVisits: عدد سجلات الزيارات كلها (شاملة اليتيمة — عدّ صادق للدفتر).
     * last30: الزيارات خلال آخر 30 يوماً حتى now (زيارة عمرها 29 يوماً تُحتسب،
     * و31 يوماً لا تُحتسب، و30 يوماً بالضبط تُحتسب — الحد شامل).
     * overdueDays: عتبة التأخير المطبقة (افتراضها 14 يوماً).
     */
    data class Data(
        val totalVisits: Int,
        val last30: Int,
        val rows: List<Row>,
        val overdue: List<Row>,
        val now: Long,
        val overdueDays: Int
    )

    /**
     * بناء التقرير من سجل الزيارات وقائمة الأطراف الحية:
     * - لا يعتمد على ترتيب المدخلات (يرتب داخلياً دائماً).
     * - rows: أطراف لديها زيارة واحدة فأكثر (زيارة على الأقل)، مرتبة بآخر زيارة تنازلياً.
     * - طرف محذوف (زياراته بلا مقابل في parties) يُتخطى من rows لا يُرمى خطأً.
     * - overdue: الصفوف التي مضى على آخر زيارتها أكثر من overdueDays يوماً.
     */
    fun summarize(visits: List<Visit>, parties: List<Party>, now: Long, overdueDays: Int = 14): Data {
        val visitsByParty = visits.groupBy { it.partyId }
        val partiesById = parties.associateBy { it.id }

        val rows = visitsByParty.mapNotNull { (partyId, partyVisits) ->
            val party = partiesById[partyId] ?: return@mapNotNull null // طرف محذوف → تخطٍّ صادق
            val newest = partyVisits.maxByOrNull { it.visitedAt } ?: return@mapNotNull null
            Row(
                partyId = partyId,
                name = party.name,
                count = partyVisits.size,
                lastVisitAt = newest.visitedAt,
                lastLat = newest.lat,
                lastLng = newest.lng,
                partyLat = party.lat,
                partyLng = party.lng
            )
        }.sortedByDescending { it.lastVisitAt }

        val windowStart = now - LAST30_DAYS * DAY_MS
        return Data(
            totalVisits = visits.size,
            last30 = visits.count { it.visitedAt >= windowStart },
            rows = rows,
            overdue = rows.filter { daysSince(it.lastVisitAt, now) > overdueDays },
            now = now,
            overdueDays = overdueDays
        )
    }

    /**
     * عدد الأيام الكاملة منذ الطابع ts حتى now: floor((now - ts) / 86400000)
     * ولا يكون سالباً أبداً — طابع مستقبلي (أو نفس اللحظة) يعني اليوم ⇒ 0.
     * (2.5 يوم مضت ⇒ 2 — الأيام الجزئية تُقطَّع لأسفل بلا تضخيم.)
     */
    fun daysSince(ts: Long, now: Long): Int =
        ((now - ts) / DAY_MS).coerceAtLeast(0L).toInt()

    /**
     * [P12-b] تمرير صريح لعدد الأيام إلى طبقة الواجهة (اختيار «اليوم/أمس/قبل N يوم»)
     * — هوية حالياً؛ تُبقى لوضوح قصد الاستدعاء في الواجهة وفصل المنطق عن النص.
     */
    fun relativeDays(days: Int): Int = days
}
