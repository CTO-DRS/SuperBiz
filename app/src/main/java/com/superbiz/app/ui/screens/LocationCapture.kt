package com.superbiz.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [P12-a] محرّك التقاط الموقع المشترك (نفس الحزمة — تستهلكه FavoritesScreen وVisitsSection
 * وأي شاشة قادمة) — بديل الدالة الخاصة القديمة في FavoritesScreen بتجربة/تقوية كاملة
 * للاستخدام على الجهاز الحقيقي.
 *
 * تعاقد الاستدعاء:
 * - لا يُطلَق أي استثناء أبداً — كل النتائج (نجاح/فشل) تمر عبر onResult مرة واحدة فقط.
 * - onResult يُستدعى دائماً على الخيط الرئيسي (سواء جاء من مستهلك getCurrentLocation
 *   على getMainExecutor أم من مؤقّت Handler الرئيسي أم من مسار API<30 المباشر — نضمن
 *   النشر إلى mainLooper إن صيغ الالتقاط من خيط خلفي).
 *
 * خطوات التقوية (Hardening) بالترتيب:
 *  1) بلا إذن (ACCESS_FINE_LOCATION وACCESS_COARSE_LOCATION كلاهما غير ممنوحين) →
 *     Failed فوراً. المستدعي مسؤول عن طلب الإذن أولاً (نمط rememberLauncherForActivityResult
 *     في الشاشات) — هذا المحرّك لا يطلب أذوناً بنفسه.
 *  2) كل مزوّدات الموقع معطّلة (GPS وNETWORK) → GpsOff فوراً — الواجهة تعرض عندئذٍ
 *     «فتح إعدادات الموقع» بدل رسالة فشل عامة (تمييز «الخدمة معطّلة» عن «فشل التحديد»).
 *  3) API ≥ 30: getCurrentLocation على أول مزوّد مفعّل (GPS → NETWORK → FUSED على 31+)
 *     لقراءة طازجة، مع CancellationSignal يُلغى بعد timeoutMs عبر postDelayed على
 *     Handler(mainLooper) — عند المهلة يُسلَّم Failed مرة واحدة ويُمنع أي تسليم لاحق.
 *  4) API < 30: لا getCurrentLocation — نعتمد getLastKnownLocation(GPS) ثم NETWORK.
 *     بصراحة: قد يكون الموقع قديماً (أقدم من 10 دقائق أو أكثر) لكنه يُسلَّم على أي حال
 *     — أفضل من لا شيء على الأجهزة القديمة حيث لا بديل متاح بلا خدمات Google Play.
 *     كلا الموقعين null → Failed.
 *  5) كل الاستثناءات (SecurityException من إذن سحب لحظياً، IllegalArgumentException من
 *     مزوّد تعطّل بين الفحص والطلب، IllegalStateException… إلخ) → Failed — لا انهيار
 *     أبداً (نمط startIntentSafe).
 *
 * التسليم المعدّي الأحادي (idempotent): علم AtomicBoolean يضمن أن onResult يُستدعى
 * مرة واحدة بالضبط حتى لو تسابق المستهلك مع مؤقّت المهلة (الاثنان على الخيط الرئيسي
 * لكن العلم يحمي أيضاً أي تسليم من خيط آخر).
 */
object LocationCapture {

    /** [P12-a] نتيجة الالتقاط — مختومة لأن المسارات الثلاثة حصرية */
    sealed class Result {
        /** نجح الالتقاط بإحداثيات صالحة */
        data class Fixed(val lat: Double, val lng: Double) : Result()

        /** كل المزوّدات معطّلة → الواجهة تعرض «فتح إعدادات الموقع» */
        object GpsOff : Result()

        /** مهلة/استثناء/بلا إذن → رسالة فشل عامة */
        object Failed : Result()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** [P12-a] هل أي مزوّد موقع مفعّل؟ — GPS أو NETWORK (الحد الأدنى المشترك لكل الأجهزة) */
    fun isAnyProviderEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        lm != null && (
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
    } catch (e: Exception) {
        false // لا انهيار أبداً — اللا-معروف يعامل كمعطّل فتظهر إرشادات الإعدادات
    }

    /**
     * [P12-a] التقاط موقع حالي — نتيجة واحدة على الخيط الرئيسي عبر onResult.
     * راجع KDoc الكائن لخطوات التقوية الخمس. لا يرمي أبداً.
     */
    fun capture(ctx: Context, timeoutMs: Long = 20_000L, onResult: (Result) -> Unit) {
        val delivered = AtomicBoolean(false)
        val appCtx = ctx.applicationContext

        fun deliver(result: Result) {
            if (delivered.compareAndSet(false, true)) {
                if (Looper.myLooper() == Looper.getMainLooper()) onResult(result)
                else mainHandler.post { onResult(result) }
            }
        }

        try {
            // (1) الإذن — المستدعي يطلبه أولاً؛ هنا فحص نهائي فقط
            val fine = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                deliver(Result.Failed)
                return
            }

            // (2) خدمات الموقع معطّلة كلها → GpsOff فوراً (لا مهلة 20 ثانية بلا معنى)
            if (!isAnyProviderEnabled(appCtx)) {
                deliver(Result.GpsOff)
                return
            }

            val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                deliver(Result.Failed)
                return
            }

            if (Build.VERSION.SDK_INT >= 30) {
                // (3) قراءة طازجة على أول مزوّد مفعّل: GPS → NETWORK → FUSED (31+)
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                        LocationManager.FUSED_PROVIDER
                    else -> null // سباق نادر: عُطّل المزوّد بين الفحص والطلب
                }
                if (provider == null) {
                    deliver(Result.Failed)
                    return
                }

                val signal = CancellationSignal()
                // مؤقّت المهلة على الخيط الرئيسي — يلغي الطلب ويسلّم Failed مرة واحدة
                val timeoutRunnable = Runnable {
                    try {
                        signal.cancel()
                    } catch (e: Exception) {
                        // لا شيء — الإلغاء لن يرمي عملياً لكن نحمي تسليم Failed
                    }
                    deliver(Result.Failed)
                }
                mainHandler.postDelayed(timeoutRunnable, timeoutMs.coerceAtLeast(1_000L))

                try {
                    lm.getCurrentLocation(
                        provider, signal, ContextCompat.getMainExecutor(appCtx)
                    ) { loc ->
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (loc != null) deliver(Result.Fixed(loc.latitude, loc.longitude))
                        else deliver(Result.Failed) // أُلغي الطلب أو فشل داخلياً
                    }
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    deliver(Result.Failed) // SecurityException/IllegalArgumentException…
                }
            } else {
                // (4) API < 30: آخر موقع معروف — GPS ثم NETWORK. بصراحة: قد يكون قديماً،
                // لكنه يُسلَّم على أي حال — أفضل من لا شيء (لا getCurrentLocation قبل API 30).
                @Suppress("DEPRECATION")
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) deliver(Result.Fixed(loc.latitude, loc.longitude))
                else deliver(Result.Failed)
            }
        } catch (e: SecurityException) {
            deliver(Result.Failed)
        } catch (e: Exception) {
            // (5) أي فشل آخر — لا انهيار أبداً (نمط startIntentSafe)
            deliver(Result.Failed)
        }
    }
}
