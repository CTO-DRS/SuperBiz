package com.superbiz.app

import com.superbiz.app.domain.portal.CustomerPortalHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerPortalHtmlTest {
    private val labels = CustomerPortalHtml.Labels("T", "Due", "D", "M", "Dr", "Cr", "B", "F")

    @Test fun escapesAndHasNoScript() {
        val html = CustomerPortalHtml.build(
            "<b>Shop</b>", "Ali \"x\" & <script>alert(1)</script>", "10.00",
            listOf(CustomerPortalHtml.Row("2026-01-01", "<img src=x onerror=1>", "1", "0", "1")), labels, true
        )
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("dir=\"rtl\""))
        assertTrue(html.contains("10.00"))
    }
}
