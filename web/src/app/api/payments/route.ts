import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { postJournal, ACCOUNTS } from "@/lib/journal";
import { parseJsonWith, paymentCreateSchema } from "@/lib/schemas"; // [W1.7/#41]
import { qirshToNumber } from "@/lib/money";
import { requireSession } from "@/lib/authGate"; // [W1.5/#39] القفل الثاني — يفرضه فاحص check_auth_gate

export const dynamic = "force-dynamic";

/** سدادات — قبض ذمم (مرتبط بفاتورة أو عام لطرف) */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  await ensureSeed();
  const raw = await req.json().catch(() => null);
  const parsed = parseJsonWith(paymentCreateSchema, raw); // [W1.7/#41] المبلغ قروش Int (G2)
  if (!parsed.ok) return parsed.res;
  const b = parsed.data;
  const invoiceId = b.invoiceId ?? null;
  const partyId = b.partyId ?? null;
  const amount = b.amount; // >0 عدد صحيح — مضمون بالمخطط

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
      // [فحص W1 / N1-b] إعادة قراءة الفاتورة **داخل** المعاملة — كان الكود يشير لمتغير
      // `invoice` خارج نطاق المعاملة (ReferenceError وقت التشغيل لكل سد مرتبط بفاتورة،
      // مقنّع بـignoreBuildErrors) — والآن القراءة الحديثة تمنع أيضاً سباق السداد المزدوج.
      const live = invoiceId ? await tx.invoice.findUnique({ where: { id: invoiceId } }) : null;
      if (invoiceId && !live) throw new Error("الفاتورة غير موجودة أثناء الحفظ");

      const payment = await tx.payment.create({
        data: {
          partyId,
          invoiceId,
          amount,
          direction: "IN",
          method: (b.method ?? "CASH").trim().slice(0, 12) || "CASH",
          note: b.note ? String(b.note).slice(0, 200) : null,
        },
      });

      if (invoiceId && live) {
        await tx.invoice.update({
          where: { id: invoiceId },
          data: {
            paid: { increment: amount },
            status: live.paid + amount >= live.total ? "PAID" : "PARTIAL",
          },
        });
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
