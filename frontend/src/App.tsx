import { useEffect, type ReactNode } from 'react';
import { useAuth } from './auth/AuthContext';
import { PulseMark } from './components/ui';
import { Link, matchPath, useRouter } from './lib/router';
import { IncidentDetailPage } from './pages/IncidentDetailPage';
import { IncidentsPage } from './pages/IncidentsPage';
import { LoginPage } from './pages/LoginPage';
import { ServicesPage } from './pages/ServicesPage';
import { UsersPage } from './pages/UsersPage';

export function App() {
  const { user, ready, logout, can } = useAuth();
  const { path, navigate } = useRouter();
  const atRoot = path === '/' || path === '/login';

  useEffect(() => {
    if (user && atRoot) navigate('/incidents', { replace: true });
  }, [user, atRoot, navigate]);

  if (!ready) return null; // restoring a session: render nothing rather than flash the login form
  if (!user) return <LoginPage />;
  if (atRoot) return null;

  const detail = matchPath('/incidents/:id', path);
  let page: ReactNode;
  if (detail) page = <IncidentDetailPage key={detail.id} id={detail.id} />;
  else if (path === '/incidents') page = <IncidentsPage />;
  else if (path === '/services') page = <ServicesPage />;
  else if (path === '/people' && can('ADMIN')) page = <UsersPage />;
  else page = (
    <div className="page"><div className="panel empty">
      <p>There’s no page at this address.</p>
      <p><Link to="/incidents">Go to incidents</Link></p>
    </div></div>
  );

  const nav = [
    { to: '/incidents', label: 'Incidents', active: path.startsWith('/incidents') },
    { to: '/services', label: 'Services', active: path === '/services' },
    ...(can('ADMIN') ? [{ to: '/people', label: 'People', active: path === '/people' }] : []),
  ];

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/incidents" className="brand"><PulseMark />OpsPulse</Link>
        <nav className="nav" aria-label="Main">
          {nav.map((n) => <Link key={n.to} to={n.to} aria-current={n.active ? 'page' : undefined}>{n.label}</Link>)}
        </nav>
        <div className="whoami">
          <span><strong>{user.fullName}</strong> ({user.role.toLowerCase()})</span>
          <button className="btn btn-sm" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main>{page}</main>
    </div>
  );
}
