# 6. Data model

Source of truth: [`V1__init_schema.sql`](../backend/src/main/resources/db/migration/V1__init_schema.sql).

## 6.1 Entity-relationship diagram

```mermaid
erDiagram
    USERS ||--o| MERCHANTS : "is (role=MERCHANT)"
    USERS ||--o| WALLETS : "owns (owner_type=USER)"
    MERCHANTS ||--|| WALLETS : "settles into (owner_type=MERCHANT)"
    MERCHANTS ||--o{ QR_CODES : issues
    QR_CODES ||--o{ PAYMENTS : "paid via"
    USERS ||--o{ PAYMENTS : pays
    MERCHANTS ||--o{ PAYMENTS : receives
    WALLETS ||--o{ LEDGER_ENTRIES : "has entries"
    PAYMENTS ||--o{ LEDGER_ENTRIES : "produces (2 per success)"
    PAYMENTS ||--o{ OUTBOX_EVENTS : "emits"

    USERS {
        uuid id PK
        varchar email UK "unique on lower(email)"
        varchar password_hash "bcrypt"
        varchar full_name
        varchar role "CUSTOMER | MERCHANT"
        timestamptz created_at
    }
    MERCHANTS {
        uuid id PK
        uuid user_id FK,UK
        varchar business_name
        varchar vpa UK "chai-point-3f9a@qrpay"
        varchar category_code "MCC"
        varchar webhook_url
        varchar webhook_secret
        varchar previous_webhook_secret
        timestamptz previous_secret_expires_at
        varchar status "ACTIVE | SUSPENDED"
    }
    WALLETS {
        uuid id PK
        uuid owner_id
        varchar owner_type "USER | MERCHANT | SYSTEM"
        bigint balance "paise, CHECK >= 0 unless SYSTEM"
        varchar currency
        varchar pin_hash "bcrypt, USER only"
        bigint version
    }
    QR_CODES {
        uuid id PK
        uuid merchant_id FK
        varchar type "STATIC | DYNAMIC"
        bigint amount "null for STATIC"
        varchar order_ref "unique per merchant"
        varchar status "ACTIVE | PAID | EXPIRED | DISABLED"
        text payload "signed string in the QR"
        timestamptz expires_at
        uuid paid_payment_id
        bigint version
    }
    PAYMENTS {
        uuid id PK
        uuid qr_id FK
        varchar qr_type
        uuid merchant_id FK
        varchar merchant_name "snapshot"
        uuid payer_user_id FK
        varchar payer_name "snapshot"
        bigint amount
        varchar status "CREATED | SUCCESS | FAILED | EXPIRED | CANCELLED"
        varchar failure_reason
        varchar idempotency_key "UNIQUE(payer_user_id, key)"
        timestamptz expires_at
        timestamptz completed_at
        bigint version
    }
    LEDGER_ENTRIES {
        uuid id PK
        uuid wallet_id FK
        uuid transfer_id "pairs DEBIT+CREDIT"
        uuid payment_id FK "null for top-ups"
        varchar entry_type "DEBIT | CREDIT"
        bigint amount
        bigint balance_after
        varchar description
    }
    OUTBOX_EVENTS {
        uuid id PK "= eventId"
        varchar aggregate_type
        uuid aggregate_id
        varchar event_type
        varchar topic
        varchar partition_key "merchantId"
        text payload "JSON"
        varchar status "PENDING | PUBLISHED"
        int attempts
    }
```

## 6.2 Tables and why they look the way they do

### `users`
Credentials and role. `UNIQUE (lower(email))` makes email case-insensitive at the database level.

### `merchants`
A merchant **is** a user with role `MERCHANT` (1:1). `vpa` is the human-readable handle printed under the QR.
`webhook_secret` (`whsec_` + 48 hex chars) is generated at onboarding and can be rotated. A rotation moves
the old value to `previous_webhook_secret` and sets `previous_secret_expires_at`; while that window is open
deliveries are signed with both, so the merchant can reconfigure without dropping webhooks.

### `wallets`
* One wallet per `(owner_type, owner_id, currency)`.
* `balance BIGINT` in paise. `CHECK (balance >= 0 OR owner_type = 'SYSTEM')`: the database refuses overdrafts.
* `pin_hash` lives on the wallet (the PIN authorises spending *this* wallet, like a UPI PIN per bank account).
* `version` enables optimistic locking as a safety net. The payment path uses pessimistic `FOR UPDATE`.
* The SYSTEM wallet `00000000-0000-7000-8000-000000000001` is seeded by the migration.

### `qr_codes`
* `CHECK (type = 'STATIC' OR (amount IS NOT NULL AND expires_at IS NOT NULL))`: a dynamic code always has both.
* `ux_qr_static_per_merchant`, a **partial unique index** `(merchant_id) WHERE type='STATIC'`: exactly one static code per merchant.
* `ux_qr_order_ref`, `(merchant_id, order_ref) WHERE order_ref IS NOT NULL`: merchant order refs are idempotency keys for QR creation.
* `ix_qr_active_expiry`, `(expires_at) WHERE status='ACTIVE' AND type='DYNAMIC'`: the sweeper scans only live dynamic codes, so the index stays tiny.
* `payload` is stored so the image can be re-rendered identically at any time. Rows minted before the
  format was slimmed keep their `v=1` string and are never rewritten - a printed sticker must keep
  matching its row, and `decodeAndVerify` still accepts v1.

### `payments`
* **Receipt semantics:** `merchant_name`, `payer_name`, `order_ref`, `qr_type` are copied in at creation. History must
  not change if a merchant renames itself.
* `UNIQUE (payer_user_id, idempotency_key)`: the durable idempotency backstop.
* `ux_payment_success_per_dynamic_qr`, `(qr_id) WHERE status='SUCCESS' AND qr_type='DYNAMIC'`: the third line of
  defence against double-paying a single-use code.
* `ix_payments_pending_expiry`, partial on `status='CREATED'`: cheap expiry sweeps.
* `ix_payments_merchant_created` / `ix_payments_payer_created`: both history screens are a single index range scan.

### `ledger_entries`
Append-only (`@Immutable` in JPA; in production also `REVOKE UPDATE, DELETE`). `transfer_id` ties the DEBIT and CREDIT
of one transfer together. `balance_after` lets you render a statement without window functions and helps spot the exact
entry where a mismatch began.

### `outbox_events`
`id` **is** the `eventId` consumers de-duplicate on. `partition_key` = merchant id. The partial index on
`status='PENDING'` keeps relay polling O(pending), not O(history). Rows are purged 7 days after publishing.

## 6.3 Money & id types

| Choice | Why |
|---|---|
| `BIGINT` minor units | Exact integer arithmetic. `Math.addExact` / `subtractExact` in Java throw on overflow instead of wrapping. JS numbers are exact up to 2⁵³ (≈ ₹90 trillion). |
| `VARCHAR(3)` ISO currency | Explicit even though there is a single currency today. Wallet↔wallet transfers assert equal currency. |
| UUIDv7 | Globally unique across future shards, generated without a DB round trip, and time-ordered (48-bit ms prefix) so inserts land at the right edge of the B-tree like a sequence would. |
| `TIMESTAMPTZ` + `hibernate.jdbc.time_zone=UTC` | Instants, never local times. |

## 6.4 Invariants (and who enforces them)

| Invariant | Enforced by |
|---|---|
| No wallet (except SYSTEM) below zero | `CHECK` constraint + `Wallet.debit()` |
| Σ balance of all wallets = 0 | `transfer()` is the only mutator (always paired entries) + reconciliation job |
| Wallet balance = Σ its ledger entries | same, verified by the reconciliation job |
| A dynamic QR has ≤ 1 successful payment | compare-and-set on `qr_codes` + partial unique index on `payments` |
| One payment per (payer, idempotency key) | unique constraint |
| Payment status only moves forward | `PaymentStatus.canTransitionTo()` + row lock |
| Event exists ⇔ state change committed | outbox row in the same transaction |

## 6.5 Evolution: partitioning & sharding

1. **Partition by time** (native declarative partitioning) `payments`, `ledger_entries` and `qr_codes` monthly by `created_at`.
   UUIDv7 ids correlate with time, which keeps partitions balanced. Old partitions are detached and archived.
2. **Read replicas** for history, statements and dashboards.
3. **Shard by wallet id** when a single primary saturates (Citus, or application-level shards). A payment then spans
   two shards (payer, merchant). Keep the **payer debit synchronous** on the payer's shard, and credit the merchant
   **asynchronously** via the event stream (merchant settlement is naturally eventually consistent in real
   PSPs: T+0/T+1). See [07](07-scalability-reliability-security.md#72-scaling-the-database).
