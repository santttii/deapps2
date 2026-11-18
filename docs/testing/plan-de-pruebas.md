# Plan de pruebas de Palco — primera versión

3.er Checkpoint (20/11/2026). Describe qué se prueba, cómo, dónde y con qué criterio se da por
aprobado. Los resultados de esta versión salen de corridas reales sobre el código del checkpoint
(ver la sección 8).

## 1. Objetivo y alcance

Comprobar que Palco cumple sus requisitos funcionales (las historias de usuario HU-01 a HU-14) y los
de calidad que pide el checkpoint: integraciones funcionando (REST, SOAP y mensajería), resiliencia
(timeouts con fallback, consumidores idempotentes, DLQ, mensajes que sobreviven a la caída del
broker, `Idempotency-Key`, `ETag` y errores en Problem Details), la IA por su gateway y la seguridad.

**Dentro del alcance:** los 9 servicios del backend (8 de negocio y el asistente), el ARCA simulado,
la API REST, la mensajería JMS y el frontend web.

**Fuera del alcance de esta versión:** pruebas de carga y de rendimiento, la pasarela de pago real
(simulada, aceptado por la cátedra el 16/10) y navegadores móviles reales (el responsive se revisa
con el emulador del navegador).

## 2. Niveles de prueba

| Nivel | Qué prueba | Herramienta | Dónde está | Cuándo corre |
|---|---|---|---|---|
| Unitarias | La lógica de cada Session Bean, con los otros servicios como dobles | JUnit 5 + Mockito | `backend/**/src/test` | Cada `mvn verify` y en el CI |
| Integración con base | Repositorios y migraciones contra una base real (H2 en modo PostgreSQL), incluida la concurrencia | JUnit 5 + H2 | Tests `*EnH2Test` | Cada `mvn verify` y en el CI |
| Integración de sistema | Recorridos completos por la API contra WildFly, la base y el broker, sin dobles | Node (`escenarios.mjs`) + jboss-cli | `scripts/pruebas/escenarios.mjs` (24 escenarios) | A mano y en el CI (job "Integración") |
| Resiliencia | Timeout de 5 s con fallback, caída del broker, ETag | Node | `scripts/pruebas/asistente.mjs`, `broker-apagado.mjs`, `etag.mjs` | En el CI (job "Integración") y a mano |
| IA | Set de evaluación de 20 casos (15 funcionales, 5 adversariales) | JUnit 5 | `EvaluacionDelAsistenteTest` | Cada `mvn verify` |
| API | Cada endpoint: caso feliz, validaciones, 401, 403, 404, 409 | Postman / Newman | `docs/api/palco-api.postman_collection.json` | A mano |
| Seguridad | Los 10 criterios SEC-xx (Dado / Cuando / Entonces) | Postman (carpeta 4) + tests | Notion, etiqueta "seguridad" | A mano y con los tests |
| Patrón State | Transiciones válidas e inválidas (409) de la venta | JUnit + Node | `EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs` | `mvn verify` y en el CI |
| Frontend | Lint y compilación (tipos) | oxlint + tsc + Vite | `frontend/` | En el CI |
| Interfaz | Factura, editar y cancelar evento, subida de fotos: recorridos en el navegador contra el servidor | Playwright | `evidencia/checkpoint-3/pantallas.txt` y `fotos.txt` | A mano antes de cada entrega |
| Secretos | Que no haya credenciales en el historial | gitleaks | CI, job "Secretos" | En cada push |

## 3. Entornos

| Entorno | Base | Cómo se levanta | Para qué |
|---|---|---|---|
| Desarrollo | H2 en memoria | `backend/dev.sh` | Trabajo diario y pruebas a mano |
| Compose | PostgreSQL 17 | `docker compose up --build` | Pruebas de sistema como en producción, demo |
| CI | PostgreSQL 17 (compose) | GitHub Actions, `.github/workflows/ci.yml` | En cada push a master y en cada pull request |

## 4. Criterios

**De entrada:** el código compila (`mvn verify`) y el servidor arranca con los 10 WAR desplegados y
todos los `health` en 200.

**De aprobación (salida):**

- 0 tests unitarios fallando.
- Los 24 escenarios de integración pasan completos.
- La matriz de trazabilidad sin criterios en falla ni sin caso.
- Las pruebas de resiliencia pasan completas.
- El set de evaluación de la IA: al menos 18 de 20 y los 5 adversariales sin falla.
- El CI en verde sobre `master`.
- Ningún defecto bloqueante abierto.

**De suspensión:** si el servidor no despliega (como DEF-01), se frenan las pruebas de sistema hasta
corregirlo; las unitarias siguen.

## 5. Casos por requisito del checkpoint

| Requisito | Caso de prueba | Comando | Resultado esperado |
|---|---|---|---|
| Integración REST | Escenarios 1 a 6, 14, 17 a 21 | `node scripts/pruebas/escenarios.mjs` | PASA |
| Integración SOAP | Escenario 15 (factura con CAE de ARCA) | ídem | PASA |
| Mensajería: cola y tópico | Escenarios 7, 9 | ídem | PASA |
| Timeout de 5 s con fallback | El modelo tarda 8 s: responde el fallback a los 5 s | `node scripts/pruebas/asistente.mjs` | FALLBACK, motivo `timeout`, menos de 7 s |
| Consumidor idempotente | Escenario 8 (mensaje repetido: una sola emisión) | `escenarios.mjs` | PASA |
| DLQ | Escenario 12 (mensaje inválido: 5 reintentos y DLQ) | `escenarios.mjs` | PASA |
| Broker apagado sin perder mensajes | Mensaje pendiente, reinicio del servidor, se emite al volver | `node scripts/pruebas/broker-apagado.mjs` | 10 de 10 |
| Cancelación de un evento | Compras anuladas, pagos devueltos, reventa retirada y aviso | Escenario 22 | PASA |
| Reglas entre compras | Tope de 6 por comprador y evento; un DNI por evento, también al pagar | Escenario 23 | PASA |
| Idempotency-Key | Escenario 2 (misma clave no duplica) | `escenarios.mjs` | PASA |
| ETag | En los 10 GET principales: 200 con ETag; 304 con `If-None-Match`; ETag nuevo si cambia | `node scripts/pruebas/etag.mjs` | 13 de 13 |
| Problem Details | Escenarios 4 y 5 (cada error con su `type`) | `escenarios.mjs` | PASA |
| IA por su gateway | Guardrails, timeout, fallback y apagado en vivo | `asistente.mjs` | 11 de 11 |
| IA: set de evaluación | 20 casos | `mvn -pl servicios/asistente -am test` | 20 de 20 |
| Patrón State con 409 | Pagar o cancelar una venta paga; pagar una cancelada | `node scripts/pruebas/patron-state.mjs` | 10 de 10 |
| Seguridad JWT y roles | Escenarios 5 y 21 | `escenarios.mjs` | PASA |

## 6. Cobertura

JaCoCo mide la cobertura de los tests unitarios y de integración con base en cada `mvn verify`. El
reporte de cada módulo queda en `backend/<módulo>/target/site/jacoco/index.html`, el CI lo sube como
artefacto y `node scripts/qa/cobertura.mjs` arma el resumen, que el CI muestra en cada corrida.

Primera medición (18/11/2026): **78,0 % de las líneas y 73,6 % de las ramas** del backend. Los
módulos con menos cobertura son `common` (49 %) y Notificaciones (61 %), y son los próximos a
reforzar.

## 7. Defectos

Se registran en el tablero con su ID `DEF-xx`, severidad, pasos para reproducirlos y estado.

| ID | Defecto | Severidad | Estado |
|---|---|---|---|
| DEF-01 | El master del 30/10 no desplegaba ningún servicio | Bloqueante | Corregido el 05/11 |
| DEF-02 | Migraciones sobre H2 que se trababan en máquinas lentas | Menor | A verificar con `dev.sh` |
| DEF-03 | La prueba de integración desactualizada con los emails nuevos | Mayor | Corregido el 05/11 |
| DEF-04 | El registro de la puerta salía en orden inverso | Menor | Corregido el 11/11 (PR #4) |

## 8. Resultados de esta versión

Corrida del 18/11/2026 sobre el código del checkpoint, con WildFly 41, Java 25 y PostgreSQL 17:

| Prueba | Resultado |
|---|---|
| Tests unitarios y de integración con base | 817 tests, 0 fallas |
| Cobertura | 77,8 % de líneas, 73,6 % de ramas |
| Set de evaluación de la IA | 20 de 20; adversariales 5 de 5 |
| Asistente en vivo | 11 de 11 |
| Broker apagado | 10 de 10; no se perdió ningún mensaje |
| ETag | 13 de 13 |
| Patrón State | 10 de 10 |
| Escenarios de integración | 24 de 24; 379 de 379 comprobaciones |
| Pantallas nuevas en el navegador (factura, editar y cancelar evento) | 20 de 20 |
| Subida de fotos de eventos en el navegador (crear, cambiar y quitar la foto) | 13 de 13 |
| Matriz de trazabilidad | 62 de 62 criterios con caso, todos en verde |

La salida completa de cada corrida está en `docs/testing/evidencia/checkpoint-3/` y la matriz en
`docs/testing/evidencia/matriz-de-trazabilidad.md`. La matriz se arma con
`node scripts/qa/matriz.mjs --escenarios <salida de escenarios.mjs> --junit <cada carpeta surefire-reports>`.

## 9. Riesgos y pendientes

- Las pruebas en el navegador (Playwright) cubren las pantallas nuevas y no corren en el CI: el resto de la
  interfaz se prueba a mano con el manual de usuario.
- No hay pruebas de carga.
- El asistente usa un modelo simulado: el gateway, los guardrails, el timeout y el fallback son los reales, y
  pasar a un modelo de lenguaje real es sumar una implementación de `ModeloDeLenguaje`.
