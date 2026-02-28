# Phase 1: Foundation - Context

**Gathered:** 2026-02-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Modulo Maven funcional (`hft-synthetic-monitoring`) com entry point Spring Boot, modelos base (`SyntheticCheck` interface, `CheckResult` record, `CheckStep` record) e configuracao externalizada via `MonitoringProperties`. Esta fase entrega a fundacao sobre a qual TODOS os checks, clients e alertas serao construidos nas fases seguintes. Nao inclui clients HTTP/WS, checks reais, scheduler ou alerting.

</domain>

<decisions>
## Implementation Decisions

### Riqueza do CheckResult
- Resultado com steps individuais — cada check reporta N steps (ex: OrderLifecycle teria submit, query, cancel como steps separados com status/duracao cada)
- Cada execucao captura timestamp de inicio/fim + duracao total em ms — essencial pra metricas de latencia
- Quando um check falha: mensagem + detalhes tecnicos (HTTP status recebido, body parcial, exception) — debug rapido sem precisar de logs externos
- CheckResult e CheckStep como Java records (imutaveis) — thread-safe por natureza

### Semantica do WARN
- WARN dispara em dois cenarios: latencia acima do threshold OU sucesso parcial (check completou mas com ressalvas)
- WARN conta parcialmente pro alerting — contador separado com threshold proprio (ex: 5 WARNs consecutivos = alerta de warning, diferente do alerta de falha)
- Threshold de latencia configuravel POR CHECK — health pode ter 500ms, order lifecycle pode ter 5s. Cada check define seu proprio limite
- Criticidade por step: steps criticos (submit, cancel) com falha = FAIL do check inteiro; steps de validacao (query timing, list verification) = WARN

### Valores default da configuracao
- Intervalo entre rodadas de checks: 30 segundos (default)
- Timeout HTTP e WebSocket: 3 segundos — agressivo, adequado pro contexto HFT (se nao respondeu em 3s, algo ta errado)
- Params de ordem sintetica mantidos conforme doc de arquitetura: quantity=0.001, price=100.00, symbol=BTCUSD, account=SM_CHECK, clientOrderId prefix=sm-
- Threshold de alerta: 3 falhas consecutivas pra disparar alerta (default)

### Identidade dos checks
- Nomes em kebab-case: `health-check`, `order-lifecycle`, `websocket-ping`, etc. — funciona bem em labels Prometheus e legivel em logs
- Cada check carrega metadata: nome + descricao + grupo (ex: grupo `orders` contem lifecycle, query, validation) — permite agrupamento no dashboard Grafana
- Checks incluem prioridade (HIGH/MEDIUM/LOW) — afeta severidade de alerta e pode influenciar ordem de execucao

### Claude's Discretion
- Como expor metadata na interface SyntheticCheck (metodos vs anotacoes) — preferencia por performance, evitar reflection
- Design interno dos records (quais campos exatos, builders se necessario)
- Estrutura do application.yml (agrupamento de properties, nomes de chaves)
- Logging de startup (o que mostrar quando o monitor sobe)

</decisions>

<specifics>
## Specific Ideas

- Contexto HFT justifica timeout agressivo de 3s — sistema que nao responde rapido ja tem problema
- Records imutaveis sao prioridade — o sistema precisa ser thread-safe desde a fundacao
- Steps detalhados no CheckResult sao essenciais porque o OrderLifecycleCheck (fase 3) tem 6+ steps e cada um precisa ser rastreavel individualmente
- Prioridade nos checks permite que o dashboard Grafana mostre health checks criticos com destaque visual

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 01-foundation*
*Context gathered: 2026-02-28*
