package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W1] اختبارات محرك استحقاق Pro النقي — الموجة 1 «الإطلاق التجاري».
 *
 * العقود الحاسمة المغطاة:
 * - الافتراض الآمن: أي حالة غير مفهومة = مجاني (لا استحقاق بالشك).
 * - PENDING ليس استحقاقاً (الدفع المستدفّع لم يكتمل).
 * - الإقرار واجب للشراء الفعلي غير المُقرّ فقط.
 * - السجل المحلي لا يُكتب من حالة UNKNOWN.
 */
class ProEntitlementTest {

    // ══ خريطة حالة Play → استحقاق ══

    @Test
    fun purchased_means_pro() {
        assertEquals(
            ProEntitlement.ProState.PRO,
            ProEntitlement.stateOf(ProEntitlement.PlayPurchaseState.PURCHASED)
        )
    }

    @Test
    fun pending_is_not_entitlement() {
        // عقد الدفع المستدفّع: بطاقة قيد التأكيد = لا باب مفتوح بعد
        assertEquals(
            ProEntitlement.ProState.FREE,
            ProEntitlement.stateOf(ProEntitlement.PlayPurchaseState.PENDING)
        )
    }

    @Test
    fun refunded_and_unspecified_are_free() {
        assertEquals(
            ProEntitlement.ProState.FREE,
            ProEntitlement.stateOf(ProEntitlement.PlayPurchaseState.REFUNDED)
        )
        assertEquals(
            ProEntitlement.ProState.FREE,
            ProEntitlement.stateOf(ProEntitlement.PlayPurchaseState.UNSPECIFIED)
        )
    }

    // ══ قرار الشراء الوارد ══

    @Test
    fun purchases_list_without_pro_is_free() {
        assertEquals(
            ProEntitlement.ProState.FREE,
            ProEntitlement.entitlementFromPurchases(hasProPurchased = false)
        )
    }

    @Test
    fun purchases_list_with_pro_is_pro() {
        assertEquals(
            ProEntitlement.ProState.PRO,
            ProEntitlement.entitlementFromPurchases(hasProPurchased = true)
        )
    }

    // ══ بوابة الأبواب (الدالة الوحيدة للواجهة) ══

    @Test
    fun locked_when_free_even_if_local_cache_says_pro() {
        // Play هو المصدر: FREE صريح يغلق الباب مهما قال السجل المحلي
        assertTrue(
            ProEntitlement.isLocked(
                ProEntitlement.ProFeature.KPI_BOARD,
                ProEntitlement.ProState.FREE,
                localCacheSaysPro = true
            )
        )
    }

    @Test
    fun unlocked_when_pro_regardless_of_cache() {
        assertFalse(
            ProEntitlement.isLocked(
                ProEntitlement.ProFeature.KPI_BOARD,
                ProEntitlement.ProState.PRO,
                localCacheSaysPro = false
            )
        )
    }

    @Test
    fun unknown_state_trusts_local_cache_only_if_pro() {
        // قبل أول اتصال: السجل المحلي يقرر — PRO مفتوح، غير ذلك مقفل
        assertFalse(
            ProEntitlement.isLocked(
                ProEntitlement.ProFeature.KPI_BOARD,
                ProEntitlement.ProState.UNKNOWN,
                localCacheSaysPro = true
            )
        )
        assertTrue(
            ProEntitlement.isLocked(
                ProEntitlement.ProFeature.KPI_BOARD,
                ProEntitlement.ProState.UNKNOWN,
                localCacheSaysPro = false
            )
        )
    }

    // ══ الإقرار (acknowledge) ══

    @Test
    fun acknowledge_required_for_purchased_unacknowledged() {
        assertTrue(
            ProEntitlement.shouldAcknowledge(
                ProEntitlement.PlayPurchaseState.PURCHASED, acknowledged = false
            )
        )
    }

    @Test
    fun acknowledge_not_required_when_already_acknowledged_or_not_purchased() {
        assertFalse(
            ProEntitlement.shouldAcknowledge(
                ProEntitlement.PlayPurchaseState.PURCHASED, acknowledged = true
            )
        )
        assertFalse(
            ProEntitlement.shouldAcknowledge(
                ProEntitlement.PlayPurchaseState.PENDING, acknowledged = false
            )
        )
    }

    // ══ كتابة السجل المحلي ══

    @Test
    fun local_write_allowed_from_decided_states_only() {
        assertTrue(ProEntitlement.localWriteAllowed(ProEntitlement.ProState.PRO))
        assertTrue(ProEntitlement.localWriteAllowed(ProEntitlement.ProState.FREE))
        // UNKNOWN = الاتصال لم يحسم بعد — كتابته قد تمحو استحقاقاً حقيقياً
        assertFalse(ProEntitlement.localWriteAllowed(ProEntitlement.ProState.UNKNOWN))
    }

    // ══ ثوابت المنتج ══

    @Test
    fun sku_is_stable_and_lowercase_inapp_id() {
        // عقد Play Console: معرّف المنتج يجب أن يبقى ثابتاً عبر الإصدارات
        assertEquals("superbiz_pro", ProEntitlement.SKU_PRO)
        assertEquals(ProEntitlement.SKU_PRO, ProEntitlement.SKU_PRO.lowercase())
    }
}
