# بوابة الأداء ومقياس Macrobenchmark — Performance Budgets & Harness
# [E1.1 V 3.4.0]

## الميزانيات المعتمدة (عقد خارطة الطريق E1.1 حرفياً + قبول المرحلة 2)

| المقياس | الميزانية | السياق |
|---|---|---|
| cold start p50 | < 2000 ms | جهاز 4غ الطبقة (صنف OOM الموثق) |
| search p95 | < 200 ms | عند 10k عنصراً |
| sync p95 | < 2000 ms | دورة مزامنة كاملة |
| checkout p50 | ≤ 30 s | قبول المرحلة 2 (E2) — مرصود من الآن |

## البوابة (مفروضة في CI الآن)

`tools/check_perf_budgets.py` — البوابة الخامسة، بنفس اقتصاد بوابات الجودة
الأربع: فاحص + اختبار ذاتي يثبت أنه يصطاد التجاوز ويفشل مغلقاً على الملف
التالف. في CI: وضع `NOT-VERIFIED-ON-DEVICE` الصادق على مسار الـPR بلا جهاز،
والفرض الكامل لحظة رفق `benchmark-results.json` (release workflow موصول
بجهاز عبر runner ذاتي الاستضافة — عقد E0.3).

## مقياس Macrobenchmark (مؤلَّف — التفعيل باب جهاز)

الوحدة جاهزة تحت `docs/perf-harness/benchmark/` (نسخة إلى جذر المستودع +
سطر `include(":benchmark")` في `settings.gradle.kts` لحظة توفر مصفوفة
الأجهزة). **لا تدخل شيفرة غير مبنية رسم البناء أبداً** — عقد النقاء هذا
السبب في فصل المقياس عن الرسم حتى الإقلاع على عتاد حقيقي.

خطوات التفعيل (تُنفَّذ مرة عند توفر الجهاز):
1. `cp -r docs/perf-harness/benchmark .` ثم أضف `include(":benchmark")` إلى `settings.gradle.kts`
2. `./gradlew :benchmark:connectedCheck` على جهاز 4غ (صنف OOM) — runner ذاتي الاستضافة أو محلياً
3. انسخ التقرير إلى `benchmark-results.json` وأرفقه بالـPR — البوابة تُفرض آلياً

العينان المؤلفتان: `StartupBenchmark` (بارد p50) و`SearchAndCheckoutBenchmark`
(بحث 10k p95 + قياس مسار الدفع p50) — القياسات مطابقة لأعمدة البوابة حرفياً.
