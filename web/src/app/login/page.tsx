"use client";

/**
 * W1.5 (Issue #39) — صفحة دخول المالك (RTL · ثيم Glass الماركة).
 * كلمة مرور واحدة من البيئة → كوكة جلسة httpOnly 12 ساعة → توجيه للبوابة.
 * بلا حسابات ولا أطراف: قرار ADR-004 — بوابة معاينة لمالك واحد.
 */
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { LockKeyhole, Loader2, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function LoginPage() {
  const router = useRouter();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(true);

  // جلسة قائمة؟ لا داعي للدخول مرتين
  useEffect(() => {
    fetch("/api/auth/session")
      .then((r) => r.json())
      .then((d) => {
        if (d?.authenticated) router.replace("/");
        else setChecking(false);
      })
      .catch(() => setChecking(false));
  }, [router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ password }),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok) {
        router.replace("/");
        return;
      }
      setError(data?.error ?? `تعذر الدخول (${res.status})`);
    } catch {
      setError("تعذر الاتصال بالبوابة");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main
      dir="rtl"
      className="flex min-h-screen items-center justify-center bg-[#0B1026] p-4"
      style={{
        backgroundImage:
          "radial-gradient(1200px 600px at 80% -10%, rgba(139,92,246,0.18), transparent), radial-gradient(900px 500px at 10% 110%, rgba(34,211,238,0.12), transparent)",
      }}
    >
      <form
        onSubmit={submit}
        className="w-full max-w-sm rounded-2xl border border-white/10 bg-white/5 p-6 shadow-2xl backdrop-blur-xl"
      >
        <div className="mb-6 flex flex-col items-center gap-3 text-center">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-[#8B5CF6]/20 ring-1 ring-[#8B5CF6]/40">
            <LockKeyhole className="h-7 w-7 text-[#8B5CF6]" aria-hidden />
          </div>
          <h1 className="text-xl font-bold text-white">بوابة SuperBiz</h1>
          <p className="text-sm text-white/60">
            دخول المالك — محمي بطبقة جلسة W1.5
            <span className="mx-1 inline-flex items-center gap-1 rounded-full bg-[#22D3EE]/10 px-2 py-0.5 text-[11px] text-[#22D3EE] ring-1 ring-[#22D3EE]/30">
              <ShieldCheck className="h-3 w-3" aria-hidden /> Issue #39
            </span>
          </p>
        </div>

        <label htmlFor="owner-password" className="mb-2 block text-sm text-white/80">
          كلمة مرور المالك
        </label>
        <input
          id="owner-password"
          type="password"
          dir="ltr"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          disabled={busy || checking}
          className="mb-1 w-full rounded-xl border border-white/10 bg-[#0B1026]/60 px-4 py-3 text-white outline-none transition focus:border-[#8B5CF6]/60 focus:ring-2 focus:ring-[#8B5CF6]/30 disabled:opacity-50"
          placeholder="••••••••"
          required
        />
        {error && (
          <p role="alert" className="mb-1 mt-2 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-300 ring-1 ring-red-500/30">
            {error}
          </p>
        )}

        <Button
          type="submit"
          disabled={busy || checking || password.length === 0}
          className="mt-4 w-full rounded-xl bg-[#8B5CF6] py-3 font-semibold text-white transition hover:bg-[#7C3AED] disabled:opacity-50"
        >
          {busy ? (
            <span className="inline-flex items-center gap-2">
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> جارٍ التحقق…
            </span>
          ) : (
            "تسجيل الدخول"
          )}
        </Button>

        <p className="mt-4 text-center text-xs text-white/40">
          الجلسة 12 ساعة · 5 محاولات كل 5 دقائق · كل الكتابة خلف بوابة المصادقة
        </p>
      </form>
    </main>
  );
}
