/**
 * القيد المزدوج — الثابت المحاسبي: totalDebit == totalCredit مساواة تامة قبل أي حفظ
 * (موروث من AccountingEngine.kt). أي مسودة غير متوازنة تُرفض — لا حفظ أبدًا.
 */
export const ACCOUNTS = {
  CASH: "1010", // نقدية في الصندوق
  AR: "1200", // ذمم مدينة (عملاء)
  INVENTORY: "1300", // مخزون
  AP: "2100", // ذمم دائنة (موردون)
  VAT: "2200", // ضريبة القيمة المضافة المستحقة
  SALES: "4000", // إيرادات المبيعات
  COGS: "5000", // تكلفة البضاعة المباعة
  EXPENSES: "5020", // مصروفات
} as const;

export type JournalDraftLine = {
  account: string;
  debit: number; // قروش
  credit: number; // قروش
};

export type JournalDraft = {
  date: Date;
  memo: string;
  refType: string;
  refId?: string;
  lines: JournalDraftLine[];
};

/** يتحقق من التوازن التام ثم يحفظ القيد داخل معاملة Prisma الممرَّرة */
export async function postJournal(tx: PrismaLike, draft: JournalDraft) {
  const totalDebit = draft.lines.reduce((s, l) => s + l.debit, 0);
  const totalCredit = draft.lines.reduce((s, l) => s + l.credit, 0);
  if (totalDebit !== totalCredit) {
    // فشل مبكر — لا يصل أي قيد غير متوازن إلى قاعدة البيانات (العقد: مساواة تامة)
    throw new Error(`UNBALANCED_JOURNAL debit=${totalDebit} credit=${totalCredit} memo=${draft.memo}`);
  }
  const entry = await tx.journalEntry.create({
    data: {
      date: draft.date,
      memo: draft.memo,
      refType: draft.refType,
      refId: draft.refId,
      lines: {
        create: draft.lines
          .filter((l) => l.debit !== 0 || l.credit !== 0)
          .map((l) => ({ account: l.account, debit: l.debit, credit: l.credit })),
      },
    },
  });
  return entry;
}

/**
 * واجهة بنيوية ضيقة لكائن معاملة Prisma (تفادي أي Double) —
 * تفصل المحرك عن قاعدة البيانات ليكون قابلاً للاختبار النقي (نمط G7)،
 * وكل معاملة Prisma حقيقية تحقق هذا الشكل بنيوياً.
 */
export type PrismaLike = {
  journalEntry: {
    create: (args: {
      data: {
        date: Date;
        memo: string;
        refType: string;
        refId?: string;
        lines: { create: Array<{ account: string; debit: number; credit: number }> };
      };
    }) => Promise<unknown>;
  };
};
