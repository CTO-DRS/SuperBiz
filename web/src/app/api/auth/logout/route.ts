/**
 * W1.5 (Issue #39) — خروج المالك: يمسح الكوكة فوراً (بلا حاجة لجلسة — الخروج لا يكشف شيئاً).
 */
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";
import { sessionCookieOptions } from "@/lib/authGate";

export const dynamic = "force-dynamic";

export async function POST() {
  const res = NextResponse.json({ ok: true });
  res.cookies.set(SESSION_COOKIE, "", sessionCookieOptions(0));
  return res;
}
