# QrPay: QR code payment system

A system-design reference implementation of a **QR-code based payment flow**, built with
**Spring Boot 3 (Java 17)** and **React + TypeScript**, and driven by **Gradle**.

Merchants generate HMAC-signed QR codes. Customers scan them and confirm with a PIN. Money moves atomically through a
double-entry ledger in **PostgreSQL**. **Kafka** carries payment events to real-time notifications (**Redis** Pub/Sub →
Server-Sent Events) and signed merchant webhooks with non-blocking retries.

```
Customer ──scan──► resolve (Redis cache) ──► initiate ──► confirm with PIN
                                                      │
                          one Postgres transaction:   ▼
          claim QR ─► debit payer ─► credit merchant ─► ledger x2 ─► SUCCESS ─► outbox
                                                                                  │
               Kafka payment.events ◄──────────── OutboxRelay ◄───────────────────┘
                 ├─► notification-service ─► Redis Pub/Sub ─► SSE ─► merchant screen flips to PAID
                 └─► webhook-dispatcher ───► signed POST to merchant server (retry topics + DLT)
```

## Documentation

| # | Document | What's inside |
|---|---|---|
| 1 | [Requirements](docs/01-requirements.md) | Functional & non-functional requirements, capacity estimation |
| 2 | [System flows](docs/02-system-flows.md) | Sequence diagrams for every flow, state machines, failure scenarios |
| 3 | [High-level design](docs/03-high-level-design.md) | Architecture & module diagrams, read/write paths, monolith → services, key decisions |
| 4 | [Components & technology choices](docs/04-components-and-technology-choices.md) | **Why** PostgreSQL, Redis, Kafka, outbox, SSE, webhooks, QR signing, Spring, React, and exactly how each is used |
| 5 | [API design](docs/05-api-design.md) | Conventions, error model, idempotency contract, every endpoint with examples, webhook contract |
| 6 | [Data model](docs/06-data-model.md) | ER diagram, table-by-table rationale, invariants, partitioning |
| 7 | [Scalability, reliability & security](docs/07-scalability-reliability-security.md) | Scaling stages, reliability patterns, observability, threat model |

Swagger UI (live, generated from code): <http://localhost:8080/swagger-ui.html>

## Project layout

```
qr-payment-system/
├── settings.gradle.kts / build.gradle.kts   Gradle multi-project (:backend, :frontend)
├── docker-compose.yml                       Postgres 16, Redis 7.4, Kafka 3.9 (KRaft), Kafka UI
├── docs/                                    System design documentation
├── backend/                                 Spring Boot service
│   └── src/main/java/com/qrpay/
│       ├── auth/  security/                 registration, login, JWT, roles
│       ├── merchant/                        merchant profile, VPA, webhook settings
│       ├── qr/                              QR create/sign/render/resolve/claim, Redis cache
│       ├── payment/                         orchestrator, transactional executor, expiry sweeper
│       ├── wallet/                          wallets, double-entry ledger, PIN, reconciliation
│       ├── outbox/  events/                 transactional outbox + Kafka relay, event contract
│       ├── notification/                    Kafka consumer → Redis Pub/Sub → SSE
│       ├── webhook/                         signed webhooks, retry topics, DLT, dev sink
│       ├── common/                          errors, idempotency, rate limit, distributed lock, ids
│       └── config/                          typed properties, Kafka, OpenAPI, MVC
│   └── src/main/resources/db/migration/     Flyway schema
└── frontend/                                React + TS (Vite), built through Gradle
    └── src/
        ├── api/                             typed client, DTO types, endpoints
        ├── hooks/usePaymentStream.ts        SSE over fetch (JWT in header), reconnect, de-dup
        ├── components/QrScanner.tsx         camera / image upload / paste
        └── pages/customer, pages/merchant   wallet, scan & pay, merchant dashboard
```

## Running locally

**Prerequisites:** JDK 17+, Node 20+ (or pass `-PnodeDownload=true` and Gradle downloads it), Docker.

```bash
# 1. infrastructure
docker compose up -d
# something else already on 6379/5432/9092? e.g.  REDIS_PORT=6380 docker compose up -d

# 2. backend on :8080 (Flyway creates the schema on start)
./gradlew :backend:bootRun
# if you changed ports:  REDIS_PORT=6380 ./gradlew :backend:bootRun

# 3. frontend on :5173 (proxies /api to :8080)
./gradlew :frontend:npmDev        # or: cd frontend && npm install && npm run dev
```

Open <http://localhost:5173>:

1. Register a **merchant** in one browser profile. Generate a dynamic QR for ₹250.
2. Register a **customer** in another profile or a private window (it gets ₹10,000 demo balance).
3. **Scan & Pay** → upload a screenshot of the QR (or use the camera, or paste the payload from "QR payload").
4. Review → enter your PIN → the merchant's QR flips to **PAID** instantly and the live feed updates.
5. In the merchant dashboard click **Use local dev sink** under *Webhooks* to watch signed webhooks arrive.

Other tools: Kafka UI <http://localhost:8090> (see `payment.events`, retry topics, consumer groups),
metrics at `/actuator/prometheus`, health at `/actuator/health`.

## Building & testing

```bash
./gradlew buildAll                 # backend jar + frontend bundle (frontend/dist)
./gradlew :backend:test            # unit tests
./gradlew :backend:test -Pit       # + integration tests (Testcontainers: Postgres, Redis, Kafka)
```

The integration suite covers the end-to-end payment, **8 customers racing for one dynamic QR (exactly one wins)**,
insufficient balance (rollback of the QR claim), wrong PIN, and static-QR multi-payments.

> **Colima / non-default Docker socket:** Testcontainers looks for `/var/run/docker.sock`. With Colima run:
> ```bash
> export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
> ```

## Configuration

All settings are in [`application.properties`](backend/src/main/resources/application.properties) under `qrpay.*`. Override them with env vars:

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | local compose | PostgreSQL |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Redis |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `JWT_SECRET` | dev key | base64, ≥ 256-bit. **Change outside local dev.** |
| `QR_SIGNING_SECRET` | dev key | base64 HMAC key for QR payloads. **Change outside local dev.** |
| `CORS_ORIGINS` | `http://localhost:5173` | allowed browser origins |
