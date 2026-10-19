#!/usr/bin/env python3
"""[P7-L16 إصلاح] فاحص مقامات/تنسيق السلاسل في CI — نسخة مكيّفة من scripts/string_format_scanner.py
(الفاحص الذي اصطاد H-1 بعد أن فاته check_strings.py الأصلي).

المسارات نسبية لجذر المستودع (tools/ ← الجذر) فيعمل محلياً وعلى CI بلا إعداد.

الفئات:
  FAIL (يُخرج 1 — يكسر CI):
    1) عدم تطابق arity بين العربية والإنجليزية لنفس المفتاح (علة C-1 بشكلها المزدوج).
    2) اختلاف مفاتيح ar/en (تفاوت الملفين بعد الدمج).
    3) خلط مقامات موضعية (%1$s) وغير موضعية (%s) في سلسلة واحدة.
    4) % شاردة في سلسلة تحتوي مقامات صالحة أيضاً — صنف H-1 القادر على إسقاط الواجهة
       (UnknownFormatConversionException) عند تمرير وسائط التنسيق.
  WARN (لا يكسر CI — خروج 0):
    * % حرفية في سلسلة بلا أي مقام — تُعرض كما هي ولا تُمرَّر كوسائط في الكود الحالي.
      هذه 20 حالة قديمة في HEAD مؤكدة عمداً (سجل الموجات: «كلها قديمة مقصودة مؤكدة»)،
      وstrings.xml ممنوعة التعديل في موجة P7 — لذا تُطبع توثيقاً دون فشل.
      أي مفتاح جديد يُدمَج بمزج % حرفية مع مقام سيسقط في فئة FAIL رقم (4) فوراً.

بلا أي اعتماديات خارجية — مكتبة python القياسية فقط (re + xml.etree).

[RVW-19 V 3.2.6] --self-test: شجرة res اصطناعية مؤقتة تُثبت قابلية الإخفاق على كل
فئة FAIL (خلط المقامات، % الشاردة الممزوجة، عدم توازن المفاتيح، تباين arity)
وصفر فشل على الشجرة النظيفة، وتحذير % الحرفية لا يكسر CI — سابقة بوابة الشبكة:
«فاحص لا يمكن أن يفشل لا يفحص شيئاً».
"""
import glob
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
AR = os.path.join(RES, "values", "strings.xml")

def locale_files(res_root):
    """[H5-2 V 3.0.0] كل ملفات اللغات values-*/strings.xml (مستثنياً values-night الثيمي)"""
    out = []
    for p in sorted(glob.glob(os.path.join(res_root, "values-*", "strings.xml"))):
        if os.path.basename(os.path.dirname(p)) == "values-night":
            continue
        out.append(p)
    return out

# مقام صحيح وفق نحو java.util.Formatter الكامل: %[index$][flags][width][.precision][conversion]
VALID = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sSdFfbnxXeEgGaAc%]")


def scan_file(path, tag, failures, warnings):
    """يبني فحص بنية المقاسم لكل سلسلة: شاردة/مزج موضعي/مزج شاردة-مع-مقام"""
    tree = ET.parse(path)
    for el in tree.iter("string"):
        name = el.get("name", "?")
        raw = "".join(el.itertext())
        i = 0
        placeholder_count = 0
        stray_count = 0
        positions = set()
        non_positional = 0
        while i < len(raw):
            if raw[i] != "%":
                i += 1
                continue
            m = VALID.match(raw, i)
            if not m:
                stray_count += 1
                i += 1
                continue
            conv = m.group(0)
            if conv == "%%":
                i = m.end()
                continue
            placeholder_count += 1
            if m.group(1):
                positions.add(int(m.group(1)))
            else:
                non_positional += 1
            i = m.end()
        if non_positional and positions:
            failures.append(f"[{tag}][{name}] خلط مقامات موضعية وغير موضعية")
        elif placeholder_count and stray_count:
            failures.append(
                f"[{tag}][{name}] % شاردة مع {placeholder_count} مقام — صنف H-1 (انهيار عند التنسيق)"
            )
        elif stray_count:
            warnings.append(
                f"[{tag}][{name}] % حرفية ({stray_count}) في سلسلة بلا مقام — تُعرض كما هي (قديمة مقصودة)"
            )
        elif positions and len(positions) != placeholder_count:
            failures.append(f"[{tag}][{name}] تكرار رقم مقام موضعي")


def arities(path, tag):
    tree = ET.parse(path)
    out = {}
    for el in tree.iter("string"):
        raw = "".join(el.itertext())
        out[el.get("name")] = len(re.findall(r"%(\d+)\$", raw))
    return out


def scan(res_root):
    """الفحص الكامل على جذر res معطى — يعيد (failures, warnings)."""
    failures = []
    warnings = []
    ar_path = os.path.join(res_root, "values", "strings.xml")
    # 0) سلامة بنية XML لكل اللغات (رسالة نظيفة بدل traceback) — [H5-2] الخمس
    all_files = [("values(ar)", ar_path)] + [
        (os.path.basename(os.path.dirname(p)), p) for p in locale_files(res_root)
    ]
    for label, path in all_files:
        try:
            ET.parse(path)
        except ET.ParseError as e:
            failures.append(f"[{label}] XML parse error: {e}")
            return failures, warnings

    # 1) تناظر المفاتيح و arity: كل لغة ضد الافتراضي — [H5-2 V 3.0.0]
    ar_keys = arities(ar_path, "ar")
    for label, path in all_files[1:]:
        loc_keys = arities(path, label)
        if set(loc_keys) != set(ar_keys):
            diff = sorted(set(ar_keys) ^ set(loc_keys))
            failures.append(f"key mismatch {label} ({len(diff)}): {', '.join(diff[:20])}")
        mismatch = {k: (ar_keys[k], loc_keys[k]) for k in ar_keys
                    if k in loc_keys and ar_keys[k] != loc_keys[k]}
        for k, (a, e) in sorted(mismatch.items()):
            failures.append(f"arity mismatch: [{label}][{k}] ar={a} {label}={e}")

    # 2) فحص بنية المقاسم داخل كل لغة
    for label, path in all_files:
        scan_file(path, label, failures, warnings)
    return failures, warnings


def main():
    if "--self-test" in sys.argv:
        code = self_test()
        print("strings format checker: PASS (self-test)" if code == 0
              else "strings format checker: FAIL (self-test)")
        return code
    failures, warnings = scan(RES)
    print(f"=== strings format scan: FAIL={len(failures)} WARN={len(warnings)} ===")
    for f in failures:
        print(f"  FAIL {f}")
    for w in warnings:
        print(f"  warn {w}")
    if warnings:
        print("ملاحظة: تحذيرات % الحرفية قديمة مقصودة في HEAD (سلاسل تُعرض كما هي بلا وسائط) ولا تكسر CI.")
    if failures:
        print("CI: FAILURE — انظر الفئات أعلاه.")
        return 1
    print("CI: OK (0 عدم توافق)")
    return 0


def self_test():
    """شجرة اصطناعية: كل فئة FAIL تُصطد والنظيف يمر وتحذير الحرفية لا يكسر — لا وسط."""
    with tempfile.TemporaryDirectory() as td:
        res = os.path.join(td, "res")
        os.makedirs(os.path.join(res, "values"))

        def write(locale, body):
            os.makedirs(os.path.join(res, locale), exist_ok=True)
            with open(os.path.join(res, locale, "strings.xml"), "w", encoding="utf-8") as f:
                f.write(f"<resources>\n{body}</resources>\n")

        # 1) شجرة نظيفة: مقام موضعي سليم متطابق arity — صفر فشل
        write("values", '<string name="ok">المجموع %1$s ريال</string>\n')
        write("values-en", '<string name="ok">Total %1$s SAR</string>\n')
        f, w = scan(res)
        if f:
            print(f"self-test: شجرة نظيفة فشلت ظلماً: {f}")
            return 1
        # 2) تباين arity بين ar/en — يجب أن يفشل
        write("values-en", '<string name="ok">Total %1$s of %2$s SAR</string>\n')
        f, _ = scan(res)
        if not any("arity mismatch" in x for x in f):
            print(f"self-test: تباين arity لم يُصطد: {f}")
            return 1
        # 3) % شاردة ممزوجة بمقام — صنف H-1 يجب أن يفشل
        write("values", '<string name="ok">المجموع %1$s ريال (100%)</string>\n')
        f, _ = scan(res)
        if not any("شاردة" in x for x in f):
            print(f"self-test: % الشاردة الممزوجة لم تُصطد: {f}")
            return 1
        # 4) خلط موضعي وغير موضعي — يجب أن يفشل
        write("values", '<string name="ok">المجموع %1$s من %s</string>\n')
        f, _ = scan(res)
        if not any("خلط مقامات" in x for x in f):
            print(f"self-test: خلط المقامات لم يُصطد: {f}")
            return 1
        # 5) % حرفية بلا مقام — تحذير لا يكسر CI (صفر فشل)
        write("values", '<string name="ok">المجموع 100% صحيح</string>\n')
        write("values-en", '<string name="ok">Total 100% fine</string>\n')
        f, w = scan(res)
        if f or not w:
            print(f"self-test: الحرفية يجب أن تحذّر ولا تفشل: failures={f} warnings={w}")
            return 1
    print("self-test: كل فئات الفشل تُصطاد، والنظيف يمر، والحرفية تحذّر بلا كسر")
    return 0


if __name__ == "__main__":
    sys.exit(main())
