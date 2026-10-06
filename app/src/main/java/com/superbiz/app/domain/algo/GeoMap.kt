package com.superbiz.app.domain.algo

import kotlin.math.cos
import kotlin.math.max

/**
 * [P16-c] هندسة «الخريطة الشاملة» للمفضّلات — كائن نقي بلا أي اعتماد أندرويد
 * (قابل للاختبار على JVM مباشرة عبر GeoMapTest)، يغذي وضع الخريطة في FavoritesScreen:
 * إسقاط كل الأطراف المفضّلة ذات الإحداثيات على مستطيل مُطبَّع [0,1] برسم Canvas،
 * وإيجاد أقرب نقطة لموقع ما (للاستخدام المستقبلي: ترتيب حسب الموقع).
 *
 * العقد مع المتصل:
 * - صلاحية الإحداثيات (نطاق ‎|lat|≤90 و|lng|≤180 وغير null) مسؤولية المتصل عبر
 *   PartyGeo.isValid — كما في عقد sortPartiesByDistance. لا يُعاد فحص النطاق هنا
 *   تفادياً لمنطقين متوازيين لصحة واحدة، والقيم غير المنتهية (NaN/∞) تُستبعد
 *   استباقاً دفاعياً فقط لأنها تفسد التخطيط صمتاً.
 * - الدوال حتمية بالكامل: بلا عشوائية، بلا حالة، ولا استثناء يُلقى للخارج.
 * - المسافات عبر PartyGeo.haversineMeters نفسها التي يستخدمها RouteOrder في
 *   ترتيب «الأقرب أولاً» — مصدر واحد للحقيقة فلا يمكن أن تختلف مسافة الخريطة
 *   عن مسافة القائمة (اختبار GeoMapTest يقفل هذا التوافق).
 */

/** نقطة جغرافية جاهزة للرسم — id يعود إلى معرّف الطرف الأصلي (يفترض فريداً) */
data class GeoPoint(val id: Long, val lat: Double, val lng: Double, val label: String)

/** موضع مُطبَّع داخل مساحة الوحدة [0,1] — y يتزايد نحو الأسفل (محاور الشاشة) */
data class OffsetNorm(val x: Float, val y: Float)

/**
 * ناتج الإسقاط: مواضع النقاط مفتاحاً بمعرّف الطرف، وامتداد الصندوق المحيط
 * (قطره بالأمتار — تعرضه الواجهة كـ«النطاق حوالي …»).
 * spanMeters = null في الحالات المنحلّة (قائمة فارغة/نقطة واحدة/كل النقاط
 * متطابقة) حيث لا معنى منطقي لمقياس امتداد.
 */
data class MapLayout(
    val positions: Map<Long, OffsetNorm>,
    val spanMeters: Double?
)

object GeoMap {

    /**
     * [P16-c] إسقاط إيزومتري أسطواني متساوي المسافات (Equirectangular) بانكماش
     * خط الطول بـ cos(lat0) حيث lat0 مركز مجموعتِ النقاط — بسيط وحتمي وبلا أي
     * مكتبة خارجية، ودقّته تكفي لخريطة نظرة عامة على مدينة/منطقة.
     *
     * القواعد:
     * - الموضعان يُطبَّعان إلى [padding, 1-padding] مع الحفاظ على نسبة الأبعاد:
     *   المحور الأكبر امتداداً يملأ المدى المتاح كاملاً، والآخر يتمركز في الوسط
     *   (لا تمديد/تشويه).
     * - الشمال أعلى: خط العرض الأكبر ⇒ y أصغر (ي انعكس عمداً لأن محور y في
     *   الشاشة يتزايد نحو الأسفل)، والشرق يمين: خط الطول الأكبر ⇒ x أكبر.
     * - الحالات المنحلّة: لا نقاط ⇒ positions فارغة؛ نقطة واحدة أو كل النقاط
     *   متطابقة ⇒ الموضع مركز (0.5, 0.5) وspanMeters = null. محور منحلّ
     *   منفرد (امتداده صفر والأكبر امتداداً) ⇒ يتمركز عند 0.5.
     * - padding يُقيَّد دفاعياً إلى [0, 0.49] كي لا تنسف قيمة شاذة التخطيط.
     * - خط الطول عبر خط 180± لا يُعالج خصيصاً (مفضّلات منطقة واحدة عملياً) —
     *   لو امتدت النقاط عبر الخط الدولي ستظهر مُطبَّعة على كامل العرض.
     */
    fun project(points: List<GeoPoint>, padding: Float = 0.08f): MapLayout {
        // استباق دفاعي فقط للقيم غير المنتهية (العقد أعلاه) — القائمة الفارغة ⇒ تخطيط فارغ
        val pts = points.filter { it.lat.isFinite() && it.lng.isFinite() }
        if (pts.isEmpty()) return MapLayout(emptyMap(), null)

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        for (p in pts) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }

        // امتدادا المحورين الخامَين: x = rad(lng)·cos(lat0)، y = rad(lat)
        val lat0 = (minLat + maxLat) / 2.0
        val k = cos(Math.toRadians(lat0))
        val minX = Math.toRadians(minLng) * k
        val maxX = Math.toRadians(maxLng) * k
        val minY = Math.toRadians(minLat)
        val maxY = Math.toRadians(maxLat)
        val spanX = maxX - minX
        val spanY = maxY - minY

        // كل النقاط متطابقة (أو نقطة واحدة) ⇒ المركز وبلا امتداد
        if (spanX <= 0.0 && spanY <= 0.0) {
            return MapLayout(pts.associate { it.id to OffsetNorm(0.5f, 0.5f) }, null)
        }

        // القطر الجغرافي للصندوق المحيط — هافرساين الزاويتين المتقابلتين (نفس دالة RouteOrder)
        val diagonal = PartyGeo.haversineMeters(minLat, minLng, maxLat, maxLng)
        val span = if (diagonal.isFinite() && diagonal > 0.0) diagonal else null

        val pad = padding.coerceIn(0f, 0.49f)
        val usable = (1f - 2f * pad).coerceAtLeast(0f)
        val scale = usable / max(spanX, spanY).toFloat()
        // تمركز المحور الأصغر امتداداً داخل المدى المتاح (الحفاظ على نسبة الأبعاد)
        val offX = (usable - (spanX * scale).toFloat()) / 2f
        val offY = (usable - (spanY * scale).toFloat()) / 2f

        val positions = HashMap<Long, OffsetNorm>(pts.size)
        for (p in pts) {
            val fx = if (spanX <= 0.0) 0.5f
            else (pad + offX + ((Math.toRadians(p.lng) * k - minX) * scale).toFloat())
                .coerceIn(pad, 1f - pad)
            // انعكاس y: الشمال (خط العرض الأكبر) أعلى أي y أصغر
            val fy = if (spanY <= 0.0) 0.5f
            else 1f - (pad + offY + ((Math.toRadians(p.lat) - minY) * scale).toFloat())
                .coerceIn(pad, 1f - pad)
            positions[p.id] = OffsetNorm(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
        }
        return MapLayout(positions, span)
    }

    /**
     * [P16-c] أقرب نقطة لموقع (lat, lng) — هافرساين عبر PartyGeo.haversineMeters
     * (الدالة ذاتها التي يرتب بها RouteOrder). يتجاوز الحد maxMeters ⇒ null
     * (منع التقاط نقطة بعيدة بمسكة خاطئة). التعادل يرجع أول نقطة في القائمة.
     * قائمة فارغة أو مسافات كلها NaN (مدخلات غير منطقية) ⇒ null بلا استثناء.
     * ملاحظة: نسخة الأمتار هذه للاستخدام المستقبلي (ترتيب حسب موقع اللحظة)؛
     * الالتقاط في واجهة الخريطة يُقاس بالبكسل مباشرة فوق مواضع الإسقاط.
     */
    fun nearest(
        points: List<GeoPoint>,
        lat: Double,
        lng: Double,
        maxMeters: Double = 2000.0
    ): GeoPoint? {
        var best: GeoPoint? = null
        var bestMeters = Double.MAX_VALUE
        for (p in points) {
            val d = PartyGeo.haversineMeters(lat, lng, p.lat, p.lng)
            if (d < bestMeters) { // «<» صارمة: التعادل يبقي الأول
                bestMeters = d
                best = p
            }
        }
        return if (best != null && bestMeters <= maxMeters) best else null
    }
}
