import { defineConfig } from "vitest/config";
import path from "node:path";
import { TEST_ENV } from "./tests/integration.env";

/**
 * جناح اختبارات بوابة الويب — عقود نقية (بلا قاعدة بيانات) + تكامل API على SQLite حقيقية.
 * - الناقلات الذهبية المُرحَّلة من app/src/test/.../ZatcaQrTest.kt تحمل تطابق البايتات
 *   بين محرك الأندرويد ومحرك الويب — العقد المشترك غير قابل للتفاوض.
 * - [W1.7/#41] اختبارات التكامل تشغّل معالجات المسارات الحقيقية فوق قاعدة ملفية واحدة
 *   تُهيأ في globalSetup (دفع المخطط مرة واحدة) — وبيئة موحدة قبل أي استيراد وحدة.
 */
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    environment: "node",
    globalSetup: ["tests/integration-setup.ts"],
    env: { ...TEST_ENV },
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
