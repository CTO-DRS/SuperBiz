package com.superbiz.app

import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Currency
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Installment
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.JournalEntry
import com.superbiz.app.data.db.JournalLine
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.Rule
import com.superbiz.app.data.db.StockMove
import com.superbiz.app.data.db.Visit
import com.superbiz.app.domain.backup.BackupData
import com.superbiz.app.domain.backup.BackupTables
import com.superbiz.app.domain.backup.ImportStats
import com.superbiz.app.domain.backup.buildBackupJson
import com.superbiz.app.domain.backup.parseBackup
import com.superbiz.app.domain.backup.planImport
import com.superbiz.app.domain.backup.totalRows
import com.superbiz.app.domain.backup.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P13-b] اختبارات BackupKit النقية (JUnit4 بلا Android):
 * بناء نسخة صغيرة (كل جدول صفّاً أو صفّين بحقول null وLong متطرف وNaN وسلاسل بفواصل/
 * اقتباسات/أسطر) → دوران كامل build→parse مع مقارنة كل كيان حقلاً حقلاً، وNaN→null
 * بدلالته، ورفض الإصدار الأحدث/الصيغة الخاطئة بلا انهيار، وخطة الدمج (استيراد/تخطي/تالف).
 * [P33-P8] موجة القروش: مبالغ البذرة Long قروش (1050 = 10.5 ريال)، والتصدير يكتب "p8":true
 * وقروشاً صحيحة، وملف قديم بلا علامة بديناريّاته الريالية يُقرأ محوَّلاً عبر Money.toPiasters.
 */
class BackupKitTest {

    // بذرة ثابتة من كل جدول — تغطي: null nullable، Long متطرف، NaN، سلاسل تتطلب تهريباً
    // [P33-P8] كل مبالغ البذرة قروش Long (×100 من ريالها القديم) — الكميات/النسب تبقى Double
    private val data = BackupData(
        version = 1,
        exportedAt = 1_700_000_000_000L,
        parties = listOf(
            Party(
                id = 1, name = "أحمد، \"الموزع\"\nالرياض", phone = "0501", type = 2,
                note = "ملاحظة\\سطر", createdAt = 123L, archived = true, favorite = true,
                lat = 24.7, lng = 46.7
            ),
            Party(id = -5, name = "صف تالف") // معرف سالب → failed في خطة الدمج
        ),
        products = listOf(
            Product(
                id = 2, name = "منتج, ب\"فاصلة\"", sku = "SKU-1", barcode = "12\n34",
                unit = "قطعة", costPrice = 1050L, salePrice = 1500L, // [P33-P8] 10.5/15.0 ريال → قروش
                stockQty = Double.NaN, // NaN → يُكتب null → يعود بقيمته الافتراضية
                reorderLevel = 3.0, category = "أ", createdAt = 9_007_199_254_740_993L, archived = false
            )
        ),
        invoices = listOf(
            Invoice(
                id = 3, number = "INV-1", partyId = 1, type = 0, date = 100L, dueDate = 200L,
                subtotal = 9000L, discount = 500L, taxRate = 15.0, taxAmount = 1350L, // [P33-P8] قروش — taxRate نسبة تبقى Double
                total = 9850L, paid = 5000L, costTotal = 6000L, status = 1,
                currency = "SAR", fxRate = 1.0, note = "دفعة أولى"
            )
        ),
        invoiceItems = listOf(
            InvoiceItem(id = 4, invoiceId = 3, productId = null, desc = "بند, \"خاص\"", qty = 2.0, unitPrice = 4500L, discount = 100L) // [P33-P8] قروش — qty كميّة تبقى Double
        ),
        payments = listOf(
            Payment(id = 5, partyId = null, invoiceId = null, checkId = null, amount = 3000L, date = 150L, direction = 0, method = "CASH", note = "", planId = null) // [P33-P8] 30 ريال → 3000 قروش
        ),
        visits = listOf(
            Visit(id = 6, partyId = 1, visitedAt = 300L, lat = null, lng = null, note = "زيارة رقم \"1\"")
        ),
        expenses = listOf(
            Expense(id = 7, amount = 1225L, category = "نقل", note = "", date = 90L, createdAt = 91L) // [P33-P8] 12.25 ريال → 1225 قروش
        ),
        checks = listOf(
            CheckEntity(id = 8, number = "CH-9", partyId = 1, bank = "بنك الرياض", amount = 100_000L, issueDate = 10L, dueDate = 400L, direction = 0, status = 0, note = "") // [P33-P8] 1000 ريال → 100000 قروش
        ),
        plans = listOf(
            InstallmentPlan(id = 9, title = "خطة أقساط", partyId = 1, direction = 0, total = 30_000L, downPayment = 10_000L, financed = 20_000L, months = 2, startDate = 500L, currency = "SAR", note = "", createdAt = 501L, archived = false) // [P33-P8] 300/100/200 ريال → قروش
        ),
        installments = listOf(
            Installment(id = 10, planId = 9, seq = 1, amount = 10_000L, dueDate = 600L, paidAmount = 10_000L, paidDate = 601L, status = 1), // [P33-P8] 100 ريال → 10000 قروش
            Installment(id = 11, planId = 9, seq = 2, amount = 10_000L, dueDate = 700L, paidAmount = 0L, paidDate = null, status = 0)
        ),
        currencies = listOf(
            Currency(code = "SAR", nameAr = "ريال", nameEn = "Riyal", symbol = "ر.س", rateToBase = 1.0, isBase = true)
        ),
        rules = listOf(
            Rule(id = 12, kind = "DUE_REMIND", enabled = true, daysBefore = 3, lastRun = 0L)
        ),
        journal = listOf(
            JournalEntry(id = 13, date = 100L, memo = "قيد بيع, مع فاصلة", refType = null, refId = null)
        ),
        journalLines = listOf(
            JournalLine(id = 14, entryId = 13, account = "1020", debit = 9850L, credit = 0L, partyId = null, currency = "SAR", fxRate = 1.0) // [P33-P8] قروش — fxRate نسبة تبقى Double
        ),
        stockMoves = listOf(
            StockMove(id = 15, productId = 2, qty = -2.0, reason = "SALE", date = 100L, refType = null, refId = null, note = "")
        )
    )

    // ─── الدوران الكامل: build → parse → مقارنة كل الحقول ───

    @Test
    fun roundtrip_partiesAndProducts() {
        val out = parseBackup(buildBackupJson(data))
        assertNotNull(out.error, out.data)
        val d = out.data!!

        // مقارنة كيان-كيان (data class equality) — الطرف الأول بكل حقوله
        assertEquals(data.parties[0], d.parties[0])
        assertEquals(data.parties[1], d.parties[1])
        // الإحداثيات المحفوظة للطرف الأول تعود، وغيابها للثاني يبقى غياباً
        assertEquals(24.7, d.parties[0].lat!!, 0.0)
        assertEquals(46.7, d.parties[0].lng!!, 0.0)
        assertNull(d.parties[1].lat)
        assertNull(d.parties[1].lng)
    }

    @Test
    fun roundtrip_allEntitiesFieldByField() {
        val out = parseBackup(buildBackupJson(data))
        assertNotNull(out.error, out.data)
        val d = out.data!!

        assertEquals(2, d.parties.size)
        assertEquals(1, d.products.size)
        assertEquals(data.invoices, d.invoices)
        assertEquals(data.invoiceItems, d.invoiceItems)
        assertEquals(data.payments, d.payments)
        assertEquals(data.visits, d.visits)
        assertEquals(data.expenses, d.expenses)
        assertEquals(data.checks, d.checks)
        assertEquals(data.plans, d.plans)
        assertEquals(data.installments, d.installments) // يشمل paidDate: قيمة + null
        assertEquals(data.currencies, d.currencies)
        assertEquals(data.rules, d.rules)
        assertEquals(data.journal, d.journal) // refType/refId null يعودان null (وليس "")
        assertEquals(data.journalLines, d.journalLines)
        assertEquals(data.stockMoves, d.stockMoves)
    }

    @Test
    fun roundtrip_nanDoubleWrittenAsNullAndReadsAsDefault() {
        val json = buildBackupJson(data)
        // دلالة NaN: الرقم غير الشرعي يُكتب null صراحةً حتى لا يفسد JSON
        assertTrue("يجب أن يحوي stockQty:null", json.contains("\"stockQty\":null"))
        val out = parseBackup(json)
        val product = out.data!!.products[0]
        // عند القراءة: null → القيمة الافتراضية للعمود (غير القابل للـnull في القاعدة)
        assertEquals(0.0, product.stockQty, 0.0)
        // وبقية حقول المنتج نفسها — بما فيها Long المتطرف بدقة كاملة
        assertEquals(9_007_199_254_740_993L, product.createdAt)
        assertEquals("منتج, ب\"فاصلة\"", product.name)
    }

    @Test
    fun build_structureFormatVersionCountsAndTables() {
        val json = buildBackupJson(data)
        val root = parseJson(json) as Map<*, *>
        assertEquals("superbiz-backup", root["format"])
        assertEquals(1L, root["version"])
        assertEquals(1_700_000_000_000L, root["exportedAt"])
        // [P33-P8] علامة عالم القروش تُكتب دائماً أعلى الجذر — تمييزها الحتمي عن النسخ الريالية القديمة
        assertEquals(true, root["p8"])

        val counts = root["counts"] as Map<*, *>
        assertEquals(2L, counts["parties"])
        assertEquals(1L, counts["products"])
        assertEquals(2L, counts["installments"])

        val tables = root["tables"] as Map<*, *>
        assertTrue(
            "كل الجداول الخمسة عشر يجب أن تُكتب",
            BackupTables.ALL.all { tables.containsKey(it) }
        )
        val parties = tables["parties"] as List<*>
        assertEquals(2, parties.size)
        val p0 = parties[0] as Map<*, *>
        assertEquals(1L, p0["id"])
        assertTrue((p0["name"] as String).contains("أحمد"))
    }

    @Test
    fun totalRows_sumsAllTables() {
        // 2+1+1+1+1+1+1+1+1+2+1+1+1+1+1 = 17
        assertEquals(17, totalRows(data))
    }

    // ─── parseBackup: الرفض الصريح بلا انهيار ───

    @Test
    fun parse_versionNewerIsRejectedWithClearError() {
        val json = "{\"format\":\"superbiz-backup\",\"version\":2,\"exportedAt\":1,\"counts\":{},\"tables\":{}}"
        val out = parseBackup(json)
        assertNull(out.data)
        assertTrue("يجب أن يذكر أن النسخة أحدث: ${out.error}", out.error!!.contains("نسخة أحدث غير مدعومة"))
    }

    @Test
    fun parse_wrongFormatRejected() {
        // صيغة غريبة
        var out = parseBackup("{\"format\":\"something-else\",\"version\":1,\"exportedAt\":1}")
        assertNull(out.data)
        assertNotNull(out.error)
        // صيغة النسخ القديمة (format رقمي) ليست نسخة superbiz-backup
        out = parseBackup("{\"format\":2,\"exportedAt\":1}")
        assertNull(out.data)
        assertNotNull(out.error)
        // بلا format أصلاً
        out = parseBackup("{}")
        assertNull(out.data)
        // نص غير JSON أصلاً — لا رمي، فقط خطأ
        out = parseBackup("هذا ليس JSON")
        assertNull(out.data)
        assertNotNull(out.error)
    }

    @Test
    fun parse_versionLowerRejected() {
        val out = parseBackup("{\"format\":\"superbiz-backup\",\"version\":0,\"exportedAt\":1}")
        assertNull(out.data)
        assertNotNull(out.error)
    }

    @Test
    fun parse_missingTablesYieldsEmptyBackup() {
        val out = parseBackup("{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":5}")
        assertNull(out.error)
        val d = out.data!!
        assertTrue(d.parties.isEmpty())
        assertTrue(d.products.isEmpty())
        assertTrue(d.invoices.isEmpty())
        assertEquals(5L, d.exportedAt)
    }

    @Test
    fun parse_unknownTableIgnoredAndBomTolerated() {
        val json = "\uFEFF{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":1," +
            "\"tables\":{\"parties\":[{\"id\":9,\"name\":\"غريب\"}],\"future_table\":[1,2,3]}}"
        val out = parseBackup(json)
        assertNull(out.error, out.error)
        assertEquals(1, out.data!!.parties.size)
        assertEquals(9L, out.data!!.parties[0].id)
    }

    @Test
    fun parse_malformedRowFailsWithTableAndIndex() {
        val json = "{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":1," +
            "\"tables\":{\"parties\":[{\"id\":1},{\"id\":\"ليس رقماً\"}]}}"
        val out = parseBackup(json)
        assertNull(out.data)
        assertNotNull(out.error)
        assertTrue("يجب أن يسمّي الجدول: ${out.error}", out.error!!.contains("parties"))
        assertTrue("يجب أن يسمّي رقم الصف: ${out.error}", out.error!!.contains("1"))
    }

    @Test
    fun parse_missingFieldsFallBackToEntityDefaults() {
        val json = "{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":1," +
            "\"tables\":{\"products\":[{\"id\":7,\"name\":\"ناقص الحقول\"}]}}"
        val out = parseBackup(json)
        assertNull(out.error, out.error)
        val p = out.data!!.products[0]
        assertEquals(7L, p.id)
        assertEquals("ناقص الحقول", p.name)
        assertEquals("", p.sku)
        assertEquals("قطعة", p.unit)
        // [P33-P8] غياب حقل مبلغ → 0 قروش (المقابل الصحيح لافتراض 0.0 ريال في القارئ القديم)
        assertEquals(0L, p.costPrice)
        assertEquals(0L, p.salePrice)
        assertEquals(false, p.archived)
        assertEquals(0L, p.createdAt)
    }

    // ─── [P33-P8] عالَما النسخ: قروش (علامة "p8") مقابل ريال قديم (بلا علامة) ───

    @Test
    fun build_p8FlagTrueAndMoneySerializedAsLongPiasters() {
        val json = buildBackupJson(data)
        val root = parseJson(json) as Map<*, *>
        // علامة عالم القروش أعلى الجذر
        assertEquals(true, root["p8"])
        // المبالغ تُكتب أعداداً صحيحة (قروش) لا كسوراً ريالية — «10.5 ريال» يخرج 1050
        val product = ((root["tables"] as Map<*, *>)["products"] as List<*>)[0] as Map<*, *>
        assertEquals(1050L, product["costPrice"])
        assertEquals(1500L, product["salePrice"])
        assertTrue("المبلغ يجب أن يُكتب قرشاً صحيحاً لا كسراً", json.contains("\"costPrice\":1050"))
        assertFalse("لا كسر عشري لحقل مبلغ في ملف قروش", json.contains("\"costPrice\":10.5"))
        // الكميات تبقى Double خاماً (NaN كُتب null — بدلالته في اختبار NaN أعلاه)
        assertTrue(json.contains("\"stockQty\":null"))
    }

    @Test
    fun parse_legacyBackupWithoutP8FlagConvertsRiyalsToPiasters() {
        // نسخة ≤ : لا علامة "p8" والمبالغ مخزّنة ريالاً عشرياً — كل حقل مبلغ يُقرأ
        // عبر Money.toPiasters (12.5 ريال → 1250 قروش) — والكميات/النسب تبقى كما هي
        val legacy = "{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":9," +
            "\"tables\":{" +
            "\"products\":[{\"id\":1,\"name\":\"قديم\",\"costPrice\":10.5,\"salePrice\":15.0,\"stockQty\":4.0}]," +
            "\"invoices\":[{\"id\":2,\"number\":\"L-1\",\"subtotal\":90.0,\"discount\":5.0,\"taxRate\":15.0,\"taxAmount\":13.5,\"total\":98.5,\"paid\":50.0,\"costTotal\":60.0}]," +
            "\"payments\":[{\"id\":3,\"amount\":12.5,\"date\":1,\"direction\":0,\"method\":\"CASH\",\"note\":\"\"}]," +
            "\"expenses\":[{\"id\":4,\"amount\":12.25,\"category\":\"نقل\",\"date\":1}]," +
            "\"journal_lines\":[{\"id\":5,\"entryId\":1,\"account\":\"1020\",\"debit\":98.5,\"credit\":0.0,\"currency\":\"SAR\",\"fxRate\":1.0}]}}"
        val out = parseBackup(legacy)
        assertNull(out.error, out.error)
        val d = out.data!!
        // ريال عشري → قروش صحيح
        assertEquals(1050L, d.products[0].costPrice)
        assertEquals(1500L, d.products[0].salePrice)
        assertEquals(9000L, d.invoices[0].subtotal)
        assertEquals(1350L, d.invoices[0].taxAmount) // نصف الريال يقرَّب HALF_UP قرشاً كاملاً
        assertEquals(9850L, d.invoices[0].total)
        assertEquals(5000L, d.invoices[0].paid)
        assertEquals(1250L, d.payments[0].amount)
        assertEquals(1225L, d.expenses[0].amount)
        assertEquals(9850L, d.journalLines[0].debit)
        assertEquals(0L, d.journalLines[0].credit)
        // الكميات والنسب لا تُحوَّل — Double خام بلا تمس (قاعدة 6 في p8-api)
        assertEquals(4.0, d.products[0].stockQty, 0.0)
        assertEquals(15.0, d.invoices[0].taxRate, 0.0)
        assertEquals(1.0, d.journalLines[0].fxRate, 0.0)
    }

    @Test
    fun parse_p8FlaggedFileWithFractionalMoneyValueFailsTheRow() {
        // ملف قروش بعلامته لا يقبل كسراً عشرياً في حقل مبلغ — صف تالف صريح، لا قَطّ صامت
        val json = "{\"format\":\"superbiz-backup\",\"version\":1,\"p8\":true,\"exportedAt\":1," +
            "\"tables\":{\"payments\":[{\"id\":1,\"amount\":12.5,\"date\":1,\"direction\":0,\"method\":\"CASH\",\"note\":\"\"}]}}"
        val out = parseBackup(json)
        assertNull(out.data)
        assertTrue("يجب أن يسمّي الجدول: ${out.error}", out.error!!.contains("payments"))
        assertTrue("يجب أن يذكر رفض الكسر العشري: ${out.error}", out.error!!.contains("قروش"))
    }

    // ─── planImport: خطة الدمج النقية ───

    @Test
    fun planImport_emptyDatabaseImportsAllAndCountsFailed() {
        val stats = planImport(data, existingPartyIds = emptySet(), existingProductIds = emptySet())
        assertEquals(1, stats.importedParties) // الطرف ذو id=-5 تالف لا يُستورد
        assertEquals(0, stats.skippedParties)
        assertEquals(1, stats.importedProducts)
        assertEquals(0, stats.skippedProducts)
        assertEquals(1, stats.failed)
        assertEquals(BackupTables.ALL.size, stats.tablesFound.size) // 15 جدولاً بترتيب ثابت
        assertEquals(2, stats.tablesFound["parties"])
        assertEquals(1, stats.tablesFound["products"])
    }

    @Test
    fun planImport_existingIdsAreSkipped() {
        val stats = planImport(data, existingPartyIds = setOf(1L), existingProductIds = setOf(2L))
        assertEquals(0, stats.importedParties)
        assertEquals(1, stats.skippedParties)
        assertEquals(0, stats.importedProducts)
        assertEquals(1, stats.skippedProducts)
        assertEquals(1, stats.failed) // id=-5 يبقى تالفاً
    }

    @Test
    fun planImport_mixedCase() {
        val stats = planImport(data, existingPartyIds = setOf(1L), existingProductIds = emptySet())
        assertEquals(0, stats.importedParties)   // 1 موجود → تخطٍّ
        assertEquals(1, stats.skippedParties)
        assertEquals(1, stats.importedProducts)  // 2 غير موجود → إضافة
        assertEquals(1, stats.failed)            // -5 تالف
    }

    @Test
    fun planImport_duplicateIdInsideFileCountedSkipped() {
        val dup = data.copy(
            parties = listOf(Party(id = 7, name = "أول"), Party(id = 7, name = "ثانٍ"))
        )
        val stats = planImport(dup, emptySet(), emptySet())
        assertEquals(1, stats.importedParties)  // الأول يُستورد
        assertEquals(1, stats.skippedParties)   // المكرر يُتخطى (آمن: آخر صفّ يحمل نفس المفتاح)
        assertEquals(0, stats.failed)
    }

    // ─── [P14-b] planImport الزيارات والمصروفات (جدولا أرشيف معزول بلا مفاتيح أجنبية) ───

    @Test
    fun planImport_v2_emptyDatabaseImportsVisitsAndExpenses() {
        // استدعاء بثلاث وسيطات فقط (نمط ) — الوسيطتان الجديدتان تصفرّان افتراضياً،
        // وهذا هو مسار ملف النسخ القديم: تحليل بلا تغيير ثم استيراد الجداول الجديدة كاملة
        val stats = planImport(data, existingPartyIds = emptySet(), existingProductIds = emptySet())
        assertEquals(1, stats.importedVisits)    // id=6 غير موجود → يُستورد
        assertEquals(0, stats.skippedVisits)
        assertEquals(1, stats.importedExpenses)  // id=7 غير موجود → يُستورد
        assertEquals(0, stats.skippedExpenses)
        assertEquals(1, stats.failed)            // party id=-5 يبقى التالف الوحيد
        assertEquals(1, stats.tablesFound["visits"])
        assertEquals(1, stats.tablesFound["expenses"])
        assertEquals(BackupTables.ALL.size, stats.tablesFound.size) // الجداول 15 بلا تغيير
    }

    @Test
    fun planImport_v2_existingVisitAndExpenseIdsSkippedIndependently() {
        val stats = planImport(
            data,
            existingPartyIds = setOf(1L),      // الطرف الموجود يُتخطى — تحقق مستقل عن الجدولين الجديدين
            existingProductIds = emptySet(),
            existingVisitIds = setOf(6L),
            existingExpenseIds = setOf(7L)
        )
        assertEquals(0, stats.importedVisits)
        assertEquals(1, stats.skippedVisits)
        assertEquals(0, stats.importedExpenses)
        assertEquals(1, stats.skippedExpenses)
        assertEquals(0, stats.importedParties)
        assertEquals(1, stats.skippedParties)
        assertEquals(1, stats.importedProducts) // المنتج غير موجود → يُستورد كالعادة
        assertEquals(1, stats.failed)
    }

    @Test
    fun planImport_v2_duplicateAndBadIdsCountAcrossFourTables() {
        // المكرر داخل الملف والمعرفات التالفة تُحسب على الجداول الأربعة في عدّاد failed واحد
        val messy = BackupData(
            version = 1,
            exportedAt = 1L,
            visits = listOf(
                Visit(id = 50, partyId = 1, visitedAt = 1L),
                Visit(id = 50, partyId = 1, visitedAt = 2L), // مكرر داخل الملف → skipped
                Visit(id = -1, partyId = 1, visitedAt = 3L)  // معرف سالب → failed
            ),
            expenses = listOf(
                Expense(id = 0, amount = 500L, date = 1L),   // [P33-P8] 5 ريال → 500 قروش — معرف صفر → failed
                Expense(id = 51, amount = 700L, date = 2L)   // [P33-P8] 7 ريال → 700 قروش
            )
        )
        val stats = planImport(messy, emptySet(), emptySet(), emptySet(), emptySet())
        assertEquals(1, stats.importedVisits)
        assertEquals(1, stats.skippedVisits)
        assertEquals(1, stats.importedExpenses)
        assertEquals(0, stats.skippedExpenses)
        assertEquals(2, stats.failed) // سالب الزيارات + صفر المصروفات — failed يجمع الأربعة
        assertEquals(BackupTables.ALL.size, stats.tablesFound.size)
    }

    @Test
    fun importStats_defaultFieldsKeepOldCallSitesCompatible() {
        // بناء موضعي بستة وسائط كما في — حقول v2 الأربعة تصفر افتراضياً فلا ينكسر أي مستدعٍ قديم
        val s = ImportStats(1, 2, 3, 4, 5, mapOf("parties" to 2))
        assertEquals(1, s.importedParties)
        assertEquals(2, s.skippedParties)
        assertEquals(3, s.importedProducts)
        assertEquals(4, s.skippedProducts)
        assertEquals(5, s.failed)
        assertEquals(0, s.importedVisits)
        assertEquals(0, s.skippedVisits)
        assertEquals(0, s.importedExpenses)
        assertEquals(0, s.skippedExpenses)
    }

    @Test
    fun planImport_v2_backupWithoutArchiveRowsYieldsZeroStats() {
        // ملف بلا صفوف زيارات/مصروفات (نسخ قديمة أو جداول فارغة) — أصفار آمنة لا اختبار null
        val noArchive = data.copy(visits = emptyList(), expenses = emptyList())
        val stats = planImport(noArchive, emptySet(), emptySet())
        assertEquals(0, stats.importedVisits)
        assertEquals(0, stats.skippedVisits)
        assertEquals(0, stats.importedExpenses)
        assertEquals(0, stats.skippedExpenses)
        assertEquals(0, stats.tablesFound["visits"])
        assertEquals(0, stats.tablesFound["expenses"])
    }

    // ─── [P15-b] planImport الشيكات وخطط الأقساط والأقساط (بقاعدة اليتيم المفروضة بالتدقيق) ───
    // تدقيق Entities.kt: checks وinstallment_plans لهما ForeignKey باتجاه parties (RESTRICT)،
    // وinstallments له ForeignKey باتجاه installment_plans (CASCADE) — لذلك:
    // اليتيم (أبوه غير موجود بالقاعدة ولا ضمن المستورد فعلاً) يُتخطى skipped لا failed،
    // وترتيب الحلقات في الخطة هو نفسه ترتيب التنفيذ (خطط قبل أقساطها).

    @Test
    fun planImport_v3_emptyDatabaseImportsAllThreeNewTables() {
        // نداء بثلاث وسيطات فقط (نمط ) — الوسائط الخمسة الأخيرة تصفرّ افتراضياً،
        // وهذه هي لغة ملف النسخ القديم: تحليل بلا تغيير ثم استيراد الجداول الجديدة كاملة.
        // أباء الملف كلهم داخل الملف نفسه (الطرف 1 يُستورد فيجيز شيكه وخطةً وأقساطها)
        val stats = planImport(data, existingPartyIds = emptySet(), existingProductIds = emptySet())
        assertEquals(1, stats.importedChecks)         // id=8 وطرفه 1 مستورد
        assertEquals(0, stats.skippedChecks)
        assertEquals(1, stats.importedPlans)          // id=9 وطفلها 1 مستورد
        assertEquals(0, stats.skippedPlans)
        assertEquals(2, stats.importedInstallments)   // 10 و11 بخطة 9 المستوردة
        assertEquals(0, stats.skippedInstallments)
        assertEquals(1, stats.failed)                 // صف الطرف التالف (-5) يبقى الوحيد
        assertEquals(BackupTables.ALL.size, stats.tablesFound.size) // الجداول 15 بلا تغيير
    }

    @Test
    fun planImport_v3_existingIdsSkippedPerTable() {
        val stats = planImport(
            data,
            existingPartyIds = setOf(1L),        // الطرف موجود → أبناؤه تبقى صالحة الأبوة
            existingProductIds = emptySet(),
            existingCheckIds = setOf(8L),
            existingPlanIds = setOf(9L),
            existingInstallmentIds = setOf(10L)
        )
        assertEquals(0, stats.importedChecks)
        assertEquals(1, stats.skippedChecks)
        assertEquals(0, stats.importedPlans)
        assertEquals(1, stats.skippedPlans)
        // القسط 10 موجود → تخطٍّ، والقسط 11 جديد لكن أبوه (الخطة 9) موجود بالقاعدة → يُستورد
        assertEquals(1, stats.importedInstallments)
        assertEquals(1, stats.skippedInstallments)
        assertEquals(1, stats.failed)
    }

    @Test
    fun planImport_v3_orphanInstallmentsSkippedNotFailed() {
        // أقساط يتيمة: planId غائب عن القاعدة وعن خطط الملف، وplanId=0 — تخطٍّ لا فشل:
        // يتيم بنيوي ليس صفاً تالفاً، وإلا كان إدراجه سيرفع استثناء القيد فيتنهار كل شيء
        val orphans = BackupData(
            version = 1,
            exportedAt = 1L,
            parties = listOf(Party(id = 1, name = "طرف")),
            plans = listOf(
                InstallmentPlan(id = 9, title = "خطة", partyId = 1, total = 3000L, financed = 3000L, months = 3, startDate = 1L) // [P33-P8] 30 ريال → 3000 قروش
            ),
            installments = listOf(
                Installment(id = 20, planId = 999, seq = 1, amount = 500L, dueDate = 1L), // [P33-P8] قروش — أبٌ غائب كلياً
                Installment(id = 21, planId = 0, seq = 2, amount = 600L, dueDate = 2L),   // معرف أبٍ غير شرعي
                Installment(id = 22, planId = 9, seq = 3, amount = 700L, dueDate = 3L)    // سليمة للمقارنة
            )
        )
        val stats = planImport(orphans, emptySet(), emptySet())
        assertEquals(1, stats.importedInstallments) // planId=9 داخل الملف يُستورد
        assertEquals(2, stats.skippedInstallments)  // اليتيمتان
        assertEquals(0, stats.failed)
    }

    @Test
    fun planImport_v3_orphanChecksAndPlansSkippedWithCascade() {
        // [P15-b] نتيجة التدقيق (تصحيح لافتراض الموجة): checks وinstallment_plans ليسا
        // جدولي أرشيف معزلين — لهما ForeignKey RESTRICT باتجاه parties، فصفٌّ يشير إلى
        // طرفٍ غائب يُتخطى وإلا انكسر إدراجه وتراجع المعاملة كلها. والتسلسل متسق:
        // أقساط الخطة اليتيمة يتيمة هي الأخرى (معرف أبّها ليس في الموجود ولا في المستورد)
        val orphans = BackupData(
            version = 1,
            exportedAt = 1L,
            parties = listOf(Party(id = 1, name = "طرف موجود")),
            checks = listOf(
                CheckEntity(id = 30, number = "CH-1", partyId = 777, amount = 900L, issueDate = 1L, dueDate = 2L), // [P33-P8] قروش — يتيم
                CheckEntity(id = 31, number = "CH-2", partyId = 1, amount = 800L, issueDate = 1L, dueDate = 2L)   // سليم
            ),
            plans = listOf(
                InstallmentPlan(id = 40, title = "خطة يتيمة", partyId = 888, total = 900L, financed = 900L, months = 1, startDate = 1L), // [P33-P8] قروش — يتيمة
                InstallmentPlan(id = 41, title = "خطة سليمة", partyId = 1, total = 900L, financed = 900L, months = 1, startDate = 1L)   // سليمة
            ),
            installments = listOf(
                Installment(id = 42, planId = 40, seq = 1, amount = 300L, dueDate = 1L), // [P33-P8] قروش — أبّها تُرك يتيمًا → يتيمة بالتسلسل
                Installment(id = 43, planId = 41, seq = 1, amount = 300L, dueDate = 1L)  // أبّها مستورد → تُستورد
            )
        )
        val stats = planImport(orphans, emptySet(), emptySet())
        assertEquals(1, stats.importedChecks)
        assertEquals(1, stats.skippedChecks)      // طرف 777 غائب
        assertEquals(1, stats.importedPlans)
        assertEquals(1, stats.skippedPlans)       // طرف 888 غائب
        assertEquals(1, stats.importedInstallments)
        assertEquals(1, stats.skippedInstallments) // تسلسل يتامي الخطة 40
        assertEquals(0, stats.failed)              // لا شيء منها تالف — كلها تخطٍّ بنيوي
    }

    @Test
    fun planImport_v3_duplicateAndBadIdsCountAcrossNewTables() {
        // المكرر داخل الملف والمعرفات التالفة تُحسب على الجداول الثلاثة في عدّاد failed واحد
        val messy = BackupData(
            version = 1,
            exportedAt = 1L,
            parties = listOf(Party(id = 1, name = "طرف")),
            checks = listOf(
                CheckEntity(id = 50, number = "أ", partyId = 1, amount = 100L, issueDate = 1L, dueDate = 2L),  // [P33-P8] قروش
                CheckEntity(id = 50, number = "ب", partyId = 1, amount = 200L, issueDate = 1L, dueDate = 2L), // مكرر → skipped
                CheckEntity(id = -3, number = "ج", partyId = 1, amount = 300L, issueDate = 1L, dueDate = 2L)  // سالب → failed
            ),
            plans = listOf(
                InstallmentPlan(id = 0, title = "تالفة", partyId = 1, total = 100L, financed = 100L, months = 1, startDate = 1L), // [P33-P8] قروش — صفر → failed
                InstallmentPlan(id = 51, title = "سليمة", partyId = 1, total = 100L, financed = 100L, months = 1, startDate = 1L)
            ),
            installments = listOf(
                Installment(id = 52, planId = 51, seq = 1, amount = 100L, dueDate = 1L),  // [P33-P8] قروش
                Installment(id = 52, planId = 51, seq = 2, amount = 100L, dueDate = 2L), // مكرر → skipped
                Installment(id = -9, planId = 51, seq = 3, amount = 100L, dueDate = 3L)  // سالب → failed
            )
        )
        val stats = planImport(messy, emptySet(), emptySet())
        assertEquals(1, stats.importedChecks)        // الأول يفوز في التكرار
        assertEquals(1, stats.skippedChecks)
        assertEquals(1, stats.importedPlans)
        assertEquals(0, stats.skippedPlans)
        assertEquals(1, stats.importedInstallments)
        assertEquals(1, stats.skippedInstallments)
        assertEquals(3, stats.failed) // -3 و0 و-9 — failed عدّاد واحد يجمع الجداول السبعة
    }

    @Test
    fun importStats_v3FieldsDefaultToZeroForOldCallSites() {
        // بناء موضعي بستة وسائط كما في — حقول v2 الأربعة وv3 الستة تصفر افتراضياً
        // فلا ينكسر أي مستدعٍ قديم ولا أي مقارنة اختبارات قديمة
        val s = ImportStats(1, 2, 3, 4, 5, mapOf("parties" to 2))
        assertEquals(0, s.importedChecks)
        assertEquals(0, s.skippedChecks)
        assertEquals(0, s.importedPlans)
        assertEquals(0, s.skippedPlans)
        assertEquals(0, s.importedInstallments)
        assertEquals(0, s.skippedInstallments)
    }

    @Test
    fun parse_fileWithoutNewTablesParsesAndRestoresWithZeroNewChanges() {
        // ملف بأسلوب قديم فاقد لمفاتيح checks/installment_plans/installments كلياً عن tables
        // — يُحلَّل بلا خطأ (الجدول الغائب = قائمة فارغة) وخطة الدمج تُصفّرها بلا فشل:
        // مسار / القديم يمر كما هو ولا يتغير شيء في سلوكه
        val json = "{\"format\":\"superbiz-backup\",\"version\":1,\"exportedAt\":7," +
            "\"tables\":{\"parties\":[{\"id\":1,\"name\":\"طرف\"}]}}"
        val out = parseBackup(json)
        assertNull(out.error, out.error)
        val d = out.data!!
        assertTrue(d.checks.isEmpty())
        assertTrue(d.plans.isEmpty())
        assertTrue(d.installments.isEmpty())
        val stats = planImport(d, emptySet(), emptySet())
        assertEquals(1, stats.importedParties)
        assertEquals(0, stats.importedChecks)
        assertEquals(0, stats.skippedChecks)
        assertEquals(0, stats.importedPlans)
        assertEquals(0, stats.skippedPlans)
        assertEquals(0, stats.importedInstallments)
        assertEquals(0, stats.skippedInstallments)
        assertEquals(0, stats.failed)
    }
}
