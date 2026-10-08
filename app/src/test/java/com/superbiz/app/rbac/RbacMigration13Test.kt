package com.superbiz.app.rbac

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.data.repo.OwnerPinSeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-3][V 1.2.0] اختبار ترحيل v12→v13 الشامل E2E — قاعدة v12 حقيقية ممتلئة
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (RBAC_V13_DESIGN §6-1 + ZATCA2_WAVE2_PLAN حاجز Z2-أ): يُبنى
 * ملف قاعدة v12 كامل البنية (من مخطط Room المُصدَّر 12.json — مصدر الحقيقة
 * لا نسخ يدوية)، تُبذر ببيانات واقعية من كل الموجات، ثم يُشغَّل
 * MIGRATION_12_13 فعلياً، ثم:
 *  1. كل صفوف الجداول القائمة سليمة — العدّادات قبل/بعد مطابقة (الفرق
 *     المعلن الوحيد: +1 سطر المالك المزروع).
 *  2. المالك يُزرع (جدول فارغ بنيوياً) بسر منقول نصاً من مزوّد البذرة،
 *     أو بلا سر حين لا حماية قائمة (سيناريو التصميم §4.2-3).
 *  3. أعمدة audit_log الجديدة NULL للصفوف التاريخية (دلالة «قبل التبني»).
 *  4. أعمدة ZATCA-2 التسعة على الفواتير ببذورها الآمنة.
 *  5. **التحقق الحاسم**: القاعدة نفسها تُفتح بRoom v13 — المطابقة البايتية
 *     بين SQL الترحيل ومخطط Room مطلوبة وإلا رمى IllegalStateException.
 */
@RunWith(AndroidJUnit4::class)
// application ناضف — إقلاع SuperBizApp الحقيقي يطلق بذرة غير متزامنة قد يعيد
// ربط ownerPinSeedProvider على خيط آخر (سباق مع ضبط الاختبار له) — نمنعه من
// الأساس كما يفعل نمط DebtsInventoryVMTest
@Config(sdk = [34], application = android.app.Application::class)
class RbacMigration13Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        // إعادة مزوّد البذرة للافتراض (لا تسريب حالة بين الاختبارات)
        AppGraph.ownerPinSeedProvider = null
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** يبني قاعدة v12 كاملة من مخطط Room المُصدَّر ويثبّت بيانات واقعية */
    private fun buildV12Database(name: String?): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadV12SchemaSql()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.forEach { db.execSQL(it) }
                    seedRealisticV12Data(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        db.execSQL("PRAGMA foreign_keys = ON")
        return db
    }

    /** قراءة مخطط v12 من الملف المُصدَّر — كل createSql للجداول والفهارس بالترتيب */
    private fun loadV12SchemaSql(): List<String> {
        val candidates = listOf(
            "schemas/com.superbiz.app.data.db.AppDatabase/12.json",
            "app/schemas/com.superbiz.app.data.db.AppDatabase/12.json"
        )
        val f = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("12.json schema not found — run from module dir")
        val root = org.json.JSONObject(f.readText())
        val out = ArrayList<String>()
        val entities = root.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            out += e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val idx = indices.getJSONObject(j)
                out += idx.getString("createSql")
                    .replace("\${TABLE_NAME}", e.getString("tableName"))
                    .replace("\${INDEX_NAME}", idx.getString("name"))
            }
        }
        return out
    }

    /** بيانات واقعية بترتيب الاعتماديات — العملاء والفواتير والسجل والولاء */
    private fun seedRealisticV12Data(db: SupportSQLiteDatabase) {
        // أطراف — عميل و مورد و طرف نقدي (أعمدة v12 الـ18 كاملة)
        db.execSQL(
            "INSERT INTO `parties` (`id`,`name`,`phone`,`type`,`note`,`createdAt`,`archived`,`favorite`,`lat`,`lng`,`email`,`address`,`taxNumber`,`crNumber`,`city`,`country`,`website`,`accountNumber`) " +
                "VALUES (1, 'مؤسسة النور', '0501234567', 0, 'عميل دائم', 100, 0, 1, 24.7, 46.7, 'a@b.sa', 'الرياض', '300000000000003', '1010101010', 'الرياض', 'السعودية', '', 'SA03000')"
        )
        db.execSQL(
            "INSERT INTO `parties` (`id`,`name`,`phone`,`type`,`note`,`createdAt`,`archived`,`favorite`,`lat`,`lng`,`email`,`address`,`taxNumber`,`crNumber`,`city`,`country`,`website`,`accountNumber`) " +
                "VALUES (2, 'مورد الأصناف', '0119998888', 1, '', 110, 0, 0, NULL, NULL, '', 'جدة', '', '', '', '', '', '')"
        )
        db.execSQL(
            "INSERT INTO `parties` (`id`,`name`,`phone`,`type`,`note`,`createdAt`,`archived`,`favorite`,`lat`,`lng`,`email`,`address`,`taxNumber`,`crNumber`,`city`,`country`,`website`,`accountNumber`) " +
                "VALUES (3, 'زبون نقدي', '', 0, 'نقطة بيع', 120, 0, 0, NULL, NULL, '', '', '', '', '', '', '', '')"
        )
        // منتجات
        db.execSQL(
            "INSERT INTO `products` (`id`,`name`,`sku`,`barcode`,`unit`,`costPrice`,`salePrice`,`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived`) " +
                "VALUES (1, 'صنف أ', 'SKU-1', '2000000000017', 'قطعة', 5000, 8000, 40.0, 5.0, 'عام', 100, 0)"
        )
        db.execSQL(
            "INSERT INTO `products` (`id`,`name`,`sku`,`barcode`,`unit`,`costPrice`,`salePrice`,`stockQty`,`reorderLevel`,`category`,`createdAt`,`archived`) " +
                "VALUES (2, 'صنف ب', 'SKU-2', '', 'كرتون', 20000, 30000, 3.0, 5.0, 'عام', 105, 0)"
        )
        // فواتير — مدفوعة وجزئية وغير مدفوعة وملغاة (كل الحالات)
        db.execSQL(
            "INSERT INTO `invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                "VALUES (1, 'INV-0001', 1, 0, 200, 300, 8000, 0, 15.0, 1200, 9200, 9200, 5000, 2, 'SAR', 1.0, '')"
        )
        db.execSQL(
            "INSERT INTO `invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                "VALUES (2, 'INV-0002', 1, 0, 210, 310, 30000, 500, 15.0, 4425, 33925, 10000, 20000, 1, 'SAR', 1.0, 'جزئية')"
        )
        db.execSQL(
            "INSERT INTO `invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                "VALUES (3, 'INV-0003', 3, 0, 220, 230, 16000, 0, 0.0, 0, 16000, 0, 10000, 0, 'SAR', 1.0, '')"
        )
        db.execSQL(
            "INSERT INTO `invoices` (`id`,`number`,`partyId`,`type`,`date`,`dueDate`,`subtotal`,`discount`,`taxRate`,`taxAmount`,`total`,`paid`,`costTotal`,`status`,`currency`,`fxRate`,`note`) " +
                "VALUES (4, 'INV-0004', 1, 1, 190, 200, 50000, 0, 15.0, 7500, 57500, 57500, 0, 3, 'SAR', 1.0, 'شراء ملغى')"
        )
        // بنود الفواتير — صفرية/معفاة/وراثة (v11)
        db.execSQL(
            "INSERT INTO `invoice_items` (`id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`,`taxKind`,`taxRate`) " +
                "VALUES (1, 1, 1, 'صنف أ', 1.0, 8000, 0, 0, -1.0)"
        )
        db.execSQL(
            "INSERT INTO `invoice_items` (`id`,`invoiceId`,`productId`,`desc`,`qty`,`unitPrice`,`discount`,`taxKind`,`taxRate`) " +
                "VALUES (2, 2, 2, 'صنف ب', 1.0, 30000, 500, 2, -1.0)"
        )
        // دفعات
        db.execSQL(
            "INSERT INTO `payments` (`id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`,`planId`) " +
                "VALUES (1, 1, 1, NULL, 9200, 205, 0, 'CASH', 'سداد كامل', NULL)"
        )
        db.execSQL(
            "INSERT INTO `payments` (`id`,`partyId`,`invoiceId`,`checkId`,`amount`,`date`,`direction`,`method`,`note`,`planId`) " +
                "VALUES (2, 1, 2, NULL, 10000, 215, 0, 'BANK', 'دفعة أولى', NULL)"
        )
        // مصروفات
        db.execSQL("INSERT INTO `expenses` (`id`,`amount`,`category`,`note`,`date`,`createdAt`) VALUES (1, 15000, 'إيجار', 'شهر', 210, 210)")
        // سجل التدقيق — صفوف ما قبل v13 بلا إسناد
        db.execSQL("INSERT INTO `audit_log` (`id`,`actor`,`action`,`details`,`ts`) VALUES (1, 'owner', 'STATEMENT_ISSUE', 'ST-0001 party=1 1..9 hash=abc', 300)")
        db.execSQL("INSERT INTO `audit_log` (`id`,`actor`,`action`,`details`,`ts`) VALUES (2, 'owner', 'STATEMENT_SEND', 'statement=1 channel=whatsapp status=SENT attempts=1', 310)")
        // الولاء والكوبونات (v12)
        db.execSQL("INSERT INTO `loyalty_entries` (`id`,`partyId`,`invoiceId`,`delta`,`reason`,`note`,`createdAt`) VALUES (1, 1, 1, 9, 'EARN', '', 320)")
        db.execSQL("INSERT INTO `coupons` (`id`,`code`,`kind`,`amountPiasters`,`percent`,`expiresAt`,`maxUses`,`usedCount`,`active`,`note`,`createdAt`) VALUES (1, 'WELCOME', 0, 500, 0.0, 0, 10, 1, 1, 'ترحيبي', 300)")
    }

    /** عدّادات صفوف كل الجداول المبذرة — قبل/بعد */
    private fun rowCounts(db: SupportSQLiteDatabase): Map<String, Int> {
        val tables = listOf(
            "parties", "products", "invoices", "invoice_items", "payments",
            "expenses", "audit_log", "loyalty_entries", "coupons"
        )
        val out = LinkedHashMap<String, Int>()
        for (t in tables) {
            db.query("SELECT COUNT(*) FROM `$t`").use { c ->
                c.moveToFirst()
                out[t] = c.getInt(0)
            }
        }
        return out
    }

    // ═══ الاختبار الشامل: بذرة رمز موجودة (الجهاز المحمي بترمز) ═══

    @Test
    fun migration12to13_preservesAllRows_seedsOwner_withPinSeed_andOpensWithRoomV13() {
        dbName = "rbac_e2e_seed.db"
        // مزوّد بذرة — يحاكي جهازاً محمياً برمز قائم (نسخ نص لا إعادة تشفير)
        AppGraph.ownerPinSeedProvider = {
            OwnerPinSeed(
                pinWrapped = "ks:deadbeef:cafebabe",
                pinSalt = "aabbccddeeff00112233445566778899",
                pinIters = 600000,
                biometric = true
            )
        }

        val db = buildV12Database(dbName)
        val before = rowCounts(db)

        val migration = AppGraph.MIGRATIONS[11]
        assertEquals(12, migration.startVersion)
        assertEquals(13, migration.endVersion)
        migration.migrate(db)

        // 1) كل الصفوف القائمة سليمة — الفرق المعلن الوحيد المالك المزروع
        val after = rowCounts(db)
        assertEquals(before, after)

        // 2) المالك المزروع — صف وحيد دور 0 فعّال
        db.query("SELECT id, name, role, active, createdAt FROM users").use { c ->
            assertTrue("users must have exactly one seeded owner", c.moveToFirst())
            assertEquals("المالك", c.getString(1))
            assertEquals(0, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertTrue(c.getLong(4) > 0)
            assertFalse("users must have exactly one row", c.moveToNext())
        }

        // 3) سر المالك منقول نصاً من البذرة (لا إعادة تشفير)
        db.query("SELECT userId, pinWrapped, pinSalt, pinIters, biometricAllowed FROM user_secrets").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("ks:deadbeef:cafebabe", c.getString(1))
            assertEquals("aabbccddeeff00112233445566778899", c.getString(2))
            assertEquals(600000, c.getInt(3))
            assertEquals(1, c.getInt(4))
            assertFalse(c.moveToNext())
        }

        // 4) أعمدة الإسناد التاريخية NULL — دلالة «قبل التبني»
        db.query("SELECT COUNT(*) FROM audit_log WHERE actorId IS NOT NULL OR actorRole IS NOT NULL").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }

        // 5) بذور ZATCA-2 الآمنة على كل فواتير v12
        db.query(
            "SELECT COUNT(*) FROM invoices WHERE uuid != '' OR icv != 0 OR pih != '' " +
                "OR zatcaSubtype != '' OR deliveryDate != 0 OR buyerName != '' " +
                "OR buyerVat != '' OR buyerAddress != '' OR zatcaStatus != 0"
        ).use { c ->
            c.moveToFirst()
            assertEquals("ZATCA-2 seeds must be neutral on all 4 legacy invoices", 0, c.getInt(0))
        }

        // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom v13 — مطابقة بايتية للمخطط ═══
        // [Z2-أ V 1.5.0]: إكمال السلسلة إلى v14 (أرشيف zatca_docs بإنشاء فقط) —
        // المخطط الحالي 14 فالفتحة النهائية تكون بمخطط اليوم كاملاً
        AppGraph.MIGRATIONS[12].migrate(db)
        db.version = 14   // كما يفعل MigrationContainer داخلياً بعد migrate()
        db.close()

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            // أي عدم تطابق بين SQL الترحيل ومخطط Room v14 يرمى هنا (RoomOpenHelper.checkIdentity)
            kotlinx.coroutines.runBlocking {
                val all = room.users().all()
                assertEquals(1, all.size)
                assertEquals(0, all[0].role)
                val secret = room.userSecrets().byUser(all[0].id)!!
                assertEquals("ks:deadbeef:cafebabe", secret.pinWrapped)
                assertEquals(2, room.auditLog().count())
                val invoices = room.invoices().exportOnce()
                assertEquals(4, invoices.size)
                assertEquals("", invoices[0].uuid)
                assertEquals(0L, invoices[0].icv)
                assertEquals(0, invoices[0].zatcaStatus)
            }
        } finally {
            room.close()
        }
    }

    // ═══ السيناريو الثاني: لا حماية قائمة (المالك بلا سر) ═══

    @Test
    fun migration12to13_withoutExistingPin_seedsOwnerWithoutSecret() {
        dbName = "rbac_e2e_nopin.db"
        // مزوّد يعيد null — جهاز بلا حماية قائمة (سيناريو التصميم §4.2-3 الحرفي)
        AppGraph.ownerPinSeedProvider = { null }

        val db = buildV12Database(dbName)
        val before = rowCounts(db)

        AppGraph.MIGRATIONS[11].migrate(db)

        // الصفوف القائمة كلها سليمة (المالك يُزرع في users الجديدة لا في القديمة)
        assertEquals(before, rowCounts(db))

        db.query("SELECT COUNT(*) FROM users WHERE role = 0").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM user_secrets").use { c ->
            c.moveToFirst()
            assertEquals("no PIN configured → owner has no secret row", 0, c.getInt(0))
        }
    }

    // ═══ حواف العقد ═══

    @Test
    fun migration12to13_userSecretsCascadeWithUsers() {
        dbName = "rbac_e2e_cascade.db"
        AppGraph.ownerPinSeedProvider = {
            OwnerPinSeed("ks:aa:bb", "ccdd", 600000, false)
        }
        val db = buildV12Database(dbName)
        AppGraph.MIGRATIONS[11].migrate(db)

        // مستخدم إضافي بسر ثم حذفه — السر يمشي معه (عقد CASCADE)
        db.execSQL("INSERT INTO `users` (`name`,`role`,`active`,`createdAt`,`lastSeenAt`) VALUES ('كاشير', 3, 1, 500, 0)")
        db.query("SELECT id FROM users WHERE name = 'كاشير'").use { c ->
            c.moveToFirst()
            db.execSQL("INSERT INTO `user_secrets` (`userId`,`pinWrapped`,`pinSalt`,`pinIters`,`biometricAllowed`) VALUES (${c.getLong(0)}, 'ks:11:22', 'eeff', 600000, 0)")
        }
        db.execSQL("DELETE FROM users WHERE name = 'كاشير'")
        db.query("SELECT COUNT(*) FROM user_secrets").use { c ->
            c.moveToFirst()
            assertEquals("owner secret only — cashier secret cascaded", 1, c.getInt(0))
        }
    }

    @Test
    fun migration12to13_isInMigrationsListAndWiredInBuilder() {
        // عقد التسجيل: الترحيل الأخير في القائمة المكشوفة للاختبارات
        assertEquals(13, AppGraph.MIGRATIONS.size)
        // [Z2-أ V 1.5.0]: أُلحق 13→14 بالنهاية — مشترك RBAC+ZATCA-2 صار الفهرس 11
        val shared = AppGraph.MIGRATIONS[11]
        assertEquals(12, shared.startVersion)
        assertEquals(13, shared.endVersion)
        val last = AppGraph.MIGRATIONS.last()
        assertEquals(13, last.startVersion)
        assertEquals(14, last.endVersion)
    }
}
