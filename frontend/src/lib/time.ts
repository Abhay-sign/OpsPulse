/** "just now", "4m", "3h 12m", "2d 5h" -- compact, because it sits in a table column. */
export function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return m % 60 ? `${h}h ${m % 60}m` : `${h}h`;
  const d = Math.floor(h / 24);
  return h % 24 ? `${d}d ${h % 24}h` : `${d}d`;
}

export function ago(iso: string, now: number = Date.now()): string {
  const secs = (now - new Date(iso).getTime()) / 1000;
  return secs < 30 ? 'just now' : `${formatDuration(secs)} ago`;
}

export function age(iso: string, until: string | null, now: number = Date.now()): string {
  const end = until ? new Date(until).getTime() : now;
  return formatDuration((end - new Date(iso).getTime()) / 1000);
}

export function clock(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}
