import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { postJournal, ACCOUNTS } from "@/lib/journal";
import { qirshToNumber, sarToQirsh } from "@/lib/money";
import { requireSession } from "@/lib/authGate"; // [W1.5/#39] القفل الثاني — يفرضه فاحص check_auth_gate

export const dynamic = "force-dynamic";

/** المصروفات — قائمة شهرية مع تجميع بالفئة */
export async function GET(req: NextRequest) {
  await ensureSeed();
  const month = req.nextUrl.searchParams.get("month"); // YYYY-MM اختياري

  const now = new Date();
  const start = month ? new Date(`${month}-01T00:00:00`) : new Date(now.getFullYear(), now.getMonth(), 1);
  const end = new Date(start);
  end.setMonth(end.getMonth() + 1);

  const rows = await db.expense.findMany({
    where: { date: { gte: start, lt: end } },
    orderBy: { date: "desc" },
  });
  const total = rows.reduce((s, e) => s + e.amount, 0);
  const byCategory = new Map<string, number>();
  for (const e of rows) byCategory.set(e.category, (byCategory.get(e.category) ?? 0) + e.amount);

  return NextResponse.json({
    expenses: rows,
    total,
    totalNum: qirshToNumber(total),
    byCategory: [...byCategory.entries()]
      .map(([category, amount]) => ({ category, amount, amountNum: qirshToNumber(amount) }))
      .sort((a, b) => b.amount - a.amount),
  });
}

/** إضافة مصروف — قيد: مصروفات مدينة / نقدية دائنة */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const body = await req.json().catch(() => null);
  const title = String(body?.title ?? "").trim();
  const amount = sarToQirsh(Number(body?.amount) || 0);
  if (!title) return NextResponse.json({ error: "عنوان المصروف مطلوب" }, { status: 400 });
  if (amount <= 0) return NextResponse.json({ error: "المبلغ يجب أن يكون أكبر من صفر" }, { status: 400 });

  try {
    const expense = await db.$transaction(async (tx) => {
      const created = await tx.expense.create({
        data: {
          title: title.slice(0, 120),
          category: String(body?.category ?? "عام").trim().slice(0, 40) || "عام",
          amount,
          date: new Date(),
          note: body?.note ? String(body.note).slice(0, 200) : null,
        },
      });
      await postJournal(tx, {
        date: new Date(),
        memo: `مصروف: ${created.title}`,
        refType: "EXPENSE",
        refId: created.id,
        lines: [
          { account: ACCOUNTS.EXPENSES, debit: amount, credit: 0 },
          { account: ACCOUNTS.CASH, debit: 0, credit: amount },
        ],
      });
      return created;
    });
    return NextResponse.json({ expense, message: "أُضيف المصروف وقُيد" }, { status: 201 });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "خطأ غير معروف";
    return NextResponse.json({ error: `فشل الحفظ: ${msg}` }, { status: 500 });
  }
}
