# Roadmap: HFT Synthetic Monitoring

## Overview

Construir uma aplicacao companheira que monitora o pm-trading-system via checks sinteticos continuos, detectando deploys quebrados antes que impactem operacoes reais. O caminho vai de scaffolding do modulo Maven independente, passando pelos clients HTTP/WS, implementacao de todos os checks (health, orders, websocket), sistema de alertas com threshold configuravel, ate deploy completo via Docker com metricas Prometheus e dashboard Grafana auto-provisionado.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Foundation** - Modulo Maven, modelos base e configuracao externalizada
- [ ] **Phase 2: Clients** - HTTP client (RestClient) e WebSocket client para comunicacao com o target
- [ ] **Phase 3: Health & Order Checks** - Checks de health, metricas e todo o ciclo de ordens
- [ ] **Phase 4: WebSocket Check** - Check completo de WebSocket (ping/pong, subscribe, mensagem invalida)
- [ ] **Phase 5: Orchestration & Alerting** - Scheduler, reporter e sistema de alertas com notificacao
- [ ] **Phase 6: Observability & Deployment** - Metricas Prometheus, dashboard Grafana e integracao Docker

## Phase Details

### Phase 1: Foundation
**Goal**: Modulo Maven funcional com entry point Spring Boot, modelos base e configuracao externalizada prontos para receber clients e checks
**Depends on**: Nothing (first phase)
**Requirements**: CORE-01, CORE-02, CORE-04
**Success Criteria** (what must be TRUE):
  1. `mvn clean compile -pl hft-synthetic-monitoring` compila sem erros e o modulo aparece no root pom.xml
  2. `SyntheticMonitoringApplication` sobe na porta 8081 e responde em /actuator/health com status UP
  3. `MonitoringProperties` carrega todos os parametros do application.yml (target URL, interval, order params, alert threshold)
  4. `SyntheticCheck` interface, `CheckResult` e `CheckStep` records existem com status OK/FAIL/WARN e podem ser instanciados
**Plans**: 3 plans

Plans:
- [x] 01-01-PLAN.md — Module scaffolding + Spring Boot entry point (Wave 1)
- [x] 01-02-PLAN.md — Base models: Status, CheckStep, CheckResult, SyntheticCheck (Wave 2)
- [x] 01-03-PLAN.md — Configuration properties: MonitoringProperties (Wave 2)

### Phase 2: Clients
**Goal**: Clients HTTP e WebSocket funcionais que conseguem se comunicar com o hft-app, servindo como fundacao para todos os checks
**Depends on**: Phase 1
**Requirements**: HTTP-01, HTTP-02, WS-01, WS-02
**Success Criteria** (what must be TRUE):
  1. `TradingApiClient` faz requests para todos os endpoints REST (submitOrder, getOrder, getOrders, getOpenOrders, cancelOrder, getHealth, getPrometheus) e retorna ResponseEntity com status e body corretos
  2. `TradingApiClient` e configuravel via properties (base URL, timeout, symbol, price, quantity, account)
  3. `TradingWebSocketClient` conecta ao ws://target/ws/trading, envia mensagens e recebe respostas
  4. Mensagens WebSocket sao sincronizadas via CountDownLatch/CompletableFuture com timeout configuravel (sem hang infinito)
**Plans**: TBD

Plans:
- [ ] 02-01: TBD
- [ ] 02-02: TBD

### Phase 3: Health & Order Checks
**Goal**: Todos os checks HTTP funcionais -- desde health/metrics simples ate o ciclo completo de ordens com validacoes de erro
**Depends on**: Phase 2
**Requirements**: HLTH-01, HLTH-02, ORD-01, ORD-02, ORD-03, ORD-04, ORD-05, ORD-06, ORD-07
**Success Criteria** (what must be TRUE):
  1. `HealthCheck` valida GET /actuator/health retornando HTTP 200 com status "UP" e reporta FAIL quando target esta down
  2. `MetricsCheck` valida GET /actuator/prometheus retornando HTTP 200 com metricas JVM presentes
  3. `OrderLifecycleCheck` executa ciclo completo (submit LIMIT com account SM_CHECK e prefixo sm- -> query -> list open contem ordem -> cancel -> verify cancelled -> verify not in open) e cada step valida HTTP status e campos do response
  4. `OrderQueryCheck` testa list orders com filtros (limit, symbol, status) e open orders, validando formato e conteudo das respostas
  5. `OrderValidationCheck` confirma que requests invalidos retornam os codigos de erro corretos: sem symbol (400), sem quantity (400), quantity negativa (400), order inexistente (404), cancel inexistente (404), cancel ja cancelada (409)
**Plans**: TBD

Plans:
- [ ] 03-01: TBD
- [ ] 03-02: TBD
- [ ] 03-03: TBD

### Phase 4: WebSocket Check
**Goal**: Check completo de WebSocket que valida conectividade, protocolo ping/pong, subscribe/unsubscribe e tratamento de mensagens invalidas
**Depends on**: Phase 2
**Requirements**: WSC-01, WSC-02, WSC-03, WSC-04
**Success Criteria** (what must be TRUE):
  1. `WebSocketCheck` conecta ao ws://target/ws/trading com timeout e reporta FAIL se conexao falha
  2. Ping/pong funciona: envia ping, recebe pong com timestamp dentro do timeout
  3. Subscribe/unsubscribe marketdata funciona: envia subscribe, recebe subscribed; envia unsubscribe, recebe unsubscribed
  4. Mensagem invalida retorna error: envia type invalido, recebe error com errorCode INVALID_MESSAGE
**Plans**: TBD

Plans:
- [ ] 04-01: TBD

### Phase 5: Orchestration & Alerting
**Goal**: Checks orquestrados automaticamente com scheduler periodico, resultados consolidados por reporter, e sistema de alertas que detecta falhas consecutivas e notifica via log + webhook
**Depends on**: Phase 3, Phase 4
**Requirements**: CORE-03, CORE-05, ALRT-01, ALRT-02, ALRT-03, ALRT-04
**Success Criteria** (what must be TRUE):
  1. `CheckScheduler` executa todos os checks registrados periodicamente no intervalo configurado (default 30s) via @Scheduled
  2. `CheckReporter` consolida resultados de todos os checks e produz report agregado com status por check
  3. `AlertManager` conta falhas consecutivas por check e dispara alerta quando threshold configuravel e atingido (default: 3 falhas)
  4. Quando um check volta ao normal apos alerta, AlertManager dispara notificacao de recuperacao
  5. `AlertNotifier` loga ERROR para falhas (sempre ativo) e envia webhook HTTP com JSON quando URL configurada
**Plans**: TBD

Plans:
- [ ] 05-01: TBD
- [ ] 05-02: TBD

### Phase 6: Observability & Deployment
**Goal**: Monitor completamente operacional em Docker com metricas Prometheus proprias, scrape target configurado e dashboard Grafana auto-provisionado
**Depends on**: Phase 5
**Requirements**: OBS-01, OBS-02, OBS-03, DOCK-01, DOCK-02, DOCK-03
**Success Criteria** (what must be TRUE):
  1. Metricas Prometheus do monitor acessiveis em localhost:8081/actuator/prometheus: synthetic_check_total, synthetic_check_duration_ms, synthetic_check_status e synthetic_alert_total
  2. Actuator do monitor expoe health, info, metrics e prometheus na porta 8081
  3. `docker-compose up` sobe o monitor como servico separado, aguardando hft-app healthy, com limites de memoria 256-512MB
  4. Prometheus scrapeia o monitor a cada 15s via target hft-synthetic-monitoring:8081
  5. Dashboard Grafana auto-provisionado mostra status dos checks, historico de falhas e latencias sem setup manual
**Plans**: TBD

Plans:
- [ ] 06-01: TBD
- [ ] 06-02: TBD
- [ ] 06-03: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 -> 2 -> 3 (and 4 in parallel if desired) -> 5 -> 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation | 3/3 | Complete | 2026-02-28 |
| 2. Clients | 0/2 | Not started | - |
| 3. Health & Order Checks | 0/3 | Not started | - |
| 4. WebSocket Check | 0/1 | Not started | - |
| 5. Orchestration & Alerting | 0/2 | Not started | - |
| 6. Observability & Deployment | 0/3 | Not started | - |
