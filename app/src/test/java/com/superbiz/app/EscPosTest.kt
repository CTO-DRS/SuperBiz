package com.superbiz.app

import com.superbiz.app.print.EscPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosTest {

    private fun sample() = EscPos.Receipt(
        businessName = "متجري",
        title = "فاتورة بيع • INV-1",
        dateText = "13/09/2026 14:25",
        partyName = "أحمد",
        lines = listOf(
            EscPos.ItemLine("عصير برتقال", "2", "5.00", "10.00"),
            EscPos.ItemLine("خبز", "1", "2.50", "2.50")
        ),
        totals = listOf(
            "المجموع" to "12.50",
            "الضريبة" to "1.88",
            "الإجمالي" to "14.38 ر.س"
        ),
        statusText = "مدفوعة",
        footer = "شكراً لتعاملكم معنا"
    )

    @Test
    fun build_startsInitAndEndsWithCut() {
        val b = EscPos.build(sample(), 32)
        assertEquals(0x1B.toByte(), b[0])
        assertEquals(0x40.toByte(), b[1])
        // آخر 4 بايتات: أمر القص الجزئي GS V B 0
        val n = b.size
        assertEquals(0x1D.toByte(), b[n - 4])
        assertEquals(0x56.toByte(), b[n - 3])
        assertEquals(0x42.toByte(), b[n - 2])
        assertEquals(0x00.toByte(), b[n - 1])
    }

    @Test
    fun build_containsUtf8Text() {
        // المسار الافتراضي أصبح CP1256 (متوافق الطابعات الحرارية) —
        // فاختبار النص العربي كـ UTF-8 صار على المسار الصريح MODE_UTF8،
        // وفيه يبقى الخرج مطابقاً حرفياً للمسار القديم
        val b8 = EscPos.build(sample(), 32, EscPos.MODE_UTF8)
        val all8 = String(b8, Charsets.UTF_8)
        assertTrue(all8.contains("متجري"))
        assertTrue(all8.contains("عصير برتقال"))
        assertTrue(all8.contains("14.38"))
    }

    @Test
    fun build_cp1256_encodesArabicAndKeepsAscii() {
        // المسار الافتراضي CP1256 — العربية بايتات 1256 غير قابلة لفك UTF-8،
        // والأرقام/اللاتينية ASCII تبقى بايتاتها نفسها في الوضعين
        val b = EscPos.build(sample(), 32, EscPos.MODE_CP1256)
        val all = String(b, Charsets.ISO_8859_1) // فك خام بايت-ببايت
        assertTrue(all.contains("14.38"))
        // بايت «م» في CP1256 = 0xE3 (227) — يظهر في اسم المتجر
        assertTrue(b.any { it == 0xE3.toByte() })
        // فك UTF-8 مباشرة لا يعيد النص العربي (دليل على اختيار جدولة CP1256 فعلاً)
        assertFalse(String(b, Charsets.UTF_8).contains("متجري"))
    }

    @Test
    fun arabicText_reversesArabicRunKeepsDigits() {
        // انعكاس الجملة العربية للطباعة LTR — الأرقام تبقى بترتيبها الطبيعي
        val s = "اجمالي 14.38"
        val out = EscPos.arabicText(s).toString(Charsets.ISO_8859_1)
        // الأرقام موجودة بالترتيب الصحيح بعد الانعكاس
        assertTrue(out.contains("14.38"))
    }

    @Test
    fun row_padsToWidthByChars() {
        val r = EscPos.row("المجموع", "100.00", 24)
        assertEquals(24, r.length)
        assertTrue(r.endsWith("100.00"))
        assertTrue(r.startsWith("المجموع"))
    }

    @Test
    fun row_overlongKeepsSingleSpace() {
        val r = EscPos.row("تسمية طويلة جداً لا تتناسب", "999.99", 10)
        assertEquals("تسمية طويلة جداً لا تتناسب 999.99", r)
    }

    @Test
    fun build_widerPaperLongerSeparator() {
        val s32 = EscPos.build(sample(), 32)
        val s48 = EscPos.build(sample(), 48)
        assertTrue(s48.size > s32.size)
    }

    @Test
    fun build_boldCommandWrapsLastTotal() {
        val b = EscPos.build(sample(), 32)
        val all = b.toList()
        val boldOn = listOf<Byte>(0x1B, 0x45, 0x01)
        val idx = all.indexOfSub(boldOn)
        // يوجد أمر عريض بعد منطقة الأصناف (لإجمالي أخير)
        assertTrue(idx > 0)
    }

    private fun List<Byte>.indexOfSub(sub: List<Byte>): Int {
        for (i in 0..size - sub.size) {
            var ok = true
            for (j in sub.indices) if (this[i + j] != sub[j]) { ok = false; break }
            if (ok) return i
        }
        return -1
    }

    // ─── [P6-M31 إصلاح] اختبارات المُشكّل والترتيب البصري ───

    @Test
    fun shape_connectsArabicLettersIntoPresentationForms() {
        val shaped = EscPos.shape("فاتورة")
        // الطول محفوظ (لا لام-ألف في الكلمة) وكل حرف تحوّل لشكل عرض FE70–FEFF
        assertEquals("فاتورة".length, shaped.length)
        assertTrue(shaped.none { it in '\u0621'..'\u064A' })
        assertTrue(shaped.all { it in '\uFB50'..'\uFEFF' })
    }

    @Test
    fun shape_lamAlefCollapsesToSingleLigature() {
        val shaped = EscPos.shape("لا")
        assertEquals(1, shaped.length)
        assertTrue(shaped[0] == '\uFEFB' || shaped[0] == '\uFEFC')
    }

    @Test
    fun visualLine_valueSegmentComesBeforeArabicLabel() {
        val v = EscPos.visualLine("المجموع: 15.00")
        val firstArabic = v.indexOfFirst { it in '\u0600'..'\u06FF' || it in '\uFB50'..'\uFEFF' }
        assertTrue(firstArabic >= 0)
        // القيمة الرقمية تُطبع قبل (يسار) المقطع العربي — تطابق المعاينة RTL
        assertTrue(v.indexOf("15.00") < firstArabic)
    }

    @Test
    fun arabicText_foldsFormsToBaseInVisualOrder() {
        val decoded = String(EscPos.arabicText("فاتورة"), charset("windows-1256"))
        // بعد الطي تبقى الحروف الأساسية بالترتيب البصري المعكوس (تُرسم LTR فتُقرأ RTL)
        assertEquals("ةروتاف", decoded)
    }

    @Test
    fun arabicText_lamAlefFoldsToVisualPair() {
        val decoded = String(EscPos.arabicText("لا"), charset("windows-1256"))
        // الألف يُرسم يسار اللام في الترتيب البصري
        assertEquals("ال", decoded)
    }

    @Test
    fun arabicText_latinStaysUntouched() {
        // نص بلا عربية يمر حرفياً (ASCII 1:1) — سلوك المسار القديم محفوظ
        assertEquals("Total: 12.50", String(EscPos.arabicText("Total: 12.50"), charset("windows-1256")))
    }
}
