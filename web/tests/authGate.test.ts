/**
 * اختبارات تكامل W1.5 — بوابة الطلب المركزية + حرس الـhandlers + فاحص CI (Issue #39).
 * معيار القبول الحرفي: «طلب كتابة بلا جلسة يرجع 401، ومعه اختبار تكامل يثبت ذلك».
 */
import { describe, it, expect } from "vitest";
import { execSync } from "node:child_process";
import { NextRequest } from "next/server";
import { signSession, SESSION_COOKIE } from "@/lib/auth";
import { gateDecision, requireSession } from "@/lib/authGate";
import { auditRouteFile, auditProxyFile, auditTree } from "../tools/check_auth_gate.mjs";

// سر الجلسة من البيئة — نفس العقد في الإنتاج (لا أسرار في الكود، البيئة هنا اختبارية)
process.env.SUPERBIZ_SESSION_SECRET = "test-secret-بجنون-123";
const SECRET = "test-secret-بجنون-123";
const reqOf = (path: string, method = "GET", opts: { cookie?: string; origin?: string } = {}) =>
  new NextRequest(`http://localhost:3000${path}`, {
    method,
    headers: {
      ...(opts.cookie ? { cookie: opts.cookie } : {}),
      ...(opts.origin ? { origin: opts.origin } : {}),
      ...(opts.origin ? { host: new URL(opts.origin).host } : { host: "localhost:3000" }),
    },
  });

describe("معيار القبول: طلب كتابة بلا جلسة = 401 مختبر", () => {
  it("POST فاتورة بلا كوكة → 401 {code: AUTH_REQUIRED} عبر requireSession (حرس الـhandler)", async () => {
    const res = await requireSession(reqOf("/api/invoices", "POST"));
    expect(res).not.toBeNull();
    expect(res!.status).toBe(401);
    const body = await res!.json();
    expect(body.code).toBe("AUTH_REQUIRED");
  });

  it("PATCH منتج بلا جلسة → 401، وبجلسة صحيحة → null (يمرر للمعالج)", async () => {
    expect((await requireSession(reqOf("/api/products", "PATCH")))!.status).toBe(401);
    const { token } = await signSession(SECRET);
    expect(await requireSession(reqOf("/api/products", "PATCH", { cookie: `${SESSION_COOKIE}=${token}` }))).toBeNull();
  });

  it("كوكة منقولة العبث أو تالفة → 401 لا انهيار", async () => {
    const { token } = await signSession(SECRET);
    expect((await requireSession(reqOf("/api/invoices", "POST", { cookie: `${SESSION_COOKIE}=${token}x` })))!.status).toBe(401);
    expect((await requireSession(reqOf("/api/invoices", "POST", { cookie: `${SESSION_COOKIE}=garbage` })))!.status).toBe(401);
    expect(await requireSession(reqOf("/api/invoices", "POST", { cookie: "other=1" }))).not.toBeNull();
  });

  it("قراءة مالية بلا جلسة → 401 عبر gateDecision (حرس الـproxy) — و/api/roadmap العامة تمر", () => {
    expect(gateDecision("/api/dashboard", "GET", { hasSession: false }).status).toBe(401);
    expect(gateDecision("/api/invoices", "GET", { hasSession: false }).status).toBe(401);
    expect(gateDecision("/api/roadmap", "GET", { hasSession: false }).status).toBe(200);
    expect(gateDecision("/api/roadmap", "GET", { hasSession: false }).code).toBeUndefined();
  });

  it("مصفوفة القرار الكاملة: جلسة ✓ تمر للكل (عدا CSRF)؛ جلسة ✗ يُمنع الكتابة والقراءة المالية", () => {
    // بلا جلسة
    for (const [p, m] of [["/api/invoices", "POST"], ["/api/parties", "PATCH"], ["/api/payments", "POST"], ["/api/expenses", "POST"], ["/api/products", "DELETE"]]) {
      const d = gateDecision(p, m, { hasSession: false });
      expect(d.status).toBe(401);
      expect(d.code).toBe("AUTH_REQUIRED");
    }
    // بجلسة + نفس المضيف → تمر
    for (const [p, m] of [["/api/invoices", "POST"], ["/api/dashboard", "GET"], ["/api/products", "PATCH"]]) {
      expect(gateDecision(p, m, { hasSession: true, origin: "http://localhost:3000", host: "localhost:3000" }).status).toBe(200);
    }
    // CSRF: جلسة صحيحة لكن Origin مضيف آخر → 403
    const csrf = gateDecision("/api/invoices", "POST", { hasSession: true, origin: "https://evil.net", host: "localhost:3000" });
    expect(csrf.status).toBe(403);
    expect(csrf.code).toBe("CSRF_ORIGIN");
  });

  it("حد المعدل في القرار: عند امتلاء النافذة → 429 RATE_LIMITED قبل أي فحص آخر", () => {
    const d = gateDecision("/api/invoices", "POST", { hasSession: false, rateLimited: true });
    expect(d.status).toBe(429);
    expect(d.code).toBe("RATE_LIMITED");
  });

  it("مساحة auth دائماً متاحة (هي من يصدر الجلسة) — حتى مع حد معدل على مستوى المسار الداخلي", () => {
    for (const [p, m] of [["/api/auth/login", "POST"], ["/api/auth/session", "GET"], ["/api/auth/logout", "POST"]]) {
      expect(gateDecision(p, m, { hasSession: false, rateLimited: true }).status).toBe(200);
    }
  });
});

describe("فاحص CI — أي handler كتابة بلا بوابة = أحمر (نمط check_network_boundary.py)", () => {
  it("POST بلا requireSession → مخالفة / معه → نظيف", () => {
    const bad = `import { NextRequest } from "next/server";\nexport async function POST(req: NextRequest) { return Response.json({}); }`;
    const good = `import { requireSession } from "@/lib/authGate";\nexport async function POST(req: NextRequest) { const g = await requireSession(req); if (g) return g; return Response.json({}); }`;
    expect(auditRouteFile("src/app/api/invoices/route.ts", bad).violations.length).toBeGreaterThan(0);
    expect(auditRouteFile("src/app/api/invoices/route.ts", good).violations).toHaveLength(0);
  });

  it("كل طرق الكتابة الأربعة مفحوصة (POST/PUT/PATCH/DELETE) والقراءة GET مستثناة", () => {
    const src = (h: string) => `export async function ${h}(req: Request) { return new Response(); }`;
    for (const h of ["POST", "PUT", "PATCH", "DELETE"]) {
      expect(auditRouteFile("src/app/api/x/route.ts", src(h)).violations.length).toBeGreaterThan(0);
    }
    expect(auditRouteFile("src/app/api/x/route.ts", src("GET")).violations).toHaveLength(0);
  });

  it("مساحة /api/auth/* معفاة (هي الباب)، والحارس يفحص الأسماء النسبية الصحيحة فقط", () => {
    const post = `export async function POST(req: Request) { return new Response(); }`;
    expect(auditRouteFile("src/app/api/auth/login/route.ts", post).violations).toHaveLength(0);
  });

  it("proxy بلا matcher يغطي /api أو بلا استيراد للبوابة → مخالفة", () => {
    const noMatcher = `import { NextResponse } from "next/server";\nexport function proxy() { return NextResponse.next(); }\nexport const config = { matcher: ["/other/:path*"] };`;
    expect(auditProxyFile(noMatcher).violations.length).toBeGreaterThan(0);
    const noGate = `import { NextResponse } from "next/server";\nexport function proxy() { return NextResponse.next(); }\nexport const config = { matcher: ["/api/:path*"] };`;
    expect(auditProxyFile(noGate).violations.length).toBeGreaterThan(0);
    const ok = `import { gateFor } from "@/lib/authGate";\nimport { type NextRequest } from "next/server";\nexport default async function proxy(req: NextRequest) { return gateFor(req); }\nexport const config = { matcher: ["/api/:path*"] };`;
    expect(auditProxyFile(ok).violations).toHaveLength(0);
  });

  it("شجرة المستودع الحالية كلها نظيفة — الفاحص يعمل CLI ويخرج 0", () => {
    const out = execSync("node tools/check_auth_gate.mjs", { cwd: __dirname + "/..", encoding: "utf8" });
    expect(out).toContain("auth-gate: OK");
  });

  it("auditTree يلتقط شجرة مركبة بمخالفة واحدة", () => {
    const tree = [
      { path: "src/app/api/invoices/route.ts", content: "export async function POST(req){}" },
      { path: "src/app/api/products/route.ts", content: 'import { requireSession } from "@/lib/authGate";\nexport async function POST(req){ await requireSession(req); }' },
      { path: "src/proxy.ts", content: 'import { gateFor } from "@/lib/authGate";\nexport const config = { matcher: ["/api/:path*"] };' },
    ];
    const r = auditTree(tree);
    expect(r.ok).toBe(false);
    expect(r.reports[0].violations.length).toBe(1);
    expect(r.reports[1].violations).toHaveLength(0);
  });
});
