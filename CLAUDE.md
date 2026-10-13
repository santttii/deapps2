# Palco — guía para agentes

Trabajo práctico de Desarrollo de Aplicaciones II (UADE). Plataforma de venta de entradas con reventa oficial
(tope +10%). Lo evalúa una cátedra: **cada decisión tiene que poder defenderse por escrito y en vivo**, así que
preferí código simple y justificado antes que código ingenioso.

## Antes de tocar nada
1. Leé `TODO.md` (estado por fases) y `docs/PLAN_AGENTES.md` (qué te toca y qué no).
2. Trabajá solo en los módulos que tu tarea nombra. Si necesitás cambiar otro módulo, parate y avisá.
3. `backend/common` es compartido: no lo modifiques salvo que tu tarea lo diga explícitamente.

## Stack y estructura
- Backend: Jakarta EE 11 sobre WildFly 41, Java 25, Maven. **8 WAR independientes** en `backend/servicios/*`,
  uno por componente, cada uno con su API en `/api/<servicio>` y su esquema de base. Se hablan solo por REST o JMS,
  nunca por clases del otro servicio.
- Frontend: React + Vite + TypeScript en `frontend/` (hoy con datos mock en `localStorage`).
- Base: H2 en modo PostgreSQL en desarrollo, Supabase (PostgreSQL) después. Migraciones Flyway en
  `src/main/resources/db/migracion` de cada servicio; el SQL tiene que correr en las dos bases.

## Convenciones (copiá el estilo de `servicios/eventos` y `servicios/usuarios`)
- Capas por servicio: `api/` (recurso JAX-RS + DTOs, capa delgada) → `negocio/` (interfaz `I<Servicio>` + Session
  Bean) → `dominio/` (entidades JPA). El recurso valida y delega; **la lógica de negocio no va en el recurso**.
- Nombres y comentarios en español. Dinero en pesos enteros (`long`), nunca `double`.
- Reglas de negocio (tope, tasa, límites) solo en `common/ReglasDeNegocio`. No dupliques constantes.
- Errores con `ErroresDeNegocio` (los traduce `ManejadoresDeErrores`). Nunca devuelvas stack traces.
- Seguridad declarativa: `@RolesAllowed` / `@PermitAll` en cada operación del recurso. Roles: `user`,
  `organizer`, `staff`, `servicio` (llamadas entre servicios).
- Cada componente se justifica como stateless o stateful, y el comentario de la clase dice por qué.
- Mensajería: contrato en `docs/superpowers/specs/mensajeria-pago-confirmado.md`. No lo cambies sin avisar.

## Cómo verificar (no declares terminado sin correr esto)
```bash
cd backend && mvn -q test                # tests
cd backend && ./dev.sh                   # levanta WildFly con los 8 servicios
curl localhost:8080/api/<servicio>/health
cd frontend && npm run lint && npm run build
```
Si no podés correr algo, decilo explícitamente en tu reporte; no lo des por probado.

## Git
- Una rama por tarea: `feat/<servicio>-<tema>`. Commits chicos, en español, formato `tipo: qué cambió`.
- Los commits los hace la persona dueña de la tarea con su propia identidad de git (la cátedra revisa la
  participación de cada integrante). No cambies `user.name` / `user.email`.
- No hagas `push --force`, no reescribas historia, no commitees secretos (`.env`, certificados, tokens).

## Al terminar, reportá
Qué hiciste, qué archivos tocaste, cómo lo probaste (comando + resultado real), qué quedó pendiente o dudoso.
