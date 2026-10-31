import { NextRequest, NextResponse } from "next/server";
import { db } from "@/lib/db";
import { ensureSeed } from "@/lib/seed";
import { sarToQirsh } from "@/lib/money";
import { parseJsonWith, productCreateSchema, productPatchSchema } from "@/lib/schemas"; // [W1.7/#41]
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

/** إضافة منتج — العقد: الأسعار تُستقبل ريالاً وتُخزَّن قروشاً (sarToQirsh) */
export async function POST(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const raw = await req.json().catch(() => null);
  const parsed = parseJsonWith(productCreateSchema, raw); // [W1.7/#41]
  if (!parsed.ok) return parsed.res;
  const b = parsed.data;

  const name = b.name.trim().slice(0, 120);
  const sku = (b.sku ?? "").trim() || `SKU-${Date.now().toString(36).toUpperCase()}`;

  const exists = await db.product.findUnique({ where: { sku } });
  if (exists) return NextResponse.json({ error: `رمز SKU مستخدم مسبقًا: ${sku}` }, { status: 409 });

  const product = await db.product.create({
    data: {
      name,
      sku,
      barcode: b.barcode ? String(b.barcode).trim() : null,
      unit: (b.unit ?? "حبة").trim().slice(0, 20) || "حبة",
      category: (b.category ?? "عام").trim().slice(0, 40) || "عام",
      costPrice: sarToQirsh(b.costPrice ?? 0),
      salePrice: sarToQirsh(b.salePrice ?? 0),
      stockQty: b.stockQty ?? 0,
      reorderLevel: b.reorderLevel ?? 5,
    },
  });
  return NextResponse.json({ product }, { status: 201 });
}

/** تعديل/أرشفة منتج */
export async function PATCH(req: NextRequest) {
  const gate = await requireSession(req); // [W1.5/#39] بلا جلسة مالك = 401
  if (gate) return gate;
  const raw = await req.json().catch(() => null);
  const parsed = parseJsonWith(productPatchSchema, raw); // [W1.7/#41]
  if (!parsed.ok) return parsed.res;
  const b = parsed.data;

  const data: Record<string, unknown> = {};
  if (b.name !== undefined) data.name = b.name.trim().slice(0, 120);
  if (b.category !== undefined) data.category = b.category.trim().slice(0, 40);
  if (b.unit !== undefined) data.unit = b.unit.trim().slice(0, 20);
  if (b.costPrice !== undefined) data.costPrice = sarToQirsh(b.costPrice);
  if (b.salePrice !== undefined) data.salePrice = sarToQirsh(b.salePrice);
  if (b.reorderLevel !== undefined) data.reorderLevel = b.reorderLevel;
  if (b.archived !== undefined) data.archived = b.archived;
  // تعديل الجرد يدويًا (تسوية بالجرد — المسار A3 مستقبلًا على مستوى الفرع)
  if (b.stockQty !== undefined) data.stockQty = b.stockQty;

  try {
    const product = await db.product.update({ where: { id: b.id }, data });
    return NextResponse.json({ product });
  } catch {
    return NextResponse.json({ error: "المنتج غير موجود" }, { status: 404 });
  }
}
