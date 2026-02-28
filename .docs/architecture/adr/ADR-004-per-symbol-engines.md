# ADR-004 — Per-Symbol Isolated Matching Engines

**Status:** Accepted
**Date:** 2024-01
**Deciders:** Platform Architecture Team

---

## Context

A cryptocurrency trading platform handles many trading pairs simultaneously (BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, and potentially hundreds more). The order matching engine must process orders for all symbols without one symbol's activity affecting another's latency.

Design options considered:

1. **Single shared engine**: One Disruptor ring buffer and one order book map containing all symbols
2. **Per-symbol engines**: One `OrderMatchingEngine` instance (with its own Disruptor) per symbol
3. **Sharded engines**: Multiple symbols grouped into engine shards

---

## Decision

Create one **`OrderMatchingEngine`** instance per trading symbol, initialized lazily on first order arrival.

**Implementation:**

```java
// In OrderService
private final ConcurrentHashMap<String, OrderMatchingEngine> engines = new ConcurrentHashMap<>();

private OrderMatchingEngine getOrCreateMatchingEngine(String symbol) {
    return engines.computeIfAbsent(symbol, s -> new OrderMatchingEngine(s, riskManager, listeners));
}
```

Each engine owns:

- Its own LMAX Disruptor ring buffer (65,536 slots, BusySpin consumer)
- Its own order book (`TreeMap` for bids and asks)
- Its own `Long2ObjectHashMap` for order ID lookup

---

## Rationale

### Per-Symbol Benefits

1. **Latency isolation**: A burst of orders on BTCUSDT does not delay ETHUSDT processing. Each symbol's consumer thread operates independently.

2. **No cross-symbol locking**: Since each engine owns its data structures exclusively, there is zero contention between symbols. There are no shared locks to acquire.

3. **Natural parallelism**: If the host has 20 CPU cores and 20 active symbols, each Disruptor consumer can pin to its own dedicated core (with `taskset` or thread affinity libraries).

4. **Simple state isolation**: Cancellation, replacement, and position tracking per symbol are straightforward because the engine is self-contained.

5. **Lazy initialization**: Symbols are activated on demand — no resources are allocated for inactive symbols.

### Why Not a Single Shared Engine?

A single engine would require:

- A `ConcurrentHashMap<String, TreeMap<...>>` guarded by a lock (or segment locks)
- All symbols competing for the same Disruptor consumer thread
- Lock contention when multiple symbols receive simultaneous orders

The resulting latency would be O(number of active symbols) in the worst case.

### Why Not Sharded Engines?

Sharding (e.g., 4 engines each handling 5 symbols) is a middle ground but:

- Adds routing complexity (which shard handles BTCUSDT?)
- Reintroduces cross-symbol latency interference within a shard
- Offers no clear benefit over per-symbol engines given modern server CPU core counts

---

## Consequences

**Positive:**

- True O(1) symbol isolation — BTCUSDT latency is independent of ETHUSDT load
- Linear horizontal scalability: adding symbols adds only 1 thread and ~4MB ring buffer
- Simple code: `OrderMatchingEngine` has no symbol-routing logic

**Negative:**

- **Thread proliferation**: Each active symbol requires 1 dedicated Disruptor consumer thread (plus Aeron threads)
  - Mitigation: For non-critical symbols, switch to `YieldingWaitStrategy` to avoid core pinning
- **Memory per symbol**: ~4MB ring buffer + order book + index map per symbol
  - At 50 symbols: ~200MB overhead (acceptable)
- **Startup time**: Lazy init means the first order for a new symbol takes slightly longer (Disruptor initialization)

---

## Operational Note

The `ConcurrentHashMap` is thread-safe and `computeIfAbsent` ensures at most one engine is created per symbol. Engines are never removed once created (symbols don't go inactive in normal operation), so there is no cleanup concern.
