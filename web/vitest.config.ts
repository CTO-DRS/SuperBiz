import { defineConfig } from "vitest/config";
import path from "node:path";

/**
 * جناح اختبارات بوابة الويب — العقود النقية فقط (بلا قاعدة بيانات).
 * الناقلات الذهبية المُرحَّلة من app/src/test/.../ZatcaQrTest.kt تحمل تطابق البايتات
 * بين محرك الأندرويد ومحرك الويب — العقد المشترك غير قابل للتفاوض.
 */
export default defineConfig({
  test: {
    include: ["tests/**/*.test.ts"],
    environment: "node",
  },
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
