// Mirrors the backend DTO records. Kept by hand: the API is small and this
// file doubles as readable documentation of what the UI depends on.

export type Role = 'ADMIN' | 'RESPONDER' | 'VIEWER';
export type Severity = 'SEV1' | 'SEV2' | 'SEV3' | 'SEV4';
export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED';
export type ServiceStatus = 'OPERATIONAL' | 'DEGRADED' | 'MAINTENANCE';

export const SEVERITIES: Severity[] = ['SEV1', 'SEV2', 'SEV3', 'SEV4'];

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  timezone: string;
  active: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface IncidentSummary {
  id: string;
  title: string;
  severity: Severity;
  status: IncidentStatus;
  serviceId: string;
  serviceName: string;
  assignedUserId: string | null;
  assignedUserName: string | null;
  alertCount: number;
  createdAt: string;
  updatedAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
}

export interface IncidentDetail extends IncidentSummary {
  description: string | null;
  dedupKey: string;
  teamId: string;
  teamName: string;
  escalationPolicyId: string | null;
  currentEscalationLevel: number;
  resolvedByUserId: string | null;
  resolvedByName: string | null;
  version: number;
}

export interface IncidentStats {
  open: number;
  triggered: number;
  acknowledged: number;
  openBySeverity: Record<Severity, number>;
  resolvedLast24h: number;
  mttaSeconds7d: number | null;
  mttrSeconds7d: number | null;
}

export type TimelineType =
  | 'CREATED' | 'ALERT_ATTACHED' | 'ACKNOWLEDGED' | 'ESCALATED' | 'REASSIGNED'
  | 'NOTE_ADDED' | 'SEVERITY_CHANGED' | 'RESOLVED' | 'NOTIFICATION_SENT';

export interface TimelineEntry {
  id: string;
  type: TimelineType;
  message: string | null;
  actorId: string | null;
  actorName: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export interface Alert {
  id: string;
  fingerprint: string;
  severity: Severity;
  summary: string;
  source: string;
  eventType: 'TRIGGER' | 'RESOLVE';
  payload: Record<string, unknown>;
  receivedAt: string;
}

export interface Team {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  createdAt: string;
}

export interface Service {
  id: string;
  name: string;
  serviceKey: string;
  description: string | null;
  teamId: string;
  teamName: string;
  escalationPolicyId: string | null;
  status: ServiceStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  active: boolean;
  lastUsedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
}

export interface CreatedApiKey {
  key: ApiKey;
  plaintextKey: string;
}

/** RFC 7807 body the backend returns for every error. */
export interface Problem {
  status: number;
  title?: string;
  detail?: string;
  errors?: Record<string, string>;
}
