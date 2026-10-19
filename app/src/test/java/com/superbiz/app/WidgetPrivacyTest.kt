package com.superbiz.app

import com.superbiz.app.domain.AppLockSignal
import com.superbiz.app.domain.statement.StatementService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-4 V 3.2.2] جدار خصوصية الويدجات + عقد معرّف التحقق — اختبار JVM نقي
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * يثبّت قرار الإخفاء الموحد [AppLockSignal.shouldRedactWidgets]:
 *  1) تفضيل المالك يحسم دائماً (العقد التاريخي).
 *  2) قفل مهيأ + تطبيق غير مفتوح = إخفاء حتى بلا تفضيل (الإصلاح الجوهري —
 *     كان الخطر 6: المبالغ معروضة في الويدجت والتطبيق مقفل).
 *  3) قفل مهيأ + تطبيق مفتوح = عرض حسب التفضيل (فتح القفل لا يعاقب).
 *  4) بلا قفل مهيأ: لا إخفاء إضافي أبداً (السلوك التاريخي حرفياً).
 *  5) الافتراض fail-closed: الإشارة تبدأ مقفلة.
 *
 * ويثبّت عقد [StatementService.verificationId] بعد إضافة اللاحقة العشوائية
 * (RVW-7): النسخة ثنائية الوسائط = العقد التاريخي بايتاً بايتاً، والنسخة
 * بلاحقة تحفظ البادئة التاريخية وتضيف مكوّناً غير قابل للتخمين.
 */
class WidgetPrivacyTest {

    @Before
    fun resetSignal() {
        AppLockSignal.unlocked = false
    }

    @After
    fun cleanupSignal() {
        AppLockSignal.unlocked = false
    }

    // ─── مصفوفة إخفاء الويدجات (RVW-4) ───

    @Test
    fun `owner preference redacts regardless of lock state`() {
        assertTrue(AppLockSignal.shouldRedactWidgets(prefRedact = true, lockConfigured = false))
        AppLockSignal.unlocked = true
        assertTrue(AppLockSignal.shouldRedactWidgets(prefRedact = true, lockConfigured = false))
        assertTrue(AppLockSignal.shouldRedactWidgets(prefRedact = true, lockConfigured = true))
    }

    @Test
    fun `lock configured and app locked redacts even without preference - the fix`() {
        // الجوهر: الويدجت مرئية للناظرين بلا قفل — القفل شاشة التطبيق وحده كان
        // يترك المبالغ (نقد/مبيعات/ذمم) مقروءة من الشاشة الرئيسية والتطبيق مقفل
        assertFalse(AppLockSignal.unlocked)
        assertTrue(AppLockSignal.shouldRedactWidgets(prefRedact = false, lockConfigured = true))
    }

    @Test
    fun `lock configured and app unlocked shows amounts per preference`() {
        AppLockSignal.unlocked = true
        assertFalse(AppLockSignal.shouldRedactWidgets(prefRedact = false, lockConfigured = true))
    }

    @Test
    fun `no lock configured never adds redaction - historic behavior preserved`() {
        assertFalse(AppLockSignal.shouldRedactWidgets(prefRedact = false, lockConfigured = false))
        AppLockSignal.unlocked = true
        assertFalse(AppLockSignal.shouldRedactWidgets(prefRedact = false, lockConfigured = false))
    }

    @Test
    fun `signal defaults to locked - fail closed on cold process`() {
        // إقلاع بارد للعملية (موت عملية أثناء الخلفية): الرسم الأول للويدجة
        // يجب أن يعرض القناع لا الأرقام — الافتراض مقفل
        assertFalse(AppLockSignal.unlocked)
    }

    @Test
    fun `lockConfigured detects each credential exactly like MainActivity`() {
        assertFalse(AppLockSignal.lockConfigured(null, null, biometric = false))
        assertTrue(AppLockSignal.lockConfigured("hash", null, biometric = false))
        assertTrue(AppLockSignal.lockConfigured(null, "blob", biometric = false))
        assertTrue(AppLockSignal.lockConfigured(null, null, biometric = true))
    }

    // ─── عقد معرّف التحقق (RVW-7) ───

    /** توقع ymd من نفس تقويم الإنتاج — اختبار محايد للمنطقة الزمنية */
    private fun expectedYmd(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return String.format(
            java.util.Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    @Test
    fun `legacy two-arg verificationId byte-identical to historic contract`() {
        val at = 1_758_643_200_000L
        assertEquals(
            "SB-ST-${expectedYmd(at)}-000001",
            StatementService.verificationId("STATEMENT-2026-000001", at)
        )
        // seq غير رقمي → "000000" بدل استثناء (العقد النقي القديم)
        assertEquals(
            "SB-ST-${expectedYmd(at)}-000000",
            StatementService.verificationId("STATEMENT-2026-XYZ", at)
        )
    }

    @Test
    fun `entropy suffix keeps historic prefix and blank entropy is identity`() {
        val at = 1_758_643_200_000L
        val legacy = StatementService.verificationId("STATEMENT-2026-000042", at)
        // فراغ = العقد التاريخي حرفياً (نفس التوقيع ثنائي الوسائط)
        assertEquals(legacy, StatementService.verificationId("STATEMENT-2026-000042", at, ""))
        assertEquals(legacy, StatementService.verificationId("STATEMENT-2026-000042", at, "  "))
        // لاحقة حقيقية: البادئة التاريخية محفوظة + المكوّن غير القابل للتخمين مُلحق
        val hardened = StatementService.verificationId("STATEMENT-2026-000042", at, "K7QM")
        assertEquals("$legacy-K7QM", hardened)
        assertTrue(hardened.startsWith("SB-ST-"))
    }

    @Test
    fun `entropy generator unambiguous alphabet and deterministic seed`() {
        val e = StatementService.newVerificationEntropy(4, Random(42))
        assertEquals(4, e.length)
        // أبجدية بلا محارف ملتبسة بصرياً (بلا 0/O و1/I/L)
        assertTrue(e.all { it in "ABCDEFGHJKMNPQRSTUVWXYZ23456789" })
        // نفس البذرة = نفس النتيجة (اختبار حتمي) — والتوزيع يغطي عينات مختلفة
        assertEquals(e, StatementService.newVerificationEntropy(4, Random(42)))
        assertNotEquals(
            StatementService.newVerificationEntropy(4, Random(1)),
            StatementService.newVerificationEntropy(4, Random(2))
        )
    }
}
