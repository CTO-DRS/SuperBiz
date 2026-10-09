#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[H4-2 V 2.5.0] فاحص تغطية الترجمات — جاهزية اللغات الموسعة (docs/LANGUAGE_PLAN.md)

العقد: أي مجلد values-XX/ موجود في res يجب أن يغطي ≥95% من مفاتيح اللغة
المرجعية (values/ = العربية) وبلا مفاتيح يتيمة محلية — وإلا فشل الفاحص.
اللغات دون العتبة يُرفض ملفها أصلاً (لا تجربة مقطعة: سقوط الناقص إلى
values/ يعني عربية أمام مستخدم لا يقرأها — قرار موثق في خطة اللغات).

الاستعمال: python3 tools/check_locale_coverage.py [--threshold 95]
المخرج: 0 عند النجاح، 1 عند الفشل. اللغتان الحاليتان ar/en خارج الفحص
(توازنهما يفرضه check_strings.py بصرامة أعلى — 100%).
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

THRESHOLD = 95
EXEMPT = {"values", "values-en"}  # المرجع + الإنجليزية المفروضة بالتوازن الحرفي

def keys_of(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"✗ XML تالف: {path}: {e}")
        return None
    return {e.get("name") for e in root.findall("string")}

def main():
    base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "main", "res")
    ref = keys_of(os.path.join(base, "values", "strings.xml"))
    if not ref:
        print("✗ لا يمكن قراءة الملف المرجعي values/strings.xml")
        return 1
    # مجلدات اللغات فقط — نمط رمز لغة ISO-639 اختيارياً بمنطقة (values-tr, values-ur, values-id…)
    # وليس أي qualifier آخر (values-night/round… ليست لغات)
    locale_re = re.compile(r"values-([a-z]{2,3})(-r[A-Z]{2})?$")
    locales = sorted(
        d for d in glob.glob(os.path.join(base, "values-*"))
        if os.path.isdir(d) and locale_re.match(os.path.basename(d))
        and os.path.basename(d) not in EXEMPT
    )
    if not locales:
        print(f"✓ لا لغات إضافية حالياً — المرجع {len(ref)} مفتاحاً (ar) + values-en مفروض بالتوازن الحرفي")
        return 0
    failed = False
    for d in locales:
        name = os.path.basename(d)
        path = os.path.join(d, "strings.xml")
        if not os.path.exists(path):
            print(f"✗ {name}: مجلد بلا strings.xml — يُرفض (لا مجلدات شكلية)")
            failed = True
            continue
        keys = keys_of(path)
        if keys is None:
            failed = True
            continue
        coverage = 100.0 * len(keys & ref) / len(ref) if ref else 0.0
        orphan = keys - ref
        missing = ref - keys
        status = "✓" if (coverage >= THRESHOLD and not orphan and not missing) else "✗"
        print(f"{status} {name}: تغطية {coverage:.1f}% ({len(keys & ref)}/{len(ref)}) — يتيمة: {len(orphan)} — ناقصة: {len(missing)}")
        if orphan:
            for k in sorted(orphan)[:5]:
                print(f"    مفتاح يتيم (غير موجود في المرجع): {k}")
        if coverage < THRESHOLD or orphan or missing:
            failed = True
    if failed:
        print(f"\n✗ الفشل: كل لغة شحيحة تغطية {THRESHOLD}% أو تحوي مفاتيح يتيمة تُرفض (قرار خطة اللغات: لا تجربة مقطعة)")
        return 1
    print(f"\n✓ كل اللغات الحاضرة مكتملة التغطية (≥{THRESHOLD}%)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
