import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { sarToQirsh } from "@/lib/money";
import { requireSession } from "@/lib/authGate"; // [W1.5/#39] القفل الثاني — يفرضه فاحص check_auth_gate

export const dynamic = "force-dynamic";

/** المنتجات — قائمة مع فلترة اختيارية (بحث/أرشيف) */
export async function GET(req: NextRequest) {
  await ensureSeed();
  const q = req.nextUrl.searchParams.get("q")?.trim() ?? "";
  const archived = req.nextUrl.searchParams.get("archived") === "1";

  const rows = await db.product.findMany({
    where: {
      archived,
      ...(q ? { OR: [{ name: { contains: q } }, { sku: { contains: q } }, { barcode: { contains: q } }] } : {}),
    },
    orderBy: { createdAt: "asc" },
  });

  const stockValue = rows.reduce((s, p) => s + Math.round(p.stockQty * p.costPrice), 0);
  return NextResponse.json({ products: rows, stockValue, stockValueNum: stockValue / 100 });
}

/** إضافة منتج */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const body = await req.json().catch(() => null);
  if (!body?.name || typeof body.name !== "string") {
    return NextResponse.json({ error: "اسم المنتج مطلوب" }, { status: 400 });
  }
  const name = String(body.name).trim().slice(0, 120);
  const sku = String(body.sku ?? "").trim() || `SKU-${Date.now().toString(36).toUpperCase()}`;

  const exists = await db.product.findUnique({ where: { sku } });
  if (exists) return NextResponse.json({ error: `رمز SKU مستخدم مسبقًا: ${sku}` }, { status: 409 });

  const product = await db.product.create({
    data: {
      name,
      sku,
      barcode: body.barcode ? String(body.barcode).trim() : null,
      unit: String(body.unit ?? "حبة").slice(0, 20) || "حبة",
      category: String(body.category ?? "عام").slice(0, 40) || "عام",
      costPrice: sarToQirsh(Number(body.costPrice) || 0),
      salePrice: sarToQirsh(Number(body.salePrice) || 0),
      stockQty: Math.max(0, Number(body.stockQty) || 0),
      reorderLevel: Math.max(0, Number(body.reorderLevel) || 5),
    },
  });
  return NextResponse.json({ product }, { status: 201 });
}

/** تعديل/أرشفة منتج */
export async function PATCH(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const body = await req.json().catch(() => null);
  if (!body?.id) return NextResponse.json({ error: "id مطلوب" }, { status: 400 });

  const data: Record<string, unknown> = {};
  if (typeof body.name === "string" && body.name.trim()) data.name = body.name.trim().slice(0, 120);
  if (typeof body.category === "string" && body.category.trim()) data.category = body.category.trim().slice(0, 40);
  if (typeof body.unit === "string" && body.unit.trim()) data.unit = body.unit.trim().slice(0, 20);
  if (body.costPrice !== undefined) data.costPrice = sarToQirsh(Number(body.costPrice) || 0);
  if (body.salePrice !== undefined) data.salePrice = sarToQirsh(Number(body.salePrice) || 0);
  if (body.reorderLevel !== undefined) data.reorderLevel = Math.max(0, Number(body.reorderLevel) || 0);
  if (body.archived !== undefined) data.archived = Boolean(body.archived);
  // تعديل الجرد يدويًا (تسوية بالجرد — المسار A3 مستقبلًا على مستوى الفرع)
  if (body.stockQty !== undefined) data.stockQty = Math.max(0, Number(body.stockQty) || 0);

  try {
    const product = await db.product.update({ where: { id: String(body.id) }, data });
    return NextResponse.json({ product });
  } catch {
    return NextResponse.json({ error: "المنتج غير موجود" }, { status: 404 });
  }
}
