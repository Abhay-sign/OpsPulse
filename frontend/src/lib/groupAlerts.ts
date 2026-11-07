import type { Alert } from '../api/types';

export interface AlertGroup {
  key: string;
  label: string;
  source: string;
  count: number;
  latest: string;   // ISO time of the newest alert in the run
  earliest: string;
}

/**
 * Collapses RUNS of identical alerts (newest-first input) into one line:
 * a check that fired 17 times shows as one row "x17", not 17 identical rows.
 * Only consecutive repeats merge, so the order of distinct events survives
 * (fire, fire, clear, fire stays as three groups).
 */
export function groupAlerts(alerts: Alert[]): AlertGroup[] {
  const groups: AlertGroup[] = [];
  for (const a of alerts) {
    const label = a.eventType === 'RESOLVE' ? 'Cleared by source' : a.summary;
    const key = `${a.eventType}|${label}|${a.source}`;
    const last = groups[groups.length - 1];
    if (last && last.key === key) {
      last.count += 1;
      last.earliest = a.receivedAt;
    } else {
      groups.push({ key, label, source: a.source, count: 1, latest: a.receivedAt, earliest: a.receivedAt });
    }
  }
  return groups;
}
