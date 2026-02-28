# Requirements: HFT Synthetic Monitoring

**Defined:** 2026-02-27
**Core Value:** Detectar deploys quebrados imediatamente atraves de checks sinteticos continuos

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Core Infrastructure

- [x] **CORE-01**: Modulo Maven `hft-synthetic-monitoring` com pom.xml, package structure e entry point Spring Boot
- [x] **CORE-02**: Configuracao externalizada via `MonitoringProperties` (@ConfigurationProperties) com todos os parametros do application.yml
- [ ] **CORE-03**: `CheckScheduler` que orquestra todos os checks periodicamente via @Scheduled (intervalo configuravel, default 30s)
- [x] **CORE-04**: Modelos base `SyntheticCheck` (interface), `CheckResult` (record) e `CheckStep` (record) com status OK/FAIL/WARN
- [ ] **CORE-05**: `CheckReporter` que consolida resultados de todos os checks e reporta

### HTTP Client

- [ ] **HTTP-01**: `TradingApiClient` usando RestClient (Spring 6.1+) com todos os endpoints: submitOrder, getOrder, getOrders, getOpenOrders, cancelOrder, getHealth, getPrometheus
- [ ] **HTTP-02**: Client configuravel via URL base, timeout, e parametros de ordem sintetica (symbol, price, quantity, account)

### WebSocket Client

- [ ] **WS-01**: `TradingWebSocketClient` usando Java-WebSocket 1.6.0 com connect, disconnect, send message, receive message
- [ ] **WS-02**: Sincronizacao de mensagens WS com CountDownLatch/CompletableFuture e timeout configuravel

### Health & Metrics Checks

- [ ] **HLTH-01**: `HealthCheck` que faz GET /actuator/health e valida HTTP 200 + status "UP"
- [ ] **HLTH-02**: `MetricsCheck` que faz GET /actuator/prometheus e valida HTTP 200 + presenca de metricas JVM

### Order Checks

- [ ] **ORD-01**: `OrderLifecycleCheck` com ciclo completo: submit LIMIT -> query -> list open (contem ordem) -> cancel -> verify cancelled -> verify not in open orders
- [ ] **ORD-02**: Submit usa account SM_CHECK, clientOrderId com prefixo sm-, quantity=0.001, price=100.00 (notional seguro)
- [ ] **ORD-03**: Validacao de cada step: HTTP status, campos do response batem com request, status transitions corretos
- [ ] **ORD-04**: `OrderQueryCheck` que testa list orders com filtros (limit, symbol, status) e open orders
- [ ] **ORD-05**: `OrderValidationCheck` que testa requests invalidos: sem symbol (400), sem quantity (400), quantity negativa (400), order inexistente (404), cancel inexistente (404), cancel ja cancelada (409)
- [ ] **ORD-06**: Batch cancel check — submete N ordens, cancela via POST /api/v1/orders/cancel-batch, valida todas canceladas
- [ ] **ORD-07**: Cancel-all check — submete N ordens, cancela via DELETE /api/v1/orders/cancel-all?symbol=X, valida todas canceladas

### WebSocket Check

- [ ] **WSC-01**: `WebSocketCheck` que conecta ao ws://target/ws/trading com timeout
- [ ] **WSC-02**: Testa ping/pong (envia ping, espera pong com timestamp)
- [ ] **WSC-03**: Testa subscribe/unsubscribe marketdata (envia subscribe, espera subscribed; envia unsubscribe, espera unsubscribed)
- [ ] **WSC-04**: Testa mensagem invalida (envia type invalido, espera error com errorCode INVALID_MESSAGE)

### Alerting

- [ ] **ALRT-01**: `AlertManager` que conta falhas consecutivas por check com threshold configuravel (default: 3)
- [ ] **ALRT-02**: Quando threshold atingido, dispara alerta; quando check volta ao normal, dispara recuperacao
- [ ] **ALRT-03**: `AlertNotifier` com log ERROR para falhas (sempre ativo)
- [ ] **ALRT-04**: `AlertNotifier` com webhook HTTP opcional (URL configuravel, body JSON com detalhes do alerta)

### Observability

- [ ] **OBS-01**: Metricas Prometheus: `synthetic_check_total{check,status}` (Counter), `synthetic_check_duration_ms{check}` (Summary), `synthetic_check_status{check}` (Gauge 1=OK/0=FAIL), `synthetic_alert_total{check}` (Counter)
- [ ] **OBS-02**: Actuator do proprio monitor expondo health, info, metrics, prometheus na porta 8081
- [ ] **OBS-03**: Dashboard Grafana auto-provisionado via JSON file no docker-compose (status dos checks, historico de falhas, latencias)

### Docker Integration

- [ ] **DOCK-01**: Dockerfile multi-stage (eclipse-temurin:21) para o monitor
- [ ] **DOCK-02**: Servico `hft-synthetic-monitoring` no docker-compose.yml com depends_on hft-app healthy, porta 8081, limites de memoria 256-512MB
- [ ] **DOCK-03**: Scrape target `hft-synthetic-monitoring:8081` no prometheus.yml com interval 15s

## v2 Requirements

### Resilience

- **RES-01**: Retry com backoff exponencial para checks que falham por timeout
- **RES-02**: Circuit breaker no client HTTP quando target esta completamente down

### Extended Checks

- **EXT-01**: Market order lifecycle check (submit MARKET, aceita NEW/FILLED/REJECTED)
- **EXT-02**: Replace order check (submit, replace price/quantity, verify)
- **EXT-03**: WebSocket marketdata stream check (subscribe e validar que recebe dados)

### Notifications

- **NOTF-01**: Integracao nativa Slack (blocks API, threading)
- **NOTF-02**: Integracao nativa Discord (embeds)
- **NOTF-03**: Email notifications

## Out of Scope

| Feature | Reason |
|---------|--------|
| Testes de carga/stress | Monitor e para validacao funcional, nao performance testing |
| Acesso direto ao banco | Desacoplamento total — so HTTP/WS como cliente externo |
| Dependencia de modulos internos | Define DTOs proprios espelhando contrato da API |
| UI propria | Usa Grafana que ja existe no stack |
| PagerDuty/OpsGenie | Webhook generico e suficiente para v1 |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CORE-01 | Phase 1 | Complete |
| CORE-02 | Phase 1 | Complete |
| CORE-03 | Phase 5 | Pending |
| CORE-04 | Phase 1 | Complete |
| CORE-05 | Phase 5 | Pending |
| HTTP-01 | Phase 2 | Pending |
| HTTP-02 | Phase 2 | Pending |
| WS-01 | Phase 2 | Pending |
| WS-02 | Phase 2 | Pending |
| HLTH-01 | Phase 3 | Pending |
| HLTH-02 | Phase 3 | Pending |
| ORD-01 | Phase 3 | Pending |
| ORD-02 | Phase 3 | Pending |
| ORD-03 | Phase 3 | Pending |
| ORD-04 | Phase 3 | Pending |
| ORD-05 | Phase 3 | Pending |
| ORD-06 | Phase 3 | Pending |
| ORD-07 | Phase 3 | Pending |
| WSC-01 | Phase 4 | Pending |
| WSC-02 | Phase 4 | Pending |
| WSC-03 | Phase 4 | Pending |
| WSC-04 | Phase 4 | Pending |
| ALRT-01 | Phase 5 | Pending |
| ALRT-02 | Phase 5 | Pending |
| ALRT-03 | Phase 5 | Pending |
| ALRT-04 | Phase 5 | Pending |
| OBS-01 | Phase 6 | Pending |
| OBS-02 | Phase 6 | Pending |
| OBS-03 | Phase 6 | Pending |
| DOCK-01 | Phase 6 | Pending |
| DOCK-02 | Phase 6 | Pending |
| DOCK-03 | Phase 6 | Pending |

**Coverage:**
- v1 requirements: 28 total
- Mapped to phases: 28
- Unmapped: 0

---
*Requirements defined: 2026-02-27*
*Last updated: 2026-02-27 after roadmap creation*
