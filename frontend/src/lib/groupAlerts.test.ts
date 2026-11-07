import { describe, expect, it } from 'vitest';
import type { Alert } from '../api/types';
import { groupAlerts } from './groupAlerts';

const a = (summary: string, at: string, eventType: Alert['eventType'] = 'TRIGGER'): Alert => ({
  id: at, fingerprint: 'f', severity: 'SEV2', summary, source: 'prometheus', eventType, payload: {}, receivedAt: at,
});

describe('groupAlerts', () => {
  it('collapses consecutive repeats and keeps the time range', () => {
    const g = groupAlerts([a('CPU high', 't3'), a('CPU high', 't2'), a('CPU high', 't1')]);
    expect(g).toHaveLength(1);
    expect(g[0]).toMatchObject({ count: 3, latest: 't3', earliest: 't1' });
  });

  it('keeps distinct events in order (fire, clear, fire = 3 groups)', () => {
    const g = groupAlerts([a('CPU high', 't3'), a('x', 't2', 'RESOLVE'), a('CPU high', 't1')]);
    expect(g.map((x) => x.label)).toEqual(['CPU high', 'Cleared by source', 'CPU high']);
  });
});
