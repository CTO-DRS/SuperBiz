package com.superbiz.app.domain.portal

/**
 * بوابة العميل (لقطة): صفحة HTML واحدة مكتفية بذاتها — بلا سكربت ولا موارد خارجية — يفتحها العميل
 * في أي متصفح: اسمه، رصيده المستحق، وحركات حسابه برصيد جارٍ. تُشارك كملف من التطبيق (التطبيق يعمل
 * بلا إنترنت؛ البوابة الحية تحتاج Relay H4-3 لاحقاً). كل نص يمر عبر [esc].
 */
object CustomerPortalHtml {

    data class Row(val date: String, val memo: String, val debit: String, val credit: String, val running: String)

    data class Labels(
        val title: String, val balanceDue: String, val date: String, val memo: String,
        val debit: String, val credit: String, val balance: String, val footer: String
    )

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    fun build(
        business: String, customer: String, balanceText: String,
        rows: List<Row>, labels: Labels, rtl: Boolean
    ): String {
        val dir = if (rtl) "rtl" else "ltr"
        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"").append(if (rtl) "ar" else "en").append("\" dir=\"").append(dir).append("\"><head>")
        sb.append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(labels.title)).append(" - ").append(esc(customer)).append("</title><style>")
        sb.append("body{margin:0;font-family:system-ui,sans-serif;background:#0b0c24;color:#e2e8f0}")
        sb.append(".w{max-width:720px;margin:0 auto;padding:20px}")
        sb.append(".card{background:#161437;border:1px solid #3b3a7a;border-radius:18px;padding:18px;margin-bottom:14px}")
        sb.append("h1{font-size:20px;margin:0 0 4px}.sub{color:#a5b4fc;font-size:13px}")
        sb.append(".bal{font-size:28px;font-weight:700;margin-top:10px;color:#fff}")
        sb.append("table{width:100%;border-collapse:collapse;font-size:13px}")
        sb.append("th,td{padding:8px 6px;border-bottom:1px solid #2a2960;text-align:start}th{color:#a5b4fc}")
        sb.append("footer{color:#64748b;font-size:11px;text-align:center;margin-top:16px}")
        sb.append("</style></head><body><div class=\"w\"><div class=\"card\"><h1>").append(esc(business)).append("</h1>")
        sb.append("<div class=\"sub\">").append(esc(labels.title)).append(" &middot; ").append(esc(customer)).append("</div>")
        sb.append("<div class=\"sub\" style=\"margin-top:10px\">").append(esc(labels.balanceDue)).append("</div>")
        sb.append("<div class=\"bal\">").append(esc(balanceText)).append("</div></div>")
        sb.append("<div class=\"card\"><table><tr><th>").append(esc(labels.date)).append("</th><th>").append(esc(labels.memo))
        sb.append("</th><th>").append(esc(labels.debit)).append("</th><th>").append(esc(labels.credit))
        sb.append("</th><th>").append(esc(labels.balance)).append("</th></tr>")
        for (r in rows) {
            sb.append("<tr><td>").append(esc(r.date)).append("</td><td>").append(esc(r.memo)).append("</td><td>")
            sb.append(esc(r.debit)).append("</td><td>").append(esc(r.credit)).append("</td><td>").append(esc(r.running)).append("</td></tr>")
        }
        sb.append("</table></div><footer>").append(esc(labels.footer)).append("</footer></div></body></html>")
        return sb.toString()
    }
}
