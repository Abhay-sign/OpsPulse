import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { hasStoredSession, onSignedOut, refreshSession } from '../api/client';
import { auth } from '../api/endpoints';
import type { Role, User } from '../api/types';

interface AuthState {
  user: User | null;
  ready: boolean;                       // false while restoring a session on page load
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  can: (...roles: Role[]) => boolean;   // UI hint only; the server enforces every rule
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(!hasStoredSession());

  // On load: a stored refresh token buys a fresh access token without a login screen.
  useEffect(() => {
    if (!hasStoredSession()) return;
    refreshSession().then((t) => {
      setUser(t?.user ?? null);
      setReady(true);
    });
  }, []);

  // Any request whose refresh failed (token revoked, user deactivated) lands here.
  useEffect(() => onSignedOut(() => setUser(null)), []);

  const login = useCallback(async (email: string, password: string) => {
    const t = await auth.login(email, password);
    setUser(t.user);
  }, []);

  const logout = useCallback(async () => {
    let token: string | null = null;
    try { token = localStorage.getItem('opspulse.refreshToken'); } catch { /* ignore */ }
    await auth.logout(token);
    setUser(null);
  }, []);

  const can = useCallback((...roles: Role[]) => !!user && roles.includes(user.role), [user]);

  return <AuthContext.Provider value={{ user, ready, login, logout, can }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const a = useContext(AuthContext);
  if (!a) throw new Error('useAuth outside AuthProvider');
  return a;
}
