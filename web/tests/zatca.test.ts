/**
 * اختبارات ZATCA مرحلة-1 — مرآة حرفية لـ app/src/test/java/com/superbiz/app/ZatcaQrTest.kt
 * الناقل الذهبي: العينة المرجعية المنشورة (Bunnia Company Limited) — تطابق بايتي مع محرك الأندرويد.
 * [W1.4 / Issue #38] اختبار توافق الطابع الزمني (20 بايت بلا أجزاء ثانية) فشل قبل الإصلاح — RED-first موثق.
 */
import { describe, it, expect } from "vitest";
import { buildZatcaQrBase64, tlvSegment, invoiceUuid } from "@/lib/zatca";

/** 2022-04-25T15:30:00Z — تاريخ ثابت ذاتي التوثيق (نفس sampleTs في ZatcaQrTest) */
const SAMPLE_TS = new Date("2022-04-25T15:30:00Z");

describe("ZatcaQr — الناقل الذهبي (مُرحَّل من ZatcaQrTest.kt)", () => {
  it("goldenVector_canonicalZatcaSample — عينة Bunnia بايتاً بتطابق مع الأندرويد", () => {
    // المتجه من ZatcaQrTest.kt سطر 26 حرفياً
    const expected =
      "ARZCdW5uaWEgQ29tcGFueSBMaW1pdGVkAg8zMTAxMjIzOTM1MDAwMDMDFDIwMjItMDQtMjVUMTU6MzA6MDBaBAYxMTUuMDAFBTE1LjAw";
    expect(
      buildZatcaQrBase64({
        seller: { name: "Bunnia Company Limited", vat: "310122393500003" },
        date: SAMPLE_TS,
        totalQirsh: 11500, // 115.00 ريال
        vatQirsh: 1500, // 15.00 ريال
      })
    ).toBe(expected);
  });

  it("timestamp_isIso8601UtcSuffix — الطابع 20 بايت بلا أجزاء ثانية (عقد ZatcaQr.kt حرفياً)", () => {
    const bytes = tlvSegmentsOf({ name: "س", vat: "1", total: 100, vatAmt: 0 });
    const tsStart = 2 + Buffer.byteLength("س", "utf8") + (2 + 1);
    expect(bytes[tsStart]).toBe(3); // tag 3
    const tsLen = bytes[tsStart + 1];
    expect(tsLen).toBe(20); // "2022-04-25T15:30:00Z" — ليس 24 (إصلاح W1.4)
    const ts = bytes.subarray(tsStart + 2, tsStart + 2 + tsLen).toString("ascii");
    expect(ts).toBe("2022-04-25T15:30:00Z");
  });

  it("segments_exactBytesOfEachTlv — مقاطع TLV الخمسة بالبايتات الصحيحة (شركة النور)", () => {
    const name = "شركة النور";
    const bytes = tlvSegmentsOf({ name, vat: "300012345600003", total: 11500, vatAmt: 1500 });
    // tag1 len1 name | tag2 len2 vat | tag3 len3 ts | tag4 len4 total | tag5 len5 vat
    expect(bytes[0]).toBe(1);
    expect(bytes[1]).toBe(Buffer.byteLength(name, "utf8")); // 19 بايت — العد بايتات لا محارف
    expect(bytes.subarray(2, 2 + 19).toString("utf8")).toBe(name);
    const vatStart = 2 + 19;
    expect(bytes[vatStart]).toBe(2);
    expect(bytes[vatStart + 1]).toBe(15);
    expect(bytes.subarray(vatStart + 2, vatStart + 2 + 15).toString("ascii")).toBe("300012345600003");
  });

  it("moneyFormatting_twoDecimalsLatinDigits — 1000.50 و150.00 بخانتين وأرقام لاتينية", () => {
    // مبلغ 1000.50 ريال = 100050 قروش — نفس عقد اختبار الأندرويد (money2)
    const bytes = tlvSegmentsOf({ name: "س", vat: "1", total: 100050, vatAmt: 15000 });
    const totalStart = 2 + Buffer.byteLength("س", "utf8") + (2 + 1) + (2 + 20);
    expect(bytes[totalStart]).toBe(4);
    expect(bytes[totalStart + 1]).toBe(7);
    expect(bytes.subarray(totalStart + 2, totalStart + 2 + 7).toString("ascii")).toBe("1000.50");
    const vatStart = totalStart + 2 + 7;
    expect(bytes[vatStart]).toBe(5);
    expect(bytes.subarray(vatStart + 2, vatStart + 2 + 6).toString("ascii")).toBe("150.00");
  });

  it("emptySellerName_isLegalZeroLengthValue — اسم بائع فارغ طول صفر قانوني", () => {
    const bytes = tlvSegmentsOf({ name: "", vat: "310122393500003", total: 11500, vatAmt: 1500 });
    expect(bytes[0]).toBe(1);
    expect(bytes[1]).toBe(0);
    expect(bytes[2]).toBe(2); // يليه tag 2 مباشرة
  });

  it("boundary_255ValueBytesAccepted_256Rejected — حدود الطول البايتي", () => {
    const ascii255 = "a".repeat(255);
    const seg = tlvSegment(1, Buffer.from(ascii255, "ascii"));
    expect(seg.length).toBe(257);
    expect(seg[1]).toBe(255);
    expect(() => tlvSegment(1, Buffer.alloc(256))).toThrow();
    expect(() => tlvSegment(0, Buffer.alloc(0))).toThrow();
    expect(() => tlvSegment(256, Buffer.alloc(0))).toThrow();
  });
});

/** يجمع حمولة QR ويفكّ Base64 لفحص البايتات — مطابق لـqrPayloadBytes في الأندرويد */
function tlvSegmentsOf(o: { name: string; vat: string; total: number; vatAmt: number }): Buffer {
  const payload = buildZatcaQrBase64({
    seller: { name: o.name, vat: o.vat },
    date: SAMPLE_TS,
    totalQirsh: o.total,
    vatQirsh: o.vatAmt,
  });
  return Buffer.from(payload, "base64");
}

describe("هوية الفاتورة UUID — عقد ZATCA", () => {
  it("صيغة UUID v4 صحيحة", () => {
    const u = invoiceUuid();
    expect(u).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
  it("ألف UUID بلا تكرار (مصدر عشوائي مناسب)", () => {
    const set = new Set<string>();
    for (let i = 0; i < 1000; i++) set.add(invoiceUuid());
    expect(set.size).toBe(1000);
  });
});
