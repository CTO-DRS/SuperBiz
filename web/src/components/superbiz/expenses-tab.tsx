"use client";

/** المصروفات — إضافة سريعة + تجميع بالفئة + قائمة شهرية */
import { useEffect, useState } from "react";
import { Receipt, Loader2, Plus } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Progress } from "@/components/ui/progress";
import { toast } from "sonner";

type Expense = { id: string; title: string; category: string; amount: number; date: string; note: string | null };
type CategoryRow = { category: string; amountNum: number };

const fmt = (n: number) => (n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const CATEGORIES = ["إيجار", "مرافق", "رواتب", "تشغيل", "صيانة", "نقل", "تسويق", "عام"];

export default function ExpensesTab({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [expenses, setExpenses] = useState<Expense[] | null>(null);
  const [total, setTotal] = useState(0);
  const [byCategory, setByCategory] = useState<CategoryRow[]>([]);
  const [form, setForm] = useState({ title: "", category: "تشغيل", amount: "" });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    fetch("/api/expenses")
      .then((r) => r.json())
      .then((d) => { if (alive) { setExpenses(d.expenses ?? []); setTotal(d.totalNum ?? 0); setByCategory(d.byCategory ?? []); } })
      .catch(() => alive && setExpenses([]));
    return () => { alive = false; };
  }, [refreshKey]);

  async function add() {
    if (!form.title.trim()) return toast.error("عنوان المصروف مطلوب");
    if (!Number(form.amount) || Number(form.amount) <= 0) return toast.error("أدخل مبلغًا صحيحًا");
    setBusy(true);
    try {
      const res = await fetch("/api/expenses", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: form.title, category: form.category, amount: Number(form.amount) }),
      });
      const data = await res.json();
      if (!res.ok) toast.error(data.error ?? "فشل الحفظ");
      else {
        toast.success(data.message ?? "أُضيف المصروف");
        setForm({ title: "", category: "تشغيل", amount: "" });
        onChanged();
      }
    } finally { setBusy(false); }
  }

  const monthlyLimit = 1500000; // 15,000 ر.س — حد شهري (إنذار كما في التطبيق)
  const limitPct = Math.min(100, (total / monthlyLimit) * 100);

  return (
    <div className="sb-fade-in grid gap-4 lg:grid-cols-3">
      <div className="space-y-4 lg:col-span-2">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <Receipt className="h-5 w-5 sb-red-text" />
          المصروفات — الشهر الحالي
          <Badge variant="outline" className="sb-chip border-rose-400/30 bg-rose-400/10 text-rose-300">
            <span className="sb-num">{fmt(total)}</span> ر.س
          </Badge>
        </h2>

        <Card className="sb-glass border-0">
          <CardContent className="p-0">
            {!expenses ? (
              <div className="space-y-2 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12 bg-white/5" />)}</div>
            ) : expenses.length === 0 ? (
              <div className="py-16 text-center text-sm text-slate-500">لا مصروفات هذا الشهر</div>
            ) : (
              <div className="sb-scroll max-h-[50vh] overflow-y-auto">
                <Table>
                  <TableHeader>
                    <TableRow className="border-white/5 hover:bg-transparent">
                      <TableHead className="text-right text-slate-400">المصروف</TableHead>
                      <TableHead className="text-right text-slate-400">الفئة</TableHead>
                      <TableHead className="text-right text-slate-400">التاريخ</TableHead>
                      <TableHead className="text-left text-slate-400">المبلغ</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {expenses.map((e) => (
                      <TableRow key={e.id} className="border-white/5 hover:bg-white/[0.03]">
                        <TableCell className="text-sm font-semibold">{e.title}</TableCell>
                        <TableCell><Badge variant="outline" className="sb-chip border-cyan-400/20 bg-cyan-400/5 text-cyan-300">{e.category}</Badge></TableCell>
                        <TableCell className="text-xs text-slate-400">{new Date(e.date).toLocaleDateString("ar-SA")}</TableCell>
                        <TableCell className="sb-num text-left font-bold text-rose-300">{fmt(e.amount / 100)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <div className="space-y-4">
        {/* إضافة سريعة */}
        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center gap-1.5 text-sm font-bold"><Plus className="h-4 w-4 sb-violet-text" /> مصروف جديد</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1">
              <Label className="text-xs">العنوان *</Label>
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} className="border-white/10 bg-white/5" placeholder="مثال: فاتورة ماء" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">الفئة</Label>
              <div className="flex flex-wrap gap-1">
                {CATEGORIES.map((c) => (
                  <button
                    key={c}
                    onClick={() => setForm({ ...form, category: c })}
                    className={`rounded-full px-2.5 py-1 text-[11px] font-semibold transition-colors ${form.category === c ? "bg-violet-500/25 text-violet-200 ring-1 ring-violet-400/40" : "bg-white/5 text-slate-400 hover:bg-white/10"}`}
                  >
                    {c}
                  </button>
                ))}
              </div>
            </div>
            <div className="space-y-1">
              <Label className="text-xs">المبلغ (ر.س)</Label>
              <Input type="number" min="0" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="border-white/10 bg-white/5 sb-num" />
            </div>
            <Button onClick={add} disabled={busy} className="w-full bg-violet-600 hover:bg-violet-500">
              {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />} إضافة وقيد
            </Button>
            <p className="text-center text-[10px] text-slate-500">القيد: مصروفات مدينة / نقدية دائنة — متوازن حتميًا</p>
          </CardContent>
        </Card>

        {/* حد شهري + فئات */}
        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-bold">الحد الشمري التقديري</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <Progress value={limitPct} className={limitPct > 80 ? "h-2 bg-white/10 [&>div]:bg-rose-400" : "h-2 bg-white/10 [&>div]:bg-cyan-400"} />
            <p className="text-xs text-slate-400">
              <span className="sb-num font-bold">{fmt(total)}</span> من <span className="sb-num">15,000.00</span> ر.س
              {limitPct > 80 && <span className="mr-1 sb-red-text"> — اقترب الحد!</span>}
            </p>
          </CardContent>
        </Card>

        <Card className="sb-glass border-0">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-bold">تجميع بالفئات</CardTitle>
          </CardHeader>
          <CardContent className="sb-scroll max-h-52 space-y-2.5 overflow-y-auto">
            {byCategory.map((c) => (
              <div key={c.category}>
                <div className="mb-1 flex justify-between text-xs">
                  <span className="font-semibold text-slate-200">{c.category}</span>
                  <span className="sb-num text-slate-400">{fmt(c.amountNum)}</span>
                </div>
                <Progress value={total > 0 ? (c.amountNum / total) * 100 : 0} className="h-1.5 bg-white/10 [&>div]:bg-violet-400" />
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
