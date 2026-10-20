package com.superbiz.app.rbac

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.CsvImportRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.LoyaltyRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.data.repo.ZatcaEnableStore
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.SessionIdentity
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E0.4 V 3.3.0] توازي البوابة على طبقة البيانات — مصفوفة مستودع × دور بالاسم
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (خارطة الطريق E0.4): «توازي حماية طبقة البيانات لدور العامل عبر
 * كل المستودعات المتبقية، مصفوفيّاً: اختبار لكل خلية دور × عملية».
 *
 * ما قبله: المصفوفة النقية (RbacMatrixTest — 68 خلية محرك) محكومة، وبوابات
 * VM قائمة (RVW-13 وغيرها)، وطبقة البيانات محروسة جزئياً فقط (BackupRepo).
 * هذه الموجة تُكمل الحرس على نقاط الكتابة (mutation) في بقية المستودعات،
 * **بنفس العمليات السبعة عشر القائمة حصراً** — لا عملية جديدة (عقد «الافتراض
 * مغلق»: عملية جديدة تبدأ مقفولة بقرار موثق منفصل) وبنفس عقد التنفيذ
 * (§4.3 وعقد الأتمتة): الضمني/SYSTEM يمران حرفياً، والجلسة الصريحة تُحكم
 * بالمصفوفة عند حد المستودع — دفاع عميق خلف بوابات الواجهة لا بديل عنها.
 *
 * خريطة الحرس الموثقة (كلها انعكاس لبوابة قائمة على المستدعي أو لسطر مصفوفة
 * صريح في RBAC_V13_DESIGN §3 — لا سياسة جديدة):
 *  InvoiceRepo.save (بيع) → POS_SELL · (شراء) → INVENTORY_MANAGE · void → INVOICE_CANCEL
 *  InventoryRepo.saveProduct (قائم) → PARTY_PRODUCT_EDIT · (جديد id=0) بلا بوابة
 *    (عقد السطر 4: الإنشاء الجديد بلا بوابة) · setArchived → PARTY_PRODUCT_EDIT
 *    deleteProduct → HARD_DELETE · moveStock → INVENTORY_MANAGE (مستدعيات VM فقط —
 *    معاملة الفاتورة تحرك المخزون عبر DAO مباشرة فلا يُمس مسار الكاشير)
 *  LedgerRepo.saveParty (قائم) → PARTY_PRODUCT_EDIT · deleteParty → HARD_DELETE
 *    (addDebt/addPayment بلا سطر مصفوفة يحكم الذمم — خارج هذه الموجة بقرار موثق أدناه)
 *  ExpenseRepo / ChecksRepo / InstallmentRepo (كتابات) → EXPENSES_CHECKS_INSTALLMENTS
 *  LoyaltyRepo.saveCoupon/deleteCoupon/manualAdjust → LOYALTY_MANAGE
 *    (مُعاملات earn/redeem/consume/reverse داخلية تحت سلطة save/void أصلاً — لا بوابة مزدوجة)
 *  CsvImportRepo.import → BACKUP_RESTORE (انعكاس بوابة importCsv الموثقة —
 *    إدخال جماعي متغيّر للبيانات = باب الاستعادة نفسه)
 *  ZatcaEnableStore.setEnabled/setProduction → TAX_OWNER_DATA (سطر 8-ب حرفياً)
 *  SettingsRepo.setAddress/setTaxNumber → TAX_OWNER_DATA (توازي RVW-13)
 *  SettingsRepo.setTaxRate/setBaseCurrency/setAutoBackupDays/setReportSchedule* →
 *    GENERAL_SETTINGS (سطر 10: ضريبة/عملة/أتمتة — أختام العمال الأخيرة بلا بوابة عمداً)
 *
 * الأرباح المقفولة هنا بـ Room حقيقي في الذاكرة + DataStore حقيقي (نمط
 * CouponVoidSemanticsTest/UsersVMGateTest): كل خلية محرمة ترمي
 * RoleDeniedException **قبل أي كتابة** (فشل مغلق — الحالة لا تتغير)، وكل
 * خلية مسموحة تُثبت التنفيذ الفعلي لا مجرد عدم الرمي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RbacDataLayerMatrixTest {

    private lateinit var db: AppDatabase
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var ledger: LedgerRepo
    private lateinit var expenses: ExpenseRepo
    private lateinit var checks: ChecksRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var loyalty: LoyaltyRepo
    private var partyId = 0L
    private var productId = 0L

    @Before
    fun setUp() = runBlocking {
        SessionState.clear()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        invoices = InvoiceRepo(db, loyalty = LoyaltyRepo(db), deviceIdProvider = { "devA" })
        inventory = InventoryRepo(db, ledger = LedgerRepo(db))
        ledger = LedgerRepo(db)
        expenses = ExpenseRepo(db, ledger = ledger)
        checks = ChecksRepo(db, ledger = ledger)
        installments = InstallmentRepo(db, ledger = LedgerRepo(db))
        loyalty = LoyaltyRepo(db)
        // [M-3.3] FK على invoices.partyId — طرف وزبون بضاعة كبذرة
        partyId = db.parties().upsert(Party(name = "عميل المصفوفة", type = 0))
        productId = db.products().upsert(
            Product(name = "صنف المصفوفة", sku = "MTX-1", salePrice = 10_000L, costPrice = 5_000L)
        )
    }

    @After
    fun tearDown() {
        SessionState.clear()
        db.close()
    }

    // ─── أدوات الخلايا ───

    /** فتح جلسة دور صريح — كل خلية تحكم نفسها ثم تمحوص في finally عبر clear */
    private fun asRole(r: Role): SessionIdentity {
        val s = SessionIdentity(90L + r.id, "مختبر ${r.name}", r)
        SessionState.start(s.userId, s.name, s.role)
        return s
    }

    private fun saleInvoice(n: String) = Invoice(
        number = n, partyId = partyId, type = 0,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L,
        subtotal = 10_000L, taxAmount = 0L, total = 10_000L
    )

    private fun purchaseInvoice(n: String) = Invoice(
        number = n, partyId = partyId, type = 1,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L,
        subtotal = 20_000L, taxAmount = 0L, total = 20_000L
    )

    private fun item() = InvoiceItem(invoiceId = 0, desc = "بند", qty = 1.0, unitPrice = 10_000L)

    private suspend fun seedInvoice(): Long =
        invoices.save(saleInvoice("MTX-${System.nanoTime()}"), listOf(item()))

    // ═══ InvoiceRepo — نقطة البيع والإلغاء ═══

    @Test
    fun `invoice save sale - cashier passes and accountant is denied fail-closed — op POS_SELL`() = runBlocking {
        asRole(Role.CASHIER)
        val id = invoices.save(saleInvoice("MTX-S1"), listOf(item()))
        assertNotNull(db.invoices().byId(id))
        SessionState.clear()

        asRole(Role.ACCOUNTANT)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { invoices.save(saleInvoice("MTX-S2"), listOf(item())) }
        }
        SessionState.clear()
        assertNull("الرفض قبل أي كتابة — لا فاتورة جزئية", db.invoices().allIncludingVoid().first().firstOrNull { it.number == "MTX-S2" })
    }

    @Test
    fun `invoice save purchase - manager passes and cashier is denied — op INVENTORY_MANAGE`() = runBlocking {
        asRole(Role.MANAGER)
        val id = invoices.save(purchaseInvoice("MTX-P1"), listOf(item()))
        assertNotNull(db.invoices().byId(id))
        SessionState.clear()

        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { invoices.save(purchaseInvoice("MTX-P2"), listOf(item())) }
        }
        SessionState.clear()
        assertNull(db.invoices().allIncludingVoid().first().firstOrNull { it.number == "MTX-P2" })
    }

    @Test
    fun `invoice void - accountant passes and cashier is denied — op INVOICE_CANCEL`() = runBlocking {
        val id1 = seedInvoice()
        asRole(Role.ACCOUNTANT)
        invoices.voidInvoice(db.invoices().byId(id1)!!)
        assertEquals(3, db.invoices().byId(id1)!!.status)
        SessionState.clear()

        val id2 = seedInvoice()
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { invoices.voidInvoice(db.invoices().byId(id2)!!) }
        }
        SessionState.clear()
        assertEquals("الإلغاء المرفوض لا يغير الحالة", 0, db.invoices().byId(id2)!!.status)
    }

    // ═══ InventoryRepo — الأصناف والمخزون ═══

    @Test
    fun `product edit existing - manager passes and cashier denied — op PARTY_PRODUCT_EDIT`() = runBlocking {
        asRole(Role.MANAGER)
        inventory.saveProduct(db.products().byId(productId)!!.copy(salePrice = 12_000L))
        assertEquals(12_000L, db.products().byId(productId)!!.salePrice)
        SessionState.clear()

        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { inventory.saveProduct(db.products().byId(productId)!!.copy(salePrice = 99_000L)) }
        }
        SessionState.clear()
        assertEquals("الرفض قبل الكتابة", 12_000L, db.products().byId(productId)!!.salePrice)
    }

    @Test
    fun `product creation stays ungated by the row-4 contract - cashier may create`() = runBlocking {
        asRole(Role.CASHIER)
        val nid = inventory.saveProduct(Product(name = "صنف كاشير", sku = "CSH-1"))
        assertNotNull(db.products().byId(nid))
    }

    @Test
    fun `product delete - owner passes and manager denied — op HARD_DELETE`() = runBlocking {
        val victim = db.products().upsert(Product(name = "ضحية حذف", sku = "DEL-1"))
        asRole(Role.MANAGER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { inventory.deleteProduct(victim) }
        }
        SessionState.clear()
        assertNotNull("الحذف المرفوض لا يحذف", db.products().byId(victim))

        asRole(Role.OWNER)
        inventory.deleteProduct(victim)
        // حذف الصنف أرشفة (عقد DAO archive) — المحروم يغيب عن القوائم الحية
        assertEquals(true, db.products().byId(victim)!!.archived)
        SessionState.clear()
    }

    @Test
    fun `product archive is an edit - manager passes cashier denied — op PARTY_PRODUCT_EDIT`() = runBlocking {
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { inventory.setArchived(productId, true) }
        }
        SessionState.clear()
        assertEquals(false, db.products().byId(productId)!!.archived)

        asRole(Role.MANAGER)
        inventory.setArchived(productId, true)
        assertEquals(true, db.products().byId(productId)!!.archived)
        SessionState.clear()
    }

    @Test
    fun `manual stock move - manager passes and cashier denied — op INVENTORY_MANAGE`() = runBlocking {
        val p = db.products().byId(productId)!!
        asRole(Role.MANAGER)
        inventory.moveStock(p, 5.0, "ADJUST", 1_700_000_000_000L, "تسوية مصفوفة", postJournal = true)
        assertEquals(5.0, db.products().byId(productId)!!.stockQty, 0.0)
        SessionState.clear()

        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { inventory.moveStock(p, 3.0, "ADJUST", 1_700_000_000_000L, "محاولة كاشير", postJournal = true) }
        }
        SessionState.clear()
        assertEquals("حركة مرفوعة بلا سلطة لا تمس المخزون", 5.0, db.products().byId(productId)!!.stockQty, 0.0)
    }

    // ═══ LedgerRepo — الأطراف ═══

    @Test
    fun `party edit existing - manager passes cashier denied — op PARTY_PRODUCT_EDIT`() = runBlocking {
        asRole(Role.MANAGER)
        ledger.saveParty(db.parties().byId(partyId)!!.copy(name = "اسم معدل"))
        assertEquals("اسم معدل", db.parties().byId(partyId)!!.name)
        SessionState.clear()

        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { ledger.saveParty(db.parties().byId(partyId)!!.copy(name = "اسم كاشير")) }
        }
        SessionState.clear()
        assertEquals("اسم معدل", db.parties().byId(partyId)!!.name)
    }

    @Test
    fun `party delete - owner only — op HARD_DELETE`() = runBlocking {
        val victim = db.parties().upsert(Party(name = "طرف حذف", type = 0))
        asRole(Role.MANAGER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { ledger.deleteParty(victim) }
        }
        SessionState.clear()
        assertNotNull(db.parties().byId(victim))

        asRole(Role.OWNER)
        ledger.deleteParty(victim)
        // حذف الطرف أرشفة (عقد DAO archive) — المحروم يغيب عن القوائم الحية
        assertEquals(true, db.parties().byId(victim)!!.archived)
        SessionState.clear()
    }

    // ═══ ExpenseRepo / ChecksRepo / InstallmentRepo — سطر 9 ═══

    @Test
    fun `expenses write - accountant passes cashier denied — op EXPENSES_CHECKS_INSTALLMENTS`() = runBlocking {
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { expenses.add(5_000L, "إيجار", "محاولة كاشير") }
        }
        SessionState.clear()
        assertTrue("لا مصروفات كتبها الكاشير", db.expenses().all().first().isEmpty())

        asRole(Role.ACCOUNTANT)
        val eid = expenses.add(5_000L, "إيجار", "مصروف محاسب")
        assertNotNull(db.expenses().all().first().firstOrNull { it.id == eid })
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { expenses.delete(db.expenses().all().first().first { it.id == eid }) }
        }
        SessionState.clear()
        assertNotNull("حذف مصروف مرفوض لا يحذف", db.expenses().all().first().firstOrNull { it.id == eid })
    }

    @Test
    fun `checks write - accountant passes cashier denied — op EXPENSES_CHECKS_INSTALLMENTS`() = runBlocking {
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking {
                checks.save(CheckEntity(number = "CH-1", partyId = partyId, amount = 10_000L,
                    issueDate = 1_700_000_000_000L, dueDate = 1_700_000_000_000L))
            }
        }
        SessionState.clear()
        assertTrue(db.checks().all().first().isEmpty())

        asRole(Role.ACCOUNTANT)
        val cid = checks.save(CheckEntity(number = "CH-2", partyId = partyId, amount = 10_000L,
            issueDate = 1_700_000_000_000L, dueDate = 1_700_000_000_000L))
        assertNotNull(db.checks().byId(cid))
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { checks.delete(cid) }
        }
        SessionState.clear()
        assertNotNull("حذف شيك مرفوض لا يحذف", db.checks().byId(cid))
    }

    @Test
    fun `installment plan create and delete - manager passes cashier denied — op EXPENSES_CHECKS_INSTALLMENTS`() = runBlocking {
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking {
                installments.createPlan("خطة كاشير", partyId, 0, 12_000L, 0L, 3,
                    1_700_000_000_000L, "SAR", "")
            }
        }
        SessionState.clear()
        assertTrue(db.installments().plans().first().isEmpty())

        asRole(Role.MANAGER)
        val pid = installments.createPlan("خطة مدير", partyId, 0, 12_000L, 0L, 3,
            1_700_000_000_000L, "SAR", "")
        asRole(Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { installments.deletePlan(db.installments().planById(pid)!!) }
        }
        SessionState.clear()
        assertNotNull("حذف خطة مرفوض لا يحذف", db.installments().planById(pid))
    }

    // ═══ LoyaltyRepo — سطر 14-ب ═══

    @Test
    fun `coupon rules - manager passes accountant denied — op LOYALTY_MANAGE`() = runBlocking {
        asRole(Role.ACCOUNTANT)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { loyalty.saveCoupon(CouponEntity(code = "ACC-1", maxUses = 1)) }
        }
        SessionState.clear()
        assertNull(db.coupons().byId(db.coupons().all().first().firstOrNull { it.code == "ACC-1" }?.id ?: 0L))

        asRole(Role.MANAGER)
        val cid = loyalty.saveCoupon(CouponEntity(code = "MGR-1", maxUses = 1))
        assertNotNull(db.coupons().byId(cid))
        asRole(Role.ACCOUNTANT)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { loyalty.deleteCoupon(cid) }
        }
        SessionState.clear()
        assertNotNull("حذف كوبون مرفوض لا يحذف", db.coupons().byId(cid))
    }

    @Test
    fun `loyalty manual adjust - manager passes accountant denied — op LOYALTY_MANAGE`() = runBlocking {
        asRole(Role.ACCOUNTANT)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { loyalty.manualAdjust(partyId, 50L, "محاولة محاسب") }
        }
        SessionState.clear()
        assertEquals(0L, loyalty.balance(partyId))

        asRole(Role.MANAGER)
        loyalty.manualAdjust(partyId, 50L, "تعديل مدير")
        assertEquals(50L, loyalty.balance(partyId))
        SessionState.clear()
    }

    // ═══ CsvImportRepo — انعكاس بوابة importCsv (BACKUP_RESTORE) ═══

    @Test
    fun `csv import mirrors the documented caller gate - owner passes manager denied — op BACKUP_RESTORE`() = runBlocking {
        asRole(Role.MANAGER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { CsvImportRepo(db).import("اسم,هاتف\nسعد,0500000000\n", "عميل", "مورد", "كلاهما") }
        }
        SessionState.clear()
        // المالك يمر — التنفيذ الفعلي يثبت بانتهاء الاستدعاء بنتيجة (أي نوع رأس —
        // جودة التحليل عقد CsvImportRepoTest لا هذه الموجة؛ البوابة هي ما يُقفل هنا)
        asRole(Role.OWNER)
        val stats = CsvImportRepo(db).import("اسم,هاتف\nسعد,0500000000\n", "عميل", "مورد", "كلاهما")
        assertNotNull(stats)
        SessionState.clear()
    }

    // ═══ ZatcaEnableStore — سطر 8-ب حرفياً ═══

    @Test
    fun `zatca link enable and production - owner only — op TAX_OWNER_DATA`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = ZatcaEnableStore(ctx)
        asRole(Role.MANAGER)
        assertThrows(RoleDeniedException::class.java) { runBlocking { store.setEnabled(true) } }
        assertThrows(RoleDeniedException::class.java) { runBlocking { store.setProduction(true) } }
        SessionState.clear()
        assertEquals(false, store.enabledOnce())

        asRole(Role.OWNER)
        store.setEnabled(true)
        assertEquals(true, store.enabledOnce())
        SessionState.clear()
    }

    // ═══ SettingsRepo — توازي RVW-13 على طبقة البيانات ═══

    @Test
    fun `tax owner settings setters - owner only — op TAX_OWNER_DATA`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepo(ctx)
        asRole(Role.MANAGER)
        assertThrows(RoleDeniedException::class.java) { runBlocking { settings.setTaxNumber("3XXXXXXXXXXXX3") } }
        assertThrows(RoleDeniedException::class.java) { runBlocking { settings.setAddress("عنوان مدير") } }
        SessionState.clear()

        asRole(Role.OWNER)
        settings.setTaxNumber("3YYYYYYYYYYYYZ")
        settings.setAddress("عنوان المالك")
        assertEquals("3YYYYYYYYYYYYZ", settings.snapshot().taxNumber)
        SessionState.clear()
    }

    @Test
    fun `general settings setters - manager passes accountant denied — op GENERAL_SETTINGS`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepo(ctx)
        asRole(Role.ACCOUNTANT)
        assertThrows(RoleDeniedException::class.java) { runBlocking { settings.setTaxRate(17.0) } }
        assertThrows(RoleDeniedException::class.java) { runBlocking { settings.setAutoBackupDays(7) } }
        SessionState.clear()

        asRole(Role.MANAGER)
        settings.setTaxRate(17.0)
        settings.setAutoBackupDays(7)
        assertEquals(17.0, settings.snapshot().taxRate, 0.0)
        assertEquals(7, settings.snapshot().autoBackupDays)
        // مجدول التقارير — نفس باب الأتمتة (سطر 10)
        settings.setReportScheduleDays(7)
        settings.setReportScheduleHour(9)
        settings.setReportScheduleChannel("email")
        assertEquals(7, settings.snapshot().reportScheduleDays)
        assertEquals(9, settings.snapshot().reportScheduleHour)
        assertEquals("email", settings.snapshot().reportScheduleChannel)
        SessionState.clear()
    }

    // ═══ عقد الأتمتة — الضمني وSYSTEM يمران حرفياً (§4.3) ═══

    @Test
    fun `automation contract - implicit owner and SYSTEM pass every guarded write`() = runBlocking {
        // بلا جلسة (قفل الجهاز / عامل خلفي في وضع أحادي المالك)
        SessionState.clear()
        val id = invoices.save(saleInvoice("MTX-A1"), listOf(item()))
        invoices.voidInvoice(db.invoices().byId(id)!!)

        // هوية النظام (تعدد المستخدمين بلا جلسة حية — عمّال WorkManager)
        SessionState.multiUserMode = true
        try {
            assertEquals(SessionState.SYSTEM, SessionState.effective())
            val id2 = invoices.save(saleInvoice("MTX-A2"), listOf(item()))
            invoices.voidInvoice(db.invoices().byId(id2)!!)
            val expId = expenses.add(5_000L, "أتمتة", "عامل نظام")
            assertTrue("المصروف كُتب فعلاً بمعرّف حقيقي", expId > 0L)
        } finally {
            SessionState.multiUserMode = false
        }
    }
}
