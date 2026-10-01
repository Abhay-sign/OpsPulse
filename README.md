# OpsPulse

A real-time incident management platform — alert ingestion, deduplication, escalation ladders, and a live dashboard. Spring Boot + PostgreSQL + Redis + React.

**Status: phase 1 complete** — infrastructure, schema, entity and repository layer.

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
│       │   ├── config/         Redis · ShedLock · Security · properties
│       │   └── web/            HealthController
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

## What Redis is for

Not "caching" in the vague sense — four specific jobs:

1. **Warm dedup pointers** — `SET dedup:{key} {incidentId} NX EX 3600` avoids a Postgres round trip on the hot ingest path. Postgres stays the source of truth; Redis is only a shortcut.
2. **Rate limiting** — per-API-key token bucket as a Lua script, so check-and-decrement is atomic.
3. **SSE fan-out** — see below.
4. **Caching services and escalation policies** — read on every ingest, changed almost never.

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
| 2 | Auth (JWT for humans, API keys for senders), users/teams/services CRUD | |
| 3 | Alert ingestion + dedup → incident creation, with concurrency tests | |
| 4 | Incident lifecycle: ack, resolve, reassign, notes → timeline events | |
| 5 | React dashboard (polling first) | |
| 6 | SSE + Redis pub/sub fan-out | |
| 7 | Escalation policies, sweeper job, email outbox | |
| 8 | CI hardening, image publishing | |

Two filter chains in phase 2, matched by path: a monitoring system cannot refresh a JWT, so `POST /api/v1/alerts` authenticates with a service-scoped API key instead.
