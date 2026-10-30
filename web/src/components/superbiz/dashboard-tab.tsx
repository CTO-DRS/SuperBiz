"use client";

/** لوحة القيادة — KPIs حية + سلسلة 7 أيام + أعلى المنتجات + مخزون منخفض + أحدث الفواتير */
import { useEffect, useState } from "react";
import {
  TrendingUp, TrendingDown, Wallet, HandCoins, Boxes, ReceiptText,
  AlertTriangle, Activity, ArrowLeft, Sparkles,
} from "lucide-react";
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, BarChart, Bar, Cell } from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Progress } from "@/components/ui/progress";
import { cn } from "@/lib/utils";

type DashData = {
  monthSalesNum: number;
  monthProfitNum: number;
  monthExpensesNum: number;
  cashNum: number;
  receivableNum: number;
  payableNum: number;
  stockValueNum: number;
  lowStockCount: number;
  productCount: number;
  pulse: number;
  series: { date: string; sales: number; profit: number }[];
  lowStock: { id: string; name: string; stockQty: number; reorderLevel: number; unit: string }[];
  recentInvoices: { id: string; number: string; date: string; total: number; status: string; type: string; party?: { name: string } | null }[];
  topProducts: { name: string; sales: number; qty: number }[];
};

const fmt = (n: number) => `${(n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

const STATUS_MAP: Record<string, { label: string; cls: string }> = {
  PAID: { label: "مسددة", cls: "border-emerald-400/30 bg-emerald-400/10 text-emerald-300" },
  PARTIAL: { label: "جزئي", cls: "border-amber-400/30 bg-amber-400/10 text-amber-300" },
  UNPAID: { label: "آجل", cls: "border-rose-400/30 bg-rose-400/10 text-rose-300" },
  VOID: { label: "ملغاة", cls: "border-slate-400/30 bg-slate-400/10 text-slate-400" },
};

export default function DashboardTab({ refreshKey }: { refreshKey: number }) {
  const [data, setData] = useState<DashData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetch("/api/dashboard")
      .then((r) => {
        if (!r.ok) throw new Error(`فشل التحميل (${r.status})`);
        return r.json();
      })
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(String(e.message ?? e)));
    return () => { alive = false; };
  }, [refreshKey]);

  if (error) {
    return (
      <Card className="sb-glass border-rose-400/30">
        <CardContent className="p-6 text-center text-rose-300">{error}</CardContent>
      </Card>
    );
  }

  if (!data) {
    return (
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 8 }).map((_, i) => (
          <Skeleton key={i} className="h-28 rounded-2xl bg-white/5" />
        ))}
      </div>
    );
  }

  const pulseColor = data.pulse >= 70 ? "text-emerald-300" : data.pulse >= 40 ? "text-amber-300" : "text-rose-300";

  const kpis = [
    { label: "مبيعات الشهر", value: data.monthSalesNum, icon: TrendingUp, cls: "sb-violet-text", chip: "from-violet-500/20 to-violet-500/5" },
    { label: "أرباح الشهر", value: data.monthProfitNum, icon: Activity, cls: "sb-green-text", chip: "from-emerald-500/20 to-emerald-500/5" },
    { label: "مصروفات الشهر", value: data.monthExpensesNum, icon: TrendingDown, cls: "sb-red-text", chip: "from-rose-500/20 to-rose-500/5" },
    { label: "النقد في الصندوق", value: data.cashNum, icon: Wallet, cls: "sb-cyan-text", chip: "from-cyan-500/20 to-cyan-500/5" },
    { label: "ذمم مدينة (لنا)", value: data.receivableNum, icon: HandCoins, cls: "sb-amber-text", chip: "from-amber-500/20 to-amber-500/5" },
    { label: "ذمم دائنة (علينا)", value: data.payableNum, icon: HandCoins, cls: "text-slate-300", chip: "from-slate-500/20 to-slate-500/5" },
    { label: "قيمة المخزون (تكلفة)", value: data.stockValueNum, icon: Boxes, cls: "sb-violet-text", chip: "from-violet-500/20 to-violet-500/5" },
    { label: "منتجات تحت حد الطلب", value: data.lowStockCount, icon: AlertTriangle, cls: data.lowStockCount > 0 ? "sb-amber-text" : "sb-green-text", chip: "from-amber-500/20 to-amber-500/5", raw: data.lowStockCount > 0 ? `${data.lowStockCount} من ${data.productCount}` : "سليم" },
  ];

  return (
    <div className="sb-fade-in space-y-5">
      {/* نبض العمل */}
      <Card className="sb-glass overflow-hidden border-0">
        <div className="flex flex-col items-center justify-between gap-4 bg-gradient-to-l from-violet-500/15 via-transparent to-cyan-500/10 p-5 sm:flex-row">
          <div className="flex items-center gap-4">
            <div className="relative flex h-16 w-16 items-center justify-center">
              <svg className="absolute inset-0 -rotate-90" viewBox="0 0 64 64">
                <circle cx="32" cy="32" r="27" fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth="6" />
                <circle
                  cx="32" cy="32" r="27" fill="none" stroke={data.pulse >= 70 ? "#34D399" : data.pulse >= 40 ? "#FBBF24" : "#F87171"}
                  strokeWidth="6" strokeLinecap="round"
                  strokeDasharray={`${(data.pulse / 100) * 2 * Math.PI * 27} 999`}
                />
              </svg>
              <span className={cn("sb-num text-lg font-extrabold", pulseColor)}>{data.pulse}</span>
            </div>
            <div>
              <div className="flex items-center gap-1.5 font-bold">
                <Sparkles className="h-4 w-4 sb-cyan-text" />
                نبض العمل — مركّب حتمي
              </div>
              <p className="mt-0.5 max-w-xl text-xs leading-relaxed text-slate-400">
                40% كفاءة التحصيل + 30% صحة المخزون + 30% انضباط المصروفات — محسوب محليًا بلا أي AI خارجي (موروث من R18Kpi).
              </p>
            </div>
          </div>
          <div className="flex gap-3 text-center text-xs text-slate-400">
            <div className="sb-glass px-4 py-2">
              <div className="sb-num text-lg font-bold text-slate-100">{data.productCount}</div>
              منتج نشط
            </div>
            <div className="sb-glass px-4 py-2">
              <div className="sb-num text-lg font-bold text-slate-100">{data.lowStockCount}</div>
              تحت حد الطلب
            </div>
          </div>
        </div>
      </Card>

      {/* بطاقات KPI */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {kpis.map((k) => {
          const Icon = k.icon;
          return (
            <Card key={k.label} className="sb-glass border-0">
              <CardContent className="p-4">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-slate-400">{k.label}</span>
                  <div className={cn("flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br", k.chip)}>
                    <Icon className={cn("h-4 w-4", k.cls)} />
                  </div>
                </div>
                <div className="mt-2 text-xl font-extrabold tracking-tight">
                  <span className="sb-num">{k.raw ?? fmt(k.value)}</span>
                  {!k.raw && <span className="mr-1 text-[11px] font-medium text-slate-400">ر.س</span>}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        {/* سلسلة 7 أيام */}
        <Card className="sb-glass border-0 lg:col-span-2">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-bold">مبيعات وأرباح آخر 7 أيام</CardTitle>
          </CardHeader>
          <CardContent className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data.series} margin={{ top: 5, right: 5, left: 5, bottom: 0 }}>
                <defs>
                  <linearGradient id="gSales" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#8B5CF6" stopOpacity={0.45} />
                    <stop offset="100%" stopColor="#8B5CF6" stopOpacity={0.02} />
                  </linearGradient>
                  <linearGradient id="gProfit" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#22D3EE" stopOpacity={0.4} />
                    <stop offset="100%" stopColor="#22D3EE" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <XAxis dataKey="date" tick={{ fill: "#8B93B8", fontSize: 11 }} axisLine={false} tickLine={false} reversed />
                <YAxis tick={{ fill: "#8B93B8", fontSize: 11 }} axisLine={false} tickLine={false} width={54} tickFormatter={(v) => `${v}`} orientation="right" />
                <Tooltip
                  contentStyle={{ background: "#131A3A", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 12, fontFamily: "inherit" }}
                  labelStyle={{ color: "#E7EAF6" }}
                  formatter={(value: number, name: string) => [`${fmt(value)} ر.س`, name === "sales" ? "المبيعات" : "الربح التقريبي"]}
                />
                <Area type="monotone" dataKey="sales" stroke="#8B5CF6" strokeWidth={2.5} fill="url(#gSales)" />
                <Area type="monotone" dataKey="profit" stroke="#22D3EE" strokeWidth={2} fill="url(#gProfit)" />
              </AreaChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        {/* مخزون منخفض */}
        <Card className="sb-glass border-0">
          <CardHeader className="flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-bold">تنبيه المخزون المنخفض</CardTitle>
            <AlertTriangle className={cn("h-4 w-4", data.lowStockCount ? "sb-amber-text" : "sb-green-text")} />
          </CardHeader>
          <CardContent className="sb-scroll max-h-64 space-y-3 overflow-y-auto">
            {data.lowStock.length === 0 && (
              <div className="py-8 text-center text-sm text-slate-500">لا تنبيهات — المخزون سليم ✓</div>
            )}
            {data.lowStock.map((p) => {
              const pct = p.reorderLevel > 0 ? Math.min(100, (p.stockQty / (p.reorderLevel * 2)) * 100) : 100;
              return (
                <div key={p.id}>
                  <div className="mb-1 flex items-center justify-between text-xs">
                    <span className="font-semibold text-slate-200">{p.name}</span>
                    <span className="sb-num text-amber-300">{p.stockQty} {p.unit}</span>
                  </div>
                  <Progress value={pct} className="h-1.5 bg-white/10 [&>div]:bg-amber-400" />
                </div>
              );
            })}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* أعلى المنتجات */}
        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-bold">أعلى المنتجات مبيعًا (7 أيام)</CardTitle>
          </CardHeader>
          <CardContent className="h-56">
            {data.topProducts.length === 0 ? (
              <div className="flex h-full items-center justify-center text-sm text-slate-500">لا مبيعات بعد</div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={data.topProducts} layout="vertical" margin={{ top: 0, right: 8, left: 8, bottom: 0 }}>
                  <XAxis type="number" hide />
                  <YAxis type="category" dataKey="name" tick={{ fill: "#C7CDEB", fontSize: 11 }} width={120} axisLine={false} tickLine={false} orientation="right" />
                  <Tooltip
                    contentStyle={{ background: "#131A3A", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 12 }}
                    formatter={(value: number) => [`${fmt(value)} ر.س`, "المبيعات"]}
                  />
                  <Bar dataKey="sales" radius={[6, 6, 6, 6]} barSize={16}>
                    {data.topProducts.map((_, i) => (
                      <Cell key={i} fill={i % 2 === 0 ? "#8B5CF6" : "#22D3EE"} fillOpacity={0.85} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        {/* أحدث الفواتير */}
        <Card className="sb-glass border-0">
          <CardHeader className="flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-bold">أحدث الفواتير</CardTitle>
            <ReceiptText className="h-4 w-4 text-slate-400" />
          </CardHeader>
          <CardContent className="sb-scroll max-h-56 space-y-2 overflow-y-auto">
            {data.recentInvoices.map((inv) => {
              const st = STATUS_MAP[inv.status] ?? STATUS_MAP.VOID;
              return (
                <div key={inv.id} className="flex items-center justify-between rounded-xl bg-white/[0.03] px-3 py-2">
                  <div className="flex items-center gap-2">
                    <ArrowLeft className="h-3.5 w-3.5 text-slate-500" />
                    <div>
                      <div className="text-xs font-bold sb-num">{inv.number}</div>
                      <div className="text-[10px] text-slate-500">
                        {inv.party?.name ?? "عميل نقدي"} · {new Date(inv.date).toLocaleDateString("ar-SA")}
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="sb-num text-sm font-bold">{fmt(inv.total / 100)}</span>
                    <Badge variant="outline" className={cn("sb-chip", st.cls)}>{st.label}</Badge>
                  </div>
                </div>
              );
            })}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
