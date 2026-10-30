import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { qirshToNumber } from "@/lib/money";

export const dynamic = "force-dynamic";

/** التقارير — سلسلة يومية (مبيعات/أرباح/مصروفات) + ضريبة + أعلى المنتجات + الفئات + مزيج الدفع */
export async function GET(req: NextRequest) {
  await ensureSeed();
  const days = Math.min(90, Math.max(7, Number(req.nextUrl.searchParams.get("days")) || 30));
  const now = new Date();
  const start = new Date(now);
  start.setDate(start.getDate() - (days - 1));
  start.setHours(0, 0, 0, 0);

  const notVoid = { status: { not: "VOID" } };

  const [invoices, expenses, items, taxAgg] = await Promise.all([
    db.invoice.findMany({
      where: { ...notVoid, type: "SALE", date: { gte: start } },
      select: { date: true, total: true, costTotal: true, taxAmount: true, credit: true, paid: true },
    }),
    db.expense.findMany({ where: { date: { gte: start } }, select: { date: true, amount: true } }),
    db.invoiceItem.groupBy({
      by: ["desc"],
      where: { invoice: { ...notVoid, type: "SALE", date: { gte: start } } },
      _sum: { lineTotal: true, qty: true },
      orderBy: { _sum: { lineTotal: "desc" } },
      take: 8,
    }),
    db.invoice.aggregate({
      where: { ...notVoid, type: "SALE", date: { gte: start } },
      _sum: { taxAmount: true, subtotal: true, total: true, costTotal: true },
    }),
  ]);

  // الفئات عبر ربط البنود بمنتجاتها
  const catRows = await db.invoiceItem.findMany({
    where: { invoice: { ...notVoid, type: "SALE", date: { gte: start } } },
    select: { lineTotal: true, product: { select: { category: true } } },
  });
  const byCategory = new Map<string, number>();
  for (const row of catRows) {
    const cat = row.product?.category ?? "عام";
    byCategory.set(cat, (byCategory.get(cat) ?? 0) + row.lineTotal);
  }

  // مزيج الدفع
  let cashSales = 0, creditSales = 0;
  for (const inv of invoices) {
    if (inv.credit) creditSales += inv.total - inv.paid + inv.paid * 0; // إجمالي الفاتورة الآجلة
    if (inv.credit) cashSales += inv.paid;
    else cashSales += inv.total;
  }
  creditSales = invoices.filter((i) => i.credit).reduce((s, i) => s + i.total, 0) - invoices.filter((i) => i.credit).reduce((s, i) => s + i.paid, 0);

  // السلسلة اليومية
  const series: { date: string; sales: number; profit: number; expenses: number }[] = [];
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now);
    d.setDate(d.getDate() - i);
    d.setHours(0, 0, 0, 0);
    const next = new Date(d);
    next.setDate(next.getDate() + 1);
    let sales = 0, profit = 0;
    for (const inv of invoices) {
      const dt = new Date(inv.date);
      if (dt >= d && dt < next) {
        sales += inv.total;
        profit += inv.total - inv.taxAmount - inv.costTotal;
      }
    }
    let exp = 0;
    for (const e of expenses) {
      const dt = new Date(e.date);
      if (dt >= d && dt < next) exp += e.amount;
    }
    series.push({
      date: `${d.getDate()}/${d.getMonth() + 1}`,
      sales: qirshToNumber(sales),
      profit: qirshToNumber(profit),
      expenses: qirshToNumber(exp),
    });
  }

  const totalSales = taxAgg._sum.total ?? 0;
  const totalTax = taxAgg._sum.taxAmount ?? 0;
  const totalProfit = totalSales - totalTax - (taxAgg._sum.costTotal ?? 0);

  return NextResponse.json({
    days,
    series,
    summary: {
      sales: qirshToNumber(totalSales),
      netSales: qirshToNumber(taxAgg._sum.subtotal ?? 0),
      tax: qirshToNumber(totalTax),
      profit: qirshToNumber(totalProfit),
      cost: qirshToNumber(taxAgg._sum.costTotal ?? 0),
    },
    topProducts: items.map((t) => ({
      name: t.desc,
      sales: qirshToNumber(t._sum.lineTotal ?? 0),
      qty: t._sum.qty ?? 0,
    })),
    categories: [...byCategory.entries()]
      .map(([name, amount]) => ({ name, value: qirshToNumber(amount) }))
      .sort((a, b) => b.value - a.value),
    paymentMix: {
      cash: qirshToNumber(cashSales),
      credit: qirshToNumber(Math.max(0, creditSales)),
    },
  });
}
