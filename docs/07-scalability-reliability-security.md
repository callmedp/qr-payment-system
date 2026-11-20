# 7. Scalability, reliability & security

## 7.1 Scaling the application tier

* **Stateless nodes.** JWT auth, Redis-backed idempotency, locks and limits, and Pub/Sub for SSE fan-out mean any node
  serves any request. Scale on CPU (HTTP) and on connection count (SSE).
* **Keep the transaction short.** The PIN BCrypt check, the lock acquisition and QR image rendering all happen outside the
  DB transaction. The transaction is ~6 statements on indexed primary keys.
* **Connection budget.** Nodes × Hikari pool must stay below Postgres `max_connections`. Put PgBouncer (transaction
  pooling) in front when the node count grows.
* **SSE capacity.** Long-lived connections are cheap on async servlets. Split the SSE edge into its own deployment
  ([03 §3.5](03-high-level-design.md#35-deployment-view--from-modular-monolith-to-services)) and move to virtual threads
  (Java 21) or WebFlux for 50k+ connections per node.
* **Kafka consumers** scale up to the partition count. Start with 24–48 partitions for `payment.events`.

## 7.2 Scaling the database

| Stage | Trigger | Action |
|---|---|---|
| 1 | Tables > ~100 GB | Monthly range partitions on `payments`, `ledger_entries`, `qr_codes`. Archive detached partitions. |
| 2 | Read load | Streaming read replicas for history/statement endpoints and analytics. |
| 3 | Hot rows (SYSTEM wallet, mega-merchant) | Split into N sub-wallets. Sweep them periodically. |
| 4 | Write TPS beyond one primary | Shard by wallet id (Citus or app-level). Payer debit stays synchronous and local. Merchant credit becomes asynchronous via events (idempotent on `paymentId`). |
| 5 | Global multi-region | Region-local shards with home-region routing per wallet. Consider a distributed SQL database (Spanner/CockroachDB/Yugabyte). |

## 7.3 Reliability patterns in the code

| Pattern | Where |
|---|---|
| Idempotency keys | `IdempotencyService`, `UNIQUE(payer, key)` |
| Pessimistic locking with lock ordering | `WalletRepository.lockByIdsInOrder` |
| Compare-and-set | `QrCodeRepository.markPaidIfActive` |
| Transactional outbox | `OutboxWriter`, `OutboxRelay` |
| At-least-once + idempotent consumers | `WebhookDispatcher` (Redis marker), UI de-dup |
| Non-blocking retry + DLT | `@RetryableTopic` on `WebhookDispatcher` |
| Graceful degradation (fail-open infra) | `DistributedLock`, `RedisRateLimiter`, `IdempotencyService`, `QrCache` |
| Timeouts everywhere | Redis 500 ms, Hikari 3 s, Kafka request 5 s / delivery 10 s, webhook 2 s / 5 s |
| Singleton jobs on a multi-node fleet | Redis lock around `ExpirySweeper`, `LedgerReconciliationJob` |
| Continuous verification | `LedgerReconciliationJob` gauges |
| Graceful shutdown | `server.shutdown=graceful`, so in-flight payments finish before the pod stops |

## 7.4 Observability

* **Health:** `/actuator/health/liveness`, `/actuator/health/readiness` (Kubernetes probes; readiness includes DB and Redis).
* **Metrics:** `/actuator/prometheus`: HTTP latency histograms per endpoint, Hikari pool usage, Kafka consumer lag,
  JVM, plus `qrpay_ledger_net_balance` and `qrpay_ledger_mismatched_wallets`.
* **Suggested alerts:** ledger gauges ≠ 0 (page immediately), confirm p99 > 500 ms, `outbox_events` PENDING count or age
  growing, webhook DLT rate > 0, consumer lag > 30 s, `PIN_LOCKED` spike (credential stuffing), 5xx rate.
* **Logs:** every line carries `requestId` (`RequestIdFilter`). Payment success/failure is logged with the payment id.
* **Next:** OpenTelemetry tracing across HTTP → DB → Kafka → consumer (Micrometer Tracing), propagating the trace id in Kafka headers.

## 7.5 Security

| Threat | Mitigation |
|---|---|
| Forged / tampered QR (e.g. a sticker pasted over the real one, or an edited amount) | HMAC-signed payload verified before any lookup. The server-side amount is authoritative. The resolve screen shows the **verified** merchant name and VPA so a customer can spot a swapped sticker. |
| QR id enumeration | Ids are unguessable (UUIDv7 random bits) and `/resolve` requires a valid signature anyway. Note `POST /payments` takes a bare `qrId` and does **not** re-verify the MAC, so the id itself must stay secret. |
| Replay of an old dynamic QR | Single-use + expiry, enforced in the transaction. |
| Double charge via retries | Idempotency keys + state machine. |
| PIN brute force | BCrypt hash, 5 attempts / 15 min lockout, per-user rate limit on payment endpoints. The PIN is never logged, stored in plain text or fingerprinted. |
| Credential stuffing | BCrypt, `auth` rate-limit bucket per IP, identical error for a wrong email or a wrong password. |
| Token theft | Short-lived JWT (12 h demo, use 15 min + refresh tokens in production), HTTPS only, token never placed in URLs (SSE uses a header). |
| Horizontal privilege escalation | Every query is scoped to the principal (`payer_user_id` / `merchant_id` from the JWT). Foreign resources return `404`, not `403`, to avoid existence leaks. |
| Vertical privilege escalation | `@PreAuthorize` roles on every merchant- and customer-only endpoint. |
| Webhook spoofing / replay | HMAC with timestamp, 5-minute tolerance, constant-time compare. |
| Leaked webhook secret | Merchant-initiated rotation (`POST /merchants/me/webhook/secret`). The old secret stays valid for a grace window (default 24 h) and deliveries are signed with both, so rotating costs no lost events. Rate-limited under the `auth` bucket. |
| SSRF via webhook URL | Production: resolve DNS and block private/loopback/link-local ranges, allow only `https`. (The local dev sink is the exception.) |
| Secrets in code | All secrets come from env vars (`JWT_SECRET`, `QR_SIGNING_SECRET`, DB credentials). Production: KMS / Vault with rotation. |
| SQL injection | JPA parameter binding only, no string-built SQL. |
| Ledger tampering | Append-only table, `REVOKE UPDATE/DELETE` in production, reconciliation alerts. |
| Abuse / DoS | Gateway/WAF limits + application rate limits per bucket. |

## 7.6 Production checklist (beyond this repo)

- TLS termination at the LB, HSTS, mTLS between services if split.
- Replace self-issued JWT with an IdP (OIDC), RS256 + JWKS rotation, refresh tokens.
- Postgres HA (Patroni / managed Multi-AZ), PITR backups, and restore drills.
- Kafka: 3+ brokers, RF=3, `min.insync.replicas=2`, topic ACLs.
- Redis: replicas + failover, `maxmemory` with a TTL-aware eviction policy.
- Debezium CDC instead of polling the outbox.
- `webhook_deliveries` table with a replay UI. Alert on DLT.
- PCI-DSS / RBI data-localisation review if connected to real rails.
