import { useEffect, useState } from 'react';
import { incidents, services as servicesApi } from '../api/endpoints';
import type { IncidentStatus, IncidentSummary, Service, Severity } from '../api/types';
import { PressureStrip } from '../components/PressureStrip';
import { SevBadge, StatusLabel, Updated } from '../components/ui';
import { Link, useRouter } from '../lib/router';
import { age } from '../lib/time';
import { usePolling } from '../lib/usePolling';

type View = 'open' | IncidentStatus | 'all';
const VIEWS: { key: View; label: string }[] = [
  { key: 'open', label: 'Open' },
  { key: 'TRIGGERED', label: 'Triggered' },
  { key: 'ACKNOWLEDGED', label: 'Acknowledged' },
  { key: 'RESOLVED', label: 'Resolved' },
  { key: 'all', label: 'All' },
];
const POLL_MS = 5000;
const PAGE_SIZE = 25;

export function IncidentsPage() {
  const { search, navigate } = useRouter();
  // Filters live in the URL, so a filtered view can be bookmarked or pasted into chat.
  const view = (search.get('view') as View) || 'open';
  const severity = (search.get('severity') as Severity) || undefined;
  const serviceId = search.get('service') || undefined;
  const page = Number(search.get('page') || 0);

  const setParam = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(search);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    if (!('page' in changes)) next.delete('page');
    const qs = next.toString();
    navigate(`/incidents${qs ? `?${qs}` : ''}`, { replace: true });
  };

  const list = usePolling(
    (signal) => incidents.list({
      openOnly: view === 'open' || undefined,
      status: view !== 'open' && view !== 'all' ? view : undefined,
      severity, serviceId, page, size: PAGE_SIZE,
    }, signal),
    POLL_MS, [view, severity, serviceId, page],
  );
  const stats = usePolling((signal) => incidents.stats(signal), POLL_MS, []);

  const [serviceList, setServiceList] = useState<Service[]>([]);
  useEffect(() => { servicesApi.list().then(setServiceList).catch(() => {}); }, []);

  // Re-render every 30s so the "age" column keeps counting between polls.
  const [, setNow] = useState(Date.now());
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 30_000); return () => clearInterval(t); }, []);

  const data = list.data;
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Incidents</h1>
          <p className="sub">Every alert with the same fingerprint lands on one incident.</p>
        </div>
        <Updated at={list.lastUpdated} error={list.error} />
      </div>

      <PressureStrip stats={stats.data} selected={severity} onSelect={(s) => setParam({ severity: s })} />

      <div className="toolbar">
        <div className="tabs" role="group" aria-label="Status">
          {VIEWS.map((v) => (
            <button key={v.key} type="button" aria-pressed={view === v.key}
                    onClick={() => setParam({ view: v.key === 'open' ? undefined : v.key })}>{v.label}</button>
          ))}
        </div>
        <select aria-label="Severity" value={severity ?? ''} onChange={(e) => setParam({ severity: e.target.value || undefined })}>
          <option value="">Any severity</option>
          {(['SEV1', 'SEV2', 'SEV3', 'SEV4'] as Severity[]).map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <select aria-label="Service" value={serviceId ?? ''} onChange={(e) => setParam({ service: e.target.value || undefined })}>
          <option value="">All services</option>
          {serviceList.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </div>

      <div className="rows" aria-busy={list.loading}>
        <div className="rows-head" aria-hidden="true">
          <span /><span>Incident</span><span>Owner</span><span className="num">Alerts</span><span className="num">Age</span><span className="num">Status</span>
        </div>
        {data && data.content.length === 0 && !list.loading && <EmptyState view={view} filtered={!!(severity || serviceId)} />}
        {data?.content.map((i) => <IncidentRow key={i.id} incident={i} />)}
        {!data && list.loading && <div className="empty">Loading incidents…</div>}
      </div>

      {data && data.totalPages > 1 && (
        <div className="pager">
          <span>{data.totalElements} incidents, page {data.page + 1} of {data.totalPages}</span>
          <span style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="btn btn-sm" disabled={page === 0} onClick={() => setParam({ page: String(page - 1) })}>Previous</button>
            <button className="btn btn-sm" disabled={page + 1 >= data.totalPages} onClick={() => setParam({ page: String(page + 1) })}>Next</button>
          </span>
        </div>
      )}
    </div>
  );
}

function IncidentRow({ incident: i }: { incident: IncidentSummary }) {
  return (
    <Link to={`/incidents/${i.id}`} className={`row${i.status === 'RESOLVED' ? ' is-resolved' : ''}`}>
      <span className={`edge bg-${i.severity}`} />
      <span style={{ minWidth: 0 }}>
        <span className="row-title" style={{ display: 'block' }}>{i.title}</span>
        <span className="row-sub"><SevBadge severity={i.severity} />{i.serviceName}</span>
      </span>
      <span className="col-owner muted" style={{ fontSize: 'var(--step--1)' }}>{i.assignedUserName ?? <span className="faint">Unassigned</span>}</span>
      <span className="num col-alerts"><b>{i.alertCount}</b></span>
      <span className="num col-age" title={i.createdAt}>{age(i.createdAt, i.resolvedAt)}</span>
      <StatusLabel status={i.status} />
    </Link>
  );
}

function EmptyState({ view, filtered }: { view: View; filtered: boolean }) {
  if (filtered) return <div className="empty"><p>No incidents match these filters.</p></div>;
  if (view === 'open' || view === 'TRIGGERED') {
    return (
      <div className="empty">
        <p><strong>Nothing needs attention.</strong></p>
        <p>New incidents appear here within a few seconds of the first alert.</p>
      </div>
    );
  }
  return <div className="empty"><p>No incidents yet.</p></div>;
}
