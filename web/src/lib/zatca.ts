/**
 * ZATCA مرحلة-1 — QR TLV (مطابق لعقد ZatcaQr.kt في تطبيق الأندرويد)
 * الهيكل: 5 وسوم TLV → Base64:
 *  (1) اسم البائع  (2) الرقم الضريبي  (3) الطابع الزمني ISO8601
 *  (4) الإجمالي بالضريبة  (5) مبلغ ضريبة القيمة المضافة
 */
import QRCode from "qrcode";

function tlv(tag: number, value: string): Buffer {
  return tlvSegment(tag, Buffer.from(value, "utf8"));
}

/**
 * مقطع TLV واحد: وسم بايت واحد (1..255) + طول بايت واحد (0..255) + القيمة —
 * مكشوف للاختبار النقي (نفس عقد `ZatcaQr.tlv` في الأندرويد: خارج الحدود يُرفض).
 */
export function tlvSegment(tag: number, value: Buffer): Buffer {
  if (tag < 1 || tag > 255) throw new Error(`ZATCA TLV tag out of byte range: ${tag}`);
  if (value.length > 255) throw new Error(`ZATCA TLV value too long for 1-byte length: ${value.length}`);
  return Buffer.concat([Buffer.from([tag, value.length]), value]);
}

export type ZatcaSeller = { name: string; vat: string };

export const DEFAULT_SELLER: ZatcaSeller = {
  name: "متجر سوبر بيز",
  vat: "310123456700003", // 15 رقمًا يبدأ بـ3 — نمط رقم ضريبي سعودي
};

/**
 * الطابع الزمني ISO-8601 بتوقيت UTC **بلا أجزاء ثانية** — تطابق بايتي مع عقد
 * `ZatcaQr.kt` الأندرويد ("yyyy-MM-dd'T'HH:mm:ss'Z'" — 20 بايت).
 * [W1.4 / #38] كان `toISOString()` يصدر `.000Z` (24 بايت) — انحراف عن العقد أُغلق RED-first.
 */
function isoUtcNoMs(d: Date): string {
  return d.toISOString().replace(/\.\d{3}Z$/, "Z");
}

/** يبني QR مرحلة-1 (فقط لفواتير المملكة — G8: ZATCA صارم لـSA) */
export function buildZatcaQrBase64(opts: {
  seller?: ZatcaSeller;
  date: Date;
  totalQirsh: number;
  vatQirsh: number;
}): string {
  const seller = opts.seller ?? DEFAULT_SELLER;
  const total = (opts.totalQirsh / 100).toFixed(2);
  const vat = (opts.vatQirsh / 100).toFixed(2);
  const payload = Buffer.concat([
    tlv(1, seller.name),
    tlv(2, seller.vat),
    tlv(3, isoUtcNoMs(opts.date)),
    tlv(4, total),
    tlv(5, vat),
  ]);
  return payload.toString("base64");
}

/** صورة QR كـdata URL للتضمين في الواجهة */
export async function qrDataUrl(base64: string): Promise<string> {
  return QRCode.toDataURL(base64, {
    errorCorrectionLevel: "M",
    margin: 1,
    width: 220,
    color: { dark: "#0B1026", light: "#FFFFFF" },
  });
}

/**
 * UUID v4 — [W1.4 / #38] المصدر التشفيري `crypto.randomUUID()` (CSPRNG) بدل
 * `Math.random()` غير التشفيري؛ يبقى fallback بالسلوك السابق للبيئات النادرة بلا واجهة WebCrypto.
 */
export function invoiceUuid(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (typeof c?.randomUUID === "function") return c.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    const v = ch === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
