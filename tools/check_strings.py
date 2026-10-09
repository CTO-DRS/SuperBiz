#!/usr/bin/env python3
# [H5-2 V 3.0.0] حارس توازن السلاسل — عمّم من زوج ar/en إلى كل اللغات.
# القاعدة الحاكمة: كل locale (values-*/strings.xml) يطابق الافتراضي (values/ = العربية)
# في العدد ومجموعة المفاتيح والترتيب حرفياً — لا سلسلة يتيمة في أي لغة.
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
BASE = os.path.join(ROOT, "values", "strings.xml")

def keys(path):
    with open(path, encoding="utf-8") as f:
        return re.findall(r'<string name="([^"]+)"', f.read())

def main():
    if not os.path.exists(BASE):
        print("فشل: لا يوجد strings.xml الافتراضي")
        return 1
    ar = keys(BASE)
    locales = sorted(glob.glob(os.path.join(ROOT, "values-*", "strings.xml")))
    locales = [p for p in locales if os.path.basename(os.path.dirname(p)) != "values-night"]
    if not locales:
        print("لا توجد لغات إضافية — لا شيء يُقارن")
        return 0
    fail = False
    for path in locales:
        loc = os.path.basename(os.path.dirname(path))
        ks = keys(path)
        if len(ks) != len(ar):
            print(f"فشل {loc}: العدد {len(ks)} != {len(ar)}")
            fail = True
            continue
        if set(ks) != set(ar):
            missing = [k for k in ar if k not in set(ks)][:10]
            extra = [k for k in ks if k not in set(ar)][:10]
            print(f"فشل {loc}: مفاتيح مختلفة — ناقص {missing} زائد {extra}")
            fail = True
            continue
        if ks != ar:
            print(f"فشل {loc}: الترتيب مختلف عن الافتراضي")
            fail = True
            continue
        print(f"{loc}: {len(ks)} = {len(ar)}, نفس الترتيب")
    return 1 if fail else 0

if __name__ == "__main__":
    sys.exit(main())
