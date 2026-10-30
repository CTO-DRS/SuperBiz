"use client";

/** نقطة البيع — شبكة منتجات + سلة ذكية + دفع نقدي/آجل + QR ضريبي بعد الإصدار */
import { useEffect, useMemo, useState } from "react";
import { ShoppingCart, Trash2, Plus, Minus, BadgePercent, ScanBarcode, CheckCircle2, Loader2 } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

type Product = {
  id: string; name: string; sku: string; unit: string; category: string;
  salePrice: number; stockQty: number; reorderLevel: number;
};
type Party = { id: string; name: string; balanceNum: number };
type CartLine = { productId: string; name: string; qty: number; unitPrice: number };

const fmt = (qirsh: number) => (qirsh / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function PosTab({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [products, setProducts] = useState<Product[]>([]);
  const [parties, setParties] = useState<Party[]>([]);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [credit, setCredit] = useState(false);
  const [partyId, setPartyId] = useState<string>("");
  const [search, setSearch] = useState("");
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState<{ number: string; total: number; qr: string; credit: boolean } | null>(null);

  useEffect(() => {
    fetch("/api/products").then((r) => r.json()).then((d) => setProducts(d.products ?? [])).catch(() => {});
    fetch("/api/parties").then((r) => r.json()).then((d) => setParties(d.parties ?? [])).catch(() => {});
  }, [refreshKey]);

  const filtered = useMemo(
    () => products.filter((p) => !p.archived && (!search || p.name.includes(search) || p.sku.toLowerCase().includes(search.toLowerCase()))),
    [products, search]
  );

  const subtotal = cart.reduce((s, l) => s + l.unitPrice * l.qty, 0);
  const taxRate = 15;
  const taxAmount = Math.round((subtotal * taxRate) / 100);
  const total = subtotal + taxAmount;

  function addToCart(p: Product) {
    if (p.stockQty <= 0) {
      toast.warning(`لا مخزون متاح: ${p.name}`);
      return;
    }
    setCart((c) => {
      const existing = c.find((l) => l.productId === p.id);
      if (existing) {
        if (existing.qty >= p.stockQty) {
          toast.warning(`الحد الأقصى المتاح من ${p.name}: ${p.stockQty}`);
          return c;
        }
        return c.map((l) => (l.productId === p.id ? { ...l, qty: l.qty + 1 } : l));
      }
      return [...c, { productId: p.id, name: p.name, qty: 1, unitPrice: p.salePrice }];
    });
  }

  function setQty(productId: string, qty: number) {
    const p = products.find((x) => x.id === productId);
    if (p && qty > p.stockQty) {
      toast.warning(`الحد الأقصى المتاح: ${p.stockQty}`);
      qty = p.stockQty;
    }
    setCart((c) => (qty <= 0 ? c.filter((l) => l.productId !== productId) : c.map((l) => (l.productId === productId ? { ...l, qty } : l))));
  }

  async function checkout() {
    if (cart.length === 0) {
      toast.error("السلة فارغة — أضف منتجات أولًا");
      return;
    }
    if (credit && !partyId) {
      toast.error("البيع الآجل يتطلب اختيار العميل (ذمم مدينة)");
      return;
    }
    setBusy(true);
    try {
      const res = await fetch("/api/invoices", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          credit,
          partyId: credit ? partyId : undefined,
          taxRate,
          items: cart.map((l) => ({ productId: l.productId, qty: l.qty })),
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        toast.error(data.error ?? "فشل إصدار الفاتورة");
        return;
      }
      setReceipt({ number: data.invoice.number, total: data.invoice.total, qr: data.zatcaQrImage, credit });
      toast.success(`تم إصدار الفاتورة ${data.invoice.number} — القيد متوازن والمخزون خُصم ذريًا`);
      setCart([]);
      setCredit(false);
      setPartyId("");
      onChanged();
    } catch {
      toast.error("تعذر الاتصال بالخادم");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sb-fade-in grid gap-4 lg:grid-cols-5">
      {/* شبكة المنتجات */}
      <div className="space-y-3 lg:col-span-3">
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <ScanBarcode className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="ابحث بالاسم أو رمز SKU أو الباركود…"
              className="border-white/10 bg-white/5 pr-9 text-sm placeholder:text-slate-500"
            />
          </div>
          <Badge variant="outline" className="sb-chip border-violet-400/30 bg-violet-400/10 text-violet-300">
            {filtered.length} منتج
          </Badge>
        </div>

        <div className="sb-scroll max-h-[62vh] overflow-y-auto pl-1">
          <div className="sb-pos-grid">
            {filtered.map((p) => {
              const low = p.stockQty <= p.reorderLevel;
              const out = p.stockQty <= 0;
              return (
                <button
                  key={p.id}
                  onClick={() => addToCart(p)}
                  disabled={out}
                  className={cn(
                    "sb-glass group relative flex flex-col items-start p-3 text-right transition-all",
                    out ? "cursor-not-allowed opacity-40" : "hover:-translate-y-0.5 hover:border-violet-400/40 hover:shadow-lg hover:shadow-violet-500/10"
                  )}
                >
                  <Badge variant="outline" className="sb-chip mb-1.5 border-cyan-400/20 bg-cyan-400/5 text-cyan-300">
                    {p.category}
                  </Badge>
                  <span className="line-clamp-2 min-h-9 text-[13px] font-bold leading-snug">{p.name}</span>
                  <div className="mt-auto flex w-full items-center justify-between pt-2">
                    <span className="sb-num text-sm font-extrabold sb-violet-text">{fmt(p.salePrice)}</span>
                    <span className={cn("sb-num text-[10px]", low ? "text-amber-400" : "text-slate-500")}>
                      {p.stockQty} {p.unit}
                    </span>
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* السلة */}
      <Card className="sb-glass h-fit border-0 lg:col-span-2">
        <CardHeader className="flex-row items-center justify-between pb-3">
          <CardTitle className="flex items-center gap-2 text-sm font-bold">
            <ShoppingCart className="h-4 w-4 sb-violet-text" />
            السلة ({cart.length})
          </CardTitle>
          {cart.length > 0 && (
            <Button size="sm" variant="ghost" onClick={() => setCart([])} className="h-7 gap-1 text-xs text-rose-300 hover:bg-rose-500/10 hover:text-rose-200">
              <Trash2 className="h-3.5 w-3.5" /> تفريغ
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="sb-scroll max-h-56 space-y-2 overflow-y-auto">
            {cart.length === 0 && (
              <div className="rounded-xl border border-dashed border-white/10 py-10 text-center text-sm text-slate-500">
                اضغط على أي منتج لإضافته للسلة
              </div>
            )}
            {cart.map((l) => (
              <div key={l.productId} className="flex items-center gap-2 rounded-xl bg-white/[0.04] p-2.5">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-xs font-bold">{l.name}</div>
                  <div className="sb-num text-[10px] text-slate-400">{fmt(l.unitPrice)} × {l.qty}</div>
                </div>
                <div className="flex items-center gap-1">
                  <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => setQty(l.productId, l.qty - 1)} aria-label="إنقاص">
                    <Minus className="h-3.5 w-3.5" />
                  </Button>
                  <span className="sb-num w-7 text-center text-sm font-bold">{l.qty}</span>
                  <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => setQty(l.productId, l.qty + 1)} aria-label="زيادة">
                    <Plus className="h-3.5 w-3.5" />
                  </Button>
                </div>
                <div className="sb-num w-20 text-left text-sm font-extrabold sb-cyan-text">{fmt(l.unitPrice * l.qty)}</div>
              </div>
            ))}
          </div>

          {/* الإجماليات */}
          <div className="space-y-1.5 rounded-xl bg-white/[0.03] p-3 text-sm">
            <div className="flex justify-between text-slate-300">
              <span>الإجمالي قبل الضريبة</span>
              <span className="sb-num">{fmt(subtotal)} ر.س</span>
            </div>
            <div className="flex justify-between text-slate-300">
              <span className="flex items-center gap-1">
                <BadgePercent className="h-3.5 w-3.5 sb-cyan-text" /> ضريبة القيمة المضافة ({taxRate}%)
              </span>
              <span className="sb-num">{fmt(taxAmount)} ر.س</span>
            </div>
            <div className="flex justify-between border-t border-white/10 pt-2 text-base font-extrabold">
              <span>الإجمالي</span>
              <span className="sb-num sb-green-text">{fmt(total)} ر.س</span>
            </div>
          </div>

          {/* طريقة الدفع */}
          <div className="flex items-center justify-between rounded-xl bg-white/[0.03] p-3">
            <Label htmlFor="credit" className="text-sm font-semibold">بيع آجل (ذمم)</Label>
            <Switch id="credit" checked={credit} onCheckedChange={setCredit} />
          </div>
          {credit && (
            <Select value={partyId} onValueChange={setPartyId}>
              <SelectTrigger className="border-white/10 bg-white/5 text-sm">
                <SelectValue placeholder="اختر العميل…" />
              </SelectTrigger>
              <SelectContent className="sb-glass-strong border-white/10">
                {parties.map((p) => (
                  <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}

          <Button
            onClick={checkout}
            disabled={busy || cart.length === 0}
            className="w-full bg-gradient-to-l from-[#8B5CF6] to-[#7C3AED] py-6 text-base font-bold hover:opacity-90"
          >
            {busy ? <Loader2 className="h-5 w-5 animate-spin" /> : <CheckCircle2 className="h-5 w-5" />}
            {busy ? "جارٍ الإصدار…" : `إصدار الفاتورة — ${fmt(total)} ر.س`}
          </Button>
          <p className="text-center text-[10px] leading-relaxed text-slate-500">
            الحفظ ذرّي: بنود + خصم مخزون + قيد مزدوج متوازن + ختم ZATCA داخل معاملة واحدة
          </p>
        </CardContent>
      </Card>

      {/* إيصال النجاح */}
      <Dialog open={!!receipt} onOpenChange={(o) => !o && setReceipt(null)}>
        <DialogContent className="sb-glass-strong border-white/10 sm:max-w-sm">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <CheckCircle2 className="h-5 w-5 sb-green-text" />
              فاتورة {receipt?.number}
            </DialogTitle>
            <DialogDescription>
              {receipt?.credit ? "بيع آجل — سُجّل بالذمم المدينة" : "بيع نقدي — قُبض الكامل"}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col items-center gap-3 py-2">
            <div className="sb-num text-3xl font-extrabold sb-green-text">{fmt(receipt?.total ?? 0)} ر.س</div>
            {receipt?.qr && (
              <div className="rounded-xl bg-white p-2">
                <img src={receipt.qr} alt="QR ضريبي ZATCA مرحلة-1" className="h-40 w-40" />
              </div>
            )}
            <p className="text-center text-[11px] leading-relaxed text-slate-400">
              QR ضريبي مرحلة-1 (TLV Base64) مطابق لمحرك <span className="sb-num">ZatcaQr.kt</span> — الوسوم 1..5
            </p>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
