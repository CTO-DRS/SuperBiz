package com.superbiz.app.repo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.NoteTemplateEntity
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.SignatureEntity
import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.data.db.StatementTemplateEntity
import com.superbiz.app.data.db.StampEntity
import com.superbiz.app.data.db.Visit
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.BackupRestoreRepo
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.data.repo.SeedDefaults
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.backup.BACKUP_TABLE_COUNT
import com.superbiz.app.domain.backup.BACKUP_VERSION
import com.superbiz.app.domain.backup.BackupData
import com.superbiz.app.domain.backup.BackupTables
import com.superbiz.app.domain.backup.buildBackupJson
import com.superbiz.app.domain.backup.parseBackup
import com.superbiz.app.domain.backup.totalRows
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * [P37-T-D] جدران المستودعات — الدوران التقاطعي للنسخ الاحتياطي على قاعدة Room حقيقية في الذاكرة
 * (نمط TransactionPathsTest حرفياً: AndroidJUnit4 + sdk 34 + inMemory + allowMainThreadQueries + runBlocking).
 *
 * يغطي عقد المسارين المثبتين في المشروع على قواعد حقيقية (وليس فقط على مستوى الحقول النقي في
 * BackupStatementsP34Test / BackupRoundtripP7Test / BackupContractP36Test):
 *
 * 1) المسار الاستبدالي [BackupRepo] — الصيغة {"app":"SuperBiz","format":3}: تصدير exportJson ثم
 *    استعادة importFrom داخل معاملة واحدة بعد wipeAll (استبدال كامل، كل الجداول الـ25).
 * 2) المسار الدمجي [BackupRestoreRepo] — الصيغة {"format":"superbiz-backup","version":1,"p8":true}:
 *    exportTo (التصدير عبر BackupKit) ثم importFrom دمجياً (الجداول الأرشيفية الآمنة فقط).
 *
 * ── ملاحظة عقد موثقة (رُصدت أثناء كتابة هذا الجدار — لا إصلاح في نطاق المهمة) ──
 * المسارُين صيغتا ملف مستقلتان تماماً بحكم التصميم (رأس BackupRestoreRepo يوثق الاستقلال:
 * «هذا الصنف مستقل تماماً عن BackupRepo») — ملف exportJson لا يُحلَّل بـparseBackup،
 * وملف buildBackupJson لا يُقبل في BackupRepo.importFrom (فرض app/format يرفضه).
 * لذا «الدوران التقاطعي» الحقيقي الممكن هو دوران كامل لكل مسار على قاعدة ثانية منفصلة،
 * وهو ما تختبره الاختبارات أدناه بمطابقة العدادات والحقول المالية بالقروش على القاعدتين.
 *
 * [P33-P8] كل المبالغ قروش Long — كل التأكيدات المالية مساواة صحيحة تامة بلا Double ولا عتبات.
 * كل الأزمنة صريحة ثابتة (t0 ومشتقاته) — لا System.currentTimeMillis في البذرة.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupCrossRoundtripTest {

    private lateinit var ctx: Context
    private lateinit var dbA: AppDatabase   // قاعدة المصدر (البذر والتصدير)
    private lateinit var dbB: AppDatabase   // القاعدة الثانية (الاستعادة الدمجية/الاستبدالية)
    private lateinit var ledgerA: LedgerRepo
    private lateinit var ledgerB: LedgerRepo
    private lateinit var inventoryA: InventoryRepo
    private lateinit var invoicesA: InvoiceRepo
    private lateinit var checksA: ChecksRepo
    private lateinit var installmentsA: InstallmentRepo
    private lateinit var expensesA: ExpenseRepo
    private lateinit var settings: SettingsRepo
    private lateinit var backupA: BackupRepo
    private lateinit var backupB: BackupRepo

    private val t0 = 1_700_000_000_000L
    private val DAY = 86_400_000L

    // معرفات البذرة — تُملأ في seedAll
    private var cust = 0L
    private var supp = 0L
    private var prodA = 0L
    private var prodB = 0L
    private var invA = 0L
    private var checkA = 0L
    private var planA = 0L
    private var expenseA = 0L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        dbA = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbB = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerA = LedgerRepo(dbA)
        ledgerB = LedgerRepo(dbB)
        inventoryA = InventoryRepo(dbA, ledgerA)
        invoicesA = InvoiceRepo(dbA)
        checksA = ChecksRepo(dbA, ledgerA)
        installmentsA = InstallmentRepo(dbA, ledgerA)
        expensesA = ExpenseRepo(dbA, ledgerA)
        settings = SettingsRepo(ctx)
        backupA = BackupRepo(ctx, dbA, settings)
        backupB = BackupRepo(ctx, dbB, settings)
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    // ───────────────────────── بذرة حتمية شاملة ─────────────────────────

    /** بذر قاعدة المصدر بكل عائلات الكيانات: أطراف/منتجات/فواتير/شيكات/أقساط/مصروفات/زيارات/كشوف/قواعد/عملات */
    private suspend fun seedAll() {
        cust = ledgerA.saveParty(Party(name = "عميل الجولة", type = 0))
        supp = ledgerA.saveParty(Party(name = "مورد الجولة", type = 1))
        prodA = inventoryA.saveProduct(
            Product(name = "منتج أ", costPrice = 2_000L, salePrice = 3_000L, stockQty = 10.0, barcode = "P37-A"))
        prodB = inventoryA.saveProduct(
            Product(name = "منتج ب", costPrice = 1_500L, salePrice = 2_500L, stockQty = 4.0, barcode = "P37-B"))
        // فاتورة بيع: أصناف + مخزون + قيد مزدوج — كلها في القاعدة قبل النسخ
        invA = invoicesA.save(
            Invoice(number = "INV-P37-1", partyId = cust, type = 0,
                date = t0, dueDate = t0 + 14 * DAY,
                subtotal = 10_000L, taxAmount = 0L, total = 10_000L, costTotal = 2_000L),
            listOf(InvoiceItem(invoiceId = 0, productId = prodA, desc = "منتج أ", qty = 2.0, unitPrice = 5_000L)))
        ledgerA.addDebt(ledgerA.party(cust)!!, 6_000L, t0 + DAY, "دين الجولة")
        ledgerA.addPayment(ledgerA.party(cust)!!, 2_500L, t0 + 2 * DAY, 0, "CASH", invA)
        checkA = checksA.save(
            CheckEntity(number = "CH-P37-1", partyId = cust, bank = "بنك الجولة",
                amount = 15_000L, issueDate = t0, dueDate = t0 + 30 * DAY, direction = 0, status = 0))
        planA = installmentsA.createPlan(
            title = "اتفاق الجولة", partyId = cust, direction = 0,
            total = 120_000L, downPayment = 20_000L, months = 10,
            startDate = t0, currency = "SAR", note = "خطة الجولة", today = t0)
        installmentsA.pay(installmentsA.installmentsOf(planA)[0], today = t0 + DAY)             // قسط كامل
        installmentsA.pay(installmentsA.installmentsOf(planA)[1], 4_000L, today = t0 + 2 * DAY) // جزئي
        expenseA = expensesA.add(5_000L, "إيجار", "إيجار المحل", t0 + 3 * DAY)
        dbA.visits().insert(Visit(partyId = cust, visitedAt = t0 + 4 * DAY, lat = 24.7, lng = 46.6, note = "زيارة الجولة"))
        // [P46-W1] بذر ولاء وكوبونات — الدوران يجريها حقلاً حقلاً على المسارين
        dbA.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(
            partyId = cust, invoiceId = invA, delta = 12L, reason = "EARN", note = "نقاط الجولة", createdAt = t0 + DAY))
        dbA.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(
            partyId = cust, invoiceId = null, delta = -2L, reason = "REDEEM", note = "استبدال", createdAt = t0 + 2 * DAY))
        dbA.coupons().upsert(com.superbiz.app.data.db.CouponEntity(
            code = "ROUND10", kind = com.superbiz.app.domain.LoyaltyP46.KIND_PERCENT,
            percent = 10.0, expiresAt = t0 + 30 * DAY, maxUses = 5, note = "كوبون الجولة"))
        SeedDefaults.ensure(dbA)   // عملات (6) + قواعد (4)
        seedStatementSystem()
    }

    /** بذر جداول منظومة الكشوف الثمانية بمعرفات صريحة — على طرف موجود (RESTRICT راضٍ) */
    private suspend fun seedStatementSystem() {
        dbA.statementTemplates().upsert(
            StatementTemplateEntity(id = 1, name = "قالب الجولة", baseTemplateId = "CLASSIC",
                configJson = "{\"cols\":[\"date\",\"amount\"]}", isDefault = true, favorite = true,
                createdAt = t0, updatedAt = t0))
        dbA.signatures().upsert(
            SignatureEntity(id = 2, name = "التوقيع", jobTitle = null, imagePath = "filesDir/sign.png",
                isDefault = true, active = true, createdAt = t0))
        dbA.stamps().upsert(
            StampEntity(id = 3, name = "الختم", imagePath = "filesDir/stamp.png",
                isDefault = false, active = true, createdAt = t0))
        dbA.noteTemplates().upsert(
            NoteTemplateEntity(id = 4, title = "تحية", body = "يرجى السداد خلال 30 يوماً", isDefault = false))
        dbA.statements().insert(
            StatementEntity(id = 5, statementNumber = "STATEMENT-2026-000001",
                verificationId = "SB-ST-20260924-000001", partyId = cust,
                fromTs = t0, toTs = t0 + DAY, templateId = "1", currency = "SAR",
                contentHash = "hash-p37", filePath = "filesDir/pdfs/s1.pdf", note = null,
                createdAt = t0, lang = "AR"))
        dbA.statements().insertDelivery(
            StatementDeliveryEntity(id = 6, statementId = 5, channel = "SMTP", status = "SENT",
                attempts = 1, lastError = null, sentAt = t0 + 1_000L, scheduledFor = null,
                dedupKey = "delivery:5:SMTP", lastAttemptAt = t0 + 1_000L))
        dbA.statementRules().upsert(
            StatementRuleEntity(id = 7, name = "قاعدة شهرية", enabled = true,
                partyMode = "ALL", partyIdsJson = "", frequency = "MONTHLY", weekday = null,
                dayOfMonth = 1, hour = 8, minute = 0, periodPreset = "PREVIOUS_MONTH",
                templateId = null, signatureId = 2L, stampId = 3L, channel = "EMAIL",
                eventFlagsJson = null, threshold = 50_000L, lastRunAt = null, nextRunAt = t0 + DAY))
        dbA.auditLog().insert(
            AuditLogEntity(id = 8, actor = "owner", action = "STATEMENT_ISSUE",
                details = "statement=STATEMENT-2026-000001", ts = t0))
    }

    private fun tempFile(tag: String, text: String): File =
        File(ctx.cacheDir, "p37-$tag-${System.nanoTime()}.json").apply { writeText(text) }

    /** عدادات الجداول الـ25 — نفس أسماء BackupTables — تُقارن قبل/بعد الاستعادة */
    private suspend fun tableCounts(db: AppDatabase): List<Pair<String, Int>> = listOf(
        "parties" to db.parties().exportOnce().size,
        "products" to db.products().exportOnce().size,
        "invoices" to db.invoices().exportOnce().size,
        "invoice_items" to db.invoiceItems().allOnce().size,
        "checks" to db.checks().allOnce().size,
        "installment_plans" to db.installments().plansExport().size,
        "installments" to db.installments().allInstallments().size,
        "payments" to db.payments().since(0).size,
        "journal_entries" to db.journal().allEntries().size,
        "journal_lines" to db.journal().allLines().size,
        "rules" to db.rules().count(),
        "currencies" to db.currencies().count(),
        "expenses" to db.expenses().count(),
        "stock_moves" to db.stockMoves().allMoves().size,
        "visits" to db.visits().allOnce().size,
        BackupTables.STATEMENT_TEMPLATES to db.statementTemplates().allOnce().size,
        BackupTables.SIGNATURES to db.signatures().allOnce().size,
        BackupTables.STAMPS to db.stamps().allOnce().size,
        BackupTables.NOTE_TEMPLATES to db.noteTemplates().allOnce().size,
        BackupTables.STATEMENTS to db.statements().listAll(Int.MAX_VALUE).size,
        BackupTables.STATEMENT_DELIVERIES to db.statements().allDeliveries().size,
        BackupTables.STATEMENT_RULES to db.statementRules().allOnce().size,
        BackupTables.AUDIT_LOG to db.auditLog().allLogs().size,
        // [P46-W1] جولة 7 — جدولا الولاء والكوبونات (اكتمال 25/25)
        BackupTables.LOYALTY_ENTRIES to db.loyalty().allOnce().size,
        BackupTables.COUPONS to db.coupons().allOnce().size
    )

    /** لقطة كل الجداول الـ25 من قاعدة حية — نفس طريقة BackupRestoreRepo.exportTo الداخلية */
    private suspend fun collectBackupData(db: AppDatabase): BackupData = BackupData(
        version = BACKUP_VERSION,
        exportedAt = t0,
        parties = db.parties().exportOnce(),
        products = db.products().exportOnce(),
        invoices = db.invoices().exportOnce(),
        invoiceItems = db.invoiceItems().allOnce(),
        payments = db.payments().since(0),
        visits = db.visits().allOnce(),
        expenses = db.expenses().allOnce(),
        checks = db.checks().allOnce(),
        plans = db.installments().plansExport(),
        installments = db.installments().allInstallments(),
        currencies = db.currencies().allOnce(),
        rules = db.rules().allOnce(),
        journal = db.journal().allEntries(),
        journalLines = db.journal().allLines(),
        stockMoves = db.stockMoves().allMoves(),
        statementTemplates = db.statementTemplates().allOnce(),
        signatures = db.signatures().allOnce(),
        stamps = db.stamps().allOnce(),
        noteTemplates = db.noteTemplates().allOnce(),
        statements = db.statements().listAll(Int.MAX_VALUE),
        statementDeliveries = db.statements().allDeliveries(),
        statementRules = db.statementRules().allOnce(),
        auditLog = db.auditLog().allLogs(),
        // [P46-W1] جولة 7 — جدولا الولاء والكوبونات
        loyaltyEntries = db.loyalty().allOnce(),
        coupons = db.coupons().allOnce()
    )

    /** ميزان الدفتر: (مدين، دائن) لكل الأسطر — يُقارن قبل/بعد الاستعادة */
    private suspend fun journalTotals(db: AppDatabase): Pair<Long, Long> {
        val lines = db.journal().allLines()
        return lines.sumOf { it.debit } to lines.sumOf { it.credit }
    }

    // ═══════════════ 1) الدوران الكامل عبر المسار الاستبدالي (BackupRepo) ═══════════════

    // ─── exportJson → قاعدة ثانية جديدة → importFrom → عدادات الجداول الـ25 متطابقة ───
    @Test
    fun `دوران_كامل_عبر_المسار_الاستبدالي_يطابق_عدادات_الجداول_الـ٢٥_على_قاعدة_ثانية`() = runBlocking {
        seedAll()
        val before = tableCounts(dbA)
        assertEquals(25, before.size)

        val exported = backupA.exportJson()
        val f = tempFile("full-counters", exported.toString(2))
        assertTrue("الاستعادة على القاعدة الثانية فشلت", backupB.importFrom(Uri.fromFile(f)))

        assertEquals("عدادات الجداول اختلفت بعد الاستعادة", before, tableCounts(dbB))
        f.delete()
        Unit // JUnit4 يشترط void
    }

    // ─── الحقول المالية قروش تامة: فاتورة/منتج/شيك/خطة/أقساط/مصروف/أطراف تعود حرفياً ───
    @Test
    fun `دوران_كامل_يطابق_الحقول_المالية_بالقروش_حقل_بحقل`() = runBlocking {
        seedAll()
        val invBefore = dbA.invoices().byId(invA)!!
        val prodBefore = dbA.products().byId(prodA)!!
        val checkBefore = dbA.checks().byId(checkA)!!
        val planBefore = dbA.installments().planById(planA)!!
        val instsBefore = dbA.installments().allInstallments().sortedBy { it.id }
        val expensesBefore = dbA.expenses().allOnce()
        val partiesBefore = dbA.parties().exportOnce()

        val f = tempFile("money-fields", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        // [P33-P8] مساواة كيان-بكيان — أي انحراف قروش/تاريخ/حالة يفشل هنا
        assertEquals(invBefore, dbB.invoices().byId(invA))
        assertEquals(prodBefore, dbB.products().byId(prodA))
        assertEquals(checkBefore, dbB.checks().byId(checkA))
        assertEquals(planBefore, dbB.installments().planById(planA))
        assertEquals(instsBefore, dbB.installments().allInstallments().sortedBy { it.id })
        assertEquals(expensesBefore, dbB.expenses().allOnce())
        assertEquals(partiesBefore, dbB.parties().exportOnce())

        // عينات مالية صريحة بالقروش (الإجمالي والمسدد والمفتوح تامة)
        assertEquals(10_000L, dbB.invoices().byId(invA)!!.total)
        assertEquals(2_500L, dbB.invoices().byId(invA)!!.paid)
        assertEquals(7_500L, dbB.invoices().byId(invA)!!.open)
        assertEquals(15_000L, dbB.checks().byId(checkA)!!.amount)
        assertEquals(100_000L, dbB.installments().planById(planA)!!.financed)
        f.delete()
        Unit
    }

    // ─── الدفتر يبقى متوازناً وأرصدة الأطراف والنقد تعود كما كانت بعد الاستعادة ───
    @Test
    fun `دوران_كامل_يعيد_الدفتر_متوازنا_وأرصدة_الأطراف_والنقد_كما_كانت`() = runBlocking {
        seedAll()
        val balancesBefore = ledgerA.balances()
        val cashBefore = ReportsRepo(dbA).cashBalance()
        val sumsBefore = dbA.journal().accountSums().sortedBy { it.account }
        assertTrue(balancesBefore.isNotEmpty())

        val f = tempFile("journal-balance", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        // التوازن الكلي: مجموع المدين = مجموع الدائن بمساواة تامة بالقروش
        val (d, c) = journalTotals(dbB)
        assertEquals("الدفتر المستعاد غير متوازن!", d, c)
        // ميزان الحسابات مطابق (مجمّع SQL نفسه على القاعدتين)
        assertEquals(sumsBefore, dbB.journal().accountSums().sortedBy { it.account })
        // أرصدة الأطراف من الدفتر مطابقة
        val balancesAfter = ledgerB.balances()
        for ((pid, bal) in balancesBefore) {
            assertEquals("رصيد الطرف $pid اختلف بعد الاستعادة", bal, balancesAfter[pid]!!)
        }
        assertEquals(cashBefore, ReportsRepo(dbB).cashBalance())
        f.delete()
        Unit
    }

    // ─── جداول الكشوف الثمانية تعود حرفياً (حقل-بحقل عبر مساواة الكيانات) ───
    @Test
    fun `جداول_الكشوف_الثمانية_تعود_حرفيا_بعد_الاستعادة_الاستبدالية`() = runBlocking {
        seedAll()
        val f = tempFile("statement-tables", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        assertEquals(dbA.statementTemplates().allOnce(), dbB.statementTemplates().allOnce())
        assertEquals(dbA.signatures().allOnce(), dbB.signatures().allOnce())
        assertEquals(dbA.stamps().allOnce(), dbB.stamps().allOnce())
        assertEquals(dbA.noteTemplates().allOnce(), dbB.noteTemplates().allOnce())
        assertEquals(dbA.statements().listAll(Int.MAX_VALUE), dbB.statements().listAll(Int.MAX_VALUE))
        assertEquals(dbA.statements().allDeliveries(), dbB.statements().allDeliveries())
        assertEquals(dbA.statementRules().allOnce(), dbB.statementRules().allOnce())
        assertEquals(dbA.auditLog().allLogs(), dbB.auditLog().allLogs())
        // [P33-P8] threshold قروش في القاعدة المستعادة
        assertEquals(50_000L, dbB.statementRules().allOnce().single().threshold)
        f.delete()
        Unit
    }

    // ─── الدفعات تعود بربطها (planId/invoiceId/checkId) — المفتاح [P7-X1 إصلاح] ───
    @Test
    fun `الدفعات_تعود_بربطها_بالخطط_والفواتير_والطرق_المالية`() = runBlocking {
        seedAll()
        val paysBefore = dbA.payments().since(0).sortedBy { it.id }

        val f = tempFile("payments-link", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        val paysAfter = dbB.payments().since(0).sortedBy { it.id }
        assertEquals(paysBefore, paysAfter)
        assertTrue(paysAfter.any { it.planId == planA && it.method == "INSTALLMENT_DOWN" && it.amount == 20_000L })
        assertTrue(paysAfter.any { it.planId == planA && it.method == "INSTALLMENT" && it.amount == 10_000L })
        assertTrue(paysAfter.any { it.invoiceId == invA && it.method == "CASH" && it.amount == 2_500L })
        assertTrue(paysAfter.any { it.method == "DEBT" && it.amount == 6_000L })
        f.delete()
        Unit
    }

    // ─── الأعلام المحفوظة تعود: أرشفة الخطة + مفضلة الطرف + إحداثياته ───
    @Test
    fun `الأعلام_المحفوظة_تعود_أرشفة_الخطة_ومفضلة_الطرف_وموقعه`() = runBlocking {
        seedAll()
        // [P36-BK]/: العلم المؤرشف يجب أن يُصدَّر ويُستعاد
        dbA.installments().archivePlan(planA)
        // [P20-FIX agent18]: المفضلة والموقع الجغرافي يجب أن يُصدَّرا ويُستعادا
        dbA.parties().setFavorite(cust, true)
        dbA.parties().setLocation(cust, 24.7, 46.6)

        val f = tempFile("flags", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        assertTrue(dbB.installments().plansExport().first { it.id == planA }.archived)
        val restoredParty = dbB.parties().byId(cust)!!
        assertTrue(restoredParty.favorite)
        assertEquals(24.7, restoredParty.lat!!, 0.0) // إحداثيات (كمية جغرافية) — ليست مالاً
        assertEquals(46.6, restoredParty.lng!!, 0.0)
        f.delete()
        Unit
    }

    // ─── «مسح قاعدة ثانية» حرفياً: بذرة دخيلة ثم wipeAll ثم استعادة كاملة نظيفة ───
    @Test
    fun `الاستعادة_الاستبدالية_على_قاعدة_ممسوحة_تستبدل_البذرة_الدخيلة_كاملة`() = runBlocking {
        seedAll()
        val partiesBefore = dbA.parties().exportOnce()
        val f = tempFile("wipe-replace", backupA.exportJson().toString(2))

        // القاعدة الثانية تُلوَّث بصفوف دخيلة ثم تُمسح مسحاً كاملاً قبل الاستعادة
        ledgerB.saveParty(Party(name = "طرف دخيل_${System.nanoTime()}"))
        dbB.maintenance().wipeAll()
        assertEquals(0, dbB.parties().count())

        assertTrue("الاستعادة على القاعدة الممسوحة فشلت", backupB.importFrom(Uri.fromFile(f)))
        assertEquals("بذرة القاعدة الثانية تطابق المصدر", partiesBefore, dbB.parties().exportOnce())
        assertTrue(dbB.parties().exportOnce().none { it.name.startsWith("طرف دخيل") })
        assertEquals(tableCounts(dbA), tableCounts(dbB))
        f.delete()
        Unit
    }

    // ═══════════════ 2) الاتجاه العكسي: تصدير واستعادة المسار الدمجي (BackupRestoreRepo) ═══════════════

    // ─── BackupRestoreRepo له تصدير فعلي exportTo — يعيد النجاح بعدد الصفوف الكلي ───
    @Test
    fun `الاتجاه_العكسي_المسار_الدمجي_له_تصدير_exportTo_يعيد_عدد_الصفوف_الكلي`() = runBlocking {
        seedAll()
        val restore = BackupRestoreRepo(dbA, ctx)
        val data = collectBackupData(dbA)
        val f = tempFile("merge-export", "سيُكتب-فوق")

        val result = restore.exportTo(Uri.fromFile(f))
        assertTrue("exportTo فشل: $result", result.isSuccess)
        assertEquals("العدد المعاد لا يطابق totalRows", totalRows(data), result.getOrNull()!!)

        // الملف المكتوب يُحلَّل بمحلل الصيغة الدمجية ويطابق اللقطة
        val parsed = parseBackup(f.readText())
        assertNull(parsed.error)
        assertEquals(data.parties, parsed.data!!.parties)
        assertEquals(data.checks, parsed.data!!.checks)
        assertEquals(data.plans, parsed.data!!.plans)
        assertEquals(data.statements, parsed.data!!.statements)
        f.delete()
        Unit
    }

    // ─── ملف المسار الدمجي يُستورد دمجياً على قاعدة ثانية: العدادات والحقول المالية ───
    @Test
    fun `ملف_المسار_الدمجي_يستورد_على_قاعدة_ثانية_بعدادات_مطابقة_وحقول_قروش_تامة`() = runBlocking {
        seedAll()
        val f = tempFile("merge-import", buildBackupJson(collectBackupData(dbA)))

        val stats = BackupRestoreRepo(dbB, ctx).importFrom(Uri.fromFile(f)).getOrNull()!!
        // العدادات على قاعدة فارغة: كل صف يُستورد ولا شيء يتخطى ولا صف تالف
        assertEquals(2, stats.importedParties)
        assertEquals(2, stats.importedProducts)
        assertEquals(1, stats.importedChecks)
        assertEquals(1, stats.importedPlans)
        assertEquals(10, stats.importedInstallments)
        assertEquals(1, stats.importedVisits)
        assertEquals(1, stats.importedExpenses)
        assertEquals(1, stats.importedStatementTemplates)
        assertEquals(1, stats.importedSignatures)
        assertEquals(1, stats.importedStamps)
        assertEquals(1, stats.importedNoteTemplates)
        assertEquals(1, stats.importedStatements)
        assertEquals(1, stats.importedStatementDeliveries)
        assertEquals(1, stats.importedStatementRules)
        assertEquals(1, stats.importedAuditLog)
        assertEquals(0, stats.failed)

        // [P33-P8] الحقول المالية على القاعدة الثانية تامة
        assertEquals(15_000L, dbB.checks().byId(checkA)!!.amount)
        assertEquals(120_000L, dbB.installments().planById(planA)!!.total)
        assertEquals(20_000L, dbB.installments().planById(planA)!!.downPayment)
        assertEquals(100_000L, dbB.installments().planById(planA)!!.financed)
        assertEquals(10_000L, dbB.installments().installmentsOf(planA).first { it.seq == 1 }.paidAmount)
        assertEquals(4_000L, dbB.installments().installmentsOf(planA).first { it.seq == 2 }.paidAmount)
        assertEquals(5_000L, dbB.expenses().allOnce().single().amount)

        // نطاق الدمج الموثق: الفواتير/القيود/الدفعات/العملات خارج نطاقه عمداً (أرشيف آمن فقط)
        assertEquals(0, dbB.invoices().count())
        assertTrue(dbB.journal().allLines().isEmpty())
        f.delete()
        Unit
    }

    // ─── الدمج على قاعدة ممتلئة: الموجود يُتخطى (لا يُعاد كتابته) والغائب يُدرج ───
    @Test
    fun `الاستعادة_الدمجية_على_قاعدة_ممتلئة_تتخطى_الموجود_وتدرج_الغائب`() = runBlocking {
        seedAll()
        // القاعدة الثانية لديها طرف بمعرف 1 (يطابق معرف عميل المصدر) — يجب ألا يُكتب فوقه
        ledgerB.saveParty(Party(name = "طرف القاعدة الثانية", type = 0))
        val f = tempFile("merge-skip", buildBackupJson(collectBackupData(dbA)))

        val stats = BackupRestoreRepo(dbB, ctx).importFrom(Uri.fromFile(f)).getOrNull()!!
        assertEquals(1, stats.skippedParties)   // المعرف الموجود يتخطى
        assertEquals(1, stats.importedParties)  // المعرف الغائب يُدرج
        assertEquals(1, stats.importedChecks)   // شيك المصدر طرفه id=1 وهو موجود أصلاً → غير يتيم
        assertEquals("طرف القاعدة الثانية", dbB.parties().byId(1L)!!.name)
        assertEquals("مورد الجولة", dbB.parties().byId(2L)!!.name)
        f.delete()
        Unit
    }

    // ─── الدمج متكرر بأمان: الاستيراد الثاني يتخطى كل شيء ولا يضاعف صفاً واحداً ───
    @Test
    fun `الاستعادة_الدمجية_متكررة_بأمان_ولا_تضاعف_الصفوف`() = runBlocking {
        seedAll()
        val f = tempFile("merge-twice", buildBackupJson(collectBackupData(dbA)))
        val repoB = BackupRestoreRepo(dbB, ctx)

        val first = repoB.importFrom(Uri.fromFile(f)).getOrNull()!!
        assertEquals(2, first.importedParties)

        val second = repoB.importFrom(Uri.fromFile(f)).getOrNull()!!
        assertEquals(0, second.importedParties)
        assertEquals(2, second.skippedParties)
        assertEquals(0, second.importedChecks)
        assertEquals(1, second.skippedChecks)
        assertEquals(0, second.importedPlans)
        assertEquals(0, second.importedInstallments)
        assertEquals(0, second.importedStatements)
        assertEquals(0, second.failed)

        // العدادات الفعلية لم تتغير بعد الجولة الثانية
        assertEquals(2, dbB.parties().count())
        assertEquals(1, dbB.checks().allOnce().size)
        assertEquals(10, dbB.installments().allInstallments().size)
        f.delete()
        Unit
    }

    // ═══════════════ 3) قاعدة اليتيم البنيوية على القاعدة الحقيقية ═══════════════

    // ─── المسار الدمجي: كشف بطرف غائب يتخطى وتسليمه يتخطى بالتسلسل — ولا صف تالف ───
    @Test
    fun `قاعدة_اليتيم_عبر_المسار_الدمجي_كشف_بطرف_غائب_يتخطى_وتسليمه_يتخطى`() = runBlocking {
        val orphan = BackupData(
            version = BACKUP_VERSION, exportedAt = t0,
            statements = listOf(
                StatementEntity(id = 9, statementNumber = "STATEMENT-2026-000009",
                    verificationId = "SB-ST-20260924-000009", partyId = 999L, // طرف غائب
                    fromTs = t0, toTs = t0 + DAY, templateId = "1", currency = "SAR",
                    contentHash = "h9", filePath = "filesDir/pdfs/s9.pdf", note = null,
                    createdAt = t0, lang = "AR")),
            statementDeliveries = listOf(
                StatementDeliveryEntity(id = 10, statementId = 9, channel = "SMTP", status = "PENDING",
                    attempts = 0, lastError = null, sentAt = null, scheduledFor = null,
                    dedupKey = "delivery:9:SMTP", lastAttemptAt = null))
        )
        val f = tempFile("orphan-merge", buildBackupJson(orphan))
        val stats = BackupRestoreRepo(dbB, ctx).importFrom(Uri.fromFile(f)).getOrNull()!!

        assertEquals(1, stats.skippedStatements)           // يتيم الأطراف
        assertEquals(1, stats.skippedStatementDeliveries)  // يتيم بالتسلسل (CASCADE عقلياً)
        assertEquals(0, stats.importedStatements)
        assertEquals(0, stats.importedStatementDeliveries)
        assertEquals(0, stats.failed)                      // يتيم بنيوي — لا صف تالف
        // على القاعدة الحقيقية: لا كشف ولا تسليم أُدرجا فعلاً
        assertTrue(dbB.statements().listAll(Int.MAX_VALUE).isEmpty())
        assertTrue(dbB.statements().allDeliveries().isEmpty())
        f.delete()
        Unit
    }

    // ─── المسار الاستبدالي: قارئ BackupRepo يدافع بدوره — كشف بطرف غائب يتخطى وتسليمه ───
    @Test
    fun `قاعدة_اليتيم_عبر_المسار_الاستبدالي_كشف_بطرف_غائب_يتخطى_وتسليمه_يتخطى`() = runBlocking {
        // ملف بصيغة المسار الاستبدالي (app/format 3) يحمل طرفاً واحداً وكشفاً يتيم طرفه (777) وتسليمه
        val legacy = """
            {"app":"SuperBiz","format":3,
             "parties":[{"id":1,"name":"طرف الملف","phone":"","type":0,"note":"",
              "createdAt":1,"archived":false,"favorite":false,"lat":null,"lng":null}],
             "statements":[{"id":9,"statementNumber":"STATEMENT-2026-000009",
              "verificationId":"SB-ST-20260924-000009","partyId":777,
              "fromTs":1,"toTs":2,"templateId":"1","currency":"SAR","contentHash":"h9",
              "filePath":"filesDir/pdfs/s9.pdf","note":null,"createdAt":1,"lang":"AR"}],
             "statement_deliveries":[{"id":10,"statementId":9,"channel":"SMTP","status":"PENDING",
              "attempts":0,"lastError":null,"sentAt":null,"scheduledFor":null,
              "dedupKey":"delivery:9:SMTP","lastAttemptAt":null}]}
        """.trimIndent()
        val f = tempFile("orphan-legacy", legacy)
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        assertEquals(1, dbB.parties().count())                              // الطرف أُدخل
        assertTrue(dbB.statements().listAll(Int.MAX_VALUE).isEmpty())       // الكشف اليتيم تُخطّى
        assertTrue(dbB.statements().allDeliveries().isEmpty())              // تسليمه تُخطّى بالتسلسل
        f.delete()
        Unit
    }

    // ─── المقابل: نفس الملف بطرف موجود يعيد الكشف والتسليم كاملين (حارس اليتيم لا يلمس السليم) ───
    @Test
    fun `الكشف_السليم_بطرف_موجود_يستعاد_كاملا_عبر_المسار_الاستبدالي`() = runBlocking {
        val sound = """
            {"app":"SuperBiz","format":3,
             "parties":[{"id":1,"name":"طرف الملف","phone":"","type":0,"note":"",
              "createdAt":1,"archived":false,"favorite":false,"lat":null,"lng":null}],
             "statements":[{"id":9,"statementNumber":"STATEMENT-2026-000009",
              "verificationId":"SB-ST-20260924-000009","partyId":1,
              "fromTs":1,"toTs":2,"templateId":"1","currency":"SAR","contentHash":"h9",
              "filePath":"filesDir/pdfs/s9.pdf","note":null,"createdAt":1,"lang":"AR"}],
             "statement_deliveries":[{"id":10,"statementId":9,"channel":"SMTP","status":"SENT",
              "attempts":1,"lastError":null,"sentAt":100,"scheduledFor":null,
              "dedupKey":"delivery:9:SMTP","lastAttemptAt":100}]}
        """.trimIndent()
        val f = tempFile("sound-statement", sound)
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        val st = dbB.statements().listAll(Int.MAX_VALUE).single()
        assertEquals("STATEMENT-2026-000009", st.statementNumber)
        assertEquals(1L, st.partyId)
        val dl = dbB.statements().allDeliveries().single()
        assertEquals(9L, dl.statementId)
        assertEquals("SENT", dl.status)
        f.delete()
        Unit
    }

    // ═══════════════ 4) wipeAll + SeedDefaults ═══════════════

    // ─── wipeAll يمسح الجداول الـ25 كلها: كل عداد = صفر بعد المسح ───
    @Test
    fun `wipeAll_يمسح_الجداول_الـ٢٥_كلها`() = runBlocking {
        seedAll()
        assertTrue(tableCounts(dbA).all { it.second > 0 }) // البذرة تعبئ كل جدول

        dbA.maintenance().wipeAll()

        val after = tableCounts(dbA)
        assertEquals(25, after.size)
        val nonEmpty = after.filter { it.second != 0 }
        assertTrue("جداول بقيت غير ممسحة: $nonEmpty", nonEmpty.isEmpty())
        Unit
    }

    // ─── SeedDefaults.ensure يعيد العملات والقواعد بعد المسح — وهو آمن للتكرار ───
    @Test
    fun `SeedDefaults_ensure_يعيد_العملات_والقواعد_بعد_المسح_بلا_تكرار`() = runBlocking {
        seedAll()
        dbA.maintenance().wipeAll()
        assertEquals(0, dbA.currencies().count())
        assertEquals(0, dbA.rules().count())

        SeedDefaults.ensure(dbA)

        val currencies = dbA.currencies().allOnce()
        assertEquals(6, currencies.size)
        assertEquals("SAR", currencies.single { it.isBase }.code)
        val kinds = dbA.rules().allOnce().map { it.kind }.toSet()
        assertEquals(setOf("DUE_REMIND", "CHECK_REMIND", "LOW_STOCK", "AUTO_BACKUP"), kinds)

        // آمن للتكرار (idempotent): نداء ثانٍ لا يضاعف
        SeedDefaults.ensure(dbA)
        assertEquals(6, dbA.currencies().count())
        assertEquals(4, dbA.rules().count())
        Unit
    }

    // ═══════════════ 5) توافق النسخ القديمة (ريال → قروش) ═══════════════

    // ─── ملف قديم (صيغة 2 — مبالغ ريال Double) يستورد ويُحوَّل بقرش على القاعدة الحقيقية ───
    @Test
    fun `ملف_قديم_ريالي_يستورد_ويحول_بقرش_عبر_المسار_الاستبدالي`() = runBlocking {
        val legacy = """
            {"app":"SuperBiz","format":2,
             "parties":[{"id":1,"name":"عميل قديم","phone":"","type":0,"note":"",
              "createdAt":1,"archived":false,"favorite":false,"lat":null,"lng":null}],
             "products":[{"id":1,"name":"منتج قديم","sku":"","barcode":"","unit":"قطعة",
              "costPrice":30.0,"salePrice":45.555,"stockQty":5.0,"reorderLevel":0.0,
              "category":"","archived":false,"createdAt":1}],
             "invoices":[{"id":1,"number":"INV-OLD","partyId":1,"type":0,"date":1,"dueDate":2,
              "subtotal":12.5,"discount":0.25,"taxRate":0,"taxAmount":0,"total":12.5,
              "paid":2.75,"costTotal":0,"status":1,"currency":"SAR","fxRate":1,"note":""}]}
        """.trimIndent()
        val f = tempFile("legacy-riyal", legacy)
        assertTrue("استيراد الملف القديم فشل", backupB.importFrom(Uri.fromFile(f)))

        // [P33-P8] Money.toPiasters بقرش: 12.5→1250، 0.25→25، 2.75→275، 30.0→3000، 45.555→4556 (HALF_UP)
        val inv = dbB.invoices().exportOnce().single()
        assertEquals(1_250L, inv.subtotal)
        assertEquals(25L, inv.discount)
        assertEquals(1_250L, inv.total)
        assertEquals(275L, inv.paid)
        val prod = dbB.products().exportOnce().single()
        assertEquals(3_000L, prod.costPrice)
        assertEquals(4_556L, prod.salePrice)

        // بعد الاستعادة يعمل البذر: العملات والقواعد عادت (الملف القديم لا يحملها)
        assertEquals(6, dbB.currencies().count())
        assertEquals(4, dbB.rules().count())
        f.delete()
        Unit
    }

    // ═══════════════ 6) عقد BACKUP_TABLE_COUNT واستقلال الصيغتين ═══════════════

    // ─── BACKUP_TABLE_COUNT متسق مع الجداول المكتوبة فعلاً في exportJson وحقول BackupData ───
    @Test
    fun `BACKUP_TABLE_COUNT_متسق_مع_الجداول_المكتوبة_فعلا_في_exportJson`() = runBlocking {
        seedAll()
        val exported = backupA.exportJson()

        // الثابت المعلن
        assertEquals(25, BACKUP_TABLE_COUNT)
        assertEquals(25, BackupTables.ALL.size)
        // كل جدول من العقد المرجعي مكتوب فعلاً في الملف (لا اسم غائب)
        for (table in BackupTables.ALL) {
            assertTrue("الجدول \"$table\" غائب عن exportJson رغم العقد 25/25", exported.has(table))
        }
        // الربط بالانعكاس: حقول BackupData الناقلة للجداول (عدا version/exportedAt والساكنات) = الثابت
        val tableFields = BackupData::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers).not() }
            .filter { it.name !in setOf("version", "exportedAt") }
        assertEquals(BACKUP_TABLE_COUNT, tableFields.size)
        Unit
    }

    // ─── الصيغتان مستقلتان بحكم التصميم: ملف كل مسار يرفضه محلل/مستورد المسار الآخر ───
    @Test
    fun `الصيغتان_مستقلتان_ملف_كل_مسار_يرفضه_مسار_الآخر_بلا_فساد`() = runBlocking {
        seedAll()
        // ملف المسار الاستبدالي لا يُحلَّل بمحلل المسار الدمجي (parseBackup)
        val exportedText = backupA.exportJson().toString(2)
        val parsed = parseBackup(exportedText)
        assertNull("ملف exportJson لا يجب أن يُحلَّل كصيغة دمجية", parsed.data)
        assertNotNull(parsed.error)

        // ولا يُستورد عبر BackupRestoreRepo.importFrom
        val f1 = tempFile("cross-1", exportedText)
        assertTrue(BackupRestoreRepo(dbB, ctx).importFrom(Uri.fromFile(f1)).isFailure)

        // والعكس: ملف المسار الدمجي يرفضه BackupRepo.importFrom (فرض app=SuperBiz)
        val f2 = tempFile("cross-2", buildBackupJson(collectBackupData(dbA)))
        assertFalse(backupB.importFrom(Uri.fromFile(f2)))
        // والرفض بلا أثر جانبي: القاعدة الثانية ما تزال فارغة تماماً
        assertEquals(0, dbB.parties().count())
        f1.delete(); f2.delete()
        Unit
    }

    // ─── الصيغة الحالية 3 (عالم القروش) في الملف، وصيغة أحدث تُرفض كلياً ───
    @Test
    fun `exportJson_يكتب_الصيغة_٣_القروشية_والصيغة_الأحدث_ترفض`() = runBlocking {
        seedAll()
        assertEquals(3, BackupRepo.FORMAT_VERSION)
        assertEquals(3, backupA.exportJson().getInt("format"))

        // ملف بصيغة أحدث من التطبيق → رفض صريح بلا أي كتابة
        val newer = JSONObject()
            .put("app", "SuperBiz")
            .put("format", 4)
            .put("parties", JSONArray().put(JSONObject().put("id", 1).put("name", "طرف مستقبلي")))
        val f = tempFile("newer-format", newer.toString())
        assertFalse("صيغة أحدث يجب أن تُرفض", backupB.importFrom(Uri.fromFile(f)))
        assertEquals(0, dbB.parties().count())
        f.delete()
        Unit
    }

    // ═══════════════ 7) الزيارات: تعود الصالحة وتُسقط اليتيمة ═══════════════

    // ─── زيارة طرف موجودة تعود، وزيارة بطرف غائب عن الملف تُسقط (فلترة اليتيم في importFrom) ───
    @Test
    fun `الزيارات_تعود_واليتيمة_منها_تسقط_عبر_المسار_الاستبدالي`() = runBlocking {
        seedAll()
        // زيارة يتيمة لطرف غير موجود — الجدول بلا FK فالحارس في الكود لا في SQLite
        dbA.visits().insert(Visit(partyId = 999L, visitedAt = t0 + 5 * DAY, note = "زيارة يتيمة"))

        val f = tempFile("visits-orphan", backupA.exportJson().toString(2))
        assertTrue(backupB.importFrom(Uri.fromFile(f)))

        val visits = dbB.visits().allOnce()
        assertEquals(1, visits.size)
        assertEquals("زيارة الجولة", visits.single().note)
        assertEquals(cust, visits.single().partyId)
        f.delete()
        Unit
    }
}
