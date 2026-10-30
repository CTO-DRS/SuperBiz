/**
 * اختبارات عقد المال — القروش Int وصفر Double في أي مسار مالي (مرآة MoneyMath.kt).
 */
import { describe, it, expect } from "vitest";
import { fmtSar, qirshToNumber, sarToQirsh, bizPulse } from "@/lib/money";

describe("عقد المال — قروش Long", () => {
  it("fmtSar يعرض ريالاً بمنزلتين ورمزاً", () => {
    expect(fmtSar(115000)).toBe("1,150.00 ر.س");
    expect(fmtSar(1500)).toBe("15.00 ر.س");
    expect(fmtSar(5)).toBe("0.05 ر.س");
    expect(fmtSar(11500, false)).toBe("115.00");
  });

  it("sarToQirsh نصف-أعلى كما في Money.kt", () => {
    expect(sarToQirsh(115.0)).toBe(11500);
    expect(sarToQirsh(0.055)).toBe(6); // 5.5 → 6 (نصف-أعلى)
    expect(sarToQirsh(10.005)).toBe(1001); // 1000.5 → 1001
    expect(sarToQirsh(-2.5)).toBe(-250); // قيم سالبة قانونية (مرتجعات)
  });

  it("qirshToNumber يقرّب للقروش أولاً — لا كسور تتهرب", () => {
    expect(qirshToNumber(11500)).toBe(115);
    expect(qirshToNumber(1500.4)).toBe(15); // 1500.4 → 1500 قروش → 15.00
  });

  it("جولة ذهاب/إياب قروش ↔ ريال تحفظ القيمة", () => {
    for (const q of [1, 99, 100, 12345, 999999]) {
      expect(sarToQirsh(qirshToNumber(q))).toBe(q);
    }
  });

  it("bizPulse حتمي — نفس المدخلات نفس النبض (عقد R18: بلا AI)", () => {
    const input = { receivable: 20000, monthSales: 100000, lowStockCount: 2, productCount: 12, monthExpenses: 30000 };
    expect(bizPulse(input)).toBe(bizPulse(input));
    expect(bizPulse(input)).toBeGreaterThanOrEqual(0);
    expect(bizPulse(input)).toBeLessThanOrEqual(100);
    // ذمم صفرية ومخزون صحي = نبض كامل
    expect(bizPulse({ receivable: 0, monthSales: 100000, lowStockCount: 0, productCount: 12, monthExpenses: 0 })).toBe(100);
  });
});
