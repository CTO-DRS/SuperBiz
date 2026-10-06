package com.superbiz.app.domain.algo

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [P11-a] هندسة الموقع الجغرافي للأطراف المفضّلة — كائن نقي بلا أي اعتماد أندرويد
 * (قابل للاختبار على JVM مباشرة عبر PartyGeoTest).
 *
 * يخدم بطاقة الموقع في FavoritesScreen: التحقق من صحة الإحداثيات، تنسيقها للعرض،
 * حساب المسافة (هافرساين)، بناء رابط geo: للخرائط، وتسمية المسافات القريبة.
 * كل الأرقام في النصوص غربية (Locale.US) اتساقاً مع Money.num في المشروع.
 */
object PartyGeo {

    /** نصف قطر الأرض بالمتر (متوسط IUGG — دقة تكفي لعرض «كم تبعد» في واجهة عمل) */
    private const val EARTH_RADIUS_M = 6371008.8

    /**
     * [P11-a] صلاحية زوج إحداثيات قادم من GPS أو إدخال: غير صفري القيمتان (غير null)،
     * غير NaN وغير لانهائية، |خط العرض| ≤ 90 و|خط الطول| ≤ 180.
     */
    fun isValid(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (lat.isNaN() || lng.isNaN() || lat.isInfinite() || lng.isInfinite()) return false
        return abs(lat) <= 90.0 && abs(lng) <= 180.0
    }

    /**
     * [P11-a] تنسيق الإحداثيات للعرض: "24.7136°, 46.6753°" — 4 منازل عشرية بأرقام غربية،
     * مع تطبيع علامة السالب للصفر المعنبر (-0.0000 تصير 0.0000) كي لا يظهر «-» بلا معنى.
     */
    fun formatLatLng(lat: Double, lng: Double): String = "${fmt(lat)}°, ${fmt(lng)}°"

    /**
     * [P11-a] المسافة بين نقطتين بالأمتار (هافرساين على كرة بنصف قطر EARTH_RADIUS_M).
     * النقطة نفسها تُرجع 0.0 تماماً.
     */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /**
     * [P11-a] رابط فتح الخرائط لنقطة (وعلامة اختيارية بالاسم):
     * "geo:24.7136,46.6753?q=24.7136,46.6753(EncodedLabel)" — صيغة q=(label)
     * تجعل الخرائط تُظهر دبوساً معنوناً باسم الطرف. التسمية تُرمَّز URI (URLEncoder UTF-8
     * نقئي ولا يحتاج أندرويد)، وإذا كانت null/فارغة يُرجع الرابط بلا قوسين إطلاقاً.
     */
    fun geoUri(lat: Double, lng: Double, label: String?): String {
        val coords = "${fmt(lat)},${fmt(lng)}"
        return if (label.isNullOrBlank()) {
            "geo:$coords?q=$coords"
        } else {
            "geo:$coords?q=$coords(${URLEncoder.encode(label, "UTF-8")})"
        }
    }

    /**
     * [P11-a] تسمية مسافة للعرض بأرقام غربية: أقل من 1000م → "650 م"،
     * وما فوق → "1.5 كم" (منزلة عشرية واحدة).
     */
    fun deltaLabel(meters: Double): String =
        if (meters < 1000.0) "${meters.toInt()} م"
        else String.format(Locale.US, "%.1f", meters / 1000.0) + " كم"

    /** [P11-a] 4 منازل عشرية بأرقام Locale.US مع إزالة سالب الصفر المعنبر */
    private fun fmt(v: Double): String {
        val s = String.format(Locale.US, "%.4f", v)
        return if (s == "-0.0000") s.substring(1) else s
    }
}
