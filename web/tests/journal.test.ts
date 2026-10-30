/**
 * اختبارات القيد المزدوج — مرآة سلوك AccountingEngine.kt على محرك الويب.
 * [W1.4 / Issue #38] العقد: totalDebit == totalCredit مساواة تامة قبل أي حفظ — لا مسودة غير متوازنة تلمس القاعدة.
 */
import { describe, it, expect, vi } from "vitest";
import { postJournal, ACCOUNTS, type PrismaLike } from "@/lib/journal";

/** معاملة وهمية تسجل ما أُرسل لـjournalEntry.create — بلا قاعدة بيانات */
function mockTx() {
  const calls: Array<{ data: { memo: string; lines: { create: Array<{ account: string; debit: number; credit: number }> } } }> = [];
  const tx: PrismaLike = {
    journalEntry: {
      create: async (args: never) => {
        calls.push(args as (typeof calls)[number]);
        return { id: "je_1" };
      },
    } as PrismaLike["journalEntry"] as never,
  } as unknown as PrismaLike;
  return { tx, calls };
}

describe("postJournal — القيد المزدوج مفروض التوازن", () => {
  it("قيد متوازن يُحفظ كاملاً ويسجل المذكرة والمرجع", async () => {
    const { tx, calls } = mockTx();
    await postJournal(tx, {
      date: new Date("2026-10-30T10:00:00Z"),
      memo: "فاتورة بيع INV-1001",
      refType: "INVOICE",
      refId: "inv_1",
      lines: [
        { account: ACCOUNTS.CASH, debit: 15000, credit: 0 },
        { account: ACCOUNTS.SALES, debit: 0, credit: 15000 },
      ],
    });
    expect(calls).toHaveLength(1);
    expect(calls[0].data.memo).toBe("فاتورة بيع INV-1001");
    expect(calls[0].data.lines.create).toHaveLength(2);
  });

  it("قيد غير متوازن يُرفض قبل أي كتابة — UNBALANCED_JOURNAL (فرق قروش واحد)", async () => {
    const { tx, calls } = mockTx();
    await expect(
      postJournal(tx, {
        date: new Date(),
        memo: "مسودة فاسدة",
        refType: "INVOICE",
        lines: [
          { account: ACCOUNTS.CASH, debit: 10000, credit: 0 },
          { account: ACCOUNTS.SALES, debit: 0, credit: 9999 },
        ],
      })
    ).rejects.toThrow(/UNBALANCED_JOURNAL/);
    expect(calls).toHaveLength(0); // لا كتابة إطلاقاً — العقد
  });

  it("أسطر صفرية تُصفّى قبل الحفظ ولا تكسر التوازن", async () => {
    const { tx, calls } = mockTx();
    await postJournal(tx, {
      date: new Date(),
      memo: "بيع نقدي بلا ذمم",
      refType: "INVOICE",
      lines: [
        { account: ACCOUNTS.CASH, debit: 5000, credit: 0 },
        { account: ACCOUNTS.AR, debit: 0, credit: 0 }, // صفرية — تُصفّى
        { account: ACCOUNTS.SALES, debit: 0, credit: 5000 },
      ],
    });
    expect(calls[0].data.lines.create).toHaveLength(2);
  });

  it("قيد بيع كامل (نقد + ذمم + مبيعات + ضريبة + COGS) متوازن حتمياً كما في مسار API", async () => {
    const { tx, calls } = mockTx();
    // نفس بنية قيد POST /api/invoices: subtotal=10000, tax=1500, total=11500, cost=6000
    await postJournal(tx, {
      date: new Date(),
      memo: "فاتورة بيع INV-1002",
      refType: "INVOICE",
      lines: [
        { account: ACCOUNTS.CASH, debit: 11500, credit: 0 },
        { account: ACCOUNTS.AR, debit: 0, credit: 0 },
        { account: ACCOUNTS.SALES, debit: 0, credit: 10000 },
        { account: ACCOUNTS.VAT, debit: 0, credit: 1500 },
        { account: ACCOUNTS.COGS, debit: 6000, credit: 0 },
        { account: ACCOUNTS.INVENTORY, debit: 0, credit: 6000 },
      ],
    });
    const lines = calls[0].data.lines.create;
    const d = lines.reduce((s, l) => s + l.debit, 0);
    const c = lines.reduce((s, l) => s + l.credit, 0);
    expect(d).toBe(c);
  });
});
