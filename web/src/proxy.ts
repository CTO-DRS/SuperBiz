/**
 * W1.5 (Issue #39) — القفل المركزي على البوابة (proxy — التسمية المعتمدة في Next 16).
 * كل مسار /api/* يمر من هنا: حد معدل → CSRF → جلسة مالك (فشل مغلق G5).
 * القراءة العامة الوحيدة: /api/roadmap — والتفاصيل في docs/ADR-004_WEB_AUTH.md.
 */
import { type NextRequest } from "next/server";
import { gateFor } from "@/lib/authGate";

export default async function proxy(req: NextRequest) {
  return gateFor(req);
}

export const config = {
  matcher: ["/api/:path*"],
};
