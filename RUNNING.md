# Running the application locally

Practical startup guide for the QR payment system. For architecture and design rationale see
[`README.md`](README.md) and [`docs/`](docs/).

---

## 1. Prerequisites

| Requirement | Version | Check with |
|---|---|---|
| JDK | 17+ | `java -version` |
| Node | 20+ | `node --version` |
| Docker | any recent | `docker --version` |
| Docker daemon | running | `docker info` |

Node is only needed for the frontend. Pass `-PnodeDownload=true` and Gradle will fetch its own copy.

### Docker runtime on macOS

There is no Docker daemon without either Docker Desktop or a VM manager. If `docker info` fails but
`docker --version` works, the CLI is installed and the daemon is not running.

```bash
colima start          # Colima
# or just launch Docker Desktop
```

Verify before continuing:

```bash
docker info >/dev/null && echo "daemon up"
```

---

## 2. Start the infrastructure

`docker-compose.yml` provides **dependencies only** — there is no backend or frontend service in it. The
app itself runs on the host via Gradle.

```bash
docker compose up -d
```

| Container | Image | Host port |
|---|---|---|
| `qrpay-postgres` | `postgres:16-alpine` | 5432 |
| `qrpay-redis` | `redis:7.4-alpine` | 6379 |
| `qrpay-kafka` | `apache/kafka:3.9.1` (single-node KRaft) | 9092 |
| `qrpay-kafka-ui` | `provectuslabs/kafka-ui` | 8090 |

Wait for health checks — Kafka is the slowest (~20-30 s):

```bash
docker compose ps
# or block until ready:
until [ "$(docker inspect -f '{{.State.Health.Status}}' qrpay-kafka)" = "healthy" ]; do sleep 2; done
```

### Port already in use

All three host ports are overridable in `docker-compose.yml` via `POSTGRES_PORT`, `REDIS_PORT` and
`KAFKA_PORT`. **Only `REDIS_PORT` is also read by the application**, so it is the one case where a single
variable moves both sides:

```bash
REDIS_PORT=6380 docker compose up -d
REDIS_PORT=6380 ./gradlew :backend:bootRun    # same variable, both sides
```

For Postgres and Kafka the app uses a different variable than compose does, so you must set both:

| Moving | Compose side | Backend side |
|---|---|---|
| Redis | `REDIS_PORT=6380` | `REDIS_PORT=6380` |
| Postgres | `POSTGRES_PORT=5433` | `DB_URL=jdbc:postgresql://localhost:5433/qrpay` |
| Kafka | `KAFKA_PORT=9093` | `KAFKA_BOOTSTRAP=localhost:9093` |

Forgetting the backend half is a common mistake: the containers start fine and the app then fails to
connect, pointing at the original port.

A common case on macOS is a native `redis-server` already holding 6379 (`brew services list`). Either shift
the container to 6380 as above, stop the native one with `brew services stop redis`, or skip the container
entirely and let the app use the Redis you already have:

```bash
docker compose up -d postgres kafka kafka-ui   # no redis service
./gradlew :backend:bootRun                     # defaults to localhost:6379
```

Check what is holding a port:

```bash
lsof -nP -iTCP -sTCP:LISTEN | grep -E ':(5432|6379|9092|8080)\b'
```

---

## 3. Start the backend

```bash
./gradlew :backend:bootRun
```

Listens on **:8080**. Takes roughly 5 seconds once Gradle has warmed up.

On startup it will:

1. Run **Flyway** `V1__init_schema.sql` — 7 tables, 12 indexes
2. Run Hibernate `ddl-auto: validate` — any entity/schema drift fails the boot rather than silently
   corrupting data
3. Create the **`payment.events`** topic (6 partitions) from the `NewTopic` bean in
   `config/KafkaConfig.java`. The broker has auto-create disabled, so this bean is the only thing that
   creates it. `@RetryableTopic` additionally provisions 5 retry topics plus a DLT
4. Start the schedulers: outbox relay (300 ms), expiry sweeper (15 s), ledger reconciliation (5 min)

---

## 4. Start the frontend (optional)

```bash
./gradlew :frontend:npmDev
# or: cd frontend && npm install && npm run dev
```

Serves **:5173** and proxies `/api` to :8080. Open <http://localhost:5173>.

---

## 5. Verify

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}
```

Confirm the schema landed:

```bash
docker compose exec postgres psql -U qrpay -d qrpay -c '\dt'
```

Expect `users`, `merchants`, `wallets`, `qr_codes`, `payments`, `ledger_entries`, `outbox_events`
plus `flyway_schema_history`.

Confirm the topics exist:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Exercise the write path end to end — this inserts a user, opens a PIN-protected wallet, and moves the
demo signup bonus out of the SYSTEM wallet as a double-entry pair:

```bash
curl -s -X POST localhost:8080/api/v1/auth/register/customer \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Test User","email":"test@example.com","password":"secret123","pin":"1234"}'
```

`HTTP 201` plus a JWT means the whole stack works.

### Check the money invariant

The ledger is closed-loop: every wallet balance summed together must be exactly zero, because all money
originates from the SYSTEM wallet (which is allowed to go negative).

```bash
docker compose exec postgres psql -U qrpay -d qrpay \
  -c "select coalesce(sum(balance),0) as must_be_zero from wallets;"
```

`LedgerReconciliationJob` checks this every 5 minutes and exports it as a metric. **Alert on either gauge
being non-zero in production.**

---

## 6. Useful endpoints

| URL | Notes |
|---|---|
| <http://localhost:8080/swagger-ui.html> | API explorer |
| <http://localhost:8080/actuator/health> | liveness + readiness, no auth |
| <http://localhost:8080/actuator/prometheus> | **requires a Bearer token** — it sits behind `anyRequest().authenticated()` |
| <http://localhost:8090> | Kafka UI — inspect `payment.events`, retry topics, consumer groups |

Reading metrics needs a token:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"secret123"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/prometheus | grep qrpay_ledger
```

---

## 7. Demo walkthrough

1. Register a **merchant** in one browser profile. Generate a dynamic QR for ₹250.
2. Register a **customer** in another profile or a private window (starts with ₹10,000 demo balance).
3. **Scan & Pay** — upload a screenshot of the QR, use the camera, or paste the payload.
4. Review, enter the PIN. The merchant's QR flips to **PAID** instantly over SSE.
5. In the merchant dashboard, click **Use local dev sink** under *Webhooks* to watch signed webhooks land.

---

## 8. Tests

```bash
./gradlew :backend:test        # unit tests only
./gradlew :backend:test -Pit   # + Testcontainers integration suite
```

> Integration tests are **excluded by default**. Without `-Pit` you run 4 pure-function unit test files and
> no service, persistence, or HTTP code is touched.

The integration suite spins up its own Postgres, Redis and Kafka containers — it does not use the
`docker compose` stack, so those can be running or not.

**Colima users:** Testcontainers looks for `/var/run/docker.sock`, which Colima does not provide.

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

---

## 9. Stopping and resetting

```bash
docker compose down        # stop containers, KEEP the database
docker compose down -v     # also drop the pgdata volume — wipes all data
colima stop                # shut the VM down too
```

The `pgdata` volume **survives `docker compose down`**. Users, merchants and payments from earlier sessions
will still be there on the next start. Use `-v` when you want a genuinely clean slate.

---

## 10. Configuration

Everything lives under `qrpay.*` in
[`backend/src/main/resources/application.properties`](backend/src/main/resources/application.properties) and is
overridable by environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | 8080 | backend HTTP port |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `localhost:5432` / `qrpay` / `qrpay` | Postgres |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / 6379 | Redis |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka |
| `JWT_SECRET` | dev-only base64 default | **must be overridden outside local dev** |
| `QR_SIGNING_SECRET` | dev-only base64 default | **must be overridden outside local dev** |
| `CORS_ORIGINS` | `http://localhost:5173` | allowed browser origins |

> **The committed defaults for `JWT_SECRET` and `QR_SIGNING_SECRET` are public dev placeholders.** Anyone
> with the repo can forge tokens and QR payloads. Override both in every non-local environment.

Two more local-only defaults worth knowing: `qrpay.webhook.dev-sink-enabled` is `true`, which exposes an
**unauthenticated** `/api/v1/dev/**` endpoint, and `qrpay.demo.signup-bonus` credits every new customer
₹10,000. Both should be off outside local development.

---

## 11. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Cannot connect to the Docker daemon` | Daemon not running — `colima start` or open Docker Desktop |
| `port is already allocated` | Something else holds the port — use `REDIS_PORT` / `POSTGRES_PORT` / `KAFKA_PORT`, and pass the same value to `bootRun` |
| Boot fails on schema validation | Entity/migration drift. `docker compose down -v` for a clean rebuild |
| App starts but Kafka logs reconnect loops | Kafka was not healthy yet — wait for the health check, then restart the app |
| Old users/payments appear on a fresh start | The `pgdata` volume persisted. `docker compose down -v` |
| Testcontainers cannot find Docker | Export the two Colima variables in §8 |
| Redis down while running | By design the app **fails open** — caching, locks, idempotency and rate limits degrade but money stays correct. The one real loss is the PIN brute-force guard |
