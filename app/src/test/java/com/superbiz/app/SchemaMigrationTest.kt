package com.superbiz.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * (M-3.6): ترقية المخطط 3→4 — فهارس أداء فقط (لا أعمدة ولا بيانات).
 * يُنشأ ملف بنيته الدنيا للإصدار 3 ثم يُشغَّل الترحيل فعلياً، ثم يُتحقق من أن
 * أسماء الفهارس الخمسة طالعة بأسماء Room المتوقعة حرفياً (وإلا يرفض Room فتح القاعدة).
 *
 * (M-3.3/M-3.10): إضافة ترقية 4→5 — إعادة بناء 8 جداول بالمفاتيح الأجنبية
 * (RESTRICT/SET NULL/CASCADE) وعمود payments.planId، على بنية v4 كاملة الجداول،
 * مع التحقق: حفظ البيانات، القيود في sqlite_master، foreign_key_check نظيف،
 * وفرض القيود عند الكتابة.
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchemaMigrationTest {

    private fun minimalV3Schema(db: SupportSQLiteDatabase) {
        // بنية دنيا بالأعمدة النهائية نفسها (v4 لا يغيّر الأعمدة) — يكفي لإنشاء الفهارس
        db.execSQL("CREATE TABLE `journal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` INTEGER NOT NULL, `memo` TEXT NOT NULL, `refType` TEXT, `refId` INTEGER)")
        db.execSQL("CREATE TABLE `parties` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `type` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `type` INTEGER NOT NULL, `date` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `subtotal` REAL NOT NULL, `discount` REAL NOT NULL, `taxRate` REAL NOT NULL, `taxAmount` REAL NOT NULL, `total` REAL NOT NULL, `paid` REAL NOT NULL, `costTotal` REAL NOT NULL, `status` INTEGER NOT NULL, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE `payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `partyId` INTEGER, `invoiceId` INTEGER, `checkId` INTEGER, `amount` REAL NOT NULL, `date` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `method` TEXT NOT NULL, `note` TEXT NOT NULL)")
        // خط التطوير أضاف فهرس archived للمنتجات في 3→4 — الجدول لازم في البنية الدنيا
        db.execSQL("CREATE TABLE `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sku` TEXT NOT NULL, `barcode` TEXT NOT NULL, `unit` TEXT NOT NULL, `costPrice` REAL NOT NULL, `salePrice` REAL NOT NULL, `stockQty` REAL NOT NULL, `reorderLevel` REAL NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
        // فهارس v3 القائمة أصلاً
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_partyId` ON `invoices` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_date` ON `invoices` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_status` ON `invoices` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_partyId` ON `payments` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_date` ON `payments` (`date`)")
    }

    /** بنية v4 كاملة — مصدر الحقيقة لترحيل 4→5 (كل الأعمدة والفهارس كما في ) */
    private fun fullV4Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `parties` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `type` INTEGER NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `journal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` INTEGER NOT NULL, `memo` TEXT NOT NULL, `refType` TEXT, `refId` INTEGER)")
        db.execSQL("CREATE TABLE `journal_lines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entryId` INTEGER NOT NULL, `account` TEXT NOT NULL, `debit` REAL NOT NULL, `credit` REAL NOT NULL, `partyId` INTEGER, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL)")
        db.execSQL("CREATE TABLE `products` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sku` TEXT NOT NULL, `barcode` TEXT NOT NULL, `unit` TEXT NOT NULL, `costPrice` REAL NOT NULL, `salePrice` REAL NOT NULL, `stockQty` REAL NOT NULL, `reorderLevel` REAL NOT NULL, `category` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `stock_moves` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `productId` INTEGER NOT NULL, `qty` REAL NOT NULL, `reason` TEXT NOT NULL, `date` INTEGER NOT NULL, `refType` TEXT, `refId` INTEGER, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE `invoices` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `type` INTEGER NOT NULL, `date` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `subtotal` REAL NOT NULL, `discount` REAL NOT NULL, `taxRate` REAL NOT NULL, `taxAmount` REAL NOT NULL, `total` REAL NOT NULL, `paid` REAL NOT NULL, `costTotal` REAL NOT NULL, `status` INTEGER NOT NULL, `currency` TEXT NOT NULL, `fxRate` REAL NOT NULL, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE `invoice_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `invoiceId` INTEGER NOT NULL, `productId` INTEGER, `desc` TEXT NOT NULL, `qty` REAL NOT NULL, `unitPrice` REAL NOT NULL, `discount` REAL NOT NULL)")
        db.execSQL("CREATE TABLE `payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `partyId` INTEGER, `invoiceId` INTEGER, `checkId` INTEGER, `amount` REAL NOT NULL, `date` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `method` TEXT NOT NULL, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE `checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `bank` TEXT NOT NULL, `amount` REAL NOT NULL, `issueDate` INTEGER NOT NULL, `dueDate` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `status` INTEGER NOT NULL, `note` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE `rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `daysBefore` INTEGER NOT NULL, `lastRun` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `currencies` (`code` TEXT NOT NULL, `nameAr` TEXT NOT NULL, `nameEn` TEXT NOT NULL, `symbol` TEXT NOT NULL, `rateToBase` REAL NOT NULL, `isBase` INTEGER NOT NULL, PRIMARY KEY(`code`))")
        db.execSQL("CREATE TABLE `installment_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `partyId` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `total` REAL NOT NULL, `downPayment` REAL NOT NULL, `financed` REAL NOT NULL, `months` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `currency` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `installments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `amount` REAL NOT NULL, `dueDate` INTEGER NOT NULL, `paidAmount` REAL NOT NULL, `paidDate` INTEGER, `status` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `amount` REAL NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, `date` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        // فهارس v4 (v3 + إضافات 3→4) — : تكييف لفهارس خط التطوير (archived) بجانب فهارس التدقيق (name)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_archived` ON `parties` (`archived`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_archived` ON `products` (`archived`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_name` ON `parties` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_date` ON `journal` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_refType_refId` ON `journal` (`refType`, `refId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_entryId` ON `journal_lines` (`entryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_account` ON `journal_lines` (`account`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_partyId` ON `journal_lines` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_productId` ON `stock_moves` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_date` ON `stock_moves` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_partyId` ON `invoices` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_date` ON `invoices` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_status` ON `invoices` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_number` ON `invoices` (`number`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_partyId` ON `payments` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_date` ON `payments` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_checkId` ON `payments` (`checkId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_partyId` ON `checks` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_dueDate` ON `checks` (`dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_status` ON `checks` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_date` ON `expenses` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_category` ON `expenses` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_partyId` ON `installment_plans` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_installment_plans_direction` ON `installment_plans` (`direction`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_planId` ON `installments` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_dueDate` ON `installments` (`dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_installments_status` ON `installments` (`status`)")
    }

    /** بذر بيانات تمثيلية ترتبط بالأبناء (أطراف/منتجات/قيود/خطط/فواتير/دفعات) */
    private fun seedV4Data(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO `parties` (`id`,`name`,`phone`,`type`,`note`,`createdAt`,`archived`) VALUES (1, 'عميل', '050', 0, '', 100, 0)")
        db.execSQL("INSERT INTO `products` (`id`,`name`,`sku`,`barcode`,`unit`,`costPrice`,`salePrice`,`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived`) VALUES (1, 'صنف', '', '2000000000017', 'قطعة', 5.0, 8.0, 10.0, 2.0, '', 100, 0)")
        db.execSQL("INSERT INTO `journal` (`id`,`date`,`memo`,`refType`,`refId`) VALUES (1, 100, 'قيد', NULL, NULL)")
        db.execSQL("INSERT INTO `journal_lines` (`id`,`entryId`,`account`,`debit`,`credit`,`partyId`,`currency`,`fxRate`) VALUES (1, 1, '1010', 50.0, 0.0, 1, 'SAR', 1.0)")
        db.execSQL("INSERT INTO `invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) VALUES (1, 'INV-0001', 1, 0, 100, 200, 80.0, 0.0, 0.0, 0.0, 80.0, 30.0, 0.0, 1, 'SAR', 1.0, '')")
        db.execSQL("INSERT INTO `invoice_items` (`id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) VALUES (1, 1, 1, 'صنف', 10.0, 8.0, 0.0)")
        db.execSQL("INSERT INTO `checks` (`id`,`number`,`partyId`,`bank`,`amount`,`issueDate`,`dueDate`,`direction`,`status`,`note`) VALUES (1, 'CH-1', 1, '', 40.0, 100, 300, 0, 0, '')")
        db.execSQL("INSERT INTO `payments` (`id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`) VALUES (1, 1, 1, 1, 30.0, 100, 0, 'CASH', '')")
        db.execSQL("INSERT INTO `installment_plans` (`id`,`title`,`partyId`,`direction`,`total`,`downPayment`,`financed`,`months`,`startDate`,`currency`,`note`,`createdAt`,`archived`) VALUES (1, 'خطة', 1, 0, 100.0, 0.0, 100.0, 4, 100, 'SAR', '', 100, 0)")
        db.execSQL("INSERT INTO `installments` (`id`,`planId`,`seq`,`amount`,`dueDate`,`paidAmount`,`paidDate`,`status`) VALUES (1, 1, 1, 25.0, 100, 0.0, NULL, 0)")
        db.execSQL("INSERT INTO `stock_moves` (`id`,`productId`,`qty`,`reason`,`date`,`refType`,`refId`,`note`) VALUES (1, 1, -10.0, 'SALE', 100, 'invoice', 1, 'INV-0001')")
    }

    @Test
    fun migration3to4_createsAllPerformanceIndexes() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null) // قاعدة في الذاكرة
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = minimalV3Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            // الترحيل الرابع في قائمة الترقيات هو 3→4
            val migration = com.superbiz.app.AppGraph.MIGRATIONS[2]
            assertEquals(3, migration.startVersion)
            assertEquals(4, migration.endVersion)
            migration.migrate(db)

            val stmt = db.query("SELECT name FROM sqlite_master WHERE type = 'index'")
            val names = HashSet<String>()
            stmt.use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
            for (expected in listOf(
                "index_journal_date",
                "index_journal_refType_refId",
                // تكييف: في هذا الخط 3→4 ينشئ فهارس archived؛ فهرسا parties.name وpayments.checkId يأتيان في 4→5
                "index_parties_archived",
                "index_products_archived",
                "index_invoices_number"
            )) {
                assertTrue("الفهرس $expected لم يُنشأ", names.contains(expected))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migration4to5_rebuildsWithForeignKeysAndPlanId() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) = fullV4Schema(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[3]
            assertEquals(4, migration.startVersion)
            assertEquals(5, migration.endVersion)
            migration.migrate(db)

            // 1) حفظ البيانات كاملة عبر الإعادة البنائية
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM invoices"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM invoice_items"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM payments"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM checks"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM installment_plans"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM installments"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM stock_moves"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM journal_lines"))
            assertEquals("INV-0001", scalarStr(db, "SELECT number FROM invoices WHERE id = 1"))
            assertEquals(80.0, scalarDouble(db, "SELECT total FROM invoices WHERE id = 1"), 1e-9)
            assertEquals(30.0, scalarDouble(db, "SELECT paid FROM invoices WHERE id = 1"), 1e-9)

            // 2) عمود planId الجديد على payments ( — M-4.9) بقيم NULL للموجود
            // + فهارس إعادة البناء الموحّدة (checkId من خط التدقيق، parties.name من M-3.6)
            val idxStmt = db.query("SELECT name FROM sqlite_master WHERE type = 'index'")
            val idxNames = HashSet<String>()
            idxStmt.use { c -> while (c.moveToNext()) idxNames += c.getString(0) }
            assertTrue("فهرس payments.checkId مفقود", idxNames.contains("index_payments_checkId"))
            assertTrue("فهرس payments.planId مفقود", idxNames.contains("index_payments_planId"))
            assertTrue("فهرس parties.name مفقود", idxNames.contains("index_parties_name"))
            val planIdCol = columnType(db, "payments", "planId")
            assertTrue("عمود planId مفقود من payments", planIdCol != null)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM payments WHERE planId IS NULL"))

            // 3) المفاتيح الأجنبية موجودة بالأعداد الصحيحة على كل جدول معاد بناؤه
            assertEquals(1L, fkCount(db, "invoices"))          // partyId → parties RESTRICT
            assertEquals(2L, fkCount(db, "invoice_items"))     // invoiceId CASCADE + productId SET NULL
            assertEquals(4L, fkCount(db, "payments"))          // party/invoice/check/plan SET NULL
            assertEquals(1L, fkCount(db, "checks"))            // partyId RESTRICT
            assertEquals(1L, fkCount(db, "stock_moves"))       // productId RESTRICT
            assertEquals(1L, fkCount(db, "installment_plans")) // partyId RESTRICT
            assertEquals(1L, fkCount(db, "installments"))      // planId CASCADE
            assertEquals(2L, fkCount(db, "journal_lines"))     // entryId CASCADE + partyId SET NULL

            // 4) لا صفوف يتيمة بعد الترحيل
            assertEquals(0L, countRows(db, "PRAGMA foreign_key_check"))

            // 5) القيود تُفرض فعلياً عند الكتابة بعد تفعيل المفاتيح (كما يفعل Room في onOpen)
            db.execSQL("PRAGMA foreign_keys = ON")
            try {
                db.execSQL("INSERT INTO `invoices` (`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) VALUES ('BAD', 424242, 0, 1, 1, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0, 'SAR', 1.0, '')")
                fail("مفتاح RESTRICT يجب أن يرفض فاتورة بلا طرف")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
        } finally {
            db.close()
        }
    }

    // [P6-M13 إصلاح]: ترقية 5→6 — فهرس مرجع حركات المخزون (CREATE INDEX فقط)
    @Test
    fun migration5to6_createsStockMovesRefIndex() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // بنية v5 الحقيقية = بنية v4 كاملة + ترحيل 4→5 الفعلي
                    // (مفاتيح أجنبية + payments.planId + فهارس parties.name/checkId)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[4]
            assertEquals(5, migration.startVersion)
            assertEquals(6, migration.endVersion)
            migration.migrate(db)

            // 1) الفهرس الجديد موجود باسم Room القياسي وبعموديه بالترتيب (refType ثم refId)
            val idxStmt = db.query("SELECT name FROM sqlite_master WHERE type = 'index'")
            val idxNames = HashSet<String>()
            idxStmt.use { c -> while (c.moveToNext()) idxNames += c.getString(0) }
            assertTrue("فهرس stock_moves المرجعي مفقود", idxNames.contains("index_stock_moves_refType_refId"))
            val cols = mutableListOf<String>()
            db.query("PRAGMA index_info(`index_stock_moves_refType_refId`)").use { c ->
                while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertEquals(listOf("refType", "refId"), cols)

            // 2) الفهرس غير فريد — مرجعان بنفس (refType, refId) يُقبلان (لا خطر على بيانات قائمة)
            db.execSQL(
                "INSERT INTO `stock_moves` (`productId`,`qty`,`reason`,`date`,`refType`,`refId`,`note`) " +
                    "VALUES (1, -1.0, 'SALE', 200, 'invoice', 1, 'مرجع مكرر مشروع')"
            )

            // 3) البيانات حية بعد الترحيل (CREATE INDEX لا يمس الصفوف) و deleteByRef يجدها
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM stock_moves"))
            assertEquals("invoice", scalarStr(db, "SELECT refType FROM stock_moves WHERE id = 1"))
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM stock_moves WHERE refType = 'invoice' AND refId = 1"))
        } finally {
            db.close()
        }
    }

    // [P11-a]: ترقية 6→7 — أعمدة المفضّلة والإحداثيات على parties (ALTER TABLE فقط)
    @Test
    fun migration6to7_addsFavoriteAndGeoColumns() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // بنية v6 الحقيقية = بنية v4 كاملة + ترحيل 4→5 (مفاتيح أجنبية) + 5→6 (فهرس)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[5]
            assertEquals(6, migration.startVersion)
            assertEquals(7, migration.endVersion)
            migration.migrate(db)

            // 1) الأعمدة الثلاثة طالعة ببنية Room المتوقعة:
            //    favorite INTEGER NOT NULL افتراضها 0، وlat/lng REAL قابلان للإلغاء
            val cols = mutableListOf<Triple<String, String, String>>() // (اسم، نوع، notnull)
            var favoriteDefault: String? = null
            db.query("PRAGMA table_info(`parties`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    cols += Triple(name, c.getString(c.getColumnIndexOrThrow("type")),
                        c.getString(c.getColumnIndexOrThrow("notnull")))
                    if (name == "favorite") favoriteDefault = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                }
            }
            assertEquals(
                listOf("favorite", "lat", "lng"),
                cols.filter { it.first in listOf("favorite", "lat", "lng") }.map { it.first }
            )
            assertEquals("INTEGER", cols.first { it.first == "favorite" }.second)
            assertEquals("1", cols.first { it.first == "favorite" }.third)
            assertEquals("0", favoriteDefault)
            assertEquals("REAL", cols.first { it.first == "lat" }.second)
            assertEquals("REAL", cols.first { it.first == "lng" }.second)
            assertEquals("0", cols.first { it.first == "lat" }.third)  // قابل للإلغاء (NULL)
            assertEquals("0", cols.first { it.first == "lng" }.third)

            // 2) البيانات القائمة حيّة والقيم الافتراضية مطابقة لكيان Party
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM parties WHERE favorite = 1"))
            assertTrue(db.query("SELECT lat IS NULL AND lng IS NULL FROM parties WHERE id = 1")
                .use { c -> c.moveToFirst(); c.getInt(0) == 1 })

            // 3) سلوك PartyDao الجديد فعلياً على المخطط المرقّى (تحديث موضعي)
            db.execSQL("UPDATE `parties` SET `favorite` = 1 WHERE `id` = 1")
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties WHERE favorite = 1 AND archived = 0"))
            db.execSQL("UPDATE `parties` SET `lat` = 24.7136, `lng` = 46.6753 WHERE `id` = 1")
            assertEquals(24.7136, scalarDouble(db, "SELECT lat FROM parties WHERE id = 1"), 1e-9)
            assertEquals(46.6753, scalarDouble(db, "SELECT lng FROM parties WHERE id = 1"), 1e-9)
            db.execSQL("UPDATE `parties` SET `lat` = NULL, `lng` = NULL WHERE `id` = 1")
            assertTrue(db.query("SELECT lat IS NULL AND lng IS NULL FROM parties WHERE id = 1")
                .use { c -> c.moveToFirst(); c.getInt(0) == 1 })
        } finally {
            db.close()
        }
    }

    // [P12-b]: ترقية 7→8 — جدول الزيارات (CREATE TABLE + فهارس فقط، لا لمس لبيانات قائمة)
    @Test
    fun migration7to8_createsVisitsTableWithIndexesAndKeepsData() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // بنية v7 الحقيقية = بنية v4 كاملة + 4→5 (مفاتيح أجنبية)
                    // + 5→6 (فهرس refType) + 6→7 (أعمدة المفضلة/الإحداثيات)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[5].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)
            // حالة v7 واقعية: الطرف مفضّل بموقع محفوظ — يجب أن يبقى كما هو بعد 7→8
            db.execSQL("UPDATE `parties` SET `favorite` = 1, `lat` = 24.7136, `lng` = 46.6753 WHERE `id` = 1")

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[6]
            assertEquals(7, migration.startVersion)
            assertEquals(8, migration.endVersion)
            migration.migrate(db)

            // 1) جدول visits بأعمدة Room المتوقعة حرفياً (الترتيب والأنواع وقيد NOT NULL):
            //    id PK تلقائي، partyId/visitedAt INTEGER NOT NULL، lat/lng REAL قابلان للإلغاء، note TEXT NOT NULL
            val cols = mutableListOf<Triple<String, String, String>>() // (اسم، نوع، notnull)
            db.query("PRAGMA table_info(`visits`)").use { c ->
                while (c.moveToNext()) {
                    cols += Triple(
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getString(c.getColumnIndexOrThrow("type")),
                        c.getString(c.getColumnIndexOrThrow("notnull"))
                    )
                }
            }
            assertEquals(
                listOf("id", "partyId", "visitedAt", "lat", "lng", "note"),
                cols.map { it.first }
            )
            assertEquals("INTEGER", cols.first { it.first == "id" }.second)
            assertEquals("1", cols.first { it.first == "id" }.third)
            assertEquals("INTEGER", cols.first { it.first == "partyId" }.second)
            assertEquals("1", cols.first { it.first == "partyId" }.third)
            assertEquals("INTEGER", cols.first { it.first == "visitedAt" }.second)
            assertEquals("1", cols.first { it.first == "visitedAt" }.third)
            assertEquals("REAL", cols.first { it.first == "lat" }.second)
            assertEquals("0", cols.first { it.first == "lat" }.third)  // قابل للإلغاء (NULL)
            assertEquals("REAL", cols.first { it.first == "lng" }.second)
            assertEquals("0", cols.first { it.first == "lng" }.third)
            assertEquals("TEXT", cols.first { it.first == "note" }.second)
            assertEquals("1", cols.first { it.first == "note" }.third)
            // المفتاح تلقائي التزايد كما يتوقعه كيان Room
            val createSql = db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'visits'")
                .use { c -> c.moveToFirst(); c.getString(0) }
            assertTrue("visits يجب أن تكون AUTOINCREMENT", createSql.contains("AUTOINCREMENT"))

            // 2) الفهرسان بأسامي Room القياسية وعمود واحد لكل منهما بالترتيب
            val idxStmt = db.query("SELECT name FROM sqlite_master WHERE type = 'index'")
            val idxNames = HashSet<String>()
            idxStmt.use { c -> while (c.moveToNext()) idxNames += c.getString(0) }
            assertTrue("فهرس visits_partyId مفقود", idxNames.contains("index_visits_partyId"))
            assertTrue("فهرس visits_visitedAt مفقود", idxNames.contains("index_visits_visitedAt"))
            db.query("PRAGMA index_info(`index_visits_partyId`)").use { c ->
                c.moveToFirst()
                assertEquals("partyId", c.getString(c.getColumnIndexOrThrow("name")))
            }
            db.query("PRAGMA index_info(`index_visits_visitedAt`)").use { c ->
                c.moveToFirst()
                assertEquals("visitedAt", c.getString(c.getColumnIndexOrThrow("name")))
            }

            // 3) roundtrip فعلي: إدراج زيارتين (بموقع/بلا موقع) وقراءتهما بترتيب visitedAt DESC
            db.execSQL(
                "INSERT INTO `visits` (`partyId`,`visitedAt`,`lat`,`lng`,`note`) " +
                    "VALUES (1, 500, 24.7136, 46.6753, 'زيارة أولى')"
            )
            db.execSQL(
                "INSERT INTO `visits` (`partyId`,`visitedAt`,`note`) VALUES (1, 900, 'بلا موقع')"
            )
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM visits"))
            // المفتاح تلقائي: 1 ثم 2
            assertEquals(1L, scalar(db, "SELECT id FROM visits WHERE visitedAt = 500"))
            assertEquals(2L, scalar(db, "SELECT id FROM visits WHERE visitedAt = 900"))
            // النص العربي والإحداثيات تدور كاملة
            assertEquals("زيارة أولى", scalarStr(db, "SELECT note FROM visits WHERE id = 1"))
            assertEquals(24.7136, scalarDouble(db, "SELECT lat FROM visits WHERE id = 1"), 1e-9)
            assertEquals(46.6753, scalarDouble(db, "SELECT lng FROM visits WHERE id = 1"), 1e-9)
            assertTrue(db.query("SELECT lat IS NULL AND lng IS NULL FROM visits WHERE id = 2")
                .use { c -> c.moveToFirst(); c.getInt(0) == 1 })
            // الترتيب التنازلي الذي يبني عليه DAO والتقرير
            assertEquals(900L, db.query("SELECT visitedAt FROM visits ORDER BY visitedAt DESC")
                .use { c -> c.moveToFirst(); c.getLong(0) })
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM visits WHERE partyId = 1"))

            // 4) قيد NOT NULL مُفروض فعلاً على note (كيان Room يعلنه NOT NULL)
            try {
                db.execSQL("INSERT INTO `visits` (`partyId`,`visitedAt`,`note`) VALUES (1, 1000, NULL)")
                fail("note NOT NULL لم تُفرض على جدول visits")
            } catch (e: SQLiteConstraintException) {
                // متوقع — القيد يعمل
            }

            // 5) حفظ بيانات المستخدم: الطرف المبذور كما هو (مفضّل بموقع) بعد الترحيل
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties WHERE favorite = 1 AND archived = 0"))
            assertEquals(24.7136, scalarDouble(db, "SELECT lat FROM parties WHERE id = 1"), 1e-9)
            assertEquals(46.6753, scalarDouble(db, "SELECT lng FROM parties WHERE id = 1"), 1e-9)
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM invoices"))
        } finally {
            db.close()
        }
    }

    // [P17-a]: ترقية 8→9 — كشف الحساب PDF: 8 أعمدة parties + 8 جداول جديدة.
    // أعلى مخاطرة في الموجة: أي انحراف بين SQL الترحيل وتعليقات Entities.kt يرفض Room
    // فتح القاعدة (اصطدام مخطط عند أول تشغيل بعد التحديث)، فالاختبار يقارن البنية
    // عموداً عموداً (اسم/نوع/NOT NULL/غياب DEFAULT) ويختبر القيود سلوكياً
    // (فريدة statementNumber/verificationId/dedupKey + FK RESTRICT/CASCADE).
    @Test
    fun migration8to9_createsStatementTablesAndPartyColumns() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // بنية v8 الحقيقية = بنية v4 كاملة + 4→5 (مفاتيح أجنبية)
                    // + 5→6 (فهرس) + 6→7 (أعمدة parties) + 7→8 (جدول الزيارات)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[5].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[6].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)
            // حالة v8 واقعية: طرف مفضّل بموقع محفوظ + زيارة مسجلة — يجب أن تبقى كما هي بعد 8→9
            db.execSQL("UPDATE `parties` SET `favorite` = 1, `lat` = 24.7136, `lng` = 46.6753 WHERE `id` = 1")
            db.execSQL(
                "INSERT INTO `visits` (`partyId`,`visitedAt`,`lat`,`lng`,`note`) " +
                    "VALUES (1, 500, 24.7136, 46.6753, 'زيارة قبل الترقية')"
            )

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[7]
            assertEquals(8, migration.startVersion)
            assertEquals(9, migration.endVersion)
            migration.migrate(db)

            // 1) أعمدة parties الثمانية: TEXT قابلة للإلغاء **بلا قيمة افتراضية SQL**
            //    (كيان Party: String? = null — Room لا يكتب DEFAULT لعمود اختياري)
            val partyCols = tableColumns(db, "parties")
            val newPartyCols = listOf(
                "email", "address", "taxNumber", "crNumber", "city", "country", "website", "accountNumber"
            )
            for (name in newPartyCols) {
                val col = partyCols.first { it.name == name }
                assertEquals("نوع عمود parties.$name", "TEXT", col.type)
                assertEquals("parties.$name قابل للإلغاء (NULL)", "0", col.notNull)
                assertEquals("parties.$name بلا DEFAULT SQL", null, col.dflt)
            }
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties WHERE email IS NULL AND crNumber IS NULL"))

            // 2) statements: الأعمدة بترتيب الكيان وأنواعها، NOT NULL لكل غير اختياري
            val st = tableColumns(db, "statements")
            assertEquals(
                listOf(
                    "id", "statementNumber", "verificationId", "partyId", "fromTs", "toTs",
                    "templateId", "currency", "contentHash", "filePath", "note", "createdAt", "lang"
                ),
                st.map { it.name }
            )
            for (name in listOf(
                "statementNumber", "verificationId", "partyId", "fromTs", "toTs",
                "templateId", "currency", "contentHash", "filePath", "createdAt", "lang"
            )) {
                assertEquals("statements.$name NOT NULL", "1", st.first { it.name == name }.notNull)
            }
            assertEquals("statements.note قابل للإلغاء", "0", st.first { it.name == "note" }.notNull)

            // 3) statement_deliveries
            val dv = tableColumns(db, "statement_deliveries")
            assertEquals(
                listOf(
                    "id", "statementId", "channel", "status", "attempts", "lastError",
                    "sentAt", "scheduledFor", "dedupKey", "lastAttemptAt"
                ),
                dv.map { it.name }
            )
            for (name in listOf("statementId", "channel", "status", "attempts", "dedupKey")) {
                assertEquals("deliveries.$name NOT NULL", "1", dv.first { it.name == name }.notNull)
            }
            for (name in listOf("lastError", "sentAt", "scheduledFor", "lastAttemptAt")) {
                assertEquals("deliveries.$name قابل للإلغاء", "0", dv.first { it.name == name }.notNull)
            }

            // 4) بقية الجداول الستة: أسماء الأعمدة مطابقة للكيانات حرفياً
            assertEquals(
                listOf("id", "name", "baseTemplateId", "configJson", "isDefault", "favorite", "createdAt", "updatedAt"),
                tableColumns(db, "statement_templates").map { it.name }
            )
            assertEquals(
                listOf("id", "name", "jobTitle", "imagePath", "isDefault", "active", "createdAt"),
                tableColumns(db, "signatures").map { it.name }
            )
            assertEquals(
                listOf("id", "name", "imagePath", "isDefault", "active", "createdAt"),
                tableColumns(db, "stamps").map { it.name }
            )
            assertEquals(
                listOf("id", "title", "body", "isDefault"),
                tableColumns(db, "note_templates").map { it.name }
            )
            assertEquals(
                listOf(
                    "id", "name", "enabled", "partyMode", "partyIdsJson", "frequency", "weekday",
                    "dayOfMonth", "hour", "minute", "periodPreset", "templateId", "signatureId",
                    "stampId", "channel", "eventFlagsJson", "threshold", "lastRunAt", "nextRunAt"
                ),
                tableColumns(db, "statement_rules").map { it.name }
            )
            assertEquals(
                listOf("id", "actor", "action", "details", "ts"),
                tableColumns(db, "audit_log").map { it.name }
            )

            // 5) المفاتيح الأجنبية بأعدادها: statements→parties وdeliveries→statements فقط
            assertEquals(1L, fkCount(db, "statements"))
            assertEquals(1L, fkCount(db, "statement_deliveries"))
            assertEquals(0L, fkCount(db, "statement_templates"))
            assertEquals(0L, fkCount(db, "signatures"))
            assertEquals(0L, fkCount(db, "statement_rules"))

            // 6) سلوك القيود بعد تفعيل المفاتيح (كما يفعل Room في onOpen)
            db.execSQL("PRAGMA foreign_keys = ON")
            insertStatement(db, "STATEMENT-2026-000001", "SB-ST-20260924-000001", 1)
            db.execSQL(
                "INSERT INTO `statement_deliveries` (`statementId`,`channel`,`status`,`attempts`,`dedupKey`) " +
                    "VALUES (1, 'WHATSAPP', 'PENDING', 0, 'delivery:1:WHATSAPP')"
            )
            // قناة ثانية لنفس الكشف تُقبل — التفرد على dedupKey لا على (كشف، قناة)
            db.execSQL(
                "INSERT INTO `statement_deliveries` (`statementId`,`channel`,`status`,`attempts`,`dedupKey`) " +
                    "VALUES (1, 'EMAIL', 'PENDING', 0, 'delivery:1:EMAIL')"
            )
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM statement_deliveries"))

            // رقم الكشف فريد (سند مالي لا يتولد مرتين)
            try {
                insertStatement(db, "STATEMENT-2026-000001", "SB-ST-20260924-000002", 1)
                fail("UNIQUE على statements.statementNumber لم تُفرض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
            // رقم التحقق فريد (مدخل شاشة التحقق في 17-c)
            try {
                insertStatement(db, "STATEMENT-2026-000002", "SB-ST-20260924-000001", 1)
                fail("UNIQUE على statements.verificationId لم تُفرض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
            // dedupKey فريد (منع تضخم سجل التسليم عند إعادة الجدولة)
            try {
                db.execSQL(
                    "INSERT INTO `statement_deliveries` (`statementId`,`channel`,`status`,`attempts`,`dedupKey`) " +
                        "VALUES (1, 'SMS', 'PENDING', 0, 'delivery:1:WHATSAPP')"
                )
                fail("UNIQUE على statement_deliveries.dedupKey لم تُفرض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
            // كشف بلا طرف مستحيل (RESTRICT كما في عرف invoices)
            try {
                insertStatement(db, "STATEMENT-2026-000003", "SB-ST-20260924-000003", 424242)
                fail("FK RESTRICT على statements.partyId لم تُفرض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
            // تسليم بلا كشف مستحيل (FK على statementId)
            try {
                db.execSQL(
                    "INSERT INTO `statement_deliveries` (`statementId`,`channel`,`status`,`attempts`,`dedupKey`) " +
                        "VALUES (424242, 'EMAIL', 'PENDING', 0, 'delivery:424242:EMAIL')"
                )
                fail("FK على statement_deliveries.statementId لم تُفرض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }
            // CASCADE: حذف الكشف يمحو سطور تسليمه (سجل تابع لا مستند مستقل)
            db.execSQL("DELETE FROM `statements` WHERE `id` = 1")
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM statement_deliveries"))
            // RESTRICT على حذف الطرف: كشف طرف بلا أي مستندات أخرى يمنع حذف طرفه وحده
            db.execSQL("INSERT INTO `parties` (`id`,`name`,`phone`,`type`,`note`,`createdAt`,`archived`) VALUES (2, 'طرف كشف', '', 0, '', 900, 0)")
            insertStatement(db, "STATEMENT-2026-000002", "SB-ST-20260924-000002", 2)
            try {
                db.execSQL("DELETE FROM `parties` WHERE `id` = 2")
                fail("RESTRICT: حذف طرف له كشف يجب أن يُرفض")
            } catch (e: SQLiteConstraintException) {
                // متوقع
            }

            // 7) حفظ بيانات المستخدم عبر الترحيل + الأعمدة الجديدة تقبل القيم وتعيد NULL
            db.execSQL("UPDATE `parties` SET `email` = 'x@y.z', `city` = 'الرياض' WHERE `id` = 1")
            assertEquals("x@y.z", scalarStr(db, "SELECT email FROM parties WHERE id = 1"))
            assertEquals("الرياض", scalarStr(db, "SELECT city FROM parties WHERE id = 1"))
            assertTrue(db.query("SELECT taxNumber IS NULL AND website IS NULL FROM parties WHERE id = 1")
                .use { c -> c.moveToFirst(); c.getInt(0) == 1 })
            assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM parties"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM parties WHERE favorite = 1 AND archived = 0"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM invoices"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM visits"))
            assertEquals("زيارة قبل الترقية", scalarStr(db, "SELECT note FROM visits WHERE visitedAt = 500"))
        } finally {
            db.close()
        }
    }

    /** إدراج كشف أدنى بكل الأعمدة غير الاختيارية — أداة اختبارات القيود أعلاه */
    private fun insertStatement(db: SupportSQLiteDatabase, number: String, verification: String, partyId: Long) {
        db.execSQL(
            "INSERT INTO `statements` (`statementNumber`,`verificationId`,`partyId`,`fromTs`,`toTs`," +
                "`templateId`,`currency`,`contentHash`,`filePath`,`note`,`createdAt`,`lang`) " +
                "VALUES ('$number', '$verification', $partyId, 100, 200, 'classic', 'SAR', 'hash', '/p.pdf', NULL, 300, 'AR')"
        )
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun scalarDouble(db: SupportSQLiteDatabase, sql: String): Double =
        db.query(sql).use { c -> c.moveToFirst(); c.getDouble(0) }

    private fun scalarStr(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    /** عدد صفوف نتيجة PRAGMA (قائمة المفاتيح = عدد المفاتيح، foreign_key_check = عدد اليتامى) */
    private fun countRows(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { c ->
            var n = 0L
            while (c.moveToNext()) n++
            return n
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // [P33-P8] ترقية 9→10 — الترحيل المالي الكامل: كل أعمدة المبالغ من REAL ريال
    // إلى INTEGER قروش بتحويل بقرش واحد ROUND(amount*100) — معيار القبول الجذري:
    // حفظ كل قرش (مقارنة checksum للمجاميع قبل/بعد بتحويل معلوم) على بذر حدّي
    // (أنصاف هللات، مبالغ ≥ 10^9، قيم سالبة) + foreign_key_check نظيف
    // + بنية Room للمخطط v10 مطابقة حرفياً (INTEGER لا REAL).
    // ═════════════════════════════════════════════════════════════════════
    @Test
    fun migration9to10_moneyColumnsBecomeIntegerPiastersExactly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // بنية v9 الحقيقية = بنية v4 كاملة + سلسلة الترحيلات 4→5→6→7→8→9
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[5].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[6].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[7].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)
            // ── بذر حدّي ──
            // أنصاف الهللات المخزنة (2.675 = نصف هللة فجوة التمثيل الثنائي) —
            // قرار التحويل: HALF_UP بعيداً عن الصفر (نفس سياسة P6-M3 المالية المعتمدة)
            db.execSQL(
                "INSERT INTO `products` (`name`,`sku`,`barcode`,`unit`,`costPrice`,`salePrice`,`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived`) " +
                    "VALUES ('حد أنصاف الهللات', '', '', 'قطعة', 2.675, 3.335, 1.0, 0.0, '', 10, 0)"
            )
            // مبالغ ضخمة (≥ 10^9 هللة) + سالبة (مرتجع/عكس قيد)
            db.execSQL(
                "INSERT INTO `invoices` (`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                    "VALUES ('EDGE-1', 1, 0, 100, 200, 12345678.91, 0.005, 15.0, 1851851.84, 14197530.75, -2.67, 9999999.99, 0, 'SAR', 1.0, 'بذر حدّي')"
            )
            // القروش قبل الترحيل (مصدر الحقيقة للمقارنة) — checksum لكل جدول متأثر
            data class MoneySum(val table: String, val col: String, val where: String = "")
            val sumsBefore = listOf(
                MoneySum("products", "costPrice", "WHERE id > 1"),
                MoneySum("invoices", "subtotal"), MoneySum("invoices", "total"),
                MoneySum("invoices", "paid"), MoneySum("invoices", "costTotal", "WHERE id > 1")
            ).associate { "${it.table}.${it.col}" to scalarDouble(db, "SELECT COALESCE(SUM(${it.col}),0) FROM ${it.table} ${it.where}") }
            val rowsBefore = mapOf(
                "products" to scalar(db, "SELECT COUNT(*) FROM products"),
                "invoices" to scalar(db, "SELECT COUNT(*) FROM invoices"),
                "invoice_items" to scalar(db, "SELECT COUNT(*) FROM invoice_items"),
                "payments" to scalar(db, "SELECT COUNT(*) FROM payments"),
                "checks" to scalar(db, "SELECT COUNT(*) FROM checks"),
                "journal_lines" to scalar(db, "SELECT COUNT(*) FROM journal_lines"),
                "installments" to scalar(db, "SELECT COUNT(*) FROM installments")
            )

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[8]
            assertEquals(9, migration.startVersion)
            assertEquals(10, migration.endVersion)
            migration.migrate(db)

            // 1) الألفة INTEGER لكل أعمدة المبالغ في الجداول العشرة (كانت REAL)
            val moneyColumns = mapOf(
                "journal_lines" to listOf("debit", "credit"),
                "products" to listOf("costPrice", "salePrice"),
                "invoices" to listOf("subtotal", "discount", "taxAmount", "total", "paid", "costTotal"),
                "invoice_items" to listOf("unitPrice", "discount"),
                "payments" to listOf("amount"),
                "checks" to listOf("amount"),
                "expenses" to listOf("amount"),
                "installment_plans" to listOf("total", "downPayment", "financed"),
                "installments" to listOf("amount", "paidAmount")
            )
            for ((tbl, cols) in moneyColumns) {
                for (col in cols) {
                    assertEquals("$tbl.$col أصبح INTEGER", "INTEGER", columnType(db, tbl, col))
                }
            }
            assertEquals("statement_rules.threshold أصبح INTEGER", "INTEGER", columnType(db, "statement_rules", "threshold"))

            // 2) لا صف فقد — العدادات مطابقة حرفياً بعد إعادة البناء
            for ((tbl, n) in rowsBefore) {
                assertEquals("$tbl: كل الصفوف حية بعد إعادة البناء", n, scalar(db, "SELECT COUNT(*) FROM `$tbl`"))
            }

            // 3) حفظ كل قرش — المجاميع بعد الترحيل = المجموع قبلها × 100 (تحويل بقرش معلوم)
            // منتج أنصاف الهللات: 2.675→268 و3.335→334 (HALF_UP بعيداً عن الصفر) —
            // 268+334 = 602 = round((2.675+3.335)*100) تقريباً لكن المقارنة تتم على الفرق المسموح
            // (الأنصاف الوحيدة الموجودة في هذا البذر) — نفحص القيم الحدية حرفياً بدل المجموع عندها
            assertEquals("نصف هللة 2.675 تتحسم 268 قرشاً", 268L, scalar(db, "SELECT costPrice FROM products WHERE name = 'حد أنصاف الهللات'"))
            assertEquals("نصف هللة 3.335 تتحسم 334 قرشاً", 334L, scalar(db, "SELECT salePrice FROM products WHERE name = 'حد أنصاف الهللات'"))
            assertEquals("المبلغ الضخم 12345678.91 يحفظ قرشه 1234567891", 1234567891L, scalar(db, "SELECT subtotal FROM invoices WHERE number = 'EDGE-1'"))
            assertEquals("السالب −2.67 يبقى سالباً −267 (بعيداً عن الصفر)", -267L, scalar(db, "SELECT paid FROM invoices WHERE number = 'EDGE-1'"))
            assertEquals("الخصم 0.005 يتحسم قرشاً واحداً", 1L, scalar(db, "SELECT discount FROM invoices WHERE number = 'EDGE-1'"))
            for ((key, before) in sumsBefore) {
                val (tbl, col, where) = key.split(".").let { Triple(it[0], it[1], if (it.size > 2) it[2] else "") }
                if (tbl == "products") continue // الأنصاف الحدية فُحصت حرفياً أعلاه
                val after = scalar(db, "SELECT COALESCE(SUM(`$col`),0) FROM `$tbl` $where")
                assertEquals("$tbl.$col: المجموع ×100 بالضبط", Math.round(before * 100), after)
            }

            // 4) الكميات والنسب بقيت REAL (لا تتحول قروشاً)
            assertEquals("stockQty كمية تبقى REAL", "REAL", columnType(db, "products", "stockQty"))
            assertEquals("taxRate نسبة تبقى REAL", "REAL", columnType(db, "invoices", "taxRate"))
            assertEquals("fxRate نسبة تبقى REAL", "REAL", columnType(db, "invoices", "fxRate"))

            // 5) المفاتيح الأجنبية أعيدت بناؤها بالتعريفات نفسها
            assertEquals("journal_lines: مفتاحان أجنبيان", 2L, fkCount(db, "journal_lines"))
            assertEquals("invoices: مفتاح أجنبي واحد", 1L, fkCount(db, "invoices"))
            assertEquals("invoice_items: مفتاحان أجنبيان", 2L, fkCount(db, "invoice_items"))
            assertEquals("payments: أربعة مفاتيح أجنبية", 4L, fkCount(db, "payments"))
            assertEquals("checks: مفتاح أجنبي واحد", 1L, fkCount(db, "checks"))
            assertEquals("installment_plans: مفتاح أجنبي واحد", 1L, fkCount(db, "installment_plans"))
            assertEquals("installments: مفتاح أجنبي واحد", 1L, fkCount(db, "installments"))
            assertEquals("foreign_key_check: لا صفوف يتيمة بعد إعادة البناء", 0L, countRows(db, "PRAGMA foreign_key_check"))

            // 6) الفهارس أعيدت بأسمائها القياسية
            val idx = HashSet<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
                while (c.moveToNext()) idx += c.getString(0)
            }
            for (name in listOf(
                "index_journal_lines_entryId", "index_journal_lines_account", "index_journal_lines_partyId",
                "index_products_barcode", "index_products_archived",
                "index_invoices_partyId", "index_invoices_date", "index_invoices_status", "index_invoices_number",
                "index_invoice_items_invoiceId", "index_invoice_items_productId",
                "index_payments_partyId", "index_payments_invoiceId", "index_payments_date",
                "index_payments_checkId", "index_payments_planId",
                "index_checks_partyId", "index_checks_dueDate", "index_checks_status",
                "index_expenses_date", "index_expenses_category",
                "index_installment_plans_partyId", "index_installment_plans_direction",
                "index_installments_planId", "index_installments_dueDate", "index_installments_status"
            )) {
                assertTrue("فهرس $name مفقود بعد إعادة البناء", idx.contains(name))
            }
        } finally {
            db.close()
        }
    }

    private fun fkCount(db: SupportSQLiteDatabase, table: String): Long =
        countRows(db, "PRAGMA foreign_key_list(`$table`)")

    // ═════════════════════════════════════════════════════════════════════
    // [P41-L1] ترقية 10→11 — الحقول الضريبية على مستوى السطر: عمودان على
    // invoice_items ببذرتين محايدتين (taxKind INTEGER DEFAULT 0 / taxRate REAL
    // DEFAULT -1.0) — أهدأ ترحيل بعد ALTER ADD لا يعيد بناء الجدول ولا
    // يمس صفاً ولا مفتاحاً أجنبياً ولا فهرساً. المعيار: الصفوف القائمة تأخذ
    // البذرتين (دلالة تاريخية حرفية — LineTaxP41)، والصفوف الجديدة بلا الحقلين
    // تأكلهما من DEFAULT، والبنية Room مطابقة (INTEGER/REAL)، والقيود سليمة.
    // ═════════════════════════════════════════════════════════════════════
    @Test
    fun migration10to11_addsLineTaxColumnsWithNeutralDefaults() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v9 حقيقية = بنية v4 + سلسلة 4→5→6→7→8→9 (نمط اختبار 9→10 حرفياً)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[5].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[6].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[7].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)
            // الوصول إلى الترحيل المالي (9→10) ثم بذر سطر بأسلوب v10 (قروش INTEGER)
            com.superbiz.app.AppGraph.MIGRATIONS[8].migrate(db)
            db.execSQL(
                "INSERT INTO `invoice_items` (`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) " +
                    "VALUES (1, NULL, 'سطر تاريخي', 2.0, 150000, 0)"
            )
            val rowsBefore = scalar(db, "SELECT COUNT(*) FROM invoice_items")

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[9]
            assertEquals(10, migration.startVersion)
            assertEquals(11, migration.endVersion)
            migration.migrate(db)
            assertEquals("القائمة صارت 13 ترحيلات — الأخير أرشيف zatca_docs 13→14", 13, com.superbiz.app.AppGraph.MIGRATIONS.size)

            // 1) العمودان بألفة Room المتوقعة
            assertEquals("taxKind INTEGER", "INTEGER", columnType(db, "invoice_items", "taxKind"))
            assertEquals("taxRate REAL", "REAL", columnType(db, "invoice_items", "taxRate"))

            // 2) الصف القائم أخذ البذرتين المحايدتين — دلالة تاريخية حرفية
            assertEquals("taxKind الافتراضي 0 قياسية", 0L, scalar(db, "SELECT taxKind FROM invoice_items WHERE desc = 'سطر تاريخي'"))
            assertEquals("taxRate الافتراضي -1 وراثة الرأس", -1.0, scalarDouble(db, "SELECT taxRate FROM invoice_items WHERE desc = 'سطر تاريخي'"), 0.0)

            // 3) صف جديد بلا الحقلين يأكلهما من DEFAULT
            db.execSQL(
                "INSERT INTO `invoice_items` (`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) " +
                    "VALUES (1, NULL, 'سطر بلا حقول', 1.0, 900, 0)"
            )
            assertEquals(0L, scalar(db, "SELECT taxKind FROM invoice_items WHERE desc = 'سطر بلا حقول'"))
            assertEquals(-1.0, scalarDouble(db, "SELECT taxRate FROM invoice_items WHERE desc = 'سطر بلا حقول'"), 0.0)

            // 4) لا صف فقد وأعمدة v10 القديمة سليمة
            assertEquals("كل الصفوف حية", rowsBefore + 1L, scalar(db, "SELECT COUNT(*) FROM invoice_items"))
            assertEquals("unitPrice بقي INTEGER قروش", "INTEGER", columnType(db, "invoice_items", "unitPrice"))
            assertEquals("qty بقي REAL كمية", "REAL", columnType(db, "invoice_items", "qty"))

            // 5) القيود والفهارس والمفاتيح لم تُمس — ALTER ADD لا يعيد البناء
            assertEquals("invoice_items: مفتاحان أجنبيان باقيان", 2L, fkCount(db, "invoice_items"))
            assertEquals("foreign_key_check: لا صفوف يتيمة", 0L, countRows(db, "PRAGMA foreign_key_check"))
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'invoice_items'").use { c ->
                val names = HashSet<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertTrue("فهرس invoiceId باق", names.contains("index_invoice_items_invoiceId"))
                assertTrue("فهرس productId باق", names.contains("index_invoice_items_productId"))
            }
        } finally {
            db.close()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // [P46-W1] ترقية 11→12 — نقاط الولاء والكوبونات: جدولان بإنشاء فقط.
    // لا جدول قائم يُمس ولا عمود يُضاف — المعيار: الجدولان ببنية Room المطابقة
    // (ألفات/مفاتيح أجنبية CASCADE/فهرس فريد للكود)، والبيانات القائمة كلها حية،
    // والصفوف الجديدة تُقبل بعد الترحيل، وforeign_key_check نظيف.
    // ═════════════════════════════════════════════════════════════════════
    @Test
    fun migration11to12_createsLoyaltyAndCouponTablesKeepingAllData() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v9 حقيقية = بنية v4 + سلسلة 4→5→6→7→8→9 (نمط اختبار 10→11 حرفياً)
                    fullV4Schema(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[3].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[4].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[5].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[6].migrate(db)
                    com.superbiz.app.AppGraph.MIGRATIONS[7].migrate(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        try {
            seedV4Data(db)
            // الوصول إلى القروش (9→10) ثم حقول السطر (10→11) — وصفوف قائمة تُحفظ
            com.superbiz.app.AppGraph.MIGRATIONS[8].migrate(db)
            com.superbiz.app.AppGraph.MIGRATIONS[9].migrate(db)
            db.execSQL(
                "INSERT INTO `invoice_items` (`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`) " +
                    "VALUES (1, NULL, 'سطر قبل v12', 1.0, 900, 0)"
            )
            val rowsBefore = scalar(db, "SELECT COUNT(*) FROM invoice_items")

            val migration = com.superbiz.app.AppGraph.MIGRATIONS[10]
            assertEquals(11, migration.startVersion)
            assertEquals(12, migration.endVersion)
            migration.migrate(db)
            assertEquals("القائمة صارت 13 ترحيلات — الأخير أرشيف zatca_docs 13→14", 13, com.superbiz.app.AppGraph.MIGRATIONS.size)

            // 1) الجدولان وُجدا بألفة Room المتوقعة
            assertEquals("partyId INTEGER", "INTEGER", columnType(db, "loyalty_entries", "partyId"))
            assertEquals("delta INTEGER", "INTEGER", columnType(db, "loyalty_entries", "delta"))
            assertEquals("reason TEXT", "TEXT", columnType(db, "loyalty_entries", "reason"))
            assertEquals("invoiceId INTEGER (nullable)", "INTEGER", columnType(db, "loyalty_entries", "invoiceId"))
            assertEquals("code TEXT", "TEXT", columnType(db, "coupons", "code"))
            assertEquals("amountPiasters INTEGER قروش", "INTEGER", columnType(db, "coupons", "amountPiasters"))
            assertEquals("percent REAL", "REAL", columnType(db, "coupons", "percent"))
            assertEquals("active INTEGER", "INTEGER", columnType(db, "coupons", "active"))

            // 2) المفاتيح الأجنبية: للدفتر اثنان (parties CASCADE / invoices CASCADE) والكوبونات بلا
            assertEquals("loyalty_entries: مفتاحان أجنبيان", 2L, fkCount(db, "loyalty_entries"))
            assertEquals("coupons: بلا مفاتيح أجنبية", 0L, fkCount(db, "coupons"))

            // 3) الفهرس الفريد لكود الكوبون موجود
            db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'coupons'").use { c ->
                val names = HashSet<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertTrue("فهرس الكود الفريد باقٍ", names.contains("index_coupons_code"))
            }

            // 4) البيانات القائمة كلها حية بلا مساس (CREATE فقط لا يلمس الجداول القائمة)
            assertEquals("كل صفوف invoice_items حية", rowsBefore, scalar(db, "SELECT COUNT(*) FROM invoice_items"))

            // 5) الصفوف الجديدة تُقبل بعد الترحيل — دفتر بطرف وفاتورة قائمين وكوبون بكود فريد
            db.execSQL(
                "INSERT INTO `loyalty_entries` (`partyId`,`invoiceId`,`delta`,`reason`,`note`,`createdAt`) " +
                    "VALUES (1, 1, 5, 'EARN', 'اختبار', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO `coupons` (`code`,`kind`,`amountPiasters`,`percent`,`expiresAt`,`maxUses`,`usedCount`,`active`,`note`,`createdAt`) " +
                    "VALUES ('WELCOME', 0, 2500, 0.0, 0, 0, 0, 1, '', 1700000000000)"
            )
            assertEquals(5L, scalar(db, "SELECT SUM(delta) FROM loyalty_entries"))
            assertEquals(2500L, scalar(db, "SELECT amountPiasters FROM coupons WHERE code = 'WELCOME'"))

            // 6) foreign_key_check نظيف على كامل القاعدة
            assertEquals("foreign_key_check: لا صفوف يتيمة", 0L, countRows(db, "PRAGMA foreign_key_check"))
        } finally {
            db.close()
        }
    }

    private fun columnType(db: SupportSQLiteDatabase, table: String, column: String): String? =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == column) return c.getString(c.getColumnIndexOrThrow("type"))
            }
            null
        }

    /** [P17-a] (اسم، نوع، notnull، dflt) لكل عمود — مقارنة بنية Room حرفياً في ترحيل 8→9 */
    private data class ColumnInfo(val name: String, val type: String, val notNull: String, val dflt: String?)

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val out = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) out += ColumnInfo(
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getString(c.getColumnIndexOrThrow("notnull")),
                if (c.isNull(c.getColumnIndexOrThrow("dflt_value"))) null
                else c.getString(c.getColumnIndexOrThrow("dflt_value"))
            )
        }
        return out
    }
}
