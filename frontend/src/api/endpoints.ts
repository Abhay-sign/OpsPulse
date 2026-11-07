import { api, apiRaw, clearSession, setSession } from './client';
import type {
  Alert, ApiKey, CreatedApiKey, IncidentDetail, IncidentStats, IncidentStatus, IncidentSummary,
  Page, Role, Service, ServiceStatus, Severity, Team, TimelineEntry, TokenResponse, User,
} from './types';

const json = (body: unknown) => ({ method: 'POST' as const, body });

export const auth = {
  async login(email: string, password: string) {
    const t = await api<TokenResponse>('/api/v1/auth/login', json({ email, password }));
    setSession(t);
    return t;
  },
  async logout(refreshToken: string | null) {
    try {
      if (refreshToken) await api('/api/v1/auth/logout', json({ refreshToken }));
    } finally {
      clearSession();
    }
  },
};

export interface IncidentFilter {
  status?: IncidentStatus;
  severity?: Severity;
  serviceId?: string;
  assigneeId?: string;
  openOnly?: boolean;
  page?: number;
  size?: number;
}

function query(params: object): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}

/** Every command sends If-Match so nobody acts on a version of the incident they no longer see. */
const ifMatch = (version: number) => ({ 'If-Match': `"${version}"` });

export const incidents = {
  list: (f: IncidentFilter, signal?: AbortSignal) =>
    api<Page<IncidentSummary>>(`/api/v1/incidents${query(f)}`, { signal }),
  stats: (signal?: AbortSignal) => api<IncidentStats>('/api/v1/incidents/stats', { signal }),
  get: (id: string, signal?: AbortSignal) => api<IncidentDetail>(`/api/v1/incidents/${id}`, { signal }),
  timeline: (id: string, signal?: AbortSignal) =>
    api<TimelineEntry[]>(`/api/v1/incidents/${id}/timeline`, { signal }),
  alerts: (id: string, signal?: AbortSignal) =>
    api<Page<Alert>>(`/api/v1/incidents/${id}/alerts?size=20`, { signal }),
  acknowledge: (i: IncidentDetail) =>
    api<IncidentDetail>(`/api/v1/incidents/${i.id}/acknowledge`, { method: 'POST', headers: ifMatch(i.version) }),
  resolve: (i: IncidentDetail, note?: string) =>
    api<IncidentDetail>(`/api/v1/incidents/${i.id}/resolve`, {
      method: 'POST', headers: ifMatch(i.version), body: { note: note || null },
    }),
  reassign: (i: IncidentDetail, userId: string) =>
    api<IncidentDetail>(`/api/v1/incidents/${i.id}/reassign`, {
      method: 'POST', headers: ifMatch(i.version), body: { userId },
    }),
  severity: (i: IncidentDetail, severity: Severity) =>
    api<IncidentDetail>(`/api/v1/incidents/${i.id}/severity`, {
      method: 'PATCH', headers: ifMatch(i.version), body: { severity },
    }),
  addNote: (id: string, message: string) => api<void>(`/api/v1/incidents/${id}/notes`, json({ message })),
};

export const users = {
  me: () => api<User>('/api/v1/auth/me'),
  list: (signal?: AbortSignal) => api<User[]>('/api/v1/users', { signal }),
  create: (u: { email: string; fullName: string; password: string; role: Role }) =>
    api<User>('/api/v1/users', json(u)),
  update: (id: string, patch: Partial<Pick<User, 'fullName' | 'role' | 'active'>>) =>
    api<User>(`/api/v1/users/${id}`, { method: 'PATCH', body: patch }),
  deactivate: (id: string) => api<void>(`/api/v1/users/${id}`, { method: 'DELETE' }),
};

export const teams = {
  list: (signal?: AbortSignal) => api<Team[]>('/api/v1/teams', { signal }),
  create: (name: string) => api<Team>('/api/v1/teams', json({ name })),
};

export const services = {
  list: (signal?: AbortSignal) => api<Service[]>('/api/v1/services', { signal }),
  create: (s: { name: string; serviceKey: string; teamId: string; description?: string }) =>
    api<Service>('/api/v1/services', json(s)),
  setStatus: (id: string, status: ServiceStatus) =>
    api<Service>(`/api/v1/services/${id}`, { method: 'PATCH', body: { status } }),
  keys: (id: string) => api<ApiKey[]>(`/api/v1/services/${id}/api-keys`),
  createKey: (id: string, name: string) => api<CreatedApiKey>(`/api/v1/services/${id}/api-keys`, json({ name })),
  revokeKey: (id: string, keyId: string) =>
    api<void>(`/api/v1/services/${id}/api-keys/${keyId}`, { method: 'DELETE' }),
};

export { apiRaw };
