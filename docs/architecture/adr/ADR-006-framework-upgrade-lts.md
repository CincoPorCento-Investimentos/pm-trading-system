# ADR-006 — Framework Upgrade to Latest LTS Versions

**Status:** Accepted
**Date:** 2026-02
**Deciders:** Platform Architecture Team

---

## Context

A plataforma Crypto HFT estava rodando com **Spring Boot 3.2.1 (EOL)**, **Java 17**, e diversas dependências desatualizadas. Com o sistema ainda **não em produção**, havia uma janela ideal para atualizar tudo de uma vez antes do lançamento, eliminando débito técnico acumulado e voltando a receber security patches.

Problemas identificados:
- **Spring Boot 3.2.1**: End-of-life, sem security patches
- **Java 17**: Sem acesso a Virtual Threads, Pattern Matching, Generational ZGC
- **Netty 4.1.104**: 14 releases de security/performance patches pendentes
- **QuickFIX/J 2.3.1**: Vulnerabilidade de infinite loop/OOM em mensagens FIX malformadas
- **Dependências com versão explícita** (HikariCP, Flyway, Micrometer, JUnit/Mockito) conflitando com gerenciamento do Spring Boot

---

## Decision

Atualizar **todos os frameworks** para as versões mais recentes estáveis (LTS), com as seguintes escolhas-chave:

### Core Platform
| Componente | Antes | Depois |
|---|---|---|
| **Java** | 17 | **21 (LTS)** |
| **Spring Boot** | 3.2.1 (EOL) | **3.5.11** |
| **Docker base** | temurin:17 | **temurin:21** |
| **PostgreSQL (Docker)** | 15-alpine | **17-alpine** |

### High-Performance Trading Stack
| Componente | Antes | Depois |
|---|---|---|
| **Aeron** | 1.42.1 | **1.46.7** |
| **SBE** | 1.29.0 | **1.33.2** |
| **Agrona** | 1.20.0 | **1.23.1** |
| **Netty** | 4.1.104 | **4.1.118** |
| **QuickFIX/J** | 2.3.1 | **2.3.2** |
| **Chronicle Queue** | 5.24.60 | **5.27.3** |

### Utilities & Testing
| Componente | Antes | Depois |
|---|---|---|
| **PostgreSQL JDBC** | 42.7.1 | **42.7.5** |
| **Guava** | 32.1.3-jre | **33.4.0-jre** |
| **Lombok** | 1.18.30 | **1.18.36** |
| **MapStruct** | 1.5.5.Final | **1.6.3** |
| **TestContainers** | 1.19.3 | **1.20.4** |
| **Java-WebSocket** | 1.5.4 | **1.6.0** |
| **Maven Compiler** | 3.11.0 | **3.13.0** |

### Dependências Removidas
| Dependência | Motivo |
|---|---|
| **JUnit 4** (4.13.2) | TestContainers 1.20+ não precisa |
| **Micrometer** (versão explícita) | Gerenciado pelo Spring Boot |
| **HikariCP** (versão explícita) | Gerenciado pelo Spring Boot |
| **Flyway** (versão explícita) | Gerenciado pelo Spring Boot |
| **Mockito/JUnit** (versão explícita) | Gerenciado pelo Spring Boot |

### Mudanças em Configuração

**application.yml:**
- Removido `hibernate.dialect` (auto-detectado em Hibernate 6.4+)
- `server.tomcat.max-threads` → `server.tomcat.threads.max`
- `logging.file.*` → `logging.logback.rollingpolicy.*`
- `management.metrics.export.prometheus` → `management.prometheus.metrics.export`

**Dockerfile:**
- Base images para `eclipse-temurin:21-jdk/jre-jammy`
- Removido `-XX:+ParallelRefProcEnabled` (default em Java 21)

**docker-compose.yml:**
- PostgreSQL `15-alpine` → `17-alpine`

---

## Rationale

### Por que Spring Boot 3.5.11 e não 4.0.3?

Spring Boot 4.0 usa **Jackson 3.x** que troca o namespace de `com.fasterxml.jackson` → `tools.jackson`. Isso quebraria:
1. **Parsing JSON de market data** — Binance/Coinbase WebSocket messages
2. **QuickFIX/J 2.3.2** — incompatível com Jackson 3 (QuickFIX/J 3.0 ainda é SNAPSHOT)
3. **Chronicle Queue** — não testado com Spring Framework 7
4. **JUnit 6** — annotations e lifecycle methods diferentes

Spring Boot **3.5.11** é a escolha correta porque:
- É o bridge release oficial para 4.0 (depreca tudo que será removido)
- Suportado até Jun/2026
- Estável e maduro (11 patch releases)
- Mantém Jackson 2.x (compatível com todas as libs)

### Por que não Agrona 2.x?

Agrona 2.x tem breaking changes (`UnsafeAccess` removido, `MemoryAccess` removido, `SigInt` removido), mas Aeron 1.46.7 ainda depende do Agrona 1.x internamente. O uber-jar `aeron-all` bundla Agrona 1.x — usar Agrona 2.x separadamente causaria conflito no classpath.

---

## Consequences

**Positive:**
- **Performance**: Java 21 traz ~6% de melhoria geral, Virtual Threads disponíveis, G1 GC melhorado
- **Segurança**: Voltamos a receber security patches (Spring Boot, Netty, JDBC, QuickFIX/J)
- **Produtividade**: Record Patterns, Pattern Matching for switch, Sequenced Collections
- **Operacional**: PostgreSQL 17 com vacuum 20x mais eficiente, 2x write throughput

**Negative:**
- Regressão de latência potencial no hot path (Aeron IPC, matching engine) — requer benchmark JMH
- `--add-opens` flags necessárias com Java 21 (strong encapsulation)
- Chronicle Queue 5.27 pode ter formato de persistência diferente (sem impacto — não há dados em produção)

**Trade-offs accepted:**
- Spring Boot 3.5 em vez de 4.0: estabilidade do ecossistema sobre features mais recentes
- Agrona 1.x em vez de 2.x: compatibilidade com Aeron sobre APIs mais modernas
- Upgrade massivo em vez de incremental: justificado por não ter produção ainda

---

## Risks

| Risco | Severidade | Mitigação |
|---|---|---|
| Regressão de latência no hot path | **Alta** | Benchmark JMH antes/depois de cada deploy |
| `--add-opens` flags com Java 21 | Média | Flags já declaradas no Dockerfile, validadas |
| Chronicle Queue formato de persistência | Baixa | Não há dados em produção |
| Flyway auto-gerenciada pelo Spring Boot | Baixa | Migrations SQL existentes compatíveis |
| Hibernate dialect auto-detection | Baixa | Hibernate 6.4+ detecta PostgreSQL corretamente |

---

## Alternatives Rejected

- **Spring Boot 4.0.3**: Jackson 3.x namespace migration quebraria market data parsing e QuickFIX/J
- **Agrona 2.x**: Conflito de classpath com `aeron-all` que bundla Agrona 1.x
- **Upgrade incremental**: Desnecessário — sem produção, sem risco de downtime
- **Manter Java 17**: Perder Virtual Threads, melhorias de GC e 6% de performance
