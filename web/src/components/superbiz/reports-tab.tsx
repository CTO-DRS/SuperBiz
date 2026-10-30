"use client";

/** التقارير — سلسلة يومية + ملخص ضريبي + أعلى المنتجات + فئات + مزيج الدفع */
import { useEffect, useState } from "react";
import { BarChart3, TrendingUp, Percent, CreditCard, Banknote } from "lucide-react";
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell, BarChart as RBarChart, Bar,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

type ReportData = {
  days: number;
  series: { date: string; sales: number; profit: number; expenses: number }[];
  summary: { sales: number; netSales: number; tax: number; profit: number; cost: number };
  topProducts: { name: string; sales: number; qty: number }[];
  categories: { name: string; value: number }[];
  paymentMix: { cash: number; credit: number };
};

const fmt = (n: number) => (n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const PIE_COLORS = ["#8B5CF6", "#22D3EE", "#34D399", "#FBBF24", "#F87171", "#A78BFA", "#67E8F9", "#6EE7B7"];

export default function ReportsTab({ refreshKey }: { refreshKey: number }) {
  const [days, setDays] = useState("30");
  const [loaded, setLoaded] = useState<{ key: string; data: ReportData } | null>(null);

  useEffect(() => {
    let alive = true;
    fetch(`/api/reports?days=${days}`)
      .then((r) => r.json())
      .then((d) => alive && setLoaded({ key: days, data: d }))
      .catch(() => {});
    return () => { alive = false; };
  }, [days, refreshKey]);

  const data = loaded?.key === `${days}` ? loaded.data : null;

  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <BarChart3 className="h-5 w-5 sb-cyan-text" />
          التقارير المالية
        </h2>
        <Tabs value={days} onValueChange={setDays}>
          <TabsList className="bg-white/5">
            <TabsTrigger value="7" className="text-xs">7 أيام</TabsTrigger>
            <TabsTrigger value="30" className="text-xs">30 يومًا</TabsTrigger>
            <TabsTrigger value="90" className="text-xs">90 يومًا</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      {!data ? (
        <div className="grid gap-4">
          <Skeleton className="h-72 rounded-2xl bg-white/5" />
          <div className="grid gap-4 lg:grid-cols-2">
            <Skeleton className="h-56 rounded-2xl bg-white/5" />
            <Skeleton className="h-56 rounded-2xl bg-white/5" />
          </div>
        </div>
      ) : (
        <>
          {/* الملخص */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {[
              { label: "إجمالي المبيعات (بالضريبة)", v: data.summary.sales, icon: TrendingUp, cls: "sb-violet-text" },
              { label: "صافي المبيعات", v: data.summary.netSales, icon: TrendingUp, cls: "sb-cyan-text" },
              { label: `ضريبة القيمة المضافة (${data.summary.sales > 0 ? ((data.summary.tax / (data.summary.sales - data.summary.tax)) * 100).toFixed(0) : 15}%)`, v: data.summary.tax, icon: Percent, cls: "sb-amber-text" },
              { label: "صافي الربح", v: data.summary.profit, icon: BarChart3, cls: "sb-green-text" },
            ].map((k) => (
              <Card key={k.label} className="sb-glass border-0">
                <CardContent className="p-4">
                  <div className="text-xs font-semibold text-slate-400">{k.label}</div>
                  <div className={cn("mt-1.5 text-xl font-extrabold sb-num", k.cls)}>{fmt(k.v)} <span className="text-[11px] font-medium text-slate-400">ر.س</span></div>
                </CardContent>
              </Card>
            ))}
          </div>

          {/* السلسلة اليومية */}
          <Card className="sb-glass border-0">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-bold">المبيعات مقابل الأرباح مقابل المصروفات ({data.days} يومًا)</CardTitle>
            </CardHeader>
            <CardContent className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={data.series} margin={{ top: 5, right: 5, left: 5, bottom: 0 }}>
                  <XAxis dataKey="date" tick={{ fill: "#8B93B8", fontSize: 10 }} axisLine={false} tickLine={false} reversed interval="preserveStartEnd" />
                  <YAxis tick={{ fill: "#8B93B8", fontSize: 10 }} axisLine={false} tickLine={false} width={56} orientation="right" />
                  <Tooltip
                    contentStyle={{ background: "#131A3A", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 12 }}
                    formatter={(value: number, name: string) => [
                      `${fmt(value)} ر.س`,
                      name === "sales" ? "المبيعات" : name === "profit" ? "الربح" : "المصروفات",
                    ]}
                  />
                  <Line type="monotone" dataKey="sales" stroke="#8B5CF6" strokeWidth={2.5} dot={false} />
                  <Line type="monotone" dataKey="profit" stroke="#34D399" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="expenses" stroke="#F87171" strokeWidth={1.5} dot={false} strokeDasharray="4 3" />
                </LineChart>
              </ResponsiveContainer>
            </CardContent>
          </Card>

          <div className="grid gap-4 lg:grid-cols-3">
            {/* أعلى المنتجات */}
            <Card className="sb-glass border-0 lg:col-span-2">
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-bold">أعلى المنتجات ({data.days} يومًا)</CardTitle>
              </CardHeader>
              <CardContent className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <RBarChart data={data.topProducts} layout="vertical" margin={{ top: 0, right: 8, left: 8, bottom: 0 }}>
                    <XAxis type="number" hide />
                    <YAxis type="category" dataKey="name" tick={{ fill: "#C7CDEB", fontSize: 11 }} width={130} axisLine={false} tickLine={false} orientation="right" />
                    <Tooltip
                      contentStyle={{ background: "#131A3A", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 12 }}
                      formatter={(value: number) => [`${fmt(value)} ر.س`, "المبيعات"]}
                    />
                    <Bar dataKey="sales" radius={[6, 6, 6, 6]} barSize={14} fill="#8B5CF6" fillOpacity={0.85} />
                  </RBarChart>
                </ResponsiveContainer>
              </CardContent>
            </Card>

            {/* الفئات */}
            <Card className="sb-glass border-0">
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-bold">المبيعات بالفئات</CardTitle>
              </CardHeader>
              <CardContent className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={data.categories} dataKey="value" nameKey="name" innerRadius="55%" outerRadius="80%" paddingAngle={3}>
                      {data.categories.map((_, i) => (
                        <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} stroke="transparent" />
                      ))}
                    </Pie>
                    <Tooltip
                      contentStyle={{ background: "#131A3A", border: "1px solid rgba(255,255,255,0.12)", borderRadius: 12 }}
                      formatter={(value: number, name: string) => [`${fmt(value)} ر.س`, name]}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="flex flex-wrap justify-center gap-2 text-[10px] text-slate-400">
                  {data.categories.map((c, i) => (
                    <span key={c.name} className="flex items-center gap-1">
                      <span className="h-2 w-2 rounded-full" style={{ background: PIE_COLORS[i % PIE_COLORS.length] }} />
                      {c.name}
                    </span>
                  ))}
                </div>
              </CardContent>
            </Card>
          </div>

          {/* مزيج الدفع */}
          <Card className="sb-glass border-0">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-sm font-bold">
                <CreditCard className="h-4 w-4 sb-cyan-text" /> مزيج الدفع
              </CardTitle>
            </CardHeader>
            <CardContent className="grid gap-3 sm:grid-cols-2">
              <div className="flex items-center justify-between rounded-xl bg-white/[0.03] p-4">
                <div className="flex items-center gap-2 text-sm font-semibold">
                  <Banknote className="h-4 w-4 sb-green-text" /> نقدية (محصّلة)
                </div>
                <div className="sb-num text-lg font-extrabold sb-green-text">{fmt(data.paymentMix.cash)} ر.س</div>
              </div>
              <div className="flex items-center justify-between rounded-xl bg-white/[0.03] p-4">
                <div className="flex items-center gap-2 text-sm font-semibold">
                  <CreditCard className="h-4 w-4 sb-amber-text" /> آجل (ذمم مدينة)
                </div>
                <div className="sb-num text-lg font-extrabold sb-amber-text">{fmt(data.paymentMix.credit)} ر.س</div>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
