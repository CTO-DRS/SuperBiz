package com.superbiz.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.repo.BudgetStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BudgetStoreBackupTest {
    @Test
    fun `dump then restore replaces targets`() {
        val s = BudgetStore(ApplicationProvider.getApplicationContext())
        s.set(0L, "rent", 100_00L)
        s.set(2L, "fuel", 50_00L)
        val dumped = s.dump()
        s.set(0L, "rent", 0L)
        s.set(0L, "other", 9_00L)
        s.restore(dumped)
        assertEquals(mapOf("rent" to 100_00L), s.all(0L))
        assertEquals(mapOf("fuel" to 50_00L), s.all(2L))
    }
}
