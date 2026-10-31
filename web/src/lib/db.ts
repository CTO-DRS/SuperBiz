import { PrismaClient } from '@prisma/client'

const globalForPrisma = globalThis as unknown as {
  prisma: PrismaClient | undefined
}

export const db =
  globalForPrisma.prisma ??
  new PrismaClient({
    // [W1.4 / #38] سجلات الاستعلام في التطوير فقط — الإنتاج: أخطاء حصراً (ضوضاء وأداء)
    // [W1.7 / #41] وفاحصة vitest صامتة — الاختبارات الحقيقية لا تحتاج ضوضاء SQL
    log: process.env.NODE_ENV === "production" || process.env.VITEST ? ["error"] : ["query", "error"],
  })

if (process.env.NODE_ENV !== 'production') globalForPrisma.prisma = db