/**
 * البذر التلقائي — بيانات تجريبية حية للبوابة (تعمل مرة واحدة).
 * كل فاتورة تُنشأ بذرّية كاملة: مخزون + قيد مزدوج متوازن + ختم ZATCA — مطابقة لمسار حفظ الفاتورة في التطبيق.
 */
import { db } from "@/lib/db";
import { postJournal, ACCOUNTS } from "@/lib/journal";
import { buildZatcaQrBase64, invoiceUuid } from "@/lib/zatca";

// مولد حتمي (بذرة ثابتة) — نتائج متطابقة بين التشغيلات
function mulberry32(seed: number) {
  let a = seed;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const PRODUCTS = [
  { name: "أرز بسمتي 5 كجم", sku: "GRN-001", category: "بقالة", unit: "كيس", costPrice: 4200, salePrice: 5500, stockQty: 90, reorderLevel: 10, barcode: "6281000000011" },
  { name: "زيت ذرة 1.5 لتر", sku: "GRN-002", category: "بقالة", unit: "عبوة", costPrice: 1800, salePrice: 2400, stockQty: 110, reorderLevel: 12, barcode: "6281000000028" },
  { name: "سكر ناعم 2 كجم", sku: "GRN-003", category: "بقالة", unit: "كيس", costPrice: 950, salePrice: 1300, stockQty: 140, reorderLevel: 20, barcode: "6281000000035" },
  { name: "حليب طويل الأجل 1 لتر", sku: "DRY-001", category: "ألبان", unit: "علبة", costPrice: 520, salePrice: 700, stockQty: 200, reorderLevel: 30, barcode: "6281000000042" },
  { name: "جبن شيدر 200 جم", sku: "DRY-002", category: "ألبان", unit: "قطعة", costPrice: 850, salePrice: 1150, stockQty: 90, reorderLevel: 15, barcode: "6281000000059" },
  { name: "ماء قوارير 40×330 مل", sku: "BEV-001", category: "مشروبات", unit: "كرتون", costPrice: 2200, salePrice: 2900, stockQty: 120, reorderLevel: 15, barcode: "6281000000066" },
  { name: "عصير برتقال 1 لتر", sku: "BEV-002", category: "مشروبات", unit: "عبوة", costPrice: 700, salePrice: 1000, stockQty: 130, reorderLevel: 18, barcode: "6281000000073" },
  { name: "مناديل ورقية 5 عبوات", sku: "HOM-001", category: "منزلية", unit: "باكو", costPrice: 1100, salePrice: 1500, stockQty: 70, reorderLevel: 10, barcode: "6281000000080" },
  { name: "منظف أرضيات 2 لتر", sku: "HOM-002", category: "منزلية", unit: "عبوة", costPrice: 1300, salePrice: 1800, stockQty: 55, reorderLevel: 8, barcode: "6281000000097" },
  { name: "شامبو عائلي 750 مل", sku: "PCARE-001", category: "عناية", unit: "عبوة", costPrice: 1600, salePrice: 2200, stockQty: 75, reorderLevel: 10, barcode: "6281000000103" },
  { name: "معجون أسنان 120 مل", sku: "PCARE-002", category: "عناية", unit: "أنبوب", costPrice: 900, salePrice: 1250, stockQty: 8, reorderLevel: 12, barcode: "6281000000110" },
  { name: "شاي أحمر 100 كيس", sku: "BEV-003", category: "مشروبات", unit: "علبة", costPrice: 1450, salePrice: 1950, stockQty: 6, reorderLevel: 12, barcode: "6281000000127" },
];

const PARTIES = [
  { name: "شركة النخبة للتجارة", phone: "0501234567", type: 0, taxNumber: "310234567800003" },
  { name: "مؤسسة الوفاء للمقاولات", phone: "0552345678", type: 0, taxNumber: "310345678900003" },
  { name: "عبدالله محمد العتيبي", phone: "0563456789", type: 0 },
  { name: "مطعم الضيافة", phone: "0534567890", type: 2, taxNumber: "310456789000003" },
  { name: "سارة فهد القحطاني", phone: "0545678901", type: 0 },
  { name: "مورد الخليج للمواد الغذائية", phone: "0525678902", type: 1 },
];

const EXPENSE_SEEDS = [
  { title: "إيجار المحل", category: "إيجار", amount: 150000 },
  { title: "فاتورة كهرباء", category: "مرافق", amount: 18500 },
  { title: "أجور مساعد جزئي", category: "رواتب", amount: 60000 },
  { title: "أكياس تغليف", category: "تشغيل", amount: 9500 },
  { title: "صيانة ثلاجة العصائر", category: "صيانة", amount: 12500 },
  { title: "اشتراك إنترنت", category: "مرافق", amount: 6000 },
];

export async function isSeeded(): Promise<boolean> {
  return (await db.product.count()) > 0;
}

export async function ensureSeed(): Promise<void> {
  if (await isSeeded()) return;
  const rnd = mulberry32(20261030);
  const now = new Date();

  // 1) المنتجات والأطراف
  const products = await Promise.all(
    PRODUCTS.map((p) => db.product.create({ data: p }))
  );
  const parties = await Promise.all(PARTIES.map((p) => db.party.create({ data: p })));
  const customers = parties.filter((p) => p.type !== 1);

  // 2) فواتير موزعة على آخر 30 يومًا
  let invoiceSeq = 1;
  let icv = 0;
  const remainingStock = new Map<number, number>(PRODUCTS.map((p, i) => [i, p.stockQty]));
  for (let day = 29; day >= 0; day--) {
    const invoicesToday = 1 + Math.floor(rnd() * 3); // 1..3 يوميًا
    for (let k = 0; k < invoicesToday; k++) {
      const date = new Date(now);
      date.setDate(date.getDate() - day);
      date.setHours(9 + Math.floor(rnd() * 10), Math.floor(rnd() * 60), 0, 0);

      // سطران إلى أربعة — داخل حدود المخزون المتاح (لا مخزون سالب أبدًا)
      const lineCount = 2 + Math.floor(rnd() * 3);
      const picked = new Set<number>();
      while (picked.size < lineCount) picked.add(Math.floor(rnd() * products.length));
      const items = [...picked]
        .map((idx) => {
          const avail = remainingStock.get(idx) ?? 0;
          if (avail <= 0) return null;
          const qty = Math.min(avail, 1 + Math.floor(rnd() * 5));
          remainingStock.set(idx, avail - qty);
          return { product: products[idx], qty };
        })
        .filter((x): x is { product: (typeof products)[number]; qty: number } => x !== null);

      if (items.length === 0) continue;

      let subtotal = 0;
      let costTotal = 0;
      const itemRows = items.map(({ product, qty }) => {
        const lineTotal = product.salePrice * qty;
        subtotal += lineTotal;
        costTotal += product.costPrice * qty;
        return { product, qty, lineTotal };
      });

      const credit = rnd() < 0.3; // 30% آجل
      const taxRate = 15;
      const taxAmount = Math.round((subtotal * taxRate) / 100);
      const total = subtotal + taxAmount;
      const isReturn = rnd() < 0.06; // 6% مرتجع
      const number = `${isReturn ? "RET" : "INV"}-${String(1000 + invoiceSeq)}`;
      invoiceSeq++;

      // حالة السداد
      let paid = 0;
      let status = "UNPAID";
      if (isReturn) {
        paid = total;
        status = "PAID";
      } else if (!credit) {
        paid = total;
        status = "PAID";
      } else {
        const r = rnd();
        if (r < 0.5) {
          paid = total;
          status = "PAID";
        } else if (r < 0.8) {
          paid = Math.round(total / 2);
          status = "PARTIAL";
        }
      }

      icv++;
      const uuid = invoiceUuid();
      const zatcaQr = buildZatcaQrBase64({
        date,
        totalQirsh: total,
        vatQirsh: taxAmount,
      });

      await db.$transaction(async (tx) => {
        const invoice = await tx.invoice.create({
          data: {
            number,
            partyId: customers[Math.floor(rnd() * customers.length)].id,
            type: isReturn ? "RETURN" : "SALE",
            credit,
            date,
            subtotal,
            discount: 0,
            taxRate,
            taxAmount,
            total,
            paid,
            costTotal,
            status,
            uuid,
            icv,
            zatcaQr,
            items: {
              create: itemRows.map(({ product, qty, lineTotal }) => ({
                productId: product.id,
                desc: product.name,
                qty,
                unitPrice: product.salePrice,
                discount: 0,
                lineTotal,
              })),
            },
          },
        });

        if (!isReturn) {
          // خصم المخزون — ذرّي مع الحفظ (عقد v18: المخزون الكلي)
          for (const { product, qty } of itemRows) {
            await tx.product.update({
              where: { id: product.id },
              data: { stockQty: { decrement: qty } },
            });
          }
        }

        // القيد المزدوج — متوازن حتميًا
        const cashPart = paid;
        const arPart = total - paid;
        if (isReturn) {
          await postJournal(tx, {
            date,
            memo: `مرتجع ${number}`,
            refType: "INVOICE",
            refId: invoice.id,
            lines: [
              { account: ACCOUNTS.SALES, debit: subtotal, credit: 0 },
              { account: ACCOUNTS.VAT, debit: taxAmount, credit: 0 },
              { account: ACCOUNTS.CASH, debit: 0, credit: total },
              { account: ACCOUNTS.INVENTORY, debit: costTotal, credit: 0 },
              { account: ACCOUNTS.COGS, debit: 0, credit: costTotal },
            ],
          });
        } else {
          await postJournal(tx, {
            date,
            memo: `فاتورة بيع ${number}`,
            refType: "INVOICE",
            refId: invoice.id,
            lines: [
              { account: ACCOUNTS.CASH, debit: cashPart, credit: 0 },
              { account: ACCOUNTS.AR, debit: arPart, credit: 0 },
              { account: ACCOUNTS.SALES, debit: 0, credit: subtotal },
              { account: ACCOUNTS.VAT, debit: 0, credit: taxAmount },
              { account: ACCOUNTS.COGS, debit: costTotal, credit: 0 },
              { account: ACCOUNTS.INVENTORY, debit: 0, credit: costTotal },
            ],
          });
        }
      });
    }
  }

  // 3) سدود جزئية على الفواتير الجزئية (تحصيلات)
  const partials = await db.invoice.findMany({ where: { status: "PARTIAL" } });
  for (const inv of partials.slice(0, 6)) {
    const remaining = inv.total - inv.paid;
    const amount = Math.round(remaining / 2);
    const payDate = new Date(inv.date);
    payDate.setDate(payDate.getDate() + 3);
    await db.$transaction(async (tx) => {
      const payment = await tx.payment.create({
        data: {
          partyId: inv.partyId,
          invoiceId: inv.id,
          amount,
          direction: "IN",
          method: "CASH",
          date: payDate,
          note: `تحصيل على ${inv.number}`,
        },
      });
      await tx.invoice.update({
        where: { id: inv.id },
        data: {
          paid: { increment: amount },
          status: inv.paid + amount >= inv.total ? "PAID" : "PARTIAL",
        },
      });
      await postJournal(tx, {
        date: payDate,
        memo: `تحصيل ${inv.number}`,
        refType: "PAYMENT",
        refId: payment.id,
        lines: [
          { account: ACCOUNTS.CASH, debit: amount, credit: 0 },
          { account: ACCOUNTS.AR, debit: 0, credit: amount },
        ],
      });
    });
  }

  // 4) مصروفات موزعة على الشهر (بمقياس متجر صغير — كل 3 أيام)
  for (let day = 29; day >= 0; day -= 3) {
    const seed = EXPENSE_SEEDS[Math.floor(rnd() * EXPENSE_SEEDS.length)];
    const date = new Date(now);
    date.setDate(date.getDate() - day);
    const jitter = 1 + (rnd() - 0.5) * 0.15;
    const amount = Math.round((seed.amount * jitter) / 100) * 100;
    await db.$transaction(async (tx) => {
      const expense = await tx.expense.create({
        data: { title: seed.title, category: seed.category, amount, date, note: "بيانات تجريبية" },
      });
      await postJournal(tx, {
        date,
        memo: `مصروف: ${seed.title}`,
        refType: "EXPENSE",
        refId: expense.id,
        lines: [
          { account: ACCOUNTS.EXPENSES, debit: amount, credit: 0 },
          { account: ACCOUNTS.CASH, debit: 0, credit: amount },
        ],
      });
    });
  }
}
