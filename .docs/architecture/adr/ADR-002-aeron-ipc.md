# ADR-002 — Aeron IPC for Inter-Process Messaging

**Status:** Accepted
**Date:** 2024-01
**Deciders:** Platform Architecture Team

---

## Context

Components within the HFT platform need to exchange messages: market data updates flow from connectors to consumers, order events flow from the API to the engine, and execution reports flow back to the gateway and API layer. These message exchanges happen millions of times per day and their latency directly contributes to total order processing time.

In addition, the platform may evolve toward a multi-process deployment where market data processing, order matching, and risk management run in separate OS processes for isolation and independent scaling.

---

## Decision

Use **Aeron** as the messaging transport layer, with **Aeron IPC** (shared memory) for in-process communication and **Aeron UDP** for cross-process or cross-host messaging.

**Configuration:**

- IPC channel: `aeron:ipc` (shared memory at `/dev/shm/aeron-hft`)
- Term buffer size: 64 MB
- Window length: 1 MB
- MTU: 1408 bytes
- Embedded MediaDriver: runs in the same JVM process
- Three streams: market data (1001), orders (1002), executions (1003)
- Idle strategy: `YIELDING` (dev) / `SPINNING` (prod)

---

## Rationale

### Aeron vs Alternatives

| Transport | Latency | Throughput | Notes |
|-----------|---------|-----------|-------|
| **Aeron IPC** | **< 1 µs** | Very High | Shared memory, no kernel involvement |
| Aeron UDP | ~5–20 µs | Very High | Cross-process, network stack |
| Kafka | > 1 ms | Extreme | Persistent, but too slow for HFT hot path |
| RabbitMQ | > 1 ms | High | AMQP overhead, broker hop |
| ZeroMQ | ~10–50 µs | Very High | Good, but Aeron is purpose-built for HFT |
| Direct method call | < 100 ns | Highest | No distribution, tight coupling |

### Why Aeron

1. **Designed for financial systems**: Aeron was built by LMAX specifically for trading and financial messaging
2. **Shared memory IPC**: The `aeron:ipc` channel operates on a memory-mapped file in `/dev/shm` — no kernel TCP stack involvement
3. **Flow control without locks**: Producer/consumer coordination via atomic sequence counters
4. **ThreadLocal send buffer**: `UnsafeBuffer` (4KB, thread-local) avoids allocation per message
5. **SBE integration**: Designed to work with SBE for complete zero-copy end-to-end messaging
6. **Back pressure handling**: Publications return negative values (`NOT_CONNECTED`, `BACK_PRESSURED`) that callers can handle gracefully

---

## Stream Design

| Stream ID | Direction | Message Types |
|-----------|-----------|--------------|
| 1001 | MarketData → Consumers | `MarketDataSnapshot`, `OrderBookUpdate` |
| 1002 | API → Engine | `NewOrderSingle`, `CancelOrderRequest`, `ReplaceOrderRequest` |
| 1003 | Engine → API/Gateway | `ExecutionReport`, `OrderCancelReject`, `TradeUpdate`, `PositionUpdate` |

---

## Consequences

**Positive:**

- Sub-microsecond IPC for market data and order event delivery
- Decoupling: producers and consumers don't need direct references to each other
- Path to multi-process deployment: switch `aeron:ipc` to `aeron:udp` without code changes

**Negative:**

- Aeron MediaDriver adds operational complexity (shared memory directory, cleanup on startup)
- Requires `IPC_LOCK` kernel capability to lock shared memory pages
- Aeron conductor/sender/receiver threads consume CPU even when idle

**Mitigations:**

- `cleanupOnStart: true` ensures stale media driver state is removed
- Container configured with `privileged: true` and appropriate ulimits
- Aeron is optional — the platform falls back to direct method calls if `hft.aeron.enabled=false`

---

## Current Usage Note

Aeron is present as infrastructure but may not be fully wired into all message paths (direct listener calls may be used in some paths). The architecture supports migrating to full Aeron messaging incrementally.
