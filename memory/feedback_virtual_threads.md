---
name: feedback-virtual-threads
description: Preferência por virtual threads e ScopedValue em vez de ThreadLocal no projeto Java 25
metadata:
  type: feedback
---

Usar `ScopedValue` em vez de `ThreadLocal` para contexto de request (ex: CorrelationIdHolder, RequestContext).

**Why:** Java 25 com virtual threads — `ScopedValue` é o mecanismo recomendado para valores imutáveis scoped a uma execução, evita bleed de ThreadLocal entre requests e funciona naturalmente com a filter chain do Spring Boot via `ScopedValue.where(...).run(() -> ...)`.

**How to apply:** Sempre que precisar propagar contexto de request (correlationId, IP, user-agent) dentro de uma filter chain, usar `ScopedValue` + habilitar `spring.threads.virtual.enabled=true` no `application.yml` para Tomcat usar virtual threads.

Relacionado: [[project_context]]