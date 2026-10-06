package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.PosCartLine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * — اختبارات انحدار لإصلاحات الموجة (كلها تفشل قبل الإصلاح)
 * F4 سطر بيع حر بلا منتج يُحذف ويُعدَّل بمؤشره وحده (كان الحذف ميتاً والتعديل جماعياً)
 * F13 سقوف المخزون تُحدَّث من حالة المنتجات الحيّة فيتقلص الكمية فوراً
 * F8 lowStock يستبعد المؤرشف (كانت التنبيهات تعمل عليه للأبد)
 * F2 مفاتيح سلة POS فريدة حتى مع عدة سطور بلا منتج (لا انهيار LazyColumn)
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R15FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        inventory = InventoryRepo(db, ledger)
    }

    @After
    fun teardown() { db.close() }

    // ═══ F4: إدارة سطر البيع الحر بالمؤشر ═══

    @Test
    fun f4_freeSaleLineRemovableByIndex_only() {
        // [P33-P8] الأسعار والتكاليف قروش (50.0/0.0 ريال → 5000/0) — الكميات والمخزون تبقى Double
        val cart = listOf(
            PosCartLine(null, "بيع حر — صيانة", 5_000L, 0L, 1.0, 0.0),
            PosCartLine(7L, "شاي", 1_000L, 600L, 3.0, 10.0),
            PosCartLine(null, "بيع حر — توصيل", 1_500L, 0L, 1.0, 0.0),
        )
        // قبل الإصلاح: remove(null) لا يحذف شيئاً إطلاقاً (زر ميت)
        val afterLegacyRemove = PosCart.remove(cart, null)
        assertEquals(3, afterLegacyRemove.size)

        // بالإصلاح: removeAt(0) يحذف سطر الصيانة فقط
        val removed = PosCart.removeAt(cart, 0)
        assertEquals(2, removed.size)
        assertEquals("شاي", removed[0].name)
        assertEquals("بيع حر — توصيل", removed[1].name)
    }

    @Test
    fun f4_setQtyByIndex_touchesOneFreeLineOnly() {
        val cart = listOf(
            PosCartLine(null, "بيع حر — أ", 5_000L, 0L, 1.0, 0.0),   // [P33-P8] قروش
            PosCartLine(null, "بيع حر — ب", 1_500L, 0L, 1.0, 0.0),
        )
        // قبل الإصلاح: setQty(null, 4) كانت تضبط الكمية 4 لكل السطور بلا منتج
        val after = PosCart.setQtyAt(cart, 0, 4.0)
        assertEquals(4.0, after[0].qty, 1e-9)
        assertEquals(1.0, after[1].qty, 1e-9)   // السطر الآخر لم يُمس

        // الكمية 0 عبر المؤشر تحذف السطر الموجَّه حصراً
        val afterZero = PosCart.setQtyAt(cart, 1, 0.0)
        assertEquals(1, afterZero.size)          // حُذف سطر «ب» فقط
        assertEquals("بيع حر — أ", afterZero[0].name)

        // مؤشر خارج المدى: بلا أي تغيير ولا انهيار
        assertTrue(PosCart.setQtyAt(cart, 99, 3.0) === cart)
        assertTrue(PosCart.removeAt(cart, -1) === cart)
    }

    // ═══ F13: تحديث السقوف من الحيّ ═══

    @Test
    fun f13_refreshStock_capsAgainstLiveProducts() {
        val cart = listOf(
            PosCartLine(7L, "شاي", 1_000L, 600L, 8.0, 10.0),          // مثبّت على قديم: 10  [P33-P8] قروش
            PosCartLine(null, "بيع حر", 2_000L, 0L, 1.0, 0.0),      // بلا منتج لا يُمس  [P33-P8] قروش
            PosCartLine(99L, "محذوف", 500L, 300L, 2.0, 4.0),         // غير موجود في الحيّ  [P33-P8] قروش
        )
        val stockOf: (Long) -> Double? = { pid -> if (pid == 7L) 3.0 else null }
        val refreshed = PosCart.refreshStock(cart, stockOf)
        assertEquals(3.0, refreshed[0].stock, 1e-9)
        assertEquals(3.0, refreshed[0].qty, 1e-9)     // الكمية انكمشت فوراً للسقف الجديد
        assertEquals(1.0, refreshed[1].qty, 1e-9)     // سطر البيع الحر لم يتغير
        assertEquals(4.0, refreshed[2].stock, 1e-9)   // منتج مفقود يحتفظ بحالته
    }

    // ═══ F8: lowStock يستبعد المؤرشف ═══

    @Test
    fun f8_lowStock_excludesArchivedProducts() = runBlocking {
        // [P33-P8] الأسعار قروش (5.0/10.0 ريال → 500/1000) — stockQty/reorderLevel كميات تبقى Double
        val pLow = Product(name = "منتج منخفض", unit = "قطعة", costPrice = 500L,
            salePrice = 1_000L, stockQty = 2.0, reorderLevel = 5.0, archived = false)
        val pArchived = Product(name = "مؤرشف منخفض", unit = "قطعة", costPrice = 500L,
            salePrice = 1_000L, stockQty = 1.0, reorderLevel = 5.0, archived = true)
        val pOk = Product(name = "سليم", unit = "قطعة", costPrice = 500L,
            salePrice = 1_000L, stockQty = 50.0, reorderLevel = 5.0, archived = false)
        db.products().upsert(pLow)
        db.products().upsert(pArchived)
        db.products().upsert(pOk)

        val low = inventory.lowStock()
        assertEquals(listOf("منتج منخفض"), low.map { it.name })   // المؤرشف خارج القائمة
    }

    // ═══ F2: مفاتيح فريدة حتى مع عدة سطور بلا منتج ═══

    @Test
    fun f2_cartKeys_uniqueWithMultipleFreeLines() {
        val cart = listOf(
            PosCartLine(null, "بيع حر — صيانة", 5_000L, 0L, 1.0, 0.0),   // [P33-P8] قروش
            PosCartLine(null, "بيع حر — توصيل", 1_500L, 0L, 1.0, 0.0),
            PosCartLine(7L, "شاي", 1_000L, 600L, 3.0, 10.0),
        )
        // نفس صيغة المفاتيح المستخدمة في PosScreen (R15-F2)
        val keys = cart.mapIndexed { idx, l -> l.productId?.let { "p$it" } ?: "f$idx" }
        assertEquals(keys.size, keys.toSet().size)
    }
}
