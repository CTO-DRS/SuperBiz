# SuperBiz Web Console — بوابة الويب

> **المسار W1** من خارطة الطريق الذكية المتكاملة ([`docs/ROADMAP_SMART_2026.md`](../docs/ROADMAP_SMART_2026.md)) — نسخة ويب v0.1 للمعاينة الحية، مطابقة لهوية التطبيق (RTL · ثيم Glass: نافي `#0B1026` · بنفسجي `#8B5CF6` · سماوي `#22D3EE`).

**هذه بوابة مستقلة تمامًا عن تطبيق الأندرويد** — لا تلمس التزام «دون اتصال 100%» ولا قائمة الخروج المغلقة (ADR-001 D5)، فهي Artifact منفصل بعقد خاص. عند الربط بالمزامنة مستقبلًا (W3) سيتم عبر ADR-003 قبل أي كود شبكي.

## الوحدات (مطابقة لشاشات التطبيق)

| الوحدة | ما تطبقه من عقد التطبيق |
|---|---|
| لوحة القيادة | KPIs حية + نبض العمل 0..100 (مستوحى من R18Kpi — حتمي بلا AI) + سلسلة 7 أيام + تنبيه مخزون |
| نقطة البيع | سلة ذكية + دفع نقدي/آجل + **حفظ ذرّي**: بنود + خصم مخزون + قيد مزدوج متوازن + ختم ZATCA داخل معاملة واحدة |
| الفواتير | قائمة + تفاصيل بهوية ZATCA (UUID/ICV) + QR ضريبي مرحلة-1 (TLV Base64 مطابق لمحرك `ZatcaQr.kt`) + **إلغاء آمن بقيد تعويضي** |
| المخزون | منتجات + أرشفة + تسوية جرد + قيمة المخزون بالتكلفة |
| الذمم | أرصدة الأطراف + تحصيل سدود (قيد: نقدية مدينة / ذمم دائنة) |
| المصروفات | فئات + حد شهري بإنذار + قيد فوري |
| التقارير | مبيعات/أرباح/ضريبة + أعلى المنتجات + فئات + مزيج الدفع (7/30/90 يومًا) |
| خارطة الطريق | المسارات W/A/B/C/D/E بروابط Issues على GitHub |
| تقرير الفحص | ملخص الفحص الهندسي V 3.5.2 |

## العقود الموروثة من التطبيق (غير قابلة للتفاوض)

- **المال قروش (Int)** — صفر Double في أي مسار مالي؛ العرض ريال بمنزلتين.
- **القيد المزدوج متوازن إلزاميًا** — `postJournal` يرفض أي مسودة `debit != credit` قبل الحفظ (مرآة `AccountingEngine.kt`).
- **الذرّية** — كل عملية متعددة الجداول داخل `db.$transaction` واحدة.
- **الترقيم التسلسلي من قاعدة البيانات داخل المعاملة** + زاتكا: UUID/ICV لكل فاتورة.
- **ZATCA صارم لـSA** — ضريبة 15% افتراضيًا، QR مرحلة-1 فقط في v0.1.

## التشغيل

```bash
cd web
bun install                 # أو npm install
cp .env.example .env        # DATABASE_URL="file:../db/custom.db" أو أي مسار SQLite
bun run db:push             # إنشاء الجداول
bun run dev                 # http://localhost:3000
```

البذر التلقائي: أول استدعاء لأي API يزرع بيانات تجريبية حتمية (12 منتجًا، 6 أطراف، ~53 فاتورة موزعة على 30 يومًا، سدود جزئية، مصروفات) — **لا مخزون سالب أبدًا** والتوازن المحاسبي تام.

## حلقة الجودة (موجة W1.4 — Issue #38)

```bash
bun install            # أو npm install
bun run test           # جناح العقود النقية (vitest — 17 اختبارًا بلا قاعدة بيانات، نمط G7)
bun run typecheck      # tsc --noEmit — مفروض: next.config لا يخفي أخطاء الأنواع (ignoreBuildErrors: false)
bun run lint           # eslint — نظيف إجباري
DATABASE_URL="file:./ci-check.db" bun run build   # بناء إنتاج كامل بأنواع مفروضة
```

- **الناقلات الذهبية المُرحَّلة حرفيًا من `ZatcaQrTest.kt`** (عينة Bunnia المرجعية + حدود TLV + طابع 20 بايت) تضمن تطابق بايتي بين محرك الويب ومحرك الأندرويد — أي انحراف مستقبلي = أحمر في الجهتين.
- **القيد المزدوج مختبر نقيًا:** مسودة غير متوازنة بفرق قروش واحد تُرفض بلا أي كتابة.
- تقرير الفحص المولِّد لهذه الحلقة: [`docs/INSPECTION_REPORT_W1_WEB.md`](../docs/INSPECTION_REPORT_W1_WEB.md) — وCI يشغّل الحلقة كاملة على كل تعديل `web/**` (`.github/workflows/web.yml`).

## البنية

```
web/
├── src/app/api/            # 8 واجهات REST (dashboard/products/parties/invoices/payments/expenses/reports/roadmap)
├── src/components/superbiz # 9 تبويبات (shell + dashboard/pos/invoices/inventory/debts/expenses/reports/roadmap/inspection)
├── src/lib/                # money (قروش) · zatca (TLV+QR) · journal (قيد متوازن) · seed (بذر حتمي)
└── prisma/schema.prisma    # 8 جداول: Product/Party/Invoice/InvoiceItem/Payment/Expense/JournalEntry/JournalLine
```

## الخطوة التالية في هذا المسار

- **W2** — صفحة تحميل APK (أحدث Release من GitHub API + SHA-256).
- **W3** — تزامن ويب↔جهاز عبر الrelay المعمي (WebCrypto) + ADR-003 — Issue #11.
- **W4** — PWA + طباعة ESC/POS عبر Web Bluetooth — Issue #12.
