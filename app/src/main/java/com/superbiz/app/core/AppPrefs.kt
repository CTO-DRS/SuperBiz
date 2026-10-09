package com.superbiz.app.core

/**
 * — خزنة الإعدادات الحيّة (AppPrefs)
 *
 * مصدر الحقيقة الدائم هو DataStore عبر SettingsRepo — لكن بعض الطبقات العميقة
 * (حوارات الطباعة، مسارات الحفظ في الشاشات، الويدجت) تحتاج قراءة متزامنة لقيمة
 * إعداد دون تمرير Flows عبر كل الطبقات. هذه الكائنة نسخة حيّة مُحدَّثة باستمرار
 * AppVM يجمع settings وينسخ الحقول هنا عند كل تغيير، فتظل القيم صالحة خلال عمر
 * العملية، وتُعاد إلى الافتراضات الآمنة عند إعادة التشغيل.
 *
 * كل حقل هنا يجب أن يُكتب من AppVM.syncPrefs() فقط.
*/
object AppPrefs {

    /** سلوك: اهتزاز تأكيد خفيف عند العمليات المالية المهمة */
    @Volatile var hapticsEnabled: Boolean = true

    /** سلوك: تأكيد قبل الإجراءات المدمّرة (إلغاء فاتورة، تفريغ السلة، تحديد كمدفوعة) */
    @Volatile var confirmDestructive: Boolean = true

    /** خصوصية: منع لقطات الشاشة (FLAG_SECURE) — كان مُقفلًا على true قبل */
    @Volatile var flagSecure: Boolean = true

    /** خصوصية: مهلة إعادة القفل عند الخلفية بالدقائق (0 = فوري) */
    @Volatile var lockTimeoutMin: Int = 0

    /** مظهر: تكبير الخط 0.85..1.30 */
    @Volatile var fontScale: Float = 1.0f

    /** مظهر: ألوان النظام الديناميكية (Android 12+) */
    @Volatile var dynamicColors: Boolean = false

    /** مظهر: عكس اتجاه الرسوم البيانية في الوضع العربي (الزمن يمين←يسار) */
    @Volatile var mirrorChartsRtl: Boolean = true

    /** أداء: إيقاف التمرير الباطني (استخدام أقل للذاكرة على الأجهزة الضعيفة) */
    @Volatile var animationsEnabled: Boolean = true

    /** متقدم: أسلوب الطباعة العربية للطابعات الحرارية — 0 = CP1256 (متوافق أوسع)، 1 = UTF-8 */
    @Volatile var arabicReceiptMode: Int = 0

    /** [H4-6] قالب الفاتورة المطبوعة — 0 كلاسيكي / 1 مفصّل / 2 مضغوط */
    @Volatile var invoiceTemplate: Int = 0

    /** متقدم: حدّ المخزون المنخفض الافتراضي للمنتجات بلا حدّ خاص */
    @Volatile var defaultLowStockQty: Int = 5

    /** إشعارات: تنبيه انخفاض المخزون في أعمال الخلفية */
    @Volatile var lowStockAlerts: Boolean = true

    /** إشعارات: تنبيه الذمم المتأخرة في أعمال الخلفية */
    @Volatile var receivableAlerts: Boolean = true

    /** بحث (): حدّ قبول التطابق الضبابي في البحث الشامل — يُقرأ من GlobalSearchVM */
    @Volatile var searchFuzzyThreshold: Double = 0.45

    /** مخزون (): كلفة أمر الشراء في معادلة EOQ — يُقرأ من InventoryVM */
    @Volatile var eoqOrderCost: Double = 25.0

    /** خصوصية ( وظيفة 38): طمس مبالغ بطاقات الرئيسية كـ «•••» */
    @Volatile var privacyBlur: Boolean = false

    /** جولة 5 [P44-K1]: تخطيط بطاقات الرؤى المخصص (JSON محرك DashboardPrefsP44) — null = الافتراضي */
    @Volatile var dashboardLayout: String? = null
}
