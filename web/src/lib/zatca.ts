/**
 * ZATCA مرحلة-1 — QR TLV (مطابق لعقد ZatcaQr.kt في تطبيق الأندرويد)
 * الهيكل: 5 وسوم TLV → Base64:
 *  (1) اسم البائع  (2) الرقم الضريبي  (3) الطابع الزمني ISO8601
 *  (4) الإجمالي بالضريبة  (5) مبلغ ضريبة القيمة المضافة
 */
import QRCode from "qrcode";

function tlv(tag: number, value: string): Buffer {
  const v = Buffer.from(value, "utf8");
  return Buffer.concat([Buffer.from([tag, v.length]), v]);
}

export type ZatcaSeller = { name: string; vat: string };

export const DEFAULT_SELLER: ZatcaSeller = {
  name: "متجر سوبر بيز",
  vat: "310123456700003", // 15 رقمًا يبدأ بـ3 — نمط رقم ضريبي سعودي
};

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
    tlv(3, opts.date.toISOString()),
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

/** UUID v4 بسيط بدون اعتماديات (العقد: uuid للفاتورة) */
export function invoiceUuid(): string {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
