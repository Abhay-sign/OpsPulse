# OpsPulse

A real-time incident management platform — alert ingestion, deduplication, escalation ladders, and a live dashboard. Spring Boot + PostgreSQL + Redis + React.

**Status: phase 2 complete** — infrastructure, schema, entities, authentication (JWT + API keys) and admin CRUD.

---

## The one idea the design rests on

**An alert is not an incident.**

| | Alert | Incident |
|---|---|---|
| Volume | High. A flapping check re-fires every 30s | One per real problem |
| Lifetime | Immutable, append-only | Has a lifecycle and an owner |
| Created by | Monitoring source | The *first* alert with a new dedup key |

`dedup_key = sha256(service_id + ":" + fingerprint)`

The first alert bearing a key opens an incident. Every subsequent matching alert attaches to that incident and increments `alert_count`. Without this, a service flapping for ten minutes produces 400 incidents and the dashboard becomes useless.

The guarantee is enforced **in the database**, not in service code:

```sql
CREATE UNIQUE INDEX uq_incidents_open_dedup
    ON incidents (dedup_key)
    WHERE status <> 'RESOLVED';
```

Application-level "check then insert" has a race window; two replicas ingesting the same alert simultaneously would both pass the check. With this index, one insert wins and the other gets a constraint violation that the ingest path catches and turns into an attach. The index is *partial* so that the same problem recurring next week correctly opens a new incident.

`SchemaIntegrityTest` asserts all three behaviours.

---

## Running it

```bash
cp .env.example .env
docker compose up --build
```

| | URL |
|---|---|
| API | http://localhost:8080 |
| Deep health check | http://localhost:8080/api/v1/health |
| Mailpit inbox | http://localhost:8025 |

First boot creates an admin from `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` (defaults `admin@opspulse.local` / `admin12345`):

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@opspulse.local","password":"admin12345"}' | jq -r .accessToken)

curl -s -X POST localhost:8080/api/v1/teams -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Payments"}'
```

Backend only, against compose-managed infra:

```bash
docker compose up -d postgres redis mailpit
cd backend && mvn spring-boot:run
```

Tests (needs a running Docker daemon — Testcontainers starts its own Postgres and Redis):

```bash
cd backend && mvn verify
```

---

## Layout

```
opspulse/
├── docker-compose.yml          postgres · redis · mailpit · backend
├── .github/workflows/ci.yml    test → build → push to GHCR
├── backend/
│   ├── Dockerfile              multi-stage, cached dependency layer
│   └── src/main/
│       ├── java/com/opspulse/
│       │   ├── domain/enums/   Role · Severity · IncidentStatus (state machine)
│       │   ├── domain/entity/  11 JPA entities
│       │   ├── repository/     10 Spring Data repositories
│       │   ├── config/         Redis · ShedLock · two security chains · admin bootstrap
│       │   ├── security/       JWT service + filter · API-key filter · refresh-token store
│       │   ├── service/        Auth · User · Team · ServiceCatalog · ApiKey
│       │   └── web/            controllers · DTO records · RFC 7807 error handler
│       └── resources/db/migration/V1__init.sql
└── frontend/                   (phase 5)
```

---

## Decisions worth knowing

**Flyway owns the schema; `ddl-auto: validate`.** Hibernate never writes DDL. Because the entities are validated against the real migration at context startup, any drift between a `@Column` and its SQL column fails the test suite immediately rather than at runtime.

**`VARCHAR` + `CHECK`, not Postgres `ENUM` types.** Adding a value to a PG enum is an awkward migration and the Hibernate mapping is fiddly. A check constraint gives identical safety.

**Entities named `UserAccount` and `MonitoredService`.** `user` is reserved in Postgres, and `Service`/`User` collide with `@Service` and Spring Security's `User` in nearly every import list.

**`@Version` on `Incident`.** Two responders clicking acknowledge at the same instant: one gets an `OptimisticLockException` rather than a silent lost update.

**`incident_events` is both the timeline and the audit log.** One append-only table, `jsonb` payload, `actor_user_id IS NULL` meaning the system acted. A separate audit table would mean writing the same rows twice.

**`notifications` is a transactional outbox.** Rows are inserted in the same transaction as the incident change; a poller sends them. A rolled-back transaction never emails anyone, a crash mid-send loses nothing, and retry is just a `next_attempt_at` column. Never call SMTP inside a request transaction.

**ShedLock on scheduled jobs.** Two replicas would otherwise both run the escalation sweeper and page the same engineer twice — the bug on-call engineers hate most. `usingDbTime()` so clock skew between replicas is irrelevant.

**Testcontainers, not H2.** The schema uses partial unique indexes, `jsonb`, `make_interval()` and `FOR UPDATE SKIP LOCKED`. H2 would either reject them or behave differently, and the suite would pass while the dedup guarantee failed in production.

---

## Authentication (phase 2)

Two kinds of caller, two separate Spring Security filter chains, chosen by URL:

| Chain | Paths | Caller | Credential | Role |
|---|---|---|---|---|
| 1 | `/api/v1/alerts/**` | monitoring systems | `X-API-Key: op_…` | `INGEST` |
| 2 | everything else | people / the dashboard | `Authorization: Bearer <JWT>` | `ADMIN` · `RESPONDER` · `VIEWER` |

A JWT is ignored on chain 1 and an API key is ignored on chain 2, so neither credential works outside its lane.

**Access + refresh tokens.** Login returns a 15-minute access token (id, email, role in its claims — no DB lookup per request) and a 30-day refresh token. The two carry a `typ` claim and each is rejected where the other is expected; otherwise a stolen refresh token would be a 30-day access token.

**Refresh rotation in Redis.** A pure JWT can't be revoked, so each refresh token's `jti` is stored as `refresh:{jti}` with a matching TTL. `/auth/refresh` consumes it with an atomic `GETDEL` and issues a new pair. A replayed token finds nothing and gets 401 — if an attacker uses a stolen token first, the real user's next refresh fails, exposing the theft. Logout deletes the key. Deactivation and role changes take effect at the next refresh (≤ 15 min), the standard JWT trade-off.

**API keys are SHA-256, not bcrypt.** bcrypt slows guessing of *low-entropy* human passwords. A key is 32 random bytes, which can't be brute-forced at any hash speed, and it is checked on *every* alert — bcrypt's ~250 ms per check would cap ingestion, and its salt would rule out an indexed lookup. Only the hash and a 10-char display prefix are stored; the plaintext is returned once, at creation.

**No public sign-up.** The first admin comes from env vars on first boot. Every write endpoint is `@PreAuthorize("hasRole('ADMIN')")`, and the last active admin can't be demoted or deactivated.

**Login doesn't leak which emails exist.** Same message for an unknown email and a wrong password, and an unknown email still runs a bcrypt comparison so response time doesn't give it away.

**Errors are RFC 7807 `application/problem+json`** everywhere, including 401/403 written by the security layer itself, with a field→message map on validation errors.

### API so far

| Method | Path | Who |
|---|---|---|
| POST | `/api/v1/auth/login` · `/refresh` · `/logout` | public |
| GET | `/api/v1/auth/me` | any user |
| GET | `/api/v1/users` · `/users/{id}` | any user |
| POST · PATCH · DELETE | `/api/v1/users` · `/users/{id}` (DELETE = deactivate) | admin |
| PATCH · POST | `/api/v1/users/me` · `/users/me/password` | self |
| GET | `/api/v1/teams` · `/teams/{id}` · `/teams/{id}/members` | any user |
| POST · PATCH · DELETE | `/api/v1/teams` · `/teams/{id}` | admin |
| PUT · DELETE | `/api/v1/teams/{id}/members/{userId}` | admin |
| GET | `/api/v1/services[?teamId=]` · `/services/{id}` | any user |
| POST · PATCH · DELETE | `/api/v1/services` · `/services/{id}` | admin |
| GET · POST · DELETE | `/api/v1/services/{id}/api-keys[/{keyId}]` | admin |
| GET | `/api/v1/alerts/auth-check` | API key |

---

## What Redis is for

Not "caching" in the vague sense — five specific jobs:

1. **Refresh-token allowlist** — `refresh:{jti}`, consumed atomically on use (see above).
2. **Warm dedup pointers** — `SET dedup:{key} {incidentId} NX EX 3600` avoids a Postgres round trip on the hot ingest path. Postgres stays the source of truth; Redis is only a shortcut.
3. **Rate limiting** — per-API-key token bucket as a Lua script, so check-and-decrement is atomic.
4. **SSE fan-out** — see below.
5. **Caching services and escalation policies** — read on every ingest, changed almost never.

Incidents are deliberately *not* cached. They mutate constantly and the invalidation cost outweighs the benefit.

## Why SSE rather than WebSocket

Only server → client push is needed. `EventSource` reconnects automatically, it works over plain HTTP, and STOMP/SockJS disappears entirely.

The trap: `SseEmitter` instances live in one JVM's heap. With two replicas, a browser connected to A never sees events produced by B. Every domain event is therefore published to a Redis channel that each replica subscribes to and forwards to its own local emitters. `RedisConfig` already wires the listener container for this.

Also — nginx buffers SSE by default and the stream will appear frozen. Set `proxy_buffering off;` on that location.

---

## Roadmap

| Phase | | |
|---|---|---|
| 1 | Infra, schema, entities, repositories, health | ✅ |
| 2 | Auth (JWT for humans, API keys for senders), users/teams/services CRUD | ✅ |
| 3 | Alert ingestion + dedup → incident creation, with concurrency tests | |
| 4 | Incident lifecycle: ack, resolve, reassign, notes → timeline events | |
| 5 | React dashboard (polling first) | |
| 6 | SSE + Redis pub/sub fan-out | |
| 7 | Escalation policies, sweeper job, email outbox | |
| 8 | CI hardening, image publishing | |
