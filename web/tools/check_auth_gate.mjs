/**
 * W1.5 (Issue #39) — فاحص بوابة المصادقة (نمط check_network_boundary.py).
 *
 * العقد: أي handler كتابة (POST/PUT/PATCH/DELETE) في src/app/api/**
 * خارج نطاق /api/auth/* يجب أن يستدعي `requireSession(` (القفل الثاني)،
 * وproxy.ts يجب أن يغطي /api/:path* بالكامل ويستورد بوابة authGate (القفل الأول).
 * أي جديد بلا بوابة = أحمر يكسر CI.
 *
 * الاستخدام: node tools/check_auth_gate.mjs  (من مجلد web/)
 * الاستيراد النقي للاختبارات: auditRouteFile / auditProxyFile / auditTree
 */
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const WRITE_FN_RE = /export\s+(?:async\s+)?function\s+(POST|PUT|PATCH|DELETE)\b/g;
const WRITE_ARROW_RE = /export\s+const\s+(POST|PUT|PATCH|DELETE)\s*[:=]/g;
const REQUIRE_SESSION_RE = /requireSession\s*\(/;
const AUTH_NS_RE = /^src\/app\/api\/auth\//;
const GATE_IMPORT_RE = /["']@\/lib\/authGate["']|["']@\/lib\/auth["']/;
const GATE_CALL_RE = /gateFor\s*\(|requireSession\s*\(/;
const API_MATCHER_RE = /["']\/api\/(?::path\*|[^"']*:path\*)["']/;

export function findWriteHandlers(src) {
  const names = new Set();
  for (const m of src.matchAll(WRITE_FN_RE)) names.add(m[1]);
  for (const m of src.matchAll(WRITE_ARROW_RE)) names.add(m[1]);
  return [...names];
}

/** ملف route واحد → قائمة مخالفات (فارغة = نظيف). مساحة /api/auth/* معفاة (هي الباب). */
export function auditRouteFile(relPath, src) {
  const violations = [];
  if (AUTH_NS_RE.test(relPath.replaceAll("\\", "/"))) return { path: relPath, violations };
  const handlers = findWriteHandlers(src);
  if (handlers.length > 0 && !REQUIRE_SESSION_RE.test(src)) {
    violations.push(
      `handler كتابة (${handlers.join(", ")}) بلا بوابة — يجب استدعاء requireSession(req) في بداية كل طريقة كتابة`,
    );
  }
  return { path: relPath, violations };
}

/** proxy.ts → مخالفات إن لم يغطِّ /api بالكامل أو لم يستورد/يستدعِ بوابة authGate. */
export function auditProxyFile(src) {
  const violations = [];
  if (!API_MATCHER_RE.test(src)) violations.push("config.matcher لا يغطي /api/:path* — الكتابة قابلة للتسرب");
  if (!GATE_IMPORT_RE.test(src)) violations.push('لا استيراد من "@/lib/authGate" — البوابة المركزية غير موصولة');
  if (!GATE_CALL_RE.test(src)) violations.push("لا استدعاء لـgateFor/requireSession — البوابة معلقة بلا تنفيذ");
  return { path: "src/proxy.ts", violations };
}

/** شجرة ملفات {path, content} → تقرير بترتيب الإدخال + ok جماعي. */
export function auditTree(files) {
  const reports = [];
  let ok = true;
  for (const f of files) {
    const isProxy = /(^|\/)src\/proxy\.ts$/.test(f.path.replaceAll("\\", "/"));
    const r = isProxy ? auditProxyFile(f.content) : auditRouteFile(f.path, f.content);
    if (r.violations.length > 0) ok = false;
    reports.push(r);
  }
  return { ok, reports };
}

// ─── CLI ────────────────────────────────────────────────────────────────────

function walk(dir, acc = []) {
  if (!existsSync(dir)) return acc;
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name);
    if (statSync(p).isDirectory()) walk(p, acc);
    else if (name === "route.ts" || name === "route.tsx") acc.push(p);
  }
  return acc;
}

function main() {
  const root = process.cwd();
  const files = walk(path.join(root, "src", "app", "api")).map((abs) => ({
    path: path.relative(root, abs).replaceAll("\\", "/"),
    content: readFileSync(abs, "utf8"),
  }));

  const proxyPath = path.join(root, "src", "proxy.ts");
  if (existsSync(proxyPath)) {
    files.unshift({ path: "src/proxy.ts", content: readFileSync(proxyPath, "utf8") });
  } else if (existsSync(path.join(root, "src", "middleware.ts"))) {
    files.unshift({ path: "src/proxy.ts", content: readFileSync(path.join(root, "src", "middleware.ts"), "utf8") });
  } else {
    files.unshift({ path: "src/proxy.ts", content: "" });
  }

  const { ok, reports } = auditTree(files);
  let violations = 0;
  for (const r of reports) {
    if (r.violations.length === 0) {
      console.log(`  ✓ ${r.path}`);
    } else {
      for (const v of r.violations) {
        violations++;
        console.error(`  ✗ ${r.path}: ${v}`);
      }
    }
  }
  if (ok) {
    console.log(`auth-gate: OK (${reports.length} ملفاً مفحوصاً — كل الكتابة خلف بوابة المصادقة)`);
    return 0;
  }
  console.error(`auth-gate: FAILED (${violations} مخالفة)`);
  return 1;
}

if (process.argv[1] && process.argv[1].endsWith("check_auth_gate.mjs")) {
  process.exit(main());
}
