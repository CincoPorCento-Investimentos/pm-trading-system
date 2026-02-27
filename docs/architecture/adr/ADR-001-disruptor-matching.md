# ADR-001 — LMAX Disruptor for Order Matching Engine

**Status:** Accepted
**Date:** 2024-01
**Deciders:** Platform Architecture Team

---

## Context

The order matching engine is the most latency-sensitive component in the system. It receives order events from multiple producer threads (REST, WebSocket, FIX) and must process them in strict order to maintain price-time priority correctness. A naive implementation using `synchronized` blocks or `ReentrantLock` would create contention and unpredictable latency spikes due to thread scheduling.

The key requirements:
- **Strict ordering**: Orders must be processed in submission order within a symbol
- **Low latency**: Sub-microsecond event handoff from producers to the consumer
- **High throughput**: Thousands of order events per second per symbol
- **Zero allocation in hot path**: GC pauses must not affect matching latency

---

## Decision

Use **LMAX Disruptor** as the inter-thread messaging mechanism for each `OrderMatchingEngine` instance.

**Configuration:**
- Ring buffer size: **65,536** (2^16 — power of 2 required for bitwise modulo)
- Producer type: **MULTI** (multiple REST/WS threads can submit orders)
- Wait strategy: **BusySpinWaitStrategy** (lowest possible latency, pins one CPU core)
- Pre-allocated: All `OrderEvent` slots pre-created at startup
- One Disruptor instance per trading symbol (e.g., BTCUSDT, ETHUSDT)

---

## Rationale

### Why Disruptor over Alternatives?

| Approach | Pros | Cons |
|----------|------|------|
| `java.util.concurrent.LinkedBlockingQueue` | Simple | Lock contention, object allocation per event, GC pressure |
| `ArrayBlockingQueue` | Bounded | Still uses locks, false sharing on head/tail |
| `ConcurrentLinkedQueue` | Lock-free | CAS loops, cache line pollution, GC allocation |
| **LMAX Disruptor** | Lock-free, zero-alloc, cache-friendly | Dedicated CPU core, complex setup |

### Disruptor Advantages

1. **Cache-line padding**: `RingBuffer` entries are padded to 64 bytes to prevent false sharing between producer and consumer sequences
2. **Pre-allocated ring**: No heap allocation during event processing — slots are reused
3. **Mechanical sympathy**: Ring buffer fits in CPU L3 cache (65536 × ~64 bytes = 4MB)
4. **Single consumer thread**: The handler thread is the sole writer to the order book, eliminating any need for locks inside the matching logic
5. **BusySpinWaitStrategy**: Consumer spins on the sequence counter rather than sleeping — trades CPU for deterministic < 100ns handoff

---

## Consequences

**Positive:**
- Sub-microsecond order event handoff between producer and consumer threads
- Order book operations (TreeMap lookups/inserts) require no synchronization
- Predictable low-latency behavior without GC-induced jitter in matching

**Negative:**
- One Disruptor consumer thread **pins a CPU core** per symbol (BusySpin). For 10 symbols, 10 cores are dedicated.
- Requires `SYS_NICE` capability in Docker to set thread scheduling priority
- Ring buffer overflow (back pressure) when producers are faster than the consumer for sustained periods — mitigated by the 65536-slot buffer and risk-based rate limiting

**Trade-offs accepted:**
- CPU cost of busy spinning is acceptable for an HFT system where latency is the primary concern
- Operational complexity of per-symbol Disruptor instances is manageable

---

## Alternatives Rejected

- **Akka Actors**: Higher overhead per message, Scala ecosystem dependency
- **Chronicle Queue**: Excellent for persistence/replay but not optimized for sub-microsecond in-process handoff
- **Direct method call with synchronized**: Would create lock contention across symbols sharing a thread pool
