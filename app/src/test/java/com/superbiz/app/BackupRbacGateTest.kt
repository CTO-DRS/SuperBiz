package com.superbiz.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.repo.BackupRepo
import com.superbiz.app.data.repo.BackupRestoreRepo
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * [RVW-12 V 3.2.3] بوابة RBAC طبقة البيانات على مساري النسخ والاستعادة —
 * الگمّة الجوهرية (BACKUP_RESTORE للمالك وحده في مصفوفة الأدوار) كمّا دفاع عميق:
 *
 * ① جلسة صريحة بغير المالك (كاشير/محاسب/مدير) تُرفض على المسارين (دمجي واستبدالي)
 *    حتى لو وصلت من مسار مستقبلي تجاوز بوابات VM — الرفض ظاهر (استثناء/Result.failure).
 * ② عقد الوضع الأحادي §4.3 محفوظ حرفياً: بلا جلسة ووضع أحادي ⇒ مالك ضمني ⇒ يمر.
 * ③ عقد الأتمتة محفوظ حرفياً: بلا جلسة في وضع التعدد ⇒ SYSTEM (OWNER) ⇒ يمر —
 *    النسخ التلقائي الأسبوعي (BackupWorker) لا يتأثر إطلاقاً.
 * ④ جلسة مالك صريحة تمر طبعاً.
 *
 * نمط الاختبار من BackupZatcaAuditTest حرفياً: Room حقيقي في الذاكرة (لا mocks).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupRbacGateTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context
    private lateinit var full: BackupRepo
    private lateinit var merge: BackupRestoreRepo

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        full = BackupRepo(ctx, db, SettingsRepo(ctx))
        merge = BackupRestoreRepo(db, ctx)
        // حالة نظيفة قبل كل اختبار — لا تسريب من اختبارات أخرى
        SessionState.clear()
        SessionState.multiUserMode = false
    }

    @After
    fun tearDown() {
        // إعادة الحالة العامة إلى العقد الافتراضي — لا تلويث لاختبارات لاحقة
        SessionState.clear()
        SessionState.multiUserMode = false
        db.close()
    }

    private fun tempUri(name: String): Uri = Uri.fromFile(File(ctx.cacheDir, name))

    @Test
    fun `non-owner explicit session is denied on both paths - visible failure`() = runBlocking {
        SessionState.multiUserMode = true
        SessionState.start(2L, "cashier", Role.CASHIER)

        // المسار الدمجي — Result.failure بسببه RoleDeniedException (فشل ظاهر لا صامت)
        val mergeResult = merge.exportTo(tempUri("rbac_merge.json"))
        assertTrue(mergeResult.isFailure)
        assertTrue(mergeResult.exceptionOrNull() is RoleDeniedException)

        // المسار الاستبدالي — الاستثناء يُرمى قبل try (لا false صامتة)
        var denied = false
        try {
            full.exportTo(tempUri("rbac_full.json"))
        } catch (e: RoleDeniedException) {
            denied = true
        }
        assertTrue(denied)

        // والاستعادة أعلى خطراً — مرفوضة على المسارين كذلك
        assertTrue(merge.importFrom(tempUri("rbac_missing.json")).exceptionOrNull() is RoleDeniedException)
        denied = false
        try {
            full.importFrom(tempUri("rbac_missing.json"))
        } catch (e: RoleDeniedException) {
            denied = true
        }
        assertTrue(denied)
    }

    @Test
    fun `owner explicit session passes on both paths`() = runBlocking {
        SessionState.multiUserMode = true
        SessionState.start(0L, "owner", Role.OWNER)
        val out = tempUri("rbac_owner.json")
        assertTrue(full.exportTo(out))
        assertTrue(merge.exportTo(tempUri("rbac_owner_merge.json")).isSuccess)
    }

    @Test
    fun `implicit owner contract (single-user no session) passes - design 4-3`() = runBlocking {
        SessionState.clear()
        SessionState.multiUserMode = false
        assertTrue(full.exportTo(tempUri("rbac_implicit.json")))
        assertTrue(merge.exportTo(tempUri("rbac_implicit_merge.json")).isSuccess)
    }

    @Test
    fun `SYSTEM identity passes in multi-user no-session - automation contract preserved`() = runBlocking {
        SessionState.clear()
        SessionState.multiUserMode = true
        assertTrue(full.exportTo(tempUri("rbac_system.json")))
        assertTrue(merge.exportTo(tempUri("rbac_system_merge.json")).isSuccess)
        assertTrue(full.exportLocal().exists())
    }
}
