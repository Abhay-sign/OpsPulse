import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { services as servicesApi, teams as teamsApi } from '../api/endpoints';
import type { ApiKey, CreatedApiKey, Service, ServiceStatus, Team } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/ui';
import { ago } from '../lib/time';

const slug = (s: string) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
  .replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');

export function ServicesPage() {
  const { can } = useAuth();
  const isAdmin = can('ADMIN');
  const toast = useToast();
  const [list, setList] = useState<Service[]>([]);
  const [teamList, setTeamList] = useState<Team[]>([]);
  const [open, setOpen] = useState<string | null>(null);

  const load = () => {
    servicesApi.list().then(setList).catch(() => toast('Couldn’t load services', true));
    teamsApi.list().then(setTeamList).catch(() => {});
  };
  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps

  const setStatus = async (s: Service, status: ServiceStatus) => {
    try { await servicesApi.setStatus(s.id, status); load(); }
    catch (e) { toast(e instanceof Error ? e.message : 'Not saved', true); }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Services</h1>
          <p className="sub">Each service gets its own API keys, so a monitor can only raise alerts for the service it watches.</p>
        </div>
      </div>

      {isAdmin && <NewServiceForm teams={teamList} onCreated={load} />}

      <div className="rows" style={{ marginTop: '1rem' }}>
        <table className="grid">
          <thead>
            <tr><th>Service</th><th className="hide-sm">Key</th><th>Team</th><th>Status</th>{isAdmin && <th className="right">API keys</th>}</tr>
          </thead>
          <tbody>
            {list.length === 0 && (
              <tr><td colSpan={5} className="empty">{isAdmin ? 'Add your first service above to start receiving alerts.' : 'No services yet.'}</td></tr>
            )}
            {list.map((s) => (
              <ServiceRow key={s.id} service={s} isAdmin={isAdmin} expanded={open === s.id}
                          onToggle={() => setOpen(open === s.id ? null : s.id)} onStatus={setStatus} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ServiceRow({ service: s, isAdmin, expanded, onToggle, onStatus }: {
  service: Service; isAdmin: boolean; expanded: boolean; onToggle: () => void;
  onStatus: (s: Service, status: ServiceStatus) => void;
}) {
  return (
    <>
      <tr>
        <td><strong>{s.name}</strong></td>
        <td className="hide-sm"><span className="code">{s.serviceKey}</span></td>
        <td>{s.teamName}</td>
        <td>
          {isAdmin ? (
            <select aria-label={`Status of ${s.name}`} value={s.status} onChange={(e) => onStatus(s, e.target.value as ServiceStatus)}>
              <option value="OPERATIONAL">Operational</option>
              <option value="DEGRADED">Degraded</option>
              <option value="MAINTENANCE">Maintenance</option>
            </select>
          ) : s.status.toLowerCase()}
        </td>
        {isAdmin && (
          <td className="right">
            <button className="btn btn-sm" aria-expanded={expanded} onClick={onToggle}>{expanded ? 'Hide keys' : 'Manage keys'}</button>
          </td>
        )}
      </tr>
      {expanded && (
        <tr><td colSpan={5} style={{ background: 'var(--sunken)' }}><KeyManager service={s} /></td></tr>
      )}
    </>
  );
}

function KeyManager({ service }: { service: Service }) {
  const toast = useToast();
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [name, setName] = useState('');
  const [created, setCreated] = useState<CreatedApiKey | null>(null);

  const load = () => servicesApi.keys(service.id).then(setKeys).catch(() => toast('Couldn’t load keys', true));
  useEffect(() => { load(); }, [service.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const create = async (e: FormEvent) => {
    e.preventDefault();
    try {
      setCreated(await servicesApi.createKey(service.id, name.trim()));
      setName('');
      load();
    } catch (err) { toast(err instanceof Error ? err.message : 'Key not created', true); }
  };

  const revoke = async (k: ApiKey) => {
    if (!window.confirm(`Revoke “${k.name}”? Monitors using it will start getting 401s immediately.`)) return;
    await servicesApi.revokeKey(service.id, k.id).catch(() => toast('Not revoked', true));
    load();
  };

  const curl = created && `curl -X POST ${window.location.origin}/api/v1/alerts \\
  -H "X-API-Key: ${created.plaintextKey}" -H "Content-Type: application/json" \\
  -d '{"fingerprint":"test:${service.serviceKey}","severity":"SEV3","summary":"Test alert from ${service.name}"}'`;

  return (
    <div style={{ padding: '0.5rem 0' }}>
      {created && (
        <div className="secret" role="alert">
          <p><strong>Copy this key now.</strong> Only a hash is stored, so it can’t be shown again.</p>
          <span className="code">{created.plaintextKey}</span>{' '}
          <button className="btn btn-sm" onClick={() => navigator.clipboard?.writeText(created.plaintextKey).then(() => toast('Copied'))}>Copy key</button>
          <p style={{ marginTop: '0.75rem' }}>Send a test alert with it:</p>
          <pre>{curl}</pre>
        </div>
      )}
      <form className="form-row" onSubmit={create} style={{ marginBottom: '0.75rem' }}>
        <label className="field">New key name
          <input type="text" value={name} placeholder="prometheus-prod" maxLength={128} onChange={(e) => setName(e.target.value)} />
        </label>
        <button className="btn" disabled={!name.trim()}>Create key</button>
      </form>
      {keys.length === 0 ? <p className="muted">No keys yet. Monitors need one to send alerts.</p> : (
        <table className="grid">
          <thead><tr><th>Name</th><th>Key starts with</th><th className="hide-sm">Last used</th><th className="right" /></tr></thead>
          <tbody>
            {keys.map((k) => (
              <tr key={k.id} style={k.active ? undefined : { opacity: 0.55 }}>
                <td>{k.name}</td>
                <td><span className="code">{k.keyPrefix}…</span></td>
                <td className="hide-sm">{k.revokedAt ? `Revoked ${ago(k.revokedAt)}` : k.lastUsedAt ? ago(k.lastUsedAt) : 'Never'}</td>
                <td className="right">{k.active && <button className="btn btn-sm btn-danger" onClick={() => revoke(k)}>Revoke</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function NewServiceForm({ teams, onCreated }: { teams: Team[]; onCreated: () => void }) {
  const toast = useToast();
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [keyTouched, setKeyTouched] = useState(false);
  const [teamId, setTeamId] = useState('');
  const [newTeam, setNewTeam] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    try {
      let team = teamId;
      if (team === '__new') team = (await teamsApi.create(newTeam.trim())).id;
      await servicesApi.create({ name: name.trim(), serviceKey: key, teamId: team });
      toast(`${name.trim()} added`);
      setName(''); setKey(''); setKeyTouched(false); setTeamId(''); setNewTeam('');
      onCreated();
    } catch (err) {
      if (err instanceof ApiError && err.problem.errors) setErrors(err.problem.errors);
      else toast(err instanceof Error ? err.message : 'Service not created', true);
    }
  };

  const valid = name.trim() && key && (teamId && (teamId !== '__new' || newTeam.trim()));
  return (
    <form className="panel" onSubmit={submit}>
      <h2>Add a service</h2>
      <div className="form-row">
        <label className="field">Name
          <input type="text" value={name} placeholder="Checkout API" onChange={(e) => {
            setName(e.target.value);
            if (!keyTouched) setKey(slug(e.target.value));
          }} />
        </label>
        <label className="field">Key (lowercase, used in alerts)
          <input type="text" value={key} onChange={(e) => { setKeyTouched(true); setKey(e.target.value); }} />
          {errors.serviceKey && <span className="field-error">{errors.serviceKey}</span>}
        </label>
        <label className="field">Owning team
          <select value={teamId} onChange={(e) => setTeamId(e.target.value)}>
            <option value="">Choose a team</option>
            {teams.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            <option value="__new">New team…</option>
          </select>
        </label>
        {teamId === '__new' && (
          <label className="field">New team name
            <input type="text" value={newTeam} onChange={(e) => setNewTeam(e.target.value)} />
          </label>
        )}
        <button className="btn btn-primary" disabled={!valid}>Add service</button>
      </div>
    </form>
  );
}
