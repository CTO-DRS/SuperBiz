package com.superbiz.app.domain.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات TextMath: بحث ضبابي عربي على الحالات الحدية الواقعية.
 * كل القيم العددية محسوبة يدوياً (ومتحقق منها بحساب مستقل) قبل كتابة التأكيد.
*/
class TextMathTest {

    // ───────── levenshtein ─────────

    @Test
    fun levenshtein_classicAndEmpty() {
        assertEquals(3, TextMath.levenshtein("kitten", "sitting"))
        assertEquals(0, TextMath.levenshtein("", ""))
        assertEquals(3, TextMath.levenshtein("", "abc"))
        assertEquals(3, TextMath.levenshtein("abc", ""))
        assertEquals(0, TextMath.levenshtein("same", "same"))
    }

    @Test
    fun levenshtein_arabicStrings() {
        // كتاب → كتب: حذف الألف فقط (وليس 2)
        assertEquals(1, TextMath.levenshtein("كتاب", "كتب"))
        // اختلاف الهمزة = استبدال واحد
        assertEquals(1, TextMath.levenshtein("أحمد", "احمد"))
        assertEquals(0, TextMath.levenshtein("نجم", "نجم"))
    }

    // ───────── similarityRatio ─────────

    @Test
    fun similarityRatio_exactEmptyAndPartial() {
        assertEquals(1.0, TextMath.similarityRatio("abc", "abc"), 1e-9)
        assertEquals(1.0, TextMath.similarityRatio("", ""), 1e-9)
        assertEquals(0.0, TextMath.similarityRatio("", "abc"), 1e-9)
        assertEquals(0.0, TextMath.similarityRatio("abc", ""), 1e-9)
        // 1 - 3/7
        assertEquals(0.5714285714285714, TextMath.similarityRatio("kitten", "sitting"), 1e-9)
        // 1 - 1/3
        assertEquals(0.6666666666666667, TextMath.similarityRatio("abc", "abd"), 1e-9)
        // 1 - 2/4
        assertEquals(0.5, TextMath.similarityRatio("abcd", "abef"), 1e-9)
    }

    // ───────── jaroWinkler ─────────

    @Test
    fun jaroWinkler_canonicalPairs() {
        // الزوج القياسي المرجعي (تبادل T/H): ≈ 0.9611
        assertEquals(0.9611111111111111, TextMath.jaroWinkler("MARTHA", "MARHTA"), 1e-9)
        assertEquals(0.9611111111111111, TextMath.jaroWinkler("martha", "marhta"), 1e-9)
    }

    @Test
    fun jaroWinkler_prefixBoostAndScale() {
        // بادئة مشتركة "mart" (4) ترفع جارو 0.9444… إلى ≈ 0.9667
        assertEquals(0.9666666666666667, TextMath.jaroWinkler("marta", "martha"), 1e-9)
        // prefixScale = 0 يلغي الدفعة ويعيد جارو المجرد
        assertEquals(0.9444444444444445, TextMath.jaroWinkler("MARTHA", "MARHTA", 0.0), 1e-9)
        // دفعة كبيرة جدًا تُسقَّف عند 1.0
        assertEquals(1.0, TextMath.jaroWinkler("abc", "abd", 0.5), 1e-9)
    }

    @Test
    fun jaroWinkler_edgeCases() {
        assertEquals(1.0, TextMath.jaroWinkler("abc", "abc"), 1e-9)
        assertEquals(1.0, TextMath.jaroWinkler("", ""), 1e-9)
        assertEquals(0.0, TextMath.jaroWinkler("", "x"), 1e-9)
        assertEquals(0.0, TextMath.jaroWinkler("x", ""), 1e-9)
        assertEquals(0.0, TextMath.jaroWinkler("abc", "xyz"), 1e-9)
        // جارو ≤ 0.7 فلا دفعة بادئة: m=2 → (2/4 + 2/4 + 2/2)/3 = 2/3
        assertEquals(0.6666666666666666, TextMath.jaroWinkler("abcd", "abef"), 1e-9)
    }

    // ───────── prefixScore ─────────

    @Test
    fun prefixScore_fullAndWordPrefix() {
        assertEquals(1.0, TextMath.prefixScore("نجم", "نجم للخدمات"), 1e-9)
        // كلمة من كلمات الهدف تبدأ بالاستعلام → 0.5
        assertEquals(0.5, TextMath.prefixScore("للخدم", "نجم للخدمات"), 1e-9)
        // لا بداية متطابقة ("للخدمات" لا تبدأ بـ"خدمات")
        assertEquals(0.0, TextMath.prefixScore("خدمات", "نجم للخدمات"), 1e-9)
    }

    @Test
    fun prefixScore_caseTrimAndMisses() {
        assertEquals(0.0, TextMath.prefixScore("", "نجم"), 1e-9)
        // تجاهل الفراغات الطرفية وحالة الأحرف
        assertEquals(1.0, TextMath.prefixScore("  نجم  ", " نجم للخدمات "), 1e-9)
        assertEquals(1.0, TextMath.prefixScore("NBC", "nbc news"), 1e-9)
        assertEquals(0.0, TextMath.prefixScore("xyz", "nbc news"), 1e-9)
    }

    // ───────── tokenScore ─────────

    @Test
    fun tokenScore_fractionAndCase() {
        assertEquals(0.5, TextMath.tokenScore("نجم عام", "نجم للخدمات العامة"), 1e-9)
        assertEquals(1.0, TextMath.tokenScore("نجم", "نجم للخدمات"), 1e-9)
        assertEquals(1.0, TextMath.tokenScore("NBC", "nbc news"), 1e-9)
        assertEquals(0.0, TextMath.tokenScore("", "نجم للخدمات"), 1e-9)
        assertEquals(0.0, TextMath.tokenScore("xyz", "نجم"), 1e-9)
        // نجم ✓ كبادئة، خ ✗ (لا كلمة تبدأ بها)
        assertEquals(0.5, TextMath.tokenScore("نجم خ", "نجم للخدمات"), 1e-9)
    }

    // ───────── initialsMatch ─────────

    @Test
    fun initialsMatch_wordInitials() {
        // أوائل كلمات الهدف: ن + ل
        assertTrue(TextMath.initialsMatch("ن ل", "نجم للخدمات"))
        assertTrue(TextMath.initialsMatch("ن ل م", "نجم للخدمات محمد"))
        assertFalse(TextMath.initialsMatch("خ ي", "نجم للخدمات"))
        // هدف بعدد كلمات أقل من الاستعلام
        assertFalse(TextMath.initialsMatch("ن ل", "نور"))
    }

    @Test
    fun initialsMatch_joinedAcronymAndGuards() {
        // حروف أول كلمة "نجم" نفسها: ن + ج + م
        assertTrue(TextMath.initialsMatch("ن ج م", "نجم للخدمات"))
        assertTrue(TextMath.initialsMatch("ن ج", "نجم للخدمات"))
        // استعلام بكلمة واحدة مرفوض دائمًا
        assertFalse(TextMath.initialsMatch("نجم", "نجم للخدمات"))
        assertFalse(TextMath.initialsMatch("", "نجم للخدمات"))
        assertFalse(TextMath.initialsMatch("ن ج م", ""))
    }

    // ───────── arabicNormalize ─────────

    @Test
    fun arabicNormalize_mappings() {
        assertEquals("ااا", TextMath.arabicNormalize("أإآ"))
        assertEquals("موسسه", TextMath.arabicNormalize("مؤسسة"))
        assertEquals("شيون", TextMath.arabicNormalize("شئون"))
        // إسقاط التشكيل (فتحة/ضمة/شدة)
        assertEquals("محمد", TextMath.arabicNormalize("مُحَمَّد"))
        // تصغير اللاتينية مع إبقاء بقية الحروف
        assertEquals("al-nour", TextMath.arabicNormalize("Al-Nour"))
        assertEquals("نجم", TextMath.arabicNormalize("نجم"))
        assertEquals("", TextMath.arabicNormalize(""))
    }

    // [P7-L3 إصلاح] — الأزواج المكافئة عبر ى/ٱ/التطويل أصبحت تطبيعاً واحداً
    @Test
    fun arabicNormalize_alefMaksuraWaslaTatweil() {
        // الألف المقصورة ى → ي (تطابق عرف BizMath بعد إغلاق الفجوة)
        assertEquals("علي", TextMath.arabicNormalize("على"))
        assertEquals("خزامي", TextMath.arabicNormalize("خزامى"))
        // ألف الوصل ٱ → ا
        assertEquals("ا", TextMath.arabicNormalize("ٱ"))
        assertEquals("اسلام", TextMath.arabicNormalize("ٱسلام"))
        // التطويل ـ يُهمَل كالتشكيل
        assertEquals("شركه", TextMath.arabicNormalize("شــركة"))
        assertEquals("محمد", TextMath.arabicNormalize("مـحـمـد"))
    }

    @Test
    fun fuzzyScore_equivalentPairsAcrossPaths() {
        // علي/على: كانت قبل الإصلاح 0.85×jw ≈ 0.699 — الآن تطابق تام بعد التطبيع
        assertEquals(1.0, TextMath.fuzzyScore("علي", "على"), 1e-9)
        assertEquals(1.0, TextMath.fuzzyScore("خزامى", "خزامي"), 1e-9)
        // شــركة/شركة: التطويل كان يكسر التطابق التام — الآن تطابق تام،
        // ونسخة التضمين (بطاقة أوسع) تقع في مسار 0.95
        assertEquals(1.0, TextMath.fuzzyScore("شركة", "شــركة"), 1e-9)
        assertEquals(0.95, TextMath.fuzzyScore("شركة", "شــركة نجم"), 1e-9)
        // ٱحمد/أحمد: كانت قبل الإصلاح 0.85×jw ≈ 0.708 — الآن تطابق تام
        assertEquals(1.0, TextMath.fuzzyScore("ٱحمد", "أحمد"), 1e-9)
        // مؤسسة/مؤسسة ٱ: حرس انحدار — الاحتواء بعد التطبيع يبقى في مسار 0.95
        assertTrue(TextMath.fuzzyScore("مؤسسة", "مؤسسة ٱ") >= 0.95)
        // المسار الحرفي: initialsMatch يرى الحرفين المكافئين حرفاً واحداً
        assertTrue(TextMath.initialsMatch("ع ل", "على"))
    }

    // ───────── digitsOnly ─────────

    @Test
    fun digitsOnly_arabicIndicAndFiltering() {
        assertEquals("0123456789", TextMath.digitsOnly("٠١٢٣٤٥٦٧٨٩"))
        // فارسية/أردية (U+06F0..)
        assertEquals("012", TextMath.digitsOnly("۰۱۲"))
        assertEquals("966501234567", TextMath.digitsOnly("+966 50 123-4567"))
        assertEquals("", TextMath.digitsOnly("abc"))
        assertEquals("", TextMath.digitsOnly(""))
        // خلط عربي ولاتيني مع إهمال غير الأرقام
        assertEquals("55", TextMath.digitsOnly("٥=5"))
    }

    // ───────── fuzzyScore ─────────

    @Test
    fun fuzzyScore_exactSubstringAndEmpty() {
        assertEquals(1.0, TextMath.fuzzyScore("نجم", "نجم"), 1e-9)
        // تطابق بعد التطبيع العربي (همزة/تشكيل)
        assertEquals(1.0, TextMath.fuzzyScore("مُحمد", "محمد"), 1e-9)
        assertEquals(0.95, TextMath.fuzzyScore("نجم", "شركة نجم للخدمات"), 1e-9)
        assertEquals(0.0, TextMath.fuzzyScore("", "نجم"), 1e-9)
        assertEquals(0.0, TextMath.fuzzyScore("   ", "نجم"), 1e-9)
    }

    @Test
    fun fuzzyScore_fuzzyCompositePath() {
        // لا بادئة ولا تضمين: max(0, 0.9×0, 0.85×jw) حيث jw("نجمم","نجم") ≈ 0.9416667
        assertEquals(0.8004166666666667, TextMath.fuzzyScore("نجمم", "نجم"), 1e-9)
        // درجة صفرية لاستعلام لا علاقة له
        assertEquals(0.0, TextMath.fuzzyScore("خزامى", "شركة نور"), 1e-6)
    }

    // ───────── rankByFuzzy ─────────

    @Test
    fun rankByFuzzy_orderTiesLimits() {
        val targets = listOf("نجم للخدمات", "شركة نور", "مؤسسة نجم", "nbc")
        // تعادلان عند 0.95 يثبتان بترتيب الفهرس تصاعديًا
        val ranked = TextMath.rankByFuzzy("نجم", targets)
        assertEquals(listOf(0 to 0.95, 2 to 0.95), ranked)
        // قصّ بالحد
        assertEquals(listOf(0 to 0.95), TextMath.rankByFuzzy("نجم", targets, limit = 1))
        // عتبة أعلى من كل الدرجات
        assertTrue(TextMath.rankByFuzzy("نجم", targets, threshold = 0.96).isEmpty())
        // حد صفري
        assertTrue(TextMath.rankByFuzzy("نجم", targets, limit = 0).isEmpty())
    }
}
