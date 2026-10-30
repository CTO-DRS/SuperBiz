/**
 * بيانات خارطة الطريق الذكية وتقرير الفحص — مطابقة للوثائق المرفوعة إلى GitHub:
 * docs/ROADMAP_SMART_2026.md · docs/INSPECTION_REPORT_V352.md
 */

export type ItemStatus = "done" | "active" | "planned" | "owner";

export type RoadmapItem = {
  code: string;
  title: string;
  acceptance: string;
  status: ItemStatus;
  priority: "P0" | "P1" | "P2";
  issue?: number;
};

export type RoadmapTrack = {
  key: string;
  name: string;
  tagline: string;
  priority: string;
  window: string;
  status: ItemStatus;
  items: RoadmapItem[];
};

export const ROADMAP: RoadmapTrack[] = [
  {
    key: "W",
    name: "بوابة الويب (Web Edition)",
    tagline: "طبقة عرض وإدارة وامتداد مبيعات فوق عقد منفصل — لا تلمس التزام «دون اتصال»",
    priority: "P0",
    window: "يبدأ فورًا",
    status: "active",
    items: [
      { code: "W1", title: "لوحة معاينة ويب تفاعلية v0.1 — Next.js عربية RTL بثيم Glass: لوحة قيادة، POS حية، فواتير بQR ضريبي TLV حقيقي، مخزون، ذمم، مصروفات، تقارير، عارض الخارطة والفحص", acceptance: "بيانات حية عبر APIs · قيد مزدوج متوازن · QR مطابق لمحرك ZatcaQr · جوال وحاسوب", status: "active", priority: "P0", issue: 9 },
      { code: "W2", title: "صفحة تحميل APK + أحدث Release داخل البوابة", acceptance: "زر تحميل يقرأ أحدث Release من GitHub API ويعرض SHA-256", status: "planned", priority: "P2", issue: 10 },
      { code: "W3", title: "تزامن ويب ↔ جهاز عبر الrelay المعمي (WebCrypto) + ADR-003", acceptance: "تقارب بايتي ويب↔جهاز مختبر", status: "planned", priority: "P1", issue: 11 },
      { code: "W4", title: "PWA + طباعة حرارية عبر Web Bluetooth", acceptance: "إيصال ESC/POS من المتصفح", status: "planned", priority: "P2", issue: 12 },
    ],
  },
  {
    key: "A",
    name: "استكمال الفروع والجرد (V3.6.0)",
    tagline: "إعادة بناء موجات V3.6.0 المفقودة بحادثة rollback — النواة E2.1 سليمة ومختبرة بـ200 سيناريو",
    priority: "P0",
    window: "أسبوع 1–3",
    status: "planned",
    items: [
      { code: "A1", title: "شاشة الفروع — ربط TransferRepo بالـAppGraph (بلا واجهة اليوم)", acceptance: "Compose + VM عبر AppGraph + اختبار تعاقدي", status: "planned", priority: "P0", issue: 13 },
      { code: "A2", title: "شاشة التحويلات — إرسال/استقبال/إلغاء بحالة صريحة + الفروق", acceptance: "منع إرسال أكبر من المتاح + عرض الفروق", status: "planned", priority: "P0", issue: 14 },
      { code: "A3", title: "خصم POS على مستوى الفرع + مطابقة المستويين (v18↔فرعي)", acceptance: "خصم ذري من branch_stock + اختبار «لا شبح» بعد البيع", status: "planned", priority: "P0", issue: 15 },
      { code: "A4", title: "إسناد فرعي تراكمي (branchId) + تقارير بالفرع → إصدار V3.6.0 موسوم", acceptance: "ترحيل 19→20 تراكمي واحد ذري + تقارير فرعية", status: "planned", priority: "P0", issue: 16 },
      { code: "A5", title: "اعتمادات التحويل (طلب/اعتماد/رفض) + Op جديد RBAC", acceptance: "مصفوفة RBAC تتوسع بنفس الPR", status: "planned", priority: "P1", issue: 17 },
    ],
  },
  {
    key: "B",
    name: "خادم المزامنة العمياء (H4-3)",
    tagline: "العميل كامل ومختبر 100% — يتبقى الrelay الأعمى المطابق لعقد SyncTransport",
    priority: "P1",
    window: "أسبوع 2–5",
    status: "planned",
    items: [
      { code: "B1", title: "Relay مرجعي (TypeScript/Bun) مطابق للعقد حرفيًا", acceptance: "خادم أعمى لا يفك payload + مطابقة عقدية مع SyncClient", status: "planned", priority: "P1", issue: 18 },
      { code: "B2", title: "Docker + دليل نشر المالك + فحوصات سلامة", acceptance: "نشر بخطوتين على VPS بنطاق HTTPS يملكه", status: "planned", priority: "P1", issue: 19 },
      { code: "B3", title: "تقارب حقيقي عبر الشبكة: جهازان → relay → تطابق بايتي → V3.7.0", acceptance: "يمدد SyncConvergenceTest عبر نقل حقيقي", status: "planned", priority: "P1", issue: 20 },
      { code: "B4", title: "تدقيق أمني خارجي للمزامنة (شرط التسويق المؤسسي)", acceptance: "تقرير تدقيق مرفق + إغلاق الملاحظات", status: "planned", priority: "P2", issue: 21 },
    ],
  },
  {
    key: "C",
    name: "ZATCA الربط الحي (Z2-ج)",
    tagline: "link-ready — كل المحرك الجهازي منجز؛ الباقي إداري وتقوية تشغيلية",
    priority: "P1",
    window: "حسب بيانات اعتماد المالك",
    status: "planned",
    items: [
      { code: "C1", title: "فتح Fatoora sandbox وإدخال CSID التجريبي", acceptance: "قياسية تُخلَّص + مبسطة تُبلَّغ على الحساب الحقيقي", status: "owner", priority: "P1", issue: 22 },
      { code: "C2", title: "سيناريو الاعتماد الحي: 10h أوفلاين + سلسلة 100 فاتورة", acceptance: "ZatcaStamp.verify() أخضر + صفر خروج لغير Fatoora", status: "planned", priority: "P1", issue: 23 },
      { code: "C3", title: "أشعار دائنة/مدينة (تصحيح بدل إلغاء)", acceptance: "UBL ذهبي + اختبارات RED-first", status: "planned", priority: "P2", issue: 24 },
      { code: "C4", title: "تخزين شهادة CSID + تنبيه تجديد (C5)", acceptance: "إنذار 30/7/1 يوم", status: "planned", priority: "P2", issue: 25 },
      { code: "C5", title: "تحديث PRIVACY_POLICY + PLAY_DATA_SAFETY بقسم الإبلاغ", acceptance: "النشر بنفس موجة الربط الحي", status: "planned", priority: "P2", issue: 26 },
    ],
  },
  {
    key: "D",
    name: "المحاسبة العميقة والمؤسسة (V4.0.0)",
    tagline: "التوسع المؤسسي فوق أساس RBAC والقيد المزدوج الناضج",
    priority: "P2",
    window: "ربع قادم",
    status: "planned",
    items: [
      { code: "D1", title: "قوالب كشوف سداسية اللغات (ar/en → fr/tr/ur/id)", acceptance: "القوالب الست PDF متطابقة البنية + سلاسل 2,441×6 دون كسر CI", status: "planned", priority: "P2", issue: 27 },
      { code: "D2", title: "صلاحيات حرة RBAC v14 — جدول role_permissions بمصفوفة الكود بذرة", acceptance: "RoleGate ينتقل للجدول بعقد دون تغيير", status: "planned", priority: "P2", issue: 28 },
      { code: "D3", title: "قرار RBAC لتحصيل الذمم (addDebt/addPayment غير مسيَّجَين)", acceptance: "سياسة موثقة + تنفيذ بنفس PR", status: "owner", priority: "P1", issue: 29 },
      { code: "D4", title: "ميزانية تقديرية مقابل فعلي + مركز تكلفة بالفرع", acceptance: "تقارير مقارنة بالفرع", status: "planned", priority: "P2", issue: 30 },
      { code: "D5", title: "تصدير محاسبي موسع (دفتر أستاذ/ميزان مراجعة)", acceptance: "مطابق EXPORT_API.md + حرس الحقن", status: "planned", priority: "P2", issue: 31 },
    ],
  },
  {
    key: "E",
    name: "الجودة والانطلاق",
    tagline: "متوازٍ دائم — البوابات الصادقة قبل التوسعة",
    priority: "P1–P2",
    window: "متوازٍ دائم",
    status: "active",
    items: [
      { code: "E1", title: "عزل الاختبارات (forkEvery) — صفر تذبذب ×3", acceptance: "3 تشغيلات متتالية صفر تذبذب موثقة بFLAKE_LEDGER", status: "planned", priority: "P1", issue: 32 },
      { code: "E2", title: "E0.1 — مراسم توقيع الإصدار + Play Console", acceptance: "APK موقّع فعليًا عند وسم v*", status: "owner", priority: "P1", issue: 33 },
      { code: "E3", title: "E0.3 — مزرعة 3 أهداف + PERF_BASELINE + Macrobenchmark", acceptance: "p50 بارد<2000ms موثق على جهاز 4GB", status: "owner", priority: "P2", issue: 34 },
      { code: "E4", title: "مسح إمكانية الوصول AA", acceptance: "ماسح صفر ملاحظات على الشاشات الأساسية", status: "planned", priority: "P2", issue: 35 },
      { code: "E5", title: "ASO للغات الست + لقطات أجهزة", acceptance: "تحديث المتجر بنفس موجة V4.0.0", status: "planned", priority: "P2", issue: 36 },
      { code: "E6", title: "توحيد الوثائق — ROADMAP الجذري يشير للخارطة الذكية", acceptance: "صفر انحراف بين الجذر وdocs وCHANGELOG", status: "done", priority: "P1", issue: 37 },
    ],
  },
];

export const INSPECTION = {
  stats: [
    { label: "الإصدار", value: "V 3.5.2", sub: "versionCode 23" },
    { label: "أسطر المصدر", value: "79,250", sub: "242 ملف Kotlin" },
    { label: "الاختبارات", value: "1,847", sub: "أخضر × 169 ملفًا" },
    { label: "المخطط", value: "v19", sub: "34 جدولًا · 18 ترحيلًا" },
    { label: "اللغات", value: "6 × 100%", sub: "2,441 مفتاحًا لكل لغة" },
    { label: "TODO/FIXME", value: "0", sub: "تأجيلات موثقة لا مخفية" },
  ],
  strengths: [
    "قاعدة ذهبية: دون اتصال 100% — شبكة معزولة feature-gated خلف فاحص CI وقائمة خروج مغلقة",
    "المال Long قروش — صفر Double — توازن القيد المزدوج مساواة تامة قبل أي حفظ",
    "ذرّية كاملة: كل عملية متعددة الجداول معاملة Room واحدة",
    "ترحيلات تراكمية فقط (1→19) — لا هدّامة، لا Keystore داخل المعاملات",
    "RBAC مقفول افتراضيًا: 4 أدوار × 17 عملية = 68 خلية مختبرة خلية-خلية",
    "انضباط RED-first: الاختبار يُشاهد يفشل قبل كل إصلاح — موثق في كل موجة",
    "بوابات CI قابلة للدحض (self-test) + سقف APK ≤10MB مقاس كل إصدار",
  ],
  gaps: [
    { t: "موجات V3.6.0 مفقودة", d: "فُقدت بحادثة تراجع البيئة — النواة سليمة ويجب إعادة التكامل (واجهة الفروع، خصم POS الفرعي، التقارير الفرعية)" },
    { t: "خادم المزامنة غير مبني", d: "العميل E2E كامل ومختبر — يتبقى relay أعمى مطابق لعقد SyncTransport" },
    { t: "ZATCA الحي ينتظر بيانات اعتماد", d: "Z2-ج إداري بالكامل — CSID من Fatoora sandbox على المالك" },
    { t: "لا نسخة ويب إطلاقًا", d: "الفجوة التي يسدّها المسار W — بوابة الويب تبدأ اليوم v0.1" },
    { t: "انحراف الوثائق", d: "ROADMAP الجذري كان متخلفًا (V3.1.0) — عولج بتوحيد المرجع 2026-10-30 (E6 ✓)" },
  ],
  openItems: [
    "E0.1 توقيع الإصدار (المالك) · E0.3 مزرعة أجهزة (المالك) · Z2-ج بيانات اعتماد (المالك) · H4-3 نطاق الخادم (المالك)",
    "قرار RBAC لتحصيل الذمم · RVW-21 الخيار B · دلالات الكوبون qty>1",
    "قوالب سداسية اللغات · تدقيق أمني خارجي للمزامنة · مسح AA · ASO · أرقام أداء على جهاز حقيقي",
  ],
};
