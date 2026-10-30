import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { postJournal, ACCOUNTS } from "@/lib/journal";
import { qirshToNumber } from "@/lib/money";

export const dynamic = "force-dynamic";

/** سدادات — قبض ذمم (مرتبط بفاتورة أو عام لطرف) */
export async function POST(req: NextRequest) {
  await ensureSeed();
  const body = await req.json().catch(() => null);
  const invoiceId = body?.invoiceId ? String(body.invoiceId) : null;
  const partyId = body?.partyId ? String(body.partyId) : null;
  const amount = Math.round(Number(body?.amount) || 0);

  if (amount <= 0) return NextResponse.json({ error: "المبلغ يجب أن يكون أكبر من صفر" }, { status: 400 });

  if (invoiceId) {
    const invoice = await db.invoice.findUnique({ where: { id: invoiceId } });
    if (!invoice) return NextResponse.json({ error: "الفاتورة غير موجودة" }, { status: 404 });
    if (invoice.status === "VOID") return NextResponse.json({ error: "لا سداد على فاتورة ملغاة" }, { status: 409 });
    const remaining = invoice.total - invoice.paid;
    if (remaining <= 0) return NextResponse.json({ error: "الفاتورة مسددة بالكامل" }, { status: 409 });
    if (amount > remaining) {
      return NextResponse.json(
        { error: `المبلغ يتجاوز المتبقي (${qirshToNumber(remaining)} ر.س)` },
        { status: 400 }
      );
    }
  } else if (!partyId) {
    return NextResponse.json({ error: "يتطلب سدادًا مرتبطًا بفاتورة أو طرف" }, { status: 400 });
  }

  try {
    const result = await db.$transaction(async (tx) => {
      const payment = await tx.payment.create({
        data: {
          partyId,
          invoiceId,
          amount,
          direction: "IN",
          method: String(body?.method ?? "CASH").slice(0, 12) || "CASH",
          note: body?.note ? String(body.note).slice(0, 200) : null,
        },
      });

      if (invoiceId) {
        const inv = await tx.invoice.update({
          where: { id: invoiceId },
          data: {
            paid: { increment: amount },
            status: invoice!.paid + amount >= invoice!.total ? "PAID" : "PARTIAL",
          },
        });
        void inv;
      }

      await postJournal(tx, {
        date: new Date(),
        memo: `تحصيل${invoiceId ? "" : " عام"}`,
        refType: "PAYMENT",
        refId: payment.id,
        lines: [
          { account: ACCOUNTS.CASH, debit: amount, credit: 0 },
          { account: ACCOUNTS.AR, debit: 0, credit: amount },
        ],
      });

      return payment;
    });

    return NextResponse.json({ payment: result, message: `تم تحصيل ${qirshToNumber(amount)} ر.س` }, { status: 201 });
  } catch (e) {
    const msg = e instanceof Error ? e.message : "خطأ غير معروف";
    return NextResponse.json({ error: `فشل التحصيل: ${msg}` }, { status: 500 });
  }
}
