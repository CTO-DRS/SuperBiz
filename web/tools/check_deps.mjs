#!/usr/bin/env node
// [W1.6 / Issue #40] فاحص اعتماديات بوابة الويب — أي حزمة إنتاج معلنة بلا أي استيراد = أحمر.
// نمط فاحصي المشروع (check_network_boundary.py / check_auth_gate.mjs): مخرج نظيف = exit 0.
// الاستخدام: node tools/check_deps.mjs
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url)); // web/tools
const web = dirname(root);
const pkg = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8'));

// 1) اجمع كل ملفات المصدر القابلة للاستيراد (src + tests + configs)
const srcDirs = [join(web, 'src'), join(web, 'tests'), join(web, 'tools')];
const exts = ['.ts', '.tsx', '.js', '.jsx', '.mjs', '.mts', '.css'];
const files = [];
function walk(dir) {
  let entries;
  try { entries = readdirSync(dir); } catch { return; }
  for (const e of entries) {
    const p = join(dir, e);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (e === 'node_modules' || e === '.next' || e === 'generated') continue;
      walk(p);
    } else if (exts.some((x) => e.endsWith(x))) {
      files.push(p);
    }
  }
}
srcDirs.forEach(walk);

// ملفات التهيئة الجذرية تُفحص أيضاً (قد تستورد حزمة برمجياً)
const rootConfigs = ['next.config.ts', 'postcss.config.mjs', 'eslint.config.mjs', 'vitest.config.ts', 'components.json']
  .map((f) => join(web, f))
  .filter((f) => { try { return statSync(f).isFile(); } catch { return false; } });
files.push(...rootConfigs);

// 2) استخرج معرّفات الاستيراد (static + dynamic import() + require + css @import/@plugin)
const importRe = /(?:import\s[\s\S]*?from\s*|import\s*\(\s*|require\s*\(\s*|export\s[\s\S]*?from\s*)['"]([^'"]+)['"]/g;
const cssImportRe = /@(?:import|plugin|source)\s+['"]?([^'";\s]+)['"]?/g;
const imported = new Set();
const importers = new Map(); // package -> first importer (relative path)
for (const f of files) {
  const text = readFileSync(f, 'utf8');
  const rel = relative(web, f).replaceAll('\\', '/');
  const collect = (spec) => {
    if (!spec.startsWith('.') && !spec.startsWith('/')) {
      // حزمة: خذ أول مقطعين لـ @scope/name أو المقطع الأول
      const parts = spec.split('/');
      const name = spec.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0];
      if (!imported.has(name)) importers.set(name, rel);
      imported.add(name);
    }
  };
  if (f.endsWith('.css')) {
    for (const m of text.matchAll(cssImportRe)) collect(m[1]);
  } else {
    for (const m of text.matchAll(importRe)) collect(m[1]);
  }
}

// 3) قارن الاعتماديات المعلنة مع المستوردة فعلاً
// قائمة سماح موثقة [W1.6/#40]: إطارية/أدوات CLI لا يُستوردها الكود لكنها إلزامية:
//   - react-dom: نظير إطار Next.js (مداخل البناء تستهلكه ضمنياً)
//   - prisma: واجهة CLI (db:push / migrate / bunx prisma generate في CI)
const ALLOWED_UNUSED = new Set(['react-dom', 'prisma']);
const deps = { ...pkg.dependencies };
const unused = Object.keys(deps).filter((name) => !imported.has(name) && !ALLOWED_UNUSED.has(name));

if (unused.length) {
  console.error('✗ حزم إنتاج معلنة بلا أي استيراد (' + unused.length + '):');
  for (const u of unused.sort()) console.error('   - ' + u);
  console.error('  إما أضف مستوردها أو أزلها من package.json (أو وثّقها في ALLOWED_UNUSED مع سببها).');
  process.exit(1);
}
console.log('✓ كل حزم الإنتاج المعلنة (' + Object.keys(deps).length + ') مستوردة فعلاً (أو موثقة في قائمة السماح) — لا حزمة ميتة.');
