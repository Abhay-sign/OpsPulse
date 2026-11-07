import type { Problem, TokenResponse } from './types';

/**
 * Thin fetch wrapper that owns the tokens.
 *
 * Where tokens live:
 *  - access token: in memory only (this module). Gone on reload, which is fine.
 *  - refresh token: localStorage, so a reload or new tab stays signed in.
 *    Trade-off: any XSS on this origin could read it. The stricter design is
 *    an httpOnly, SameSite=Strict cookie set by the server, which JS can never
 *    read -- at the cost of CSRF handling. Rotation (phase 2) limits the
 *    damage either way: a stolen token is single-use.
 *
 * Single-flight refresh: when the access token expires, the dashboard may
 * have several requests in flight (list poll, stats poll, detail poll). All of
 * them get 401 at once. If each refreshed on its own, the FIRST refresh would
 * rotate the token and the others would present an already-used one -- which
 * the server treats as theft and rejects, logging the user out. So every 401
 * awaits the same in-flight refresh promise, and each request retries once.
 */

const REFRESH_KEY = 'opspulse.refreshToken';

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem: Problem) {
    super(problem.detail || problem.title || `Request failed (${status})`);
  }
}

type Listener = () => void;

let accessToken: string | null = null;
let refreshing: Promise<TokenResponse | null> | null = null;
const signedOutListeners = new Set<Listener>();

// injectable for tests
let fetchImpl: typeof fetch = (...args) => fetch(...args);
let storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null =
  typeof localStorage !== 'undefined' ? localStorage : null;

export function _setTestDoubles(f: typeof fetch, s: typeof storage) {
  fetchImpl = f;
  storage = s;
  accessToken = null;
  refreshing = null;
}

function safeGet(): string | null {
  try { return storage?.getItem(REFRESH_KEY) ?? null; } catch { return null; }
}
function safeSet(v: string | null) {
  try { v === null ? storage?.removeItem(REFRESH_KEY) : storage?.setItem(REFRESH_KEY, v); } catch { /* private mode */ }
}

export function hasStoredSession(): boolean {
  return safeGet() !== null;
}

export function onSignedOut(fn: Listener): () => void {
  signedOutListeners.add(fn);
  return () => signedOutListeners.delete(fn);
}

export function setSession(t: TokenResponse) {
  accessToken = t.accessToken;
  safeSet(t.refreshToken);
}

export function clearSession() {
  accessToken = null;
  safeSet(null);
  signedOutListeners.forEach((l) => l());
}

/** One refresh at a time; concurrent callers share the same promise. */
export function refreshSession(): Promise<TokenResponse | null> {
  if (!refreshing) {
    const token = safeGet();
    refreshing = (async () => {
      if (!token) return null;
      try {
        const res = await fetchImpl('/api/v1/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: token }),
        });
        if (!res.ok) return null;
        const t = (await res.json()) as TokenResponse;
        setSession(t);
        return t;
      } catch {
        return null;
      }
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  data: T;
  etag: string | null;
}

export async function apiRaw<T>(path: string, opts: RequestOptions = {}, isRetry = false): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;

  const res = await fetchImpl(path, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    signal: opts.signal,
  });

  if (res.status === 401 && !isRetry && !path.startsWith('/api/v1/auth/')) {
    const refreshed = await refreshSession();
    if (refreshed) return apiRaw<T>(path, opts, true);
    clearSession();
  }

  if (!res.ok) {
    let problem: Problem = { status: res.status };
    try { problem = { status: res.status, ...(await res.json()) }; } catch { /* empty body */ }
    throw new ApiError(res.status, problem);
  }

  const text = res.status === 204 ? '' : await res.text();
  return { data: (text ? JSON.parse(text) : undefined) as T, etag: res.headers.get('ETag') };
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return (await apiRaw<T>(path, opts)).data;
}
