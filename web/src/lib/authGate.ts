/**
 * W1.5 (Issue #39) — بوابة الطلب المركزية: القرار الموحد (proxy) + حرس الـhandlers.
 *
 * ثلاث قفل على كل طلب كتابة، وقفلان على كل قراءة مالية:
 *   1. حد معدل (429)      — يسبق كل شيء لإخماد التخمين/الإغراق.
 *   2. فحص CSRF بـOrigin (403) — على الكتابة فقط.
 *   3. الجلسة (401)       — الكتابة + القراءات المالية؛ /api/roadmap العامة.
 *
 * الفشل مُغلق دائمًا: بلا اعتماديات مضبوطة = لا دخول إطلاقًا (G5).
 */
import { NextRequest, NextResponse } from "next/server";
import {
  SESSION_COOKIE,
  SESSION_TTL_MS,
  assertSameOrigin,
  createRateLimiter,
  hashPassword,
  isAuthNamespace,
  isFinancialRead,
  isPublicRead,
  isWriteMethod,
  verifyPassword,
  verifySession,
  type RateLimiter,
} from "@/lib/auth";

// ─── حدود المعدل (ذاكرة العملية — بوابة مالك واحدة، موثق في ADR-004 §حدود) ──

/** الكتابة: 120 طلب/دقيقة لكل IP. */
export const writeRateLimiter: RateLimiter = createRateLimiter({ windowMs: 60_000, max: 120 });
/** تسجيل الدخول: 5 محاولات/5 دقائق لكل IP — إخماد تخمين الـPIN. */
export const loginRateLimiter: RateLimiter = createRateLimiter({ windowMs: 5 * 60_000, max: 5 });

// ─── الاعتماديات من البيئة (لا أسرار في الكود) ─────────────────────────────

const mem: { secret?: string; cred?: Promise<string | null> } = {};

/**
 * بصمة اعتماد المالك: `SUPERBIZ_OWNER_PASSWORD_HASH` (pbkdf2$sha256$…)
 * أو مشتقة مرة واحدة في الذاكرة من `SUPERBIZ_OWNER_PASSWORD`.
 * لا شيء منهما = null (البوابة مغلقة والـlogin يعيد 503).
 */
export function getOwnerCredentialHash(): Promise<string | null> {
  if (!mem.cred) {
    mem.cred = (async () => {
      const h = process.env.SUPERBIZ_OWNER_PASSWORD_HASH;
      if (h && h.startsWith("pbkdf2$sha256$")) return h;
      const pw = process.env.SUPERBIZ_OWNER_PASSWORD;
      if (pw && pw.length >= 4) return hashPassword(pw);
      return null;
    })();
  }
  return mem.cred;
}

export function ownerConfigured(): boolean {
  const h = process.env.SUPERBIZ_OWNER_PASSWORD_HASH;
  return Boolean((h && h.startsWith("pbkdf2$sha256$")) || process.env.SUPERBIZ_OWNER_PASSWORD);
}

/** سر توقيع الجلسة: `SUPERBIZ_SESSION_SECRET` أو مشتق حتميًا من بصمة الاعتماد. */
export async function ownerSecret(): Promise<string> {
  if (mem.secret) return mem.secret;
  const envSecret = process.env.SUPERBIZ_SESSION_SECRET;
  if (envSecret && envSecret.length >= 16) {
    mem.secret = envSecret;
    return envSecret;
  }
  const cred = await getOwnerCredentialHash();
  if (!cred) throw new Error("AUTH_NOT_CONFIGURED");
  mem.secret = `sb-session-v1::${cred}`;
  return mem.secret;
}

// ─── استخراج هوية الطالب ────────────────────────────────────────────────────

export function clientIp(req: NextRequest): string {
  const fwd = req.headers.get("x-forwarded-for");
  if (fwd) return fwd.split(",")[0]!.trim();
  return req.headers.get("x-real-ip") ?? "local";
}

// ─── القرار النقي (مُختبَر جدوليًا) — مصدر حقيقة الـproxy ────────────────────

export type GateInput = {
  hasSession: boolean;
  origin?: string | null;
  host?: string;
  rateLimited?: boolean;
};
export type GateDecision = { status: 200 | 401 | 403 | 429; code?: string; error?: string; retryAfterSec?: number };

export function gateDecision(pathname: string, method: string, input: GateInput): GateDecision {
  // نطاق المصادقة عام دائمًا — هو من يصدر الجلسة (حد المعدل داخلي في login).
  if (isAuthNamespace(pathname)) return { status: 200 };
  const write = isWriteMethod(method);
  if (write && input.rateLimited) return { status: 429, code: "RATE_LIMITED", error: "طلبات كثيرة — حاول بعد قليل" };
  if (write && input.origin !== undefined && input.origin !== null) {
    if (assertSameOrigin(input.origin, input.host ?? "") === false) {
      return { status: 403, code: "CSRF_ORIGIN", error: "طلب من مصدر غير مطابق — مرفوض حمايةً من CSRF" };
    }
  }
  const needsSession = write || isFinancialRead(pathname);
  if (needsSession && !input.hasSession) {
    return { status: 401, code: "AUTH_REQUIRED", error: "يجب تسجيل الدخول كمالك للبوابة" };
  }
  return { status: 200 };
}

// ─── ردود الخطأ الموحدة ─────────────────────────────────────────────────────

function jsonErr(status: 401 | 403 | 429, code: string, error: string, extra: Record<string, unknown> = {}) {
  return NextResponse.json({ error, code, ...extra }, { status });
}

// ─── حرس الـproxy (القفل المركزي على /api/*) ────────────────────────────────

export async function hasValidSession(req: NextRequest): Promise<boolean> {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  if (!token) return false;
  try {
    return (await verifySession(token, await ownerSecret())) !== null;
  } catch {
    return false; // بلا سر مضبوط = لا جلسة صالحة إطلاقًا (فشل مغلق)
  }
}

export async function gateFor(req: NextRequest): Promise<NextResponse> {
  const { pathname } = req.nextUrl;
  // نطاق المصادقة أولًا — هو الباب الذي يصدر الجلسة (حد المعدل الداخلي في login نفسه)
  if (isAuthNamespace(pathname)) return NextResponse.next();
  const method = req.method;
  const write = isWriteMethod(method);

  if (write) {
    const rl = writeRateLimiter.hit(clientIp(req));
    if (!rl.ok) return jsonErr(429, "RATE_LIMITED", "طلبات كثيرة — حاول بعد قليل", { retryAfterSec: rl.retryAfterSec });
  }

  if (write && assertSameOrigin(req.headers.get("origin"), req.headers.get("host") ?? "") === false) {
    return jsonErr(403, "CSRF_ORIGIN", "طلب من مصدر غير مطابق — مرفوض حمايةً من CSRF");
  }

  if (isPublicRead(pathname) && method === "GET") return NextResponse.next();

  if ((write || isFinancialRead(pathname)) && !(await hasValidSession(req))) {
    return jsonErr(401, "AUTH_REQUIRED", "يجب تسجيل الدخول كمالك للبوابة");
  }
  return NextResponse.next();
}

// ─── حرس الـhandlers (القفل الثاني داخل كل route كتابة — يفرضه فاحص CI) ─────

/** null = الجلسة صحيحة، تابع المعالج. غير ذلك = رد 401 جاهز. */
export async function requireSession(req: NextRequest): Promise<NextResponse | null> {
  const token = req.cookies.get(SESSION_COOKIE)?.value;
  const valid =
    token
      ? await (async () => {
          try {
            return (await verifySession(token, await ownerSecret())) !== null;
          } catch {
            return false;
          }
        })()
      : false;
  return valid ? null : jsonErr(401, "AUTH_REQUIRED", "يجب تسجيل الدخول كمالك للبوابة");
}

// ─── أدوات مسارات المصادقة ──────────────────────────────────────────────────

export async function checkOwnerPassword(password: string): Promise<boolean> {
  const cred = await getOwnerCredentialHash();
  if (!cred) return false;
  return verifyPassword(password, cred);
}

export function sessionCookieOptions(maxAgeSec = Math.floor(SESSION_TTL_MS / 1000)) {
  return {
    httpOnly: true,
    sameSite: "strict" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: maxAgeSec,
  };
}
