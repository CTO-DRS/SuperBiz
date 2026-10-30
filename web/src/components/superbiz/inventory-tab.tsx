"use client";

/** المخزون — قائمة المنتجات + إضافة/تعديل + أرشفة + تسوية جرد */
import { useEffect, useState } from "react";
import { Package, Plus, Pencil, Archive, ArchiveRestore, Loader2, Boxes } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

type Product = {
  id: string; name: string; sku: string; barcode: string | null; unit: string; category: string;
  costPrice: number; salePrice: number; stockQty: number; reorderLevel: number; archived: boolean;
};

const fmt = (qirsh: number) => (qirsh / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const EMPTY = { id: "", name: "", sku: "", category: "بقالة", unit: "حبة", costPrice: "", salePrice: "", stockQty: "", reorderLevel: "5" };

export default function InventoryTab({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [products, setProducts] = useState<Product[] | null>(null);
  const [stockValue, setStockValue] = useState(0);
  const [showArchived, setShowArchived] = useState(false);
  const [dialog, setDialog] = useState(false);
  const [form, setForm] = useState(EMPTY);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    fetch(`/api/products?archived=${showArchived ? "1" : "0"}`)
      .then((r) => r.json())
      .then((d) => { if (alive) { setProducts(d.products ?? []); setStockValue(d.stockValueNum ?? 0); } })
      .catch(() => alive && setProducts([]));
    return () => { alive = false; };
  }, [refreshKey, showArchived]);

  function openNew() { setForm(EMPTY); setDialog(true); }
  function openEdit(p: Product) {
    setForm({
      id: p.id, name: p.name, sku: p.sku, category: p.category, unit: p.unit,
      costPrice: String(p.costPrice / 100), salePrice: String(p.salePrice / 100),
      stockQty: String(p.stockQty), reorderLevel: String(p.reorderLevel),
    });
    setDialog(true);
  }

  async function save() {
    if (!form.name.trim()) return toast.error("اسم المنتج مطلوب");
    setBusy(true);
    try {
      const isEdit = !!form.id;
      const res = await fetch("/api/products", {
        method: isEdit ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...(isEdit ? { id: form.id } : {}),
          name: form.name,
          sku: isEdit ? undefined : form.sku,
          category: form.category,
          unit: form.unit,
          costPrice: Number(form.costPrice) || 0,
          salePrice: Number(form.salePrice) || 0,
          stockQty: Number(form.stockQty) || 0,
          reorderLevel: Number(form.reorderLevel) || 0,
        }),
      });
      const data = await res.json();
      if (!res.ok) toast.error(data.error ?? "فشل الحفظ");
      else {
        toast.success(isEdit ? "حدّث المنتج" : "أُضيف المنتج");
        setDialog(false);
        onChanged();
      }
    } finally { setBusy(false); }
  }

  async function toggleArchive(p: Product) {
    const res = await fetch("/api/products", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id: p.id, archived: !p.archived }),
    });
    if (res.ok) {
      toast.success(p.archived ? `أُعيد ${p.name} من الأرشيف` : `أُرشف ${p.name}`);
      onChanged();
    } else toast.error("فشل التعديل");
  }

  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <Package className="h-5 w-5 sb-violet-text" />
          المخزون
          <Badge variant="outline" className="sb-chip border-cyan-400/30 bg-cyan-400/10 text-cyan-300">
            <Boxes className="h-3.5 w-3.5" /> قيمة المخزون <span className="sb-num">{fmt(stockValue)}</span> ر.س
          </Badge>
        </h2>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => setShowArchived((v) => !v)} className="border-white/15 bg-white/5 text-xs">
            {showArchived ? "عرض النشط" : "عرض الأرشيف"}
          </Button>
          <Button size="sm" onClick={openNew} className="gap-1.5 bg-violet-600 text-xs hover:bg-violet-500">
            <Plus className="h-4 w-4" /> منتج جديد
          </Button>
        </div>
      </div>

      <Card className="sb-glass border-0">
        <CardContent className="p-0">
          {!products ? (
            <div className="space-y-2 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12 bg-white/5" />)}</div>
          ) : products.length === 0 ? (
            <div className="py-16 text-center text-sm text-slate-500">لا منتجات {showArchived ? "مؤرشفة" : ""}</div>
          ) : (
            <div className="sb-scroll overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow className="border-white/5 hover:bg-transparent">
                    <TableHead className="text-right text-slate-400">المنتج</TableHead>
                    <TableHead className="text-right text-slate-400">SKU</TableHead>
                    <TableHead className="text-right text-slate-400">الفئة</TableHead>
                    <TableHead className="text-left text-slate-400">التكلفة</TableHead>
                    <TableHead className="text-left text-slate-400">البيع</TableHead>
                    <TableHead className="text-center text-slate-400">المخزون</TableHead>
                    <TableHead className="text-center text-slate-400">إجراءات</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {products.map((p) => {
                    const low = !p.archived && p.stockQty <= p.reorderLevel;
                    return (
                      <TableRow key={p.id} className={cn("border-white/5 hover:bg-white/[0.03]", p.archived && "opacity-50")}>
                        <TableCell className="text-sm font-semibold">{p.name}</TableCell>
                        <TableCell className="sb-num text-xs text-slate-400">{p.sku}</TableCell>
                        <TableCell><Badge variant="outline" className="sb-chip border-cyan-400/20 bg-cyan-400/5 text-cyan-300">{p.category}</Badge></TableCell>
                        <TableCell className="sb-num text-left text-xs">{fmt(p.costPrice)}</TableCell>
                        <TableCell className="sb-num text-left text-xs font-bold sb-violet-text">{fmt(p.salePrice)}</TableCell>
                        <TableCell className="text-center">
                          <span className={cn("sb-num text-sm font-bold", low ? "text-amber-400" : "text-slate-200")}>
                            {p.stockQty} <span className="text-[10px] font-normal text-slate-500">{p.unit}</span>
                          </span>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center justify-center gap-1">
                            <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => openEdit(p)} aria-label={`تعديل ${p.name}`}>
                              <Pencil className="h-3.5 w-3.5" />
                            </Button>
                            <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => toggleArchive(p)} aria-label={p.archived ? "استعادة" : "أرشفة"}>
                              {p.archived ? <ArchiveRestore className="h-3.5 w-3.5 sb-green-text" /> : <Archive className="h-3.5 w-3.5" />}
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Dialog open={dialog} onOpenChange={setDialog}>
        <DialogContent className="sb-glass-strong border-white/10 sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{form.id ? "تعديل منتج" : "منتج جديد"}</DialogTitle>
            <DialogDescription>الأسعار بالريال — تُخزَّن قروشًا (عقد المال Long)</DialogDescription>
          </DialogHeader>
          <div className="grid grid-cols-2 gap-3">
            <div className="col-span-2 space-y-1">
              <Label className="text-xs">اسم المنتج *</Label>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="border-white/10 bg-white/5" />
            </div>
            {!form.id && (
              <div className="col-span-2 space-y-1">
                <Label className="text-xs">رمز SKU (تلقائي إن تُرك فارغًا)</Label>
                <Input value={form.sku} onChange={(e) => setForm({ ...form, sku: e.target.value })} className="border-white/10 bg-white/5 sb-num" dir="ltr" />
              </div>
            )}
            <div className="space-y-1">
              <Label className="text-xs">الفئة</Label>
              <Input value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} className="border-white/10 bg-white/5" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">الوحدة</Label>
              <Input value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} className="border-white/10 bg-white/5" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">سعر التكلفة (ر.س)</Label>
              <Input type="number" min="0" step="0.01" value={form.costPrice} onChange={(e) => setForm({ ...form, costPrice: e.target.value })} className="border-white/10 bg-white/5 sb-num" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">سعر البيع (ر.س)</Label>
              <Input type="number" min="0" step="0.01" value={form.salePrice} onChange={(e) => setForm({ ...form, salePrice: e.target.value })} className="border-white/10 bg-white/5 sb-num" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">الجرد الحالي</Label>
              <Input type="number" min="0" step="1" value={form.stockQty} onChange={(e) => setForm({ ...form, stockQty: e.target.value })} className="border-white/10 bg-white/5 sb-num" />
            </div>
            <div className="space-y-1">
              <Label className="text-xs">حد إعادة الطلب</Label>
              <Input type="number" min="0" step="1" value={form.reorderLevel} onChange={(e) => setForm({ ...form, reorderLevel: e.target.value })} className="border-white/10 bg-white/5 sb-num" />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialog(false)} className="border-white/10 bg-white/5">إلغاء</Button>
            <Button onClick={save} disabled={busy} className="bg-violet-600 hover:bg-violet-500">
              {busy && <Loader2 className="h-4 w-4 animate-spin" />} حفظ
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
