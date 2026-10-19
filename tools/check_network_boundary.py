#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[Z2-أ V 1.5.0] فاحص حدود طبقة الشبكة — تنفيذ آلي للبند D1 من ADR-001
(docs/ADR-001_NETWORK_LAYER.md): كل كود الشبكة يعيش في حزمة واحدة
`com.superbiz.app.network`، ولا يجوز لأي ملف خارجها استيرادها إلا عبر
سلك التوصيل الجذري المصرَّح به (قائمة بيضاء صريحة أدناه).

[RVW-16] سد ثغرة الفاحص نفسه: كان يطابق `import com.superbiz.app.network.*`
فقط، بينما التوصيل الفعلي في SuperBizApp.kt يستخدم أسماء مؤهلة بالكامل
(`com.superbiz.app.network.SyncClient`) بلا import — فكانت النتيجة
"scanned import sites: 0" من أول إصدار للفاحص: بوابة لا ترى أي استخدام
حقيقي = بوابة شكلية لا تستطيع الفشل على النمط الموجود فعلاً في الشجرة.
الآن يطابق النمطين معاً: الاستيراد والاسم المؤهل، بعد إزالة التعليقات
(ذكر الحزمة في توثيقٍ مثل ADR ليس استخداماً)، مع علم صريح بعدد مواقع
الالتقاط الحقيقية — إن صارت 0 يوماً فهذا بحد ذاته يستحق النظر.

--self-test: يبني شجرة اصطناعية مؤقتة (خرق عبر import، خرق عبر اسم مؤهل،
ملف نظيف بذكرٍ في تعليق، توصيل مُبيَّض، داخل الحزمة) ويتحقق أن الفاحص
يميّزها كلها — بوابة لا تُثبت قدرتها على الفشل ليست بوابة.
المخرج: 0 عند النجاح، 1 عند الفشل.
"""
import pathlib
import re
import sys
import tempfile

PACKAGE_PREFIX = "com.superbiz.app.network."

# القائمة البيضاء: أسلاك التوصيل الجذرية المصرَّح بها في ADR-001 (تنشئ
# المنفّذ وتزمه في الواجهة النقية) — أي إضافة هنا تتطلب مراجعة ADR.
WHITELIST_FILES = {
    "com/superbiz/app/SuperBizApp.kt",
}

IMPORT_RE = re.compile(r"^\s*import\s+(com\.superbiz\.app\.network[.\w]*)", re.M)
FQN_RE = re.compile(r"\bcom\.superbiz\.app\.network\.\w+")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# مواضع الالتقاط في الشجرة الحقيقية — إن صارت 0 فالفاحص أو التوصيل تغيّرا
EXPECTED_MIN_REAL_SITES = 1


def scan(root: pathlib.Path, whitelist=WHITELIST_FILES):
    """يمسح الشجرة ويعيد (مخالفات، ملفات بإستيرادات، ملفات بأسماء مؤهلة، المواقع الحقيقية)."""
    violations = []
    files_with_import = 0
    files_with_fqn = 0
    real_sites = 0
    for kt in root.rglob("*.kt"):
        rel = kt.relative_to(root).as_posix()
        text = kt.read_text(encoding="utf-8", errors="replace")
        # الاستيراد يُقرأ من النص الأصلي؛ الاسم المؤهل من النص بعد إزالة التعليقات
        code = LINE_COMMENT_RE.sub("", BLOCK_COMMENT_RE.sub(" ", text))
        imports = set(IMPORT_RE.findall(text))
        fqns = set(FQN_RE.findall(code))
        refs = set(imports) | fqns
        if not refs:
            continue
        if imports:
            files_with_import += 1
        if fqns:
            files_with_fqn += 1
        in_package = rel.startswith("com/superbiz/app/network/")
        whitelisted = rel in whitelist
        if in_package or whitelisted:
            real_sites += len(refs)
            continue
        for ref in sorted(refs):
            kind = "imports" if ref in imports else "references"
            violations.append(f"{rel}: {kind} {ref}")
    return violations, files_with_import, files_with_fqn, real_sites


def check_package_exists(root: pathlib.Path):
    package_dir = root / "com" / "superbiz" / "app" / "network"
    if not package_dir.is_dir():
        return f"network package missing: {package_dir}"
    return None


def self_test() -> int:
    """شجرة اصطناعية: الفاحص يجب أن يفشل على الخرقين وينجح على النظيف — لا وسط."""
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td) / "java"
        net = root / "com" / "superbiz" / "app" / "network"
        evil = root / "com" / "superbiz" / "app" / "evil"
        clean = root / "com" / "superbiz" / "app" / "clean"
        for d in (net, evil, clean):
            d.mkdir(parents=True)
        (net / "SyncClient.kt").write_text(
            "package com.superbiz.app.network\n"
            "class SyncClient\n", encoding="utf-8")
        # داخل الحزمة — مسموح
        (net / "Internal.kt").write_text(
            "package com.superbiz.app.network\n"
            "val x = com.superbiz.app.network.SyncClient()\n", encoding="utf-8")
        # خرق 1: اسم مؤهل بلا استيراد — الثغرة التي كانت تفلت من النسخة القديمة
        (evil / "LeakFqn.kt").write_text(
            "package com.superbiz.app.evil\n"
            "val leak = com.superbiz.app.network.SyncClient()\n", encoding="utf-8")
        # خرق 2: استيراد تقليدي
        (evil / "LeakImport.kt").write_text(
            "package com.superbiz.app.evil\n"
            "import com.superbiz.app.network.SyncClient\n"
            "val leak2 = SyncClient()\n", encoding="utf-8")
        # ملف مُبيَّض
        (root / "com" / "superbiz" / "app" / "SuperBizApp.kt").write_text(
            "package com.superbiz.app\n"
            "val wiring = com.superbiz.app.network.SyncClient()\n", encoding="utf-8")
        # نظيف: الذكر في تعليق توثيقي ليس استخداماً
        (clean / "Clean.kt").write_text(
            "package com.superbiz.app.clean\n"
            "/** الفاحص الآلي في CI يمنع أي استخدام لـ com.superbiz.app.network خارج حزمته */\n"
            "// وهذه سطر تعليق فيه com.superbiz.app.network.SyncClient أيضاً\n"
            "class Clean\n", encoding="utf-8")

        missing = check_package_exists(root)
        if missing:
            print("self-test: unexpected package-missing:", missing)
            return 1
        violations, n_import, n_fqn, real_sites = scan(root)
        got = sorted(violations)
        want = sorted([
            "com/superbiz/app/evil/LeakFqn.kt: references com.superbiz.app.network.SyncClient",
            "com/superbiz/app/evil/LeakImport.kt: imports com.superbiz.app.network.SyncClient",
        ])
        if got != want:
            print("self-test: mismatch")
            print("  want:", want)
            print("  got: ", got)
            print("network boundary checker: FAIL")
            return 1
        if n_import != 1 or n_fqn != 4 or real_sites != 2:
            print(f"self-test: counters off (imports={n_import}, fqn={n_fqn}, sites={real_sites})")
            print("network boundary checker: FAIL")
            return 1
        print("self-test: both violation shapes caught, clean/commented/whitelisted passed")
        return 0


def main() -> int:
    if "--self-test" in sys.argv:
        code = self_test()
        print("network boundary checker: PASS (self-test)" if code == 0
              else "network boundary checker: FAIL (self-test)")
        return code

    root = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java"

    missing = check_package_exists(root)
    if missing:
        print(missing)
        print("network boundary checker: FAIL")
        return 1

    violations, n_import, n_fqn, real_sites = scan(root)
    if violations:
        print("ADR-001 D1 violations — network usage outside the isolated package:")
        for v in violations:
            print("  -", v)
        print("network boundary checker: FAIL")
        return 1

    print(f"real reference sites (in-package+whitelist): {real_sites} "
          f"(files: {n_import} with imports, {n_fqn} with FQN) — expected >= {EXPECTED_MIN_REAL_SITES}")
    if real_sites < EXPECTED_MIN_REAL_SITES:
        print("no real network wiring detected — is the boundary still wired as ADR-001 assumes?")
        print("network boundary checker: FAIL")
        return 1
    print("network boundary checker: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
