# 1. Requirements

> **Scope in one sentence:** a closed-loop, wallet-funded QR payment system (think "UPI QR / Paytm QR"):
> merchants display a signed QR code, customers scan it, confirm with a PIN, money moves atomically
> from the customer's wallet to the merchant's settlement wallet, and the merchant is notified in real time
> (on screen and via a signed webhook to their own server).

---

## 1.1 Actors

| Actor | Description |
|---|---|
| **Customer (payer)** | Holds a PIN-protected wallet. Scans QR codes and authorises payments. |
| **Merchant (payee)** | Shop / business. Generates QR codes, watches payments land, owns a settlement wallet. |
| **Merchant server** | The merchant's own backend (POS, order system). Receives webhooks. |
| **Operations** | Monitors ledger integrity, dead-lettered webhooks, system health. |

---

## 1.2 Functional requirements

| ID | Requirement | Priority |
|---|---|---|
| **FR-1** | A customer can register; registration opens a wallet protected by a 4–6 digit PIN (demo: credited with ₹10,000). | Must |
| **FR-2** | A merchant can onboard with business name + category (MCC) and receives a unique payment handle (VPA, e.g. `chai-point-3f9a@qrpay`), a settlement wallet and a webhook signing secret. | Must |
| **FR-3** | A merchant can generate a **dynamic QR**: fixed amount, single-use, expiry between 30 s and 30 min, optional merchant `orderRef`. Re-creating with the same `orderRef` + amount returns the same live code (retry-safe). | Must |
| **FR-4** | Every merchant has one **static QR**: reusable forever, the customer enters the amount. | Must |
| **FR-5** | QR payloads are **cryptographically signed**; forged or tampered codes are rejected before any database lookup. | Must |
| **FR-6** | A customer can scan a QR (camera, uploaded image, or pasted payload) and see **who** they are paying (verified name + VPA) and **how much**. | Must |
| **FR-7** | A customer can **initiate** a payment, which creates a short-lived payment session (default 5 min, never longer than the QR's own expiry). No money moves yet. | Must |
| **FR-8** | A customer **confirms** the payment with their PIN. On success the payer is debited and the merchant credited **atomically**; the result is `SUCCESS` or `FAILED` with a reason (e.g. `INSUFFICIENT_BALANCE`). | Must |
| **FR-9** | A dynamic QR can be paid **at most once**, even when several customers try at the same instant. | Must |
| **FR-10** | A customer can cancel an unconfirmed payment; unconfirmed payments and dynamic QRs expire automatically. | Must |
| **FR-11** | The merchant sees the payment **in real time** (target < 2 s): the QR on the till flips to *PAID* and a live feed updates. The customer's wallet view refreshes too. | Must |
| **FR-12** | The merchant's server receives a **signed webhook** for every payment outcome, with automatic retries and a dead-letter queue. | Must |
| **FR-13** | Both parties can view wallet balance, a ledger statement and payment history. | Must |
| **FR-14** | Every money-moving request is **idempotent** (`Idempotency-Key` header), so client retries never double-charge. | Must |
| **FR-15** | Wrong PIN attempts are limited (5 per 15 min) to stop brute force. | Must |
| **FR-16** | Demo top-up of the customer wallet. | Should |

### Explicitly out of scope (designed for, not implemented)

- Real money rails (UPI/NPCI switch, card networks, bank settlement): the `WalletService.transfer` boundary is where a PSP adapter would plug in.
- Refunds, disputes and chargebacks (the ledger already supports reversing entries).
- KYC, merchant risk scoring, fraud/velocity rules beyond rate limits.
- Multi-currency and FX.
- Admin console.

---

## 1.3 Non-functional requirements

| Category | Requirement | Target | How the design meets it |
|---|---|---|---|
| **Correctness / consistency** | No double spend, no lost or created money, no double payment of a dynamic QR. | 0 incidents; invariants checked continuously | Single ACID transaction per payment in PostgreSQL, row locks in a fixed order, compare-and-set on QR status, unique constraints, a double-entry ledger, and a reconciliation job ([04 §PostgreSQL](04-components-and-technology-choices.md#41-postgresql--system-of-record)). |
| **Availability** | The payment path stays up. | 99.95 % (≈ 22 min/month) | Stateless app nodes behind a load balancer, PostgreSQL primary + synchronous standby. Redis and Kafka are **not** on the critical commit path: lock / rate-limit / idempotency cache fail open to database guards, and the outbox buffers events while Kafka is down. |
| **Latency** | QR resolve | p99 < 100 ms | Redis cache-aside snapshot; the HMAC is verified in memory first. |
|  | Payment confirm | p99 < 300 ms (excluding bank rails) | One short DB transaction; the BCrypt PIN check (~70 ms) and the Redis lock happen **before** the transaction opens, so row locks are held for milliseconds. |
|  | QR generation | p99 < 150 ms | One INSERT plus one in-memory HMAC; no image is drawn server-side. |
|  | Merchant notification | p95 < 2 s after commit | Outbox poll (300 ms) → Kafka → Redis Pub/Sub → SSE. |
| **Throughput** | Sustain peak traffic. | 2,000 TPS confirm, 10,000 RPS resolve (see estimate) | Horizontal app scaling, Redis for hot reads, Kafka partitions for consumer parallelism, and a partitioning/sharding path for Postgres ([07](07-scalability-reliability-security.md)). |
| **Durability** | A committed payment is never lost. | RPO = 0 | Postgres WAL + synchronous replication. Events are persisted in the same transaction (outbox) before they are ever published. |
| **Idempotency** | Retries are safe everywhere. | exactly-once *effect* | Idempotency-Key (Redis) + `UNIQUE(payer, idempotency_key)` + state machine + consumer de-duplication by `eventId`. |
| **Security** | Protect money and credentials. | — | JWT (HS256) auth, BCrypt for passwords and PINs, PIN lockout, HMAC-signed QR payloads, HMAC-signed webhooks with timestamp (replay protection), per-user rate limits, role-based access, server-authoritative amounts, and no secrets in URLs or idempotency fingerprints. |
| **Auditability** | Every paisa is explainable. | — | Immutable append-only `ledger_entries`, payments as denormalised receipts, request IDs in every log line. |
| **Observability** | Detect problems before customers do. | — | Actuator liveness/readiness, Prometheus metrics, ledger invariant gauges, structured logs. |
| **Maintainability** | Evolve without rewrites. | — | Modular monolith: one package per bounded context with explicit service boundaries, each extractable into its own service ([03](03-high-level-design.md#35-deployment-view--from-modular-monolith-to-services)). |

---

## 1.4 Capacity estimation (back-of-envelope)

**Assumptions:** 20 M monthly active customers, 2 M merchants, **30 M payments/day**, peak factor ×5
(lunch hour, festival sales).

### Traffic

| Metric | Calculation | Result |
|---|---|---|
| Average payment TPS | 30 M ÷ 86,400 s | **≈ 350 TPS** |
| Peak payment TPS | 350 × 5 | **≈ 1,750 TPS** → design for **2,000** |
| API calls per payment | resolve + initiate + confirm + ~2 status/history reads | ≈ 5 |
| Peak API RPS | 1,750 × 5 | **≈ 9,000 RPS** |
| QR creations (dynamic) | ≈ 60 % of payments | ≈ 1,000/s peak |

### Database write load (per payment)

payment insert + payment update + QR update + 2 wallet updates + 2 ledger inserts + outbox insert + outbox update
≈ **9 row writes** → 1,750 TPS × 9 ≈ **16 k row writes/s** at peak. That is within reach of one well-provisioned
Postgres primary (NVMe, tuned WAL), but it is the scaling pressure point, so the design includes a partitioning
and sharding plan.

### Storage

| Data | Size / payment | Per day | Per year |
|---|---|---|---|
| `payments` | ~500 B + indexes | 15 GB | 5.5 TB |
| `ledger_entries` (×2) | ~400 B + indexes | 12 GB | 4.4 TB |
| `qr_codes` (dynamic) | ~600 B | 11 GB | 4 TB (archivable after expiry) |
| `outbox_events` | ~1 KB | 30 GB | purged after 7 days |

→ **~15 TB/year** of durable data. Use monthly range partitions on `payments` and `ledger_entries`, keep ~90 days hot on
the primary, and move older partitions to cheaper storage or the data warehouse.

### Redis memory

| Use | Calculation | Size |
|---|---|---|
| Live dynamic QR snapshots | 1,000/s × 300 s TTL × ~500 B | ~150 MB |
| Idempotency records | 60 M keys/day × ~700 B × 24 h TTL | **~42 GB** |
| Rate-limit counters, locks, PIN counters | small, short TTL | < 1 GB |

The idempotency store dominates. In production, shorten its TTL to 1–6 h (the database unique constraint still
protects initiate beyond that window) or store only a response hash plus the payment id. Either way it needs a small
Redis Cluster (3 primaries + replicas).

### Kafka

30 M events/day × ~800 B ≈ 24 GB/day. With 7-day retention and replication factor 3 that is **≈ 500 GB** of disk.
Use 24–48 partitions for `payment.events` so notification and webhook consumers can scale out to that many instances.

### Real-time connections

Up to ~500 k merchants online at peak, each with one SSE connection. At ~10 k connections per node
(servlet async; virtual threads or WebFlux raise this), that is ~50 nodes. That is also the reason for the
Redis Pub/Sub fan-out described in [04](04-components-and-technology-choices.md#43-redis--speed-layer-and-coordination).
