package com.superbiz.app.domain.algo

import com.superbiz.app.data.db.Party

/**
 * [P13-a] صف نتيجة ترتيب المسافة — يلفّ الطرف الأصلي بلا أي نسخ (مرجع للكائن نفسه،
 * فأي قراءة بعده (الاسم/الهاتف/البطاقة) تعتمد على الطرف الأصلي مباشرة):
 * - distanceMeters: غير null فقط حين أمكن حساب المسافة بصدق (موقعي صالح + موقع الطرف صالح).
 * - hasLocation: هل للطرف إحداثيات صالحة بصرف النظر عن صلاحية موقعي.
 */
data class PartyDistance(
    val party: Party,
    val distanceMeters: Double?,
    val hasLocation: Boolean
)

/**
 * [P13-a] ترتيب الأطراف حسب القرب من موقعي («الأقرب أولاً») — منطق نقي بلا أي
 * اعتماد أندرويد (قابل للاختبار على JVM مباشرة عبر RouteOrderTest)، يغذي رقاقة
 * «الأقرب أولاً» في شاشة المفضّلات.
 *
 * القواعد:
 * - موقعي غير صالح (null أو لا يجتاز PartyGeo.isValid: NaN/لانهائي/خارج المدى) ⇒
 *   الترتيب الأصلي كما هو، وكل المسافات null (صدق: لا مرجع فلا مسافة تُعروض)،
 *   وhasLocation تعكس إحداثيات كل طرف كما هي.
 * - الأطراف ذات الإحداثيات الصالحة تُرتَّب تصاعدياً على المسافة (هافرساين عبر
 *   PartyGeo.haversineMeters)، والترتيب مستقر: المسافة نفسها تحافظ على الترتيب
 *   النسبي الأصلي (sortBy على كائنات = TimSort مستقر).
 * - الأطراف بلا إحداثيات صالحة في الذيل بترتيبها الأصلي النسبي، ومسافاتها null.
 * - قائمة فارغة ⇒ قائمة فارغة.
 */
fun sortPartiesByDistance(parties: List<Party>, myLat: Double?, myLng: Double?): List<PartyDistance> {
    if (parties.isEmpty()) return emptyList()

    // موقعي غير صالح ⇒ الترتيب الأصلي ملفوفاً بلا مسافات (لا استثناء ولا قيمة زائفة)
    if (!PartyGeo.isValid(myLat, myLng)) {
        return parties.map { p -> PartyDistance(p, null, PartyGeo.isValid(p.lat, p.lng)) }
    }

    val located = ArrayList<PartyDistance>(parties.size)
    val missing = ArrayList<PartyDistance>()
    for (p in parties) {
        if (PartyGeo.isValid(p.lat, p.lng)) {
            located += PartyDistance(p, PartyGeo.haversineMeters(myLat!!, myLng!!, p.lat!!, p.lng!!), true)
        } else {
            missing += PartyDistance(p, null, false)
        }
    }
    located.sortBy { it.distanceMeters } // sortBy مستقر: المسافة نفسها تحافظ على الترتيب النسبي الأصلي
    return located + missing
}
