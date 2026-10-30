/**
 * اختبارات W1.5 — طبقة المصادقة والتفويض (Issue #39) — RED-first.
 * العقد النقي: جلسة مالك HMAC-SHA256 + PBKDF2 لكلمة المرور + سياسة بوابة مركزية
 * + حد معدل — كلها WebCrypto صرفة آمنة للـedge (بلا node:crypto).
 * مرآة الأحكام: G1 (دون اتصال — لا شبكة هنا)، G5 (مقفول افتراضيًا: كل شيء ممنوع
 * إلا ما سُمح صراحة)، G6 (RED-first).
 */
import { describe, it, expect } from "vitest";
import {
  SESSION_COOKIE,
  SESSION_TTL_MS,
  hashPassword,
  verifyPassword,
  signSession,
  verifySession,
  isWriteMethod,
  isFinancialRead,
  isPublicRead,
  isAuthNamespace,
  assertSameOrigin,
  createRateLimiter,
} from "@/lib/auth";

const SECRET = "test-secret-بجنون-123";

describe("عقد بيانات الاعتماد — PBKDF2", () => {
  it("hashPassword يُنتج صيغة pbkdf2$sha256$iter$salt$hash قابلة للتحقق", async () => {
    const stored = await hashPassword("pin-1234");
    expect(stored).toMatch(/^pbkdf2\$sha256\$\d+\$[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+$/);
    expect(await verifyPassword("pin-1234", stored)).toBe(true);
  });

  it("كلمة مرور خاطئة تُرفض — وصيغة تالفة ترفض بلا رمي", async () => {
    const stored = await hashPassword("pin-1234");
    expect(await verifyPassword("pin-9999", stored)).toBe(false);
    expect(await verifyPassword("", stored)).toBe(false);
    expect(await verifyPassword("pin-1234", "garbage")).toBe(false);
    expect(await verifyPassword("pin-1234", "pbkdf2$sha256$abc$!!$??")).toBe(false);
  });

  it("كل تجزئة ملح فريد — لا خزن حتمي يكشف التطابق", async () => {
    const a = await hashPassword("نفس-ال pin");
    const b = await hashPassword("نفس-ال pin");
    expect(a).not.toBe(b);
    expect(await verifyPassword("نفس-ال pin", a)).toBe(true);
    expect(await verifyPassword("نفس-ال pin", b)).toBe(true);
  });
});

describe("عقد الجلسة — HMAC-SHA256", () => {
  it("توقيع ← تحقق ذهاب/إياب بحمولة مالك كاملة (sub/iat/exp/jti)", async () => {
    const { token, payload } = await signSession(SECRET);
    expect(payload.sub).toBe("owner");
    expect(payload.exp - payload.iat).toBe(SESSION_TTL_MS);
    expect(payload.jti).toBeTruthy();
    expect(token).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
    const verified = await verifySession(token, SECRET);
    expect(verified).not.toBeNull();
    expect(verified?.sub).toBe("owner");
    expect(verified?.jti).toBe(payload.jti);
  });

  it("عبث بأي جزء (حمولة/توقيع) أو سر خاطئ أو انتهاء = رفض صامت null", async () => {
    const { token } = await signSession(SECRET);
    const [p, s] = token.split(".");
    // عبث بالحمولة
    const payloadB64 = Buffer.from(JSON.stringify({ sub: "hacker", iat: 0, exp: 9e15, jti: "x" })).toString(
      "base64url",
    );
    expect(await verifySession(`${payloadB64}.${s}`, SECRET)).toBeNull();
    // عبث بالتوقيع
    expect(await verifySession(`${p}.${s.slice(0, -2)}xx`, SECRET)).toBeNull();
    // سر آخر
    expect(await verifySession(token, "another-secret")).toBeNull();
    // انتهاء صلاحية
    const expired = await signSession(SECRET, { nowMs: Date.now() - SESSION_TTL_MS - 1 });
    expect(await verifySession(expired.token, SECRET)).toBeNull();
    // بنى تالفة
    expect(await verifySession("not-a-token", SECRET)).toBeNull();
    expect(await verifySession("a.b.c", SECRET)).toBeNull();
    expect(await verifySession("!!!.???", SECRET)).toBeNull();
  });
});

describe("سياسة البوابة — تصنيف المسارات (مقفول افتراضيًا G5)", () => {
  it("طرق الكتابة هي POST/PUT/PATCH/DELETE حصراً", () => {
    for (const m of ["POST", "PUT", "PATCH", "DELETE"]) expect(isWriteMethod(m)).toBe(true);
    for (const m of ["GET", "HEAD", "OPTIONS"]) expect(isWriteMethod(m)).toBe(false);
  });

  it("قراءات البيانات المالية محمية — و/api/roadmap القراءة العامة الوحيدة", () => {
    for (const p of ["/api/dashboard", "/api/invoices", "/api/parties", "/api/payments", "/api/products", "/api/expenses", "/api/reports"]) {
      expect(isFinancialRead(p)).toBe(true);
      expect(isPublicRead(p)).toBe(false);
    }
    expect(isPublicRead("/api/roadmap")).toBe(true);
    expect(isPublicRead("/api/unknown")).toBe(false);
    expect(isFinancialRead("/api/roadmap")).toBe(false);
  });

  it("مساحة /api/auth/* عامة هي الباب الوحيد لإصدار الجلسة", () => {
    for (const p of ["/api/auth/login", "/api/auth/session", "/api/auth/logout"]) {
      expect(isAuthNamespace(p)).toBe(true);
    }
    expect(isAuthNamespace("/api/invoices")).toBe(false);
    expect(isAuthNamespace("/api/auth")).toBe(true); // النطاق كله
  });

  it("assertSameOrigin: نفس المضيف ✓ / مضيف آخر ✗ / بلا Origin (عميل غير متصفح) = null حياد", () => {
    expect(assertSameOrigin("https://portal.example.com", ["portal.example.com"])).toBe(true);
    expect(assertSameOrigin("https://evil.example.net", ["portal.example.com"])).toBe(false);
    expect(assertSameOrigin(null, ["portal.example.com"])).toBeNull();
  });
});

describe("حد المعدل — نافذة منزلقة", () => {
  it("يمتص حتى الحد ثم يمنع مع retryAfterSec، ويستأنف بعد مرور النافذة", () => {
    const rl = createRateLimiter({ windowMs: 1_000, max: 3 });
    expect(rl.hit("ip1", 0).ok).toBe(true);
    expect(rl.hit("ip1", 10).ok).toBe(true);
    expect(rl.hit("ip1", 20).ok).toBe(true);
    const blocked = rl.hit("ip1", 30);
    expect(blocked.ok).toBe(false);
    expect(blocked.retryAfterSec).toBeGreaterThan(0);
    // نافذة جديدة
    expect(rl.hit("ip1", 1_100).ok).toBe(true);
  });

  it("المفاتيح مستقلة — ولا يُحجب المالك بسبب عميل آخر", () => {
    const rl = createRateLimiter({ windowMs: 1_000, max: 1 });
    expect(rl.hit("a", 0).ok).toBe(true);
    expect(rl.hit("b", 1).ok).toBe(true);
    expect(rl.hit("a", 2).ok).toBe(false);
  });
});

describe("ثوابت الجلسة", () => {
  it("اسم الكوكة ثابت للعقد بين proxy وroutes واختبارات التكامل", () => {
    expect(SESSION_COOKIE).toBe("sb_session");
    expect(SESSION_TTL_MS).toBe(12 * 60 * 60 * 1000); // 12 ساعة
  });
});
