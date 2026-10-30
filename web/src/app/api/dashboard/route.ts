import { NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { bizPulse, qirshToNumber } from "@/lib/money";

export const dynamic = "force-dynamic";

/** لوحة القيادة — كل الأرقام قروش وتُعرض ريالًا في الواجهة */
export async function GET() {
  await ensureSeed();

  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const weekStart = new Date(now);
  weekStart.setDate(weekStart.getDate() - 6);
  weekStart.setHours(0, 0, 0, 0);

  const notVoid = { status: { not: "VOID" } };

  const [monthInvoices, weekInvoices, expensesMonth, journalSums, lowStock, productAgg, recentInvoices, topItems] =
    await Promise.all([
      db.invoice.findMany({
        where: { ...notVoid, type: "SALE", date: { gte: monthStart } },
        select: { total: true, costTotal: true, taxAmount: true },
      }),
      db.invoice.findMany({
        where: { ...notVoid, type: "SALE", date: { gte: weekStart } },
        select: { date: true, total: true, costTotal: true },
        orderBy: { date: "asc" },
      }),
      db.expense.aggregate({
        where: { date: { gte: monthStart } },
        _sum: { amount: true },
      }),
      db.journalLine.groupBy({
        by: ["account"],
        _sum: { debit: true, credit: true },
        where: { account: { in: ["1010", "1200", "2100"] } },
      }),
      db.product.findMany({
        where: { archived: false, stockQty: { lte: 10 } },
        select: { id: true, name: true, stockQty: true, reorderLevel: true, unit: true },
        orderBy: { stockQty: "asc" },
        take: 8,
      }),
      db.product.aggregate({
        where: { archived: false },
        _count: true,
      }),
      db.invoice.findMany({
        where: notVoid,
        orderBy: { date: "desc" },
        take: 6,
        select: {
          id: true, number: true, date: true, total: true, status: true, type: true, credit: true,
          party: { select: { name: true } },
        },
      }),
      db.invoiceItem.groupBy({
        by: ["desc"],
        where: { invoice: { ...notVoid, type: "SALE", date: { gte: weekStart } } },
        _sum: { lineTotal: true, qty: true },
        orderBy: { _sum: { lineTotal: "desc" } },
        take: 5,
      }),
    ]);

  // مبيعات/أرباح الشهر
  let monthSales = 0, monthProfit = 0;
  for (const inv of monthInvoices) {
    monthSales += inv.total;
    monthProfit += inv.total - inv.taxAmount - inv.costTotal;
  }
  const monthExpenses = expensesMonth._sum.amount ?? 0;

  // أرصدة الدفتر (1010 نقدية · 1200 مدينة · 2100 دائنة)
  let cash = 0, receivable = 0, payable = 0;
  for (const row of journalSums) {
    const net = (row._sum.debit ?? 0) - (row._sum.credit ?? 0);
    if (row.account === "1010") cash = net;
    if (row.account === "1200") receivable = Math.max(0, net);
    if (row.account === "2100") payable = Math.max(0, -net);
  }

  // سلسلة 7 أيام
  const series: { date: string; sales: number; profit: number }[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(now);
    d.setDate(d.getDate() - i);
    d.setHours(0, 0, 0, 0);
    const next = new Date(d);
    next.setDate(next.getDate() + 1);
    let sales = 0, profit = 0;
    for (const inv of weekInvoices) {
      const dt = new Date(inv.date);
      if (dt >= d && dt < next) {
        sales += inv.total;
        profit += inv.total - inv.costTotal;
      }
    }
    series.push({
      date: `${d.getDate()}/${d.getMonth() + 1}`,
      sales: qirshToNumber(sales),
      profit: qirshToNumber(profit),
    });
  }

  // قيمة المخزون بسعر التكلفة
  const stockRows = await db.product.findMany({
    where: { archived: false },
    select: { stockQty: true, costPrice: true, salePrice: true },
  });
  const stockValue = stockRows.reduce((s, p) => s + Math.round(p.stockQty * p.costPrice), 0);
  const stockRetail = stockRows.reduce((s, p) => s + Math.round(p.stockQty * p.salePrice), 0);

  const pulse = bizPulse({
    receivable,
    monthSales,
    lowStockCount: lowStock.length,
    productCount: productAgg._count,
    monthExpenses,
  });

  return NextResponse.json({
    monthSales, monthProfit, monthExpenses, cash, receivable, payable,
    stockValue, stockRetail,
    lowStockCount: lowStock.length,
    productCount: productAgg._count,
    pulse,
    series,
    lowStock,
    recentInvoices,
    topProducts: topItems.map((t) => ({
      name: t.desc,
      sales: qirshToNumber(t._sum.lineTotal ?? 0),
      qty: t._sum.qty ?? 0,
    })),
    monthSalesNum: qirshToNumber(monthSales),
    monthProfitNum: qirshToNumber(monthProfit),
    monthExpensesNum: qirshToNumber(monthExpenses),
    cashNum: qirshToNumber(cash),
    receivableNum: qirshToNumber(receivable),
    payableNum: qirshToNumber(payable),
    stockValueNum: qirshToNumber(stockValue),
  });
}
