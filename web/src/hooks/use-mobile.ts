import * as React from "react"

/**
 * [فحص W1 / N11] الملف كان مستورداً في `sidebar.tsx` ومفقوداً من المستودع —
 * خطأ typecheck مقنّع بـignoreBuildErrors. التنفيذ بـuseSyncExternalStore:
 * بلا setState داخل تأثير (متوافق مع قاعدة react-hooks/set-state-in-effect).
 */
const MOBILE_BREAKPOINT = 768

function subscribe(callback: () => void) {
  const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
  mql.addEventListener("change", callback)
  return () => mql.removeEventListener("change", callback)
}

export function useIsMobile() {
  return React.useSyncExternalStore(
    subscribe,
    () => window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`).matches,
    () => false
  )
}
