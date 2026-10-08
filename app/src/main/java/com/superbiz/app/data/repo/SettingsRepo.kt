package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "superbiz_settings")

/**
 * [H1-3][v13] بذرة رمز المالك للترحيل — مادة الدخول القائمة تُنسخ نصاً إلى
 * user_secrets داخل ترحيل 12→13 (نسخ مغلّف ks: لا إعادة تشفير — عقد التصميم).
 */
data class OwnerPinSeed(
    val pinWrapped: String,
    val pinSalt: String,
    val pinIters: Int,
    val biometric: Boolean
)

data class Settings(
    val businessName: String = "",
    val avatarPath: String? = null,
    val language: String = "ar",       // ar / en
    val theme: String = "dark",        // dark / light / auto
    val baseCurrency: String = "SAR",
    val taxRate: Double = 15.0,
    val pinHash: String? = null,        // - بصمة صريحة (مفقودات قديمة فقط — تُرقّى تلقائياً)
    val pinSalt: String? = null,
    val pinBlob: String? = null,        // بصمة مغلّفة بمفتاح Keystore — لا تحقق خارج الجهاز
    val pinIters: Int = 0,              // دورات PBKDF2 المخزنة (0 = مفقودات قديمة 60k)
    val pinLength: Int = 0,             // طول الرمز المخزَّن عند التعيين (0 = مفقودات قديمة) — شاشة القفل تُرسل عند بلوغه بالضبط فلا تُحتسب محاولات فاشلة لرمز ناقص
    val backupDirUri: String? = null,
    val lastAutoBackup: Long = 0,
    val walkInPartyId: Long = 0,       // طرف «زبون نقدي» لنقطة البيع
    val seeded: Boolean = false,
    // ملف شخصي متكامل + بيومتريا + شاشة ترحيب
    val ownerName: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val taxNumber: String = "",
    val biometric: Boolean = false,     // فتح بالبصمة/الوجه
    val welcomeSeen: Boolean = false,   // هل عُرضت شاشة الترحيب التفاعلية؟
    val permissionsSeen: Boolean = false, // هل عُرضت شاشة الأذونات وإمكانية الوصول؟
    val autoBackupDays: Int = 7,         // تكرار النسخ الاحتياطي التلقائي (0 = إيقاف)
    // [P36-BK] تضمين ملفات PDFs الكشوف في مرآة النسخ الاحتياطي (SAF) — 0 افتراضياً للتوفير
    val backupIncludePdfs: Boolean = false,
    // جدولة إرسال التقرير A4 تلقائياً (واتساب/بريد)
    val reportScheduleDays: Int = 0,     // 0 = إيقاف، 1 = يومي، 7 = أسبوعي
    val reportScheduleHour: Int = 20,    // ساعة التجهيز (0..23) بتوقيت الجهاز
    val reportScheduleChannel: String = "whatsapp", // whatsapp / email
    val lastScheduledReport: Long = 0,   // آخر موعد جُهِّز فيه التقرير المجدول
    val reportRecipient: String = "",    // المستلم الافتراضي للتقرير (بريد محفوظ) — : كانت الفاصلة مفقودة = خطأ ترجمة
    val monthlyGoal: Double = 0.0,       // هدف المبيعات الشهري (0 = معطّل)
    val redactWidgets: Boolean = false,  // إخفاء المبالغ في ويدجت الشاشة الرئيسية
    // قسم الإعدادات المركزي — كل مفتاح يُحفظ في DataStore ويُطبّق فعلياً في موضع استهلاكه
    val hapticsEnabled: Boolean = true,      // سلوك: اهتزاز تأكيد للعمليات المالية
    val confirmDestructive: Boolean = true,  // سلوك: تأكيد قبل الإجراءات المدمّرة
    val flagSecure: Boolean = true,          // خصوصية: منع لقطات الشاشة
    val lockTimeoutMin: Int = 0,             // خصوصية: مهلة إعادة القفل عند الخلفية (0 = فوري)
    val fontScale: Float = 1.0f,             // مظهر: تكبير الخط (0.85..1.30)
    val dynamicColors: Boolean = false,      // مظهر: ألوان النظام الديناميكية (Android 12+)
    val mirrorChartsRtl: Boolean = true,     // مظهر: عكس الرسوم البيانية في العربي
    val animationsEnabled: Boolean = true,   // أداء: تفعيل الحركات والتمرير الباطني
    val arabicReceiptMode: Int = 0,          // متقدم: طباعة عربية 0=CP1256 / 1=UTF-8
    val defaultLowStockQty: Int = 5,         // متقدم: حدّ المخزون المنخفض الافتراضي
    val lowStockAlerts: Boolean = true,      // إشعارات: تنبيه المخزون المنخفض
    val receivableAlerts: Boolean = true,    // إشعارات: تنبيه الذمم المتأخرة
    // مفاتيح التحليلات الذكية — لكل مفتاح مستهلك حقيقي
    val defaultTargetMargin: Double = 30.0,  // مخزون: الهامش المستهدف الافتراضي لمستشار التسعير
    val lateFeeDailyPct: Double = 0.0,       // أقساط: نسبة غرامة التأخير اليومية (0 = معطّل)
    val lateFeeCapPct: Double = 10.0,        // أقساط: سقف الغرامة كنسبة من الأصل
    val weekendFriSat: Boolean = true,       // تقويم: عطلة الجمعة+السبت لأيام العمل (غيرها أحد فقط)
    // مفاتيح المرحلة الثالثة — لكل مفتاح مستهلك حقيقي موثّق
    val searchFuzzyThreshold: Double = 0.45, // بحث: حدّ قبول التطابق الضبابي (أقل = نتائج أوسع)
    val eoqOrderCost: Double = 25.0,         // مخزون: كلفة أمر الشراء في معادلة EOQ
    val posHeldCart: String? = null,         // نقطة بيع: سلة معلّقة JSON (productId,qty,name,price,cost,discount,payMode,partyId)
    val recentSearches: String? = null,      // بحث: آخر 8 استعلامات JSON — إعادة سريعة بنقرة
    // مفاتيح الموجة الرابعة (النصف الأول) — لكل مفتاح مستهلك حقيقي موثّق
    val expenseMonthlyLimit: Double = 0.0,   // مصروفات: حد إنذار شهري (0 = بلا حد — الإخفاء صادق)
    val privacyBlur: Boolean = false,        // خصوصية (وظيفة 38): طمس مبالغ بطاقات الرئيسية كـ «•••»
    // [P17-a]: هوية الشركة على كشف الحساب PDF — كلها String فارغ = غير مضبوط
    val crNumber: String = "",               // السجل التجاري للمكشوف على الكشف
    val city: String = "",                   // المدينة (تغذية عنوان الكشف)
    val country: String = "",                // الدولة
    val website: String = "",                // الموقع الإلكتروني
    val companyPhotoPath: String = "",       // صورة الشركة/السجل — "" = لا صورة (قرار العقد: String افتراض فارغ لكل مفاتيح الموجة الستة لا nullable)
    val statementDefaultNote: String = "",   // الملاحظة الافتراضية المعبأة في كشف جديد
    // [P44-K1] جولة 5: تخطيط بطاقات الرؤى المخصص (JSON محرك DashboardPrefsP44) — null = الافتراضي
    val dashboardLayout: String? = null,
    // [P46-W1] جولة 7: نقاط الولاء والكوبونات — المعطّل افتراضياً (قرار منتج صريح)
    val loyaltyEnabled: Boolean = false,
    val loyaltyEarnDivisor: Long = 1000L,   // قروش صافية لكل نقطة (1000 = كل 10 ريال نقطة)
    val loyaltyPointValue: Long = 10L       // قروش قيمة النقطة عند الاستبدال (10 = جوهر 1%)
)

class SettingsRepo(private val context: Context) {

    private object K {
        val business = stringPreferencesKey("business_name")
        val avatar = stringPreferencesKey("avatar_path")
        val lang = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val cur = stringPreferencesKey("base_currency")
        val tax = doublePreferencesKey("tax_rate")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val pinBlob = stringPreferencesKey("pin_blob")
        val pinIters = intPreferencesKey("pin_iters")
        val pinLength = intPreferencesKey("pin_length")   // طول الرمز عند التعيين
        val redactWidgets = booleanPreferencesKey("redact_widgets")
        val backupDir = stringPreferencesKey("backup_dir_uri")
        val lastBackup = longPreferencesKey("last_auto_backup")
        val walkIn = longPreferencesKey("walk_in_party_id")
        val seeded = booleanPreferencesKey("seeded")
        val owner = stringPreferencesKey("owner_name")
        val phone = stringPreferencesKey("profile_phone")
        val email = stringPreferencesKey("profile_email")
        val address = stringPreferencesKey("profile_address")
        val taxNum = stringPreferencesKey("profile_tax_number")
        val biometric = booleanPreferencesKey("biometric_enabled")
        val welcomeSeen = booleanPreferencesKey("welcome_seen")
        val permsSeen = booleanPreferencesKey("permissions_seen")
        val autoBackup = intPreferencesKey("auto_backup_days")
        // [P36-BK] تضمين PDFs الكشوف في مرآة النسخ
        val backupPdfs = booleanPreferencesKey("backup_include_pdfs")
        val rptDays = intPreferencesKey("report_schedule_days")
        val rptHour = intPreferencesKey("report_schedule_hour")
        val rptChannel = stringPreferencesKey("report_schedule_channel")
        val rptLast = longPreferencesKey("last_scheduled_report")
        val rptRecipient = stringPreferencesKey("report_recipient")
        val monthlyGoal = doublePreferencesKey("monthly_sales_goal")
        // مفاتيح الإعدادات المركزية الجديدة
        val haptics = booleanPreferencesKey("haptics_enabled")
        val confirmDestructive = booleanPreferencesKey("confirm_destructive")
        val flagSecure = booleanPreferencesKey("flag_secure")
        val lockTimeoutMin = intPreferencesKey("lock_timeout_min")
        val fontScale = floatPreferencesKey("font_scale")
        val dynamicColors = booleanPreferencesKey("dynamic_colors")
        val mirrorChartsRtl = booleanPreferencesKey("mirror_charts_rtl")
        val animations = booleanPreferencesKey("animations_enabled")
        val arabicReceiptMode = intPreferencesKey("arabic_receipt_mode")
        val defaultLowStockQty = intPreferencesKey("default_low_stock_qty")
        val lowStockAlerts = booleanPreferencesKey("low_stock_alerts")
        val receivableAlerts = booleanPreferencesKey("receivable_alerts")
        val targetMargin = doublePreferencesKey("default_target_margin")
        val lateFeeDaily = doublePreferencesKey("late_fee_daily_pct")
        val lateFeeCap = doublePreferencesKey("late_fee_cap_pct")
        val weekendFriSat = booleanPreferencesKey("weekend_fri_sat")
        val searchFuzzy = doublePreferencesKey("search_fuzzy_threshold")
        val eoqOrderCost = doublePreferencesKey("eoq_order_cost")
        val posHeldCart = stringPreferencesKey("pos_held_cart")
        val recentSearches = stringPreferencesKey("recent_searches")
        val expLimit = doublePreferencesKey("expense_monthly_limit")
        val privacyBlur = booleanPreferencesKey("privacy_blur")   // وظيفة 38: طمس مبالغ الرئيسية
        // [P17-a]: هوية الشركة على كشف الحساب
        val crNumber = stringPreferencesKey("company_cr_number")
        val city = stringPreferencesKey("company_city")
        val country = stringPreferencesKey("company_country")
        val website = stringPreferencesKey("company_website")
        val companyPhoto = stringPreferencesKey("company_photo_path")
        val statementNote = stringPreferencesKey("statement_default_note")
        // [P44-K1] جولة 5: تخطيط بطاقات الرؤى
        val dashboardLayout = stringPreferencesKey("dashboard_layout")
        // [P46-W1] جولة 7: الولاء والكوبونات
        val loyaltyEnabled = booleanPreferencesKey("loyalty_enabled")
        val loyaltyEarnDivisor = longPreferencesKey("loyalty_earn_divisor")
        val loyaltyPointValue = longPreferencesKey("loyalty_point_value")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            businessName = p[K.business] ?: "",
            avatarPath = p[K.avatar],
            language = p[K.lang] ?: "ar",
            theme = p[K.theme] ?: "dark",
            baseCurrency = p[K.cur] ?: "SAR",
            taxRate = p[K.tax] ?: 15.0,
            pinHash = p[K.pinHash],
            pinSalt = p[K.pinSalt],
            pinBlob = p[K.pinBlob],
            pinIters = p[K.pinIters] ?: 0,
            pinLength = p[K.pinLength] ?: 0,
            redactWidgets = p[K.redactWidgets] ?: false,
            backupDirUri = p[K.backupDir],
            lastAutoBackup = p[K.lastBackup] ?: 0L,
            walkInPartyId = p[K.walkIn] ?: 0L,
            seeded = p[K.seeded] ?: false,
            ownerName = p[K.owner] ?: "",
            phone = p[K.phone] ?: "",
            email = p[K.email] ?: "",
            address = p[K.address] ?: "",
            taxNumber = p[K.taxNum] ?: "",
            biometric = p[K.biometric] ?: false,
            welcomeSeen = p[K.welcomeSeen] ?: false,
            permissionsSeen = p[K.permsSeen] ?: false,
            autoBackupDays = p[K.autoBackup] ?: 7,
            backupIncludePdfs = p[K.backupPdfs] ?: false,
            reportScheduleDays = p[K.rptDays] ?: 0,
            reportScheduleHour = p[K.rptHour] ?: 20,
            reportScheduleChannel = p[K.rptChannel] ?: "whatsapp",
            lastScheduledReport = p[K.rptLast] ?: 0L,
            reportRecipient = p[K.rptRecipient] ?: "",
            monthlyGoal = p[K.monthlyGoal] ?: 0.0,
            // قراءة المفاتيح الجديدة بقيم افتراضية آمنة
            hapticsEnabled = p[K.haptics] ?: true,
            confirmDestructive = p[K.confirmDestructive] ?: true,
            flagSecure = p[K.flagSecure] ?: true,
            lockTimeoutMin = p[K.lockTimeoutMin] ?: 0,
            fontScale = p[K.fontScale] ?: 1.0f,
            dynamicColors = p[K.dynamicColors] ?: false,
            mirrorChartsRtl = p[K.mirrorChartsRtl] ?: true,
            animationsEnabled = p[K.animations] ?: true,
            arabicReceiptMode = p[K.arabicReceiptMode] ?: 0,
            defaultLowStockQty = p[K.defaultLowStockQty] ?: 5,
            lowStockAlerts = p[K.lowStockAlerts] ?: true,
            receivableAlerts = p[K.receivableAlerts] ?: true,
            defaultTargetMargin = p[K.targetMargin] ?: 30.0,
            lateFeeDailyPct = p[K.lateFeeDaily] ?: 0.0,
            lateFeeCapPct = p[K.lateFeeCap] ?: 10.0,
            weekendFriSat = p[K.weekendFriSat] ?: true,
            searchFuzzyThreshold = p[K.searchFuzzy] ?: 0.45,
            eoqOrderCost = p[K.eoqOrderCost] ?: 25.0,
            posHeldCart = p[K.posHeldCart],
            recentSearches = p[K.recentSearches],
            // قراءة حد المصروف بقيمة افتراضية آمنة
            expenseMonthlyLimit = p[K.expLimit] ?: 0.0,
            // وضع الخصوصية — طمس مبالغ الرئيسية (وظيفة 38)
            privacyBlur = p[K.privacyBlur] ?: false,
            // [P17-a]: هوية الشركة على كشف الحساب — افتراضات فارغة آمنة
            crNumber = p[K.crNumber] ?: "",
            city = p[K.city] ?: "",
            country = p[K.country] ?: "",
            website = p[K.website] ?: "",
            companyPhotoPath = p[K.companyPhoto] ?: "",
            statementDefaultNote = p[K.statementNote] ?: "",
            // [P44-K1] جولة 5: تخطيط بطاقات الرؤى — افتراض null آمن
            dashboardLayout = p[K.dashboardLayout],
            // [P46-W1] جولة 7: الولاء — افتراضات آمنة (معطّل + عقد الجوهر 1%)
            loyaltyEnabled = p[K.loyaltyEnabled] ?: false,
            loyaltyEarnDivisor = p[K.loyaltyEarnDivisor] ?: 1000L,
            loyaltyPointValue = p[K.loyaltyPointValue] ?: 10L
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    suspend fun setBusinessName(v: String) = context.dataStore.edit { it[K.business] = v }
    suspend fun setAvatar(path: String?) = context.dataStore.edit {
        if (path == null) it.remove(K.avatar) else it[K.avatar] = path
    }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[K.lang] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.theme] = v }
    suspend fun setBaseCurrency(code: String) = context.dataStore.edit { it[K.cur] = code }
    suspend fun setTaxRate(v: Double) = context.dataStore.edit {
        // تثبيت النسبة في 0..100 ورفض NaN — كان يُخزَّن كما هو
        // فتُفسد نسبة سالبة أو هائلة حسابات الضريبة في كل الفواتير الجديدة
        val safe = if (!v.isFinite()) 0.0 else v.coerceIn(0.0, 100.0)
        it[K.tax] = safe
    }
    suspend fun setPin(hash: String?, salt: String?) = context.dataStore.edit {
        if (hash == null || salt == null) { it.remove(K.pinHash); it.remove(K.pinSalt) }
        else { it[K.pinHash] = hash; it[K.pinSalt] = salt }
    }

    /**الصيغة الجديدة — ملوح + بصمة مغلّفة بـ Keystore + عدد الدورات، مع محو المفقودات القديمة */
    /**يُخزَّن أيضاً طول الرمز ليعرف شاشة القفل الطول المستهدف بدقة */
    suspend fun setPinSecured(salt: String, blob: String, iters: Int, pinLength: Int = 0) = context.dataStore.edit {
        it[K.pinSalt] = salt
        it[K.pinBlob] = blob
        it[K.pinIters] = iters
        if (pinLength in 6..8) it[K.pinLength] = pinLength else it.remove(K.pinLength)
        it.remove(K.pinHash)
    }

    /**إزالة كل مادة الرمز (بأي صيغة) */
    suspend fun clearPin() = context.dataStore.edit {
        it.remove(K.pinHash); it.remove(K.pinSalt); it.remove(K.pinBlob); it.remove(K.pinIters)
        it.remove(K.pinLength)
    }

    /**إخفاء المبالغ في الويدجت (خصوصية) */
    suspend fun setRedactWidgets(v: Boolean) = context.dataStore.edit { it[K.redactWidgets] = v }
    suspend fun setBackupDir(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(K.backupDir) else it[K.backupDir] = uri
    }
    suspend fun setLastAutoBackup(ts: Long) = context.dataStore.edit { it[K.lastBackup] = ts }
    suspend fun setWalkInPartyId(id: Long) = context.dataStore.edit { it[K.walkIn] = id }
    suspend fun setSeeded() = context.dataStore.edit { it[K.seeded] = true }

    // الملف الشخصي المتكامل
    suspend fun setOwnerName(v: String) = context.dataStore.edit { it[K.owner] = v }
    suspend fun setPhone(v: String) = context.dataStore.edit { it[K.phone] = v }
    suspend fun setEmail(v: String) = context.dataStore.edit { it[K.email] = v }
    suspend fun setAddress(v: String) = context.dataStore.edit { it[K.address] = v }
    suspend fun setTaxNumber(v: String) = context.dataStore.edit { it[K.taxNum] = v }
    suspend fun setBiometric(v: Boolean) = context.dataStore.edit { it[K.biometric] = v }
    suspend fun setWelcomeSeen() = context.dataStore.edit { it[K.welcomeSeen] = true }
    suspend fun setPermissionsSeen() = context.dataStore.edit { it[K.permsSeen] = true }
    suspend fun setAutoBackupDays(v: Int) = context.dataStore.edit { it[K.autoBackup] = v.coerceIn(0, 365) }

    /** [P36-BK] تضمين ملفات PDFs الكشوف في مرآة النسخ الاحتياطي */
    suspend fun setBackupIncludePdfs(v: Boolean) = context.dataStore.edit { it[K.backupPdfs] = v }

    // جدولة إرسال التقرير A4
    suspend fun setReportScheduleDays(v: Int) = context.dataStore.edit { it[K.rptDays] = v.coerceIn(0, 365) }
    suspend fun setReportScheduleHour(v: Int) = context.dataStore.edit { it[K.rptHour] = v.coerceIn(0, 23) }
    suspend fun setReportScheduleChannel(v: String) = context.dataStore.edit {
        it[K.rptChannel] = if (v == "email") "email" else "whatsapp"
    }
    suspend fun setLastScheduledReport(ts: Long) = context.dataStore.edit { it[K.rptLast] = ts }

    // المستلم الافتراضي لتقرير البريد المجدول
    suspend fun setReportRecipient(v: String) = context.dataStore.edit {
        val t = v.trim()
        if (t.isEmpty()) it.remove(K.rptRecipient) else it[K.rptRecipient] = t
    }

    // هدف المبيعات الشهري
    suspend fun setMonthlyGoal(v: Double) = context.dataStore.edit {
        // Infinity كان يمرّ — نسخة نمط setExpenseMonthlyLimit
        val g = if (!v.isFinite() || v < 0.0) 0.0 else v
        if (g <= 0.0) it.remove(K.monthlyGoal) else it[K.monthlyGoal] = g
    }

    // مُحدِّثات الإعدادات المركزية — كل واحد يغذّي مستهلكاً حقيقياً
    suspend fun setHapticsEnabled(v: Boolean) = context.dataStore.edit { it[K.haptics] = v }
    suspend fun setConfirmDestructive(v: Boolean) = context.dataStore.edit { it[K.confirmDestructive] = v }
    suspend fun setFlagSecure(v: Boolean) = context.dataStore.edit { it[K.flagSecure] = v }
    suspend fun setLockTimeoutMin(v: Int) = context.dataStore.edit { it[K.lockTimeoutMin] = v.coerceIn(0, 60) }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[K.fontScale] = v.coerceIn(0.85f, 1.30f) }
    suspend fun setDynamicColors(v: Boolean) = context.dataStore.edit { it[K.dynamicColors] = v }
    suspend fun setMirrorChartsRtl(v: Boolean) = context.dataStore.edit { it[K.mirrorChartsRtl] = v }
    suspend fun setAnimationsEnabled(v: Boolean) = context.dataStore.edit { it[K.animations] = v }
    suspend fun setArabicReceiptMode(v: Int) = context.dataStore.edit { it[K.arabicReceiptMode] = if (v == 1) 1 else 0 }
    suspend fun setDefaultLowStockQty(v: Int) = context.dataStore.edit { it[K.defaultLowStockQty] = v.coerceIn(0, 9999) }
    suspend fun setLowStockAlerts(v: Boolean) = context.dataStore.edit { it[K.lowStockAlerts] = v }
    suspend fun setReceivableAlerts(v: Boolean) = context.dataStore.edit { it[K.receivableAlerts] = v }

    // مُحدِّثات التحليلات الذكية
    suspend fun setDefaultTargetMargin(v: Double) = context.dataStore.edit {
        val m = if (v.isNaN()) 30.0 else v
        it[K.targetMargin] = m.coerceIn(0.0, 90.0)
    }
    suspend fun setLateFeeDailyPct(v: Double) = context.dataStore.edit {
        it[K.lateFeeDaily] = (if (v.isNaN()) 0.0 else v).coerceIn(0.0, 5.0)
    }
    suspend fun setLateFeeCapPct(v: Double) = context.dataStore.edit {
        it[K.lateFeeCap] = (if (v.isNaN()) 10.0 else v).coerceIn(0.0, 100.0)
    }
    suspend fun setWeekendFriSat(v: Boolean) = context.dataStore.edit { it[K.weekendFriSat] = v }

    // ═══ : مُحدِّثات المرحلة الثالثة ═══
    suspend fun setSearchFuzzyThreshold(v: Double) = context.dataStore.edit {
        it[K.searchFuzzy] = (if (v.isNaN()) 0.45 else v).coerceIn(0.2, 0.8)
    }
    suspend fun setEoqOrderCost(v: Double) = context.dataStore.edit {
        it[K.eoqOrderCost] = (if (v.isNaN()) 25.0 else v).coerceIn(0.0, 10000.0)
    }
    suspend fun setPosHeldCart(json: String?) = context.dataStore.edit {
        if (json.isNullOrBlank()) it.remove(K.posHeldCart) else it[K.posHeldCart] = json
    }

    /** آخر 8 استعلامات بحث — تُستبدل المكررة وتُرفع للأمام (JSON بسيط عبر org.json المدمجة) */
    suspend fun addRecentSearch(term: String) = context.dataStore.edit {
        val t = term.trim()
        if (t.isEmpty()) return@edit
        val arr = try { org.json.JSONArray(it[K.recentSearches] ?: "[]") } catch (e: Exception) { org.json.JSONArray() }
        val out = org.json.JSONArray()
        out.put(t)
        for (i in 0 until arr.length()) {
            val s = try { arr.optString(i) } catch (e: Exception) { "" }
            if (s.isNotEmpty() && s != t && out.length() < 8) out.put(s)
        }
        it[K.recentSearches] = out.toString()
    }
    suspend fun clearRecentSearches() = context.dataStore.edit { it.remove(K.recentSearches) }

    // ═══ : مُحدِّث الموجة الرابعة (النصف الأول) ═══
    /** حد المصروف الشهري — ≤0 أو غير سالم = مسح (بلا حد، الإخفاء صادق) */
    suspend fun setExpenseMonthlyLimit(v: Double) = context.dataStore.edit {
        val x = if (v.isNaN() || v.isInfinite()) 0.0 else v
        if (x <= 0.0) it.remove(K.expLimit) else it[K.expLimit] = x
    }

    /** وظيفة 38: وضع الخصوصية — طمس مبالغ بطاقات الرئيسية مع كشف مؤقت بنقرة طويلة */
    suspend fun setPrivacyBlur(v: Boolean) = context.dataStore.edit { it[K.privacyBlur] = v }

    // ═══ [P17-a]: مُحدِّثات هوية الشركة على كشف الحساب PDF ═══
    suspend fun setCrNumber(v: String) = context.dataStore.edit { it[K.crNumber] = v.trim() }
    suspend fun setCity(v: String) = context.dataStore.edit { it[K.city] = v.trim() }
    suspend fun setCountry(v: String) = context.dataStore.edit { it[K.country] = v.trim() }
    suspend fun setWebsite(v: String) = context.dataStore.edit { it[K.website] = v.trim() }
    /** صورة الشركة/السجل — نص فارغ يمسح المفتاح (لا مسار يشغل مكان الفارغ أبداً) */
    suspend fun setCompanyPhotoPath(path: String?) = context.dataStore.edit {
        val t = path?.trim().orEmpty()
        if (t.isEmpty()) it.remove(K.companyPhoto) else it[K.companyPhoto] = t
    }
    /** الملاحظة الافتراضية لكشف جديد — النص الفارغ يمسح المفتاح */
    suspend fun setStatementDefaultNote(v: String) = context.dataStore.edit {
        val t = v.trim()
        if (t.isEmpty()) it.remove(K.statementNote) else it[K.statementNote] = t
    }

    /** [P44-K1] جولة 5: تخطيط بطاقات الرؤى — الفارغ/الأبيض يمسح المفتاح (الافتراضي) */
    suspend fun setDashboardLayout(json: String?) = context.dataStore.edit {
        val t = json?.trim().orEmpty()
        if (t.isEmpty()) it.remove(K.dashboardLayout) else it[K.dashboardLayout] = t
    }

    // ═══ [P46-W1] جولة 7: مُحدّثات الولاء والكوبونات ═══
    suspend fun setLoyaltyEnabled(v: Boolean) = context.dataStore.edit { it[K.loyaltyEnabled] = v }

    /** قروش صافية لكل نقطة كسباً — حد أدنى 1 (كل قروش نقطة) وسقف حماية من الإعداد الفاسد */
    suspend fun setLoyaltyEarnDivisor(v: Long) = context.dataStore.edit {
        it[K.loyaltyEarnDivisor] = v.coerceIn(1L, 10_000_000L)
    }

    /** قروش قيمة النقطة عند الاستبدال — ≥ 1 بعقد fail-closed (صفر = بلا استبدال) */
    suspend fun setLoyaltyPointValue(v: Long) = context.dataStore.edit {
        it[K.loyaltyPointValue] = v.coerceIn(1L, 1_000_000L)
    }

    // ═══ [H1-3][v13] بذرة المالك للترحيل ═══

    /**
     * قراءة متزامنة لمادة رمز المالك القائمة — تُستدعى حصراً من داخل
     * MIGRATION_12_13 (خيط تنفيذ Room الخلفي) لنقل مادة الدخول إلى
     * user_secrets بنسخ نص للمغلّف ks: لا إعادة تشفير ولا مسّ Keystore.
     *
     * - تعيد null إن لم توجد حماية قائمة (المالك يُزرع بلا سر — أول دخول
     *   يطلب إنشاء PIN) أو فشلت القراءة (DataStore تالف) — في الحالتين
     *   يبقى قرار قفل الجهاز على DataStore كما هو فلا ضعف أمني (فشل هادئ
     *   محفوظ الدلالة، والترحيل لا يرمي).
     * - runBlocking آمن هنا: لا حلقة انتظار محتملة — DataStore يقرأ على
     *   نطاق IO مستقل عن منفّذ معاملات Room.
     */
    internal fun readOwnerPinSeedSync(): OwnerPinSeed? = try {
        kotlinx.coroutines.runBlocking {
            val p = context.dataStore.data.first()
            val blob = p[K.pinBlob]
            val salt = p[K.pinSalt]
            if (blob.isNullOrBlank() || salt.isNullOrBlank()) null
            else OwnerPinSeed(
                pinWrapped = blob,
                pinSalt = salt,
                pinIters = p[K.pinIters] ?: 0,
                biometric = p[K.biometric] ?: false
            )
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (_: Exception) {
        null
    }
}
