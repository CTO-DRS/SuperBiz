package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.HealthScore
import com.superbiz.app.domain.PinStrength
import com.superbiz.app.domain.SettingsCodec
import com.superbiz.app.security.PinManager
import com.superbiz.app.security.PinVault
import com.superbiz.app.work.BackupAutoLogic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * : اختبارات موجة الإعدادات + الأمان (R6-P4-4b) —
 * بنّاء/قارئ تصدير الإعدادات JSON (وظيفة 33): القبول الصالح، ورفض المحظور/المجهول/
 * النوع الخطأ/خارج النطاق/المشوّه/المكرر/الترويسة الفاسدة/القيم الفارغة —
 * ومقياس قوة PIN (وظيفة 37) — ودرجة الصحة المالية (وظيفة 39): الأوزان الدقيقة،
 * الحدود، null-النشر، وكل المدخلات الصفرية — ومنطق جدولة النسخ التلقائي (وظيفة 34).
*/
class SettingsSecurityP4Test {

    private fun s(k: String, v: String) = SettingsCodec.Entry(k, SettingsCodec.Val.S(v))
    private fun n(k: String, v: Double) = SettingsCodec.Entry(k, SettingsCodec.Val.N(v))
    private fun b(k: String, v: Boolean) = SettingsCodec.Entry(k, SettingsCodec.Val.B(v))

    private fun header(values: String) =
        "{\"format\":\"superbiz-settings\",\"version\":1,\"values\":{$values}}"

    // ══ وظيفة 33 — بنّاء/قارئ إعدادات JSON ══

    @Test
    fun codec_roundtrip_preservesValidEntries() {
        val entries = listOf(
            s("language", "ar"), s("theme", "dark"), s("baseCurrency", "SAR"),
            n("taxRate", 15.0), n("fontScale", 1.1), n("lockTimeoutMin", 5.0),
            n("autoBackupDays", 3.0), n("searchFuzzyThreshold", 0.45),
            b("hapticsEnabled", true), b("privacyBlur", true),
            s("reportRecipient", "user@example.com")
        )
        val parsed = SettingsCodec.parse(SettingsCodec.build(entries))
        assertTrue(parsed is SettingsCodec.ParseResult.Ok)
        assertEquals(entries, (parsed as SettingsCodec.ParseResult.Ok).values)
    }

    @Test
    fun codec_parse_acceptsHeaderAndRejectsBadHeader() {
        val ok = SettingsCodec.parse(header("\"theme\":\"dark\""))
        assertTrue(ok is SettingsCodec.ParseResult.Ok)
        // ترويسة صيغة خاطئة
        val badFormat = SettingsCodec.parse(
            "{\"format\":\"other-tool\",\"version\":1,\"values\":{\"theme\":\"dark\"}}"
        )
        assertTrue(badFormat is SettingsCodec.ParseResult.Err)
        assertEquals("bad_format", (badFormat as SettingsCodec.ParseResult.Err).reason)
        // إصدار غير مدعوم
        val badVersion = SettingsCodec.parse(
            "{\"format\":\"superbiz-settings\",\"version\":2,\"values\":{\"theme\":\"dark\"}}"
        )
        assertEquals("bad_version", ((badVersion as SettingsCodec.ParseResult.Err).reason))
    }

    @Test
    fun codec_parse_rejectsBlockedAndUnknownKeys() {
        // مفتاح سرّي محظور (مواد الرمز) — القائمة البيضاء ترفضه
        val secret = SettingsCodec.parse(header("\"pinBlob\":\"wrapped-secret\""))
        assertTrue(secret is SettingsCodec.ParseResult.Err)
        assertEquals("unknown_key:pinBlob", (secret as SettingsCodec.ParseResult.Err).reason)
        // مفتاح مجهول تماماً
        val unknown = SettingsCodec.parse(header("\"totallyUnknown\":1"))
        assertEquals("unknown_key:totallyUnknown", ((unknown as SettingsCodec.ParseResult.Err).reason))
        // biometric محظور أيضاً بوصفه بوابة حساسة
        val bio = SettingsCodec.parse(header("\"biometric\":true"))
        assertEquals("unknown_key:biometric", ((bio as SettingsCodec.ParseResult.Err).reason))
    }

    @Test
    fun codec_parse_rejectsWrongTypes() {
        // نص حيث عدد متوقع / عدد حيث نص متوقع / منطقي حيث نص متوقع
        assertEquals("bad_type:taxRate", reasonOf(SettingsCodec.parse(header("\"taxRate\":\"15\""))))
        assertEquals("bad_type:theme", reasonOf(SettingsCodec.parse(header("\"theme\":5"))))
        assertEquals("bad_type:hapticsEnabled", reasonOf(SettingsCodec.parse(header("\"hapticsEnabled\":\"yes\""))))
        // كائن متداخل حيث قيمة عددية — يُرفض (لا قيم مركبة في الصيغة)
        assertEquals("bad_type:taxRate", reasonOf(SettingsCodec.parse(header("\"taxRate\":{\"a\":1}"))))
    }

    @Test
    fun codec_parse_rejectsOutOfRangeValues() {
        assertEquals("out_of_range:taxRate", reasonOf(SettingsCodec.parse(header("\"taxRate\":500"))))
        assertEquals("out_of_range:fontScale", reasonOf(SettingsCodec.parse(header("\"fontScale\":2.0"))))
        assertEquals("out_of_range:lateFeeDailyPct", reasonOf(SettingsCodec.parse(header("\"lateFeeDailyPct\":9"))))
        // 90 يوماً أصبح صالحاً (كان سقف 30 يرمي قيماً مشروعة يكتبها setAutoBackupDays
        // حتى 365 فيُسقط تصدير الإعدادات كله) — خارج المدى الآن هو فوق 365 فقط
        assertTrue("90 يوماً يجب أن يقبل الآن (R14-F13)", SettingsCodec.parse(header("\"autoBackupDays\":90")) is SettingsCodec.ParseResult.Ok)
        assertEquals("out_of_range:autoBackupDays", reasonOf(SettingsCodec.parse(header("\"autoBackupDays\":366"))))
        // قيمة نصية خارج المسموح
        assertEquals("bad_value:theme", reasonOf(SettingsCodec.parse(header("\"theme\":\"solarized\""))))
    }

    @Test
    fun codec_parse_rejectsMalformedJson() {
        assertEquals("empty", reasonOf(SettingsCodec.parse("")))
        assertEquals("empty", reasonOf(SettingsCodec.parse(null)))
        assertEquals("malformed", reasonOf(SettingsCodec.parse("not json at all")))
        assertEquals("malformed", reasonOf(SettingsCodec.parse(header("\"theme\":\"dark\"") + " trailing")))
        assertEquals("malformed", reasonOf(SettingsCodec.parse(header("\"theme\":\"dark\"").dropLast(1))))
        // مفتاح مكرر داخل values
        assertEquals(
            "duplicate:theme",
            reasonOf(SettingsCodec.parse(header("\"theme\":\"dark\",\"theme\":\"light\"")))
        )
        // قيم فارغة تماماً
        assertEquals("empty_values", reasonOf(SettingsCodec.parse(header(""))))
    }

    @Test
    fun codec_parse_rejectsTopLevelUnknownKey() {
        val r = SettingsCodec.parse(
            "{\"format\":\"superbiz-settings\",\"version\":1,\"values\":{\"theme\":\"dark\"},\"pinSalt\":\"abc\"}"
        )
        assertEquals("unknown_key:pinSalt", reasonOf(r))
    }

    @Test
    fun codec_build_validatesAndEscapes() {
        // مفتاح مجهول في البناء
        try { SettingsCodec.build(listOf(s("pinBlob", "x"))); fail("مفتاح محظور يجب رفضه") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("unknown_key")) }
        // عدد غير سالم
        try { SettingsCodec.build(listOf(n("taxRate", Double.NaN))); fail("NaN يجب رفضه") }
        catch (e: IllegalArgumentException) { assertEquals("bad_number:taxRate", e.message) }
        // قائمة فارغة
        try { SettingsCodec.build(emptyList()); fail("قائمة فارغة يجب رفضها") }
        catch (e: IllegalArgumentException) { assertEquals("empty", e.message) }
        // تهريب السلاسل: اقتباس ومائل عكسي يبقيان القيمة مطابقة بعد القراءة
        val tricky = "a\"b\\c"
        val parsed = SettingsCodec.parse(SettingsCodec.build(listOf(s("reportRecipient", tricky))))
        assertEquals(tricky, ((parsed as SettingsCodec.ParseResult.Ok).values.single().value as SettingsCodec.Val.S).v)
    }

    private fun reasonOf(r: SettingsCodec.ParseResult): String =
        (r as? SettingsCodec.ParseResult.Err)?.reason ?: "expected Err but was $r"

    // ══ وظيفة 37 — مقياس قوة الرمز ══

    @Test
    fun pinStrength_belowPolicyIsZero() {
        assertEquals(0, PinStrength.level(""))
        assertEquals(0, PinStrength.level("12345"))
        assertEquals(0, PinStrength.level("ab1"))
    }

    @Test
    fun pinStrength_knownWeakPatternsAreCappedAtOne() {
        assertEquals(1, PinStrength.level("123456"))     // متتالية صاعدة
        assertEquals(1, PinStrength.level("87654321"))   // متتالية هابطة بطول 8
        assertEquals(1, PinStrength.level("111111"))     // كل المحارف متطابقة
        assertEquals(1, PinStrength.level("000000"))
        assertTrue(PinStrength.weakPattern("654321"))
        assertFalse(PinStrength.weakPattern("24681357"))
    }

    @Test
    fun pinStrength_varietyAndLengthLevels() {
        assertEquals(2, PinStrength.level("12ab34"))     // 6 محارف + فئتان
        assertEquals(2, PinStrength.level("13572468"))   // 8 أرقام غير متتالية
        assertEquals(2, PinStrength.level("abcdefgh"))   // 8 حروف بلا تنوّع
        assertEquals(3, PinStrength.level("a1b2c3d4"))   // 8 + فئتان
        assertEquals(3, PinStrength.level("abc123!@"))   // 8 + ثلاث فئات
    }

    // ══ وظيفة 39 — درجة الصحة المالية ══

    @Test
    fun healthScore_exactWeights() {
        // 40% هامش + 30% سيولة (norm 90 يوماً) + 30% تحصيل
        assertEquals(50, HealthScore.compute(HealthScore.Inputs(0.5, 45.0, 50.0)))   // 20+15+15
        assertEquals(54, HealthScore.compute(HealthScore.Inputs(0.6, 90.0, 0.0)))    // 24+30+0
        assertEquals(0, HealthScore.compute(HealthScore.Inputs(0.0, 0.0, 0.0)))      // كل المدخلات صفرية
        // لا حرق نقدي (sentinel سالب) = مكون السيولة كامل: 0 + 30 + 0
        assertEquals(30, HealthScore.compute(HealthScore.Inputs(0.0, -1.0, 0.0)))
    }

    @Test
    fun healthScore_nullPropagation() {
        assertNull(HealthScore.compute(HealthScore.Inputs(null, 45.0, 50.0)))
        assertNull(HealthScore.compute(HealthScore.Inputs(0.5, null, 50.0)))
        assertNull(HealthScore.compute(HealthScore.Inputs(0.5, 45.0, null)))
        assertNull(HealthScore.compute(HealthScore.Inputs(Double.NaN, 45.0, 50.0)))
        assertNull(HealthScore.compute(HealthScore.Inputs(0.5, Double.POSITIVE_INFINITY, 50.0)))
    }

    @Test
    fun healthScore_boundariesAndClamps() {
        // القصّ من الأعلى: هامش 1.2 → 100، سيولة 200 يوم → 100، تحصيل 150 → 100
        assertEquals(100, HealthScore.compute(HealthScore.Inputs(1.2, 200.0, 150.0)))
        // القصّ من الأسفل: هامش سالب → 0
        assertEquals(0, HealthScore.compute(HealthScore.Inputs(-0.5, 0.0, 0.0)))
        // سيولة صفرية (نقد منتهٍ) → صفر لهذا المكون
        assertEquals(70, HealthScore.compute(HealthScore.Inputs(1.0, 0.0, 100.0)))   // 40+0+30
        // مستويات العرض
        assertEquals(3, HealthScore.levelOf(100)); assertEquals(3, HealthScore.levelOf(80))
        assertEquals(2, HealthScore.levelOf(79)); assertEquals(2, HealthScore.levelOf(60))
        assertEquals(1, HealthScore.levelOf(59)); assertEquals(1, HealthScore.levelOf(40))
        assertEquals(0, HealthScore.levelOf(39)); assertEquals(0, HealthScore.levelOf(0))
    }

    // ══ وظيفة 34 — منطق جدولة النسخ التلقائي ══

    @Test
    fun backupAutoLogic_dueAndPeriod() {
        val now = 1_700_000_000_000L
        val day = BackupAutoLogic.DAY_MS
        // معطّل → لا نسخ
        assertTrue(!BackupAutoLogic.isDue(now, 0, 0))
        // بلا ختم سابق → نتيجة أولى الآن
        assertTrue(BackupAutoLogic.isDue(now, 0, 7))
        // داخل النافذة → لا، وخارجها → نعم
        assertTrue(!BackupAutoLogic.isDue(now, now - 3 * day, 7))
        assertTrue(BackupAutoLogic.isDue(now, now - 8 * day, 7))
        // الهامش السماوي 5 دقائق: تُحتسب النتيجة مستحقة قبل اكتمال الدورة بخمس دقائق
        assertTrue(!BackupAutoLogic.isDue(now, now - (7 * day - 6 * 60_000L), 7))
        assertTrue(BackupAutoLogic.isDue(now, now - (7 * day - 4 * 60_000L), 7))
        // مدة الدورة: الخيارات 1/3/7/30 مع القصّ
        assertEquals(1L, BackupAutoLogic.periodDays(1))
        assertEquals(3L, BackupAutoLogic.periodDays(3))
        assertEquals(30L, BackupAutoLogic.periodDays(30))
        assertEquals(1L, BackupAutoLogic.periodDays(0))
        assertEquals(30L, BackupAutoLogic.periodDays(45))
    }

    private fun assertFalse(v: Boolean) { org.junit.Assert.assertFalse(v) }
}

/**
 * [P6-M6-14 إصلاح] تكامل الخزنة مع مخزن الإعدادات (Robolectric):
 *  • لا تُكتب v1 جديدة — فشل Keystore عند التشفير فشل مغلق باستثناء واضح.
 *  • الترقية الشفافة v1→ks بعد أول تحقق ناجح تخزن الصيغة الآمنة مع حفظ طول الرمز ومحو المفقودات.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinVaultSettingsUpgradeTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** مفتاح AES حقيقي يُحقن بديلاً عن AndroidKeyStore المفقود في Robolectric */
    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun keystoreFailure_failsClosed_writesNoNewV1() = runBlocking {
        PinVault.keyProvider = { throw java.security.KeyStoreException("no keystore in test") }
        try {
            val repo = SettingsRepo(ctx)
            repo.clearPin()
            try {
                repo.setPinSecured("0011223344", PinVault.encrypt("ab".repeat(32)), PinManager.ITERATIONS, 6)
                fail("يجب أن يفشل التشفير مغلقاً بدل إنتاج v1 جديدة")
            } catch (e: PinVault.PinVaultException) {
                assertTrue(e.message!!.contains("Keystore"))
            }
            // لا مادة رمز جديدة في المخزن
            assertNull(repo.snapshot().pinBlob)
            assertNull(repo.snapshot().pinSalt)
        } finally {
            PinVault.keyProvider = { PinVault.masterKey() }
        }
    }

    @Test
    fun v1Upgrade_afterSuccessfulVerify_persistsKsFormat() = runBlocking {
        PinVault.keyProvider = { testKey }
        try {
            val repo = SettingsRepo(ctx)
            repo.clearPin()
            val salt = PinManager.newSalt()
            // مخزون قديم v1 (بصمة عارية) + طول رمز مخزّن من تعيين سابق
            val legacyHash = PinManager.hash("135791", salt, PinManager.ITERATIONS)
            repo.setPinSecured(salt, "v1:$legacyHash", PinManager.ITERATIONS, 6)
            // «تحقق ناجح» من الصيغة القديمة (نفس ترتيب SettingsVM.verifyPin) ثم الترقية الشفافة
            val blob = repo.snapshot().pinBlob
            val unwrapped = blob?.let { PinVault.decrypt(it) }
            val verified = unwrapped != null &&
                PinManager.verify("135791", salt, unwrapped!!, PinManager.ITERATIONS)
            assertTrue(verified)
            val rewrapped = PinVault.rewrap(blob)
            assertNotNull(rewrapped)
            repo.setPinSecured(salt, rewrapped!!, PinManager.ITERATIONS, 6)
            val s = repo.snapshot()
            assertTrue(s.pinBlob!!.startsWith("ks:"))
            // الرمز نفسه يتحقق من الصيغة الجديدة — بلا فقدان وصول
            val after = PinVault.decrypt(s.pinBlob)
            assertTrue(after != null && PinManager.verify("135791", salt, after!!, PinManager.ITERATIONS))
            assertEquals(6, s.pinLength)
            assertNull(s.pinHash)
            repo.clearPin()
            assertNull(repo.snapshot().pinBlob)
        } finally {
            PinVault.keyProvider = { PinVault.masterKey() }
        }
    }
}
