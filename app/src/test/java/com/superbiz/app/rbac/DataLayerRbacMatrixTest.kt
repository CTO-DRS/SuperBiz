package com.superbiz.app.rbac

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.ExpenseRepo
import com.superbiz.app.data.repo.InstallmentRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.LoyaltyRepo
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E0.4 V3.3.0] توازي البوابات في طبقة البيانات — مصفوفة الدور × العملية
 * ═══════════════════════════════════════════════════════════════════════════
 * خريطة الطريق (المرحلة صفر E0.4): «اكتمال توازي حرس طبقة البيانات لدور
 * العامل في بقية المستودعات، على نمط المصفوفة: اختبار لكل خلية دور × عملية».
 *
 * الواقع قبل هذه الموجة: محرك النقي (RbacMatrixTest) مقفول 68 خلية، لكن
 * أربعة أسطر من المصفوفة كان بلا أي إنفاذ في الطبقتين معاً — POS_SELL،
 * PARTY_PRODUCT_EDIT، INVENTORY_MANAGE، EXPENSES_CHECKS_INSTALLMENTS —
 * وأسطر أخرى كانت محكومة في الواجهة/الVM فقط بلا توازٍ في طبقة البيانات.
 *
 * العقود هنا (Room حقيقي في الذاكرة — نمط CouponVoidSemanticsTest):
 *  • كل خلية من خلايا السطور السبعة ذات سطح طبقة-بيانات تُحسم بالاسم:
 *    الأدوار الممنوحة تُنجز العملية فعلاً، والمحرومة تُرفض RoleDeniedException.
 *  • «الإنشاء الجديد بلا بوابة» (عقد المصفوفة §3 الموثق على Op.PARTY_PRODUCT_EDIT)
 *    يُقفل حرفياً: حفظ كيان جديد بلا id يمر لكل الأدوار، وتعديل قائم يتطلب البوابة.
 *  • هوية SYSTEM (عمال الخلفية) تمر في كل الأسطح — عقد RVW-5: الأتمتة لا تتوقف.
 *  • قراءة ledger.onChanged تُلغى في المحرّمات — لا ويدجت في الاختبار.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataLayerRbacMatrixTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var expenses: ExpenseRepo
    private lateinit var checks: ChecksRepo
    private lateinit var installments: InstallmentRepo
    private lateinit var loyalty: LoyaltyRepo

    private var partyId = 0L
    private var productId = 0L

    private val allRoles = listOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER)

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = LedgerRepo(db)
        invoices = InvoiceRepo(db, loyalty = LoyaltyRepo(db), deviceIdProvider = { "devA" })
        inventory = InventoryRepo(db, ledger)
        expenses = ExpenseRepo(db, ledger)
        checks = ChecksRepo(db, ledger)
        installments = InstallmentRepo(db, ledger)
        loyalty = LoyaltyRepo(db)
        partyId = db.parties().upsert(Party(name = "عميل اختبار", type = 0))
        productId = db.products().upsert(Product(name = "صنف اختبار"))
        Unit
    }

    @After
    fun tearDown() {
        SessionState.clear()
        db.close()
    }

    // ── عصا الحسم: خلية واحدة باسم صريح ──
    private inline fun cell(role: Role, allowed: Boolean, crossinline attempt: suspend () -> Unit) {
        SessionState.start(9L, "مُختبِر-${role.name}", role)
        try {
            runBlocking { attempt() }
            if (!allowed) fail("${role.name} يجب أن يُرفض — الباب كان مفتوحاً (فشل مفتوح)")
        } catch (e: RoleDeniedException) {
            if (allowed) fail("${role.name} ممنوح في المصفوفة لكنه رُفض: ${e.message}")
        } finally {
            SessionState.clear()
        }
    }

    private fun saleInvoice(number: String) = Invoice(
        number = number, partyId = partyId, type = 0,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L,
        subtotal = 10_000L, taxAmount = 0L, total = 10_000L
    )

    private fun purchaseInvoice(number: String) = Invoice(
        number = number, partyId = partyId, type = 1,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L,
        subtotal = 10_000L, taxAmount = 0L, total = 10_000L
    )

    private fun item() = InvoiceItem(invoiceId = 0, desc = "خدمة", qty = 1.0, unitPrice = 10_000L)

    // ═══ سطر 1: POS_SELL — فاتورة بيع (المحاسب لا يبيع) ═══

    @Test
    fun POS_SELL_saleInvoice_matrix() = runBlocking {
        // المالك/المدير/الكاشير يبيعون — والمحاسب محروم (تصميم §3 سطر 1)
        listOf(Role.OWNER, Role.MANAGER, Role.CASHIER).forEach { r ->
            cell(r, allowed = true) {
                invoices.save(saleInvoice("INV-${r.name}-S"), listOf(item()))
            }
        }
        cell(Role.ACCOUNTANT, allowed = false) {
            invoices.save(saleInvoice("INV-ACC-S"), listOf(item()))
        }
    }

    // ═══ سطر 1 المكمّل: الشراء = INVENTORY_MANAGE (فاتورة شراء ترفع المخزون) ═══

    @Test
    fun purchaseInvoice_isInventoryManage_matrix() = runBlocking {
        listOf(Role.OWNER, Role.MANAGER).forEach { r ->
            cell(r, allowed = true) {
                invoices.save(purchaseInvoice("INV-${r.name}-P"), listOf(item()))
            }
        }
        listOf(Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            cell(r, allowed = false) {
                invoices.save(purchaseInvoice("INV-${r.name}-P"), listOf(item()))
            }
        }
    }

    // ═══ سطر 3: INVOICE_CANCEL — الإلغاء بلا كاشير ═══

    @Test
    fun INVOICE_CANCEL_matrix() = runBlocking {
        // المالك يحفظ فاتورة مرجعية ثم كل دور يجرب الإلغاء على فاتورته الخاصة
        listOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT).forEach { r ->
            SessionState.start(9L, "seed-owner", Role.OWNER)
            val id = invoices.save(saleInvoice("INV-C-${r.name}"), listOf(item()))
            SessionState.clear()
            cell(r, allowed = true) { invoices.voidInvoice(db.invoices().byId(id)!!) }
        }
        SessionState.start(9L, "seed-owner", Role.OWNER)
        val cId = invoices.save(saleInvoice("INV-C-CASH"), listOf(item()))
        SessionState.clear()
        cell(Role.CASHIER, allowed = false) { invoices.voidInvoice(db.invoices().byId(cId)!!) }
        // الفاتورة سليمة لم تُلمس — الرفض مغلق لا يترك أثراً
        assertEquals("status<3 تعني غير ملغاة", 0, db.invoices().byId(cId)!!.status)
    }

    // ═══ سطر 4: PARTY_PRODUCT_EDIT — تعديل قائم ببوابة، وإنشاء جديد حرّ (العقد الموثق) ═══

    @Test
    fun PARTY_PRODUCT_EDIT_editGated_creationFree_matrix() = runBlocking {
        // تعديل طرف قائم: المالك والمدير فقط
        listOf(Role.OWNER, Role.MANAGER).forEach { r ->
            cell(r, allowed = true) {
                ledger.saveParty(Party(id = partyId, name = "عميل-${r.name}", type = 0))
            }
        }
        listOf(Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            cell(r, allowed = false) {
                ledger.saveParty(Party(id = partyId, name = "عميل-معدل", type = 0))
            }
        }
        // الإنشاء الجديد (id=0) بلا بوابة — العقد الحرفي لسطر المصفوفة
        allRoles.forEach { r ->
            cell(r, allowed = true) { ledger.saveParty(Party(name = "جديد-${r.name}", type = 0)) }
        }
        // تعديل منتج قائم: نفس البوابة
        listOf(Role.OWNER, Role.MANAGER).forEach { r ->
            cell(r, allowed = true) {
                inventory.saveProduct(Product(id = productId, name = "صنف-${r.name}"))
            }
        }
        listOf(Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            cell(r, allowed = false) {
                inventory.saveProduct(Product(id = productId, name = "صنف-معدل"))
            }
        }
        // إنشاء منتج جديد حرّ لكل الأدوار (نفس عقد «الإنشاء الجديد بلا بوابة»)
        allRoles.forEach { r ->
            cell(r, allowed = true) { inventory.saveProduct(Product(name = "جديد-${r.name}")) }
        }
    }

    // ═══ سطر 5: HARD_DELETE — المالك وحده (طرف/منتج) ═══

    @Test
    fun HARD_DELETE_ownerOnly_matrix() = runBlocking {
        // طرف مرجعي لكل محاولة (الحذف الناجح للمالك يحتاج طرفاً بلا قيود مفتوحة)
        cell(Role.OWNER, allowed = true) { ledger.deleteParty(partyId) }
        listOf(Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            val pid = db.parties().upsert(Party(name = "طرف-${r.name}", type = 0))
            cell(r, allowed = false) { ledger.deleteParty(pid) }
            assertNotNull("الحذف المرفوض لا يمسح", db.parties().byId(pid))
        }
        cell(Role.OWNER, allowed = true) { inventory.deleteProduct(productId) }
        listOf(Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            val pr = db.products().upsert(Product(name = "صنف-${r.name}"))
            cell(r, allowed = false) { inventory.deleteProduct(pr) }
            assertNotNull("الحذف المرفوض لا يمسح", db.products().byId(pr))
        }
    }

    // ═══ سطر 6: INVENTORY_MANAGE — حركات المخزون والأرشفة ═══

    @Test
    fun INVENTORY_MANAGE_moves_matrix() = runBlocking {
        val product = db.products().byId(productId)!!
        listOf(Role.OWNER, Role.MANAGER).forEach { r ->
            cell(r, allowed = true) {
                inventory.moveStock(product, qty = 2.0, reason = "ADJUST", date = 1_700_000_000_000L,
                    note = "تسوية-${r.name}", postJournal = false)
            }
            cell(r, allowed = true) { inventory.setArchived(product.id, value = true) }
        }
        listOf(Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            cell(r, allowed = false) {
                inventory.moveStock(product, qty = 2.0, reason = "ADJUST", date = 1_700_000_000_000L,
                    note = "تسوية-${r.name}", postJournal = false)
            }
            cell(r, allowed = false) { inventory.setArchived(product.id, value = false) }
        }
    }

    // ═══ سطر 9: EXPENSES_CHECKS_INSTALLMENTS — بلا كاشير ═══

    @Test
    fun EXPENSES_CHECKS_INSTALLMENTS_matrix() = runBlocking {
        listOf(Role.OWNER, Role.MANAGER, Role.ACCOUNTANT).forEach { r ->
            cell(r, allowed = true) { expenses.add(amount = 1_000L, category = "عامة", note = r.name) }
            cell(r, allowed = true) {
                checks.save(CheckEntity(number = "CH-${r.name}", partyId = partyId,
                    amount = 5_000L, issueDate = 1_700_000_000_000L, dueDate = 1_700_000_000_000L))
            }
            cell(r, allowed = true) {
                installments.createPlan(title = "خطة-${r.name}", partyId = partyId, direction = 0,
                    total = 12_000L, downPayment = 0L, months = 3,
                    startDate = 1_700_000_000_000L, currency = "SAR", note = "")
            }
        }
        cell(Role.CASHIER, allowed = false) { expenses.add(amount = 1_000L, category = "عامة", note = "") }
        cell(Role.CASHIER, allowed = false) {
            checks.save(CheckEntity(number = "CH-CASH", partyId = partyId,
                amount = 5_000L, issueDate = 1_700_000_000_000L, dueDate = 1_700_000_000_000L))
        }
        cell(Role.CASHIER, allowed = false) {
            installments.createPlan(title = "خطة-كاشير", partyId = partyId, direction = 0,
                total = 12_000L, downPayment = 0L, months = 3,
                startDate = 1_700_000_000_000L, currency = "SAR", note = "")
        }
    }

    // ═══ سطر 14: LOYALTY_MANAGE — قواعد الكوبونات للمالك والمدير ═══

    @Test
    fun LOYALTY_MANAGE_matrix() = runBlocking {
        listOf(Role.OWNER, Role.MANAGER).forEach { r ->
            cell(r, allowed = true) {
                loyalty.saveCoupon(com.superbiz.app.data.db.CouponEntity(code = "C-${r.name}", maxUses = 1))
            }
        }
        listOf(Role.ACCOUNTANT, Role.CASHIER).forEach { r ->
            cell(r, allowed = false) {
                loyalty.saveCoupon(com.superbiz.app.data.db.CouponEntity(code = "C-${r.name}", maxUses = 1))
            }
        }
        // حذف كوبون قائم: نفس بوابة الإدارة
        val cid = db.coupons().upsert(com.superbiz.app.data.db.CouponEntity(code = "DEL", maxUses = 1))
        cell(Role.OWNER, allowed = true) { loyalty.deleteCoupon(cid) }
        val cid2 = db.coupons().upsert(com.superbiz.app.data.db.CouponEntity(code = "DEL2", maxUses = 1))
        cell(Role.CASHIER, allowed = false) { loyalty.deleteCoupon(cid2) }
        assertNotNull("الحذف المرفوض لا يمسح", db.coupons().byId(cid2))
    }

    // ═══ عقد RVW-5: هوية SYSTEM تمر في كل الأسطح — الأتمتة لا تتوقف ═══

    @Test
    fun systemIdentity_passesAllSurfaces_backgroundWorkersUnaffected() = runBlocking {
        SessionState.clear()
        // وضع التعدد بلا جلسة صريحة = SYSTEM (OWNER) — نفس ما يراه عامل الخلفية
        SessionState.multiUserMode = true
        try {
            invoices.save(saleInvoice("INV-SYS-1"), listOf(item()))
            invoices.save(purchaseInvoice("INV-SYS-2"), listOf(item()))
            ledger.saveParty(Party(id = partyId, name = "عميل-نظام", type = 0))
            inventory.moveStock(db.products().byId(productId)!!, qty = 1.0, reason = "ADJUST",
                date = 1_700_000_000_000L, note = "", postJournal = false)
            expenses.add(amount = 500L, category = "نظام", note = "")
            loyalty.saveCoupon(com.superbiz.app.data.db.CouponEntity(code = "SYS", maxUses = 1))
            assertTrue("هوية النظام أنجزت كل الأسطح", db.invoices().all().first().isNotEmpty())
        } finally {
            SessionState.multiUserMode = false
        }
    }
}
