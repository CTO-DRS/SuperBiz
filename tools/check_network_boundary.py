#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[Z2-أ V 1.5.0] فاحص حدود طبقة الشبكة — تنفيذ آلي للبند D1 من ADR-001
(docs/ADR-001_NETWORK_LAYER.md): كل كود الشبكة يعيش في حزمة واحدة
`com.superbiz.app.network`، ولا يجوز لأي ملف خارجها استيرادها إلا عبر
سلك التوصيل الجذري المصرَّح به (قائمة بيضاء صريحة أدناه).

الفاحص يعمل على قائمة الملفات مباشرة بلا أي اعتماديات خارجية (نمط
check_strings_format.py) ويخرج PASS/FAIL — أي استيراد جديد خارج الحزمة
وخارج القائمة البيضاء = فشل CI ورفض PR (نص D1 حرفياً).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java"
PACKAGE_PREFIX = "com.superbiz.app.network."
PACKAGE_DIR = ROOT / "com" / "superbiz" / "app" / "network"

# القائمة البيضاء: أسلاك التوصيل الجذرية المصرَّح بها في ADR-001 (تنشئ
# المنفّذ وتزمه في الواجهة النقية) — أي إضافة هنا تتطلب مراجعة ADR.
WHITELIST_FILES = {
    "com/superbiz/app/SuperBizApp.kt",
}

IMPORT_RE = re.compile(r"^\s*import\s+(com\.superbiz\.app\.network[.\w]*)", re.M)


def main() -> int:
    violations = []
    scanned = 0
    for kt in ROOT.rglob("*.kt"):
        rel = kt.relative_to(ROOT).as_posix()
        text = kt.read_text(encoding="utf-8", errors="replace")
        found = IMPORT_RE.findall(text)
        if not found:
            continue
        scanned += 1
        in_package = rel.startswith("com/superbiz/app/network/")
        whitelisted = rel in WHITELIST_FILES
        if in_package or whitelisted:
            continue
        for imp in found:
            violations.append(f"{rel}: imports {imp}")

    # حارس إضافي: هل حزمة الشبكة نفسها موجودة أصلاً؟ (الامتناع عن النسيان)
    if not PACKAGE_DIR.exists():
        print(f"network package missing: {PACKAGE_DIR}")
        print("network boundary checker: FAIL")
        return 1

    if violations:
        print("ADR-001 D1 violations — network imports outside the isolated package:")
        for v in violations:
            print("  -", v)
        print("network boundary checker: FAIL")
        return 1

    print(f"scanned import sites: {scanned}, whitelist: {sorted(WHITELIST_FILES)}")
    print("network boundary checker: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
