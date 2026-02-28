# 05 — Data Architecture

Covers the persistence layer, domain model precision, binary message schemas, and ID generation.

---

## PostgreSQL Schema (Entity Relationship Diagram)

```mermaid
erDiagram
    orders {
        VARCHAR(64)  order_id PK
        VARCHAR(64)  client_order_id UK
        VARCHAR(20)  symbol
        VARCHAR(10)  side
        VARCHAR(20)  order_type
        VARCHAR(10)  time_in_force
        VARCHAR(20)  status
        NUMERIC(24_8) price
        NUMERIC(24_8) quantity
        NUMERIC(24_8) filled_quantity
        NUMERIC(24_8) remaining_quantity
        NUMERIC(24_8) average_price
        NUMERIC(24_8) stop_price
        VARCHAR(50)  exchange
        VARCHAR(50)  account
        BIGINT       submitted_nanos
        BIGINT       acknowledged_nanos
        TIMESTAMP    created_at
        TIMESTAMP    updated_at
    }

    trades {
        VARCHAR(64)  trade_id PK
        VARCHAR(64)  order_id FK
        VARCHAR(20)  symbol
        VARCHAR(10)  side
        NUMERIC(24_8) price
        NUMERIC(24_8) quantity
        NUMERIC(24_8) commission
        VARCHAR(20)  commission_asset
        VARCHAR(50)  exchange
        VARCHAR(50)  account
        VARCHAR(64)  counterparty_order_id
        BOOLEAN      is_maker
        BIGINT       matched_nanos
        BIGINT       reported_nanos
        TIMESTAMP    executed_at
    }

    positions {
        BIGSERIAL    id PK
        VARCHAR(20)  symbol
        VARCHAR(50)  account
        NUMERIC(24_8) quantity
        NUMERIC(24_8) average_entry_price
        NUMERIC(24_8) realized_pnl
        NUMERIC(24_8) unrealized_pnl
        NUMERIC(24_8) notional_value
        NUMERIC(24_8) margin_used
        NUMERIC(10_4) leverage
        TIMESTAMP    created_at
        TIMESTAMP    updated_at
    }

    market_data_snapshots {
        BIGSERIAL    id PK
        VARCHAR(20)  symbol
        VARCHAR(50)  exchange
        NUMERIC(24_8) bid_price
        NUMERIC(24_8) bid_quantity
        NUMERIC(24_8) ask_price
        NUMERIC(24_8) ask_quantity
        NUMERIC(24_8) last_price
        NUMERIC(24_8) last_quantity
        NUMERIC(24_8) volume_24h
        BIGINT       sequence_number
        TIMESTAMP    exchange_timestamp
        TIMESTAMP    received_at
    }

    audit_log {
        BIGSERIAL    id PK
        VARCHAR(50)  event_type
        VARCHAR(50)  entity_type
        VARCHAR(64)  entity_id
        VARCHAR(50)  account
        JSONB        old_value
        JSONB        new_value
        JSONB        metadata
        VARCHAR(45)  ip_address
        TEXT         user_agent
        TIMESTAMP    created_at
    }

    risk_limits {
        BIGSERIAL    id PK
        VARCHAR(50)  account
        VARCHAR(50)  limit_type
        VARCHAR(20)  symbol
        NUMERIC(30_8) max_value
        NUMERIC(30_8) current_value
        BOOLEAN      is_active
        TIMESTAMP    created_at
        TIMESTAMP    updated_at
    }

    orders ||--o{ trades : "generates"
    orders }o--|| positions : "updates"
```

---

## Database Indexes

### orders table

| Index | Columns | Purpose |
|-------|---------|---------|
| `PK` | `order_id` | Primary lookup |
| `UK` | `client_order_id` | Client dedup |
| `IDX` | `symbol` | Filter by market |
| `IDX` | `account` | Filter by account |
| `IDX` | `status` | Filter open/closed |
| `IDX` | `created_at` | Time range queries |
| `COMPOSITE` | `(symbol, status)` | Open orders by symbol |
| `COMPOSITE` | `(account, status)` | Open orders by account |

### trades table

| Index | Columns | Purpose |
|-------|---------|---------|
| `IDX` | `order_id` | Join to orders |
| `IDX` | `symbol` | Filter by market |
| `IDX` | `account` | Filter by account |
| `IDX` | `executed_at` | Time range queries |
| `COMPOSITE` | `(symbol, executed_at)` | OHLCV queries |
| `COMPOSITE` | `(account, executed_at)` | Account P&L queries |

---

## SBE Message Schema

All binary messages use **little-endian** byte order, fixed-width fields, and the `messageHeader` block (templateId, schemaId, version, blockLength).

| ID | Message | Block Length | Key Repeating Group |
|----|---------|-------------|-------------------|
| 1 | `NewOrderSingle` | ~120 bytes | — |
| 2 | `CancelOrderRequest` | ~80 bytes | — |
| 3 | `ReplaceOrderRequest` | ~120 bytes | — |
| 4 | `ExecutionReport` | ~160 bytes | — |
| 5 | `OrderCancelReject` | ~100 bytes | — |
| 6 | `MarketDataSnapshot` | ~60 bytes | Bid/Ask levels (up to 20 each) |
| 7 | `OrderBookUpdate` | ~40 bytes | Bid/Ask changes |
| 8 | `TradeUpdate` | ~120 bytes | — |
| 9 | `Heartbeat` | ~24 bytes | — |
| 10 | `PositionUpdate` | ~100 bytes | — |

### Custom Primitive Types

| SBE Type | Encoding | Java Type | Description |
|----------|----------|-----------|-------------|
| `decimal` | `int64` mantissa + `int8` exponent | `BigDecimal` | Price/quantity with full precision |
| `timestamp` | `int64` | `long` | Nanoseconds since Unix epoch |
| `varStringEncoding` | Length-prefixed UTF-8 | `String` | Symbol, orderId, account |
| `Side` | `uint8` ENUM | `Order.Side` | 1=BUY, 2=SELL |
| `OrdType` | `uint8` ENUM | `Order.OrderType` | 1=MARKET, 2=LIMIT, 3=STOP |
| `OrdStatus` | `uint8` ENUM | `Order.OrderStatus` | 0=NEW, 1=PARTIAL, 2=FILLED… |

---

## Price Precision Model

To avoid floating-point errors in financial calculations, the platform uses **integer arithmetic** internally:

```
External (API/JSON):  42000.50     (BigDecimal, 8 decimal places)
Internal (Engine):    4200050000000  (long, × 10^8)
```

### Why integer arithmetic?

| Problem with floats | Solution |
|--------------------|---------|
| `0.1 + 0.2 ≠ 0.3` in IEEE 754 | Integer comparisons are exact |
| Float comparison unreliable for price equality | `price1 == price2` works on longs |
| Slower BigDecimal operations in hot loop | `long` arithmetic is single CPU instruction |

**Conversion:** `long internalPrice = price.multiply(BigDecimal.valueOf(100_000_000L)).longValue()`

---

## Snowflake ID Generation

Orders, trades, and positions use **Snowflake IDs** — 64-bit integers generated without coordination across nodes.

```
 63        22      12      0
  |         |       |      |
  0 [41-bit ms timestamp] [10-bit node] [12-bit seq]
```

| Segment | Bits | Range | Notes |
|---------|------|-------|-------|
| Sign bit | 1 | Always 0 | Keeps IDs positive |
| Timestamp | 41 | ~69 years from epoch | Milliseconds since custom epoch |
| Node ID | 10 | 0–1023 | Set via `hft.node.id` config |
| Sequence | 12 | 0–4095 | Resets each millisecond |

**Throughput:** 4,096 unique IDs per millisecond per node = **4M IDs/sec** per node.

**Clock drift handling:** If `currentMs < lastMs`, `IdGenerator` waits for `nextMs` (busy-spin up to 1ms).

---

## Redis Data Model

Redis is configured as an **LRU cache** with AOF persistence. Potential usage patterns:

| Key Pattern | Value | TTL | Purpose |
|-------------|-------|-----|---------|
| `order:{orderId}` | JSON string | 1h | Hot order cache |
| `position:{account}:{symbol}` | JSON string | None | Position cache |
| `riskcheck:{account}` | Hash | None | Real-time risk state |
| `marketdata:{symbol}` | Hash | 30s | Last known tick |

> Note: Redis integration is configured but the extent of use depends on active Spring Cache annotations in the service layer.

---

## Domain Model Lifecycle

```mermaid
stateDiagram-v2
    direction LR

    [*] --> Order : POST /api/v1/orders
    Order --> RiskCheck : RiskManager.checkOrder()
    RiskCheck --> Rejected : any check fails
    RiskCheck --> Persisted : all checks pass

    Persisted --> MatchingEngine : engine.submitOrder()
    MatchingEngine --> Resting : no match, LIMIT
    MatchingEngine --> PartialFill : partial match
    MatchingEngine --> FullFill : complete match

    PartialFill --> PartialFill : more fills
    PartialFill --> FullFill : last fill
    PartialFill --> Cancelled : cancel request

    Resting --> Cancelled : cancel request
    Resting --> PartialFill : incoming order

    Cancelled --> [*] : persist CANCELLED
    FullFill --> [*] : persist FILLED
    Rejected --> [*] : persist REJECTED
```

Each state transition:

1. Updates the in-memory `Order` object (new immutable copy via `@With`)
2. Persists change to PostgreSQL via `orderRepository.updateStatus()` or `updateFill()`
3. Notifies WebSocket listeners
4. Updates `Position` and `RiskManager` state
