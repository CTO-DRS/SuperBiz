/**
 * W1.5 (Issue #39) — نواة المصادقة النقية: جلسة مالك موقعة HMAC-SHA256 +
 * بيانات اعتماد PBKDF2 + سياسة تصنيف المسارات + حد معدل منزلق.
 *
 * قيود صارمة (غير قابلة للتفاوض):
 * - WebCrypto صرفة (globalThis.crypto) — آمنة للـedge حيث يعمل proxy،
 *   وبلا أي استيراد من node:crypto أو عقد شبكي (G1).
 * - مقفول افتراضيًا (G5): كل مسار /api ممنوع إلا ما سُمح صراحة في هذه الوحدة.
 * - لا أسرار في الكود: السر من البيئة، والتحقق صامت الفشل (null/false) لا يرمي.
 */

// ─── الثوابت ────────────────────────────────────────────────────────────────

/** اسم كوكة الجلسة — عقد مشترك بين proxy وroutes وصفحة الدخول والاختبارات. */
export const SESSION_COOKIE = "sb_session";

/** عمر الجلسة: 12 ساعة — بعدها إعادة دخول إلزامية. */
export const SESSION_TTL_MS = 12 * 60 * 60 * 1000;

/** دورات PBKDF2 — توازن مقبول لدخول مالك واحد (OWASP 2023: ≥600k لSHA-512، 210k لSHA-256). */
export const PBKDF2_ITERATIONS = 210_000;

export type SessionPayload = {
  sub: "owner";
  iat: number;
  exp: number;
  jti: string;
};

// ─── أدوات ترميز صرفة ───────────────────────────────────────────────────────

const enc = new TextEncoder();

function b64urlDecode(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function bytesToB64(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** مقارنة زمن-ثابت لسلسلتين — لا تسريب توقيت على البايتات. */
export function timingSafeEqualStr(a: string, b: string): boolean {
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  const n = Math.max(ab.length, bb.length);
  let diff = ab.length ^ bb.length;
  for (let i = 0; i < n; i++) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

// ─── بيانات الاعتماد — PBKDF2-SHA256 ────────────────────────────────────────

/** ينتج `pbkdf2$sha256$<iter>$<saltB64>$<hashB64>` بملح عشوائي 16 بايت. */
export async function hashPassword(password: string, iterations = PBKDF2_ITERATIONS): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(password, salt, iterations);
  return `pbkdf2$sha256$${iterations}$${bytesToB64(salt)}$${bytesToB64(hash)}`;
}

/** تحقق صامت: أي صيغة تالفة/كلمة خاطئة = false بلا رمي. */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  try {
    const parts = stored.split("$");
    if (parts.length !== 5 || parts[0] !== "pbkdf2" || parts[1] !== "sha256") return false;
    const iterations = Number(parts[2]);
    if (!Number.isInteger(iterations) || iterations < 1 || iterations > 10_000_000) return false;
    const salt = b64ToBytes(parts[3]);
    const expected = b64ToBytes(parts[4]);
    const actual = await pbkdf2(password, salt, iterations);
    if (actual.length !== expected.length) return false;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= actual[i] ^ expected[i];
    return diff === 0;
  } catch {
    return false;
  }
}

async function pbkdf2(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as BufferSource, iterations },
    key,
    256,
  );
  return new Uint8Array(bits);
}

// ─── الجلسة — توقيع HMAC-SHA256 مضغوط ───────────────────────────────────────

export type SignOptions = { nowMs?: number; ttlMs?: number };

export async function signSession(
  secret: string,
  opts: SignOptions = {},
): Promise<{ token: string; payload: SessionPayload }> {
  const nowMs = opts.nowMs ?? Date.now();
  const ttlMs = opts.ttlMs ?? SESSION_TTL_MS;
  const payload: SessionPayload = {
    sub: "owner",
    iat: nowMs,
    exp: nowMs + ttlMs,
    jti: crypto.randomUUID(),
  };
  const body = b64urlEncode(enc.encode(JSON.stringify(payload)));
  const sig = b64urlEncode(await hmac(secret, body));
  return { token: `${body}.${sig}`, payload };
}

/** تحقق صامت: توقيع + صلاحية + sub=owner — أي خلل = null. */
export async function verifySession(
  token: string,
  secret: string,
  nowMs = Date.now(),
): Promise<SessionPayload | null> {
  try {
    const segs = token.split(".");
    if (segs.length !== 2) return null;
    const [body, sig] = segs;
    if (!body || !sig) return null;
    const expected = await hmac(secret, body);
    const given = b64urlDecode(sig);
    if (given.length !== expected.length) return null;
    let diff = 0;
    for (let i = 0; i < expected.length; i++) diff |= expected[i] ^ given[i];
    if (diff !== 0) return null;
    const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(body))) as SessionPayload;
    if (payload.sub !== "owner") return null;
    if (typeof payload.exp !== "number" || payload.exp <= nowMs) return null;
    if (typeof payload.iat !== "number" || typeof payload.jti !== "string") return null;
    return payload;
  } catch {
    return null;
  }
}

async function hmac(secret: string, data: string): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(data)));
}

// ─── سياسة تصنيف المسارات (مصدر الحقيقة الوحيد — proxy وchecker وtests) ─────

/** طرق الكتابة الممنوعة بلا جلسة — POST/PUT/PATCH/DELETE حصراً. */
export function isWriteMethod(method: string): boolean {
  return method === "POST" || method === "PUT" || method === "PATCH" || method === "DELETE";
}

/** قراءات البيانات المالية — تُحمى بالجلسة نفسها (proxy). */
const FINANCIAL_READS = [
  "/api/dashboard",
  "/api/invoices",
  "/api/parties",
  "/api/payments",
  "/api/products",
  "/api/expenses",
  "/api/reports",
];

export function isFinancialRead(pathname: string): boolean {
  return FINANCIAL_READS.includes(pathname);
}

/** القراءة العامة الوحيدة المسموحة — بيانات خارطة الطريق (لا شيء مالي). */
export function isPublicRead(pathname: string): boolean {
  return pathname === "/api/roadmap";
}

/** نطاق المصادقة — الباب الوحيد لإصدار/فحص/إنهاء الجلسة (معفى من الجلسة نفسها). */
export function isAuthNamespace(pathname: string): boolean {
  return pathname === "/api/auth" || pathname.startsWith("/api/auth/");
}

/**
 * فحص CSRF بـOrigin: true المضيف مسموح، false مضيف آخر، null بلا ترويسة
 * (عميل غير متصفح مثل curl — لا يُحجب هنا، الحماية تبقى بالجلسة + SameSite=Strict).
 * المضيفون المسموحون: نص واحد أو قائمة (النشر خلف نطاق/نطاق معاينة).
 */
export function assertSameOrigin(
  origin: string | null | undefined,
  allowedHosts: string | string[],
): boolean | null {
  if (!origin) return null;
  const hosts = Array.isArray(allowedHosts) ? allowedHosts : [allowedHosts];
  try {
    return hosts.includes(new URL(origin).host);
  } catch {
    return false;
  }
}

// ─── حد المعدل — نافذة منزلقة في الذاكرة (بوابة مالك واحدة — وثيقة ADR-004) ──

export type RateLimitOptions = { windowMs: number; max: number };
export type RateLimitResult = { ok: boolean; remaining: number; retryAfterSec: number };

export type RateLimiter = {
  hit(key: string, nowMs?: number): RateLimitResult;
  reset(): void;
};

export function createRateLimiter(opts: RateLimitOptions): RateLimiter {
  const hits = new Map<string, number[]>();
  return {
    hit(key, nowMs = Date.now()) {
      const windowStart = nowMs - opts.windowMs;
      const arr = (hits.get(key) ?? []).filter((t) => t > windowStart);
      if (arr.length >= opts.max) {
        const oldest = arr[0]!;
        hits.set(key, arr);
        return {
          ok: false,
          remaining: 0,
          retryAfterSec: Math.max(1, Math.ceil((oldest + opts.windowMs - nowMs) / 1000)),
        };
      }
      arr.push(nowMs);
      hits.set(key, arr);
      return { ok: true, remaining: opts.max - arr.length, retryAfterSec: 0 };
    },
    reset() {
      hits.clear();
    },
  };
}
