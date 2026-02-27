# 04 — Data Flows

Critical end-to-end paths through the system with nanosecond-resolution latency capture points.

---

## Flow 1 — Order Submission (REST → Match → Broadcast)

This is the primary critical path. Every nanosecond matters.

```mermaid
sequenceDiagram
    participant C as Client
    participant OC as OrderController
    participant OS as OrderService
    participant RM as RiskManager
    participant DB as PostgreSQL
    participant OME as OrderMatchingEngine<br/>(Disruptor)
    participant WS as WebSocket Handler
    participant CL as Connected Clients

    C->>OC: POST /api/v1/orders (JSON)
    Note over OC: Deserialize OrderRequest DTO
    OC->>OS: submitOrder(request)

    Note over OS: T0 = NanoClock.nanoTime()
    OS->>OS: IdGenerator.nextId() → orderId (Snowflake)
    OS->>OS: Build Order domain object

    OS->>RM: checkOrder(order)
    Note over RM: 9 sequential risk checks<br/>(circuit breaker, rate, size,<br/>notional, position, exposure,<br/>daily loss, price deviation)
    alt Risk check fails
        RM-->>OS: RejectionReason
        OS-->>OC: OrderResponse (REJECTED)
        OC-->>C: 200 OK (rejected body)
    end

    OS->>DB: orderRepository.save(order) [async via @Transactional]
    OS->>RM: addPendingExposure(symbol, qty)
    OS->>OME: submitOrder(order) → ring buffer

    Note over OME: Disruptor handler picks up event
    OME->>OME: matchOrder(order)
    Note over OME: Price-time priority traversal<br/>of opposite side TreeMap

    alt Full match
        OME->>OME: executeTrade(maker, taker)
        OME->>RM: updatePosition(trade)
        OME->>DB: updateFill(orderId, qty, avgPx)
        OME->>WS: notifyTradeListeners(trade)
        OME->>WS: notifyOrderUpdateListeners(FILLED)
    else Partial match
        OME->>OME: executeTrade(maker, taker)
        OME->>OME: addToBook(remainingQty)
        OME->>WS: notifyOrderUpdateListeners(PARTIALLY_FILLED)
    else No match (LIMIT)
        OME->>OME: addToBook(order)
        OME->>WS: notifyOrderUpdateListeners(NEW)
    end

    WS->>CL: broadcastOrderUpdate(order) [account subscribers]
    WS->>CL: broadcastTrade(trade) [account subscribers]

    Note over OS: submitLatencyUs = (NanoClock.nanoTime() - T0) / 1000
    OS-->>OC: OrderResponse (with latencyUs)
    OC-->>C: 200 OK (OrderResponse JSON)
```

### Latency Capture Points

| Point | Field | Description |
|-------|-------|-------------|
| T0 | `submittedNanos` | Timestamp when OrderService receives the order |
| T1 | `acknowledgedNanos` | FIX gateway acknowledgment (if FIX enabled) |
| T2 | `matchedNanos` | Timestamp when trade is executed in matching engine |
| T3 | `reportedNanos` | Trade report received from exchange (FIX path) |

---

## Flow 2 — Market Data Ingestion & Broadcast

```mermaid
sequenceDiagram
    participant EX as Crypto Exchange<br/>(Binance/Coinbase)
    participant WC as WebSocketMarketDataClient
    participant OB as OrderBook
    participant TL as tickListeners
    participant WS as WebSocket Handler
    participant CL as Connected Clients

    EX->>WC: WebSocket frame (JSON)
    Note over WC: T_recv = NanoClock.nanoTime()
    WC->>WC: parseMessage(frame)

    alt Binance @trade event
        WC->>WC: build MarketData(lastPrice, lastQty)
        WC->>TL: notifyTickListeners(marketData)
    else Binance @depth update
        WC->>OB: update(side, price, qty)
        Note over OB: StampedLock writeLock()<br/>TreeMap.put / remove
        OB-->>WC: updated
        WC->>TL: notifyBookListeners(orderBook)
    else Binance 24hrTicker
        WC->>WC: build MarketData(OHLCV, volume)
        WC->>TL: notifyTickListeners(marketData)
    else Coinbase ticker
        WC->>WC: build MarketData(bid, ask)
        WC->>TL: notifyTickListeners(marketData)
    else Coinbase l2update
        WC->>OB: update(side, price, qty)
        WC->>TL: notifyBookListeners(orderBook)
    end

    TL->>WS: broadcastMarketData(symbol, marketData)
    WS->>CL: push JSON to all sessions subscribed to symbol
```

### Market Data Message Format (WebSocket → Client)

```json
{
  "type": "marketdata",
  "symbol": "BTCUSDT",
  "timestamp": 1708000000000000000,
  "data": {
    "bidPrice": 42000.00,
    "bidQuantity": 1.5,
    "askPrice": 42001.00,
    "askQuantity": 0.8,
    "lastPrice": 42000.50,
    "lastQuantity": 0.1,
    "volume24h": 25000.0,
    "high24h": 43000.0,
    "low24h": 41000.0
  }
}
```

---

## Flow 3 — FIX Order Routing (Internal Match → Exchange)

Applies when `hft.fix.enabled=true`. The matching engine can also route orders to an external exchange.

```mermaid
sequenceDiagram
    participant OME as OrderMatchingEngine
    participant FG as FixGateway
    participant EX as Exchange (FIX 4.4)
    participant RM as RiskManager
    participant DB as PostgreSQL
    participant WS as WebSocket Handler

    OME->>FG: sendOrder(order) [via trade listener]
    FG->>FG: buildNewOrderSingle(order)
    Note over FG: Map Order domain → FIX tags<br/>ClOrdID=orderId, Symbol, Side,<br/>OrdType, Price, OrderQty, TimeInForce
    FG->>EX: NewOrderSingle (MsgType=D)
    Note over FG: pendingOrders.put(clOrdId, order)

    EX-->>FG: ExecutionReport (MsgType=8)
    Note over FG: Map FIX ExecType/OrdStatus<br/>→ Order.OrderStatus

    alt ExecType = TRADE / PARTIAL_FILL
        FG->>FG: build Trade from CumQty, AvgPx
        FG->>RM: updatePosition(trade)
        FG->>DB: updateFill(orderId)
        FG->>WS: notifyTradeListeners(trade)
        FG->>WS: notifyOrderUpdateListeners(order)
    else OrdStatus = CANCELLED / REJECTED
        FG->>DB: updateStatus(orderId, status)
        FG->>WS: notifyOrderUpdateListeners(order)
        FG->>FG: pendingOrders.remove(clOrdId)
    end

    Note over FG: acknowledgedNanos = NanoClock.nanoTime()
```

---

## Flow 4 — WebSocket Subscription Lifecycle

```mermaid
sequenceDiagram
    participant C as Client
    participant WH as TradingWebSocketHandler

    C->>WH: WebSocket connect
    Note over WH: Add session to activeSessions set

    C->>WH: {"type":"subscribe","channel":"marketdata","symbol":"BTCUSDT"}
    WH->>WH: symbolSubscriptions.put(sessionId, symbol)

    C->>WH: {"type":"subscribe","channel":"orders","account":"DEFAULT"}
    WH->>WH: accountSubscriptions.put(sessionId, account)

    loop Market data arrives
        WH->>C: {"type":"marketdata","symbol":"BTCUSDT","data":{...}}
    end

    loop Order update
        WH->>C: {"type":"order","account":"DEFAULT","data":{...}}
    end

    C->>WH: {"type":"ping"}
    WH->>C: {"type":"pong"}

    C->>WH: {"type":"unsubscribe","channel":"marketdata","symbol":"BTCUSDT"}
    WH->>WH: symbolSubscriptions.remove(sessionId)

    C->>WH: WebSocket disconnect
    WH->>WH: Remove from activeSessions, clean up all subscriptions
```

---

## Flow 5 — Order Cancellation

```mermaid
sequenceDiagram
    participant C as Client
    participant OC as OrderController
    participant OS as OrderService
    participant OME as OrderMatchingEngine
    participant RM as RiskManager
    participant DB as PostgreSQL
    participant WS as WebSocket Handler

    C->>OC: DELETE /api/v1/orders/{orderId}
    OC->>OS: cancelOrder(orderId)
    OS->>DB: findById(orderId)
    DB-->>OS: Order

    alt Order is terminal (FILLED/CANCELLED/REJECTED)
        OS-->>OC: error "Order already terminal"
        OC-->>C: 400 Bad Request
    end

    OS->>OME: cancelOrder(orderId)
    Note over OME: Ring buffer CANCEL event<br/>Long2ObjectHashMap O(1) lookup<br/>Remove from TreeMap price level
    OME->>RM: releasePendingExposure(symbol, qty)
    OME->>DB: updateStatus(orderId, CANCELLED)
    OME->>WS: notifyOrderUpdateListeners(CANCELLED)
    WS->>C: {"type":"order","data":{"status":"CANCELLED",...}}
    OS-->>OC: OrderResponse (CANCELLED)
    OC-->>C: 200 OK
```

---

## Aeron IPC Internal Messaging (Optional Path)

When Aeron is enabled, components communicate via shared memory instead of direct method calls:

```
Market Data Client
    → AeronTransport.publish(stream=1001, SBE MarketDataSnapshot)
    → [/dev/shm/aeron-hft ring buffer]
    → AeronTransport.subscribe(stream=1001)
    → OrderMatchingEngine / API / Risk consumers

OrderService
    → AeronTransport.publish(stream=1002, SBE NewOrderSingle)
    → [/dev/shm/aeron-hft ring buffer]
    → OrderMatchingEngine subscriber

OrderMatchingEngine
    → AeronTransport.publish(stream=1003, SBE ExecutionReport)
    → [/dev/shm/aeron-hft ring buffer]
    → FIX Gateway / API / Risk consumers
```

Latency: **sub-microsecond** for IPC path (shared memory, no syscalls in hot path).
