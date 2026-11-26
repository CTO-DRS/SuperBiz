package com.superbiz.app.testutil

/**
 * [إصلاح تذبذب ConfigRbacGateTest — السبب الجذري، FLAKE_LEDGER #3]
 * كل `by preferencesDataStore(...)` في التطبيق مفرد على مستوى العملية (حقل `$delegate` ساكن)،
 * بينما يعيد Robolectric إنشاء Application ومجلد البيانات لكل اختبار: فيبقى المفرد مربوطًا
 * بمجلد الاختبار السابق المحذوف ⇒ الكتابة لا تصل والقراءة `first { ... }` تنتظر حتى المهلة.
 * هذه الأداة تصفّر الحقل الداخلي INSTANCE في كل مفوّض فيُبنى DataStore جديد على مجلد الاختبار الحالي.
 */
object DataStoreTestReset {

    private val holders = listOf(
        "com.superbiz.app.data.repo.SettingsRepoKt",
        "com.superbiz.app.data.repo.WebhookStoreKt",
        "com.superbiz.app.data.repo.ProStoreKt",
        "com.superbiz.app.data.repo.ChecksArchiveStoreKt",
        "com.superbiz.app.data.repo.SyncEnableStoreKt",
        "com.superbiz.app.data.repo.ZatcaEnableStoreKt",
        "com.superbiz.app.data.repo.GeminiAiStoreKt",
        "com.superbiz.app.security.LockoutKt",
    )

    fun reset() {
        for (name in holders) {
            val cls = try { Class.forName(name) } catch (e: ClassNotFoundException) { continue }
            for (f in cls.declaredFields) {
                if (!f.name.endsWith("\$delegate")) continue
                f.isAccessible = true
                val delegate = f.get(null) ?: continue
                val inst = try { delegate.javaClass.getDeclaredField("INSTANCE") } catch (e: NoSuchFieldException) { continue }
                inst.isAccessible = true
                synchronized(delegate) { inst.set(delegate, null) }
            }
        }
    }
}
