"use client";

/** تقرير الفحص الهندسي — بطاقة المشروع + نقاط القوة + الفجوات + البنود المفتوحة */
import { ShieldCheck, CheckCircle2, AlertTriangle, CircleDot, BookOpen } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { INSPECTION } from "@/lib/roadmap-data";

export default function InspectionTab() {
  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold">
            <ShieldCheck className="h-5 w-5 sb-green-text" />
            تقرير الفحص الهندسي — V 3.5.2
          </h2>
          <p className="mt-1 text-xs text-slate-400">فحص طبقات متوازٍ: البيانات والمحركات · الواجهة والجودة والتسليم · الوثائق الاستراتيجية (2026-10-30)</p>
        </div>
        <a href="https://github.com/CTO-DRS/SuperBiz/blob/main/docs/INSPECTION_REPORT_V352.md" target="_blank" rel="noreferrer">
          <Badge variant="outline" className="sb-chip cursor-pointer border-cyan-400/30 bg-cyan-400/10 text-cyan-300 hover:bg-cyan-400/20">
            <BookOpen className="h-3.5 w-3.5" /> الوثيقة الكاملة على GitHub
          </Badge>
        </a>
      </div>

      {/* بطاقة الأرقام */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {INSPECTION.stats.map((s) => (
          <Card key={s.label} className="sb-glass border-0">
            <CardContent className="p-3 text-center">
              <div className="text-[11px] font-semibold text-slate-400">{s.label}</div>
              <div className="sb-num mt-1 text-xl font-extrabold sb-violet-text">{s.value}</div>
              <div className="mt-0.5 text-[10px] text-slate-500">{s.sub}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* نقاط القوة */}
        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm font-bold">
              <CheckCircle2 className="h-4 w-4 sb-green-text" />
              نقاط القوة البنيوية (ترثها كل موجة قادمة)
            </CardTitle>
          </CardHeader>
          <CardContent className="sb-scroll max-h-80 space-y-2 overflow-y-auto">
            {INSPECTION.strengths.map((s, i) => (
              <div key={i} className="flex items-start gap-2 rounded-xl bg-white/[0.03] p-3 text-xs leading-relaxed text-slate-300">
                <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 sb-green-text" />
                {s}
              </div>
            ))}
          </CardContent>
        </Card>

        {/* الفجوات */}
        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-2 text-sm font-bold">
              <AlertTriangle className="h-4 w-4 sb-amber-text" />
              الفجوات التي تعالجها خارطة الطريق
            </CardTitle>
          </CardHeader>
          <CardContent className="sb-scroll max-h-80 space-y-2 overflow-y-auto">
            {INSPECTION.gaps.map((g, i) => (
              <div key={i} className="rounded-xl bg-white/[0.03] p-3">
                <div className="text-xs font-bold sb-amber-text">{g.t}</div>
                <p className="mt-1 text-[11px] leading-relaxed text-slate-400">{g.d}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>

      {/* البنود المفتوحة */}
      <Card className="sb-glass border-0">
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-sm font-bold">
            <CircleDot className="h-4 w-4 sb-cyan-text" />
            جرد البنود المفتوحة (لا شيء مخفي — تأجيلات موثقة)
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {INSPECTION.openItems.map((item, i) => (
            <div key={i} className="flex items-start gap-2 rounded-xl bg-white/[0.03] p-3 text-xs leading-relaxed text-slate-300">
              <span className="sb-num mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-md bg-cyan-500/15 text-[10px] font-bold text-cyan-300">{i + 1}</span>
              {item}
            </div>
          ))}
          <p className="pt-1 text-center text-[11px] text-slate-500">
            الحكم النهائي: المشروع جاهز لمسار تطوير متكامل — الأولوية الأولى: الفروع (V3.6.0)، الثانية: بوابة الويب، الثالثة: خادم المزامنة.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
