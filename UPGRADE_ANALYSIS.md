# Framework Upgrade Analysis - Crypto HFT Trading Platform

## Resumo Executivo

Esta analise documenta a migracao completa de todos os frameworks da **Crypto HFT Trading Platform** para as versoes mais recentes estaveis. A migracao foi implementada nesta branch e esta pronta para validacao.

---

## O Que Foi Atualizado

### Versao Anterior -> Nova Versao

| Componente | Antes | Depois | Tipo de Mudanca |
|---|---|---|---|
| **Java** | 17 | **21 (LTS)** | Major LTS upgrade |
| **Spring Boot** | 3.2.1 (EOL) | **3.5.11** | Minor (com breaking changes) |
| **Aeron** | 1.42.1 | **1.46.7** | Minor |
| **SBE** | 1.29.0 | **1.33.2** | Minor |
| **Agrona** | 1.20.0 | **1.23.1** | Minor |
| **QuickFIX/J** | 2.3.1 | **2.3.2** | Patch (security fix) |
| **LMAX Disruptor** | 4.0.0 | **4.0.0** | Sem mudanca (ja na ultima) |
| **Chronicle Queue** | 5.24.60 | **5.27.3** | Minor |
| **PostgreSQL JDBC** | 42.7.1 | **42.7.5** | Patch (security fixes) |
| **HikariCP** | 5.1.0 (explicito) | **gerenciado pelo Spring Boot** | Removida versao explicita |
| **Flyway** | 10.4.1 (explicito) | **gerenciado pelo Spring Boot** | Removida versao explicita |
| **Netty** | 4.1.104.Final | **4.1.118.Final** | Patch (14 releases) |
| **Java-WebSocket** | 1.5.4 (hardcoded) | **1.6.0** (centralizado no parent) | Minor |
| **Guava** | 32.1.3-jre | **33.4.0-jre** | Major |
| **Lombok** | 1.18.30 | **1.18.36** | Patch |
| **MapStruct** | 1.5.5.Final | **1.6.3** | Minor |
| **TestContainers** | 1.19.3 | **1.20.4** | Minor |
| **Micrometer** | 1.12.1 (explicito) | **gerenciado pelo Spring Boot** | Removida versao explicita |
| **Maven Compiler Plugin** | 3.11.0 | **3.13.0** | Minor |
| **exec-maven-plugin** | 3.1.1 | **3.5.0** | Minor |
| **build-helper-maven-plugin** | 3.4.0 | **3.6.0** | Minor |
| **PostgreSQL (Docker)** | 15-alpine | **17-alpine** | 2 major versions |
| **Docker Base Image** | temurin:17-jdk/jre-jammy | **temurin:21-jdk/jre-jammy** | LTS upgrade |

### Dependencias Removidas
| Componente | Motivo |
|---|---|
| **JUnit 4** (4.13.2) | Nao mais necessario. TestContainers 1.20+ nao depende de JUnit 4 |
| **Micrometer** (versao explicita) | Gerenciado pelo Spring Boot 3.5.11 |
| **HikariCP** (versao explicita) | Gerenciado pelo Spring Boot 3.5.11 |
| **Flyway** (versao explicita) | Gerenciado pelo Spring Boot 3.5.11 |
| **JUnit/Mockito** (versao explicita) | Gerenciado pelo Spring Boot 3.5.11 |

---

## Ganhos

### Performance
- **Java 21 LTS**: ~6% de melhoria geral em benchmarks (JIT otimizado, GC melhorado)
- **Virtual Threads (Project Loom)**: Disponivel para uso futuro em operacoes I/O (WebSocket connections, DB queries)
- **G1 GC melhorado**: Region size ate 512MB (antes 32MB), bitmap unico (economia de ~1.5% heap)
- **Generational ZGC**: Alternativa ao G1 para heaps grandes com latencia ultra-baixa
- **PostgreSQL 17**: Vacuum 20x mais eficiente, ate 2x melhor throughput de escrita (WAL processing)
- **Aeron 1.46.7**: Publication Revoke para shutdown mais rapido, melhor observabilidade
- **Netty 4.1.118**: Melhorias de performance de I/O e event loop

### Seguranca
- **Spring Boot 3.5.11**: Voltamos a receber security patches (3.2 era EOL)
- **PostgreSQL JDBC 42.7.5**: Correcoes de seguranca (channel binding validation)
- **QuickFIX/J 2.3.2**: Fix para infinite loop/OOM em mensagens FIX malformadas
- **Netty 4.1.118**: 14 releases de security patches

### Produtividade
- **Java 21**: Record Patterns, Pattern Matching for switch, Sequenced Collections
- **MapStruct 1.6.3**: Melhor suporte a records
- **Lombok 1.18.36**: Compatibilidade total com JDK 21-24
- **Spring Boot 3.5**: Melhor auto-configuration, Hibernate dialect auto-detection

### Operacional
- **PostgreSQL 17**: Melhorias no query planner, COPY 2x mais rapido para bulk loading
- **Spring Boot 3.5**: Prometheus/Micrometer atualizados automaticamente
- **TestContainers 1.20.4**: Melhor performance, novas features

---

## Riscos e Mitigacoes

### Risco ALTO
| Risco | Impacto | Mitigacao |
|---|---|---|
| Regressao de latencia no hot path (Aeron IPC, matching engine) | Critico para HFT | Executar benchmarks JMH antes/depois. Monitorar GC logs com `-Xlog:gc*` |
| Incompatibilidade de `--add-opens` flags com Java 21 | Falha na inicializacao | Flags ja declaradas no Dockerfile. Validar com Aeron/Agrona/Chronicle |
| Chronicle Queue 5.27 pode ter formato de persistencia diferente | Perda de dados de queue | Testar leitura de queues existentes. Como nao ha producao, risco baixo |

### Risco MEDIO
| Risco | Impacto | Mitigacao |
|---|---|---|
| Spring Boot 3.5 deprecou APIs usadas indiretamente | Warnings em compilacao | Corrigir deprecation warnings antes de ir para producao |
| Flyway gerenciado pelo Spring Boot pode ter versao diferente da esperada | Falha em migrations | Testar `flyway migrate` contra banco limpo e com dados |
| Hibernate dialect auto-detection pode escolher dialect errado | Queries incorretas | Removemos dialect explicito. Hibernate 6.4+ detecta corretamente para PostgreSQL |

### Risco BAIXO
| Risco | Impacto | Mitigacao |
|---|---|---|
| Guava 33.x remove APIs deprecated | Erro de compilacao | Revisado: nenhuma API removida e usada no projeto |
| TestContainers 1.20 muda comportamento de containers | Falha em testes de integracao | Testar `mvn test` no modulo hft-persistence |
| Netty 4.1.118 muda comportamento do event loop | Performance diferente no TCP server | Backward compatible na linha 4.1 |

---

## Tradeoffs

### Por que Spring Boot 3.5.11 e nao 4.0.3?

**Spring Boot 4.0** traz mudancas muito agressivas para um sistema indo para producao:
1. **Jackson 3.x**: Muda groupId de `com.fasterxml.jackson` para `tools.jackson`. `JacksonException` nao extende mais `IOException`. Isso quebraria TODO o parsing de market data (Binance/Coinbase WebSocket)
2. **QuickFIX/J 2.3.2 nao e compativel com Jackson 3** (QuickFIX/J 3.0 e apenas SNAPSHOT)
3. **Chronicle Queue** pode nao ter sido testado com Spring Framework 7
4. **JUnit 6**: Annotations e lifecycle methods diferentes

**Spring Boot 3.5.11** e a escolha correta porque:
- E o bridge release oficial para 4.0 (depreca tudo que sera removido)
- Suportado ate Jun/2026
- Estavel e maduro (11 patch releases)
- Mantem Jackson 2.x (compativel com todas as libs)
- Da tempo para o ecossistema amadurecer (QuickFIX/J 3.0, etc.)

### Por que nao Agrona 2.x?

Agrona 2.x tem breaking changes (`UnsafeAccess` removido, etc.), mas o Aeron 1.46.7 ainda depende do Agrona 1.x. Usar Agrona 2.x causaria conflito com o uber-jar do `aeron-all`. Quando Aeron migrar para Agrona 2.x, atualizaremos ambos juntos.

---

## Mudancas nos Arquivos de Configuracao

### application.yml
- **Removido**: `hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect` (auto-detected em Hibernate 6.4+)
- **Atualizado**: `server.tomcat.max-threads` -> `server.tomcat.threads.max` (deprecado em Spring Boot 3.3)
- **Atualizado**: `logging.file.max-size/max-history` -> `logging.logback.rollingpolicy.max-file-size/max-history`
- **Reestruturado**: `management.metrics.export.prometheus` -> `management.prometheus.metrics.export`

### Dockerfile
- **Atualizado**: Base images para `eclipse-temurin:21-jdk/jre-jammy`
- **Removido**: `-XX:+ParallelRefProcEnabled` (default em Java 21, gera warning se explicito)

### docker-compose.yml
- **Atualizado**: PostgreSQL `15-alpine` -> `17-alpine`

---

## Validacao Recomendada

Antes de ir para producao, execute:

```bash
# 1. Compilacao
mvn clean compile

# 2. Testes unitarios
mvn test

# 3. Testes de integracao (requer Docker)
mvn verify -pl hft-persistence

# 4. Build completo
mvn clean package -DskipTests

# 5. Docker build
docker compose build

# 6. Smoke test
docker compose up -d
curl http://localhost:8080/actuator/health

# 7. Benchmark de latencia (critico para HFT)
# Executar JMH benchmarks no OrderMatchingEngine e Aeron IPC
# Comparar com baseline da versao anterior
```

---

## Roadmap Futuro

| Quando | O Que | Por Que |
|---|---|---|
| **Proximo Sprint** | Habilitar Virtual Threads para WebSocket connections | Simplifica modelo de threading, melhor throughput I/O |
| **Q2 2026** | Avaliar Generational ZGC como alternativa ao G1 | Latencia de GC potencialmente menor para heaps grandes |
| **Apos Jun/2026** | Migrar para Spring Boot 4.0.x | 3.5 vai EOL. Jackson 3 migration sera necessaria |
| **Quando disponivel** | QuickFIX/J 3.0 GA + Spring Boot 4.0 | Compatibilidade completa com Jackson 3.x |
