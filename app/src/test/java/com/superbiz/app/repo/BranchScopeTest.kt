package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.CurrentBranch
import com.superbiz.app.data.repo.TransferRepo
import com.superbiz.app.data.repo.mirrorToActiveBranch
import com.superbiz.app.data.repo.resolveBranchId
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [A4] نطاق الفرع: الفرع الحالي يحكم الختم والمرآة، والتقارير تُفصل بالفرع */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BranchScopeTest {
    private lateinit var db: AppDatabase
    private var a = 0L
    private var b = 0L
    private var pid = 0L

    @Before
    fun setUp() = runBlocking {
        SessionState.clear()
        CurrentBranch.id = 0L
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val t = TransferRepo(db)
        a = t.createBranch("أ", kind = 0, today = 1L)
        b = t.createBranch("ب", kind = 0, today = 1L)
        pid = db.products().upsert(Product(name = "س", sku = "S1", stockQty = 0.0, costPrice = 100))
        Unit
    }

    @After
    fun tearDown() { CurrentBranch.id = 0L; SessionState.clear(); db.close() }

    @Test
    fun resolveFallsBackToFirstActiveThenFollowsSelection() = runBlocking {
        assertEquals(a, db.resolveBranchId())
        CurrentBranch.id = b
        assertEquals(b, db.resolveBranchId())
        db.branches().setActive(b, false)
        assertEquals(a, db.resolveBranchId())
        Unit
    }

    @Test
    fun mirrorAndReportsFollowCurrentBranch() = runBlocking {
        CurrentBranch.id = b
        db.mirrorToActiveBranch(pid, 5.0)
        assertEquals(5.0, db.branchStock().qtyAt(b, pid) ?: 0.0, 0.0)
        assertEquals(0.0, db.branchStock().qtyAt(a, pid) ?: 0.0, 0.0)
        assertEquals(500L, Math.round(db.branches().stockValue(b)))
        db.expenses().insert(Expense(amount = 70, category = "x", note = "", date = 1L, branchId = b))
        assertEquals(70L, db.branches().expensesOf(b))
        assertEquals(0L, db.branches().expensesOf(a))
        Unit
    }
}
