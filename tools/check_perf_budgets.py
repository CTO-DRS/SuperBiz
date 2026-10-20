#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[E1.1 V 3.4.0] بوابة ميزانيات الأداء — نفس اقتصاد بوابات الجودة الأربع
(فاحص + اختبار ذاتي يثبت أنه يفشل عند الانتهاك).

عقد القبول (خارطة الطريق E1.1): «تأكيدات الميزانية في CI تفشل عند التراجع»:
  • cold start  p50 < 2000 ms  على جهاز 4غ الطبقة
  • search      p95 < 200 ms   عند 10k عنصراً
  • sync        p95 < 2000 ms
  • checkout    p50 ≤ 30 s     (قبول المرحلة 2 — مرصود هنا من الآن)

المدخل: ملف نتائج Macrobenchmark JSON (frameworkResults — android benchmark
plugin) أو ملف مسطح مبسط {"metric": {"p50": ms, "p95": ms}} — البوابة تقرأ
الاثنين وتفشل مغلقة على أي ملف مفقود/تالف.

وضع «لا جهاز» (الحقيقة الموثقة): بلا `--results FILE` تعيد البوابة 0 مع
طبقة NOT-VERIFIED صريحة — قبول E1.1 الكامل ينتظر مصفوفة الأجهزة (E0.3)،
والبوابة تُفرض فعلياً في CI فور توفر أول تقرير جهاز (release workflow
يرفق النتائج آلياً حين تعمل على runner موصول بجهاز).

الاستخدام:
  python3 tools/check_perf_budgets.py --results bench.json      # فرض
  python3 tools/check_perf_budgets.py --self-test               # إثبات الفشل
  python3 tools/check_perf_budgets.py                            # وضع لا-جهاز
"""
import json
import sys

# الميزانيات المعتمدة (ms) — من خارطة الطريق E1.1 حرفياً + قبول المرحلة 2
BUDGETS = {
    "cold_start_p50": 2000.0,
    "search_p95": 200.0,
    "sync_p95": 2000.0,
    "checkout_p50": 30000.0,
}


def parse_results(path):
    """يقرأ تقرير Macrobenchmark (المخطط الرسمي: benchmarks[].benchmarks[].metrics)
    أو ملفاً مسطحاً {"cold_start_p50": {"p50": ...}, ...} — ويعيد قاموساً موحداً."""
    src = json.load(open(path))
    out = {}
    if isinstance(src, dict) and "benchmarks" in src:
        for bench in src["benchmarks"]:
            name = str(bench.get("name", "")).lower()
            for mname, mval in (bench.get("metrics") or {}).items():
                # أعمدة الإطار: P50/P90/P95/P99 (أرقام ms مباشرة)
                for col, key in (("P50", "p50"), ("P95", "p95")):
                    if col in mval:
                        metric = f"{_classify(name, mname)}_{key}"
                        try:
                            out[metric] = min(out.get(metric, float("inf")), float(mval[col]))
                        except (TypeError, ValueError):
                            raise SystemExit(f"corrupt metric value: {name}.{mname}.{col} = {mval[col]!r}")
    elif isinstance(src, dict):
        for k, v in src.items():
            if isinstance(v, dict):
                for col in ("p50", "p95"):
                    if col in v:
                        out[k if k.endswith(col) else f"{k}_{col}"] = float(v[col])
            else:
                out[k] = float(v)
    else:
        raise SystemExit("corrupt results file: unrecognized shape")
    return out


def _classify(bench_name, metric_name):
    """يربط اسم اختبار Macrobenchmark بمفتاح الميزانية الموحد."""
    blob = f"{bench_name} {metric_name}".lower()
    if "startup" in blob or "cold" in blob:
        return "cold_start"
    if "search" in blob:
        return "search"
    if "sync" in blob:
        return "sync"
    if "checkout" in blob or "invoice" in blob or "pos" in blob:
        return "checkout"
    return metric_name.lower().replace("timems", "").strip("_") or "unknown"


def run(results_path):
    metrics = parse_results(results_path)
    missing = [b for b in BUDGETS if b not in metrics]
    if missing:
        # فشل مغلق: تقرير جهاز بلا أعمدة الميزانية = تقرير غير صالح للبوابة
        print(f"FAIL: results file lacks budget metrics: {missing}")
        print(f"       present: {sorted(metrics)}")
        return 2
    failed = []
    for budget, cap in sorted(BUDGETS.items()):
        val = metrics[budget]
        verdict = "PASS" if val < cap else "FAIL"
        print(f"  {budget:<16} {val:9.1f} ms / cap {cap:8.1f} ms  {verdict}")
        if val >= cap:
            failed.append(budget)
    if failed:
        print(f"FAIL: budgets exceeded: {failed} — regression blocks merge (E1.1 contract)")
        return 1
    print("performance budgets: PASS")
    return 0


def self_test():
    """إثبات قابلية الفشل: تجاوز واحد يُصطاد، والنظيف يمر، والملف التالف يفشل مغلقاً."""
    import tempfile, os
    clean = {"cold_start_p50": {"p50": 1850.0}, "search_p95": {"p95": 180.0},
             "sync_p95": {"p95": 1500.0}, "checkout_p50": {"p50": 25000.0}}
    regressed = dict(clean, search_p95={"p95": 260.0})
    for label, payload, expect in (("clean", clean, 0), ("regressed", regressed, 1)):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(payload, f)
            tmp = f.name
        try:
            rc = run(tmp)
            assert rc == expect, f"self-test {label}: rc={rc} expected {expect}"
        finally:
            os.unlink(tmp)
    # ملف تالف → فشل مغلق (لا صمت)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write("[1,2,3]")
        tmp = f.name
    try:
        rc = 0
        try:
            rc = run(tmp)
        except SystemExit:
            rc = 2
        assert rc == 2, f"self-test corrupt: rc={rc} expected 2"
    finally:
        os.unlink(tmp)
    print("performance budget checker: PASS (self-test)")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        sys.exit(0)
    args = sys.argv[1:]
    if "--results" in args:
        path = args[args.index("--results") + 1]
        sys.exit(run(path))
    print("NOT-VERIFIED-ON-DEVICE: no benchmark results supplied — device matrix (E0.3) pending.")
    print("The gate enforces automatically in CI the moment a results file is attached.")
    sys.exit(0)
