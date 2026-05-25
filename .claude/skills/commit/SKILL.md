---
name: commit
description: >
  Use esta skill sempre que o usuário pedir para fazer um commit, preparar alterações para commit,
  gerar mensagem de commit, ou quando arquivos Java forem criados/modificados e precisarem ser
  versionados. Também aciona quando o usuário mencionar "git commit", "commitar", "versionar",
  "subir alterações", "criar commit", ou qualquer variação. Se houver classes Java novas, sempre
  inserir o cabeçalho Javadoc antes de commitar.
---

# Skill: Commit

## Regras Obrigatórias

### 1. Cabeçalho Javadoc em classes Java novas

Sempre que uma **nova classe Java** for criada (arquivo `.java` que não existia antes), inserir o cabeçalho logo abaixo da declaração do `package` (ou no topo caso não haja package), antes de qualquer `import`:

```java
/**
 * @author Andre
 * <DD> de <Mês por extenso em português>. de <AAAA>
 */
```

**Exemplo real:**
```java
package br.com.exemplo.service;
 
/**
 * @author Andre
 * 10 de Abril. de 2026
 */
public class ProdutoService {
    ...
}
```

- O formato do mês deve ser por extenso em português (Janeiro, Fevereiro, Março, Abril, Maio, Junho, Julho, Agosto, Setembro, Outubro, Novembro, Dezembro)
- Usar a data atual real do sistema ao criar o arquivo
- **Não inserir** em classes já existentes que estão apenas sendo modificadas
- **Não inserir** em interfaces, enums ou records a menos que o usuário peça explicitamente
---

### 2. Mensagem de commit

#### Formato obrigatório

```
<tipo>: <resumo curto e direto>
 
<corpo opcional com mais detalhes>
```

#### Tipos permitidos

| Tipo | Quando usar |
|------|-------------|
| `feat` | Nova funcionalidade |
| `fix` | Correção de bug |
| `refactor` | Refatoração sem mudança de comportamento |
| `chore` | Tarefas de manutenção, configs, dependências |
| `docs` | Documentação |
| `test` | Adição ou ajuste de testes |
| `style` | Formatação, espaçamento (sem lógica) |
| `perf` | Melhoria de performance |

#### Regras da mensagem

1. **Sempre em português** — sem termos em inglês desnecessários (nomes de classes/métodos podem permanecer)
2. **Sem referências a IA** — proibido mencionar Claude, ChatGPT, IA, inteligência artificial, "gerado por", "assistido por" ou qualquer variação
3. **Sem ponto final** na linha de resumo
4. **Imperativo** na primeira linha: "Adiciona", "Corrige", "Remove", "Refatora" — não "Adicionado", "Corrigido"
5. **Resumo claro** — descreva O QUE foi feito, não COMO
6. Corpo (opcional): use quando há mais de uma alteração relevante ou contexto importante
#### Exemplos corretos ✅

```
feat: Adiciona endpoint de consulta de clientes por CPF
```

```
fix: Corrige cálculo incorreto de juros no serviço de parcelas
 
O valor base estava sendo multiplicado antes da aplicação da taxa,
causando divergência nos contratos gerados após Março/2025.
```

```
refactor: Extrai lógica de validação de boleto para classe dedicada
```

```
chore: Atualiza dependências do Spring Boot para 3.2.5
```

#### Exemplos incorretos ❌

```
feat: Added new feature using AI assistance   ← inglês + referência a IA
fix: corrigido o bug.                          ← passado + ponto final
update: alterações diversas                   ← tipo inválido + vago demais
```
 
---

### 3. Fluxo ao preparar um commit

1. **Verificar arquivos alterados** — identificar quais são classes Java novas
2. **Inserir cabeçalho Javadoc** nas classes novas (se houver)
3. **Analisar o conjunto de mudanças** — entender o que foi feito
4. **Gerar a mensagem de commit** seguindo as regras acima
5. **Apresentar ao usuário** antes de executar o commit, salvo se ele pedir para commitar direto
---

### 4. Múltiplos contextos de mudança

Se as alterações cobrem mais de um contexto distinto (ex: correção de bug + nova feature), sugerir commits separados em vez de um único commit misturado.
 