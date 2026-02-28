---
created: 2026-02-28T02:55:11.728Z
title: Update project architecture docs
area: docs
files:
  - docs/architecture/README.md
  - docs/architecture/02-containers.md
  - docs/architecture/03-components.md
  - docs/architecture/08-observability.md
  - docs/architecture/09-deployment.md
---

## Problem

Os docs de arquitetura existentes (01 a 09) nao mencionam o modulo `hft-synthetic-monitoring`. Apos a implementacao do monitor, os seguintes documentos precisam ser atualizados:

- **README.md**: Adicionar synthetic monitoring na tabela de tecnologias e no indice de docs
- **02-containers.md**: Adicionar o container hft-synthetic-monitoring no diagrama C4 Level 2
- **03-components.md**: Mencionar o monitor como componente externo que valida o sistema
- **08-observability.md**: Documentar as metricas sinteticas e o dashboard Grafana do monitor
- **09-deployment.md**: Atualizar docker-compose section com o novo servico

Alem disso, o root pom.xml tera um novo modulo — docs que referenciam a lista de modulos precisam refletir isso.

## Solution

Apos completar a implementacao do synthetic monitoring (Phase 6), fazer um pass nos docs de arquitetura atualizando referencias ao novo modulo. Pode ser uma fase adicional ou um todo pos-milestone.
