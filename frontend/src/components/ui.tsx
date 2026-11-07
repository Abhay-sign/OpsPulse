import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import type { IncidentStatus, Severity } from '../api/types';

export function SevBadge({ severity }: { severity: Severity }) {
  return <span className={`sev sev-${severity}`}>{severity}</span>;
}

const STATUS_LABEL: Record<IncidentStatus, string> = {
  TRIGGERED: 'Triggered',
  ACKNOWLEDGED: 'Acknowledged',
  RESOLVED: 'Resolved',
};

export function StatusLabel({ status }: { status: IncidentStatus }) {
  return <span className={`status status-${status}`}>{STATUS_LABEL[status]}</span>;
}

export function PulseMark() {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="7" fill="currentColor" />
      <path d="M4 17h6l3-8 5 15 3-7h7" fill="none" stroke="#E8505B" strokeWidth="2.6"
            strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/* ---- toasts ------------------------------------------------------------- */

interface Toast { id: number; text: string; error?: boolean }
const ToastContext = createContext<(text: string, error?: boolean) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((text: string, error = false) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, text, error }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), error ? 7000 : 3500);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => <div key={t.id} className={`toast${t.error ? ' error' : ''}`}>{t.text}</div>)}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

export function Updated({ at, error }: { at: Date | null; error: Error | null }) {
  if (error) {
    return <span className="updated stale"><span className="live-dot" />Can't reach the server. Retrying…</span>;
  }
  return (
    <span className="updated">
      <span className="live-dot" />
      {at ? `Updated ${at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}` : 'Loading…'}
    </span>
  );
}
