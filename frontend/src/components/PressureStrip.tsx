import { SEVERITIES, type IncidentStats, type Severity } from '../api/types';
import { formatDuration } from '../lib/time';

/**
 * The dashboard's headline: how much is on fire, and how bad. One bar split by
 * open incidents per severity, so a SEV1 is visible from across the room.
 * Clicking a severity filters the list below.
 */
export function PressureStrip({ stats, selected, onSelect }: {
  stats: IncidentStats | undefined;
  selected: Severity | undefined;
  onSelect: (s: Severity | undefined) => void;
}) {
  if (!stats) return <div className="pressure" style={{ minHeight: 132 }} aria-busy="true" />;
  const open = stats.open;
  return (
    <section className="pressure" aria-label="Open incidents by severity">
      <div className="pressure-top">
        <div className="pressure-count">
          {open}<span>{open === 1 ? 'open incident' : 'open incidents'}</span>
        </div>
        <div className="pressure-meta">
          <div><b>{stats.triggered}</b>waiting for someone</div>
          <div><b>{stats.resolvedLast24h}</b>resolved today</div>
          <div title="Mean time to acknowledge, last 7 days">
            <b>{stats.mttaSeconds7d == null ? '–' : formatDuration(stats.mttaSeconds7d)}</b>to acknowledge
          </div>
          <div title="Mean time to resolve, last 7 days">
            <b>{stats.mttrSeconds7d == null ? '–' : formatDuration(stats.mttrSeconds7d)}</b>to resolve
          </div>
        </div>
      </div>
      {open === 0 ? (
        <p className="all-clear">Nothing open. All services are quiet.</p>
      ) : (
        <>
          <div className="strip" role="img"
               aria-label={SEVERITIES.map((s) => `${stats.openBySeverity[s]} ${s}`).join(', ')}>
            {SEVERITIES.filter((s) => stats.openBySeverity[s] > 0).map((s) => (
              <div key={s} className={`bg-${s}`} style={{ flexGrow: stats.openBySeverity[s] }} />
            ))}
          </div>
          <div className="strip-legend">
            {SEVERITIES.map((s) => (
              <button key={s} type="button" aria-pressed={selected === s}
                      onClick={() => onSelect(selected === s ? undefined : s)}>
                <span className={`swatch bg-${s}`} />{stats.openBySeverity[s]} {s}
              </button>
            ))}
          </div>
        </>
      )}
    </section>
  );
}
