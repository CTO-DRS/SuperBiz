package com.superbiz.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-18 V 3.2.6] اختبار تركيب السلسلة الكاملة v1→v18 — ترقية جهاز عريق دفعة واحدة
 * ═══════════════════════════════════════════════════════════════════════════
 * الخلفية (خارطة R7 بند 3): كل خطوة من خطوات السلسلة الـ17 مغطاة اختباراً حدّياً
 * (RVW-15 أقفل 1→2 و2→3)، لكن «تكوين» السلسلة — جهاز v1 حقيقي يرقى عبر كل
 * الخطوات في تجربة واحدة متصلة — لم يكن مبرهناً. هذا الاختبار هو البرهان:
 *
 * ① بنية v1 الحقيقية: 9 جداول التأسيس (نموذج RVW-15) + rules + currencies —
 *   الاثنان الأخيران مقيمان منذ v1 بالبرهان الانتحالي: لا ترحيل ينشئهما،
 *   وfullV4Schema (مصدر حقيقة v4 في SchemaMigrationTest) يتضمنهما بشبتهما.
 * ② بذور بيانات على v1 (طرف/فاتورة/بند/دفعة/سطر دفعة/منتج/شيك/عملة/قاعدة)
 *   وبذور عند إصدارات وسيطة (خطة أقساط على v2، مصروف على v3، كوبون على v12)
 *   — كلها يجب أن تنجو إلى v18.
 * ③ **حفظ الأعمدة لكل إصدار**: بعد كل ترحيل يبلغ به الجهاز الإصدار v ≥ 5
 *   تُقارن بنية القاعدة مقابل <v>.json المُصدَّر من معالج Room — الجداول
 *   والأعمدة بترتيبها الحرفي (اسم/نوع/عدم-إتاحة/مفتاح أساسي) والفهارس
 *   بأسمائها وفريديةها — مرجع غير دوري (المخططات المُصدَّرة لا SQL الترحيلات).
 *   (الإصدارات 2..4 بلا مخططات مُصدَّرة: يكفي حقائقها المعروفة المُختبَرة
 *   حدّياً في SchemaMigrationTest — إنشاء الجداول والفهارس المعروفة.)
 * ④ التحقق الحاسم: فتح القاعدة نفسها بRoom v18 — عدم المطابقة البايتية
 *   الكاملة (بما فيه المفاتيح الأجنبية) يرمى في RoomOpenHelper.checkIdentity —
 *   ثم قراءات DAO حية تثبت بقاء القيم المالية بالقروش بعد ترحيل المال 9→10.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationChainCompositionTest {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** بنية v1 الحقيقية — 11 جدولاً (انظر KDoc ①) */
    private fun trueV1Schema(db: SupportSQLiteDatabase) {
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
        // فهارس v1 — القائمة الحتمية: فهارس fullV4Schema مطروحاً منها إضافات الترحيلات
        // (1→2: الأقساط الخمسة · 2→3: المصروفات · 3→4: الخمسة الموثقة في SQL الترحيل)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parties_name` ON `parties` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_entryId` ON `journal_lines` (`entryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_account` ON `journal_lines` (`account`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_journal_lines_partyId` ON `journal_lines` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_productId` ON `stock_moves` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_date` ON `stock_moves` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_partyId` ON `invoices` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_date` ON `invoices` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoices_status` ON `invoices` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_invoiceId` ON `invoice_items` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_invoice_items_productId` ON `invoice_items` (`productId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_partyId` ON `payments` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_invoiceId` ON `payments` (`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_date` ON `payments` (`date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payments_checkId` ON `payments` (`checkId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_partyId` ON `checks` (`partyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_dueDate` ON `checks` (`dueDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checks_status` ON `checks` (`status`)")
    }

    private fun seedV1(db: SupportSQLiteDatabase) {
        // مبالغ REAL ريال (عقد ما قبل P33-P8) — يجب أن تتحول إلى قروش INTEGER ×100
        // بترحيل المال 9→10 وتنجو كلها إلى v18 حرفياً
        db.execSQL("INSERT INTO parties (id, name, phone, type, note, createdAt, archived) VALUES (1, 'عميل الجد العريق', '0500000001', 0, '', 100, 0)")
        db.execSQL("INSERT INTO products (id, name, sku, barcode, unit, costPrice, salePrice, stockQty, reorderLevel, category, createdAt, archived) VALUES (1, 'صنف الجد', 'SKU-1', '', 'قطعة', 6.0, 10.0, 50.0, 5.0, '', 100, 0)")
        db.execSQL("INSERT INTO invoices (id, number, partyId, type, date, dueDate, subtotal, discount, taxRate, taxAmount, total, paid, costTotal, status, currency, fxRate, note) VALUES (1, 'INV-0001', 1, 0, 100, 200, 80.0, 0.0, 0.0, 0.0, 80.0, 30.0, 60.0, 0, 'SAR', 1.0, '')")
        db.execSQL("INSERT INTO invoice_items (id, invoiceId, productId, desc, qty, unitPrice, discount) VALUES (1, 1, 1, 'بند الجد', 2.0, 40.0, 0.0)")
        db.execSQL("INSERT INTO payments (id, partyId, invoiceId, checkId, amount, date, direction, method, note) VALUES (1, 1, 1, NULL, 30.0, 100, 0, 'CASH', '')")
        db.execSQL("INSERT INTO checks (id, number, partyId, bank, amount, issueDate, dueDate, direction, status, note) VALUES (1, 'CHQ-1', 1, 'بنك الجد', 50.0, 100, 300, 0, 0, '')")
        db.execSQL("INSERT INTO journal (id, date, memo, refType, refId) VALUES (1, 100, 'قيد الجد', 'INVOICE', 1)")
        db.execSQL("INSERT INTO journal_lines (id, entryId, account, debit, credit, partyId, currency, fxRate) VALUES (1, 1, 'CASH', 80.0, 0.0, 1, 'SAR', 1.0)")
        db.execSQL("INSERT INTO rules (id, kind, enabled, daysBefore, lastRun) VALUES (1, 'CHECK_DUE', 1, 3, 0)")
        db.execSQL("INSERT INTO currencies (code, nameAr, nameEn, symbol, rateToBase, isBase) VALUES ('SAR', 'ريال', 'Riyal', '﷼', 1.0, 1)")
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun scalarStr(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    // ─────────────── حفظ الأعمدة مقابل المخطط المُصدَّر (②) ───────────────

    private fun loadSchemaJson(v: Int): JSONObject {
        val candidates = listOf(
            "schemas/com.superbiz.app.data.db.AppDatabase/$v.json",
            "app/schemas/com.superbiz.app.data.db.AppDatabase/$v.json"
        )
        val f = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("$v.json schema not found — run from module dir")
        return JSONObject(f.readText())
    }

    /**
     * يفحص كل كيان في <v>.json: الجدول بأعمدته (اسم/نوع/عدم-إتاحة/PK) وفهارسه.
     *
     * **عقد المقارنة بالمفتاح الاسمي (عقد Room نفسه)**: Room يتحقق من هوية المخطط
     * عند الفتح بجدول أعمدة مفتاحه الاسم (TableInfo) — ترتيب الأعمدة ليس جزءاً من
     * الهوية. وواقع الترحيلات يفرض هذا: أعمدة v16/v17 أُلحقت بـALTER فتأتي بعد
     * جداولها بترتيب التنفيذ، بينما <v>.json يرتبها بترتيب إعلان الكيان —
     * انحراف ترتيبي حميد تختبره هنا بالاسم حرفياً كما تفحصه Room (والفتح البايتي
     * النهائي بRoom v18 في هذا الاختبار نفسه يقفل التحقق الرسمي كاملاً).
     */
    private fun assertVersionShape(db: SupportSQLiteDatabase, v: Int) {
        val root = loadSchemaJson(v)
        val entities = root.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            assertEquals("الإصدار $v: الجدول $table مفقود", 1L,
                scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"))

            // أعمدة JSON بالمفتاح الاسمي — عقد Room حرفياً
            val fields = e.getJSONArray("fields")
            val expected = LinkedHashMap<String, Triple<String, Boolean, Boolean>>() // name → (type, notNull, isPk)
            val pk = e.getJSONObject("primaryKey").getJSONArray("columnNames")
            val pkSet = (0 until pk.length()).mapTo(HashSet()) { pk.getString(it) }
            for (j in 0 until fields.length()) {
                val f = fields.getJSONObject(j)
                val name = f.getString("columnName")
                expected[name] = Triple(f.getString("affinity"), f.getBoolean("notNull"), name in pkSet)
            }
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameI = c.getColumnIndex("name")
                val typeI = c.getColumnIndex("type")
                val notNullI = c.getColumnIndex("notnull")
                val pkI = c.getColumnIndex("pk")
                val actual = LinkedHashMap<String, Triple<String, Int, Int>>()
                while (c.moveToNext()) {
                    actual[c.getString(nameI)] = Triple(c.getString(typeI), c.getInt(notNullI), c.getInt(pkI))
                }
                assertEquals("الإصدار $v: $table مجموعة الأعمدة", expected.keys, actual.keys)
                for ((name, want) in expected) {
                    val got = actual[name]!!
                    assertEquals("الإصدار $v: $table.$name النوع", want.first, got.first)
                    assertEquals("الإصدار $v: $table.$name عدم-الإتاحة",
                        if (want.second) 1 else 0, got.second)
                    assertEquals("الإصدار $v: $table.$name عضوية المفتاح الأساسي",
                        if (want.third) 1 else 0, if (got.third > 0) 1 else 0)
                }
            }

            // الفهارس بالاسم والفريدية
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val idx = indices.getJSONObject(j)
                val name = idx.getString("name")
                assertEquals("الإصدار $v: فهرس $name مفقود على $table", 1L,
                    scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$name'"))
                val wantUnique = if (idx.getBoolean("unique")) "UNIQUE" else null
                val sql = scalarStr(db, "SELECT IFNULL(sql, '') FROM sqlite_master WHERE type = 'index' AND name = '$name'")
                if (wantUnique != null) assertTrue("الإصدار $v: فهرس $name يجب أن يكون فريداً", sql.contains(wantUnique))
            }
        }
    }

    @Test
    fun fullChain_v1to18_oneContinuousUpgrade_dataSurvivesPerVersionShapesHold() {
        dbName = "rvw18_chain.db"
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    trueV1Schema(db)
                    seedV1(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        try {
            // السلسلة الحقيقية كاملة — خطوة خطوة في تجربة واحدة متصلة
            for (k in AppGraph.MIGRATIONS.indices) {
                val m = AppGraph.MIGRATIONS[k]
                assertEquals(k + 1, m.startVersion)
                assertEquals(k + 2, m.endVersion)
                m.migrate(db)
                val v = k + 2

                // بذور الإصدارات الوسيطة — صفوف وُلدت في منتصف حياة الجهاز العريق
                when (v) {
                    2 -> { // 1→2 أنشأ جدولا الأقساط — زرع صفين كما يفعل مستخدم v2 حقيقي
                        db.execSQL("INSERT INTO installment_plans (id, title, partyId, direction, total, downPayment, financed, months, startDate, currency, note, createdAt, archived) VALUES (1, 'خطة الجد', 1, 0, 400.0, 0.0, 400.0, 4, 100, 'SAR', '', 100, 0)")
                        db.execSQL("INSERT INTO installments (id, planId, seq, amount, dueDate, paidAmount, paidDate, status) VALUES (1, 1, 1, 100.0, 200, 0.0, NULL, 0)")
                    }
                    3 -> db.execSQL("INSERT INTO expenses (id, amount, category, note, date, createdAt) VALUES (1, 5.0, 'إيجار', '', 100, 100)") // 2→3 أنشأ المصروفات
                    12 -> db.execSQL("INSERT INTO coupons (code, kind, amountPiasters, percent, expiresAt, maxUses, usedCount, active, note, createdAt) VALUES ('CHAIN-1', 0, 500, 0.0, 0, 10, 2, 1, '', 1000)") // 11→12 أنشأ الكوبونات
                }

                // حفظ الأعمدة مقابل المخطط المُصدَّر (v ≥ 5 — حيث تبدأ المخططات المُصدَّرة)
                if (v >= 5) assertVersionShape(db, v)
            }
            // الترويسة لا يرفعها migrate اليدوي (Room يرفعها حين يشغّل الترحيلات نفسه)
            // — نضبطها إلى 18 ليعاد فتح القاعدة بالتحقق البايتي عند v18 مباشرة
            db.version = 18

            // حكم البقاء على القاعدة الخام: المال تحول قروشاً بلا فاقد (9→10 ×100)
            assertEquals(8000L, scalar(db, "SELECT total FROM invoices WHERE id = 1"))
            assertEquals(3000L, scalar(db, "SELECT paid FROM invoices WHERE id = 1"))
            assertEquals(4000L, scalar(db, "SELECT unitPrice FROM invoice_items WHERE id = 1"))
            assertEquals(3000L, scalar(db, "SELECT amount FROM payments WHERE id = 1"))
            assertEquals(5000L, scalar(db, "SELECT amount FROM checks WHERE id = 1"))
            assertEquals(8000L, scalar(db, "SELECT debit FROM journal_lines WHERE id = 1"))
            assertEquals(1000L, scalar(db, "SELECT salePrice FROM products WHERE id = 1"))
            assertEquals(500L, scalar(db, "SELECT amount FROM expenses WHERE id = 1"))
            assertEquals("عميل الجد العريق", scalarStr(db, "SELECT name FROM parties WHERE id = 1"))
            assertEquals("صف الكوبون الوسيط نجا", 2L, scalar(db, "SELECT usedCount FROM coupons WHERE code = 'CHAIN-1'"))
            assertEquals("قاعدة العملة نجت", 1L, scalar(db, "SELECT COUNT(*) FROM currencies WHERE code = 'SAR'"))
            assertEquals("القاعدة نجت", 1L, scalar(db, "SELECT COUNT(*) FROM rules WHERE id = 1"))
            assertEquals("خطة الأقساط الوسيطة نجت", 1L, scalar(db, "SELECT COUNT(*) FROM installment_plans WHERE id = 1"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM installments WHERE id = 1"))

            // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom v18 (عدم المطابقة البايتية يرمى) ═══
            db.close()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .allowMainThreadQueries()
                .addMigrations(*AppGraph.MIGRATIONS.toTypedArray())
                .build()
            try {
                kotlinx.coroutines.runBlocking {
                    // قراءات DAO حية على المخطط الحقيقي — القيم المالية بالقروش سليمة
                    val party = room.parties().byId(1L)!!
                    assertEquals("عميل الجد العريق", party.name)
                    val invoice = room.invoices().byId(1L)!!
                    assertEquals(8000L, invoice.total)
                    assertEquals(3000L, invoice.paid)
                    val coupon = room.coupons().byCode("CHAIN-1")!!
                    assertEquals(2, coupon.usedCount)
                    assertEquals(10, coupon.maxUses)
                    // دفتر v18 قابل للكتابة على قاعدة الجهاز العريق بعد الترقية
                    val ev = room.couponRedemptions().insertOrIgnore(
                        com.superbiz.app.data.db.CouponRedemptionEntity(
                            eventId = "ancient|1|CHAIN-1", code = "CHAIN-1",
                            invoiceId = 1L, qty = 1, at = 2000L, origin = "ancient"
                        )
                    )
                    assertTrue(ev > 0L)
                    assertEquals(1, room.couponRedemptions().countByCode("CHAIN-1"))
                }
            } finally {
                room.close()
            }
        } finally {
            if (db.isOpen) db.close()
        }
    }
}
