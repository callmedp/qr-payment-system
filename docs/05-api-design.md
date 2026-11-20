# 5. API design

- Base URL: `http://localhost:8080/api/v1`
- Live OpenAPI spec: **`/v3/api-docs`**, Swagger UI: **`/swagger-ui.html`** (generated from the controllers, always in sync)
- Style: resource-oriented REST, JSON, versioned in the path (`/v1`), so breaking changes ship as `/v2` side by side.

---

## 5.1 Conventions

| Topic | Convention |
|---|---|
| **Auth** | `Authorization: Bearer <JWT>` on everything except register/login. Roles: `CUSTOMER`, `MERCHANT`. |
| **Money** | Integers in **minor units** (paise) + ISO-4217 `currency`. `25000` = ₹250.00. Never floats. |
| **IDs** | UUIDv7 strings. |
| **Timestamps** | ISO-8601 UTC, e.g. `2026-09-11T10:15:30.123Z`. |
| **Idempotency** | `Idempotency-Key: <1-64 chars>` is **required** on `POST /payments`, `POST /payments/{id}/confirm`, `POST /wallets/me/top-up`. See 5.3. |
| **Pagination** | `?page=0&size=20` (max 100) → `{ items, page, size, totalItems, totalPages }` |
| **Tracing** | Send `X-Request-Id` (or the server generates one). It is echoed back and included in every log line and error body. |
| **Rate limits** | Per user (or per IP when anonymous). Headers `X-RateLimit-Limit`, `X-RateLimit-Remaining`. `429` includes `Retry-After`. |

### Error format

Every error uses the same envelope. Clients switch on `code` (stable), never on `message` (human-readable).

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "Insufficient wallet balance",
  "details": [ { "field": "amount", "message": "must be greater than 0" } ],
  "requestId": "9d7c2f1e-…",
  "timestamp": "2026-09-11T10:15:30.123Z"
}
```

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean-validation failure (`details` lists fields), malformed JSON |
| 400 | `QR_INVALID` | Payload not ours / tampered / bad signature |
| 400 | `AMOUNT_REQUIRED`, `AMOUNT_MISMATCH`, `AMOUNT_LIMIT_EXCEEDED`, `INVALID_EXPIRY` | Amount / expiry rules |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | Missing / oversized key |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS` | No/invalid/expired token, bad login |
| 403 | `FORBIDDEN` | Wrong role (e.g. a merchant calling `/payments`) |
| 404 | `NOT_FOUND` | Missing resource **or** a resource owned by someone else (no existence leaks) |
| 409 | `EMAIL_ALREADY_REGISTERED`, `DUPLICATE_ORDER_REF`, `QR_ALREADY_PAID`, `QR_INACTIVE`, `PAYMENT_INVALID_STATE`, `REQUEST_IN_PROGRESS`, `CONCURRENT_MODIFICATION`, `CONFLICT` | State conflicts. `REQUEST_IN_PROGRESS` / `CONCURRENT_MODIFICATION` are safe to retry. |
| 410 | `QR_EXPIRED`, `PAYMENT_EXPIRED` | Past expiry |
| 422 | `INVALID_PIN`, `INSUFFICIENT_BALANCE`, `IDEMPOTENCY_KEY_REUSED` | Semantically invalid |
| 423 | `PIN_LOCKED` | Too many wrong PINs, locked for 15 min |
| 429 | `RATE_LIMITED` | Slow down, see `Retry-After` |
| 500 | `INTERNAL_ERROR` | Bug. Report the `requestId`. |

---

## 5.2 Endpoint catalogue

| # | Method & path | Role | Idempotency-Key | Rate bucket | Purpose |
|---|---|---|---|---|---|
| 1 | `POST /auth/register/customer` | public | – | `auth` | Create customer + PIN wallet |
| 2 | `POST /auth/register/merchant` | public | – | `auth` | Onboard merchant + settlement wallet |
| 3 | `POST /auth/login` | public | – | `auth` | Get JWT |
| 4 | `GET /auth/me` | any | – | – | Current user |
| 5 | `GET /merchants/me` | MERCHANT | – | – | Profile, VPA, webhook settings |
| 6 | `PUT /merchants/me/webhook` | MERCHANT | – | – | Set or clear webhook URL |
| 6a | `GET /merchants/me/webhook/secret` | MERCHANT | – | – | Reveal the signing secret |
| 6b | `POST /merchants/me/webhook/secret` | MERCHANT | – | `auth` | Rotate it (old one kept in grace) |
| 7 | `POST /qr-codes/dynamic` | MERCHANT | via `orderRef` | `qr-create` | Single-use fixed-amount QR |
| 8 | `GET /qr-codes/static` | MERCHANT | – | – | Get or create the reusable QR |
| 9 | `GET /qr-codes` | MERCHANT | – | – | Recent dynamic QRs |
| 10 | `GET /qr-codes/{id}` | MERCHANT | – | – | QR status (polling fallback) |
| 11 | `POST /qr-codes/resolve` | CUSTOMER | – | `qr-resolve` | Scan → payee + amount |
| 12 | `POST /payments` | CUSTOMER | **required** | `payments` | Initiate payment |
| 13 | `POST /payments/{id}/confirm` | CUSTOMER | **required** | `payments` | Authorise with PIN, moves money |
| 14 | `POST /payments/{id}/cancel` | CUSTOMER | – | – | Abandon unconfirmed payment |
| 15 | `GET /payments/{id}` | payer or merchant | – | – | Payment details |
| 16 | `GET /payments` | any | – | – | History (sent for customers, received for merchants) |
| 17 | `GET /wallets/me` | any | – | – | Balance |
| 18 | `GET /wallets/me/ledger` | any | – | – | Statement |
| 19 | `POST /wallets/me/top-up` | CUSTOMER | **required** | – | Demo top-up |
| 20 | `GET /notifications/stream` | any | – | – | SSE stream of payment events |
| 21 | `POST /dev/webhook-sink`, `GET /dev/webhook-sink` | public (dev only) | – | – | Local webhook receiver |

---

## 5.3 Idempotency contract

```
POST /api/v1/payments
Idempotency-Key: 7c0a3b0e-6a0f-4c52-9d7e-3f0b1e4a9c11
```

| Situation | Response |
|---|---|
| First request with key K | Executed normally. `Idempotent-Replayed: false` |
| Same K, same body, first one finished | **Stored response replayed**, no side effects. `Idempotent-Replayed: true` |
| Same K while the first is still running | `409 REQUEST_IN_PROGRESS`. Retry after a moment. |
| Same K, **different** body | `422 IDEMPOTENCY_KEY_REUSED` |
| First attempt failed with an error | Key released. The same K may be retried. |

* Keys are scoped **per user and per operation** (`idem:{scope}:{userId}:{key}`) and kept for 24 h.
* For `confirm`, the request fingerprint is the `paymentId` only. **The PIN is never hashed or stored.**
* Defense in depth: `payments` has `UNIQUE(payer_user_id, idempotency_key)`, and `confirm` on an already `SUCCESS` payment
  returns it unchanged, so retries are safe even if Redis lost the key.
* **Client rule:** generate one key per *user intention* (e.g. per tap of "Pay"), and reuse it for every retry of that
  intention.

---

## 5.4 Endpoints in detail

### Auth

**`POST /auth/register/customer`** → `201`

```json
// request
{ "fullName": "Ravi Kumar", "email": "ravi@example.com", "password": "correct-horse", "pin": "1234" }
// response (AuthResponse)
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9…",
  "tokenType": "Bearer",
  "expiresAt": "2026-09-11T22:15:30Z",
  "user": { "id": "0199…", "email": "ravi@example.com", "fullName": "Ravi Kumar", "role": "CUSTOMER", "merchantId": null }
}
```
Validation: `password` 8–72 chars, `pin` `\d{4,6}`. Errors: `409 EMAIL_ALREADY_REGISTERED`.

**`POST /auth/register/merchant`** → `201 AuthResponse` (with `merchantId`)

```json
{ "fullName": "Mira Shah", "email": "mira@chaipoint.in", "password": "correct-horse",
  "businessName": "Chai Point", "categoryCode": "5812" }
```

**`POST /auth/login`** → `200 AuthResponse`. Body `{ "email", "password" }`. Errors: `401 INVALID_CREDENTIALS` (the same
error whether the email or the password is wrong).

---

### QR codes (merchant)

**`POST /qr-codes/dynamic`** → `201 QrView`

```json
// request
{ "amount": 25000, "orderRef": "ORD-42", "expiresInSeconds": 300 }
// response
{
  "id": "0199b0c4-…",
  "type": "DYNAMIC",
  "status": "ACTIVE",
  "amount": 25000,
  "currency": "INR",
  "orderRef": "ORD-42",
  "payload": "qrpay://pay?v=2&qid=0199b0c4-…&sig=q1w2e3…",
  "expiresAt": "2026-09-11T10:20:30Z",
  "paidPaymentId": null,
  "createdAt": "2026-09-11T10:15:30Z"
}
```

The response carries the signed `payload` but no image: clients draw it themselves (the web app
renders an SVG with ECC level M). The backend has no image endpoint.

| Field | Rule |
|---|---|
| `amount` | > 0, ≤ `qrpay.payment.max-amount` (₹1,00,000) |
| `orderRef` | optional, ≤ 64, `[A-Za-z0-9._-]`, unique per merchant. Same ref + amount while ACTIVE → the existing code is returned (retry-safe). |
| `expiresInSeconds` | 30–1800, default 300 |

The pre-check that makes this idempotent is a read, so it is not atomic against a *simultaneous* first
call with the same ref: one of the two loses `ux_qr_order_ref` and gets `409 CONFLICT` ("… is being
created, retry"). It is deliberately not `DUPLICATE_ORDER_REF` — the loser may simply be a concurrent
retry, and inside a rollback-only transaction the two are indistinguishable. Retrying settles it:
the pre-check then returns the existing code, or raises `DUPLICATE_ORDER_REF` if the amount differs.

Errors: `409 DUPLICATE_ORDER_REF`, `409 CONFLICT` (concurrent create with the same `orderRef`; retry),
`400 AMOUNT_LIMIT_EXCEEDED`, `400 INVALID_EXPIRY`, `403 FORBIDDEN` (suspended merchant).

**`GET /qr-codes/static`** → `200 QrView` (`type: STATIC`, `amount: null`, `expiresAt: null`)

**`GET /qr-codes/{id}`** → `200 QrView`, e.g. to poll `status` until `PAID`.

---

### QR resolve (customer)

**`POST /qr-codes/resolve`** → `200 ResolvedQr`

```json
// request
{ "payload": "qrpay://pay?v=2&qid=…&sig=…" }
// response
{
  "qrId": "0199b0c4-…",
  "type": "DYNAMIC",
  "merchantName": "Chai Point",
  "merchantVpa": "chai-point-3f9a@qrpay",
  "amount": 25000,
  "currency": "INR",
  "orderRef": "ORD-42",
  "expiresAt": "2026-09-11T10:20:30Z"
}
```
Errors: `400 QR_INVALID`, `409 QR_ALREADY_PAID`, `410 QR_EXPIRED`, `409 QR_INACTIVE`, `404 NOT_FOUND`.

---

### Payments (customer)

**`POST /payments`** (Idempotency-Key required) → `201 PaymentView` (`200` + `Idempotent-Replayed: true` on replay)

```json
// request - dynamic QR (amount omitted or equal)
{ "qrId": "0199b0c4-…", "note": "2 cutting chai" }
// request - static QR (amount required)
{ "qrId": "0199a111-…", "amount": 12000 }
// response
{
  "id": "0199b0c9-…",
  "qrId": "0199b0c4-…",
  "qrType": "DYNAMIC",
  "merchantId": "0199a0f2-…",
  "merchantName": "Chai Point",
  "payerName": "Ravi Kumar",
  "amount": 25000,
  "currency": "INR",
  "status": "CREATED",
  "failureReason": null,
  "orderRef": "ORD-42",
  "note": "2 cutting chai",
  "createdAt": "2026-09-11T10:16:02Z",
  "expiresAt": "2026-09-11T10:20:30Z",
  "completedAt": null
}
```
Errors: `400 AMOUNT_REQUIRED | AMOUNT_MISMATCH | AMOUNT_LIMIT_EXCEEDED`, and the QR errors listed under resolve.

**`POST /payments/{id}/confirm`** (Idempotency-Key required) → `200 PaymentView`

```json
// request
{ "pin": "1234" }
// response - success
{ "id": "0199b0c9-…", "status": "SUCCESS", "completedAt": "2026-09-11T10:16:09Z", … }
// response - business failure (still HTTP 200: the request was valid, the payment failed)
{ "id": "0199b0c9-…", "status": "FAILED", "failureReason": "INSUFFICIENT_BALANCE", … }
```

| Outcome | HTTP | Payment status afterwards |
|---|---|---|
| Money moved | 200 `SUCCESS` | SUCCESS |
| Not enough balance / QR taken / QR expired / payment expired | 200 `FAILED` or `EXPIRED` + reason | FAILED / EXPIRED |
| Wrong PIN | 422 `INVALID_PIN` (`"Incorrect PIN, 3 attempt(s) left"`) | still CREATED |
| Locked out | 423 `PIN_LOCKED` | still CREATED |
| Someone else is confirming this dynamic QR right now | 409 `CONCURRENT_MODIFICATION` (retry) | still CREATED |
| Already cancelled/failed/expired | 409 `PAYMENT_INVALID_STATE` | unchanged |
| Already succeeded | 200 `SUCCESS` (no-op) | SUCCESS |

**`POST /payments/{id}/cancel`** → `200 PaymentView` (`CANCELLED`); `409 PAYMENT_INVALID_STATE` if not `CREATED`.

**`GET /payments?page=0&size=20`** → `200 Page<PaymentView>`: what a customer paid, or what a merchant received.

---

### Wallet

**`GET /wallets/me`** → `{ "id", "ownerType": "USER|MERCHANT", "balance": 975000, "currency": "INR", "updatedAt" }`

**`GET /wallets/me/ledger?page=0&size=20`** → `Page<LedgerEntryView>`

```json
{ "items": [ { "id": "…", "entryType": "DEBIT", "amount": 25000, "balanceAfter": 975000,
               "description": "QR payment to Chai Point", "paymentId": "0199b0c9-…", "createdAt": "…" } ],
  "page": 0, "size": 20, "totalItems": 2, "totalPages": 1 }
```

**`POST /wallets/me/top-up`** (Idempotency-Key required) → `{ "amount": 50000 }` (100 ≤ amount ≤ 1,00,00,000) → `WalletView`

---

### Merchant

**`GET /merchants/me`** → `{ "id", "businessName", "vpa", "categoryCode", "status", "webhookUrl", "createdAt" }`

The signing secret is deliberately **not** in the profile payload — it is fetched only when asked for.

**`PUT /merchants/me/webhook`** → body `{ "url": "https://pos.example.com/hooks/qrpay" }` or `{ "url": null }` to remove.

**`GET /merchants/me/webhook/secret`** → `{ "webhookSecret": "whsec_…", "previousSecretExpiresAt": null }`

**`POST /merchants/me/webhook/secret`** → rotates, returning the new secret and when the old one dies:

```json
{ "webhookSecret": "whsec_3748b9cd…", "previousSecretExpiresAt": "2026-09-15T06:10:07Z" }
```

Until that moment every delivery is signed with **both** secrets (two `v1` parts), so a receiver that has
not yet been reconfigured keeps verifying and no event is lost. Window: `qrpay.webhook.secret-grace-period`
(default 24 h).

---

### Real-time stream

**`GET /notifications/stream`** with `Accept: text/event-stream` and `Authorization: Bearer …`

```
event:connected
data:{"at":"2026-09-11T10:15:00Z"}

:hb

id:0199b0ca-…
event:payment
data:{"eventId":"0199b0ca-…","eventType":"PAYMENT_SUCCEEDED","paymentId":"0199b0c9-…","status":"SUCCESS",
      "failureReason":null,"qrId":"0199b0c4-…","orderRef":"ORD-42","amount":25000,"currency":"INR",
      "merchantName":"Chai Point","payerName":"Ravi Kumar","occurredAt":"2026-09-11T10:16:09Z"}
```
Both the payer and the merchant receive the event. `:hb` heartbeats arrive every 20 s. Delivery is at-least-once, so
clients de-dup on `eventId`.

---

## 5.5 Webhook contract (outbound, to the merchant's server)

```
POST https://pos.example.com/hooks/qrpay
Content-Type: application/json
X-QrPay-Event-Id: 0199b0ca-…
X-QrPay-Event-Type: PAYMENT_SUCCEEDED
X-QrPay-Merchant-Id: 0199a0f2-…
X-QrPay-Signature: t=1789123569,v1=5f2b9c…e1
```
```json
{
  "id": "0199b0ca-…",
  "type": "PAYMENT_SUCCEEDED",
  "apiVersion": "2026-09-01",
  "createdAt": "2026-09-11T10:16:09Z",
  "data": {
    "paymentId": "0199b0c9-…", "status": "SUCCESS", "failureReason": null,
    "amount": 25000, "currency": "INR",
    "qrId": "0199b0c4-…", "qrType": "DYNAMIC", "orderRef": "ORD-42", "payerName": "Ravi Kumar"
  }
}
```

**Verifying (merchant side)**

```java
// t=<seconds>,v1=<sig>[,v1=<sig>]  - several v1 parts appear during a secret rotation,
// so accept the request if ANY of them matches your secret.
String[] parts = header.split(",");
long t = Long.parseLong(parts[0].substring(2));
byte[] expected = hex(hmacSha256(webhookSecret, t + "." + rawBody)).getBytes();

boolean match = false;
for (int i = 1; i < parts.length; i++) {
    match |= MessageDigest.isEqual(expected, parts[i].substring(3).getBytes());
}
boolean ok = match && Math.abs(nowEpochSeconds - t) <= 300;
```

> Iterating over every `v1` part is what lets your endpoint keep working across a secret rotation.
> An implementation that reads only `parts[1]` will start rejecting deliveries the moment one happens.

* Respond `2xx` quickly and process asynchronously. Timeouts (5 s), `5xx`, `408` and `429` are retried
  (2 s, 6 s, 18 s, 54 s, 162 s), then dead-lettered. Other `4xx` are not retried.
* Events can arrive more than once and out of order across different payments, so **de-duplicate on `X-QrPay-Event-Id`**.

Event types: `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED`.

---

## 5.6 End-to-end example (curl)

```bash
API=http://localhost:8080/api/v1

# merchant
MT=$(curl -s -X POST $API/auth/register/merchant -H 'Content-Type: application/json' \
  -d '{"fullName":"Mira","email":"mira@shop.in","password":"password123","businessName":"Chai Point","categoryCode":"5812"}' | jq -r .accessToken)

# customer
CT=$(curl -s -X POST $API/auth/register/customer -H 'Content-Type: application/json' \
  -d '{"fullName":"Ravi","email":"ravi@mail.in","password":"password123","pin":"1234"}' | jq -r .accessToken)

# merchant creates a ₹250 QR
QR=$(curl -s -X POST $API/qr-codes/dynamic -H "Authorization: Bearer $MT" -H 'Content-Type: application/json' \
  -d '{"amount":25000,"orderRef":"ORD-1"}')
PAYLOAD=$(echo $QR | jq -r .payload); QR_ID=$(echo $QR | jq -r .id)

# customer scans, initiates, confirms
curl -s -X POST $API/qr-codes/resolve -H "Authorization: Bearer $CT" -H 'Content-Type: application/json' \
  -d "{\"payload\":\"$PAYLOAD\"}" | jq
PID=$(curl -s -X POST $API/payments -H "Authorization: Bearer $CT" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d "{\"qrId\":\"$QR_ID\"}" | jq -r .id)
curl -s -X POST $API/payments/$PID/confirm -H "Authorization: Bearer $CT" -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' -d '{"pin":"1234"}' | jq '{status, amount, merchantName}'
```
