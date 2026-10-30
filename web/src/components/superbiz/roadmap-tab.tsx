"use client";

/** خارطة الطريق الذكية — المسارات الستة W/A/B/C/D/E بروابط الـIssues على GitHub */
import { useState } from "react";
import { Map, CheckCircle2, CircleDot, Circle, ExternalLink, Github, Layers } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ROADMAP, type ItemStatus } from "@/lib/roadmap-data";
import { cn } from "@/lib/utils";

const STATUS_UI: Record<ItemStatus, { label: string; cls: string }> = {
  done: { label: "منجز", cls: "border-emerald-400/30 bg-emerald-400/10 text-emerald-300" },
  active: { label: "قيد التنفيذ", cls: "border-cyan-400/30 bg-cyan-400/10 text-cyan-300" },
  planned: { label: "مخطط", cls: "border-slate-400/30 bg-slate-400/10 text-slate-400" },
  owner: { label: "على المالك", cls: "border-amber-400/30 bg-amber-400/10 text-amber-300" },
};

const TRACK_KEYS = ["W", "A", "B", "C", "D", "E"] as const;

export default function RoadmapTab() {
  const [track, setTrack] = useState<string>("ALL");

  const tracks = ROADMAP.filter((t) => track === "ALL" || t.key === track);

  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold">
            <Map className="h-5 w-5 sb-violet-text" />
            خارطة الطريق الذكية المتكاملة 2026-2027
          </h2>
          <p className="mt-1 text-xs text-slate-400">
            الموثقة في <span className="sb-num" dir="ltr">docs/ROADMAP_SMART_2026.md</span> — كل بند مسجل Issue على GitHub مع معيار قبول قابل للفحص.
          </p>
        </div>
        <a href="https://github.com/CTO-DRS/SuperBiz/milestones" target="_blank" rel="noreferrer">
          <Badge variant="outline" className="sb-chip cursor-pointer border-violet-400/30 bg-violet-400/10 text-violet-300 hover:bg-violet-400/20">
            <Github className="h-3.5 w-3.5" /> Milestones على GitHub
          </Badge>
        </a>
      </div>

      <Tabs value={track} onValueChange={setTrack}>
        <TabsList className="bg-white/5">
          <TabsTrigger value="ALL" className="text-xs">كل المسارات</TabsTrigger>
          {TRACK_KEYS.map((k) => (
            <TabsTrigger key={k} value={k} className="sb-num text-xs">{k}</TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <div className="grid gap-4">
        {tracks.map((t) => {
          const done = t.items.filter((i) => i.status === "done").length;
          const active = t.items.filter((i) => i.status === "active").length;
          const progress = Math.round(((done + active * 0.5) / t.items.length) * 100);
          return (
            <Card key={t.key} className="sb-glass border-0">
              <CardHeader className="flex-row flex-wrap items-center justify-between gap-3 pb-3">
                <div className="min-w-0">
                  <CardTitle className="flex flex-wrap items-center gap-2 text-base font-extrabold">
                    <span className={cn(
                      "sb-num flex h-8 w-8 items-center justify-center rounded-lg text-sm",
                      t.key === "W" ? "bg-violet-500/25 text-violet-200" :
                      t.key === "A" ? "bg-rose-500/25 text-rose-200" :
                      t.key === "B" ? "bg-cyan-500/25 text-cyan-200" :
                      t.key === "C" ? "bg-emerald-500/25 text-emerald-200" :
                      t.key === "D" ? "bg-amber-500/25 text-amber-200" :
                      "bg-slate-500/25 text-slate-200"
                    )}>{t.key}</span>
                    {t.name}
                    <span className={cn("sb-chip", STATUS_UI[t.status].cls)}>{STATUS_UI[t.status].label}</span>
                  </CardTitle>
                  <p className="mt-1.5 text-xs leading-relaxed text-slate-400">{t.tagline}</p>
                </div>
                <div className="flex items-center gap-4 text-xs text-slate-400">
                  <span>الأولوية <b className="sb-num">{t.priority}</b></span>
                  <span>النافذة: <b>{t.window}</b></span>
                  <div className="w-28">
                    <div className="mb-1 flex justify-between">
                      <span>التقدم</span><span className="sb-num">{progress}%</span>
                    </div>
                    <Progress value={progress} className="h-1.5 bg-white/10 [&>div]:bg-gradient-to-l [&>div]:from-violet-500 [&>div]:to-cyan-400" />
                  </div>
                </div>
              </CardHeader>
              <CardContent className="space-y-2">
                {t.items.map((item) => {
                  const st = STATUS_UI[item.status];
                  const StatusIcon = item.status === "done" ? CheckCircle2 : item.status === "active" ? CircleDot : item.status === "owner" ? Circle : Circle;
                  return (
                    <div key={item.code} className="group flex items-start gap-3 rounded-xl bg-white/[0.03] p-3 transition-colors hover:bg-white/[0.05]">
                      <StatusIcon className={cn(
                        "mt-0.5 h-4 w-4 shrink-0",
                        item.status === "done" ? "sb-green-text" :
                        item.status === "active" ? "sb-cyan-text" :
                        item.status === "owner" ? "sb-amber-text" : "text-slate-500"
                      )} />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="sb-num text-xs font-extrabold sb-cyan-text">{item.code}</span>
                          <span className="text-sm font-bold leading-snug">{item.title}</span>
                          <Badge variant="outline" className={cn("sb-chip", st.cls)}>{st.label}</Badge>
                          <Badge variant="outline" className="sb-chip border-white/10 bg-white/5 text-slate-400">{item.priority}</Badge>
                        </div>
                        <p className="mt-1 text-[11px] leading-relaxed text-slate-400">
                          <span className="font-semibold text-slate-500">معيار القبول:</span> {item.acceptance}
                        </p>
                      </div>
                      {item.issue && (
                        <a
                          href={`https://github.com/CTO-DRS/SuperBiz/issues/${item.issue}`}
                          target="_blank"
                          rel="noreferrer"
                          className="flex shrink-0 items-center gap-1 rounded-lg bg-white/5 px-2 py-1 text-[10px] font-semibold text-slate-400 transition-colors hover:bg-violet-500/20 hover:text-violet-200"
                          aria-label={`الـIssue #${item.issue} على GitHub`}
                        >
                          <span className="sb-num">#{item.issue}</span>
                          <ExternalLink className="h-3 w-3" />
                        </a>
                      )}
                    </div>
                  );
                })}
              </CardContent>
            </Card>
          );
        })}
      </div>

      <Card className="sb-glass border-violet-400/20">
        <CardContent className="flex items-center gap-3 p-4 text-xs leading-relaxed text-slate-300">
          <Layers className="h-5 w-5 shrink-0 sb-violet-text" />
          <p>
            <b>الحوكمة:</b> كل موجة = إصدار موسوم + اختبارات جديدة + CI أخضر + رفع مباشر إلى GitHub. القواعد الثابتة: دون اتصال أولًا · المال قروش · ذرّية · ترحيلات تراكمية · RBAC مقفول افتراضيًا · RED-first.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
