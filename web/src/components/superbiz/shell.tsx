"use client";

/**
 * هيكل بوابة SuperBiz — وحدة واحدة بتنقل داخلي (RTL · ثيم Glass الماركة)
 * المسار W1 من خارطة الطريق الذكية: docs/ROADMAP_SMART_2026.md §2
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  LayoutDashboard, ShoppingCart, FileText, Package, HandCoins,
  Receipt, BarChart3, Map, ShieldCheck, Github, RefreshCw, Store,
  Satellite, HeartPulse,
} from "lucide-react";
import { Toaster } from "@/components/ui/sonner";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import DashboardTab from "@/components/superbiz/dashboard-tab";
import PosTab from "@/components/superbiz/pos-tab";
import InvoicesTab from "@/components/superbiz/invoices-tab";
import InventoryTab from "@/components/superbiz/inventory-tab";
import DebtsTab from "@/components/superbiz/debts-tab";
import ExpensesTab from "@/components/superbiz/expenses-tab";
import ReportsTab from "@/components/superbiz/reports-tab";
import RoadmapTab from "@/components/superbiz/roadmap-tab";
import InspectionTab from "@/components/superbiz/inspection-tab";

export const TABS = [
  { id: "dashboard", label: "لوحة القيادة", icon: LayoutDashboard },
  { id: "pos", label: "نقطة البيع", icon: ShoppingCart },
  { id: "invoices", label: "الفواتير", icon: FileText },
  { id: "inventory", label: "المخزون", icon: Package },
  { id: "debts", label: "الذمم", icon: HandCoins },
  { id: "expenses", label: "المصروفات", icon: Receipt },
  { id: "reports", label: "التقارير", icon: BarChart3 },
  { id: "roadmap", label: "خارطة الطريق", icon: Map },
  { id: "inspection", label: "تقرير الفحص", icon: ShieldCheck },
] as const;

export type TabId = (typeof TABS)[number]["id"];

export default function SuperBizApp() {
  const [tab, setTab] = useState<TabId>("dashboard");
  const [refreshKey, setRefreshKey] = useState(0);
  const [pulse, setPulse] = useState<number | null>(null);

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);

  // نبض العمل يظهر في الترويسة — من نفس API اللوحة
  useEffect(() => {
    if (tab !== "dashboard") return;
    fetch("/api/dashboard")
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => d?.pulse != null && setPulse(d.pulse))
      .catch(() => {});
  }, [tab, refreshKey]);

  const body = useMemo(() => {
    switch (tab) {
      case "dashboard": return <DashboardTab refreshKey={refreshKey} />;
      case "pos": return <PosTab refreshKey={refreshKey} onChanged={refresh} />;
      case "invoices": return <InvoicesTab refreshKey={refreshKey} onChanged={refresh} />;
      case "inventory": return <InventoryTab refreshKey={refreshKey} onChanged={refresh} />;
      case "debts": return <DebtsTab refreshKey={refreshKey} onChanged={refresh} />;
      case "expenses": return <ExpensesTab refreshKey={refreshKey} onChanged={refresh} />;
      case "reports": return <ReportsTab refreshKey={refreshKey} />;
      case "roadmap": return <RoadmapTab />;
      case "inspection": return <InspectionTab />;
    }
  }, [tab, refreshKey, refresh]);

  return (
    <div className="sb-root flex min-h-screen flex-col" dir="rtl">
      {/* الترويسة */}
      <header className="sticky top-0 z-40 border-b border-white/10 bg-[#0B1026]/80 backdrop-blur-xl">
        <div className="mx-auto flex w-full max-w-7xl items-center gap-3 px-4 py-3">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-[#8B5CF6] to-[#22D3EE] shadow-lg shadow-violet-500/20">
              <Store className="h-5 w-5 text-white" />
            </div>
            <div className="leading-tight">
              <div className="text-base font-extrabold tracking-tight">
                Super<span className="sb-violet-text">Biz</span>
              </div>
              <div className="text-[10px] text-slate-400">بوابة الويب · V 3.5.2</div>
            </div>
          </div>

          <nav aria-label="التنقل الرئيسي" className="mx-auto hidden flex-wrap items-center justify-center gap-1 lg:flex">
            {TABS.map((t) => {
              const Icon = t.icon;
              const active = tab === t.id;
              return (
                <button
                  key={t.id}
                  onClick={() => setTab(t.id)}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "flex items-center gap-1.5 rounded-lg px-3 py-2 text-[13px] font-semibold transition-colors",
                    active
                      ? "bg-violet-500/20 text-violet-200 ring-1 ring-violet-400/40"
                      : "text-slate-400 hover:bg-white/5 hover:text-slate-100"
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {t.label}
                </button>
              );
            })}
          </nav>

          <div className="mr-auto flex items-center gap-2 lg:mr-0">
            {pulse !== null && (
              <span className="sb-chip hidden border-emerald-400/30 bg-emerald-400/10 text-emerald-300 sm:inline-flex">
                <HeartPulse className="h-3.5 w-3.5" />
                النبض <span className="sb-num">{pulse}</span>/100
              </span>
            )}
            <span className="sb-chip hidden border-cyan-400/30 bg-cyan-400/10 text-cyan-300 md:inline-flex">
              <Satellite className="h-3.5 w-3.5" />
              دون اتصال أولًا
            </span>
            <a href="https://github.com/CTO-DRS/SuperBiz" target="_blank" rel="noreferrer" aria-label="مستودع GitHub">
              <Button size="sm" variant="outline" className="gap-1.5 border-white/15 bg-white/5 hover:bg-white/10">
                <Github className="h-4 w-4" />
                <span className="hidden sm:inline">المستودع</span>
              </Button>
            </a>
          </div>
        </div>
        {/* تنقل الجوال */}
        <nav aria-label="تنقل الجوال" className="sb-scroll flex gap-1 overflow-x-auto border-t border-white/5 px-3 pb-2 pt-1 lg:hidden">
          {TABS.map((t) => {
            const Icon = t.icon;
            const active = tab === t.id;
            return (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={cn(
                  "flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold transition-colors",
                  active ? "bg-violet-500/20 text-violet-200" : "text-slate-400"
                )}
              >
                <Icon className="h-3.5 w-3.5" />
                {t.label}
              </button>
            );
          })}
        </nav>
      </header>

      {/* المحتوى */}
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-5">{body}</main>

      {/* تذييل ثابت أسفل الشاشة */}
      <footer className="mt-auto border-t border-white/10 bg-[#0B1026]/70 backdrop-blur">
        <div className="mx-auto flex w-full max-w-7xl flex-col items-center justify-between gap-2 px-4 py-4 text-xs text-slate-500 sm:flex-row">
          <div className="flex items-center gap-1.5">
            <Store className="h-3.5 w-3.5 sb-violet-text" />
            SuperBiz © 2026 CTO-DRS — كل الحقوق محفوظة · بوابة الويب W1 (v0.1)
          </div>
          <div className="flex items-center gap-3">
            <span>المال Long قروش · قيد مزدوج متوازن · ZATCA مرحلة-1</span>
            <button
              onClick={() => { refresh(); window.scrollTo({ top: 0, behavior: "smooth" }); }}
              className="flex items-center gap-1 text-slate-400 transition-colors hover:text-cyan-300"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              تحديث
            </button>
          </div>
        </div>
      </footer>

      <Toaster position="bottom-center" richColors theme="dark" />
    </div>
  );
}
