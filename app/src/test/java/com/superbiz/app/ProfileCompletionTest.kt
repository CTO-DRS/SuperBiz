package com.superbiz.app

import com.superbiz.app.domain.ProfileCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات حساب اكتمال الملف الشخصي */
class ProfileCompletionTest {

    private fun full() = ProfileCompletion.Input(
        businessName = "متجر النور",
        avatarSet = true,
        ownerName = "أحمد",
        phone = "0501234567",
        address = "الرياض",
        taxNumber = "3000123456",
        email = "a@b.com"
    )

    @Test
    fun emptyProfile_isZero() {
        assertEquals(0, ProfileCompletion.percent(ProfileCompletion.Input()))
    }

    @Test
    fun fullProfile_is100() {
        assertEquals(100, ProfileCompletion.percent(full()))
    }

    @Test
    fun businessOnly_weights25() {
        val v = ProfileCompletion.percent(ProfileCompletion.Input(businessName = "X"))
        assertEquals(ProfileCompletion.W_BUSINESS, v)
    }

    @Test
    fun avatarOnly_weights20() {
        val v = ProfileCompletion.percent(ProfileCompletion.Input(avatarSet = true))
        assertEquals(ProfileCompletion.W_AVATAR, v)
    }

    @Test
    fun weightsSumTo100() {
        assertEquals(
            100,
            ProfileCompletion.W_BUSINESS + ProfileCompletion.W_AVATAR + ProfileCompletion.W_OWNER +
                ProfileCompletion.W_PHONE + ProfileCompletion.W_ADDRESS +
                ProfileCompletion.W_TAX_NUMBER + ProfileCompletion.W_EMAIL
        )
    }

    @Test
    fun blankWhitespace_countsAsEmpty() {
        val v = ProfileCompletion.percent(ProfileCompletion.Input(businessName = "   "))
        assertEquals(0, v)
    }

    @Test
    fun missing_sortedByWeightDescending() {
        val m = ProfileCompletion.missing(ProfileCompletion.Input()) // كل شيء ناقص
        assertEquals(7, m.size)
        // الأثقل أولاً: business(25) ثم avatar(20)
        assertTrue(m[0] == 0 && m[1] == 1)
        // الأخف أخيراً: email(5)
        assertEquals(6, m.last())
    }

    @Test
    fun missing_emptyWhenComplete() {
        assertTrue(ProfileCompletion.missing(full()).isEmpty())
    }

    @Test
    fun partial_sumMatchesWeights() {
        val i = ProfileCompletion.Input(
            businessName = "B", ownerName = "O", phone = "P", email = "E"
        )
        assertEquals(
            ProfileCompletion.W_BUSINESS + ProfileCompletion.W_OWNER +
                ProfileCompletion.W_PHONE + ProfileCompletion.W_EMAIL,
            ProfileCompletion.percent(i)
        )
    }
}
