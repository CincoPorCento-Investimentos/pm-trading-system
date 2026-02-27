# HFT Crypto Trading Platform — Architecture Documentation

This directory contains the comprehensive architecture documentation for the **Crypto HFT Trading Platform**, a high-frequency cryptocurrency trading system built with Java 17, Spring Boot 3.2, and specialized low-latency libraries.

---

## Navigation

| Document | Description |
|----------|-------------|
| [01 — System Context](./01-system-context.md) | High-level view: actors, external systems, system boundaries |
| [02 — Containers](./02-containers.md) | Runtime processes, databases, and communication channels |
| [03 — Components](./03-components.md) | Internal module breakdown with responsibilities and relationships |
| [04 — Data Flows](./04-data-flows.md) | End-to-end sequence diagrams for critical paths |
| [05 — Data Architecture](./05-data-architecture.md) | Database schema, SBE messages, domain models, ID strategy |
| [06 — Performance](./06-performance.md) | Threading model, lock-free design, latency profile, memory |
| [07 — Risk Management](./07-risk-management.md) | Pre-trade risk pipeline, circuit breaker, position management |
| [08 — Observability](./08-observability.md) | Metrics, Prometheus, Grafana, audit log, health checks |
| [09 — Deployment](./09-deployment.md) | Docker Compose, JVM tuning, Linux kernel tuning, startup sequence |

### Architecture Decision Records (ADRs)

| ADR | Decision |
|-----|----------|
| [ADR-001](./adr/ADR-001-disruptor-matching.md) | LMAX Disruptor for order matching engine |
| [ADR-002](./adr/ADR-002-aeron-ipc.md) | Aeron IPC for inter-process messaging |
| [ADR-003](./adr/ADR-003-sbe-serialization.md) | Simple Binary Encoding (SBE) for zero-copy serialization |
| [ADR-004](./adr/ADR-004-per-symbol-engines.md) | Per-symbol isolated matching engines |
| [ADR-005](./adr/ADR-005-risk-pre-trade.md) | Synchronous pre-trade risk checks in the critical path |

---

## Technology Stack

| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| Runtime | Java | 17 (LTS) | Primary language |
| Framework | Spring Boot | 3.2.1 | Application framework, DI, web |
| Messaging | Aeron | 1.42.1 | Sub-microsecond IPC |
| Serialization | SBE | 1.29.0 | Zero-copy binary encoding |
| Concurrency | LMAX Disruptor | 4.0.0 | Lock-free ring buffer event processing |
| FIX Protocol | QuickFIX/J | 2.3.1 | FIX 4.4 exchange connectivity |
| Networking | Netty | 4.1.104 | Async TCP for market data |
| Database | PostgreSQL | 15 | Order, trade, position persistence |
| Cache | Redis | 7 | Caching and pub/sub |
| Journaling | Chronicle Queue | 5.24.60 | Event sourcing / audit log |
| Migrations | Flyway | 10.4.1 | Database schema versioning |
| Metrics | Micrometer + Prometheus | 1.12.1 | Observability |
| Visualization | Grafana | latest | Dashboards |

---

## Glossary

| Term | Definition |
|------|-----------|
| **HFT** | High-Frequency Trading — strategies that exploit very short-term market movements at high speed |
| **Aeron** | A low-level UDP/IPC transport library designed for sub-microsecond latencies using shared memory |
| **SBE** | Simple Binary Encoding — a binary message format that allows zero-copy reads directly from a byte buffer |
| **Disruptor** | LMAX Disruptor — a lock-free, cache-friendly ring buffer for inter-thread messaging |
| **FIX** | Financial Information eXchange — industry standard protocol for electronic trading communication |
| **IPC** | Inter-Process Communication |
| **Ring Buffer** | Fixed-size circular queue used by the Disruptor to pass events between producer and consumer threads |
| **Snowflake ID** | Distributed unique ID format: timestamp bits + node ID bits + sequence bits |
| **Price-Time Priority** | Order book matching rule: orders at better prices execute first; among equals, the earlier order executes first |
| **GTC / IOC / FOK** | Good Till Cancel / Immediate Or Cancel / Fill Or Kill — order time-in-force policies |
| **P&L** | Profit and Loss |
| **Maker / Taker** | In a matched trade, the maker placed the passive (resting) order; the taker placed the aggressive order |
