# ADR-003 — SBE (Simple Binary Encoding) for Zero-Copy Serialization

**Status:** Accepted
**Date:** 2024-01
**Deciders:** Platform Architecture Team

---

## Context

Messages exchanged between system components (market data, orders, executions) must be serialized for Aeron transport. The serialization format directly impacts:
- **Throughput**: How many messages per second can be processed
- **Latency**: Serialization/deserialization adds to end-to-end message latency
- **Memory**: Object allocation during encoding contributes to GC pressure

---

## Decision

Use **Simple Binary Encoding (SBE)** as the wire format for all Aeron messages.

SBE is used to generate Java codecs from XML schema definitions at build time (`hft-sbe/src/main/resources/*.xml`). The generated codecs read/write directly from/to `DirectBuffer` (off-heap memory) with no intermediate object creation.

---

## Rationale

### Serialization Format Comparison

| Format | Encoding | Size | Parse Speed | Alloc | Notes |
|--------|----------|------|------------|-------|-------|
| JSON | Text | Large | ~µs | High | REST API boundary only |
| Protobuf | Binary | Medium | ~100ns | Medium | Schema evolution, but copies data |
| Avro | Binary | Medium | ~200ns | Medium | Schema registry needed |
| MessagePack | Binary | Medium | ~200ns | Medium | Similar to Protobuf |
| FlatBuffers | Binary | Low | ~10ns | Zero | Zero-copy reads, schema evolution |
| **SBE** | Binary | **Minimal** | **~10ns** | **Zero** | **Purpose-built for financial messaging** |
| Chronicle Wire | Binary | Low | ~50ns | Low | Chronicle ecosystem |

### Why SBE

1. **Zero-copy**: Codecs operate directly on a `DirectBuffer` — no intermediate Java objects are created during encoding or decoding
2. **Fixed-width fields**: Most SBE fields have predetermined sizes (no length prefixes needed for primitive fields), allowing O(1) field access by offset
3. **Financial domain alignment**: Native support for fixed-point decimal (`mantissa` + `exponent`) matching financial price representation
4. **Nanosecond timestamps**: `int64` timestamp field holds nanoseconds since epoch without precision loss
5. **Repeating groups**: Efficient encoding for multi-level order book data (variable number of bid/ask levels in a single message)
6. **Code generation**: Schema changes auto-generate new codecs — no manual serialization code
7. **Designed for Aeron**: SBE + Aeron is the canonical LMAX stack

---

## Schema Design Principles

1. **Little-endian byte order**: Matches x86 native byte order (no byte swapping needed)
2. **Aligned fields**: Fields placed at natural alignment offsets (int32 at 4-byte boundaries)
3. **Fixed header**: `messageHeader` block (templateId, schemaId, version, blockLength) precedes every message for framing and version detection
4. **`NullValue` sentinels**: Fields use type-specific null values (e.g., `Long.MIN_VALUE` for nullable int64) instead of optional wrappers
5. **Variable-length strings at end**: `varStringEncoding` fields (orderId, symbol, account) placed after all fixed fields to preserve random access to fixed fields

---

## Consequences

**Positive:**
- Zero heap allocation in message encoding/decoding hot path
- Message parse time in the 10–50 ns range
- Type-safe generated codecs eliminate manual parsing errors
- Minimal wire size reduces Aeron buffer pressure

**Negative:**
- SBE messages are not human-readable (binary format requires tooling to inspect)
- Schema changes require regenerating codecs and redeploying all consumers
- Variable-length string fields (`varStringEncoding`) require sequential access (can't random-seek past them)
- Learning curve for developers unfamiliar with the SBE toolchain

**Boundary**: SBE is only used for **internal Aeron messages**. The external REST API and WebSocket interfaces continue to use JSON for developer ergonomics and compatibility.
