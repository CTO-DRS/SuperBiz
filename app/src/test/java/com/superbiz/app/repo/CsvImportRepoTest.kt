package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.repo.CsvImportRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-6 V 2.5.0] اختبارات منفّذ الاستيراد CsvImportRepo — قاعدة Room حقيقية
 * ═══════════════════════════════════════════════════════════════════════════
 * نمط RepoInvariantsTest حرفياً (inMemory + runBlocking). يثبت عقود التنفيذ:
 * إدخال جديد، تحديث المطابق، المخزون حركة تسوية لا كتابة صامتة، الأطراف
 * بهويتها، والأرصدة لا تُكتب نصاً إطلاقاً (عقد CsvImportKit بند 1).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CsvImportRepoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CsvImportRepo

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CsvImportRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val productsCsv = """
        الاسم,رمز SKU,الباركود,الوحدة,سعر التكلفة,سعر البيع,الكمية المتوفرة,حد الطلب,قيمة المخزون
        قهوة مختصة,SKU-1,6280000001,كيس,45.00,60.00,12,3,540.00
        سكر ناعم,SKU-2,,كجم,8.50,12.25,100.5,20,850.00
    """.trimIndent()

    @Test
    fun importProducts_createsThenUpdatesWithStockMove() = runBlocking {
        // الجولة الأولى: إدخال صنفين
        val s1 = repo.import(productsCsv, "العملاء", "الموردون", "عميل ومورد")
        assertEquals("products", s1.kind)
        assertEquals(2, s1.upserted)
        assertEquals(0, s1.skipped.size)
        val p1 = db.products().allOnceIncludingArchived().first { it.sku == "SKU-1" }
        assertEquals("قهوة مختصة", p1.name)
        assertEquals(4500L, p1.costPrice)
        assertEquals(6000L, p1.salePrice)
        assertEquals(12.0, p1.stockQty, 0.0) // صنف جديد: الكمية أولية مباشرة
        assertEquals(0, db.stockMoves().allMoves().count { it.productId == p1.id }) // بلا حركة تسوية عند الإنشاء

        // الجولة الثانية: نفس الرموز — تحديث الأسعار + فرق كمية يُقيَّد حركة لا كتابة صامتة
        val csv2 = productsCsv
            .replace("45.00", "47.50")
            .replace("12,3,540.00", "15,3,712.50")
        val s2 = repo.import(csv2, "العملاء", "الموردون", "عميل ومورد")
        assertEquals(2, s2.upserted)
        val p1b = db.products().allOnceIncludingArchived().first { it.sku == "SKU-1" }
        assertEquals(4750L, p1b.costPrice)   // السعر حُدِّث
        assertEquals(15.0, p1b.stockQty, 0.0) // الكمية صارت 15
        assertEquals(1, db.stockMoves().allMoves().count { it.productId == p1b.id }) // حركة تسوية واحدة بالفرق
        val moves = db.stockMoves().allMoves().filter { it.productId == p1b.id }
        assertEquals(1, moves.size)
        assertEquals(3.0, moves[0].qty, 0.0)  // 15 − 12 = +3
        assertEquals("csv_import", moves[0].refType)
    }

    @Test
    fun importParties_identityOnly_noBalanceWrite() = runBlocking {
        val csv = """
            الاسم,الهاتف,النوع,الرصيد,مؤشر الخطر,الحالة
            عميل التجزئة,0501111111,العملاء,999.99,مرتفع,نشط
            المورد الأساسي,0502222222,الموردون,50,منخفض,نشط
        """.trimIndent()
        val s = repo.import(csv, "العملاء", "الموردون", "عميل ومورد")
        assertEquals("parties", s.kind)
        assertEquals(2, s.upserted)
        val party = db.parties().exportOnce().first { it.phone == "0501111111" }
        assertEquals("عميل التجزئة", party.name)
        assertEquals(0, party.type)          // العملاء → 0
        // الرصيد عمداً لا يُكتب — لا قيد ولا رصيد وُلد من النص (عقد بند 1)
        assertEquals(0, db.journal().count())
        val supplier = db.parties().exportOnce().first { it.phone == "0502222222" }
        assertEquals(1, supplier.type)
    }

    @Test
    fun importUnknownKind_reportsCleanly() = runBlocking {
        val s = repo.import("الاسم,الملاحظة\nشخص ما,بلا هاتف\n", "العملاء", "الموردون", "عميل ومورد")
        assertEquals("unknown", s.kind)
        assertEquals(0, s.upserted)
        assertTrue(s.skipped.isNotEmpty())
    }
}
