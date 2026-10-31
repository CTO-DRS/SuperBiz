/**
 * W1.7 (Issue #41) — إعداد عالمي يُشغَّل مرة واحدة قبل كل عمال vitest:
 * يمسح قاعدة SQLite الخاصة بالتكامل ثم يدفع مخطط Prisma عليها.
 * (بلا هذا: أول استعلام يفشل «لا جدول» — والملف نفسه مستثنى ب*.db في .gitignore.)
 */
import { execSync } from "node:child_process";
import { rmSync } from "node:fs";
import { INTEGRATION_DB_FILE, INTEGRATION_DB_URL, TEST_ENV } from "./integration.env";

export default function globalSetup(): void {
  rmSync(INTEGRATION_DB_FILE, { force: true });
  rmSync(`${INTEGRATION_DB_FILE}-journal`, { force: true });
  execSync("bunx prisma db push --skip-generate", {
    stdio: "pipe",
    env: { ...process.env, ...TEST_ENV, DATABASE_URL: INTEGRATION_DB_URL },
  });
}
