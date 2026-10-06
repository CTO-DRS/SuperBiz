package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Currency
import com.superbiz.app.data.db.Rule

/**
 * (M-5.6 توحيد): بذر القيم الافتراضية (العملات + قواعد التذكير) مستخلَص في مكان واحد —
 * منقول من خط التدقيق ().
 *
 * كان البذر يعمل عند إقلاع التطبيق فقط (seedIfFirstRun) — بينما المسح في wipeAll()
 * يمحو جدولي rules وcurrencies تماماً، فبعد استعادة نسخة قديمة بلا هذين الجدولين
 * (أو جزئية المحتوى) يبقى التطبيق بلا عملات وقواعد حتى إعادة التشغيل البارد.
 * الآن يُستدعى ensure() من مسارَي الإقلاع وما بعد الاستعادة على السواء —
 * وهو آمن للتكرار (idempotent): لا يزرع إلا ما هو فارغ أصلاً.
*/
object SeedDefaults {

    suspend fun ensure(db: AppDatabase) {
        if (db.currencies().count() == 0) {
            db.currencies().upsertAll(defaultCurrencies())
        }
        if (db.rules().count() == 0) {
            db.rules().upsert(Rule(kind = "DUE_REMIND", enabled = true, daysBefore = 3))
            db.rules().upsert(Rule(kind = "CHECK_REMIND", enabled = true, daysBefore = 5))
            db.rules().upsert(Rule(kind = "LOW_STOCK", enabled = true, daysBefore = 0))
            db.rules().upsert(Rule(kind = "AUTO_BACKUP", enabled = true, daysBefore = 0))
        }
    }

    /** حزمة العملات الافتراضية — نفس قيم v1 الأولى حرفياً (الأسعار المرجعية عند التثبيت) */
    fun defaultCurrencies(): List<Currency> = listOf(
        Currency("SAR", "ريال سعودي", "Saudi Riyal", "ر.س", 1.0, true),
        Currency("USD", "دولار أمريكي", "US Dollar", "$", 0.2665, false),
        Currency("EUR", "يورو", "Euro", "€", 0.2453, false),
        Currency("AED", "درهم إماراتي", "UAE Dirham", "د.إ", 0.9785, false),
        Currency("EGP", "جنيه مصري", "Egyptian Pound", "ج.م", 12.95, false),
        Currency("YER", "ريال يمني", "Yemeni Rial", "ر.ي", 664.5, false)
    )
}
