#!/usr/bin/env python3
# -*- coding: utf-8 -*-
""" — أداة فحص تطابق النصوص بين العربية والإنجليزية.

تتحقق من: (1) نفس عدد المفاتيح، (2) نفس مجموعة المفاتيح، (3) نفس الترتيب.
تخرج برمز غير صفر عند أي اختلاف — مناسبة لبوابة CI.

الاستخدام: python3 tools/check_strings.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AR = ROOT / "app/src/main/res/values/strings.xml"
EN = ROOT / "app/src/main/res/values-en/strings.xml"

def keys(path: Path):
    src = path.read_text(encoding="utf-8")
    return re.findall(r'<string name="([^"]+)"', src)

def main() -> int:
    ar, en = keys(AR), keys(EN)
    ok = True
    if len(ar) != len(en):
        print(f"✗ عدد المفاتيح مختلف: ar={len(ar)} en={len(en)}")
        ok = False
    only_ar = set(ar) - set(en)
    only_en = set(en) - set(ar)
    if only_ar:
        print(f"✗ مفاتيح بلا مقابل إنجليزي: {sorted(only_ar)[:10]}")
        ok = False
    if only_en:
        print(f"✗ مفاتيح بلا مقابل عربي: {sorted(only_en)[:10]}")
        ok = False
    if ar != en and set(ar) == set(en):
        print("✗ المفاتيح متطابقة لكن الترتيب مختلف (يبقى الفرق معياراً صارماً)")
        ok = False
    if ok:
        print(f"✓ تطابق تام: {len(ar)} مفتاحاً عربياً = {len(en)} إنجليزياً، نفس الترتيب")
        return 0
    return 1

if __name__ == "__main__":
    sys.exit(main())
