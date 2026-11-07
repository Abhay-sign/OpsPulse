import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { incidents, users as usersApi } from '../api/endpoints';
import type { Severity, TimelineEntry, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { SevBadge, StatusLabel, Updated, useToast } from '../components/ui';
import { Link } from '../lib/router';
import { age, ago, clock } from '../lib/time';
import { groupAlerts } from '../lib/groupAlerts';
import { usePolling } from '../lib/usePolling';

const POLL_MS = 4000;

export function IncidentDetailPage({ id }: { id: string }) {
  const { can } = useAuth();
  const toast = useToast();
  const canAct = can('ADMIN', 'RESPONDER');

  const detail = usePolling((s) => incidents.get(id, s), POLL_MS, [id]);
  const timeline = usePolling((s) => incidents.timeline(id, s), POLL_MS, [id]);
  const alerts = usePolling((s) => incidents.alerts(id, s), POLL_MS * 2, [id]);

  const [people, setPeople] = useState<User[]>([]);
  useEffect(() => {
    if (canAct) usersApi.list().then((u) => setPeople(u.filter((x) => x.active && x.role !== 'VIEWER'))).catch(() => {});
  }, [canAct]);

  const [busy, setBusy] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [resolveNote, setResolveNote] = useState('');

  const refreshAll = () => { detail.refresh(); timeline.refresh(); };

  /**
   * Every command carries If-Match with the version on screen. 412 means
   * someone changed the incident since we last loaded it: we don't retry
   * blindly, we reload and let the human look again.
   */
  const run = async (label: string, action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      toast(label);
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        toast('Someone else just changed this incident. It has been reloaded, check it and try again.', true);
      } else if (e instanceof ApiError) {
        toast(e.message, true);
      } else {
        toast('Can’t reach the server. Your change was not saved.', true);
      }
    } finally {
      setBusy(false);
      refreshAll();
    }
  };

  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return (
      <div className="page">
        <Link to="/incidents" className="back">Back to incidents</Link>
        <div className="panel empty"><p>This incident doesn’t exist, or the link is wrong.</p></div>
      </div>
    );
  }

  const i = detail.data;
  if (!i) return <div className="page"><p className="muted">Loading incident…</p></div>;
  const open = i.status !== 'RESOLVED';

  return (
    <div className="page">
      <Link to="/incidents" className="back">Back to incidents</Link>

      <header className="detail-head">
        <span className={`edge bg-${i.severity}`} />
        <div>
          <div className="detail-meta">
            <StatusLabel status={i.status} />
            <SevBadge severity={i.severity} />
            <span>{i.serviceName}</span>
            <span>Open for {age(i.createdAt, i.resolvedAt)}</span>
            <Updated at={detail.lastUpdated} error={detail.error} />
          </div>
          <h1 className="detail-title">{i.title}</h1>
          {i.description && <p className="muted" style={{ margin: 0, maxWidth: '70ch' }}>{i.description}</p>}

          {canAct && open && (
            <div className="actions">
              {i.status === 'TRIGGERED' && (
                <button className="btn btn-ack" disabled={busy}
                        onClick={() => run('Acknowledged', () => incidents.acknowledge(i))}>Acknowledge</button>
              )}
              {!resolving && (
                <button className="btn btn-resolve" disabled={busy} onClick={() => setResolving(true)}>Resolve</button>
              )}
              <select aria-label="Owner" disabled={busy} value={i.assignedUserId ?? ''}
                      onChange={(e) => e.target.value && run('Owner changed', () => incidents.reassign(i, e.target.value))}>
                {!i.assignedUserId && <option value="">Assign to…</option>}
                {people.map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
              </select>
              <select aria-label="Severity" disabled={busy} value={i.severity}
                      onChange={(e) => run('Severity changed', () => incidents.severity(i, e.target.value as Severity))}>
                {(['SEV1', 'SEV2', 'SEV3', 'SEV4'] as Severity[]).map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
          )}
          {resolving && open && (
            <ResolveForm busy={busy} note={resolveNote} setNote={setResolveNote}
                         onCancel={() => setResolving(false)}
                         onResolve={() => run('Resolved', async () => {
                           await incidents.resolve(i, resolveNote.trim());
                           setResolving(false);
                           setResolveNote('');
                         })} />
          )}
        </div>
      </header>

      <div className="detail-grid">
        <section className="panel" aria-labelledby="tl">
          <h2 id="tl">Timeline</h2>
          <Timeline entries={timeline.data ?? []} />
          {canAct && <NoteForm incidentId={i.id} onAdded={timeline.refresh} />}
        </section>

        <aside>
          <section className="panel">
            <h2>Details</h2>
            <dl className="facts">
              <dt>Owner</dt><dd>{i.assignedUserName ?? <span className="faint">Unassigned</span>}</dd>
              <dt>Team</dt><dd>{i.teamName}</dd>
              <dt>Opened</dt><dd>{clock(i.createdAt)}</dd>
              {i.acknowledgedAt && <><dt>Acknowledged</dt><dd>{clock(i.acknowledgedAt)}</dd></>}
              {i.resolvedAt && <><dt>Resolved</dt><dd>{clock(i.resolvedAt)}{i.resolvedByName ? ` by ${i.resolvedByName}` : ' automatically'}</dd></>}
              <dt>Alerts</dt><dd>{i.alertCount}</dd>
            </dl>
          </section>
          <section className="panel">
            <h2>Recent alerts</h2>
            <ul className="alerts">
              {groupAlerts(alerts.data?.content ?? []).map((g, n) => (
                <li key={`${g.key}-${n}`}>
                  <span>
                    {g.label}<br />
                    <span className="faint">{g.source}{g.count > 1 && `, fired ${g.count} times`}</span>
                  </span>
                  <span className="faint" title={g.latest}>{ago(g.latest)}</span>
                </li>
              ))}
            </ul>
            {alerts.data && alerts.data.totalElements > alerts.data.content.length && (
              <p className="faint" style={{ margin: '0.5rem 0 0', fontSize: 'var(--step--1)' }}>
                Showing the latest {alerts.data.content.length} of {alerts.data.totalElements}.
              </p>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}

function ResolveForm({ busy, note, setNote, onCancel, onResolve }: {
  busy: boolean; note: string; setNote: (s: string) => void; onCancel: () => void; onResolve: () => void;
}) {
  return (
    <div className="panel" style={{ marginTop: '1rem', maxWidth: 560 }}>
      <label className="field">What fixed it? (optional, goes on the timeline)
        <textarea value={note} maxLength={4000} onChange={(e) => setNote(e.target.value)} autoFocus />
      </label>
      <div className="actions" style={{ marginTop: '0.75rem' }}>
        <button className="btn btn-resolve" disabled={busy} onClick={onResolve}>Resolve incident</button>
        <button className="btn btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

function Timeline({ entries }: { entries: TimelineEntry[] }) {
  if (entries.length === 0) return <p className="muted">No events yet.</p>;
  return (
    <ol className="timeline">
      {entries.map((e) => (
        <li key={e.id} className={e.type === 'NOTE_ADDED' ? 'tl-note' : undefined}>
          <span className={`dot dot-${e.type}`} />
          <span className="tl-who">{e.type === 'NOTE_ADDED' ? `${e.actorName} added a note` : e.actorName === 'system' ? 'OpsPulse' : e.actorName}</span>
          <time className="tl-when" dateTime={e.createdAt} title={clock(e.createdAt)}>{ago(e.createdAt)}</time>
          {e.message && <p className="tl-msg">{e.message}</p>}
        </li>
      ))}
    </ol>
  );
}

function NoteForm({ incidentId, onAdded }: { incidentId: string; onAdded: () => void }) {
  const toast = useToast();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!text.trim()) return;
    setBusy(true);
    try {
      await incidents.addNote(incidentId, text.trim());
      setText('');
      onAdded();
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Note not saved', true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="note-form" onSubmit={submit}>
      <label className="field">Add a note
        <textarea value={text} maxLength={4000} onChange={(e) => setText(e.target.value)}
                  placeholder="What you’ve checked, what you’re trying next" />
      </label>
      <button className="btn" disabled={busy || !text.trim()}>Add note</button>
    </form>
  );
}

