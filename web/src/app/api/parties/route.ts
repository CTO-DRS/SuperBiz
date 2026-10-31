import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { qirshToNumber } from "@/lib/money";
import { parseJsonWith, partyCreateSchema } from "@/lib/schemas"; // [W1.7/#41]
import { requireSession } from "@/lib/authGate"; // [W1.5/#39] القفل الثاني — يفرضه فاحص check_auth_gate

export const dynamic = "force-dynamic";

/** الأطراف — مع رصيد الذمم لكل طرف (فواتير آجلة غير مسددة − سدود) */
export async function GET(req: NextRequest) {
  await ensureSeed();
  const q = req.nextUrl.searchParams.get("q")?.trim() ?? "";

  const parties = await db.party.findMany({
    where: {
      archived: false,
      ...(q ? { name: { contains: q } } : {}),
    },
    orderBy: { createdAt: "asc" },
    include: {
      invoices: { where: { status: { in: ["UNPAID", "PARTIAL"] }, type: "SALE" }, select: { total: true, paid: true } },
    },
  });

  const rows = parties.map((p) => {
    const balance = p.invoices.reduce((s, inv) => s + (inv.total - inv.paid), 0);
    return {
      id: p.id, name: p.name, phone: p.phone, type: p.type, taxNumber: p.taxNumber,
      balance, balanceNum: qirshToNumber(balance),
      openInvoices: p.invoices.length,
    };
  });

  const totalAR = rows.reduce((s, r) => s + r.balance, 0);
  return NextResponse.json({ parties: rows, totalAR, totalARNum: qirshToNumber(totalAR) });
}

/** إضافة طرف */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const raw = await req.json().catch(() => null);
  const parsed = parseJsonWith(partyCreateSchema, raw); // [W1.7/#41]
  if (!parsed.ok) return parsed.res;
  const b = parsed.data;

  const party = await db.party.create({
    data: {
      name: b.name.trim().slice(0, 120),
      phone: b.phone ? String(b.phone).trim().slice(0, 30) : null,
      type: b.type ?? 0,
      taxNumber: b.taxNumber ? String(b.taxNumber).trim().slice(0, 20) : null,
    },
  });
  return NextResponse.json({ party }, { status: 201 });
}
