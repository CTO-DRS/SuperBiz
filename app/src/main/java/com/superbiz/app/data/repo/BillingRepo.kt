package com.superbiz.app.data.repo

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.superbiz.app.domain.ProEntitlement
import kotlinx.coroutines.launch

/**
 * [W1] متحكم Play Billing v7 — فريميوم Pro **بلا خادم**:
 *
 * غلاف رقيق حول BillingClient يفعل الثلاثة المطلوبة فقط:
 * 1) اتصال + رصد المشتريات (queryPurchasesAsync) وتصحيح السجل المحلي عبر
 *    محرك [ProEntitlement] النقي (القرار كله هناك — هنا مجرد لصق).
 * 2) شراء لمرة واحدة (launchBillingFlow) من نافذة نشطة حقيقية.
 * 3) إقرار فوري (acknowledge) لكل شراء مُرصد غير مُقرّ — واجب Play.
 *
 * قرارات موثقة:
 * - INAPP (شراء لمرة واحدة) وليس SUBS — أبسط لمالك متجر صغير ولا يشترط خادماً
 *   للاستهلاك، ويلتزم بند الخارطة «بلا خادم» حرفياً.
 * - enablePendingPurchases بعقد الدفع المستدفّع (منتجات لمرة واحدة إلزامية
 *   في v7+ للحالات النقدية) — حالة PENDING ليست استحقاقاً (عقد المحرك).
 * - فشل الاتصال لا يرمي ولا يعلّق: حالة READY=false تُنشر عبر [billingReady]
 *   والواجهة تعرض رسالة صادقة (متجر Play غير متاح الآن) — التطبيق نفسه
 *   يعمل دون اتصال كما هو وعده.
 */
class BillingRepo(
    context: Context,
    private val proStore: ProStore,
    private val scope: kotlinx.coroutines.CoroutineScope
) : PurchasesUpdatedListener {

    /** حالة الاتصال المعلنة للواجهة — true فقط بعد onBillingSetupFinished OK */
    @Volatile
    var billingReady: Boolean = false
        private set

    /** سعر المنتج المُنسّق من Play (مثل «ر.س.‏ 49.99») — null قبل الرصد */
    @Volatile
    var formattedPrice: String? = null
        private set

    /** [W1] خطاف إشعار الواجهة (ProVM يربطه بحالاته التفاعلية) — يُنادى من خيوط Billing */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun notifyChanged() { onChanged?.let { cb -> runCatching { cb() } } }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    /** اتصال كسول — يستدعى عند فتح شاشة Pro أو عند إقلاع التطبيق (AppVM) */
    fun connect() {
        if (billingReady || client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                billingReady = result.responseCode == BillingClient.BillingResponseCode.OK
                if (billingReady) refresh() else notifyChanged()
            }

            override fun onBillingServiceDisconnected() {
                billingReady = false
                notifyChanged()
            }
        })
    }

    /** رصد المشتريات الحية وتصحيح السجل المحلي (استعادة = استدعاء هذه مجدداً) */
    fun refresh() {
        if (!client.isReady) return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val hasPro = purchases.any {
                it.products.contains(ProEntitlement.SKU_PRO) &&
                    ProEntitlement.stateOf(it.purchaseState.toPlay()) ==
                    ProEntitlement.ProState.PRO
            }
            scope.launch { applyEntitlement(hasPro, purchases) }
            notifyChanged()
        }
    }

    /** لصق الاستحقاق + الإقرار — القرار النقي في المحرك، والكتابة هنا فقط */
    private suspend fun applyEntitlement(hasPro: Boolean, purchases: List<Purchase>) {
        val state = ProEntitlement.entitlementFromPurchases(hasPro)
        if (ProEntitlement.localWriteAllowed(state)) {
            proStore.setUnlocked(state == ProEntitlement.ProState.PRO)
        }
        purchases.forEach { p ->
            if (ProEntitlement.shouldAcknowledge(
                    p.purchaseState.toPlay(), p.isAcknowledged
                )
            ) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(p.purchaseToken).build()
                ) { /* الإقرار أحادي الاتجاه — فشله لا يغيّر الاستحقاق المحلي */ }
            }
        }
    }

    /** جلب تفاصيل المنتج (للعرض السعر) ثم تشغيل تدفق الشراء من نافذة نشطة */
    fun purchase(activity: Activity, onUnavailable: (Int) -> Unit) {
        if (!client.isReady) {
            onUnavailable(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
            return
        }
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(ProEntitlement.SKU_PRO)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                ).build()
        ) { result, detailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || detailsList.isNullOrEmpty()) {
                onUnavailable(result.responseCode)
                return@queryProductDetailsAsync
            }
            val pd = detailsList.first { it.productId == ProEntitlement.SKU_PRO }
            formattedPrice = pd.oneTimePurchaseOfferDetails?.formattedPrice
            notifyChanged()
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd)
                            .build()
                    )
                ).build()
            client.launchBillingFlow(activity, flow)
        }
    }

    /** الاستعادة اليدوية (زر في شاشة Pro) — إعادة الرصد تكفي؛ Play هو المصدر */
    fun restore(onDone: (Boolean) -> Unit = {}) {
        if (!client.isReady) {
            onDone(false)
            return
        }
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()
        ) { result, purchases ->
            val ok = result.responseCode == BillingClient.BillingResponseCode.OK
            if (ok) {
                val hasPro = purchases.any {
                    it.products.contains(ProEntitlement.SKU_PRO) &&
                        ProEntitlement.stateOf(it.purchaseState.toPlay()) ==
                        ProEntitlement.ProState.PRO
                }
                scope.launch { applyEntitlement(hasPro, purchases) }
            }
            notifyChanged()
            onDone(ok)
        }
    }

    /** رصد تدفق الشراء الحي (النافذة المنبثقة) — نفس عقد applyEntitlement */
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return
        val list = purchases ?: return
        val hasPro = list.any {
            it.products.contains(ProEntitlement.SKU_PRO) &&
                ProEntitlement.stateOf(it.purchaseState.toPlay()) ==
                ProEntitlement.ProState.PRO
        }
        scope.launch { applyEntitlement(hasPro, list) }
        notifyChanged()
    }

    /** تحويل حالة الشراء الصحيحة إلى تعداد المحرك النقي */
    private fun Int.toPlay(): ProEntitlement.PlayPurchaseState = when (this) {
        Purchase.PurchaseState.PURCHASED -> ProEntitlement.PlayPurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> ProEntitlement.PlayPurchaseState.PENDING
        else -> ProEntitlement.PlayPurchaseState.UNSPECIFIED
    }
}
