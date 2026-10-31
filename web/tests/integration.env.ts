/**
 * W1.7 — مصدر واحد لمسار قاعدة بيانات التكامل (يستورده vitest.config وglobalSetup معًا).
 * مسار مطلق يتفادى التباس Prisma (المسارات النسبية تُحل بالنسبة لschema.prisma).
 */
import path from "node:path";

const dbFile = path.resolve(__dirname, "../.tmp-integration.db");

export const INTEGRATION_DB_FILE = dbFile;
export const INTEGRATION_DB_URL = `file:${dbFile}`;

/** بيئة الاختبار الموحدة — نفس عقد الإنتاج: لا أسرار في الكود، قيم اختبارية صريحة. */
export const TEST_ENV = {
  DATABASE_URL: INTEGRATION_DB_URL,
  SUPERBIZ_SESSION_SECRET: "test-secret-بجنون-123",
  SUPERBIZ_OWNER_PASSWORD: "test-owner-pin-123456",
} as const;
