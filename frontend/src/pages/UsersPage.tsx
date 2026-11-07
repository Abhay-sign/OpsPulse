import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { users as usersApi } from '../api/endpoints';
import type { Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/ui';

const ROLE_HELP: Record<Role, string> = {
  ADMIN: 'Admin: manages people, services and keys',
  RESPONDER: 'Responder: acknowledges and resolves incidents',
  VIEWER: 'Viewer: can watch, can’t act',
};

export function UsersPage() {
  const { user: me } = useAuth();
  const toast = useToast();
  const [list, setList] = useState<User[]>([]);
  const load = () => usersApi.list().then(setList).catch(() => toast('Couldn’t load people', true));
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const update = async (u: User, patch: Partial<Pick<User, 'role' | 'active'>>) => {
    try { await usersApi.update(u.id, patch); load(); }
    catch (e) { toast(e instanceof Error ? e.message : 'Not saved', true); load(); }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>People</h1>
          <p className="sub">There’s no public sign-up: admins add everyone here.</p>
        </div>
      </div>
      <NewUserForm onCreated={load} />
      <div className="rows" style={{ marginTop: '1rem' }}>
        <table className="grid">
          <thead><tr><th>Name</th><th className="hide-sm">Email</th><th>Role</th><th className="right">Access</th></tr></thead>
          <tbody>
            {list.map((u) => (
              <tr key={u.id} style={u.active ? undefined : { opacity: 0.55 }}>
                <td><strong>{u.fullName}</strong>{u.id === me?.id && <span className="faint"> (you)</span>}</td>
                <td className="hide-sm">{u.email}</td>
                <td>
                  <select aria-label={`Role of ${u.fullName}`} value={u.role} disabled={!u.active}
                          onChange={(e) => update(u, { role: e.target.value as Role })}>
                    <option value="ADMIN">Admin</option>
                    <option value="RESPONDER">Responder</option>
                    <option value="VIEWER">Viewer</option>
                  </select>
                </td>
                <td className="right">
                  {u.active
                    ? <button className="btn btn-sm btn-danger" onClick={() => update(u, { active: false })}>Deactivate</button>
                    : <button className="btn btn-sm" onClick={() => update(u, { active: true })}>Reactivate</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function NewUserForm({ onCreated }: { onCreated: () => void }) {
  const toast = useToast();
  const [form, setForm] = useState({ fullName: '', email: '', password: '', role: 'RESPONDER' as Role });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value } as typeof form);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    try {
      await usersApi.create(form);
      toast(`${form.fullName} added`);
      setForm({ fullName: '', email: '', password: '', role: 'RESPONDER' });
      onCreated();
    } catch (err) {
      if (err instanceof ApiError && err.problem.errors) setErrors(err.problem.errors);
      else toast(err instanceof Error ? err.message : 'Not added', true);
    }
  };

  return (
    <form className="panel" onSubmit={submit}>
      <h2>Add a person</h2>
      <div className="form-row">
        <label className="field">Full name<input type="text" value={form.fullName} onChange={set('fullName')} />
          {errors.fullName && <span className="field-error">{errors.fullName}</span>}</label>
        <label className="field">Email<input type="email" value={form.email} onChange={set('email')} />
          {errors.email && <span className="field-error">{errors.email}</span>}</label>
        <label className="field">Temporary password<input type="password" value={form.password} onChange={set('password')} autoComplete="new-password" />
          {errors.password && <span className="field-error">At least 8 characters</span>}</label>
        <label className="field">Role
          <select value={form.role} onChange={set('role')}>
            {(Object.keys(ROLE_HELP) as Role[]).map((r) => <option key={r} value={r}>{ROLE_HELP[r]}</option>)}
          </select>
        </label>
        <button className="btn btn-primary" disabled={!form.fullName || !form.email || !form.password}>Add person</button>
      </div>
    </form>
  );
}
