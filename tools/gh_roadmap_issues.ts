// Create milestones + issues on GitHub for the smart roadmap
const TOKEN = process.env.GH_TOKEN ?? "";
const REPO = "CTO-DRS/SuperBiz";
const API = `https://api.github.com/repos/${REPO}`;

const H = {
  Authorization: `Bearer ${TOKEN}`,
  Accept: "application/vnd.github+json",
  "Content-Type": "application/json",
  "X-GitHub-Api-Version": "2022-11-28",
};

async function api(path, method = "GET", body?: unknown) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: H,
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${JSON.stringify(json).slice(0, 300)}`);
  return json;
}

const milestones = [
  { title: "W — بوابة الويب (Web Edition)", description: "لوحة معاينة ويب تفاعلية + تزامن E2E + PWA. المرجع: docs/ROADMAP_SMART_2026.md §2", state: "open" },
  { title: "A — استكمال الفروع والجرد (V3.6.0)", description: "إعادة بناء موجات V3.6.0 المفقودة: واجهة الفروع، خصم POS الفرعي، التقارير الفرعية، اعتمادات التحويل. المرجع: §3", state: "open" },
  { title: "B — خادم المزامنة العمياء (H4-3)", description: "Relay مرجعي مطابق لعقد SyncTransport + Docker + تقارب عبر الشبكة + تدقيق خارجي. المرجع: §4", state: "open" },
  { title: "C — ZATCA الربط الحي (Z2-ج)", description: "Sandbox CSID → مطابقة → إنتاج + أشعار دائنة/مدينة + تحديث الوثائق القانونية. المرجع: §5", state: "open" },
  { title: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", description: "كشوف سداسية اللغات، صلاحيات حرة RBAC v14، ميزانية بالفرع، تصدير محاسبي. المرجع: §6", state: "open" },
  { title: "E — الجودة والانطلاق", description: "عزل التذبذب ×3، توقيع E0.1، مزرعة أجهزة E0.3، AA، ASO، توحيد الوثائق. المرجع: §7", state: "open" },
];

const issues: Array<{ m: string; title: string; labels: string[]; body: string }> = [
  // W
  { m: "W — بوابة الويب (Web Edition)", title: "W1: لوحة معاينة ويب تفاعلية v0.1 (Next.js, RTL, Glass theme)", labels: ["web", "P0"], body: "### معيار القبول\n- بوابة عربية RTL بثيم Glass الماركة (نافي #0B1026 + بنفسجي #8B5CF6 + سماوي #22D3EE)\n- لوحة قيادة KPIs حية، POS حية، فواتير بQR ضريبي مرحلة-1 (TLV مطابق لمحرك ZatcaQr)، مخزون، ذمم، مصروفات، تقارير رسومية\n- قيد مزدوج متوازن (debit == credit) في كل حفظ\n- عارض خارطة الطريق الذكية وتقرير الفحص داخل البوابة\n- تعمل على الجوال والحاسوب\n\n**التسليم:** `web/` في هذا المستودع — كل تكرار يُرفع مباشرة." },
  { m: "W — بوابة الويب (Web Edition)", title: "W2: صفحة تحميل APK + أحدث Release داخل البوابة", labels: ["web", "P2"], body: "### معيار القبول\n- زر تحميل يقرأ أحدث Release من GitHub API ويعرض SHA-256\n- لقطات الشاشة وأحدث نسخة APK ظاهرة داخل البوابة" },
  { m: "W — بوابة الويب (Web Edition)", title: "W3: تزامن ويب ↔ جهاز عبر الrelay المعمي + ADR-003", labels: ["web", "sync", "P1"], body: "### معيار القبول\n- WebCrypto: Argon2id + AES-256-GCM مطابق لعقد ADR-002\n- تقارب بايتي ويب↔جهاز مختبر عبر relay حقيقي\n- ADR-003 موثق قبل أي كود شبكي" },
  { m: "W — بوابة الويب (Web Edition)", title: "W4: PWA + طباعة حرارية عبر Web Bluetooth", labels: ["web", "P2"], body: "### معيار القبول\n- تثبيت PWA وإشعارات تعمل\n- إيصال ESC/POS يُطبع من المتصفح على طابعة Bluetooth" },
  // A
  { m: "A — استكمال الفروع والجرد (V3.6.0)", title: "A1: شاشة الفروع — عرض/إنشاء/أرشفة فرع (ربط TransferRepo بالـAppGraph)", labels: ["android", "branches", "P0"], body: "### معيار القبول\n- Compose + VM عبر AppGraph (TransferRepo بلا AppGraph اليوم)\n- عرض مخزون كل فرع\n- اختبار واجهة تعاقدي أخضر\n\n*المرجع: تقرير الفحص §3 بند 6 — docs/INSPECTION_REPORT_V352.md*" },
  { m: "A — استكمال الفروع والجرد (V3.6.0)", title: "A2: شاشة التحويلات — إرسال/استقبال/إلغاء بحالة صريحة + الفروق", labels: ["android", "branches", "P0"], body: "### معيار القبول\n- المسار كامل عبر TransferRepo (IN_TRANSIT/RECEIVED/CANCELED)\n- منع إرسال أكبر من المتاح + عرض الفروق المسجلة عند الاستقبال" },
  { m: "A — استكمال الفروع والجرد (V3.6.0)", title: "A3: خصم POS على مستوى الفرع + مطابقة المستويين (v18↔فرعي)", labels: ["android", "branches", "pos", "P0"], body: "### معيار القبول\n- خصم ذري من branch_stock للفرع النشط داخل معاملة الحفظ\n- انعكاس على products.stockQty (عقد v18 محفوظ)\n- اختبار خاصية «لا مخزون شبح» يمدد MultiBranchCoreTest بعد البيع" },
  { m: "A — استكمال الفروع والجرد (V3.6.0)", title: "A4: إسناد فرعي (branchId تراكمي) + تقارير محدودة بالفرع → V3.6.0", labels: ["android", "branches", "P0"], body: "### معيار القبول\n- ترحيل 19→20 تراكمي واحد ذري (branchId على الفواتير/المصروفات)\n- مبدّل «الفرع الحالي» في الواجهة\n- تقارير مبيعات/أرباح/مخزون بالفرع تعمل\n- اختبار تقارب المزامنة يعاد (جداول الفروع local-owned)" },
  { m: "A — استكمال الفروع والجرد (V3.6.0)", title: "A5: اعتمادات التحويل (طلب/اعتماد/رفض) + Op جديد في RBAC", labels: ["android", "branches", "rbac", "P1"], body: "### معيار القبول\n- حالة PENDING_APPROVAL + طالب/معتمد في stock_transfers\n- Op جديد TRANSFER_APPROVE بمصفوفة RBAC محدثة بنفس الPR + اختبار الخلية" },
  // B
  { m: "B — خادم المزامنة العمياء (H4-3)", title: "B1: Relay مرجعي (TypeScript/Bun) مطابق لعقد SyncTransport", labels: ["sync", "server", "P1"], body: "### معيار القبول\n- POST /push + GET /pull مطابقان لعقد SyncTransport حرفيًا\n- تخزين مشفر-at-rest، حد 4MB للكتلة، 429/408 للمؤقت\n- الخادم أعمى: لا يفك أي payload\n- اختبارات وحدة + مطابقة عقدية مع SyncClient" },
  { m: "B — خادم المزامنة العمياء (H4-3)", title: "B2: Docker + دليل نشر المالك + فحوصات سلامة", labels: ["sync", "server", "ops", "P1"], body: "### معيار القبول\n- نشر بخطوتين على VPS نظيف بنطاق HTTPS يملكه المالك\n- فحص سلامة /health + سجلات بلا محتوى (أعمى)" },
  { m: "B — خادم المزامنة العمياء (H4-3)", title: "B3: تقارب حقيقي عبر الشبكة: جهازان → relay → تطابق بايتي → V3.7.0", labels: ["sync", "P1"], body: "### معيار القبول\n- يمدد SyncConvergenceTest عبر نقل شبكي حقيقي\n- سيناريو «أوفلاين طويل ثم عودة» بدون فقد أو تكرار" },
  { m: "B — خادم المزامنة العمياء (H4-3)", title: "B4: تدقيق أمني خارجي للمزامنة (شرط التسويق المؤسسي)", labels: ["security", "P2"], body: "### معيار القبول\n- تقرير تدقيق مستقل مرفق بdocs/\n- إغلاق الملاحظات الحرجة والعالية بـRED-first" },
  // C
  { m: "C — ZATCA الربط الحي (Z2-ج)", title: "C1: فتح Fatoora sandbox وإدخال CSID التجريبي (إداري — على المالك)", labels: ["zatca", "owner", "P1"], body: "### الخطوات (runbook §8)\n1. سجل تجاري + رقم ضريبة مسجل بZATCA\n2. حساب Fatoora + CSID sandbox\n3. إدخال الاعتمادات من الإعدادات → «الربط الضريبي — فاتورة»\n\n> إداري بالكامل — لا يحجب أي مسار هندسي (G7)." },
  { m: "C — ZATCA الربط الحي (Z2-ج)", title: "C2: سيناريو الاعتماد الحي: خلاصة قياسية + إبلاغ مبسطة + 10h أوفلاين + سلسلة 100 فاتورة", labels: ["zatca", "P1"], body: "### معيار القبول\n- فاتورة قياسية (0100000) تُخلَّص + مبسطة (0200000) تُبلَّغ على sandbox حقيقي\n- سلسلة 100 فاتورة (3 أشعار دائنة) تتحقق محليًا بZatcaStamp.verify()\n- صفر خروج لغير Fatoora (تحقق proxy)" },
  { m: "C — ZATCA الربط الحي (Z2-ج)", title: "C3: أشعار دائنة/مدينة (تصحيح بدل إلغاء)", labels: ["zatca", "android", "P2"], body: "### معيار القبول\n- UBL دائنة/مدينة ذهبي + اختبارات RED-first\n- ربط بالإلغاء الآمن الحالي دون كسر السلسلة" },
  { m: "C — ZATCA الربط الحي (Z2-ج)", title: "C4: تخزين شهادة CSID + انتهاء صلاحية + تنبيه تجديد (C5)", labels: ["zatca", "P2"], body: "### معيار القبول\n- إنذار 30/7/1 يوم قبل انتهاء الشهادة\n- عرض حالة الاعتماد في لوحة حالات الربط" },
  { m: "C — ZATCA الربط الحي (Z2-ج)", title: "C5: تحديث PRIVACY_POLICY + PLAY_DATA_SAFITY بقسم الإبلاغ الحي", labels: ["zatca", "legal", "P2"], body: "### معيار القبول\n- قسم «إرسال البيانات»: UBL + بصمة → Fatoora فقط (قائمة خروج D5)\n- النشر بنفس موجة الربط الحي" },
  // D
  { m: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", title: "D1: قوالب كشوف سداسية اللغات (ar/en → fr/tr/ur/id)", labels: ["android", "i18n", "P2"], body: "### معيار القبول\n- القوالب الست تنشئ PDF متطابقة البنية مع RTL صحيح\n- سلاسل اللغات الست تبقى 2,441×6 بدون كسر بوابات CI" },
  { m: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", title: "D2: صلاحيات حرة RBAC v14 — جدول role_permissions بمصفوفة الكود بذرة", labels: ["rbac", "android", "P2"], body: "### معيار القبول\n- RoleGate ينتقل من مصفوفة الكود للجدول بعقد المستهلك دون تغيير\n- ترحيل تراكمي يبذر المصفوفة الحالية (68 خلية)\n- RbacMatrixTest يبقى أخضر فوق الجدول" },
  { m: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", title: "D3: قرار RBAC لتحصيل الذمم (addDebt/addPayment غير مسيَّجَين)", labels: ["rbac", "decision", "P1"], body: "### المطلوب\nقرار مالك موثق: تسجيل المسارين بالمصفوفة (الخيار الموصى به: تسييج COLLECTION مع منح CASHIER مسارًا مقيَّدًا) وتنفيذه بنفس PR مع تحديث RBAC_MATRIX.md." },
  { m: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", title: "D4: ميزانية تقديرية مقابل فعلي + مركز تكلفة بالفرع", labels: ["android", "finance", "P2"], body: "### معيار القبول\n- أهداف مصروفات بالفروع + تقارير مقارنة\n- تكامل مع لوحة KPIs القائمة" },
  { m: "D — المحاسبة العميقة والمؤسسة (V4.0.0)", title: "D5: تصدير محاسبي موسع (دفتر أستاذ/ميزان مراجعة CSV)", labels: ["android", "export", "P2"], body: "### معيار القبول\n- مطابق EXPORT_API.md + UTF-8-BOM + حرس حقن CSV الموحد" },
  // E
  { m: "E — الجودة والانطلاق", title: "E1: عزل الاختبارات (forkEvery/نطاق موارد) — صفر تذبذب ×3", labels: ["quality", "P1"], body: "### معيار القبول\n- 3 تشغيلات متتالية صفر تذبذب موثقة في FLAKE_LEDGER.md\n- إغلاق الباب المفتوح الوحيد (تذبذب 0.04% الحالي ضمن الميزانية)" },
  { m: "E — الجودة والانطلاق", title: "E2: E0.1 — مراسم توقيع الإصدار + Play Console (على المالك)", labels: ["release", "owner", "P1"], body: "### معيار القبول\n- سر SIGNING_KEYSTORE_B64 مضبوط بسرريات GitHub\n- وسم تجريبي ينتج APK موقّع فعليًا (لا unsigned)" },
  { m: "E — الجودة والانطلاق", title: "E3: E0.3 — مزرعة 3 أهداف + PERF_BASELINE_W1 + تفعيل Macrobenchmark", labels: ["quality", "perf", "P2"], body: "### معيار القبول\n- p50 بارد<2000ms على جهاز 4GB موثق\n- benchmark-results.json مرفق بrelease ليصبح بوابة الأداء مفروضة" },
  { m: "E — الجودة والانطلاق", title: "E4: مسح إمكانية الوصول AA (TalkBack/تباين/أحجام)", labels: ["a11y", "P2"], body: "### معيار القبول\n- ماسح Accessibility Scanner صفر ملاحظات على الشاشات العشر الأساسية" },
  { m: "E — الجودة والانطلاق", title: "E5: ASO للغات الست + لقطات أجهزة إضافية", labels: ["store", "P2"], body: "### معيار القبول\n- تحديث STORE_LISTING_ASO للغات الست بنفس موجة V4.0.0" },
  { m: "E — الجودة والانطلاق", title: "E6: توحيد الوثائق — ROADMAP الجذري يشير للخارطة الذكية", labels: ["docs", "P1"], body: "### الحالة\n✅ منفذ 2026-10-30 — commit a86ac7e: ROADMAP.md الجذري يحيل إلى docs/ROADMAP_SMART_2026.md + تقرير الفحص docs/INSPECTION_REPORT_V352.md." },
];

async function main() {
  // 1) milestones
  const mIds: Record<string, number> = {};
  for (const m of milestones) {
    const created = await api("/milestones", "POST", m);
    mIds[m.title] = created.number;
    console.log(`milestone ✓ ${m.title} (#${created.number})`);
  }
  // 2) issues
  for (const it of issues) {
    const created = await api("/issues", "POST", {
      title: it.title,
      body: it.body + `\n\n---\n🏷️ المسار: **${it.m}** · Milestone #${mIds[it.m]} · المرجع: \`docs/ROADMAP_SMART_2026.md\``,
      labels: it.labels,
      milestone: mIds[it.m],
    });
    console.log(`issue ✓ #${created.number} ${it.title}`);
  }
  console.log("DONE");
}
main().catch((e) => { console.error(e); process.exit(1); });
