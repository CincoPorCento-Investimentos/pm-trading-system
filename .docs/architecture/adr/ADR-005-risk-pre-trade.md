# ADR-005 — Synchronous Pre-Trade Risk Checks in the Critical Path

**Status:** Accepted
**Date:** 2024-01
**Deciders:** Platform Architecture Team

---

## Context

Risk management can be implemented at different points in the order lifecycle:

1. **Pre-trade (synchronous)**: Check risk before the order enters the matching engine
2. **Post-trade (asynchronous)**: Allow the order to match first, then reconcile risk violations
3. **Hybrid**: Fast pre-checks synchronously, deep checks asynchronously

In financial systems, uncontrolled risk can result in catastrophic losses within milliseconds. The Knight Capital incident (2012) — a $440M loss in 45 minutes — is a canonical example of missing pre-trade controls.

---

## Decision

All risk checks are **synchronous, sequential, and blocking** in the critical path between order submission and engine entry. No order reaches the matching engine unless all 9 risk checks pass.

```java
// OrderService.submitOrder()
RiskCheckResult result = riskManager.checkOrder(order);  // SYNCHRONOUS, all 9 checks
if (result.isRejected()) {
    return OrderResponse.rejected(order, result.getReason());
}
// Only after all checks pass:
engine.submitOrder(order);  // → Disruptor ring buffer
```

---

## Rationale

### Why Synchronous?

1. **Correctness over speed**: An order that violates risk limits must never execute. Asynchronous checks introduce a race window where the order can match before the check completes.

2. **Simple state model**: Synchronous checks mean that when `submitOrder()` returns, the risk state is consistent. No rollback or compensation logic needed.

3. **Latency is acceptable**: The 9 checks execute in ~1–5 µs total (in-memory reads, atomic counter operations, arithmetic). This is negligible compared to REST API network latency (~100µs+) and PostgreSQL persistence (~500µs).

4. **Fail-fast**: The first failing check returns immediately without executing subsequent checks. In the common case (no violations), all 9 checks complete quickly.

### Risk Check Performance Characteristics

| Check | Operation | Estimated Time |
|-------|-----------|---------------|
| Circuit breaker | `AtomicBoolean.get()` | < 10 ns |
| Rate limit | `AtomicLong` read + compare | < 20 ns |
| Size limits | `BigDecimal.compareTo()` | ~50 ns |
| Notional | `BigDecimal.multiply()` | ~100 ns |
| Position limit | `ConcurrentHashMap.get()` + arithmetic | ~200 ns |
| Total exposure | `volatile double` read + arithmetic | < 50 ns |
| Daily loss | `volatile double` read + compare | < 10 ns |
| Price deviation | `BigDecimal.subtract().divide()` | ~150 ns |
| **Total** | | **~600 ns – 2 µs** |

This is far below the 100+ µs network RTT for REST clients.

---

## Pending Exposure Tracking

A subtle correctness issue: if multiple orders are submitted concurrently (before any fills occur), position-limit checks using only the current position would allow each order to pass individually, but together they'd exceed the limit.

**Solution:** Track "pending exposure" — the sum of quantities of all orders that have passed risk checks but not yet been matched:

```java
// After risk check passes:
riskManager.addPendingExposure(symbol, order.quantity);

// After trade fills:
riskManager.releasePendingExposure(symbol, filledQty);

// Position limit check uses:
if (currentPosition + pendingExposure + orderQty > maxPositionSize) REJECT;
```

This ensures the sum of all in-flight orders is always within limits.

---

## Consequences

**Positive:**

- No order can bypass risk controls under any concurrency scenario
- Simple mental model: order is safe if and only if `submitOrder()` returns success
- Immediate rejection feedback to clients (no async callback needed for rejections)
- Audit log accurately captures reject reason at submission time

**Negative:**

- Risk checks add ~1–5 µs to every order submission (acceptable)
- Risk state is in-memory — requires reconstruction from PostgreSQL on restart
  - Mitigation: On startup, load open orders and positions from DB to restore pending exposure
- Risk checks are not idempotent — duplicate orders (e.g., retry on timeout) may pass if the first order's pending exposure wasn't recorded
  - Mitigation: Client-order-ID deduplication at the API layer

---

## Circuit Breaker

The `tradingEnabled` flag acts as a master kill switch. When tripped (manually or by daily-loss-limit breach), it is check #1 and returns in < 10 ns — all subsequent checks are skipped. This ensures that even under extreme conditions, the platform can stop accepting orders in microseconds.
