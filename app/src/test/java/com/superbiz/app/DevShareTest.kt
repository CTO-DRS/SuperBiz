package com.superbiz.app

import com.superbiz.app.ui.screens.settings.DEV_LINKS
import com.superbiz.app.ui.screens.settings.DevShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevShareTest {
    private val json = """{"banners":[
        {"title":{"ar":"أ","en":"A"},"body":{"en":"b"},"url":"https://x.test","from":"#112233","to":"zz"},
        {"title":{"ar":""},"url":"https://bad.test"},
        {"title":{"en":"C"},"url":"http://insecure.test"}]}"""

    @Test fun parsePicksLocaleFallsBackAndDropsBad() {
        val en = DevShare.parseBanners(json, "en")
        assertEquals(listOf("A", "C"), en.map { it.title })
        assertEquals("b", en[0].body)
        assertEquals(0xFF112233L, en[0].from)
        assertEquals(0xFF06B6D4L, en[0].to)
        assertEquals("", en[1].url)
        assertEquals("أ", DevShare.parseBanners(json, "ar")[0].title)
        assertTrue(DevShare.parseBanners("not json", "ar").isEmpty())
    }

    @Test fun messagesCarryLinks() {
        val m = DevShare.allAccountsMessage("H", DEV_LINKS.map { "n" to it.url })
        assertEquals(1 + 8, m.lines().size)
        assertTrue(m.contains("https://github.com/CTO-DRS"))
        assertTrue(DevShare.appMessage("hi ").endsWith("releases/latest"))
    }
}
