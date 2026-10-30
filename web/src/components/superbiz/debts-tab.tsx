"use client";

/** الذمم — أرصدة الأطراف + تحصيل سداد + إضافة طرف */
import { useEffect, useState } from "react";
import { HandCoins, UserPlus, Loader2, Phone } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "sonner";

type Party = {
  id: string; name: string; phone: string | null; type: number;
  balance: number; balanceNum: number; openInvoices: number;
};

const fmt = (n: number) => (n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function DebtsTab({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [parties, setParties] = useState<Party[] | null>(null);
  const [totalAR, setTotalAR] = useState(0);
  const [collect, setCollect] = useState<Party | null>(null);
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [newParty, setNewParty] = useState({ name: "", phone: "" });

  useEffect(() => {
    let alive = true;
    fetch("/api/parties")
      .then((r) => r.json())
      .then((d) => { if (alive) { setParties(d.parties ?? []); setTotalAR(d.totalARNum ?? 0); } })
      .catch(() => alive && setParties([]));
    return () => { alive = false; };
  }, [refreshKey]);

  async function doCollect() {
    if (!collect) return;
    const amt = Number(amount);
    if (!amt || amt <= 0) return toast.error("أدخل مبلغًا صحيحًا");
    setBusy(true);
    try {
      const res = await fetch("/api/payments", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ partyId: collect.id, amount: Math.round(amt * 100) }),
      });
      const data = await res.json();
      if (!res.ok) toast.error(data.error ?? "فشل التحصيل");
      else {
        toast.success(data.message ?? "تم التحصيل");
        setCollect(null);
        setAmount("");
        onChanged();
      }
    } finally { setBusy(false); }
  }

  async function addParty() {
    if (!newParty.name.trim()) return toast.error("اسم الطرف مطلوب");
    const res = await fetch("/api/parties", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(newParty),
    });
    const data = await res.json();
    if (!res.ok) toast.error(data.error ?? "فشل الحفظ");
    else {
      toast.success("أُضيف الطرف");
      setAddOpen(false);
      setNewParty({ name: "", phone: "" });
      onChanged();
    }
  }

  const debtors = (parties ?? []).filter((p) => p.balance > 0);

  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <HandCoins className="h-5 w-5 sb-amber-text" />
          الذمم المدينة
          <Badge variant="outline" className="sb-chip border-amber-400/30 bg-amber-400/10 text-amber-300">
            الإجمالي <span className="sb-num">{fmt(totalAR)}</span> ر.س
          </Badge>
        </h2>
        <Button size="sm" onClick={() => setAddOpen(true)} className="gap-1.5 bg-violet-600 text-xs hover:bg-violet-500">
          <UserPlus className="h-4 w-4" /> طرف جديد
        </Button>
      </div>

      <Card className="sb-glass border-0">
        <CardContent className="p-0">
          {!parties ? (
            <div className="space-y-2 p-4">{Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-12 bg-white/5" />)}</div>
          ) : (
            <div className="sb-scroll overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow className="border-white/5 hover:bg-transparent">
                    <TableHead className="text-right text-slate-400">الطرف</TableHead>
                    <TableHead className="text-right text-slate-400">الهاتف</TableHead>
                    <TableHead className="text-center text-slate-400">فواتير مفتوحة</TableHead>
                    <TableHead className="text-left text-slate-400">الرصيد المستحق</TableHead>
                    <TableHead className="text-center text-slate-400">تحصيل</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {parties.map((p) => (
                    <TableRow key={p.id} className="border-white/5 hover:bg-white/[0.03]">
                      <TableCell className="text-sm font-semibold">{p.name}</TableCell>
                      <TableCell className="sb-num text-xs text-slate-400" dir="ltr">{p.phone ?? "—"}</TableCell>
                      <TableCell className="sb-num text-center text-xs">{p.openInvoices}</TableCell>
                      <TableCell className="sb-num text-left font-bold">
                        <span className={p.balance > 0 ? "sb-amber-text" : "sb-green-text"}>{fmt(p.balanceNum)} ر.س</span>
                      </TableCell>
                      <TableCell className="text-center">
                        {p.balance > 0 ? (
                          <Button size="sm" variant="outline" className="h-7 gap-1 border-emerald-400/30 bg-emerald-400/10 text-xs text-emerald-300 hover:bg-emerald-400/20" onClick={() => { setCollect(p); setAmount(String(Math.max(1, Math.round(p.balanceNum)))); }}>
                            <HandCoins className="h-3.5 w-3.5" /> تحصيل
                          </Button>
                        ) : (
                          <span className="text-xs text-slate-500">مسدد ✓</span>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      {debtors.length === 0 && parties && (
        <div className="sb-glass py-8 text-center text-sm text-emerald-300">لا ذمم مدينة مفتوحة — التحصيل ممتاز ✓</div>
      )}

      {/* حوار التحصيل */}
      <Dialog open={!!collect} onOpenChange={(o) => !o && setCollect(null)}>
        <DialogContent className="sb-glass-strong border-white/10 sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>تحصيل من {collect?.name}</DialogTitle>
            <DialogDescription>
              المتبقي المستحق: <span className="sb-num">{fmt(collect?.balanceNum ?? 0)}</span> ر.س — القيد: نقدية مدينة / ذمم دائنة
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1">
            <Label className="text-xs">مبلغ التحصيل (ر.س)</Label>
            <Input type="number" min="1" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} className="border-white/10 bg-white/5 sb-num" />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCollect(null)} className="border-white/10 bg-white/5">إلغاء</Button>
            <Button onClick={doCollect} disabled={busy} className="bg-emerald-600 hover:bg-emerald-500">
              {busy && <Loader2 className="h-4 w-4 animate-spin" />} تأكيد التحصيل
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* حوار إضافة طرف */}
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent className="sb-glass-strong border-white/10 sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>طرف جديد</DialogTitle>
            <DialogDescription>عميل أو مورد — يظهر في نقطة البيع الآجلة والذمم</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label className="text-xs">الاسم *</Label>
              <Input value={newParty.name} onChange={(e) => setNewParty({ ...newParty, name: e.target.value })} className="border-white/10 bg-white/5" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs flex items-center gap-1"><Phone className="h-3 w-3" /> الهاتف</Label>
              <Input value={newParty.phone} onChange={(e) => setNewParty({ ...newParty, phone: e.target.value })} className="border-white/10 bg-white/5 sb-num" dir="ltr" />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAddOpen(false)} className="border-white/10 bg-white/5">إلغاء</Button>
            <Button onClick={addParty} className="bg-violet-600 hover:bg-violet-500">حفظ</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
