import { beforeEach, describe, expect, it } from 'vitest';
import { _setTestDoubles, api, ApiError, onSignedOut, setSession } from './client';

/**
 * The bug this guards against: three polls hit 401 together, each refreshes
 * on its own, the first refresh rotates the token, and the other two present
 * a used token -> the server sees "reuse" and the user is logged out.
 */
function memoryStorage() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
  };
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const tokens = (n: number) => ({
  accessToken: `access-${n}`, refreshToken: `refresh-${n}`, tokenType: 'Bearer', expiresIn: 900,
  user: { id: 'u', email: 'e', fullName: 'F', role: 'RESPONDER', timezone: 'UTC', active: true, createdAt: '' },
});

describe('api client', () => {
  let refreshCalls: string[];
  let current: string;

  beforeEach(() => {
    refreshCalls = [];
    current = 'access-1';
    const storage = memoryStorage();
    _setTestDoubles((async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/auth/refresh') {
        const presented = JSON.parse(String(init?.body)).refreshToken;
        refreshCalls.push(presented);
        await new Promise((r) => setTimeout(r, 20)); // slow enough for the others to pile up
        if (presented !== 'refresh-1') return json(401, { detail: 'reused' });
        current = 'access-2';
        return json(200, tokens(2));
      }
      const auth = (init?.headers as Record<string, string>)?.Authorization;
      return auth === `Bearer ${current}` ? json(200, { ok: url }) : json(401, { detail: 'expired' });
    }) as typeof fetch, storage);
    setSession(tokens(1) as never);
    current = 'access-NEW-SERVER-KEY'; // simulate expiry: the old access token no longer works
  });

  it('refreshes ONCE for many simultaneous 401s, then retries each request', async () => {
    const results = await Promise.all([api('/a'), api('/b'), api('/c')]);
    expect(refreshCalls).toEqual(['refresh-1']);
    expect(results).toEqual([{ ok: '/a' }, { ok: '/b' }, { ok: '/c' }]);
  });

  it('signs out when the refresh itself is rejected', async () => {
    let signedOut = false;
    const off = onSignedOut(() => { signedOut = true; });
    setSession({ ...tokens(1), refreshToken: 'revoked' } as never);
    await expect(api('/a')).rejects.toBeInstanceOf(ApiError);
    expect(signedOut).toBe(true);
    off();
  });

  it('surfaces the RFC 7807 detail as the error message', async () => {
    _setTestDoubles((async () => json(409, { status: 409, detail: 'Cannot acknowledge an incident that is already RESOLVED' })) as typeof fetch, memoryStorage());
    await expect(api('/x')).rejects.toThrow('Cannot acknowledge an incident that is already RESOLVED');
  });
});
