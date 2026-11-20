# 4. Components & technology choices

Every infrastructure component below follows the same template: **what it is, why we need it,
why this tool over the alternatives, exactly how the code uses it, and what happens when it fails.**

- [4.1 PostgreSQL: system of record](#41-postgresql--system-of-record)
- [4.2 The double-entry ledger](#42-the-double-entry-ledger)
- [4.3 Redis: speed layer and coordination](#43-redis--speed-layer-and-coordination)
- [4.4 Kafka: event backbone](#44-kafka--event-backbone)
- [4.5 Transactional outbox](#45-transactional-outbox)
- [4.6 Server-Sent Events: real-time push](#46-server-sent-events--real-time-push)
- [4.7 Webhooks](#47-webhooks)
- [4.8 QR generation & signing](#48-qr-generation--signing)
- [4.9 Application stack (Spring Boot)](#49-application-stack-spring-boot)
- [4.10 Frontend stack](#410-frontend-stack)
- [4.11 Build & tooling (Gradle, Docker, Testcontainers)](#411-build--tooling)
- [4.12 Summary table](#412-summary-table)

---

## 4.1 PostgreSQL: system of record

**What:** relational database holding users, merchants, wallets, ledger, QR codes, payments and the outbox.

**Why a relational ACID database for payments?** The heart of the system is one operation that must be
all-or-nothing across **five tables**: claim the QR, debit one wallet, credit another, write two ledger rows,
flip the payment status and record an event. That is exactly what a multi-row ACID transaction gives us. It also gives
us **constraints the database itself enforces**, so even a bug in application code cannot produce a negative balance
or a second successful payment for a single-use QR.

**Why PostgreSQL specifically**

| Feature | Where we rely on it |
|---|---|
| Row-level locks `SELECT … FOR UPDATE` | `PaymentRepository.lockById`, `WalletRepository.lockByIdsInOrder` |
| `FOR UPDATE SKIP LOCKED` | `OutboxRepository.lockPendingBatch`: many relays, no double publish, no blocking |
| Conditional update re-checked after lock (READ COMMITTED) | `QrCodeRepository.markPaidIfActive`: the single-use claim |
| **Partial unique indexes** | one static QR per merchant, unique `order_ref` per merchant, one `SUCCESS` per dynamic QR |
| CHECK constraints | `balance >= 0` (except SYSTEM), amount > 0, enum domains |
| `TIMESTAMPTZ`, native `UUID` | all timestamps are UTC instants, compact 16-byte ids |
| Transactional DDL | Flyway migrations roll back cleanly if they fail halfway |
| Mature HA (Patroni, streaming/sync replication), logical decoding | RPO=0 failover; Debezium CDC for the outbox later |

**Alternatives rejected**

| Option | Why not (for this workload) |
|---|---|
| MySQL / InnoDB | Viable, but lacks partial indexes and CHECK semantics are younger. Postgres constraints map better to our invariants. |
| MongoDB | Multi-document transactions exist but are costlier and less natural. Weaker schema guarantees for money. |
| Cassandra / DynamoDB | Great write scale, but no multi-row ACID and no row locks. We would have to rebuild ledger safety with conditional writes and sagas. Reconsider only at a scale where sharded Postgres does not fit (and then look at CockroachDB/Spanner/Yugabyte first). |

**How the code uses it**

* Schema: [`V1__init_schema.sql`](../backend/src/main/resources/db/migration/V1__init_schema.sql), applied by Flyway on start.
  Hibernate only **validates** (`ddl-auto: validate`) and never alters the schema.
* **Money = `BIGINT` paise.** No floats, no rounding surprises, identical in SQL, Java and JSON.
* **Ids = UUIDv7** (`common.id.UuidV7`): generated in the app, time-ordered so B-tree inserts stay append-mostly.
* HikariCP pool (20 per node), `open-in-view: false` so no lazy loading leaks outside transactions.

**Failure mode:** Postgres is the one hard dependency of the write path. With a synchronous standby and automatic
failover (Patroni / RDS Multi-AZ), a primary loss costs ~30 s of write unavailability and **zero committed data**.

---

## 4.2 The double-entry ledger

Balances never change "just because". Every movement is a `transfer(from, to, amount)` that writes **two
immutable rows** sharing a `transfer_id`:

| wallet | entry_type | amount | balance_after |
|---|---|---|---|
| customer | DEBIT | 25,000 | 975,000 |
| merchant | CREDIT | 25,000 | 25,000 |

* Money entering the closed loop (top-up, signup bonus) comes from the **SYSTEM wallet**, which is allowed to go
  negative. Therefore **Σ balance over all wallets = 0 at all times.**
* `wallets.balance` is a cached projection of the ledger that keeps reads O(1), and the ledger proves it.
* `LedgerReconciliationJob` recomputes both invariants every 5 minutes and exports
  `qrpay.ledger.net_balance` and `qrpay.ledger.mismatched_wallets`. Alert on either ≠ 0.
* Refunds and reversals are simply new transfers in the opposite direction. History is never rewritten.

**Hot-account note:** the SYSTEM wallet row is locked by every top-up. At scale, split it into N sub-accounts
(`SYSTEM-0 … SYSTEM-15`, chosen randomly) or credit bonuses asynchronously. The same technique applies to very
large merchants (sharded settlement sub-wallets, periodically swept).

---

## 4.3 Redis: speed layer and coordination

**What:** in-memory key-value store with atomic operations, TTLs, Lua scripting and Pub/Sub.

**Why we need it:** to keep the hot read path off Postgres, and to coordinate **stateless** app nodes, which share nothing
in memory, without adding load to the database.

| Use | Key pattern | Operation | Code | Why Redis |
|---|---|---|---|---|
| **QR snapshot cache** | `qr:{qrId}` | GET / SET EX (TTL = QR expiry) / DEL after commit | `qr.QrCache` | Resolve is the hottest read (every scan). A TTL equal to the QR expiry makes the cache self-cleaning. |
| **Idempotency store** | `idem:{scope}:{user}:{key}` | SET NX EX → SET (response) | `common.idempotency.IdempotencyService` | Atomic claim (NX) shared by all nodes; auto-expiry. |
| **Distributed lock** | `lock:qr:{qrId}`, `lock:payment:{id}`, `lock:job:*` | SET NX PX + Lua compare-and-delete | `common.lock.DistributedLock` | Cheap lease lock. Lua ensures we only delete our own lock. |
| **Rate limiter** | `rl:{bucket}:{subject}:{window}` | Lua `INCR` + `PEXPIRE` | `common.ratelimit.RedisRateLimiter` | Global counters across nodes. Atomic script avoids TTL-less counters. |
| **PIN brute-force guard** | `pin-fail:{userId}` | INCR + EXPIRE, DEL on success | `WalletService.verifyPin` | Short-lived counter with a lockout window. |
| **Webhook de-dup** | `webhook:delivered:{eventId}` | SET EX 7d / EXISTS | `webhook.WebhookDispatcher` | Prevents re-delivery on Kafka redelivery or retries. |
| **Real-time fan-out** | channel `rt:user:{userId}` | PUBLISH / PSUBSCRIBE `rt:user:*` | `notification.RealtimeBroadcaster` | Broadcast to every node; the one holding the SSE connection writes it. |

**Why Redis over alternatives:** Memcached has no atomic scripts, no Pub/Sub and no persistence. Hazelcast/Ignite embed
state into app nodes, which makes deployments stateful. Using Postgres for all of this (advisory locks, `LISTEN/NOTIFY`,
counter tables) would add write load to the one component we most need to protect.

**Failure mode: deliberately fail-open.** If Redis is unreachable:

* cache → falls through to Postgres (slower, correct)
* idempotency → action runs anyway, protected by `UNIQUE(payer_user_id, idempotency_key)` + the state machine
* lock → proceeds without it, protected by `FOR UPDATE`, compare-and-set and the partial unique index
* rate limiter → allows (the gateway still has coarse limits)
* PIN counter → cannot lock out (log + alert; this is the one real security degradation)

This is only safe because **no correctness property depends on Redis**. That is also why we do not need
Redlock: our lock is an efficiency lock, not a correctness lock.

**Production setup:** Redis Cluster or a managed service (ElastiCache / Memorystore) with replicas, `maxmemory-policy
volatile-lru` (every key we write has a TTL), AOF for faster warm restarts.

---

## 4.4 Kafka: event backbone

**What:** distributed, partitioned, replicated commit log.

**Why we need it:** after a payment commits, several independent things must happen: push to the merchant's screen,
POST to the merchant's server, and later analytics, fraud scoring, loyalty points and settlement files. Doing them inside the
request would make the payment slow and fragile (a merchant's slow webhook would slow down the customer's payment).
Events let each concern run at its own pace, fail independently and replay.

**Why Kafka over alternatives**

| Need | Kafka | RabbitMQ | SQS/SNS | Redis Streams |
|---|---|---|---|---|
| Several independent consumers of the same event | ✅ consumer groups | fan-out exchanges (copies per queue) | SNS→SQS fan-out | ✅ |
| Ordering per key | ✅ per partition | per queue only | FIFO queues (limited TPS) | per stream |
| Durable replay (reprocess last 7 days) | ✅ retention-based | ❌ consumed = gone | ❌ | limited by memory |
| Throughput at 10⁵+ msg/s | ✅ | OK | OK | memory-bound |
| Ecosystem (CDC, stream processing, sinks) | ✅ Connect / Debezium / Streams | limited | AWS only | limited |

**How the code uses it**

| Topic | Key | Producer | Consumer groups |
|---|---|---|---|
| `payment.events` (6 partitions locally, 24–48 in production) | `merchantId` | `OutboxRelay` | `notification-service`, `webhook-dispatcher` |
| `payment.events-webhook-retry-0..4` | same | Spring Kafka `@RetryableTopic` | `webhook-dispatcher` (delayed) |
| `payment.events-webhook-dlt` | same | Spring Kafka | `@DltHandler` → log/alert |

* **Keyed by `merchantId`:** all events for one merchant are ordered on one partition, and load spreads evenly across merchants.
* **Producer:** `acks=all`, `enable.idempotence=true`, so no loss on broker failover and no duplicates from producer retries.
* **Consumers:** manual per-record ack after processing, so delivery is **at-least-once**. Consumers are **idempotent**:
  webhooks de-dup on `eventId` in Redis, and the UI de-dups on `eventId`.
* **Non-blocking retries** for webhooks (`@RetryableTopic`, 2 s → 162 s backoff) keep a broken merchant endpoint from
  stalling a partition shared with healthy merchants.
* Local broker runs in **KRaft** mode (no ZooKeeper). Production: 3+ brokers, `replication.factor=3`, `min.insync.replicas=2`.

**Failure mode:** Kafka is **not on the payment path**. If it is down, payments still commit. Events wait in the
outbox and drain on recovery. Real-time push is delayed, and the merchant UI falls back to polling.

---

## 4.5 Transactional outbox

**Problem (dual write):** "commit to Postgres, then publish to Kafka" has a gap. If the process dies between the two, the
payment happened but nobody is ever told. "Publish, then commit" is worse: we could announce a payment that then rolls back.

**Solution:** `OutboxWriter.append()` inserts the event into `outbox_events` **in the same transaction** as the payment
update. `OutboxRelay` (every 300 ms, on every node) claims a batch with `FOR UPDATE SKIP LOCKED`, sends it to Kafka, waits
for acks and marks the rows `PUBLISHED`. Published rows are purged after 7 days.

| Alternative | Why not |
|---|---|
| Kafka transactions | Covers Kafka ↔ Kafka, not Postgres ↔ Kafka. |
| XA / 2PC | Operationally painful, poor availability, weak Kafka support. |
| **Debezium CDC** on the outbox table | ✅ **Recommended production upgrade.** Reads the WAL, so there is no polling latency and no extra query load. The table and event format stay the same. |

---

## 4.6 Server-Sent Events: real-time push

**Need:** when a customer pays, the merchant's till must flip to *PAID* within about a second.

| Option | Verdict |
|---|---|
| Client polling every N s | Simple, but N× wasted requests and up to N s latency. **Kept as a fallback** (every 4 s, only while the stream is down). |
| WebSocket | Bidirectional, which we don't need. Needs sticky-session / upgrade handling at the LB and a custom reconnect protocol. |
| **SSE** ✅ | Server → client only (exactly our case), plain HTTP/1.1 or HTTP/2, standard reconnect semantics, proxy-friendly, trivial in Spring MVC (`SseEmitter`). |
| Mobile push (FCM/APNs) | Right for native apps in the background. Complementary, not a replacement. |

**Implementation**

* `SseHub` keeps `userId → {emitters}` for connections on **this node**, with a 20 s heartbeat comment frame to keep
  proxies from idling the connection out and to detect dead clients.
* Cross-node delivery uses Redis Pub/Sub (see 4.3): the Kafka consumer on node B reaches the browser connected to node A.
* The browser reads the stream with `fetch()` + `ReadableStream` (`usePaymentStream.ts`) rather than `EventSource`, so the
  **JWT travels in the `Authorization` header, not in the URL** (URLs end up in access logs). It reconnects with exponential
  backoff and de-dups on `eventId`.

---

## 4.7 Webhooks

Merchants' servers (POS, order management) need a machine-to-machine signal.

* **Signature:** `X-QrPay-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>` (Stripe-style). Signing the
  timestamp lets receivers reject replays older than 5 minutes. Verification uses a constant-time compare.
* **Headers:** `X-QrPay-Event-Id` (de-dup key for merchants), `X-QrPay-Event-Type`, `X-QrPay-Merchant-Id`.
* **Retry policy:** 6 attempts over ~4 minutes via retry topics. Retried: timeouts, connection errors, 5xx, 408, 429,
  and transient failures on our side (e.g. a database blip while loading the merchant). Not retried: other 4xx
  (the merchant rejected the event, so retrying will not help) and unparseable payloads (sent straight to the DLT).
* **DLT:** permanently failed events land in `payment.events-webhook-dlt` for alerting and manual replay.
* **Public contract:** `WebhookPayload` is versioned (`apiVersion`) and never exposes internal user ids.
* **Dev sink:** `POST/GET /api/v1/dev/webhook-sink` plays a merchant server locally and verifies signatures exactly
  as a merchant integration should.
* **Production hardening:** SSRF protection (resolve the host and reject private/link-local ranges), per-merchant
  concurrency limits, delivery log table with a replay button.

---

## 4.8 QR generation & signing

* **The backend never draws QR images.** It mints the row and signs the payload; the browser draws it with
  **`qrcode-generator`** (zero dependencies, bundled types) as an SVG - vector, so it is crisp on a large till screen
  and prints sharp as a sticker. We use error-correction level **M** (~15 % of the code can be damaged or covered and
  it still scans) with a 2-module quiet zone. Any other client gets the same `payload` and draws it with any QR library.
* **HMAC-SHA256, truncated to 128 bits**, over the exact signed bytes (no canonicalisation ambiguity). The key is
  `QR_SIGNING_SECRET` (in production from a KMS or secret manager, rotated with a `kid`-style version param).
* **Static vs dynamic**

| | Static | Dynamic |
|---|---|---|
| Amount | entered by customer | fixed, in the DB (never in the payload) |
| Lifetime | forever (until disabled) | 30 s – 30 min |
| Uses | unlimited | exactly one successful payment |
| Typical place | printed sticker at a shop | POS screen per bill |
| Lock scope on confirm | the payment | the QR (serialises all payers of that code) |

---

## 4.9 Application stack (Spring Boot)

| Library | Why |
|---|---|
| **Spring Boot 3.5 / Java 17** | Mature, batteries-included (security, data, Kafka, Redis, actuator). Java 17 LTS runs everywhere. |
| **Spring MVC** (not WebFlux) | Payment logic is transactional, blocking JDBC. MVC keeps it simple and debuggable. SSE works via async servlets. Virtual threads (Java 21+) are the easy scalability upgrade. |
| **Spring Security + JWT (jjwt)** | Stateless auth, so any node can serve any request. Method-level `@PreAuthorize` for roles. In production, swap self-issued tokens for an IdP (Keycloak / Cognito / Auth0) with RS256 + JWKS. |
| **Spring Data JPA / Hibernate** | Productive CRUD + explicit `@Lock`/`@Modifying` queries for the critical paths. `Persistable` avoids a SELECT before INSERT with app-assigned ids. |
| **Flyway** | Versioned, reviewable SQL migrations. Hibernate only validates. |
| **Spring Kafka** | `@KafkaListener`, `@RetryableTopic` + DLT, error handlers. |
| **Spring Data Redis (Lettuce)** | Non-blocking, thread-safe client. `StringRedisTemplate` + Lua scripts. |
| **springdoc-openapi** | Live OpenAPI 3 spec + Swagger UI at `/swagger-ui.html`. |
| **Actuator + Micrometer (Prometheus)** | Liveness/readiness probes for Kubernetes, JVM/HTTP/Kafka/Hikari metrics, custom ledger gauges. |
| **Bean Validation** | Declarative request validation → uniform `VALIDATION_FAILED` errors. |
| **Lombok** | Removes getter/constructor boilerplate on entities and services. |

---

## 4.10 Frontend stack

| Choice | Why |
|---|---|
| **React 19 + TypeScript** | Typed mirrors of the API DTOs (`api/types.ts`) catch contract drift at compile time. |
| **Vite** | Instant dev server. Dev proxy `/api → :8080` makes the app same-origin (no CORS in dev, SSE just works). |
| **react-router** | Role-based routes (`/customer`, `/merchant`). |
| **html5-qrcode** | Camera **and** image-file decoding in the browser. Lazy-loaded so it costs nothing until the scanner opens. |
| **No global state library** | Server state is small and page-scoped. `useState` + a tiny `api()` wrapper is enough. React Query would be the next step. |
| **Idempotency keys** (`crypto.randomUUID()`) | One key per logical action, reused across retries. |

---

## 4.11 Build & tooling

* **Gradle multi-project** (`settings.gradle.kts`): `:backend` (Spring Boot plugin, BOM via `platform()`) and `:frontend`
  (`com.github.node-gradle.node` drives `npm install` + `vite build`). `./gradlew buildAll` builds both.
* **Docker Compose** for local Postgres 16, Redis 7.4, Kafka 3.9 (KRaft) and Kafka UI (`:8090`).
* **Testcontainers** integration tests spin up the same three services per test run (`./gradlew :backend:test -Pit`).

---

## 4.12 Summary table

| Concern | Choice | Key reason | Fallback when it fails |
|---|---|---|---|
| System of record | PostgreSQL | ACID across tables, locks, constraints | Sync standby failover |
| Money model | Double-entry ledger, BIGINT paise | Auditability, exactness | Reconciliation job alerts |
| Hot reads | Redis cache-aside | Sub-ms QR resolve | Read from Postgres |
| Retry safety | Idempotency-Key in Redis | Cross-node atomic claim | DB unique constraint + state machine |
| Contention control | Redis lease lock | Fast rejection on hot QR | DB row lock + CAS |
| Abuse control | Redis rate limiter, PIN counter | Global counters | Gateway limits |
| Async events | Kafka | Durable, ordered, multi-consumer | Outbox buffers |
| Event reliability | Transactional outbox | No dual write | Relay on any node |
| Real-time UI | SSE + Redis Pub/Sub | Simple one-way push across nodes | UI polling every 4 s |
| Merchant integration | Signed webhooks + retry topics + DLT | Secure, non-blocking retries | DLT + manual replay |
| QR | HMAC-signed id-only payload, drawn client-side | Tamper-proof, server-authoritative | n/a |
