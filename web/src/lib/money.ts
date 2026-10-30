/**
 * عقد المال — SuperBiz: القروش Long (Int هنا) — صفر Double في أي مسار مالي.
 * العرض: ريال سعودي بمنزلتين. BHD منزلتان (موروث من GulfTax) — للعرض فقط في الوحدة التجريبية.
 */

/** قروش → نص ريال «1,150.00 ر.س» */
export function fmtSar(qirsh: number, symbol = true): string {
  const v = (qirsh / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  return symbol ? `${v} ر.س` : v;
}

/** قروش → رقم عشري للرسوم البيانية */
export function qirshToNumber(qirsh: number): number {
  return Math.round(qirsh) / 100;
}

/** ريال عشري → قروش (نصف-أعلى كما في Money.kt) */
export function sarToQirsh(sar: number): number {
  return Math.round(sar * 100);
}

/** نبض العمل 0..100 — مركّب حتمي (مستوحى من R18Kpi):
 * 40% تحصيل الذمم + 30% صحة المخزون + 30% تغطية المصروفات من المبيعات */
export function bizPulse(input: {
  receivable: number; // قروش مدينة
  monthSales: number; // قروش
  lowStockCount: number;
  productCount: number;
  monthExpenses: number;
}): number {
  const collectScore =
    input.monthSales > 0 ? Math.max(0, 1 - input.receivable / (input.monthSales * 2)) : 1;
  const stockScore = input.productCount > 0 ? 1 - input.lowStockCount / input.productCount : 1;
  const expenseScore =
    input.monthSales > 0 ? Math.max(0, 1 - input.monthExpenses / (input.monthSales * 0.8)) : 1;
  return Math.round(collectScore * 40 + stockScore * 30 + expenseScore * 30);
}
