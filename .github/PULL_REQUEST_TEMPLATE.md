# 📚 [Título descritivo da mudança]

> 🏷️ **Tipo:** `feature` | `fix` | `refactor` | `docs` | `chore`
> 🔗 **Issue:** #

---

## 🎯 O que foi feito?

<!--
📝 Como preencher:
- Resuma em 1-3 frases O QUE essa PR faz e POR QUÊ
- Foque no impacto pro usuário/sistema, não nos detalhes técnicos
- Exemplo:
  "Implementamos o fluxo de depósito via PIX, permitindo que usuários
  adicionem saldo à conta em menos de 30 segundos. Isso desbloqueava
  o módulo de trading que dependia de saldo disponível."
-->

---

## 🚀 Valor Gerado

<!--
📝 Como preencher:
- Preencha pelo menos UMA linha. Remova as que não se aplicam
- Seja específico e mensurável quando possível
- Ruim: "Melhora a experiência" | Bom: "Reduz tempo de checkout de 8s para 2s"
-->

| | Benefício |
| - | --------- |
| 👤 **Usuário** | <!-- Ex: Checkout 2x mais rápido, novo fluxo de depósito PIX --> |
| 🧑‍💻 **Dev Experience** | <!-- Ex: Onboarding de novos devs 3x mais rápido com docs modulares --> |
| 💰 **Negócio** | <!-- Ex: Desbloqueia módulo de trading, reduz churn no pagamento --> |
| ⚙️ **Operação** | <!-- Ex: Elimina 40% dos alertas falso-positivo no monitoring --> |

---

## 📋 Mudanças Principais

<!--
📝 Como preencher:
- Agrupe mudanças em categorias lógicas (use quantas precisar, remova as extras)
- Cada categoria tem um emoji que indica o tipo:
  ✨ Nova feature    🔧 Refatoração     🗃️ Migration
  🔌 Mudança de API  🐛 Correção        🧹 Limpeza/Remoção
  📚 Documentação    🎨 UI/Estilo       ⚡ Performance
- Liste arquivos/pastas relevantes em cada categoria
- Exemplo:
  #### 1️⃣ ✨ Fluxo de Depósito PIX
  - Criado `domains/core/cash/deposit/` com service, schema e testes
  - Novo endpoint `POST /api/deposit` com validação Zod
  - Componente `<DepositForm />` com feedback em tempo real
-->

### 1️⃣ [Emoji + Categoria]

### 2️⃣ [Emoji + Categoria]

### 3️⃣ [Emoji + Categoria]

---

## 💡 Por que essa abordagem?

<!--
📝 Como preencher:
- Explique as decisões técnicas e trade-offs
- A tabela Antes/Depois ajuda a mostrar o contraste — use quando fizer sentido
- Se não houve alternativas consideradas, descreva brevemente a motivação
- Exemplo:
  "Optamos por event sourcing no fluxo de depósito para ter auditoria
  completa. A alternativa era CRUD simples, mas perderíamos o histórico
  de estados intermediários que compliance exige."
-->

| Aspecto | Antes 😫 | Depois 🎯 |
| ------- | -------- | --------- |
| <!-- Ex: Navegação --> | <!-- Ex: 1 arquivo gigante --> | <!-- Ex: Índice + docs especializados --> |

---

## ✅ O que não mudou?

<!--
📝 Como preencher:
- Liste o que permanece intacto — isso dá confiança ao reviewer
- Foque em áreas que o reviewer poderia pensar que foram afetadas
- Exemplo:
  - 🔒 Fluxo de autenticação — zero alterações
  - 🔒 Schema do banco — nenhuma migration
  - 🔒 API pública — contratos mantidos
-->

- 🔒

---

## 🔍 Como Revisar?

<!--
📝 Como preencher:
- Dê um roteiro pro reviewer, na ordem ideal de leitura
- Comece pelo arquivo que dá contexto geral, depois aprofunde
- Exemplo:
  1. 👀 Leia `domains/core/cash/deposit/deposit.schema.ts` — entenda o contrato
  2. 👀 Veja `deposit.service.ts` — lógica de negócio
  3. 👀 Confira `deposit.test.ts` — cenários cobertos
  4. 👀 Revise `app/api/deposit/route.ts` — integração HTTP
-->

> 💡 **Dica:** comece pelo arquivo X para entender o contexto geral.

1. 👀
2. 👀
3. 👀

---

## 📊 Estatísticas

<!--
📝 Como preencher:
- Preencha com os números reais do diff (visíveis no GitHub)
- Em PRs grandes, adicione contexto: "898 linhas adicionadas (7 novos domínios)"
-->

| Métrica | Valor |
| ------- | ----- |
| 📁 Arquivos alterados | **X** |
| ➕ Linhas adicionadas | **X** |
| ➖ Linhas removidas | **X** |

---

## 🧪 Testes

<!--
📝 Como preencher:
- Marque com [x] o que foi validado
- Se testou manualmente, descreva o cenário brevemente
- Exemplo de teste manual: "Criei depósito de R$100 via PIX, confirmei
  que o saldo atualizou em <5s e o extrato mostra a transação"
-->

- [ ] ✅ Testes unitários passando
- [ ] 🔗 Testes de integração passando (se aplicável)
- [ ] 🖱️ Testado manualmente (descreva o cenário abaixo)
