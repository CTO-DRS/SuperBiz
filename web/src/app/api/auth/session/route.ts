/**
 * W1.5 (Issue #39) — فحص حالة الجلسة الحالية (بلا تسريب أي حمولة).
 * عام بقصد: الواجهة تسأل «هل أنا داخل؟» قبل عرض شاشة الدخول.
 */
import { NextRequest, NextResponse } from "next/server";
import { hasValidSession } from "@/lib/authGate";

export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  return NextResponse.json({ authenticated: await hasValidSession(req) });
}
