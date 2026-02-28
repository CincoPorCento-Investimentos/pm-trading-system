# HFT Synthetic Monitoring

## What This Is

Aplicacao companheira do pm-trading-system (3D System HFT) que roda em paralelo fazendo requisicoes sinteticas continuas contra a REST API e WebSocket do sistema principal. Quando um deploy quebrado sobe, o monitor detecta falhas imediatamente e dispara alarmes — atuando como rede de seguranca para ambientes de desenvolvimento e producao.

Modulo Maven independente (`hft-synthetic-monitoring`), completamente desacoplado dos modulos internos. Fala com o sistema apenas via HTTP/WS, exatamente como um cliente externo faria.

## Core Value

Detectar deploys quebrados imediatamente atraves de checks sinteticos continuos, antes que impactem operacoes reais de trading.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

(None yet — ship to validate)

### Active

<!-- Current scope. Building toward these. -->

- [ ] Health check (GET /actuator/health) com validacao de status UP
- [ ] Metrics check (GET /actuator/prometheus) com validacao de metricas JVM
- [ ] Order lifecycle check completo (submit LIMIT -> query -> list open -> cancel -> verify cancelled -> verify not in open)
- [ ] Order query check (list orders com filtros, open orders)
- [ ] Order validation check (requests invalidos retornam 400/404/409 corretos)
- [ ] Batch cancel check (cancel multiplas ordens)
- [ ] Cancel-all check (cancel todas as ordens de um symbol)
- [ ] WebSocket check (connect, ping/pong, subscribe/unsubscribe, mensagem invalida -> error)
- [ ] Sistema de alertas com threshold configuravel (N falhas consecutivas = alarme)
- [ ] Notificacao via log ERROR (sempre) + webhook HTTP opcional (Slack/Discord/custom)
- [ ] Metricas Prometheus do proprio checker (total, duration, status gauge, alert counter)
- [ ] Scheduler configuravel para orquestrar checks periodicamente
- [ ] Reporter que consolida resultados
- [ ] Dashboard Grafana provisionado automaticamente via JSON
- [ ] Integracao Docker Compose (servico separado, depends_on hft-app healthy)
- [ ] Integracao Prometheus (scrape target do monitor)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Testes de carga/stress — o monitor e para validacao funcional, nao performance testing
- Monitoramento de banco de dados direto — o monitor so fala via HTTP/WS, nao acessa DB
- Dependencia de modulos internos (hft-common, etc) — propositalmente desacoplado, define seus proprios DTOs
- UI propria para o monitor — usa Grafana que ja existe no stack
- Integracao com PagerDuty/OpsGenie — webhook generico e suficiente para v1

## Context

O pm-trading-system e um sistema HFT critico com 10 modulos Maven (Java 21, Spring Boot 3.5.11):
- hft-common, hft-sbe, hft-aeron, hft-engine, hft-market-data, hft-fix-gateway, hft-api, hft-persistence, hft-app, hft-synthetic-monitoring
- API REST em `/api/v1/orders` (submit, query, cancel, batch-cancel, cancel-all)
- WebSocket em `ws://localhost:8080/ws/trading` (subscribe, marketdata, orders, trades)
- Actuator expondo health, metrics, prometheus
- Docker Compose com 5 servicos: postgres, redis, hft-app, prometheus, grafana
- Prometheus ja scrapeando hft-app a cada 5s

O documento de arquitetura `docs/architecture/10-synthetic-monitoring.md` contem a especificacao completa:
- Estrutura de classes e interfaces
- Contratos de API com curl examples e JSONs esperados
- Configuracao YAML
- Dependencias Maven
- Docker integration
- Metricas Prometheus
- Ordem de implementacao sugerida (18 steps)

### Decisoes ja tomadas no doc:

- RestClient (Spring 6.1+) para HTTP client — leve, sem deps extras
- Java-WebSocket 1.6.0 para WS client
- Ordens sinteticas com quantity=0.001, price=100.00 (notional=0.10, muito abaixo do limite de risco)
- Account `SM_CHECK` para isolamento do trafego real
- ClientOrderId com prefixo `sm-` para identificacao
- Porta 8081 para o monitor (8080 e do hft-app)

## Constraints

- **Tech stack**: Java 21 + Spring Boot 3.5.11 + Maven — consistente com o resto do sistema
- **Desacoplamento**: Zero dependencias de modulos internos — apenas HTTP/WS como cliente externo
- **Seguranca**: Ordens sinteticas devem ser inofensivas (low quantity, low price, account isolado)
- **Recursos**: Container limitado a 512MB RAM (256MB reservados)
- **Porta**: 8081 (8080 ja em uso pelo hft-app)

## Key Decisions

<!-- Decisions that constrain future work. Add throughout project lifecycle. -->

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Modulo Maven independente (nao integrado ao hft-app) | Desacoplamento total — monitora como cliente externo | — Pending |
| RestClient do Spring 6.1+ para HTTP | Leve, sem deps extras, ja no Spring Boot 3.5 | — Pending |
| Java-WebSocket 1.6.0 para WS | Simples, standalone, sem deps pesadas | — Pending |
| Dashboard Grafana auto-provisionado | Sobe junto com docker-compose, zero setup manual | — Pending |
| Webhook generico (nao Slack-specific) | Flexivel — funciona com Slack, Discord, custom | — Pending |
| Account SM_CHECK + prefix sm- | Isolamento total do trafego real de trading | — Pending |

---
*Last updated: 2026-02-27 after initialization*
