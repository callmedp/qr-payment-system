# 2. System flows

This document walks through every runtime flow end to end. Component names match packages in
`backend/src/main/java/com/qrpay/*`.

- [2.1 Onboarding & authentication](#21-onboarding--authentication)
- [2.2 Merchant generates a dynamic QR](#22-merchant-generates-a-dynamic-qr)
- [2.3 Customer scans & resolves](#23-customer-scans--resolves-the-qr)
- [2.4 Payment: initiate + confirm (the core flow)](#24-payment-initiate--confirm-the-core-flow)
- [2.5 After commit: events, real-time push, webhooks](#25-after-commit-events-real-time-push-webhooks)
- [2.6 Two customers, one dynamic QR (concurrency)](#26-two-customers-one-dynamic-qr-concurrency)
- [2.7 State machines](#27-state-machines)
- [2.8 Failure scenarios & how each is handled](#28-failure-scenarios)

---

## 2.1 Onboarding & authentication

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant
    actor C as Customer
    participant API as Auth API
    participant DB as PostgreSQL

    M->>API: POST /auth/register/merchant (name, email, password, business, MCC)
    API->>DB: BEGIN
    API->>DB: INSERT users (role=MERCHANT, bcrypt password)
    API->>DB: INSERT merchants (vpa=chai-point-3f9a@qrpay, webhook_secret)
    API->>DB: INSERT wallets (owner_type=MERCHANT, balance=0)
    API->>DB: COMMIT
    API-->>M: 201 JWT (sub=userId, role=MERCHANT, mid=merchantId)

    C->>API: POST /auth/register/customer (name, email, password, PIN)
    API->>DB: BEGIN
    API->>DB: INSERT users (role=CUSTOMER)
    API->>DB: INSERT wallets (owner_type=USER, pin_hash=bcrypt(PIN))
    API->>DB: transfer SYSTEM wallet to customer (signup bonus, 2 ledger rows)
    API->>DB: COMMIT
    API-->>C: 201 JWT (sub=userId, role=CUSTOMER)
```

* Everything one registration creates is committed in a **single transaction**, so a user can never exist without a wallet.
* The JWT carries `role` and `merchantId`, so later requests need **no session lookup** and the app tier stays stateless.
* The signup bonus goes through the same `transfer()` as a payment. The SYSTEM wallet goes negative, so the
  sum of all balances stays exactly 0.

---

## 2.2 Merchant generates a dynamic QR

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant (till / dashboard)
    participant QR as QrController / QrService
    participant Codec as QrPayloadCodec
    participant DB as PostgreSQL

    M->>QR: POST /qr-codes/dynamic amount=25000, orderRef=ORD-42, expiresInSeconds=300
    QR->>QR: rate limit "qr-create", role=MERCHANT, merchant ACTIVE, amount within limit
    alt orderRef already exists
        QR->>DB: SELECT by (merchant_id, order_ref)
        QR-->>M: same ACTIVE code if amount matches, else 409 DUPLICATE_ORDER_REF
    end
    QR->>QR: id = UUIDv7, expiresAt = now + 300s
    QR->>Codec: encode(qid)
    Codec-->>QR: qrpay://pay?v=2&qid=..&sig=HMAC
    QR->>DB: INSERT qr_codes (status=ACTIVE)
    QR-->>M: 201 id, payload, expiresAt
    M->>M: draw payload as SVG in the browser (ECC=M)
    Note over M: The till shows the QR with a countdown and<br/>opens an SSE stream to wait for "PAID"
```

**Payload format** - an unguessable pointer plus an integrity tag, nothing more:

```
qrpay://pay?v=2&qid=0199...&sig=Xy3...
```

| Param | Meaning |
|---|---|
| `v` | payload version, which lets the format evolve |
| `qid` | QR id, the pointer to the server-side record |
| `sig` | first 128 bits of HMAC-SHA256 over everything before `&sig=`, base64url |

**Why no payee, amount or expiry in the code.** This is a closed loop: only our own customer app
scans these codes, and it always round-trips `/qr-codes/resolve` before showing anything (there is
no URI parsing in the frontend at all). Carrying self-describing fields would therefore buy nothing
and cost twice: the code gets denser, and anyone who photographs it reads the merchant's VPA,
legal name and order value. Measured with ZXing at ECC-M, dropping them moves a dynamic code from
**204 bytes / QR version 10 / 57x57 modules** to **83 bytes / version 5 / 37x37** - at the fixed
320 px render that is 5.25 -> 7.80 px per module, which is the whole point: a code that scans in
poor light and from further away.

A truncated 128-bit MAC keeps the QR smaller still while remaining infeasible to forge.

> The self-describing shape only earns its bytes if a *third party* must pay without calling our
> API - which is exactly why the real UPI intent URI carries `pa` and `am`. If qrpay ever opens up
> to third-party PSP apps, those fields have to come back and this trade-off flips.

**v1 compatibility.** Codes minted before this change carried `mid`, `pa`, `pn`, `am`, `exp` and
`cu`. `decodeAndVerify` still accepts `v=1` and ignores the extra params, so a static QR printed
and stuck to a counter keeps working indefinitely. Stored payloads are never rewritten - a printed
code must keep matching its row.

---

## 2.3 Customer scans & resolves the QR

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer app
    participant QR as QrService
    participant Codec as QrPayloadCodec
    participant R as Redis
    participant DB as PostgreSQL

    C->>C: decode QR image in browser (html5-qrcode)
    C->>QR: POST /qr-codes/resolve payload
    QR->>Codec: decodeAndVerify(payload)
    alt bad prefix / bad signature
        Codec-->>C: 400 QR_INVALID (no DB or cache hit at all)
    end
    QR->>R: GET qr:{qid}
    alt cache hit
        R-->>QR: QrSnapshot
    else cache miss
        QR->>DB: SELECT qr_codes + merchants
        QR->>R: SET qr:{qid} snapshot EX until expiresAt
    end
    QR->>QR: status ACTIVE? not expired? merchant active?
    QR-->>C: 200 merchantName, vpa, amount, expiresAt
```

* **Everything shown comes from the server snapshot.** The payload carries no amount, payee or expiry
  to disagree with the database, and even a validly signed payload is only a pointer - the signature
  proves we issued this `qid`, not that it is still payable.
* The cache TTL is set to the QR's own expiry, so a dynamic QR's cache entry disappears exactly when it stops being payable.
  On `PAID` the key is evicted **after commit**.

---

## 2.4 Payment: initiate + confirm (the core flow)

A payment has two steps, like UPI "collect → authorise":

1. **Initiate.** Validate and create a `CREATED` payment. This is cheap and moves no money. It gives the customer a stable
   `paymentId` and a TTL while they type their PIN.
2. **Confirm.** Authorise with the PIN. The money moves in one atomic transaction.

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant PC as PaymentController
    participant IS as IdempotencyService
    participant PS as PaymentService
    participant WS as WalletService
    participant L as DistributedLock
    participant PE as PaymentExecutor
    participant R as Redis
    participant DB as PostgreSQL

    rect rgb(235, 242, 255)
    Note over C,DB: Step 1 - initiate
    C->>PC: POST /payments  Idempotency-Key K1  qrId (amount if static)
    PC->>IS: execute(K1)
    IS->>R: SET idem:init:user:K1 IN_PROGRESS NX EX 24h
    IS->>PS: initiate()
    PS->>DB: SELECT payment by (payer, K1), durable idempotency backstop
    PS->>PS: payableSnapshot(qrId), resolve amount (server amount wins)
    PS->>DB: INSERT payments (CREATED, expires_at = min(now+5m, qr.expiresAt))
    IS->>R: SET idem:init:user:K1 COMPLETED + response
    PC-->>C: 201 paymentId, amount, expiresAt
    end

    rect rgb(235, 250, 240)
    Note over C,DB: Step 2 - confirm
    C->>PC: POST /payments/{id}/confirm  Idempotency-Key K2  pin
    PC->>IS: execute(K2) fingerprint = paymentId only, never the PIN
    IS->>PS: confirm()
    PS->>DB: SELECT payment, fast-fail if not CREATED (SUCCESS is returned as-is)
    PS->>WS: verifyPin() BCrypt + Redis failure counter, outside any transaction
    PS->>L: SET lock:qr:{qid} token NX PX 15s
    alt lock busy
        L-->>C: 409 CONCURRENT_MODIFICATION
    end
    PS->>PE: execute(paymentId)
    PE->>DB: BEGIN
    PE->>DB: SELECT payment FOR UPDATE, re-check CREATED and not expired
    PE->>DB: UPDATE qr_codes SET status=PAID WHERE id=? AND status=ACTIVE AND expires_at>now
    Note right of DB: 0 rows means QR_ALREADY_PAID or QR_EXPIRED, rollback
    PE->>DB: SELECT wallets WHERE id IN (payer, merchant) ORDER BY id FOR UPDATE
    PE->>DB: UPDATE payer balance -= amount (CHECK balance >= 0)
    PE->>DB: UPDATE merchant balance += amount
    PE->>DB: INSERT 2 ledger_entries (DEBIT + CREDIT, same transfer_id)
    PE->>DB: UPDATE payments SET status=SUCCESS
    PE->>DB: INSERT outbox_events PAYMENT_SUCCEEDED
    PE->>DB: COMMIT
    PS->>L: release (Lua compare-and-delete)
    PC-->>C: 200 status=SUCCESS
    end
```

**Why each step sits where it does**

| Decision | Reason |
|---|---|
| PIN check **before** the lock and the transaction | BCrypt takes ~70 ms on purpose. Holding a row lock during it would cut throughput on hot wallets. |
| Redis lock **around** the transaction | It stops 50 customers hammering one dynamic QR from all queueing on the same Postgres row lock. The loser gets a fast 409. The lock is an optimisation only: correctness comes from the next three rows. |
| `SELECT … FOR UPDATE` on the payment | Serialises confirm, cancel and the expiry sweeper for the same payment. |
| Compare-and-set on `qr_codes.status` | Exactly one transaction flips `ACTIVE → PAID`. Postgres re-evaluates the `WHERE` after acquiring the row lock. |
| Wallets locked **in id order** | Two concurrent transfers touching the same wallets can never deadlock. |
| Balance `CHECK` constraint | The database refuses a negative balance even if application code has a bug. |
| Outbox insert in the **same** transaction | The event exists if and only if the payment committed (no dual write). |

**Business failures** (insufficient balance, QR already paid or expired) roll the attempt back, then
`PaymentExecutor.markFailed()` records `FAILED` + reason and a `PAYMENT_FAILED` event in a **second short
transaction**. The API returns `200` with `status: FAILED`, because the request itself was valid.
**A wrong PIN** returns `422 INVALID_PIN` and leaves the payment `CREATED`, so the customer can retry until the lockout.

---

## 2.5 After commit: events, real-time push, webhooks

```mermaid
sequenceDiagram
    autonumber
    participant DB as PostgreSQL outbox_events
    participant OR as OutboxRelay (every 300 ms, any node)
    participant K as Kafka payment.events (key = merchantId)
    participant NC as NotificationConsumer (group notification-service)
    participant PS as Redis Pub/Sub rt:user:*
    participant N as App node holding the SSE connection
    actor M as Merchant browser
    participant WD as WebhookDispatcher (group webhook-dispatcher)
    participant MS as Merchant server

    OR->>DB: SELECT PENDING ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
    OR->>K: send(key=merchantId, value=PaymentEvent) acks=all, idempotent producer
    K-->>OR: ack
    OR->>DB: UPDATE status=PUBLISHED, COMMIT

    par notification fan-out
        K->>NC: PaymentEvent
        NC->>PS: PUBLISH rt:user:{merchantUserId}
        NC->>PS: PUBLISH rt:user:{payerUserId}
        PS->>N: message (every node is pattern-subscribed)
        N->>M: SSE event payment
        Note over M: QR flips to PAID, live feed updates
    and webhook delivery
        K->>WD: PaymentEvent
        WD->>WD: skip if webhook:delivered:{eventId} exists
        WD->>MS: POST webhook  X-QrPay-Signature t=..,v1=HMAC
        alt 2xx
            WD->>WD: SET webhook:delivered:{eventId} EX 7d
        else timeout / 5xx / 429
            WD->>K: republish to payment.events-webhook-retry-N (2s, 6s, 18s, 54s, 162s)
            Note over WD,K: after 6 attempts to payment.events-webhook-dlt
        end
    end
```

* The **two consumer groups** read the same topic independently. A slow merchant webhook can never delay a real-time push.
* **Why Redis Pub/Sub after Kafka?** Kafka hands each event to *one* instance of the notification group, but the
  merchant's SSE connection might be held by any instance. Redis Pub/Sub broadcasts to all instances, and whichever one holds
  the connection writes to it.
* **Non-blocking retries.** Failed webhooks go to delay topics instead of blocking the partition, so other merchants'
  webhooks keep flowing.
* **Fallbacks.** The merchant UI polls `GET /qr-codes/{id}` every 4 s whenever the stream is not live, so the till
  never gets stuck.

---

## 2.6 Two customers, one dynamic QR (concurrency)

```mermaid
sequenceDiagram
    autonumber
    actor A as Customer A
    actor B as Customer B
    participant L as Redis lock:qr:{qid}
    participant DB as PostgreSQL

    par
        A->>L: SET NX (wins)
    and
        B->>L: SET NX (loses)
        L-->>B: 409 CONCURRENT_MODIFICATION, retry shortly
    end
    A->>DB: UPDATE qr_codes SET PAID WHERE status=ACTIVE, 1 row
    A->>DB: debit A, credit merchant, COMMIT
    A->>L: release
    B->>L: retry SET NX (wins)
    B->>DB: UPDATE qr_codes SET PAID WHERE status=ACTIVE, 0 rows
    B->>DB: ROLLBACK, then markFailed(QR_ALREADY_PAID)
    B-->>B: 200 status=FAILED reason=QR_ALREADY_PAID
```

Even if Redis were down (the lock fails open), both transactions would reach the compare-and-set and
Postgres would let exactly one of them update the row. As a third line of defence, a partial unique index
`ux_payment_success_per_dynamic_qr` makes a second `SUCCESS` row for the same dynamic QR impossible.
`PaymentFlowIntegrationTest.dynamicQrIsPaidExactlyOnceUnderConcurrency` runs 8 payers concurrently against real
Postgres, Redis and Kafka and asserts exactly one success.

---

## 2.7 State machines

### Payment

```mermaid
stateDiagram-v2
    [*] --> CREATED: initiate
    CREATED --> SUCCESS: confirm OK
    CREATED --> FAILED: insufficient balance / QR taken / QR expired
    CREATED --> CANCELLED: customer cancels
    CREATED --> EXPIRED: pending TTL elapsed (sweeper or confirm)
    CREATED --> CREATED: wrong PIN (attempt counted)
    SUCCESS --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    EXPIRED --> [*]
```

`PaymentStatus.canTransitionTo()` encodes this table, and `Payment.transitionTo()` refuses anything else.
When processing moves to an external PSP, the natural extension is `CREATED → PROCESSING → SUCCESS|FAILED`
driven by the PSP callback.

### QR code

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: create
    ACTIVE --> PAID: dynamic only, first successful payment
    ACTIVE --> EXPIRED: dynamic only, expires_at passed
    ACTIVE --> DISABLED: merchant / ops action
    PAID --> [*]
    EXPIRED --> [*]
```

Static QRs stay `ACTIVE` indefinitely and accept any number of payments.

---

## 2.8 Failure scenarios

| Scenario | What happens | Customer / merchant impact |
|---|---|---|
| Customer double-taps **Pay** | Same `Idempotency-Key`: the 2nd request gets `409 REQUEST_IN_PROGRESS` while the 1st runs, then a replay of the stored response. | Charged once. |
| Network drops after the server committed | App retries with the same key and gets the stored `SUCCESS` response (`Idempotent-Replayed: true`). Even without Redis, `confirm` on a `SUCCESS` payment returns it unchanged. | Charged once, sees success. |
| Insufficient balance | Transaction rolls back (QR claim included), payment → `FAILED`, `PAYMENT_FAILED` event. | Clear error, QR still payable by someone else. |
| Wrong PIN | `422 INVALID_PIN`, counter +1. After 5 failures, `423 PIN_LOCKED` for 15 min. | Can retry. |
| QR expires while the customer types the PIN | Confirm sees `expires_at <= now` in the transaction → `QR_EXPIRED` → `FAILED`. | Merchant generates a new QR. |
| App node crashes mid-transaction | Postgres rolls back the uncommitted transaction. The Redis lock expires after 15 s. | Nothing moved. Retry works. |
| Crash **after commit, before Kafka publish** | The event is safe in `outbox_events` (`PENDING`). Any surviving node's relay publishes it. | Notification a little late, never lost. |
| Crash after publish, before marking `PUBLISHED` | The event is re-published → consumers de-duplicate on `eventId`. | No visible duplicates. |
| **Kafka down** | Payments keep succeeding (Kafka is not in the transaction). Outbox rows accumulate and drain on recovery. The UI polling fallback keeps the till accurate. | Push and webhooks delayed. |
| **Redis down** | Cache → DB reads. Lock, rate limiter and idempotency store fail open to DB guards (row locks, CAS, unique constraints). PIN lockout counter unavailable (alert). | Slower, still correct. |
| **PostgreSQL primary down** | Payments fail fast (`500`) until failover to the synchronous standby (~30 s with Patroni). No committed data lost (RPO 0). | Brief outage of the write path. |
| Merchant webhook endpoint down | Retry topics with backoff over ~4 min, then DLT + alert. The payment itself is unaffected. | Merchant reconciles via API or replays from DLT. |
| Ledger bug or manual DB edit | `LedgerReconciliationJob` flags `net_balance != 0` or mismatched wallets via metrics and error logs. | Ops alerted within 5 min. |
