/**
 * W1.7 (Issue #41) — تحقق zod موحد لكل مسارات الكتابة — العقد الوحيد للمدخلات.
 *
 * قواعد صارمة موروثة (غير قابلة للتفاوض):
 * - G2: المال قروش Int — السداد يُقبل عددًا صحيحًا فقط (الواجهة ترسل Math.round(sar*100))؛
 *   أي كسر عشري في مسار مالي يُرفض ولا يُقرَّب صمتًا.
 * - لا سالب: الأسعار والكميات والجرد والخصومات ≥ 0 (كانت تُكبس صمتًا — الآن تُرفض بوضوح).
 * - الرسائل عربية لأنها تصل واجهة المالك كما هي.
 * - رد الخطأ الموحد: { error, code: "INVALID_INPUT" } بـ400 — نفس شكل بوابة المصادقة.
 */
import { NextResponse } from "next/server";
import { z } from "zod";

// ─── الفواتير ───────────────────────────────────────────────────────────────

export const invoiceItemSchema = z.object({
  productId: z.string().min(1).nullish(),
  desc: z.string().max(200, "وصف البند أطول من الحد (200)").optional(),
  qty: z.number().positive("الكمية يجب أن تكون أكبر من صفر"),
  unitPrice: z.number().min(0, "السعر لا يكون سالبًا").optional(),
  discount: z.number().min(0, "الخصم لا يكون سالبًا").optional(),
});

export const invoiceCreateSchema = z.object({
  items: z.array(invoiceItemSchema).min(1, "أضف بندًا واحدًا على الأقل بكمية أكبر من صفر"),
  credit: z.boolean().optional(),
  taxRate: z.number().int("نسبة الضريبة عدد صحيح").min(0, "الضريبة بين 0 و100").max(100, "الضريبة بين 0 و100").optional(),
  partyId: z.string().min(1).nullish(),
});

export const invoiceCancelSchema = z.object({
  id: z.string().min(1, "id مطلوب"),
});

// ─── المنتجات ───────────────────────────────────────────────────────────────

export const productCreateSchema = z.object({
  name: z.string().trim().min(1, "اسم المنتج مطلوب").max(120, "الاسم أطول من الحد (120)"),
  sku: z.string().max(40, "رمز SKU أطول من الحد (40)").optional(),
  barcode: z.string().max(60, "الباركود أطول من الحد (60)").nullish(),
  unit: z.string().max(20).optional(),
  category: z.string().max(40).optional(),
  costPrice: z.number().min(0, "سعر التكلفة لا يكون سالبًا").optional(),
  salePrice: z.number().min(0, "سعر البيع لا يكون سالبًا").optional(),
  stockQty: z.number().min(0, "الجرد لا يكون سالبًا").optional(),
  reorderLevel: z.number().min(0, "حد الطلب لا يكون سالبًا").optional(),
});

export const productPatchSchema = z.object({
  id: z.string().min(1, "id مطلوب"),
  name: z.string().min(1, "الاسم لا يكون فارغًا").max(120).optional(),
  category: z.string().max(40).optional(),
  unit: z.string().max(20).optional(),
  costPrice: z.number().min(0, "سعر التكلفة لا يكون سالبًا").optional(),
  salePrice: z.number().min(0, "سعر البيع لا يكون سالبًا").optional(),
  stockQty: z.number().min(0, "الجرد لا يكون سالبًا").optional(),
  reorderLevel: z.number().min(0, "حد الطلب لا يكون سالبًا").optional(),
  archived: z.boolean().optional(),
});

// ─── الأطراف ────────────────────────────────────────────────────────────────

export const partyCreateSchema = z.object({
  name: z.string().trim().min(1, "اسم الطرف مطلوب").max(120, "الاسم أطول من الحد (120)"),
  phone: z.string().max(30, "الهاتف أطول من الحد (30)").nullish(),
  type: z.number().int().min(0, "النوع 0 عميل / 1 مورد / 2 كلاهما").max(2, "النوع 0 عميل / 1 مورد / 2 كلاهما").optional(),
  taxNumber: z.string().max(20, "الرقم الضريبي أطول من الحد (20)").nullish(),
});

// ─── السدود — المبلغ قروش Int فقط (G2) ─────────────────────────────────────

export const paymentCreateSchema = z.object({
  invoiceId: z.string().min(1).nullish(),
  partyId: z.string().min(1).nullish(),
  amount: z.number().int("مبلغ السدَّاد بالقروش (عدد صحيح) — لا كسور عشرية في المال").positive("المبلغ يجب أن يكون أكبر من صفر"),
  method: z.string().max(12).optional(),
  note: z.string().max(200, "الملاحظة أطول من الحد (200)").nullish(),
});

// ─── المصروفات — المبلغ ريال عشري (يُحوَّل قروش داخليًا بsarToQirsh) ────────

export const expenseCreateSchema = z.object({
  title: z.string().min(1, "عنوان المصروف مطلوب").max(120, "العنوان أطول من الحد (120)"),
  category: z.string().max(40).optional(),
  amount: z.number().positive("المبلغ يجب أن يكون أكبر من صفر"),
  note: z.string().max(200, "الملاحظة أطول من الحد (200)").nullish(),
});

// ─── المحلل الموحد ──────────────────────────────────────────────────────────

export type ParseResult<T> = { ok: true; data: T } | { ok: false; res: NextResponse };

/** يفكّ جسم الطلب عبر المخطط؛ الفشل = 400 {error, code: INVALID_INPUT} بأول مخالفة عربية. */
export function parseJsonWith<T>(schema: z.ZodType<T>, body: unknown): ParseResult<T> {
  const r = schema.safeParse(body);
  if (r.success) return { ok: true, data: r.data };
  const first = r.error.issues[0];
  const path = first && first.path.length ? `${first.path.join(".")}: ` : "";
  return {
    ok: false,
    res: NextResponse.json(
      { error: `${path}${first?.message ?? "مدخلات غير صالحة"}`, code: "INVALID_INPUT" },
      { status: 400 },
    ),
  };
}
