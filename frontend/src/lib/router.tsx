import { createContext, useCallback, useContext, useEffect, useMemo, useState, type MouseEvent, type ReactNode } from 'react';

/**
 * A deliberately tiny History-API router: the app has five routes, which
 * doesn't justify a routing library. It does the three things that matter:
 * pushState navigation without reloads, back/forward via popstate, and
 * :param matching.
 *
 * nginx (and the Vite dev server) serve index.html for unknown paths, so a
 * deep link like /incidents/abc survives a page refresh.
 */

interface RouterState {
  path: string;
  search: URLSearchParams;
  navigate: (to: string, opts?: { replace?: boolean }) => void;
}

const RouterContext = createContext<RouterState | null>(null);

export function RouterProvider({ children }: { children: ReactNode }) {
  const [loc, setLoc] = useState(() => ({ path: window.location.pathname, search: window.location.search }));

  useEffect(() => {
    const onPop = () => setLoc({ path: window.location.pathname, search: window.location.search });
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigate = useCallback((to: string, opts?: { replace?: boolean }) => {
    if (opts?.replace) window.history.replaceState(null, '', to);
    else window.history.pushState(null, '', to);
    setLoc({ path: window.location.pathname, search: window.location.search });
    window.scrollTo({ top: 0 });
  }, []);

  const value = useMemo(
    () => ({ path: loc.path, search: new URLSearchParams(loc.search), navigate }),
    [loc, navigate],
  );
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

export function useRouter(): RouterState {
  const r = useContext(RouterContext);
  if (!r) throw new Error('useRouter outside RouterProvider');
  return r;
}

/** "/incidents/:id" vs "/incidents/42" -> { id: "42" }, or null. */
export function matchPath(pattern: string, path: string): Record<string, string> | null {
  const p = pattern.split('/').filter(Boolean);
  const a = path.split('/').filter(Boolean);
  if (p.length !== a.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < p.length; i++) {
    if (p[i].startsWith(':')) params[p[i].slice(1)] = decodeURIComponent(a[i]);
    else if (p[i] !== a[i]) return null;
  }
  return params;
}

interface LinkProps {
  to: string;
  children: ReactNode;
  className?: string;
  'aria-current'?: 'page' | undefined;
}

/** A real <a href>, so middle-click / cmd-click still open a new tab. */
export function Link({ to, children, className, ...rest }: LinkProps) {
  const { navigate } = useRouter();
  const onClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    navigate(to);
  };
  return <a href={to} onClick={onClick} className={className} {...rest}>{children}</a>;
}
