# Synthetic Monitoring — Plano de Implementação

## Contexto

O pm-trading-system é um sistema HFT crítico (3D System). Deploys mal sucedidos podem quebrar funcionalidades silenciosamente. O Synthetic Monitoring é uma aplicação companheira que roda em paralelo, fazendo requisições sintéticas contínuas e validando respostas. Quando um deploy quebrado sobe, o monitor detecta falhas imediatamente e dispara alarmes — atuando como uma rede de segurança para o ambiente de desenvolvimento e produção.

---

## 1. Estrutura do Módulo

Novo módulo Maven: `hft-synthetic-monitoring`

```
hft-synthetic-monitoring/
├── pom.xml
├── Dockerfile
└── src/main/java/com/cryptohft/monitoring/
    ├── SyntheticMonitoringApplication.java     # Entry point Spring Boot
    ├── config/
    │   └── MonitoringProperties.java           # Configurações externalizadas
    ├── check/
    │   ├── SyntheticCheck.java                 # Interface base para todos os checks
    │   ├── CheckResult.java                    # Resultado de um check (OK/FAIL/WARN)
    │   ├── HealthCheck.java                    # GET /actuator/health
    │   ├── MetricsCheck.java                   # GET /actuator/prometheus
    │   ├── OrderLifecycleCheck.java            # Ciclo completo: submit → query → cancel → verify
    │   ├── OrderQueryCheck.java                # GET orders, open orders, filtros
    │   ├── OrderValidationCheck.java           # Requests inválidos → espera 400
    │   └── WebSocketCheck.java                 # Conecta WS, subscribe, ping/pong
    ├── client/
    │   ├── TradingApiClient.java               # HTTP client para REST API
    │   └── TradingWebSocketClient.java         # WebSocket client
    ├── runner/
    │   └── CheckScheduler.java                 # Scheduler que orquestra os checks
    ├── alert/
    │   ├── AlertManager.java                   # Gerencia alertas e thresholds
    │   └── AlertNotifier.java                  # Notificação (log + webhook opcional)
    └── report/
        └── CheckReporter.java                  # Consolida resultados e reporta
```

Também será criado:
```
hft-synthetic-monitoring/
└── src/main/resources/
    └── application.yml                         # Config do monitor
```

---

## 2. Arquivos Existentes a Modificar

| Arquivo | Modificação |
|---------|-------------|
| `/home/user/pm-trading-system/pom.xml` | Adicionar `<module>hft-synthetic-monitoring</module>` |
| `/home/user/pm-trading-system/docker-compose.yml` | Adicionar serviço `hft-synthetic-monitoring` |
| `/home/user/pm-trading-system/monitoring/prometheus.yml` | Adicionar scrape target do monitor |

---

## 3. Contratos da API — Curl + Headers + JSON

### 3.1 HealthCheck

```bash
curl -X GET http://localhost:8080/actuator/health \
  -H "Accept: application/json"
```

**Response Headers esperados:**
```
HTTP/1.1 200 OK
Content-Type: application/json
```

**Response Body esperado:**
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```
**Validações:** HTTP 200, `status` == `"UP"`

---

### 3.2 MetricsCheck

```bash
curl -X GET http://localhost:8080/actuator/prometheus \
  -H "Accept: text/plain"
```

**Response Headers esperados:**
```
HTTP/1.1 200 OK
Content-Type: text/plain;version=0.0.4;charset=utf-8
```

**Response Body esperado (trecho):**
```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.048576E7
```
**Validações:** HTTP 200, body contém `jvm_memory_used_bytes`

---

### 3.3 OrderLifecycleCheck (check principal)

**Step 1 — Submit LIMIT order:**

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "timeInForce": "GTC",
    "price": 100.00,
    "quantity": 0.001,
    "account": "SM_CHECK",
    "clientOrderId": "sm-a1b2c3d4",
    "exchange": "INTERNAL"
  }'
```

**Request Headers:**
```
Content-Type: application/json
Accept: application/json
```

**Response Headers esperados:**
```
HTTP/1.1 200 OK
Content-Type: application/json
X-Request-Id: <uuid>
X-Latency-Ms: <number>
```

**Response Body esperado:**
```json
{
  "orderId": "1234567890123456789",
  "clientOrderId": "sm-a1b2c3d4",
  "symbol": "BTCUSDT",
  "side": "BUY",
  "orderType": "LIMIT",
  "timeInForce": "GTC",
  "status": "NEW",
  "price": 100.00,
  "quantity": 0.001,
  "filledQuantity": 0,
  "remainingQuantity": 0.001,
  "averagePrice": null,
  "exchange": "INTERNAL",
  "account": "SM_CHECK",
  "createdAt": "2026-02-28T10:30:00Z",
  "updatedAt": null,
  "errorCode": null,
  "errorMessage": null,
  "submitLatencyUs": 1250,
  "ackLatencyUs": null
}
```
**Validações:** HTTP 200, `orderId` != null, `status` in (`NEW`, `PENDING_NEW`), `symbol`/`side`/`price`/`quantity` == request

---

**Step 2 — Query order:**

```bash
curl -X GET http://localhost:8080/api/v1/orders/{orderId} \
  -H "Accept: application/json"
```

**Response Body esperado:** Mesmo schema acima, com campos consistentes com Step 1.

**Validações:** HTTP 200, todos os campos batem com o submit

---

**Step 3 — List open orders:**

```bash
curl -X GET "http://localhost:8080/api/v1/orders/open?symbol=BTCUSDT" \
  -H "Accept: application/json"
```

**Response Body esperado:**
```json
[
  {
    "orderId": "1234567890123456789",
    "symbol": "BTCUSDT",
    "status": "NEW",
    ...
  }
]
```
**Validações:** HTTP 200, array contém a ordem criada no Step 1

---

**Step 4 — Cancel order:**

```bash
curl -X DELETE http://localhost:8080/api/v1/orders/{orderId} \
  -H "Accept: application/json"
```

**Response Body esperado:**
```json
{
  "orderId": "1234567890123456789",
  "status": "CANCELLED",
  "symbol": "BTCUSDT",
  "side": "BUY",
  "updatedAt": "2026-02-28T10:30:15Z",
  ...
}
```
**Validações:** HTTP 200, `status` == `CANCELLED`

---

**Step 5 — Verify cancelled:**

```bash
curl -X GET http://localhost:8080/api/v1/orders/{orderId} \
  -H "Accept: application/json"
```
**Validações:** HTTP 200, `status` == `CANCELLED`

---

**Step 6 — Verify not in open orders:**

```bash
curl -X GET "http://localhost:8080/api/v1/orders/open?symbol=BTCUSDT" \
  -H "Accept: application/json"
```
**Validações:** HTTP 200, array NÃO contém o orderId cancelado

---

### 3.4 OrderQueryCheck

**List orders com filtros:**
```bash
curl -X GET "http://localhost:8080/api/v1/orders?limit=10" \
  -H "Accept: application/json"
```

**Response Body esperado:**
```json
[
  {
    "orderId": "...",
    "symbol": "BTCUSDT",
    "side": "BUY",
    "status": "NEW",
    "price": 100.00,
    "quantity": 0.001,
    ...
  }
]
```
**Validações:** HTTP 200, é um JSON array, cada item tem `orderId`, `symbol`, `status`

**List com filtro de status:**
```bash
curl -X GET "http://localhost:8080/api/v1/orders?symbol=BTCUSDT&status=CANCELLED&limit=5" \
  -H "Accept: application/json"
```
**Validações:** HTTP 200, todos os itens têm `status` == `CANCELLED`

**Open orders:**
```bash
curl -X GET "http://localhost:8080/api/v1/orders/open" \
  -H "Accept: application/json"
```
**Validações:** HTTP 200, é um JSON array

---

### 3.5 OrderValidationCheck (testa erros esperados)

**Submit sem symbol (espera 400):**
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 100.00,
    "quantity": 0.001
  }'
```

**Response esperado (HTTP 400):**
```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-02-28T10:30:00Z",
  "details": ["symbol: Symbol is required"]
}
```

**Submit sem quantity (espera 400):**
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 100.00
  }'
```

**Response esperado (HTTP 400):**
```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-02-28T10:30:00Z",
  "details": ["quantity: Quantity is required"]
}
```

**Submit com quantity negativa (espera 400):**
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 100.00,
    "quantity": -1
  }'
```

**Response esperado (HTTP 400):**
```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "timestamp": "2026-02-28T10:30:00Z",
  "details": ["quantity: Quantity must be positive"]
}
```

**GET order inexistente (espera 404):**
```bash
curl -X GET http://localhost:8080/api/v1/orders/NONEXISTENT_ID_12345 \
  -H "Accept: application/json"
```

**Response esperado (HTTP 404):**
```json
{
  "errorCode": "ORDER_NOT_FOUND",
  "message": "Order not found: NONEXISTENT_ID_12345",
  "timestamp": "2026-02-28T10:30:00Z"
}
```

**Cancel order inexistente (espera 404):**
```bash
curl -X DELETE http://localhost:8080/api/v1/orders/NONEXISTENT_ID_12345 \
  -H "Accept: application/json"
```

**Response esperado (HTTP 404):**
```json
{
  "errorCode": "ORDER_NOT_FOUND",
  "message": "Order not found: NONEXISTENT_ID_12345",
  "timestamp": "2026-02-28T10:30:00Z"
}
```

**Cancel order já cancelada (espera 409):**
```bash
# Primeiro submete e cancela uma ordem, depois tenta cancelar novamente
curl -X DELETE http://localhost:8080/api/v1/orders/{cancelledOrderId} \
  -H "Accept: application/json"
```

**Response esperado (HTTP 409):**
```json
{
  "errorCode": "INVALID_ORDER_STATE",
  "message": "Cannot cancel order {orderId} in state: CANCELLED",
  "timestamp": "2026-02-28T10:30:00Z"
}
```

---

### 3.6 Batch Cancel Check

```bash
curl -X POST http://localhost:8080/api/v1/orders/cancel-batch \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{
    "orderIds": ["orderId1", "orderId2"],
    "symbol": "BTCUSDT"
  }'
```

**Response esperado (HTTP 200):**
```json
[
  { "orderId": "orderId1", "status": "CANCELLED", ... },
  { "orderId": "orderId2", "status": "CANCELLED", ... }
]
```

---

### 3.7 Cancel All Check

```bash
curl -X DELETE "http://localhost:8080/api/v1/orders/cancel-all?symbol=BTCUSDT" \
  -H "Accept: application/json"
```

**Response esperado (HTTP 200):**
```json
[
  { "orderId": "...", "status": "CANCELLED", ... }
]
```

---

### 3.8 WebSocketCheck

**Endpoint:** `ws://localhost:8080/ws/trading`

**Ping → Pong:**
```json
// Client envia:
{"type": "ping"}

// Server responde:
{"type": "pong", "timestamp": 1709107200000}
```

**Subscribe marketdata:**
```json
// Client envia:
{"type": "subscribe", "channel": "marketdata", "symbol": "BTCUSDT"}

// Server responde:
{"type": "subscribed", "channel": "marketdata", "symbol": "BTCUSDT"}
```

**Unsubscribe:**
```json
// Client envia:
{"type": "unsubscribe", "channel": "marketdata", "symbol": "BTCUSDT"}

// Server responde:
{"type": "unsubscribed", "channel": "marketdata", "symbol": "BTCUSDT"}
```

**Mensagem inválida → Error:**
```json
// Client envia:
{"type": "invalid_type_xyz"}

// Server responde:
{"type": "error", "errorCode": "INVALID_MESSAGE", "errorMessage": "...", "timestamp": 1709107200000}
```

**Validações:** Conectar com sucesso, receber pong/subscribed/unsubscribed/error conforme esperado, timeout de 5s por mensagem

---

## 4. Classes Principais — Design

### SyntheticCheck (Interface)
```java
public interface SyntheticCheck {
    String getName();
    String getDescription();
    CheckResult execute();
}
```

### CheckResult
```java
public record CheckResult(
    String checkName,
    Status status,          // OK, FAIL, WARN
    String message,
    long durationMs,
    Instant timestamp,
    List<CheckStep> steps   // sub-steps detalhados
) {}

public record CheckStep(
    String name,
    Status status,
    String detail,
    long durationMs
) {}
```

### TradingApiClient
Usa `RestClient` (Spring 6.1+) — leve, sem dependências extras:
```java
@Component
public class TradingApiClient {
    ResponseEntity<OrderResponse> submitOrder(OrderRequest request);
    ResponseEntity<OrderResponse> getOrder(String orderId);
    ResponseEntity<List<OrderResponse>> getOrders(String symbol, String status, int limit);
    ResponseEntity<List<OrderResponse>> getOpenOrders(String symbol);
    ResponseEntity<OrderResponse> cancelOrder(String orderId);
    ResponseEntity<String> getHealth();
    ResponseEntity<String> getPrometheus();
}
```

### CheckScheduler
```java
@Component
public class CheckScheduler {
    @Scheduled(fixedDelayString = "${monitoring.interval-ms:30000}")
    void runChecks();  // executa todos os checks em sequência
}
```

### AlertManager
- Conta falhas consecutivas por check
- Threshold configurável (default: 3 falhas consecutivas = alarme)
- Quando threshold atingido → dispara alerta via AlertNotifier
- Quando check volta ao normal → dispara alerta de recuperação

### AlertNotifier
- Log em nível ERROR para falhas (sempre ativo)
- Webhook HTTP opcional (Slack/Discord/custom) — configurável via URL
- Métricas Prometheus (counter de falhas, gauge de status)

---

## 5. Configuração (application.yml do checker)

```yaml
spring:
  application:
    name: hft-synthetic-monitoring

server:
  port: ${MONITOR_PORT:8081}

synthetic-monitoring:
  target:
    base-url: ${TARGET_BASE_URL:http://localhost:8080}
    ws-url: ${TARGET_WS_URL:ws://localhost:8080/ws/trading}
  interval-ms: ${CHECK_INTERVAL_MS:30000}
  timeout-ms: ${CHECK_TIMEOUT_MS:5000}
  order:
    symbol: ${CHECK_SYMBOL:BTCUSDT}
    price: ${CHECK_PRICE:100.00}
    quantity: ${CHECK_QUANTITY:0.001}
    account: SM_CHECK
    client-id-prefix: sm-
  alert:
    consecutive-failures-threshold: ${ALERT_THRESHOLD:3}
    webhook-url: ${ALERT_WEBHOOK_URL:}
  checks:
    health: true
    metrics: true
    order-lifecycle: true
    order-query: true
    order-validation: true
    websocket: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

---

## 6. Dependências Maven (pom.xml do módulo)

```xml
<dependencies>
    <!-- Spring Boot Web (inclui RestClient) -->
    <dependency>spring-boot-starter-web</dependency>
    <!-- Spring Boot Actuator (health + prometheus do próprio checker) -->
    <dependency>spring-boot-starter-actuator</dependency>
    <!-- Micrometer Prometheus (métricas do checker) -->
    <dependency>micrometer-registry-prometheus</dependency>
    <!-- WebSocket client -->
    <dependency>org.java-websocket:Java-WebSocket</dependency>
    <!-- Jackson (JSON) — já incluído pelo starter-web -->
    <!-- Lombok -->
    <dependency>lombok</dependency>
    <!-- Logback (logging) -->
    <dependency>logback-classic</dependency>
    <!-- Test -->
    <dependency>spring-boot-starter-test</dependency>
</dependencies>
```

Nota: o checker NÃO depende de módulos internos (hft-common, etc). É propositalmente desacoplado — ele fala com o sistema apenas via HTTP/WS, exatamente como um cliente externo faria.

---

## 7. Docker Integration

Adicionar ao `docker-compose.yml`:
```yaml
hft-synthetic-monitoring:
  build:
    context: .
    dockerfile: hft-synthetic-monitoring/Dockerfile
  container_name: hft-synthetic-monitoring
  environment:
    TARGET_BASE_URL: http://hft-app:8080
    TARGET_WS_URL: ws://hft-app:8080/ws/trading
    CHECK_INTERVAL_MS: 30000
    ALERT_WEBHOOK_URL: ""
  ports:
    - "8081:8081"
  depends_on:
    hft-app:
      condition: service_healthy
  networks:
    - hft-network
  deploy:
    resources:
      limits:
        memory: 512M
      reservations:
        memory: 256M
  restart: unless-stopped
```

Dockerfile separado e simples (multi-stage) em `hft-synthetic-monitoring/Dockerfile`.

---

## 8. Métricas Prometheus Expostas pelo Checker

| Métrica | Tipo | Descrição |
|---------|------|-----------|
| `synthetic_check_total{check,status}` | Counter | Total de execuções por check e resultado |
| `synthetic_check_duration_ms{check}` | Summary | Duração de cada check |
| `synthetic_check_status{check}` | Gauge | Status atual (1=OK, 0=FAIL) |
| `synthetic_alert_total{check}` | Counter | Total de alertas disparados |

Adicionar ao `monitoring/prometheus.yml`:
```yaml
- job_name: 'hft-synthetic-monitoring'
  metrics_path: '/actuator/prometheus'
  static_configs:
    - targets: ['hft-synthetic-monitoring:8081']
  scrape_interval: 15s
```

---

## 9. Ordem de Implementação

1. Criar estrutura do módulo Maven `hft-synthetic-monitoring` + pom.xml
2. Registrar módulo no root pom.xml
3. `CheckResult`, `CheckStep`, `SyntheticCheck` — modelos base
4. `MonitoringProperties` — configuração
5. `TradingApiClient` — HTTP client (RestClient do Spring 6.1+)
6. `TradingWebSocketClient` — WS client (Java-WebSocket 1.6.0)
7. `HealthCheck` + `MetricsCheck` — checks simples
8. `OrderLifecycleCheck` — check principal (submit→query→cancel→verify)
9. `OrderQueryCheck` + `OrderValidationCheck` — checks de query e validação
10. `WebSocketCheck` — check de WebSocket
11. `AlertManager` + `AlertNotifier` — sistema de alertas
12. `CheckReporter` — consolidação de resultados + métricas Prometheus
13. `CheckScheduler` — agendamento com @Scheduled
14. `SyntheticMonitoringApplication` — entry point
15. `application.yml` — configuração
16. `Dockerfile` para o monitor
17. Atualizar `docker-compose.yml` e `prometheus.yml`
18. Testes unitários

---

## 10. Verificação

1. **Build**: `mvn clean package -pl hft-synthetic-monitoring` — deve compilar sem erros
2. **Standalone**: Subir o hft-app localmente, depois rodar o monitor apontando para `localhost:8080` — logs devem mostrar todos os checks passando
3. **Docker**: `docker-compose up` — monitor deve subir após hft-app e logar status dos checks
4. **Alarme**: Parar o hft-app e verificar que o monitor detecta falhas e loga alertas ERROR
5. **Métricas**: Acessar `http://localhost:8081/actuator/prometheus` e verificar métricas do monitor

---

## 11. Considerações de Segurança e Edge Cases

- **Risk safety**: Ordens usam quantity=0.001 e price=100.00 (notional=0.10), muito abaixo dos limites de risco (max notional=1M)
- **Account isolation**: Todas as ordens usam prefixo `SM_` no account para isolamento total do tráfego real
- **Cleanup automático**: Serviço de cleanup roda a cada 5min + no shutdown (@PreDestroy) para cancelar ordens órfãs
- **PENDING_NEW transitório**: Após submit de LIMIT, o status pode ser brevemente PENDING_NEW antes de NEW — os checks aceitam ambos
- **MARKET orders**: Podem ser REJECTED imediatamente se não há liquidez — o check aceita NEW, FILLED ou REJECTED como válido
- **WebSocket threading**: Usar CountDownLatch/CompletableFuture para sincronizar mensagens WS com timeout
- **Desacoplamento total**: O módulo NÃO depende de hft-common nem de nenhum outro módulo interno — define seus próprios DTOs espelhando o contrato da API, exatamente como um cliente externo faria
