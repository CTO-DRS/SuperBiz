package com.superbiz.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.OwnerPinSeed // [H1-3][v13] بذرة المالك للترحيل
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.data.repo.ReportsRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.data.repo.VisitsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** حاوية تبعيات يدوية بسيطة (نمط AppGraph) — بلا أطر خارجية */
class AppGraph(val context: android.content.Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy {
        // [H1-3][v13] ربط مزوّد بذرة المالك قبل بناء القاعدة — القراءة المتزامنة
        // لDataStore تتم داخل مسار الترحيل فقط (عند وجود قاعدة v12 حقيقية) بنسخ
        // نص للمغلّف ks: لا إعادة تشفير ولا مسّ Keystore (عقد التصميم §4.2-3).
        // الربط idempotent (بشرط العدم) — الاختبارات قد تربط بذرة اصطناعية قبل
        // أي لمس لgraph.db، وفشل مغلق: مزوّد سابق يبقى هو المعتمد.
        if (ownerPinSeedProvider == null) {
            ownerPinSeedProvider = { settings.readOwnerPinSeedSync() }
        }
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, "superbiz.db")
            // [P11-a]: أُضيف MIGRATION_6_7 — القائمة هنا هي المسار الفعلي لفتح القاعدة
            // (قائمة MIGRATIONS أدناه للاختبارات)؛ نسيانها هنا يعني فشل فتح قاعدة v6 القائمة
            // [P12-b]: أُضيف MIGRATION_7_8 — نسيانه هنا يعني فشل فتح قاعدة v7 القائمة
            // [P17-a]: أُضيف MIGRATION_8_9 — نسيانه هنا يعني فشل فتح قاعدة v8 القائمة
            // [P33-P8]: أُضيف MIGRATION_9_10 — نسيانه هنا يعني فشل فتح قاعدة v9 القائمة
            // [P41-L1]: أُضيف MIGRATION_10_11 — نسيانه هنا يعني فشل فتح قاعدة v10 القائمة
            // [P46-W1]: أُضيف MIGRATION_11_12 — نسيانه هنا يعني فشل فتح قاعدة v11 القائمة
            // [H1-3][v13]: أُضيف MIGRATION_12_13 — نسيانه هنا يعني فشل فتح قاعدة v12 القائمة
            // [Z2-أ][v14]: أُضيف MIGRATION_13_14 — نسيانه هنا يعني فشل فتح قاعدة v13 القائمة
            // [H4-1][v15]: أُضيف MIGRATION_14_15 — نسيانه هنا يعني فشل فتح قاعدة v14 القائمة
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
            .build()
    }
    val settings by lazy { SettingsRepo(context) }
    val ledger by lazy {
        LedgerRepo(db).also { repo ->
            // أي قيد محاسبي جديد يجدد ويدجت رصيد الذمم تلقائياً
            repo.onMutate = { com.superbiz.app.widget.WidgetSync.push(context) }
        }
    }
    // [P46-W1] جولة 7 — نقاط الولاء والكوبونات (مخطط v12)
    val loyalty by lazy { com.superbiz.app.data.repo.LoyaltyRepo(db) }
    val invoices by lazy {
        // [Z2-أ V 1.5.0] خاتم ZATCA-2 — لقطة بائع من الإعدادات لحظة الإصدار
        val stamper = com.superbiz.app.data.repo.ZatcaStamper(db, sellerProvider = {
            val s = settings.snapshot()
            com.superbiz.app.data.repo.ZatcaStamper.Seller(
                name = s.businessName,
                vatNumber = s.taxNumber,
                crn = s.crNumber,
                street = s.address,
                city = s.city,
                country = s.country.ifBlank { "SA" },
            )
        })
        InvoiceRepo(db, loyalty, stamper).also { repo ->
            // حفظ/إلغاء فاتورة (ومنها POS) يجدد ويدجات الشاشة الرئيسية فوراً
            repo.onMutate = { com.superbiz.app.widget.WidgetSync.push(context) }
            // [H4-6][V 3.0.0] إشعار الويب هوك عند فاتورة جديدة — إطفائي، صامت بلا تفعيل
            repo.onInvoiceCreated = { invId ->
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        if (!webhook.enabledOnce()) return@launch
                        val inv = db.invoices().byId(invId) ?: return@launch
                        val payload = com.superbiz.app.domain.export.WebhookKit.invoiceCreated(
                            invoiceId = inv.id,
                            invoiceNumber = inv.number,
                            totalPiasters = inv.total,
                            taxPiasters = inv.taxAmount,
                            partyName = inv.partyId?.let { db.parties().byId(it)?.name },
                            currencyCode = "SAR",
                            origCurrency = inv.origCurrency,
                            origTotal = inv.origTotal,
                            at = System.currentTimeMillis()
                        )
                        webhookClient.send(payload)
                    }
                }
            }
        }
    }
    val inventory by lazy {
        InventoryRepo(db, ledger).also { repo ->
            // أي حركة مخزون أو تعديل منتج يجدد ويدجت المخزون المنخفض فوراً
            repo.onMutate = { com.superbiz.app.widget.WidgetSync.push(context) }
        }
    }
    val checks by lazy { ChecksRepo(db, ledger) }
    val installments by lazy { com.superbiz.app.data.repo.InstallmentRepo(db, ledger) }
    val expenses by lazy { com.superbiz.app.data.repo.ExpenseRepo(db, ledger) }
    val reports by lazy { ReportsRepo(db) }
    val backup by lazy { BackupRepo(context, db, settings) }

    /** [P12-b] سجل زيارات العملاء بموقعها الجغرافي — يغذّي VisitsSection في المفضّلات */
    val visits by lazy { VisitsRepo(db) }

    /** [P17-a] كشف الحساب PDF — تجميع/إصدار/تسليم + قوالب وتواقيع وأختام وقواعد وسجل تدقيق */
    val statements by lazy { com.superbiz.app.data.repo.StatementRepo(db, ledger, settings) }

    /** [W1] مخزن Pro (نسخة سريعة للاستحقاق + أهداف لوحة المؤشرات) — بلا مساس بمخطط الإعدادات */
    val proStore by lazy { com.superbiz.app.data.repo.ProStore(context) }

    /** [W1] متحكم Play Billing v7 — فريميوم Pro بلا خادم (شراء لمرة واحدة + استعادة) */
    val billing by lazy {
        com.superbiz.app.data.repo.BillingRepo(context, proStore, appScope)
    }

    // ─── [Z2-أ/ب V 1.5.0] الربط الضريبي — بوابة الميزة + العميل المعزول (ADR-001) ───

    /** بوابة الميزة المزدوجة — خارج تفعيلها الصريح يبقى التطبيق صامتاً شبكياً كلياً */
    val zatcaLink by lazy { com.superbiz.app.data.repo.ZatcaEnableStore(context) }
    val sync by lazy { com.superbiz.app.data.repo.SyncEnableStore(context) }
    val syncEngine by lazy { com.superbiz.app.data.repo.SyncEngine(db, sync) }
    val syncClient by lazy { com.superbiz.app.network.SyncClient(sync) }
    val webhook by lazy { com.superbiz.app.data.repo.WebhookStore(context) }
    val webhookClient by lazy { com.superbiz.app.network.WebhookClient(webhook) }

    /**
     * عميل منصة فاتورة — يُحقن هنا فقط (سلك التوصيل الجذري المصرَّح به في
     * فاحص حدود ADR-001): البقية ترى الواجهة النقية ZatcaGateway حصراً.
     * مزوّد بيانات الاعتماد null الآن — CSID الحي يُملأ في دفعة Z2-ج،
     * والبوابة المزدوجة تمنع أي socket قبله (TransientFailure مغلق).
     */
    val zatcaGateway: com.superbiz.app.domain.ZatcaGateway by lazy {
        com.superbiz.app.network.ZatcaFatooraGateway(zatcaLink, credentials = { null })
    }

    /** تهيئة أولية/إعادة بناء: عملات + قواعد افتراضية — آمنة للتكرار (idempotent) */
    suspend fun seedIfFirstRun() {
        val s = settings.snapshot()
        // (M-5.6 توحيد): البذر مستخلَص في SeedDefaults — يُستدعى من الإقلاع ومن ما بعد
        // الاستعادة على السواء (كان المسح في wipeAll يمحو جدولي rules وcurrencies تماماً
        // فبعد استعادة نسخة بلاهما يبقى التطبيق بلا عملات وقواعد حتى إعادة تشغيل بارد)
        com.superbiz.app.data.repo.SeedDefaults.ensure(db)
        // ترقية: قاعدة تذكير الأقساط للنسخ السابقة
        if (db.rules().byKind("INSTALLMENT_REMIND") == null) {
            db.rules().upsert(com.superbiz.app.data.db.Rule(kind = "INSTALLMENT_REMIND", enabled = true, daysBefore = 3))
        }
        if (!s.seeded) settings.setSeeded()
    }

    companion object {

        /** ترقية مخطط قاعدة البيانات 1→2: جدولا الأقساط — تحفظ كل بيانات المستخدم */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `installment_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `direction` INTEGER NOT NULL, " +
                        "`total` REAL NOT NULL, `downPayment` REAL NOT NULL, `financed` REAL NOT NULL, " +
                        "`months` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_partyId` ON `installment_plans` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_direction` ON `installment_plans` (`direction`)")
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `amount` REAL NOT NULL, " +
                        "`dueDate` INTEGER NOT NULL, `paidAmount` REAL NOT NULL, `paidDate` INTEGER, " +
                        "`status` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_planId` ON `installments` (`planId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueDate` ON `installments` (`dueDate`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_status` ON `installments` (`status`)")
            }
        }

        /** ترقية مخطط قاعدة البيانات 2→3: جدول المصروفات — تحفظ كل بيانات المستخدم */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`amount` REAL NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_category` ON `expenses` (`category`)")
            }
        }

        /**
 * — ترقية المخطط 3→4: فهارس الأداء.
 * كان جدول دفتر اليومية بلا فهرس مرجع/تاريخ، فكانت استعلامات
 * unpost/deleteEntriesByRef/accountSumsBetween تفحص كل الصفوف،
 * واستعلام findNumber على الفواتير يُستدعى حتى 10 آلاف مرة عند كل حفظ بلا فهرس.
 * كلها CREATE INDEX فقط — لا تغيير في الصفوف ولا في أعمدة البيانات، فلا خطر على البيانات.
*/
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_refType_refId` ON `journal` (`refType`, `refId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_date` ON `journal` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_number` ON `invoices` (`number`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_archived` ON `parties` (`archived`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_products_archived` ON `products` (`archived`)")
            }
        }

        /**
 * (M-3.3 توحيد): مفاتيح أجنبية مفروضة على مستوى SQLite — مخطط v5.
 * إعادة بناء 8 جداول بقيود RESTRICT للأطراف/المنتجات (الأرشفة هي مسار الإزالة)،
 * وSET NULL للمراجع الاختيارية، وCASCADE للأبناء الحقيقيين (بنود الفاتورة،
 * أقساط الخطة، أسطر القيد). لا فاتورة بلا طرف ولا بند يتيم ولا قسط بلا خطة.
 * + عمود payments.planId (M-4.9) + فهرس parties.name (M-3.6 توحيد).
 * SQL من خط التدقيق — مختبَط على قاعدة v4 كاملة البيانات
 * حفظ الصفوف، أسماء القيود في foreign_key_list، foreign_key_check نظيف.
*/
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                // ── invoices: FK(partyId → parties RESTRICT) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                        "`date` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `subtotal` REAL NOT NULL, " +
                        "`discount` REAL NOT NULL, `taxRate` REAL NOT NULL, `taxAmount` REAL NOT NULL, " +
                        "`total` REAL NOT NULL, `paid` REAL NOT NULL, `costTotal` REAL NOT NULL, " +
                        "`status` INTEGER NOT NULL, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, " +
                        "`note` TEXT NOT NULL, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                d.execSQL(
                    "INSERT INTO `_new_invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`," +
                        "`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                        "SELECT `id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`," +
                        "`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note` FROM `invoices`"
                )
                d.execSQL("DROP TABLE `invoices`")
                d.execSQL("ALTER TABLE `_new_invoices` RENAME TO `invoices`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_partyId` ON `invoices` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_date` ON `invoices` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_status` ON `invoices` (`status`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_number` ON `invoices` (`number`)")

                // ── invoice_items: FK(invoiceId → invoices CASCADE, productId → products SET NULL) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`invoiceId` INTEGER NOT NULL, `productId` INTEGER, `desc` TEXT NOT NULL, " +
                        "`qty` REAL NOT NULL, `unitPrice` REAL NOT NULL, `discount` REAL NOT NULL, " +
                        "FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                d.execSQL(
                    "INSERT INTO `_new_invoice_items` (`id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) " +
                        "SELECT `id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount` FROM `invoice_items`"
                )
                d.execSQL("DROP TABLE `invoice_items`")
                d.execSQL("ALTER TABLE `_new_invoice_items` RENAME TO `invoice_items`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")

                // ── payments: FK(×4 SET NULL) + عمود planId الجديد (M-4.9) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`partyId` INTEGER, `invoiceId` INTEGER, `checkId` INTEGER, `amount` REAL NOT NULL, " +
                        "`date` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `method` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `planId` INTEGER, " +
                        "FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(`checkId`) REFERENCES `checks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                d.execSQL(
                    "INSERT INTO `_new_payments` (`id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`,`planId`) " +
                        "SELECT `id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`,NULL FROM `payments`"
                )
                d.execSQL("DROP TABLE `payments`")
                d.execSQL("ALTER TABLE `_new_payments` RENAME TO `payments`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_partyId` ON `payments` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_date` ON `payments` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_checkId` ON `payments` (`checkId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_planId` ON `payments` (`planId`)")

                // ── checks: FK(partyId → parties RESTRICT) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `bank` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, `issueDate` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, " +
                        "`direction` INTEGER NOT NULL, `status` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                        "FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                d.execSQL(
                    "INSERT INTO `_new_checks` (`id`,`number`,`partyId`,`bank`,`amount`,`issueDate`,`dueDate`,`direction`,`status`,`note`) " +
                        "SELECT `id`,`number`,`partyId`,`bank`,`amount`,`issueDate`,`dueDate`,`direction`,`status`,`note` FROM `checks`"
                )
                d.execSQL("DROP TABLE `checks`")
                d.execSQL("ALTER TABLE `_new_checks` RENAME TO `checks`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_partyId` ON `checks` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_dueDate` ON `checks` (`dueDate`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_status` ON `checks` (`status`)")

                // ── stock_moves: FK(productId → products RESTRICT) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_stock_moves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`productId` INTEGER NOT NULL, `qty` REAL NOT NULL, `reason` TEXT NOT NULL, " +
                        "`date` INTEGER NOT NULL, `refType` TEXT, `refId` INTEGER, `note` TEXT NOT NULL, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                d.execSQL(
                    "INSERT INTO `_new_stock_moves` (`id`,`productId`,`qty`,`reason`,`date`,`refType`,`refId`,`note`) " +
                        "SELECT `id`,`productId`,`qty`,`reason`,`date`,`refType`,`refId`,`note` FROM `stock_moves`"
                )
                d.execSQL("DROP TABLE `stock_moves`")
                d.execSQL("ALTER TABLE `_new_stock_moves` RENAME TO `stock_moves`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_productId` ON `stock_moves` (`productId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_date` ON `stock_moves` (`date`)")

                // ── installment_plans: FK(partyId → parties RESTRICT) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_installment_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `direction` INTEGER NOT NULL, " +
                        "`total` REAL NOT NULL, `downPayment` REAL NOT NULL, `financed` REAL NOT NULL, " +
                        "`months` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `currency` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                d.execSQL(
                    "INSERT INTO `_new_installment_plans` (`id`,`title`,`partyId`,`direction`,`total`,`downPayment`," +
                        "`financed`,`months`,`startDate`,`currency`,`note`,`createdAt`,`archived`) " +
                        "SELECT `id`,`title`,`partyId`,`direction`,`total`,`downPayment`,`financed`,`months`," +
                        "`startDate`,`currency`,`note`,`createdAt`,`archived` FROM `installment_plans`"
                )
                d.execSQL("DROP TABLE `installment_plans`")
                d.execSQL("ALTER TABLE `_new_installment_plans` RENAME TO `installment_plans`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_partyId` ON `installment_plans` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_direction` ON `installment_plans` (`direction`)")

                // ── installments: FK(planId → installment_plans CASCADE) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `amount` REAL NOT NULL, " +
                        "`dueDate` INTEGER NOT NULL, `paidAmount` REAL NOT NULL, `paidDate` INTEGER, " +
                        "`status` INTEGER NOT NULL, FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                d.execSQL(
                    "INSERT INTO `_new_installments` (`id`,`planId`,`seq`,`amount`,`dueDate`,`paidAmount`,`paidDate`,`status`) " +
                        "SELECT `id`,`planId`,`seq`,`amount`,`dueDate`,`paidAmount`,`paidDate`,`status` FROM `installments`"
                )
                d.execSQL("DROP TABLE `installments`")
                d.execSQL("ALTER TABLE `_new_installments` RENAME TO `installments`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_planId` ON `installments` (`planId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueDate` ON `installments` (`dueDate`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_status` ON `installments` (`status`)")

                // ── journal_lines: FK(entryId → journal CASCADE, partyId → parties SET NULL) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_journal_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`entryId` INTEGER NOT NULL, `account` TEXT NOT NULL, `debit` REAL NOT NULL, " +
                        "`credit` REAL NOT NULL, `partyId` INTEGER, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, " +
                        "FOREIGN KEY(`entryId`) REFERENCES `journal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                d.execSQL(
                    "INSERT INTO `_new_journal_lines` (`id`,`entryId`,`account`,`debit`,`credit`,`partyId`,`currency`,`fxRate`) " +
                        "SELECT `id`,`entryId`,`account`,`debit`,`credit`,`partyId`,`currency`,`fxRate` FROM `journal_lines`"
                )
                d.execSQL("DROP TABLE `journal_lines`")
                d.execSQL("ALTER TABLE `_new_journal_lines` RENAME TO `journal_lines`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_entryId` ON `journal_lines` (`entryId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_account` ON `journal_lines` (`account`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_partyId` ON `journal_lines` (`partyId`)")

                // (M-3.6 توحيد): فهرس اسم الطرف من خط التدقيق — بلا إعادة بناء
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_name` ON `parties` (`name`)")
            }
        }

        /**
         * [P6-M13 إصلاح] — ترقية المخطط 5→6: فهرس مرجع حركات المخزون فقط.
         * كان deleteByRef(refType, refId) عند إلغاء/تعديل الفواتير يمسح جدول
         * stock_moves كاملاً لغياب أي فهرس على (refType, refId). CREATE INDEX فقط —
         * لا أعمدة ولا بيانات ولا قيود جديدة فلا خطر على بيانات المستخدم،
         * والفهرس غير فريد (ممنوع فهارس فريدة على invoices.number أو barcode
         * لتجنب رفض بيانات قائمة مكررة).
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_moves_refType_refId` " +
                        "ON `stock_moves` (`refType`, `refId`)"
                )
            }
        }

        /**
         * [P11-a] — ترقية المخطط 6→7: المفضّلون وبطاقة الموقع الجغرافي للأطراف.
         * ثلاثة أعمدة على parties فقط (ALTER TABLE ADD COLUMN — لا إعادة بناء ولا لمس بيانات):
         * favorite رقمية NOT NULL افتراضها 0 فيحفظ كل الأطراف القائمة غير مفضّلة،
         * وlat/lng حقيقيتان قابلتان للإلغاء (NULL = بلا موقع بعد).
         * مطابقة تماماً لحقول Party في Entities.kt بقيمها الافتراضية.
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `lat` REAL")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `lng` REAL")
            }
        }

        /**
         * [P12-b] — ترقية المخطط 7→8: جدول الزيارات (تقارير زيارات العملاء بربط الموقع).
         * CREATE TABLE + فهارس فقط — لا لمس لجداول البيانات القائمة ولا لبيانات المستخدم.
         * بنيته مطابقة تماماً لكيان Visit في Entities.kt (وإلا رفض Room فتح القاعدة):
         * id تلقائي، partyId/visitedAt NOT NULL، lat/lng حقيقيتان قابليتان للإلغاء
         * (زيارة بلا موقع GPS مسموحة)، note نصي NOT NULL افتراضه "".
         * بلا مفاتيح أجنبية عمداً — الزيارة سجل تاريخي يبقى بعد حذف طرفه
         * والتقرير يتخطى الأيتام (توثيق كامل عند الكيان).
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `visits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`partyId` INTEGER NOT NULL, `visitedAt` INTEGER NOT NULL, `lat` REAL, `lng` REAL, " +
                        "`note` TEXT NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_partyId` ON `visits` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_visitedAt` ON `visits` (`visitedAt`)")
            }
        }

        /**
         * [P17-a] — ترقية المخطط 8→9: كشف الحساب PDF.
         * ① أعمدة parties الثمانية الجديدة (ALTER TABLE ADD COLUMN — كلها TEXT قابلة للإلغاء،
         *    بلا قيم افتراضية SQL: مطابقة لكيان Party حيث القيم الافتراضية Kotlin = null،
         *    وRoom لا يكتب DEFAULT في مخططه للأعمدة الاختيارية، فالترتيب حرفي:
         *    email, address, taxNumber, crNumber, city, country, website, accountNumber).
         * ② ثمانية جداول جديدة CREATE TABLE + فهارسها — SQL مطابق حرفياً لما يولّده KSP
         *    في app/schemas/…/9.json (ترتيب الأعمدة، NOT NULL، نصوص المفاتيح الأجنبية،
         *    أسماء الفهارس القياسية index_<table>_<column> والأمم UNIQUE على
         *    statements.statementNumber/statements.verificationId/statement_deliveries.dedupKey).
         * لا إعادة بناء لأي جدول قائم ولا لمس لبيانات المستخدم.
         */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                // ── parties: 8 أعمدة كشف اختيارية ──
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `email` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `address` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `taxNumber` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `crNumber` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `city` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `country` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `website` TEXT")
                d.execSQL("ALTER TABLE `parties` ADD COLUMN `accountNumber` TEXT")

                // ── statement_templates: قوالب محفوظة (بلا مفاتيح أجنبية — JSON حر) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statement_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `baseTemplateId` TEXT NOT NULL, `configJson` TEXT NOT NULL, " +
                        "`isDefault` INTEGER NOT NULL, `favorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )

                // ── signatures ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signatures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `jobTitle` TEXT, `imagePath` TEXT NOT NULL, " +
                        "`isDefault` INTEGER NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )

                // ── stamps ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stamps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `imagePath` TEXT NOT NULL, " +
                        "`isDefault` INTEGER NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )

                // ── note_templates ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `body` TEXT NOT NULL, `isDefault` INTEGER NOT NULL)"
                )

                // ── statements: FK(partyId → parties RESTRICT) + فهارس فريدة للترقيم والتحقق ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`statementNumber` TEXT NOT NULL, `verificationId` TEXT NOT NULL, `partyId` INTEGER NOT NULL, " +
                        "`fromTs` INTEGER NOT NULL, `toTs` INTEGER NOT NULL, `templateId` TEXT NOT NULL, " +
                        "`currency` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `filePath` TEXT NOT NULL, " +
                        "`note` TEXT, `createdAt` INTEGER NOT NULL, `lang` TEXT NOT NULL, " +
                        "FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_statements_statementNumber` ON `statements` (`statementNumber`)")
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_statements_verificationId` ON `statements` (`verificationId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statements_partyId` ON `statements` (`partyId`)")

                // ── statement_deliveries: FK(statementId → statements CASCADE) + dedupKey فريد ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statement_deliveries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`statementId` INTEGER NOT NULL, `channel` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `lastError` TEXT, `sentAt` INTEGER, `scheduledFor` INTEGER, " +
                        "`dedupKey` TEXT NOT NULL, `lastAttemptAt` INTEGER, " +
                        "FOREIGN KEY(`statementId`) REFERENCES `statements`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_statement_deliveries_dedupKey` ON `statement_deliveries` (`dedupKey`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_deliveries_statementId` ON `statement_deliveries` (`statementId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_deliveries_status` ON `statement_deliveries` (`status`)")

                // ── statement_rules: بلا مفاتيح أجنبية (templateId نصي حر، signature/stamp اختيارية مرنة) ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statement_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `partyMode` TEXT NOT NULL, " +
                        "`partyIdsJson` TEXT NOT NULL, `frequency` TEXT NOT NULL, `weekday` INTEGER, " +
                        "`dayOfMonth` INTEGER, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, " +
                        "`periodPreset` TEXT NOT NULL, `templateId` TEXT, `signatureId` INTEGER, `stampId` INTEGER, " +
                        "`channel` TEXT NOT NULL, `eventFlagsJson` TEXT, `threshold` REAL, " +
                        "`lastRunAt` INTEGER, `nextRunAt` INTEGER)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_rules_enabled` ON `statement_rules` (`enabled`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_rules_nextRunAt` ON `statement_rules` (`nextRunAt`)")

                // ── audit_log: إلحاق فقط — فهرسا الزمن والفعل ──
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`actor` TEXT NOT NULL, `action` TEXT NOT NULL, `details` TEXT NOT NULL, `ts` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_ts` ON `audit_log` (`ts`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_action` ON `audit_log` (`action`)")
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // [P33-P8] MIGRATION_9_10 — الترحيل المالي الكامل: كل أعمدة المبالغ من REAL ريال
        // إلى INTEGER قروش بتحويل بقرش واحد ROUND(amount * 100) داخل معاملة واحدة —
        // كان هذا البند مؤجلاً من كـ"أوسع ترحيل في تاريخ المشروع" (خطة P8 موثقة
        // في ROADMAP). إعادة بناء جراحية لـ10 جداول (نمط الإعادة البنائية المجرّب 4→5)،
        // بلا destructive fallback، والفهارس تعاد بأسمائها الأصلية، وترتيب القيود كما هو.
        // ROUND() في SQLite تقرب نصف الوحدة بعيداً عن الصفر — مطابق لقرار HALF_UP
        // البعيد عن الصفر للمسالب المثبت منذ P6-M3، فأنصاف الهللات المخزنة تتحسم
        // قرشاً واحداً بنفس السياسة المالية المعتمدة.
        // ═════════════════════════════════════════════════════════════════════
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                // ── journal_lines: debit/credit → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_journal_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entryId` INTEGER NOT NULL, `account` TEXT NOT NULL, `debit` INTEGER NOT NULL, `credit` INTEGER NOT NULL, `partyId` INTEGER, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, FOREIGN KEY(`entryId`) REFERENCES `journal`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                d.execSQL(
                    "INSERT INTO `_new_journal_lines` (`id`,`entryId`,`account`,`debit`,`credit`,`partyId`,`currency`,`fxRate`) SELECT `id`,`entryId`,`account`,ROUND(`debit`*100),ROUND(`credit`*100),`partyId`,`currency`,`fxRate` FROM `journal_lines`"
                )
                d.execSQL("DROP TABLE `journal_lines`")
                d.execSQL("ALTER TABLE `_new_journal_lines` RENAME TO `journal_lines`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_entryId` ON `journal_lines` (`entryId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_account` ON `journal_lines` (`account`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_partyId` ON `journal_lines` (`partyId`)")

                // ── products: costPrice/salePrice → قروش (الكميات تبقى REAL) ──
                d.execSQL(
                    "CREATE TABLE `_new_products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sku` TEXT NOT NULL, `barcode` TEXT NOT NULL, `unit` TEXT NOT NULL, `costPrice` INTEGER NOT NULL, `salePrice` INTEGER NOT NULL, `stockQty` REAL NOT NULL, `reorderLevel` REAL NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)"
                )
                d.execSQL(
                    "INSERT INTO `_new_products` (`id`,`name`,`sku`,`barcode`,`unit`,`costPrice`,`salePrice`,`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived`) SELECT `id`,`name`,`sku`,`barcode`,`unit`,ROUND(`costPrice`*100),ROUND(`salePrice`*100),`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived` FROM `products`"
                )
                d.execSQL("DROP TABLE `products`")
                d.execSQL("ALTER TABLE `_new_products` RENAME TO `products`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_products_archived` ON `products` (`archived`)")

                // ── invoices: المبالغ الستة → قروش (taxRate/fxRate نسب تبقى REAL) ──
                // ملاحظة: products مُشارة إليها بFK من invoice_items — إعادة البناء بلا
                // FOREIGN_KEYS مفروضة (Room يُشغلها في onOpen بعد الترحيل) فالترتيب حر
                // والتحقق يعود بعد اكتمال كل الجداول.
                d.execSQL(
                    "CREATE TABLE `_new_invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `type` INTEGER NOT NULL, `date` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `subtotal` INTEGER NOT NULL, `discount` INTEGER NOT NULL, `taxRate` REAL NOT NULL, `taxAmount` INTEGER NOT NULL, `total` INTEGER NOT NULL, `paid` INTEGER NOT NULL, `costTotal` INTEGER NOT NULL, `status` INTEGER NOT NULL, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, `note` TEXT NOT NULL, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
                d.execSQL(
                    "INSERT INTO `_new_invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) SELECT `id`,`number`,`partyId`,`type`,`date`,`dueDate`,ROUND(`subtotal`*100),ROUND(`discount`*100),`taxRate`,ROUND(`taxAmount`*100),ROUND(`total`*100),ROUND(`paid`*100),ROUND(`costTotal`*100),`status`,`currency`,`fxRate`,`note` FROM `invoices`"
                )
                d.execSQL("DROP TABLE `invoices`")
                d.execSQL("ALTER TABLE `_new_invoices` RENAME TO `invoices`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_partyId` ON `invoices` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_date` ON `invoices` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_status` ON `invoices` (`status`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_number` ON `invoices` (`number`)")

                // ── invoice_items: unitPrice/discount → قروش (qty كمية تبقى REAL) ──
                d.execSQL(
                    "CREATE TABLE `_new_invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `productId` INTEGER, `desc` TEXT NOT NULL, `qty` REAL NOT NULL, `unitPrice` INTEGER NOT NULL, `discount` INTEGER NOT NULL, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                d.execSQL(
                    "INSERT INTO `_new_invoice_items` (`id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) SELECT `id`,`invoiceId`,`productId`,`desc`,`qty`,ROUND(`unitPrice`*100),ROUND(`discount`*100) FROM `invoice_items`"
                )
                d.execSQL("DROP TABLE `invoice_items`")
                d.execSQL("ALTER TABLE `_new_invoice_items` RENAME TO `invoice_items`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")

                // ── payments: amount → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `partyId` INTEGER, `invoiceId` INTEGER, `checkId` INTEGER, `amount` INTEGER NOT NULL, `date` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `method` TEXT NOT NULL, `note` TEXT NOT NULL, `planId` INTEGER, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`checkId`) REFERENCES `checks`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                d.execSQL(
                    "INSERT INTO `_new_payments` (`id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`,`planId`) SELECT `id`,`partyId`,`invoiceId`,`checkId`,ROUND(`amount`*100),`date`,`direction`,`method`,`note`,`planId` FROM `payments`"
                )
                d.execSQL("DROP TABLE `payments`")
                d.execSQL("ALTER TABLE `_new_payments` RENAME TO `payments`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_partyId` ON `payments` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_date` ON `payments` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_checkId` ON `payments` (`checkId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_planId` ON `payments` (`planId`)")

                // ── checks: amount → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `bank` TEXT NOT NULL, `amount` INTEGER NOT NULL, `issueDate` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `status` INTEGER NOT NULL, `note` TEXT NOT NULL, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
                d.execSQL(
                    "INSERT INTO `_new_checks` (`id`,`number`,`partyId`,`bank`,`amount`,`issueDate`,`dueDate`,`direction`,`status`,`note`) SELECT `id`,`number`,`partyId`,`bank`,ROUND(`amount`*100),`issueDate`,`dueDate`,`direction`,`status`,`note` FROM `checks`"
                )
                d.execSQL("DROP TABLE `checks`")
                d.execSQL("ALTER TABLE `_new_checks` RENAME TO `checks`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_partyId` ON `checks` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_dueDate` ON `checks` (`dueDate`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_status` ON `checks` (`status`)")

                // ── installment_plans: المبالغ الثلاثة → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_installment_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `total` INTEGER NOT NULL, `downPayment` INTEGER NOT NULL, `financed` INTEGER NOT NULL, `months` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `currency` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                )
                d.execSQL(
                    "INSERT INTO `_new_installment_plans` (`id`,`title`,`partyId`,`direction`,`total`,`downPayment`,`financed`,`months`,`startDate`,`currency`,`note`,`createdAt`,`archived`) SELECT `id`,`title`,`partyId`,`direction`,ROUND(`total`*100),ROUND(`downPayment`*100),ROUND(`financed`*100),`months`,`startDate`,`currency`,`note`,`createdAt`,`archived` FROM `installment_plans`"
                )
                d.execSQL("DROP TABLE `installment_plans`")
                d.execSQL("ALTER TABLE `_new_installment_plans` RENAME TO `installment_plans`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_partyId` ON `installment_plans` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_direction` ON `installment_plans` (`direction`)")

                // ── installments: amount/paidAmount → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `paidAmount` INTEGER NOT NULL, `paidDate` INTEGER, `status` INTEGER NOT NULL, FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                d.execSQL(
                    "INSERT INTO `_new_installments` (`id`,`planId`,`seq`,`amount`,`dueDate`,`paidAmount`,`paidDate`,`status`) SELECT `id`,`planId`,`seq`,ROUND(`amount`*100),`dueDate`,ROUND(`paidAmount`*100),`paidDate`,`status` FROM `installments`"
                )
                d.execSQL("DROP TABLE `installments`")
                d.execSQL("ALTER TABLE `_new_installments` RENAME TO `installments`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_planId` ON `installments` (`planId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueDate` ON `installments` (`dueDate`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_status` ON `installments` (`status`)")

                // ── expenses: amount → قروش ──
                d.execSQL(
                    "CREATE TABLE `_new_expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amount` INTEGER NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, `date` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                d.execSQL(
                    "INSERT INTO `_new_expenses` (`id`,`amount`,`category`,`note`,`date`,`createdAt`) SELECT `id`,ROUND(`amount`*100),`category`,`note`,`date`,`createdAt` FROM `expenses`"
                )
                d.execSQL("DROP TABLE `expenses`")
                d.execSQL("ALTER TABLE `_new_expenses` RENAME TO `expenses`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_category` ON `expenses` (`category`)")

                // ── statement_rules: threshold → قروش (NULL يبقى NULL) ──
                d.execSQL(
                    "CREATE TABLE `_new_statement_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `partyMode` TEXT NOT NULL, `partyIdsJson` TEXT NOT NULL, `frequency` TEXT NOT NULL, `weekday` INTEGER, `dayOfMonth` INTEGER, `hour` INTEGER NOT NULL, `minute` INTEGER NOT NULL, `periodPreset` TEXT NOT NULL, `templateId` TEXT, `signatureId` INTEGER, `stampId` INTEGER, `channel` TEXT NOT NULL, `eventFlagsJson` TEXT, `threshold` INTEGER, `lastRunAt` INTEGER, `nextRunAt` INTEGER)"
                )
                d.execSQL(
                    "INSERT INTO `_new_statement_rules` (`id`,`name`,`enabled`,`partyMode`,`partyIdsJson`,`frequency`,`weekday`,`dayOfMonth`,`hour`,`minute`,`periodPreset`,`templateId`,`signatureId`,`stampId`,`channel`,`eventFlagsJson`,`threshold`,`lastRunAt`,`nextRunAt`) SELECT `id`,`name`,`enabled`,`partyMode`,`partyIdsJson`,`frequency`,`weekday`,`dayOfMonth`,`hour`,`minute`,`periodPreset`,`templateId`,`signatureId`,`stampId`,`channel`,`eventFlagsJson`,CASE WHEN `threshold` IS NULL THEN NULL ELSE ROUND(`threshold`*100) END,`lastRunAt`,`nextRunAt` FROM `statement_rules`"
                )
                d.execSQL("DROP TABLE `statement_rules`")
                d.execSQL("ALTER TABLE `_new_statement_rules` RENAME TO `statement_rules`")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_rules_enabled` ON `statement_rules` (`enabled`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_statement_rules_nextRunAt` ON `statement_rules` (`nextRunAt`)")
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // [P41-L1] MIGRATION_10_11 — الحقول الضريبية على مستوى السطر + تمييز
        // صفرية/معفاة: عمودان على invoice_items ببذرتين محايدتين محفوظتين الدلالة:
        //  taxKind INTEGER NOT NULL DEFAULT 0  (0 قياسية S — دلالة كل الصفوف التاريخية)
        //  taxRate REAL NOT NULL DEFAULT -1.0  (سالب = وراثة نسبة الرأس — السلوك التاريخي)
        // ALTER TABLE ADD COLUMN لا يعيد بناء الجدول ولا يمس صفاً ولا مفتاحاً
        // أجنبياً ولا فهرساً — أهدأ ترحيل في تاريخ المشروع بعد v10، وبلا أي
        // destructive fallback (قاعدة المشروع غير القابلة للتفاوض). المسار
        // الواعي بالسطر لا يُفتح إلا بسطر صريح — الصفوف المبذورة تبقى سلوكها
        // التاريخي حرفياً (LineTaxP41 هو صندوق الرياضيات الموحد).
        // ═════════════════════════════════════════════════════════════════════
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL("ALTER TABLE `invoice_items` ADD COLUMN `taxKind` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `invoice_items` ADD COLUMN `taxRate` REAL NOT NULL DEFAULT -1.0")
            }
        }

        // ═════════════════════════════════════════════════════════════════
        // [P46-W1] MIGRATION_11_12 — نقاط الولاء والكوبونات: جدولان بإنشاء فقط.
        // لا جدول قائم يُمس ولا عمود يُضاف ولا فهرس يُعاد بناؤه — أهدأ ترحيل بعد
        // 10→11، وبلا أي destructive fallback (قاعدة المشروع غير القابلة للتفاوض).
        // بنية SQL مطابقة حرفياً لما يولده Room عن الكيانين (12.json) — الألفات
        // والمفاتيح الأجنبية ON DELETE CASCADE والفهرس الفريد للكود.
        // ═════════════════════════════════════════════════════════════════
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loyalty_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `partyId` INTEGER NOT NULL, `invoiceId` INTEGER, `delta` INTEGER NOT NULL, `reason` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`partyId`) REFERENCES `parties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_loyalty_entries_partyId` ON `loyalty_entries` (`partyId`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_loyalty_entries_invoiceId` ON `loyalty_entries` (`invoiceId`)")
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `coupons` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `kind` INTEGER NOT NULL, `amountPiasters` INTEGER NOT NULL, `percent` REAL NOT NULL, `expiresAt` INTEGER NOT NULL, `maxUses` INTEGER NOT NULL, `usedCount` INTEGER NOT NULL, `active` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_coupons_code` ON `coupons` (`code`)")
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // [H1-3][v13] MIGRATION_12_13 — الترحيل المشترك: RBAC + ZATCA-2 في ترحيل واحد
        // ═══════════════════════════════════════════════════════════════════════
        // عقد التصميم (RBAC_V13_DESIGN §4 + ZATCA2_WAVE2_PLAN §2: «الترحيلان يُنفّان
        // معاً في v13») — ترحيل إلحاقي ذرّي خالص:
        //   1. CREATE TABLE users + user_secrets + فهارسهما (إنشاء فقط — سابقة 11→12
        //      بلا DEFAULT في CREATE لأن Room يولّده بلا DEFAULT، والافتراضات Kotlin
        //      في الكيانات هي بذور الإدراج على مستوى التطبيق)
        //   2. ALTER TABLE audit_log بعمودَي الإسناد (nullable — NULL دلالته «قبل التبني»)
        //   3. ALTER TABLE invoices بتسعة أعمدة هوية ZATCA-2 ببذور آمنة (سابقة 10→11:
        //      NOT NULL DEFAULT إلزامي في SQLite للعمود المُضاف)
        //   4. زرع المالك داخل المعاملة نفسها — من مزوّد بذرة DataStore المتزامن
        //      (ownerPinSeedProvider) بنسخ نص للمغلّف ks: لا إعادة تشفير ولا مسّ Keystore،
        //      وبلا حماية قائمة يُزرع المالك بلا سر (أول دخول يطلب إنشاء PIN)
        // لا تعديل عمود قائم، لا إعادة تسمية، لا حذف — صفر صف يُعاد كتابته عدا سطر
        // المالك المزروع المعلن. كل الخطوات execSQL متتالية ضمن معاملة Room التلقائية
        // للمهاجر — فشل في منتصفها يعيد v12 كما كانت بلا حالة نصفية ممكنة.
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1) المستخدمون — إنشاء فقط (البنية مطابقة لما يولّده Room عن UserEntity)
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `users` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `role` INTEGER NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_users_role` ON `users` (`role`)" )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_users_active` ON `users` (`active`)")
                // 2) أسرار الدخول — صيغة PinVault لكل مستخدم (CASCADE مع مالكها)
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_secrets` (`userId` INTEGER PRIMARY KEY NOT NULL, `pinWrapped` TEXT NOT NULL, `pinSalt` TEXT NOT NULL, `pinIters` INTEGER NOT NULL, `biometricAllowed` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                // 3) إسناد التدقيق — أعمدة nullable بلا افتراض (NULL دلالة «قبل التبني»)
                d.execSQL("ALTER TABLE `audit_log` ADD COLUMN `actorId` INTEGER")
                d.execSQL("ALTER TABLE `audit_log` ADD COLUMN `actorRole` INTEGER")
                // 4) هوية ZATCA مرحلة-2 على الفواتير — بذور آمنة محايدة دلالياً (سابقة 10→11)
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `icv` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `pih` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `zatcaSubtype` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `deliveryDate` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `buyerName` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `buyerVat` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `buyerAddress` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `zatcaStatus` INTEGER NOT NULL DEFAULT 0")
                // 5) زرع المالك الافتراضي داخل نفس المعاملة — بلا سيناريو ثالث:
                //    الجدول جديد بنيوياً فوجود صفوف مسبقاً مستحيل، والفحص تحصين حاسم
                d.execSQL(
                    "INSERT INTO `users` (`name`, `role`, `active`, `createdAt`, `lastSeenAt`) " +
                        "SELECT 'المالك', 0, 1, " + System.currentTimeMillis() + ", 0 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM `users`)"
                )
                //    مادة الرمز القائمة تُنسخ نصاً (ks:<iv>:<ct> كما هي — لا إعادة تشفير،
                //    المفتاح في Keystore لا يُمس داخل الترحيل). بلا حماية قائمة يبقى
                //    المالك بلا صف سر — أول دخول بعد الترقية يطلب إنشاء PIN للمالك.
                ownerPinSeedProvider?.invoke()?.let { seed ->
                    d.execSQL(
                        "INSERT INTO `user_secrets` (`userId`, `pinWrapped`, `pinSalt`, `pinIters`, `biometricAllowed`) " +
                            "SELECT `id`, ?, ?, ?, ? FROM `users` WHERE `role` = 0 " +
                            "AND NOT EXISTS (SELECT 1 FROM `user_secrets`)",
                        arrayOf(seed.pinWrapped, seed.pinSalt, seed.pinIters, if (seed.biometric) 1 else 0)
                    )
                }
            }
        }

        // [Z2-أ V 1.5.0] ترحيل 13→14 — أرشيف مستندات ZATCA-2: جدول واحد بإنشاء فقط
        // (عقد الترحيلات: إلحاقي خالص — CREATE TABLE/INDEX فقط، لا جدول قائم يُمس
        // ولا صف يُعاد كتابته، وبذور القيم كلها في الكيان الافتراضية محايدة دلالياً.
        // قاعدة v13 سليمة تماماً بلا الجدول — الأرشيف يبدأ فارغاً ويُملأ من الإصدارات
        // الجديدة فقط، والفواتير القائمة تبقى غير مختومة بقرارها الموثق).
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `zatca_docs` (`invoiceId` INTEGER PRIMARY KEY NOT NULL, `xml` TEXT NOT NULL, `xmlHash` TEXT NOT NULL, `subtype` TEXT NOT NULL, `issuedAt` INTEGER NOT NULL, `reportedAt` INTEGER NOT NULL, `rejectReason` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `clearedXml` TEXT NOT NULL, FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_zatca_docs_invoiceId` ON `zatca_docs` (`invoiceId`)")
            }
        }

        // [H4-1 V 2.5.0] ترحيل 14→15 — ختم الفئة الأصلية للعملات المتعددة: 6 أعمدة إلحاقية
        // (3 على invoices + 3 على expenses) ببذور محايدة دلالياً: origCurrency="" يعني
        // «الفئة بالأساس نفسه» — كل الصفوف التاريخية سليمة دلالتها حرفياً بلا أي مسّ،
        // وأعمدة المبالغ القائمة تبقى قروش الأساس كما هي (وحدة القياس الموحدة P33-P8
        // لا تتغير). نفس سابقة 10→11 و13 (ALTER ADD COLUMN NOT NULL DEFAULT).
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `origCurrency` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `origTotal` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `invoices` ADD COLUMN `origFxMicros` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `expenses` ADD COLUMN `origCurrency` TEXT NOT NULL DEFAULT ''")
                d.execSQL("ALTER TABLE `expenses` ADD COLUMN `origTotal` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `expenses` ADD COLUMN `origFxMicros` INTEGER NOT NULL DEFAULT 0")
            }
        }


        /**
         * [H4-3][v16] ترحيل المزامنة E2E (ADR-002 D3/D4) — إلحاقي خالص:
         * 1) أعمدة هوية وساعة على الجداول التسعة القابلة للمزامنة (DEFAULT محايدة).
         * 2) sync_log — دفتر تغييرات المزامنة.
         * 3) _sync_applying — حارس مشغّلات (صف واحد) يكبت الالتقاط أثناء تطبيق
         *    الوارد كي لا تنتفخ ساعة LWW ولا يعود الصف المستورد دفتراً.
         * 4) 27 مشغّلاً: لكل جدول (INSERT/UPDATE يلمسان الساعة ويدفعان دفتراً،
         *    DELETE يكتب شاهداً) — التقاط صفري اللمس لكتابات Room والاستعادة كليهما.
         * العقد: ساعة LWW لا يكتبها كود التطبيق إطلاقاً — مشغّلات فقط.
         */
        /**
         * حراسة المشغّلات دلالية لا تعتمد recursive_triggers (يواجه "too many levels
         * of trigger recursion" الذي كشفه اختبار التقارب):
         * - INSERT يشترط ساعة = 0 (إدخال محلي؛ إدراج المحرك يحمل ساعة واردة > 0).
         * - UPDATE يشترط ساعة غير متغيّرة (تحرير مستخدم يكتب الكيان بساعته القديمة؛
         *   تطبيق المحرك يكتب ساعة واردة جديدة فلا يُطلق شيئاً — التعرّش ينقطع عند 1).
         * - DELETE خلف حارس العلم — المحرك يسجل شواهد الوارد بنفسه (imported=1).
         */
        private fun syncTouchSql(table: String, originNew: String, originOld: String): List<String> {
            val nowMs = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
            // هوية الدفتر: صف أصلي (originDeviceId='') يُعرَّف بمعرّفه المحلي NEW.id،
            // وصف مستورد يُعرَّف بهوية أصلية NEW.originId — CASE واحد يحسمها في SQL
            val oidNew = "CASE WHEN NEW.`originDeviceId` = '' THEN NEW.`id` ELSE NEW.`originId` END"
            val oidOld = "CASE WHEN OLD.`originDeviceId` = '' THEN OLD.`id` ELSE OLD.`originId` END"
            val ledgerLive = "INSERT INTO `sync_log` (`tableName`,`originDeviceId`,`originId`,`updatedAt`,`deleted`,`imported`,`at`) " +
                "VALUES ('$table', $originNew, $oidNew, (SELECT syncUpdatedAt FROM `$table` WHERE rowid = NEW.rowid), 0, 0, $nowMs)"
            val ledgerDel = "INSERT INTO `sync_log` (`tableName`,`originDeviceId`,`originId`,`updatedAt`,`deleted`,`imported`,`at`) " +
                "VALUES ('$table', $originOld, $oidOld, OLD.`syncUpdatedAt`, 1, 0, $nowMs)"
            return listOf(
                "CREATE TRIGGER IF NOT EXISTS `trg_sync_${table}_i` AFTER INSERT ON `$table` " +
                    "WHEN NEW.`syncUpdatedAt` = 0 BEGIN " +
                    "UPDATE `$table` SET `syncUpdatedAt` = MAX((SELECT syncUpdatedAt FROM `$table` WHERE rowid = NEW.rowid) + 1, $nowMs) WHERE rowid = NEW.rowid; " +
                    ledgerLive + "; END",
                "CREATE TRIGGER IF NOT EXISTS `trg_sync_${table}_u` AFTER UPDATE ON `$table` " +
                    "WHEN OLD.`syncUpdatedAt` = NEW.`syncUpdatedAt` BEGIN " +
                    "UPDATE `$table` SET `syncUpdatedAt` = MAX((SELECT syncUpdatedAt FROM `$table` WHERE rowid = NEW.rowid) + 1, $nowMs) WHERE rowid = NEW.rowid; " +
                    ledgerLive + "; END",
                "CREATE TRIGGER IF NOT EXISTS `trg_sync_${table}_d` AFTER DELETE ON `$table` " +
                    "WHEN (SELECT v FROM `_sync_applying`) = 0 BEGIN " +
                    ledgerDel + "; END"
            )
        }

        /**
         * [H4-3] SQL زمن التشغيل للمزامنة: الحارس + 27 مشغّلاً — مشترك بين
         * MIGRATION_15_16 واختبار التقارب (قواعد الذاكرة تبنيه بعد فتح v16).
         */
        internal fun syncRuntimeSql(): List<String> {
            val identityTables = listOf("parties", "products", "visits", "coupons", "statement_templates", "signatures", "stamps", "note_templates")
            val out = mutableListOf(
                "CREATE TABLE IF NOT EXISTS `_sync_applying` (`v` INTEGER NOT NULL)",
                "INSERT OR IGNORE INTO `_sync_applying` (`v`) VALUES (0)"
            )
            for (t in identityTables) out += syncTouchSql(t, "NEW.`originDeviceId`", "OLD.`originDeviceId`")
            out += syncTouchSql("currencies", "NEW.`code`", "OLD.`code`")
            return out
        }

        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(d: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1) أعمدة الهوية والساعة — 8 جداول بهوية أصل، والعملات بساعة حصراً
                val identityTables = listOf("parties", "products", "visits", "coupons", "statement_templates", "signatures", "stamps", "note_templates")
                for (t in identityTables) {
                    d.execSQL("ALTER TABLE `$t` ADD COLUMN `syncUpdatedAt` INTEGER NOT NULL DEFAULT 0")
                    d.execSQL("ALTER TABLE `$t` ADD COLUMN `originDeviceId` TEXT NOT NULL DEFAULT ''")
                    d.execSQL("ALTER TABLE `$t` ADD COLUMN `originId` INTEGER NOT NULL DEFAULT 0")
                }
                d.execSQL("ALTER TABLE `currencies` ADD COLUMN `syncUpdatedAt` INTEGER NOT NULL DEFAULT 0")
                d.execSQL("ALTER TABLE `visits` ADD COLUMN `partyRef` TEXT NOT NULL DEFAULT ''")

                // 2) الدفتر + 3) الحارس + 4) المشغّلات — من المساعد المشترك مع الاختبارات
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tableName` TEXT NOT NULL, `originDeviceId` TEXT NOT NULL, `originId` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, " +
                        "`imported` INTEGER NOT NULL DEFAULT 0, `at` INTEGER NOT NULL)"
                )
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_log_imported` ON `sync_log` (`imported`)")
                d.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_log_updatedAt` ON `sync_log` (`updatedAt`)")
                for (sql in syncRuntimeSql()) d.execSQL(sql)
            }
        }

        // قائمة الترقيات مكشوفة للاختبارات (SchemaMigrationTest يشغّل كل ترحيل فعلياً) —
        // تُعرّف بعد الترحيلات لأن تهيئة خصائص Kotlin تتم بترتيب الإعلان
        // [P11-a]: MIGRATION_6_7 أُلحقت بالنهاية — الفهرسة بالموضع في الاختبارات تبقى صحيحة
        // [P12-b]: MIGRATION_7_8 أُلحقت بالنهاية كذلك — فهرس 6 هو ترحيل الزيارات
        // [P17-a]: MIGRATION_8_9 أُلحقت بالنهاية — الفهرس 7 هو ترحيل كشف الحساب
        // [P41-L1]: MIGRATION_10_11 أُلحقت بالنهاية — الفهرس 9 هو حقول السطر الضريبية
        // [P46-W1]: MIGRATION_11_12 أُلحقت بالنهاية — الفهرس 10 هو جداول الولاء والكوبونات
        // [H1-3]: MIGRATION_12_13 أُلحقت بالنهاية — الفهرس 11 هو RBAC + ZATCA-2 المشترك
        // [Z2-أ]: MIGRATION_13_14 أُلحقت بالنهاية — الفهرس 12 هو أرشيف zatca_docs
        // [H4-1]: MIGRATION_14_15 أُلحقت بالنهاية — الفهرس 13 هو ختم الفئة الأصلية
        internal val MIGRATIONS = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)

        /**
         * [H1-3] مزوّد بذرة رمز المالك — يُربط في AppGraph.db قبل بناء القاعدة بقراءة
         * DataStore المتزامنة (SettingsRepo.readOwnerPinSeedSync) لينقل الترحيل مادة
         * الرمز القائمة إلى user_secrets بنسخ نص. قابل للاستبدال في الاختبارات
         * (بذرة اصطناعية أو null لمحاكاة «لا حماية قائمة»).
         */
        internal var ownerPinSeedProvider: (() -> OwnerPinSeed?)? = null

        @Volatile private var instance: AppGraph? = null
        fun get(context: android.content.Context): AppGraph {
            val app = context.applicationContext as Application
            return instance ?: synchronized(this) {
                instance ?: AppGraph(app).also { instance = it }
            }
        }

        fun from(context: android.content.Context): AppGraph = get(context)
    }
}

class SuperBizApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.get(this)
        createNotificationChannels()
        // (M-3.9 توحيد): بذر الإقلاع كان launch مجرداً بلا معالج استثناءات — فشل بذر
        // واحد (قرص ممتلئ، قاعدة مقفلة…) كان يغلق العملية في حلقة انهيار عند كل إقلاع.
        // الآن: معالج مسجّل + 3 محاولات بفواصل، والجدولة تُنفّذ في كل الأحوال.
        val seedHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.e("SuperBizApp", "seedIfFirstRun failed after retries", e)
        }
        graph.appScope.launch(seedHandler) {
            var ok = false
            for (attempt in 1..3) {
                try {
                    graph.seedIfFirstRun()
                    ok = true
                    break
                } catch (e: Exception) {
                    android.util.Log.e("SuperBizApp", "seed attempt $attempt failed", e)
                    kotlinx.coroutines.delay(2_000L * attempt)
                }
            }
            if (!ok) android.util.Log.e("SuperBizApp", "seeding failed 3 times — running degraded until next start")
            try {
                com.superbiz.app.work.AutomationWorker.schedule(graph)
            } catch (e: Exception) {
                android.util.Log.e("SuperBizApp", "worker schedule failed", e)
            }
        }

        // [P14-a] إعادة جدولة تذكير «العملاء الذين لم يُزاروا» عند بدء العملية —
        // [P20-FIX agent14]: force=true إلزامي — Force Stop وتحديث الحزمة كلاهما يقتلان كل
        // منبّهات AlarmManager بينما armedFor في SharedPreferences ينجو، فكان الإقلاع التالي
        // يرى موعداً مستقبلياً ويترك إعادة التسليح = تذكير مفقود يوماً على الأقل. إعادة التسليح
        // بنفس REQUEST_CODE و FLAG_UPDATE_CURRENT idempotent بلا ثمن يُذكر
        // reattach نفسها دفاعية، والغلاف هنا كي لا يُغلق أي فشل غير متوقع الإقلاع كله.
        try {
            com.superbiz.app.work.VisitReminder.reattach(this, force = true)
        } catch (_: Exception) {
        }

        // [P18-b][P18-int] مجدول كشوف الحساب الدوري — تقييم القواعد كل 15 دقيقة
        // (KEEP: الدورة ثابتة لا إعدادات تغيّرها؛ العامل ينجح فوراً بلا قواعد مفعلة)
        try {
            com.superbiz.app.work.StatementScheduleWorker.ensure(this)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannels() {
        // حماية NotificationChannel — تتطلب API 26 وminSdk هو 24 (كانت تُغلق التطبيق عند الإقلاع على Android 7)
        // [P30-B]: كانت قناة واحدة لكل شيء — المستخدم لا يستطيع كتم التقارير والإبقاء على
        // تنبيهات السداد. أربع قنوات بأسماء وأوصاف موطّنين: التنبيهات (افتراضي) والتقارير
        // والنسخ الاحتياطي (منخفضة — وثائق لا تستدعي مقاطعة) والزيارات (افتراضي).
        // القناة الأصلية محفوظة بمعرفها فلا تنكسر إعدادات المستخدمين الحاليين، وكل
        // مرسل ينضم لقناته (AutomationLogic.notify / ReportSchedule / VisitReminder).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            fun channel(id: String, nameRes: Int, descRes: Int, importance: Int) =
                NotificationChannel(id, getString(nameRes), importance)
                    .apply { description = getString(descRes) }
            nm.createNotificationChannel(channel(
                CHANNEL_ALERTS, R.string.notif_channel_main,
                R.string.notif_channel_main_desc, NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(channel(
                CHANNEL_REPORTS, R.string.notif_channel_reports,
                R.string.notif_channel_reports_desc, NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(channel(
                CHANNEL_BACKUP, R.string.notif_channel_backup,
                R.string.notif_channel_backup_desc, NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(channel(
                CHANNEL_VISITS, R.string.notif_channel_visits,
                R.string.notif_channel_visits_desc, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "superbiz_alerts"

        // [P30-B] قنوات متخصصة — معرفات ثابتة لا تتغير عبر الإصدارات
        const val CHANNEL_REPORTS = "superbiz_reports"
        const val CHANNEL_BACKUP = "superbiz_backup"
        const val CHANNEL_VISITS = "superbiz_visits"
    }
}
