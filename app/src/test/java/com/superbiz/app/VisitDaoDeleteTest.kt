package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Visit
import com.superbiz.app.data.repo.VisitsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P13-a] اختبارات حذف سجل الزيارة — قاعدة Room حقيقية في الذاكرة
 * (نمط TransactionPathsTest نفسه: Robolectric + AndroidJUnit4 + in-memory).
 *
 * يتحقق من العقد الجديد في VisitDao/VisitsRepo:
 * - deleteVisit يحذف الصف المستهدف وحده (لا تسرب لصفوف الطرف الآخر).
 * - حذف معرّف غير موجود عملية آمنة بلا أثر (SQL DELETE لا يرمي).
 * - المسار عبر المستودع (VisitsRepo.deleteVisit — المستخدم من VisitsSection) يترك
 *   كل القراءات الحية (all/allOnce/forParty) مصدرها القاعدة مباشرة فتُعيد الحالة
 *   الجديدة — التقرير في الواجهة متفاعل معها بلا أي refresh يدوي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VisitDaoDeleteTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteVisit_removesOnlyTargetRow() = runBlocking {
        val dao = db.visits()
        val id1 = dao.insert(Visit(partyId = 1, visitedAt = 1_000L, lat = 24.7, lng = 46.6))
        val id2 = dao.insert(Visit(partyId = 2, visitedAt = 2_000L))
        assertEquals(2, dao.allOnce().size)

        dao.deleteVisit(id1)

        // الصف المستهدف ذهب وحده، وصف الطرف الآخر باقٍ
        assertEquals(listOf(id2), dao.allOnce().map { it.id })
    }

    @Test
    fun deleteVisit_missingIdIsHarmlessNoOp() = runBlocking {
        val dao = db.visits()
        val id = dao.insert(Visit(partyId = 1, visitedAt = 1_000L))

        dao.deleteVisit(999L) // لا صف بهذا المعرّف — لا استثناء ولا تغيير

        assertEquals(listOf(id), dao.allOnce().map { it.id })
    }

    @Test
    fun deleteVisit_throughVisitsRepo_liveReadsReflectIt() = runBlocking {
        val repo = VisitsRepo(db)
        val pid = 7L
        // طوابع زمنية صريحة متمايزة — recordVisit يختم «الآن» وقد تسقط دعوتان في نفس
        // الميلي ثانية فتصبح «الأحدث أولاً» غير محددة الترتيب عند تعادل visitedAt
        db.visits().insert(Visit(partyId = pid, visitedAt = 1_000L, note = "تسجيل خاطئ"))
        db.visits().insert(Visit(partyId = pid, visitedAt = 2_000L, note = "تسجيل صحيح"))
        assertEquals(2, repo.forParty(pid).size)

        // الصف الأول (الأحدث: 2_000) هو «سجل الزيارة» الذي يحذفه زر الصف في VisitsSection
        val latest = repo.forParty(pid).first()
        repo.deleteVisit(latest.id)

        assertTrue(repo.forParty(pid).size == 1)
        assertEquals("تسجيل خاطئ", repo.forParty(pid).single().note)
        assertTrue(repo.allOnce().size == 1)
        // التدفق الحي (مصدر تقرير VisitsSection) يُقرأ من القاعدة مباشرة
        assertEquals(1, repo.all().first().size)
    }
}
