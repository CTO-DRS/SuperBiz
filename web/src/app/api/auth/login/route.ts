/**
 * W1.5 (Issue #39) — دخول المالك: كلمة مرور واحدة من البيئة، لا حسابات ولا أطراف ثالثة.
 * قرار ADR-004: اعتماد واحد من env (أو بصمة PBKDF2 جاهزة) + جلسة HMAC 12 ساعة.
 * حد المعدل داخلي صارم: 5 محاولات/5 دقائق لكل IP — يشمل المحاولات الناجحة.
 */
import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE, signSession } from "@/lib/auth";
import {
  checkOwnerPassword,
  clientIp,
  loginRateLimiter,
  ownerConfigured,
  ownerSecret,
  sessionCookieOptions,
} from "@/lib/authGate";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const rl = loginRateLimiter.hit(clientIp(req));
  if (!rl.ok) {
    return NextResponse.json(
      { error: "محاولات كثيرة — حاول بعد قليل", code: "RATE_LIMITED", retryAfterSec: rl.retryAfterSec },
      { status: 429 },
    );
  }

  if (!ownerConfigured()) {
    return NextResponse.json(
      {
        error:
          "لم تُضبط كلمة مرور المالك — اضبط SUPERBIZ_OWNER_PASSWORD (أو SUPERBIZ_OWNER_PASSWORD_HASH) ثم أعد تشغيل البوابة",
        code: "AUTH_NOT_CONFIGURED",
      },
      { status: 503 },
    );
  }

  const body = await req.json().catch(() => null);
  const password = typeof body?.password === "string" ? body.password : "";
  if (!password) {
    return NextResponse.json({ error: "أدخل كلمة مرور المالك", code: "BAD_REQUEST" }, { status: 400 });
  }

  if (!(await checkOwnerPassword(password))) {
    return NextResponse.json({ error: "كلمة المرور غير صحيحة", code: "INVALID_CREDENTIALS" }, { status: 401 });
  }

  const { token } = await signSession(await ownerSecret());
  const res = NextResponse.json({ ok: true, owner: "مالك البوابة" });
  res.cookies.set(SESSION_COOKIE, token, sessionCookieOptions());
  return res;
}
