#!/usr/bin/env python3
# [H5-2 V 3.0.0] حارس توازن السلاسل — عمّم من زوج ar/en إلى كل اللغات.
# القاعدة الحاكمة: كل locale (values-*/strings.xml) يطابق الافتراضي (values/ = العربية)
# في العدد ومجموعة المفاتيح والترتيب حرفياً — لا سلسلة يتيمة في أي لغة.
#
# [RVW-19 V 3.2.6] --self-test: يبني شجرة res اصطناعية مؤقتة — توازن نظيف يجب أن
# يمر، ونقص مفتاح يجب أن يفشل، واختلاف ترتيب يجب أن يفشل — فلا يمر الفاحص حين
# لا يقدر على الإخفاق («فاحص لا يمكن أن يفشل لا يفحص شيئاً» — سابقة بوابة الشبكة).
import glob
import os
import re
import sys
import tempfile

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

def keys(path):
    with open(path, encoding="utf-8") as f:
        return re.findall(r'<string name="([^"]+)"', f.read())

def locale_files(res_root):
    out = sorted(glob.glob(os.path.join(res_root, "values-*", "strings.xml")))
    return [p for p in out if os.path.basename(os.path.dirname(p)) != "values-night"]

def check(res_root):
    """فحص توازن السلاسل على جذر res معطى — يعيد 0 نجاحاً و1 فشلاً."""
    base = os.path.join(res_root, "values", "strings.xml")
    if not os.path.exists(base):
        print("فشل: لا يوجد strings.xml الافتراضي")
        return 1
    ar = keys(base)
    locales = locale_files(res_root)
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

def self_test():
    """شجرة اصطناعية: التوازن يمر، والنقص والترتيب يفشلان — لا وسط."""
    with tempfile.TemporaryDirectory() as td:
        res = os.path.join(td, "res")
        os.makedirs(os.path.join(res, "values"))
        clean = '<resources>\n<string name="a">أ</string>\n<string name="b">ب</string>\n<string name="c">ج</string>\n</resources>\n'
        with open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8") as f:
            f.write(clean)
        # 1) لغة متوازنة حرفياً — يجب أن تمر
        os.makedirs(os.path.join(res, "values-en"))
        with open(os.path.join(res, "values-en", "strings.xml"), "w", encoding="utf-8") as f:
            f.write('<resources>\n<string name="a">A</string>\n<string name="b">B</string>\n<string name="c">C</string>\n</resources>\n')
        if check(res) != 0:
            print("self-test: توازن نظيف فشل ظلماً")
            return 1
        # 2) لغة ناقصة مفتاحاً — يجب أن تفشل
        os.makedirs(os.path.join(res, "values-fr"))
        with open(os.path.join(res, "values-fr", "strings.xml"), "w", encoding="utf-8") as f:
            f.write('<resources>\n<string name="a">A</string>\n<string name="b">B</string>\n</resources>\n')
        if check(res) != 1:
            print("self-test: نقص مفتاح لم يُصطد")
            return 1
        # 3) لغة كاملة المفاتيح مختلفة الترتيب — يجب أن تفشل
        os.makedirs(os.path.join(res, "values-tr"))
        with open(os.path.join(res, "values-tr", "strings.xml"), "w", encoding="utf-8") as f:
            f.write('<resources>\n<string name="b">B</string>\n<string name="a">A</string>\n<string name="c">C</string>\n</resources>\n')
        if check(res) != 1:
            print("self-test: اختلاف الترتيب لم يُصطد")
            return 1
    print("self-test: توازن نظيف يمر، ونقص مفتاح واختلاف ترتيب يُصطدان")
    return 0

def main():
    if "--self-test" in sys.argv:
        code = self_test()
        print("strings balance checker: PASS (self-test)" if code == 0
              else "strings balance checker: FAIL (self-test)")
        return code
    return check(ROOT)

if __name__ == "__main__":
    sys.exit(main())
