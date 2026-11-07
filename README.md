# OpsPulse

A real-time incident management platform — alert ingestion, deduplication, escalation ladders, and a live dashboard. Spring Boot + PostgreSQL + Redis + React.

**Status: phase 4 complete** — infrastructure, schema, auth (JWT + API keys), admin CRUD, alert ingestion with database-enforced deduplication, and the incident lifecycle (acknowledge, resolve, reassign, severity, notes) with an audit timeline.

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

Application-level "check then insert" has a race window; two replicas ingesting the same alert simultaneously would both pass the check. With this index, the ingest path inserts with `ON CONFLICT ... DO NOTHING`: one insert wins, the other gets no row back and attaches to the winner instead (details in *Alert ingestion* below). The index is *partial* so that the same problem recurring next week correctly opens a new incident.

`SchemaIntegrityTest` asserts the index behaviour; `IngestConcurrencyTest` fires 25 identical alerts at the same instant and asserts exactly one incident with `alert_count = 25`.

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
│       │   ├── ingest/         AlertIngestService · DedupCache · RateLimiter (Lua) · DedupKeys
│       │   ├── event/          IncidentChangedEvent · after-commit listeners
│       │   ├── service/        Auth · User · Team · ServiceCatalog · ApiKey · IncidentQuery · IncidentCommand
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
| POST | `/api/v1/alerts` | API key |
| GET | `/api/v1/alerts/auth-check` | API key |
| GET | `/api/v1/incidents?status=&severity=&serviceId=&assigneeId=&openOnly=&page=&size=` | any user |
| GET | `/api/v1/incidents/{id}` (with `ETag`) · `/{id}/alerts` · `/{id}/timeline` | any user |
| POST | `/api/v1/incidents/{id}/acknowledge` · `/resolve` · `/reassign` · `/notes` (optional `If-Match`) | responder, admin |
| PATCH | `/api/v1/incidents/{id}/severity` (optional `If-Match`) | responder, admin |

---

## Alert ingestion (phase 3)

```bash
curl -X POST localhost:8080/api/v1/alerts -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"fingerprint":"cpu-high:web-03","severity":"SEV2","summary":"CPU above 95% on web-03","source":"prometheus"}'
# 201 {"outcome":"CREATED",  "incidentId":"e29f…", ...}   first time
# 200 {"outcome":"ATTACHED", "incidentId":"e29f…", ...}   every repeat
# send {"fingerprint":"cpu-high:web-03","eventType":"RESOLVE"} to auto-resolve
```

The API key decides the service, so a sender can never raise alerts for someone else's service.

**A TRIGGER alert, step by step**

1. **Rate limit**: a token bucket per API key in Redis (a Lua script, so read-refill-spend is atomic across replicas). Over the limit → `429` with `Retry-After`.
2. **Warm path**: Redis holds `dedup:{key} → incidentId`. If it's there, one statement attaches the alert:
   ```sql
   WITH cur AS (SELECT id, severity FROM incidents
                 WHERE id = ? AND dedup_key = ? AND status <> 'RESOLVED' FOR UPDATE)
   UPDATE incidents i SET alert_count = i.alert_count + 1,
                          severity    = LEAST(i.severity, ?),   -- 'SEV1' < 'SEV2', so worse wins
                          updated_at  = now()
     FROM cur WHERE i.id = cur.id
   RETURNING cur.severity;
   ```
   Zero rows back means the pointer was stale (a human resolved that incident), so we evict it and fall through. No SELECT is needed to find the incident.
3. **Cold path**: `SELECT` the open incident. If found, attach as above.
4. **Create**:
   ```sql
   INSERT INTO incidents (...) SELECT ... FROM services s WHERE s.id = ?
   ON CONFLICT (dedup_key) WHERE status <> 'RESOLVED' DO NOTHING
   RETURNING id;
   ```
   A row back means we created it. Nothing back means another request created it a moment earlier; loop to step 3, which now finds it.
5. After commit, warm the Redis pointer. It is never set before commit, so it can't point at a row that rolled back.

**Why `ON CONFLICT` and not catch-the-exception-and-retry.** In Postgres a failed statement aborts the whole transaction, so after catching a unique violation you can't even run the follow-up SELECT without a new transaction or a savepoint. `ON CONFLICT DO NOTHING` never raises. If another transaction is mid-insert on the same key, Postgres makes us wait for it to commit, then reports "nothing inserted". Under READ COMMITTED the next statement takes a fresh snapshot and sees the winner. One transaction, no exception, no retry. In the concurrency test about half the threads take that lost-the-race branch on every run.

**Why the counter is a SQL increment, not `incident.setAlertCount(n + 1)`.** A read-modify-write in Java loses increments under concurrency. It would also bump `@Version`, so a responder's acknowledge would fail just because another alert arrived. `alert_count` is a counter, not a human edit, so it bypasses optimistic locking on purpose. `@DynamicUpdate` on `Incident` makes that safe: Hibernate then updates only the changed columns, so a responder saving a stale copy can't overwrite the counter.

**RESOLVE alerts** close the open incident through the entity, so `@Version` protects against a human acknowledging it at the same instant (retried up to 3 times). With nothing open, the alert is stored and the response says `NO_OPEN_INCIDENT`.

**Redis is never required.** Every Redis call fails open: no pointer means the Postgres path, and no rate limiter means the alert is accepted. Dropping real alerts because a cache is down would be the worst possible failure for a pager.

**Severity only goes up.** A SEV1 arriving on a SEV3 incident raises it and writes a `SEVERITY_CHANGED` timeline event. A later SEV4 doesn't lower it.

**Not every alert goes on the timeline.** A check flapping every 30 seconds would bury the human actions, so individual alerts are listed at `/incidents/{id}/alerts` and the timeline holds only state changes.

---

## Incident lifecycle (phase 4)

```
TRIGGERED ──acknowledge──► ACKNOWLEDGED ──resolve──► RESOLVED (terminal)
    └──────────────────────resolve─────────────────────┘
```

The transitions live in one place, `IncidentStatus.canTransitionTo`, and every command asks it. An illegal move (ack twice, resolve a resolved incident) is a `409` with a readable message.

| Command | Effect | Timeline event |
|---|---|---|
| `POST /incidents/{id}/acknowledge` | → ACKNOWLEDGED; takes ownership if unassigned; stops escalation (phase 7) | `ACKNOWLEDGED` |
| `POST /incidents/{id}/resolve` `{note?}` | → RESOLVED, records who; frees the dedup key | `RESOLVED` |
| `POST /incidents/{id}/reassign` `{userId}` | new owner (active, not a viewer); same owner is a no-op | `REASSIGNED` (from/to) |
| `PATCH /incidents/{id}/severity` `{severity}` | open incidents only | `SEVERITY_CHANGED` |
| `POST /incidents/{id}/notes` `{message}` | allowed after resolution (root cause, follow-ups) | `NOTE_ADDED` |

Commands need RESPONDER or ADMIN; viewers can only watch. Each command is one transaction that changes the row, appends the timeline event (the same table is the audit log, `actor_user_id = NULL` meaning the system), and publishes an `IncidentChangedEvent`.

**Two kinds of "someone got there first"**

- **Concurrent write.** Two responders click Acknowledge in the same instant and both transactions read version 3. `@Version` lets one commit, and the other fails at flush with `409`. `IncidentConcurrencyTest` repeats this race 5 times and asserts exactly one winner and one `ACKNOWLEDGED` row every time.
- **Stale view.** Bob opened the page a minute ago at version 3; Alice has since acknowledged it. That isn't concurrent, so `@Version` alone wouldn't stop Bob's click from acting on a state he never saw. So `GET /incidents/{id}` returns `ETag: "3"`, and a command may send it back as `If-Match: "3"`. If it no longer matches, the result is `412 Precondition Failed` and nothing changes. `If-Match` is optional, since scripts don't need it, but the dashboard will always send it.

New alerts don't count as "changed" for this purpose. Ingest bumps `alert_count` without touching `version`, so a flapping check can't make every responder action fail with `412` (tested).

**Domain events, delivered after commit.** Commands and ingest publish `IncidentChangedEvent` inside their transaction, and listeners use `@TransactionalEventListener` (AFTER_COMMIT), so a rolled-back change never triggers a side effect. The first listener drops the Redis dedup pointer when an incident is resolved. Phase 6 adds the SSE fan-out and phase 7 the notifier as more listeners, without touching the write paths.

---

## What Redis is for

Not "caching" in the vague sense — five specific jobs:

1. **Refresh-token allowlist** — `refresh:{jti}`, consumed atomically on use (see above).
2. **Warm dedup pointers** — `SET dedup:{key} {incidentId} NX EX 3600` avoids a Postgres round trip on the hot ingest path. Postgres stays the source of truth; Redis is only a shortcut.
3. **Rate limiting** — per-API-key token bucket as a Lua script, so check-and-decrement is atomic.
4. **SSE fan-out** — see below.
5. **Caching services and escalation policies** — read on every ingest, changed almost never. *(Not needed yet: the ingest path reads neither; the API key lookup already joins the service.)*

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
| 3 | Alert ingestion + dedup → incident creation, with concurrency tests | ✅ |
| 4 | Incident lifecycle: ack, resolve, reassign, notes → timeline events | ✅ |
| 5 | React dashboard (polling first) | |
| 6 | SSE + Redis pub/sub fan-out | |
| 7 | Escalation policies, sweeper job, email outbox | |
| 8 | CI hardening, image publishing | |
