import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { postJournal, ACCOUNTS } from "@/lib/journal";
import { buildZatcaQrBase64, invoiceUuid, qrDataUrl } from "@/lib/zatca";
import { qirshToNumber } from "@/lib/money";
import { requireSession } from "@/lib/authGate"; // [W1.5/#39] القفل الثاني — يفرضه فاحص check_auth_gate

export const dynamic = "force-dynamic";

/** قائمة الفواتير — أو فاتورة واحدة مع بنودها وQR عبر ?id= */
export async function GET(req: NextRequest) {
  await ensureSeed();
  const id = req.nextUrl.searchParams.get("id");

  if (id) {
    const invoice = await db.invoice.findUnique({
      where: { id },
      include: { items: true, party: { select: { name: true, phone: true } } },
    });
    if (!invoice) return NextResponse.json({ error: "الفاتورة غير موجودة" }, { status: 404 });
    return NextResponse.json({ invoice, zatcaQrImage: invoice.zatcaQr ? await qrDataUrl(invoice.zatcaQr) : null });
  }

  const status = req.nextUrl.searchParams.get("status");
  const invoices = await db.invoice.findMany({
    where: status && status !== "ALL" ? { status } : {},
    orderBy: { date: "desc" },
    take: 100,
    include: { party: { select: { name: true } } },
  });
  return NextResponse.json({ invoices });
}

/** إنشاء فاتورة بيع (نقطة البيع) — ذرّية كاملة: بنود + مخزون + قيد مزدوج + ختم ZATCA */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  await ensureSeed();
  const body = await req.json().catch(() => null);
  const items: Array<{ productId?: string; desc?: string; qty?: number; unitPrice?: number; discount?: number }> =
    Array.isArray(body?.items) ? body.items : [];

  const clean = items
    .map((it) => ({
      productId: it.productId ? String(it.productId) : null,
      desc: String(it.desc ?? "").trim() || "بند",
      qty: Math.max(0, Number(it.qty) || 0),
      unitPrice: Math.max(0, Math.round(Number(it.unitPrice) || 0)),
      discount: Math.max(0, Math.round(Number(it.discount) || 0)),
    }))
    .filter((it) => it.qty > 0);

  if (clean.length === 0) {
    return NextResponse.json({ error: "أضف بندًا واحدًا على الأقل بكمية أكبر من صفر" }, { status: 400 });
  }

  const credit = Boolean(body?.credit);
  const taxRate = Math.min(100, Math.max(0, Number(body?.taxRate ?? 15)));
  const partyId = body?.partyId ? String(body.partyId) : null;

  if (credit && !partyId) {
    return NextResponse.json({ error: "البيع الآجل يتطلب اختيار العميل (ذمم مدينة)" }, { status: 400 });
  }

  // التحقق من المنتجات والأسعار (المصدر: قاعدة البيانات — لا ثقة بالواجهة في الأسعار)
  const productIds = clean.map((i) => i.productId).filter((x): x is string => Boolean(x));
  const products = productIds.length ? await db.product.findMany({ where: { id: { in: productIds } } }) : [];
  const productMap = new Map(products.map((p) => [p.id, p]));

  for (const it of clean) {
    if (!it.productId) continue;
    const p = productMap.get(it.productId);
    if (!p) return NextResponse.json({ error: `منتج غير موجود` }, { status: 400 });
    if (p.archived) return NextResponse.json({ error: `المنتج مؤرشف: ${p.name}` }, { status: 400 });
    if (p.stockQty < it.qty) {
      return NextResponse.json(
        { error: `المخزون لا يكفي: ${p.name} (المتاح ${p.stockQty})` },
        { status: 400 }
      );
    }
    it.unitPrice = p.salePrice; // سعر البيع المعتمد
    if (!it.desc || it.desc === "بند") it.desc = p.name;
  }

  const subtotal = clean.reduce((s, it) => s + it.unitPrice * it.qty - it.discount, 0);
  const costTotal = clean.reduce((s, it) => {
    const p = it.productId ? productMap.get(it.productId) : undefined;
    return s + (p ? Math.round(p.costPrice * it.qty) : 0);
  }, 0);
  const taxAmount = Math.round((subtotal * taxRate) / 100);
  const total = subtotal + taxAmount;
  const paid = credit ? 0 : total;
  const status = credit ? "UNPAID" : "PAID";

  const date = new Date();

  try {
    const created = await db.$transaction(async (tx) => {
      // [W1.4 / #38] رقم تسلسلي + ICV **داخل** المعاملة (عقد الترقيم من قاعدة البيانات نفسها)
      // — كان يُحسب قبلها فينتج طلبان متوازيان ICV مكرراً (كسر عدّاد ZATCA التزايدي).
      const seq = (await tx.invoice.count()) + 1;
      const number = `INV-${1000 + seq}`;
      const icv = seq;
      const clash = await tx.invoice.findUnique({ where: { number } });
      const finalNumber = clash ? `INV-${1000 + seq}-${Date.now().toString(36).slice(-4).toUpperCase()}` : number;

      const zatcaQr = buildZatcaQrBase64({ date, totalQirsh: total, vatQirsh: taxAmount });

      const invoice = await tx.invoice.create({
        data: {
          number: finalNumber,
          partyId,
          type: "SALE",
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
          currency: "SAR",
          uuid: invoiceUuid(),
          icv,
          zatcaQr,
          items: {
            create: clean.map((it) => ({
              productId: it.productId,
              desc: it.desc,
              qty: it.qty,
              unitPrice: it.unitPrice,
              discount: it.discount,
              lineTotal: it.unitPrice * it.qty - it.discount,
            })),
          },
        },
      });

      // خصم المخزون — ذرّي (عقد v18: المخزون الكلي)
      for (const it of clean) {
        if (!it.productId) continue;
        await tx.product.update({
          where: { id: it.productId },
          data: { stockQty: { decrement: it.qty } },
        });
      }

      // القيد المزدوج — متوازن حتميًا
      await postJournal(tx, {
        date,
        memo: `فاتورة بيع ${finalNumber}${credit ? " (آجل)" : ""}`,
        refType: "INVOICE",
        refId: invoice.id,
        lines: [
          { account: ACCOUNTS.CASH, debit: paid, credit: 0 },
          { account: ACCOUNTS.AR, debit: total - paid, credit: 0 },
          { account: ACCOUNTS.SALES, debit: 0, credit: subtotal },
          { account: ACCOUNTS.VAT, debit: 0, credit: taxAmount },
          { account: ACCOUNTS.COGS, debit: costTotal, credit: 0 },
          { account: ACCOUNTS.INVENTORY, debit: 0, credit: costTotal },
        ],
      });

      return invoice;
    });

    return NextResponse.json(
      {
        invoice: created,
        zatcaQrImage: created.zatcaQr ? await qrDataUrl(created.zatcaQr) : null,
        message: `تم إصدار الفاتورة ${created.number}`,
      },
      { status: 201 }
    );
  } catch (e) {
    const msg = e instanceof Error ? e.message : "خطأ غير معروف";
    return NextResponse.json({ error: `فشل الحفظ: ${msg}` }, { status: 500 });
  }
}

/** إلغاء آمن — قيد تعويضي + إرجاع المخزون (انعكاس أثره كما في العقد) */
export async function PATCH(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const body = await req.json().catch(() => null);
  if (!body?.id) return NextResponse.json({ error: "id مطلوب" }, { status: 400 });

  const invoice = await db.invoice.findUnique({ where: { id: String(body.id) }, include: { items: true } });
  if (!invoice) return NextResponse.json({ error: "الفاتورة غير موجودة" }, { status: 404 });
  if (invoice.status === "VOID") return NextResponse.json({ error: "الفاتورة ملغاة مسبقًا" }, { status: 409 });

  try {
    await db.$transaction(async (tx) => {
      await tx.invoice.update({
        where: { id: invoice.id },
        data: { status: "VOID" },
      });

      // إرجاع/سحب المخزون حسب النوع
      for (const it of invoice.items) {
        if (!it.productId) continue;
        const delta = invoice.type === "RETURN" ? -it.qty : it.qty;
        await tx.product.update({
          where: { id: it.productId },
          data: { stockQty: { increment: delta } },
        });
      }

      // قيد تعويضي — عكس الأثر الأصلي بدقة (بصفي VOID)
      const arPart = invoice.total - invoice.paid;
      if (invoice.type === "RETURN") {
        await postJournal(tx, {
          date: new Date(),
          memo: `إلغاء مرتجع ${invoice.number}`,
          refType: "VOID",
          refId: invoice.id,
          lines: [
            { account: ACCOUNTS.CASH, debit: invoice.total, credit: 0 },
            { account: ACCOUNTS.SALES, debit: 0, credit: invoice.subtotal },
            { account: ACCOUNTS.VAT, debit: 0, credit: invoice.taxAmount },
            { account: ACCOUNTS.INVENTORY, debit: 0, credit: invoice.costTotal },
            { account: ACCOUNTS.COGS, debit: invoice.costTotal, credit: 0 },
          ],
        });
      } else {
        await postJournal(tx, {
          date: new Date(),
          memo: `إلغاء فاتورة ${invoice.number}`,
          refType: "VOID",
          refId: invoice.id,
          lines: [
            { account: ACCOUNTS.CASH, debit: 0, credit: invoice.paid },
            { account: ACCOUNTS.AR, debit: 0, credit: arPart },
            { account: ACCOUNTS.SALES, debit: invoice.subtotal, credit: 0 },
            { account: ACCOUNTS.VAT, debit: invoice.taxAmount, credit: 0 },
            { account: ACCOUNTS.INVENTORY, debit: invoice.costTotal, credit: 0 },
            { account: ACCOUNTS.COGS, debit: 0, credit: invoice.costTotal },
          ],
        });
      }
    });
    return NextResponse.json({ ok: true, message: `أُلغيت الفاتورة ${invoice.number} وعُكس أثرها بالكامل` });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "خطأ غير معروف";
    return NextResponse.json({ error: `فشل الإلغاء: ${msg}` }, { status: 500 });
  }
}
