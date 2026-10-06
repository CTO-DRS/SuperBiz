package com.superbiz.app.domain

/**
 * [W1] محرك استحقاق Pro النقي — الموجة 1 من الخارطة العالمية «الإطلاق التجاري»:
 *
 * صندوق منطق الفريميوم الوحيد (بلا أي تبعية أندرويد) — يفصل قرار الاستحقاق عن
 * تفاصيل Play Billing حتى يبقى قابلاً للاختبار حرفاً-بحرف ولو استبدل مزوّد
 * الفواتير لاحقاً. النموذج: **شراء لمرة واحدة بلا خادم** — Google Play هو مصدر
 * الحقيقة، والتطبيق يخزّن نسخة محلية سريعة (ProStore) تُصحّح عند كل اتصال.
 *
 * العقود الحاسمة:
 * - الافتراض آمن دائماً: أي حالة غير مفهومة = **مجاني** (لا استحقاق بالشك).
 * - الاستحقاق يقتضي purchaseState == PURCHASED فقط — PENDING (بطاقة قيد
 *   التأكيد عند الدفع المستدفّع) **ليس** استحقاقاً بعد.
 * - الإقرار (acknowledge) واجب شرط بقاء الاستحقاق عبر 3 أيام فقط تقنياً،
 *   لكننا نقرّ فوراً عند كل رصد — والشراء غير المُقرّ يظل مستحقاً (عقد Play
 *   الرسمي: acknowledge لا يلغي الاستحقاق، بل يُعيده بعد مهلة).
 * - السجل المحلي (ProStore) **ليس** مصدر حقيقة — إن خالف Play يُصحّح هبوطاً
 *   (Play يقول مجاني ⇒ مجاني فوراً) ولا يُرقّى به بخلاف رصد فعلي من Play.
 */
object ProEntitlement {

    /** معرّف المنتج الوحيد في Play Console (شراء لمرة واحدة، INAPP) */
    const val SKU_PRO = "superbiz_pro"

    /** حالة استحقاق كما يراها التطبيق */
    enum class ProState { UNKNOWN, FREE, PRO }

    /** حالة شراء Play كما ترد في PurchasesUpdatedListener / queryPurchasesAsync */
    enum class PlayPurchaseState { UNSPECIFIED, PURCHASED, PENDING, REFUNDED }

    /**
     * الأبواب المدفوعة — قائمة مركزية واحدة. كل ميزة Pro مستقبلية تُسجّل هنا
     * قبل أي استعمال في الواجهة، والحارس الوحيد هو [isLocked].
     * الموجة 1: لوحة المؤشرات (KPIs) — الباب الأول.
     */
    enum class ProFeature { KPI_BOARD }

    /** خريطة حالة Play الخام إلى استحقاق — الافتراض الآمن = مجاني */
    fun stateOf(playState: PlayPurchaseState): ProState = when (playState) {
        PlayPurchaseState.PURCHASED -> ProState.PRO
        PlayPurchaseState.PENDING,
        PlayPurchaseState.REFUNDED,
        PlayPurchaseState.UNSPECIFIED -> ProState.FREE
    }

    /**
     * قرار الشراء الوارد من [android.billingclient.api.PurchasesUpdatedListener]:
     * تقبل قائمة مشتريات واحدة فقط إن كانت تحوي شراءً فعلياً مفعّلاً لـSKU
     * الخاص بنا — أي SKU آخر يُتجاهل بلا خطأ (تسامح مع مشتريات مستقبلية).
     */
    fun entitlementFromPurchases(hasProPurchased: Boolean): ProState =
        if (hasProPurchased) ProState.PRO else ProState.FREE

    /**
     * هل الباب مقفل؟ — الدالة الوحيدة التي تستدعيها الواجهة.
     * UNKNOWN (مثلاً قبل أول اتصال) = مفتوح مؤقتاً بقراءة السجل المحلي يقررها
     * المستدعي؛ هنا القرار صريح: PRO ⇒ مفتوح، غير ذلك ⇒ مقفل.
     */
    fun isLocked(feature: ProFeature, state: ProState, localCacheSaysPro: Boolean): Boolean =
        when (state) {
            ProState.PRO -> false
            ProState.FREE -> true
            ProState.UNKNOWN -> !localCacheSaysPro
        }

    /** هل يجب إقرار هذا الشراء؟ (state + isAcknowledged) */
    fun shouldAcknowledge(playState: PlayPurchaseState, acknowledged: Boolean): Boolean =
        playState == PlayPurchaseState.PURCHASED && !acknowledged

    /**
     * تأشير السجل المحلي: نكتب PRO فقط من رصد Play فعلي (عقد الصعود)، ونكتب
     * FREE من رصد Play فعلي أو عند طلب المستخدم تصفير الحالة — ولا نكتب أبداً
     * من حالة UNKNOWN (الاتصال لم يحسم بعد).
     */
    fun localWriteAllowed(fromPlayState: ProState): Boolean = fromPlayState != ProState.UNKNOWN
}
