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

[RVW-19 V 3.2.6] --self-test: شجرة res اصطناعية مؤقتة — لغة كاملة التغطية
تمر، ولغة تحت العتبة تفشل، ومفتاح يتيم يفشل — إثبات قابلية الإخفاق
(سابقة بوابة الشبكة RVW-16: «فاحص لا يمكن أن يفشل لا يفحص شيئاً»).
"""
import os
import re
import sys
import glob
import tempfile
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

def locale_dirs(base):
    # مجلدات اللغات فقط — نمط رمز لغة ISO-639 اختيارياً بمنطقة (values-tr, values-ur, values-id…)
    # وليس أي qualifier آخر (values-night/round… ليست لغات)
    locale_re = re.compile(r"values-([a-z]{2,3})(-r[A-Z]{2})?$")
    return sorted(
        d for d in glob.glob(os.path.join(base, "values-*"))
        if os.path.isdir(d) and locale_re.match(os.path.basename(d))
        and os.path.basename(d) not in EXEMPT
    )

def check(base, threshold=THRESHOLD):
    """فحص التغطية على جذر res معطى — يعيد 0 نجاحاً و1 فشلاً."""
    ref = keys_of(os.path.join(base, "values", "strings.xml"))
    if not ref:
        print("✗ لا يمكن قراءة الملف المرجعي values/strings.xml")
        return 1
    locales = locale_dirs(base)
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
        status = "✓" if (coverage >= threshold and not orphan and not missing) else "✗"
        print(f"{status} {name}: تغطية {coverage:.1f}% ({len(keys & ref)}/{len(ref)}) — يتيمة: {len(orphan)} — ناقصة: {len(missing)}")
        if orphan:
            for k in sorted(orphan)[:5]:
                print(f"    مفتاح يتيم (غير موجود في المرجع): {k}")
        if coverage < threshold or orphan or missing:
            failed = True
    if failed:
        print(f"\n✗ الفشل: كل لغة شحيحة تغطية {threshold}% أو تحوي مفاتيح يتيمة تُرفض (قرار خطة اللغات: لا تجربة مقطعة)")
        return 1
    print(f"\n✓ كل اللغات الحاضرة مكتملة التغطية (≥{threshold}%)")
    return 0

def self_test():
    """شجرة اصطناعية: كاملة التغطية تمر، والشحيحة واليتيمة تُرفض — لا وسط."""
    with tempfile.TemporaryDirectory() as td:
        res = os.path.join(td, "res")
        os.makedirs(os.path.join(res, "values"))
        ten = "".join(f'<string name="k{i}">ق{i}</string>\n' for i in range(10))
        with open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8") as f:
            f.write(f"<resources>\n{ten}</resources>\n")
        # 1) لغة مكتملة 10/10 — يجب أن تمر
        os.makedirs(os.path.join(res, "values-ur"))
        with open(os.path.join(res, "values-ur", "strings.xml"), "w", encoding="utf-8") as f:
            f.write("<resources>\n" + "".join(f'<string name="k{i}">K{i}</string>\n' for i in range(10)) + "</resources>\n")
        if check(res) != 0:
            print("self-test: تغطية كاملة فشلت ظلماً")
            return 1
        # 2) لغة شحيحة 9/10 (90% < 95%) — يجب أن تفشل
        os.makedirs(os.path.join(res, "values-tr"))
        with open(os.path.join(res, "values-tr", "strings.xml"), "w", encoding="utf-8") as f:
            f.write("<resources>\n" + "".join(f'<string name="k{i}">K{i}</string>\n' for i in range(9)) + "</resources>\n")
        if check(res) != 1:
            print("self-test: تغطية تحت العتبة لم تُصطد")
            return 1
        # 3) مفتاح يتيم (موجود باللغة غائب عن المرجع) — يجب أن يفشل حتى مع تغطية عالية
        os.makedirs(os.path.join(res, "values-id"))
        with open(os.path.join(res, "values-id", "strings.xml"), "w", encoding="utf-8") as f:
            f.write("<resources>\n" + "".join(f'<string name="k{i}">K{i}</string>\n' for i in range(10)) + '<string name="ghost">G</string>\n</resources>\n')
        if check(res) != 1:
            print("self-test: مفتاح يتيم لم يُصطد")
            return 1
    print("self-test: مكتملة تمر، وشحيحة العتبة ويتيمة المفتاح تُصطدان")
    return 0

def main():
    if "--self-test" in sys.argv:
        code = self_test()
        print("locale coverage checker: PASS (self-test)" if code == 0
              else "locale coverage checker: FAIL (self-test)")
        return code
    # عتبة سطر الأوامر تبقى كما هي
    threshold = THRESHOLD
    for i, a in enumerate(sys.argv[1:]):
        if a == "--threshold" and i + 2 < len(sys.argv):
            threshold = int(sys.argv[i + 2])
    return check(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              "app", "src", "main", "res"), threshold)

if __name__ == "__main__":
    sys.exit(main())
