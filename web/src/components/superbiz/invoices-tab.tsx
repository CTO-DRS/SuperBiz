"use client";

/** الفواتير — قائمة + تفاصيل ببنود وQR ضريبي + إلغاء آمن بقيد تعويضي */
import { useEffect, useState } from "react";
import { FileText, Eye, Ban, Loader2, Receipt } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { toast } from "sonner";
import { cn } from "@/lib/utils";

type InvoiceRow = {
  id: string; number: string; date: string; type: string; credit: boolean;
  subtotal: number; taxAmount: number; total: number; paid: number; status: string;
  party?: { name: string } | null;
};
type InvoiceDetail = InvoiceRow & {
  uuid: string | null; icv: number; costTotal: number;
  items: { id: string; desc: string; qty: number; unitPrice: number; lineTotal: number }[];
  party?: { name: string; phone?: string | null } | null;
};

const fmt = (qirsh: number) => (qirsh / 100).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const STATUS: Record<string, { label: string; cls: string }> = {
  PAID: { label: "مسددة", cls: "border-emerald-400/30 bg-emerald-400/10 text-emerald-300" },
  PARTIAL: { label: "جزئي", cls: "border-amber-400/30 bg-amber-400/10 text-amber-300" },
  UNPAID: { label: "آجل", cls: "border-rose-400/30 bg-rose-400/10 text-rose-300" },
  VOID: { label: "ملغاة", cls: "border-slate-400/30 bg-slate-400/10 text-slate-400" },
};

export default function InvoicesTab({ refreshKey, onChanged }: { refreshKey: number; onChanged: () => void }) {
  const [invoices, setInvoices] = useState<InvoiceRow[] | null>(null);
  const [filter, setFilter] = useState("ALL");
  const [detail, setDetail] = useState<{ inv: InvoiceDetail; qr: string | null } | null>(null);
  const [voidTarget, setVoidTarget] = useState<InvoiceRow | null>(null);
  const [busyVoid, setBusyVoid] = useState(false);

  useEffect(() => {
    let alive = true;
    fetch(`/api/invoices?status=${filter}`)
      .then((r) => r.json())
      .then((d) => alive && setInvoices(d.invoices ?? []))
      .catch(() => alive && setInvoices([]));
    return () => { alive = false; };
  }, [refreshKey, filter]);

  async function openDetail(id: string) {
    const res = await fetch(`/api/invoices?id=${id}`);
    if (!res.ok) return toast.error("تعذر تحميل الفاتورة");
    const d = await res.json();
    setDetail({ inv: d.invoice, qr: d.zatcaQrImage });
  }

  async function doVoid() {
    if (!voidTarget) return;
    setBusyVoid(true);
    try {
      const res = await fetch("/api/invoices", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id: voidTarget.id }),
      });
      const data = await res.json();
      if (!res.ok) toast.error(data.error ?? "فشل الإلغاء");
      else {
        toast.success(data.message ?? "أُلغيت الفاتورة");
        setVoidTarget(null);
        setDetail(null);
        onChanged();
      }
    } finally {
      setBusyVoid(false);
    }
  }

  return (
    <div className="sb-fade-in space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-lg font-bold">
          <FileText className="h-5 w-5 sb-violet-text" />
          الفواتير
        </h2>
        <Tabs value={filter} onValueChange={setFilter}>
          <TabsList className="bg-white/5">
            <TabsTrigger value="ALL" className="text-xs">الكل</TabsTrigger>
            <TabsTrigger value="PAID" className="text-xs">مسددة</TabsTrigger>
            <TabsTrigger value="PARTIAL" className="text-xs">جزئي</TabsTrigger>
            <TabsTrigger value="UNPAID" className="text-xs">آجل</TabsTrigger>
            <TabsTrigger value="VOID" className="text-xs">ملغاة</TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      <Card className="sb-glass border-0">
        <CardContent className="p-0">
          {!invoices ? (
            <div className="space-y-2 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12 bg-white/5" />)}</div>
          ) : invoices.length === 0 ? (
            <div className="py-16 text-center text-sm text-slate-500">لا فواتير في هذا الفلتر</div>
          ) : (
            <div className="sb-scroll overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow className="border-white/5 hover:bg-transparent">
                    <TableHead className="text-right text-slate-400">الرقم</TableHead>
                    <TableHead className="text-right text-slate-400">التاريخ</TableHead>
                    <TableHead className="text-right text-slate-400">الطرف</TableHead>
                    <TableHead className="text-right text-slate-400">النوع</TableHead>
                    <TableHead className="text-left text-slate-400">الإجمالي</TableHead>
                    <TableHead className="text-left text-slate-400">المسدد</TableHead>
                    <TableHead className="text-center text-slate-400">الحالة</TableHead>
                    <TableHead className="text-center text-slate-400">إجراءات</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {invoices.map((inv) => {
                    const st = STATUS[inv.status] ?? STATUS.VOID;
                    return (
                      <TableRow key={inv.id} className="border-white/5 hover:bg-white/[0.03]">
                        <TableCell className="sb-num font-bold sb-cyan-text">{inv.number}</TableCell>
                        <TableCell className="text-xs text-slate-400">{new Date(inv.date).toLocaleDateString("ar-SA")}</TableCell>
                        <TableCell className="text-xs">{inv.party?.name ?? "عميل نقدي"}</TableCell>
                        <TableCell>
                          <span className={cn("text-xs font-semibold", inv.type === "RETURN" ? "sb-amber-text" : "sb-green-text")}>
                            {inv.type === "RETURN" ? "مرتجع" : "بيع"}
                          </span>
                        </TableCell>
                        <TableCell className="sb-num text-left font-bold">{fmt(inv.total)}</TableCell>
                        <TableCell className="sb-num text-left text-slate-300">{fmt(inv.paid)}</TableCell>
                        <TableCell className="text-center">
                          <Badge variant="outline" className={cn("sb-chip", st.cls)}>{st.label}</Badge>
                        </TableCell>
                        <TableCell>
                          <div className="flex items-center justify-center gap-1">
                            <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => openDetail(inv.id)} aria-label={`عرض ${inv.number}`}>
                              <Eye className="h-4 w-4" />
                            </Button>
                            {inv.status !== "VOID" && (
                              <Button size="icon" variant="ghost" className="h-8 w-8 text-rose-300 hover:bg-rose-500/10" onClick={() => setVoidTarget(inv)} aria-label={`إلغاء ${inv.number}`}>
                                <Ban className="h-4 w-4" />
                              </Button>
                            )}
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

      {/* تفاصيل الفاتورة */}
      <Dialog open={!!detail} onOpenChange={(o) => !o && setDetail(null)}>
        <DialogContent className="sb-glass-strong sb-scroll max-h-[85vh] max-w-lg overflow-y-auto border-white/10">
          {detail && (
            <>
              <DialogHeader>
                <DialogTitle className="flex items-center gap-2">
                  <Receipt className="h-5 w-5 sb-violet-text" />
                  فاتورة <span className="sb-num">{detail.inv.number}</span>
                </DialogTitle>
                <DialogDescription>
                  {detail.inv.party?.name ?? "عميل نقدي"} · {new Date(detail.inv.date).toLocaleString("ar-SA")}
                </DialogDescription>
              </DialogHeader>

              <div className="space-y-3">
                <div className="sb-scroll overflow-x-auto rounded-xl bg-white/[0.03]">
                  <Table>
                    <TableHeader>
                      <TableRow className="border-white/5">
                        <TableHead className="text-right text-slate-400">البند</TableHead>
                        <TableHead className="text-center text-slate-400">الكمية</TableHead>
                        <TableHead className="text-left text-slate-400">السعر</TableHead>
                        <TableHead className="text-left text-slate-400">الإجمالي</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {detail.inv.items.map((it) => (
                        <TableRow key={it.id} className="border-white/5">
                          <TableCell className="text-xs">{it.desc}</TableCell>
                          <TableCell className="sb-num text-center text-xs">{it.qty}</TableCell>
                          <TableCell className="sb-num text-left text-xs">{fmt(it.unitPrice)}</TableCell>
                          <TableCell className="sb-num text-left text-xs font-bold">{fmt(it.lineTotal)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>

                <div className="space-y-1 rounded-xl bg-white/[0.03] p-3 text-sm">
                  <div className="flex justify-between text-slate-300"><span>قبل الضريبة</span><span className="sb-num">{fmt(detail.inv.subtotal)} ر.س</span></div>
                  <div className="flex justify-between text-slate-300"><span>الضريبة</span><span className="sb-num">{fmt(detail.inv.taxAmount)} ر.س</span></div>
                  <div className="flex justify-between border-t border-white/10 pt-1.5 font-extrabold"><span>الإجمالي</span><span className="sb-num sb-green-text">{fmt(detail.inv.total)} ر.س</span></div>
                  <div className="flex justify-between text-slate-400"><span>المسدد</span><span className="sb-num">{fmt(detail.inv.paid)} ر.س</span></div>
                </div>

                <div className="flex items-start gap-4 rounded-xl bg-white/[0.03] p-3">
                  {detail.qr && (
                    <div className="shrink-0 rounded-lg bg-white p-1.5">
                      <img src={detail.qr} alt="QR ضريبي" className="h-28 w-28" />
                    </div>
                  )}
                  <div className="space-y-1 text-[11px] leading-relaxed text-slate-400">
                    <div>هوية ZATCA: <span className="sb-num sb-cyan-text">{detail.inv.uuid ?? "—"}</span></div>
                    <div>العداد التزايدي ICV: <span className="sb-num">{detail.inv.icv}</span></div>
                    <div>تكلفة البضاعة: <span className="sb-num">{fmt(detail.inv.costTotal)} ر.س</span></div>
                    <div className="pt-1 text-slate-500">الختم تم داخل معاملة الحفظ — مطابق لعقد ZatcaStamp في التطبيق</div>
                  </div>
                </div>
              </div>
            </>
          )}
        </DialogContent>
      </Dialog>

      {/* تأكيد الإلغاء */}
      <AlertDialog open={!!voidTarget} onOpenChange={(o) => !o && setVoidTarget(null)}>
        <AlertDialogContent className="sb-glass-strong border-white/10">
          <AlertDialogHeader>
            <AlertDialogTitle>إلغاء الفاتورة {voidTarget?.number}؟</AlertDialogTitle>
            <AlertDialogDescription>
              سيُكتب قيد تعويضي بصفي VOID ويعود المخزون كما كان — لا حذف ولا تعديل للسجل الأصلي (عقد الإلغاء الآمن).
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel className="border-white/10 bg-white/5">تراجع</AlertDialogCancel>
            <AlertDialogAction
              onClick={(e) => { e.preventDefault(); doVoid(); }}
              disabled={busyVoid}
              className="bg-rose-500/90 hover:bg-rose-500"
            >
              {busyVoid ? <Loader2 className="h-4 w-4 animate-spin" /> : <Ban className="h-4 w-4" />}
              تأكيد الإلغاء
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
