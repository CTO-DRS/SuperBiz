/**
 * W1.7 (Issue #41) — اختبارات تكامل API على SQLite حقيقية: معالجات المسارات الحقيقية
 * (requireSession + zod + Prisma معاملات ذرّية) فوق قاعدة ملفية تُهيأ في globalSetup.
 *
 * معيار القبول الحرفي من الخارطة: «≥15 اختبار مسار + خاصيتا المخزون والتوازن».
 * - خاصية التوازن: كل قيد محفوظ في القاعدة debit == credit مساواة تامة + المجموع العام.
 * - خاصية المخزون: لا مخزون سالب أبدًا — البيع يُرفض قبل الخصم إن لم يكفِ.
 */
import { beforeAll, describe, expect, it } from "vitest";
import { NextRequest } from "next/server";
import { signSession, SESSION_COOKIE } from "@/lib/auth";
import { db } from "@/lib/db";
import { ACCOUNTS } from "@/lib/journal";
import { GET as invoicesGet, PATCH as invoicesPatch, POST as invoicesPost } from "@/app/api/invoices/route";
import { POST as paymentsPost } from "@/app/api/payments/route";
import { PATCH as productsPatch, POST as productsPost } from "@/app/api/products/route";
import { POST as partiesPost } from "@/app/api/parties/route";
import { POST as expensesPost } from "@/app/api/expenses/route";
import { TEST_ENV } from "./integration.env";

const SECRET = TEST_ENV.SUPERBIZ_SESSION_SECRET;
const cookieOf = async () => {
  const { token } = await signSession(SECRET);
  return `${SESSION_COOKIE}=${token}`;
};

const post = (path: string, body: unknown, cookie?: string) =>
  new NextRequest(`http://localhost:3000${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(cookie ? { cookie } : {}) },
    body: JSON.stringify(body),
  });

const patch = (path: string, body: unknown, cookie?: string) =>
  new NextRequest(`http://localhost:3000${path}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", ...(cookie ? { cookie } : {}) },
    body: JSON.stringify(body),
  });

const get = (path: string, cookie?: string) =>
  new NextRequest(`http://localhost:3000${path}`, { headers: cookie ? { cookie } : {} });

let cookie = "";

// هويات المنتجات المضبوطة (كل سيناريو منتج مستقل لتبقى الحسابات قابلة للجمع حرفيًا)
let prodA = { id: "", stock: 10, sale: 1500, cost: 800 }; // بيع نقدي 3
let prodB = { id: "", stock: 5, sale: 2000, cost: 1200 }; // بيع آجل 5 ثم سداد كامل
let prodC = { id: "", stock: 2, sale: 1000, cost: 600 }; // محاولة بيع 3 → رفض
let prodD = { id: "", stock: 4, sale: 3000, cost: 1800 }; // بيع آجل 2 ثم إلغاء → استرجاع
let partyA = { id: "" };
let creditInvoice = { id: "", total: 0 }; // فاتورة prodB الآجلة (11500)
let voidableInvoice = { id: "" }; // فاتورة prodD الآجلة (تُلغى)

/** إنشاء منتج مضبوط عبر المسار نفسه (تكامل حقيقي — لا اختصار مباشر للقاعدة) */
async function mkProduct(sku: string, sale: number, cost: number, stock: number): Promise<string> {
  const res = await productsPost(
    post("/api/products", { name: `منتج ${sku}`, sku, salePrice: sale, costPrice: cost, stockQty: stock }, cookie),
  );
  expect(res.status).toBe(201);
  return (await res.json()).product.id as string;
}

beforeAll(async () => {
  cookie = await cookieOf();
  // مسح نظيف — الترتيب يراعي المفاتيح الأجنبية
  await db.journalLine.deleteMany();
  await db.journalEntry.deleteMany();
  await db.payment.deleteMany();
  await db.invoiceItem.deleteMany();
  await db.invoice.deleteMany();
  await db.party.deleteMany();
  await db.product.deleteMany();
  await db.expense.deleteMany();
  // عقد products POST: الأسعار تُستقبل ريالاً وتُخزَّن قروشاً (sarToQirsh) — لذا sale/100
  prodA.id = await mkProduct("INT-A", prodA.sale / 100, prodA.cost / 100, prodA.stock);
  prodB.id = await mkProduct("INT-B", prodB.sale / 100, prodB.cost / 100, prodB.stock);
  prodC.id = await mkProduct("INT-C", prodC.sale / 100, prodC.cost / 100, prodC.stock);
  prodD.id = await mkProduct("INT-D", prodD.sale / 100, prodD.cost / 100, prodD.stock);
});

// ─── تحقق zod الموحد [W1.7] — رفض 400 بكود INVALID_INPUT ────────────────────

describe("تحقق zod الموحد على مسارات الكتابة", () => {
  it("فواتير POST بلا بنود → 400 {code: INVALID_INPUT}", async () => {
    const res = await invoicesPost(post("/api/invoices", { items: [] }, cookie));
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
    expect(body.error).toContain("بند");
  });

  it("منتجات POST بلا اسم → 400 {code: INVALID_INPUT}", async () => {
    const res = await productsPost(post("/api/products", { name: "" }, cookie));
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
  });

  it("منتجات POST بسعر تكلفة سالب → 400 (لا سالب — كان يُقبل صمتًا قبل الموجة)", async () => {
    const res = await productsPost(
      post("/api/products", { name: "فاشل-سالب", sku: `NEG-${Date.now()}`, costPrice: -1000 }, cookie),
    );
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
  });

  it("سداد بمبلغ عشري → 400 (G2: المال قروش Int — لا تقريب صامت)", async () => {
    const res = await paymentsPost(post("/api/payments", { partyId: "x", amount: 115.5 }, cookie));
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
  });

  it("مصروف بمبلغ صفر → 400 {code: INVALID_INPUT}", async () => {
    const res = await expensesPost(post("/api/expenses", { title: "إيجار", amount: 0 }, cookie));
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
  });

  it("طرف بلا اسم → 400 {code: INVALID_INPUT}", async () => {
    const res = await partiesPost(post("/api/parties", { name: "  " }, cookie));
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.code).toBe("INVALID_INPUT");
  });
});

// ─── المنتجات ───────────────────────────────────────────────────────────────

describe("مسار المنتجات", () => {
  it("POST منتج صالح → 201 بأسعار قروش (sarToQirsh) وSKU تلقائي", async () => {
    const res = await productsPost(
      post("/api/products", { name: "تحقق-قروش", costPrice: 8, salePrice: 15, stockQty: 10 }, cookie),
    );
    expect(res.status).toBe(201);
    const { product } = await res.json();
    expect(product.costPrice).toBe(800);
    expect(product.salePrice).toBe(1500);
    expect(product.stockQty).toBe(10);
    expect(product.sku).toMatch(/^SKU-/);
  });

  it("POST بنفس SKU يدويًا → 409 تعارض", async () => {
    const res = await productsPost(
      post("/api/products", { name: "مكرر", sku: "DUP-INT-1", costPrice: 1, salePrice: 2 }, cookie),
    );
    expect(res.status).toBe(201);
    const second = await productsPost(
      post("/api/products", { name: "مكرر-2", sku: "DUP-INT-1", costPrice: 1, salePrice: 2 }, cookie),
    );
    expect(second.status).toBe(409);
  });

  it("PATCH تسوية جرد وحد طلب → 200 مطبّقة", async () => {
    const res = await productsPatch(patch("/api/products", { id: prodA.id, stockQty: 10, reorderLevel: 7 }, cookie));
    expect(res.status).toBe(200);
    const { product } = await res.json();
    expect(product.reorderLevel).toBe(7);
  });
});

// ─── الفواتير — الذرّية الكاملة: بنود + مخزون + قيد + ZATCA ─────────────────

describe("مسار الفواتير (نقطة البيع)", () => {
  it("POST بيع نقدي → 201 بختم ZATCA (uuid/icv/QR) ومجاميع محاسبية صحيحة", async () => {
    const res = await invoicesPost(
      post("/api/invoices", { items: [{ productId: prodA.id, qty: 3 }] }, cookie),
    );
    expect(res.status).toBe(201);
    const { invoice } = await res.json();
    // 3 × 1500 = 4500 + ضريبة 15% (675) = 5175 قروش
    expect(invoice.subtotal).toBe(4500);
    expect(invoice.taxAmount).toBe(675);
    expect(invoice.total).toBe(5175);
    expect(invoice.status).toBe("PAID");
    expect(invoice.paid).toBe(5175);
    expect(invoice.uuid).toMatch(/^[0-9a-f-]{36}$/);
    expect(invoice.icv).toBeGreaterThan(0);
    expect(typeof invoice.zatcaQr).toBe("string");
    expect(invoice.zatcaQr.length).toBeGreaterThan(0);
  });

  it("بعد البيع النقدي: المخزون نُقص ذرّيًا (10 → 7) وخصم مستوى المنتج", async () => {
    const p = await db.product.findUniqueOrThrow({ where: { id: prodA.id } });
    expect(p.stockQty).toBe(7);
  });

  it("GET فاتورة بالمعرف → 200 ببنودها وصورة QR", async () => {
    const inv = await db.invoice.findFirstOrThrow({ where: { items: { some: { productId: prodA.id } } } });
    const res = await invoicesGet(get(`/api/invoices?id=${inv.id}`, cookie));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.invoice.items).toHaveLength(1);
    expect(body.zatcaQrImage).toMatch(/^data:image\/png;base64,/);
  });

  it("POST كمية أكبر من المخزون → 400 والرصيد لم يُمس", async () => {
    const res = await invoicesPost(
      post("/api/invoices", { items: [{ productId: prodC.id, qty: 3 }] }, cookie),
    );
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.error).toContain("المخزون لا يكفي");
    const p = await db.product.findUniqueOrThrow({ where: { id: prodC.id } });
    expect(p.stockQty).toBe(2);
  });

  it("POST آجل بلا طرف → 400 (الذمم تتطلب طرفًا)", async () => {
    const res = await invoicesPost(
      post("/api/invoices", { items: [{ productId: prodB.id, qty: 1 }], credit: true }, cookie),
    );
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.error).toContain("البيع الآجل");
  });

  it("POST آجل بطرف → 201 UNPAID ببند ذمم مدينة كامل في القيد", async () => {
    const party = await partiesPost(post("/api/parties", { name: "عميل التكامل", phone: "0511111111" }, cookie));
    expect(party.status).toBe(201);
    partyA = { id: (await party.json()).party.id };

    const res = await invoicesPost(
      post("/api/invoices", { items: [{ productId: prodB.id, qty: 5 }], credit: true, partyId: partyA.id }, cookie),
    );
    expect(res.status).toBe(201);
    const { invoice } = await res.json();
    // 5 × 2000 = 10000 + 1500 ضريبة = 11500 قروش
    expect(invoice.total).toBe(11500);
    expect(invoice.status).toBe("UNPAID");
    expect(invoice.paid).toBe(0);
    creditInvoice = { id: invoice.id, total: invoice.total };

    const entry = await db.journalEntry.findFirstOrThrow({
      where: { refType: "INVOICE", refId: invoice.id },
      include: { lines: true },
    });
    const ar = entry.lines.find((l) => l.account === ACCOUNTS.AR);
    expect(ar?.debit).toBe(11500);
    const cash = entry.lines.find((l) => l.account === ACCOUNTS.CASH);
    expect(cash?.debit ?? 0).toBe(0);
  });

  it("PATCH إلغاء فاتورة آجلة → عكس كامل: مخزون مسترجع وحالة VOID وقيد تعويضي متوازن", async () => {
    // فاتورة prodD الآجلة (2 × 3000 = 6000 + 900 ضريبة = 6900)
    const res0 = await invoicesPost(
      post("/api/invoices", { items: [{ productId: prodD.id, qty: 2 }], credit: true, partyId: partyA.id }, cookie),
    );
    expect(res0.status).toBe(201);
    voidableInvoice = { id: (await res0.json()).invoice.id };

    const res = await invoicesPatch(patch("/api/invoices", { id: voidableInvoice.id }, cookie));
    expect(res.status).toBe(200);
    const p = await db.product.findUniqueOrThrow({ where: { id: prodD.id } });
    expect(p.stockQty).toBe(4); // 4 − 2 ثم +2 عند الإلغاء
    const inv = await db.invoice.findUniqueOrThrow({ where: { id: voidableInvoice.id } });
    expect(inv.status).toBe("VOID");
  });

  it("PATCH إلغاء فاتورة ملغاة مسبقًا → 409", async () => {
    const res = await invoicesPatch(patch("/api/invoices", { id: voidableInvoice.id }, cookie));
    expect(res.status).toBe(409);
  });

  it("POST سداد على فاتورة ملغاة → 409", async () => {
    const res = await paymentsPost(
      post("/api/payments", { invoiceId: voidableInvoice.id, amount: 1000 }, cookie),
    );
    expect(res.status).toBe(409);
  });
});

// ─── السدود — دورة حياة الذمم ───────────────────────────────────────────────

describe("مسار السدود", () => {
  it("POST سداد جزئي → 201 والفاتورة PARTIAL وقيد نقدي/ذمم متوازن", async () => {
    const res = await paymentsPost(
      post("/api/payments", { invoiceId: creditInvoice.id, partyId: partyA.id, amount: 5000 }, cookie),
    );
    expect(res.status).toBe(201);
    const inv = await db.invoice.findUniqueOrThrow({ where: { id: creditInvoice.id } });
    expect(inv.paid).toBe(5000);
    expect(inv.status).toBe("PARTIAL");
  });

  it("POST سداد يتجاوز المتبقي → 400", async () => {
    const res = await paymentsPost(
      post("/api/payments", { invoiceId: creditInvoice.id, amount: 999_999 }, cookie),
    );
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.error).toContain("يتجاوز المتبقي");
  });

  it("POST سداد التكميلي → الفاتورة PAID بالكامل", async () => {
    const res = await paymentsPost(
      post("/api/payments", { invoiceId: creditInvoice.id, amount: 6500 }, cookie),
    );
    expect(res.status).toBe(201);
    const inv = await db.invoice.findUniqueOrThrow({ where: { id: creditInvoice.id } });
    expect(inv.paid).toBe(11500);
    expect(inv.status).toBe("PAID");
  });
});

// ─── المصروفات ──────────────────────────────────────────────────────────────

describe("مسار المصروفات", () => {
  it("POST مصروف صالح → 201 بقيد مصروفات مدينة/نقدية دائنة", async () => {
    const res = await expensesPost(
      post("/api/expenses", { title: "إيجار التكامل", category: "إيجار", amount: 250.5 }, cookie),
    );
    expect(res.status).toBe(201);
    const { expense } = await res.json();
    expect(expense.amount).toBe(25050); // sarToQirsh
    const entry = await db.journalEntry.findFirstOrThrow({
      where: { refType: "EXPENSE", refId: expense.id },
      include: { lines: true },
    });
    const exp = entry.lines.find((l) => l.account === ACCOUNTS.EXPENSES);
    const cash = entry.lines.find((l) => l.account === ACCOUNTS.CASH);
    expect(exp?.debit).toBe(25050);
    expect(cash?.credit).toBe(25050);
  });
});

// ─── الخصائص المحاسبية — معيار القبول الأعلى ────────────────────────────────

describe("خاصية التوازن: كل قيد في القاعدة متوازن مساواةً تامة", () => {
  it("كل قيد debit == credit + المجموع العام متساوٍ", async () => {
    const entries = await db.journalEntry.findMany({ include: { lines: true } });
    expect(entries.length).toBeGreaterThan(0);
    let totalDebit = 0;
    let totalCredit = 0;
    for (const e of entries) {
      const d = e.lines.reduce((s, l) => s + l.debit, 0);
      const c = e.lines.reduce((s, l) => s + l.credit, 0);
      expect(d).toBe(c); // مساواة تامة — لا تسامح قروش واحد
      totalDebit += d;
      totalCredit += c;
    }
    expect(totalDebit).toBe(totalCredit);
  });
});

describe("خاصية المخزون: لا سالب أبدًا — البيع يُرفض قبل الخصم", () => {
  it("أرصدة نهائية مطابقة للمسار كاملًا: بيع/رفض/إلغاء دون أي شبح", async () => {
    const [a, b, c, d] = await Promise.all([
      db.product.findUniqueOrThrow({ where: { id: prodA.id } }),
      db.product.findUniqueOrThrow({ where: { id: prodB.id } }),
      db.product.findUniqueOrThrow({ where: { id: prodC.id } }),
      db.product.findUniqueOrThrow({ where: { id: prodD.id } }),
    ]);
    expect(a.stockQty).toBe(7); // 10 − 3 نقدي
    expect(b.stockQty).toBe(0); // 5 − 5 آجل مسدد
    expect(c.stockQty).toBe(2); // رفض البيع — لم يُمس
    expect(d.stockQty).toBe(4); // 4 − 2 ثم إلغاء أعاده
    // والخاصية العامة: لا منتج بمخزون سالب في القاعدة كلها
    const negatives = await db.product.count({ where: { stockQty: { lt: 0 } } });
    expect(negatives).toBe(0);
  });
});
