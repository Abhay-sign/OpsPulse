import { useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { PulseMark } from '../components/ui';

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email, password);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401
        ? 'That email and password don’t match an active account.'
        : 'Can’t reach the server. Check that the backend is running.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <section className="login-art">
        <div className="brand" style={{ color: 'var(--paper)' }}><PulseMark /> OpsPulse</div>
        <div>
          <h1>Know what’s broken before your customers do.</h1>
          <p>Alerts from every monitor fold into one incident per real problem, with an owner and a timeline.</p>
        </div>
        <svg className="trace" viewBox="0 0 600 120" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0 70 H180 L200 70 L215 30 L235 105 L255 50 L268 70 H600" fill="none"
                stroke="#E8505B" strokeWidth="3" strokeLinejoin="round" />
        </svg>
      </section>
      <section className="login-form">
        <form onSubmit={submit} noValidate>
          <h2 style={{ fontSize: 'var(--step-2)', margin: 0 }}>Sign in</h2>
          <label className="field">Email
            <input type="email" autoComplete="username" required value={email}
                   onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className="field">Password
            <input type="password" autoComplete="current-password" required value={password}
                   onChange={(e) => setPassword(e.target.value)} />
          </label>
          {error && <div className="field-error" role="alert">{error}</div>}
          <button className="btn btn-primary" disabled={busy || !email || !password}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </section>
    </div>
  );
}
