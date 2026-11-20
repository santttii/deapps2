# Plan de pruebas de Palco (primera versión)

3.er Checkpoint (20/11/2026). Describe qué se prueba, cómo, dónde y con qué criterio se da por
aprobado. Los resultados de esta versión salen de corridas reales sobre el código del checkpoint
(ver la sección 8).

**Control de versiones**

| Versión | Fecha | Cambios | Autor/a |
|---|---|---|---|
| 0.1 | 18/11/2026 | Primera versión: alcance, niveles, entornos, criterios, casos por requisito, cobertura, defectos y resultados | Valentina Frisoli |
| 0.2 | 19/11/2026 | Correcciones (cobertura, DLQ, Problem Details, interfaz, asistente) y secciones nuevas: referencias, técnicas de diseño, prerrequisitos, cómo repetir las pruebas contra el compose, severidades y ciclo de vida de los defectos, riesgos, responsables y cronograma | Santiago Loto (revisión: Valentina Frisoli) |

**Referencias**

- Consigna del TP: `docs/consigna/` (sección 6, requisitos técnicos obligatorios).
- Historias de usuario y criterios de aceptación en Gherkin: `docs/documentacion/historias-de-usuario.md`.
- Documento único del proyecto: `docs/documentacion/Palco_Documentacion_Completa.md` (reparto en 18.1, calendario
  en 18.2).
- Contratos: `docs/api/openapi.yaml`, `docs/api/arca.wsdl`, `docs/api/mensajeria-pago-confirmado.md` y
  `docs/api/mensajeria-cancelacion-de-eventos.md`.
- Colección de Postman: `docs/api/palco-api.postman_collection.json` (guía en `docs/api/GUIA_POSTMAN.md`).
- Matriz de trazabilidad: `docs/testing/evidencia/matriz-de-trazabilidad.md`; evidencia de las corridas:
  `docs/testing/evidencia/checkpoint-3/`.
- Manual de usuario: `docs/manual/manual-de-usuario.md`.

## 1. Objetivo y alcance

Comprobar que Palco cumple sus requisitos funcionales (las historias de usuario HU-01 a HU-14) y los
de calidad que pide el checkpoint: integraciones funcionando (REST, SOAP y mensajería), resiliencia
(timeouts con fallback, consumidores idempotentes, DLQ, mensajes que sobreviven a la caída del
broker, `Idempotency-Key`, `ETag` y errores en Problem Details), la IA por su gateway y la seguridad.

**Dentro del alcance:** los 9 servicios del backend (8 de negocio y el asistente), el ARCA simulado,
la API REST, la mensajería JMS y el frontend web.

**Fuera del alcance de esta versión:** la pasarela de pago real (simulada, aceptado por la cátedra el
16/10) y navegadores móviles reales (el responsive se revisa con el emulador del navegador). Las pruebas de
contrato y de carga están planificadas (sección 2) y no forman parte de los resultados de esta versión.

## 2. Niveles de prueba

| Nivel | Qué prueba | Herramienta | Dónde está | Cuándo corre |
|---|---|---|---|---|
| Unitarias | La lógica de cada Session Bean, con los otros servicios como dobles | JUnit 5 + Mockito | `backend/**/src/test` | Cada `mvn verify` y en el CI |
| Integración con base | Repositorios y migraciones contra una base real (H2 en modo PostgreSQL), incluida la concurrencia | JUnit 5 + H2 | Tests `*EnH2Test` | Cada `mvn verify` y en el CI |
| Integración de sistema | Recorridos completos por la API contra WildFly, la base y el broker, sin dobles | Node (`escenarios.mjs`) + jboss-cli | `scripts/pruebas/escenarios.mjs` (24 escenarios) | A mano y en el CI (job "Integración") |
| Resiliencia | Timeout de 5 s con fallback, caída del broker, ETag | Node | `scripts/pruebas/asistente.mjs`, `broker-apagado.mjs`, `etag.mjs` | En el CI (job "Integración") y a mano |
| IA | Set de evaluación de 26 casos (15 funcionales, 11 adversariales) con el modelo simulado; los guardrails, con intentos de inyección y preguntas legítimas que no se tienen que bloquear; el adaptador del modelo real, sin red | JUnit 5 | `EvaluacionDelAsistenteTest`, `GuardrailsTest`, `ModeloCompatibleOpenAITest` | Cada `mvn verify` |
| API | Los endpoints de la API, en carpetas: casos felices y de error (validaciones, 401, 403, 404, 409, entre otros) | Postman / Newman | `docs/api/palco-api.postman_collection.json` (la genera `scripts/postman/generar.js`) | A mano |
| Seguridad | Los 10 criterios SEC-xx (Dado / Cuando / Entonces) | Postman (carpeta 4, con el id SEC-… de cada criterio) + tests | Notion, etiqueta "seguridad" | A mano y con los tests |
| Patrón State | Transiciones válidas e inválidas (409) de la venta | JUnit + Node | `EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs` | `mvn verify` y en el CI |
| Frontend | Lint y compilación (tipos) | oxlint + tsc + Vite | `frontend/` | En el CI |
| Interfaz (E2E) | Recorridos en el navegador contra el sistema levantado | Playwright | `frontend/e2e/` (`npm run e2e` en `frontend/`) | En el CI (job "E2E") y a mano antes de cada entrega |
| Secretos | Que no haya credenciales en el historial | gitleaks | CI, job "Secretos" | En el CI |
| Contrato REST | Que cada respuesta cumpla `docs/api/openapi.yaml`: códigos documentados, esquemas y `application/problem+json` en los errores, con casos que la herramienta genera desde el contrato | Schemathesis | Todavía no está en el repositorio | **Planificado**, contra el compose, antes de la Entrega Final (03/12) |
| Contrato SOAP | Que el ARCA simulado cumpla `docs/api/arca.wsdl`: `FECAESolicitar` aprobado, rechazado (`Resultado` R) y con Fault, y que cada respuesta valide contra el esquema del WSDL | SoapUI | Todavía no está en el repositorio | **Planificado**, contra el compose, antes de la Entrega Final (03/12) |
| Carga | Que el catálogo y la compra aguanten una salida a la venta: 50 usuarios virtuales durante 5 minutos | k6 | Todavía no está en el repositorio | **Planificado**, contra el compose, antes de la Entrega Final (03/12) |

### Pruebas planificadas: contrato y carga

Estas tres no se corrieron en esta versión y no tienen resultados en la sección 8. Lo que no cumpla su criterio se
registra en `docs/testing/registro-de-defectos.md`.

- **Contrato REST, con Schemathesis.** Contra el compose levantado, con `docs/api/openapi.yaml` (versión 1.3.0 de la
  API) y un token por rol (`user`, `organizer` y `staff`, de las cuentas de prueba). Comprueba que cada código de
  estado y cada cuerpo estén en el contrato y que los errores salgan en Problem Details. Las operaciones que cobran
  usan los datos de la sección 3 (`tok_test`). Criterio: ninguna respuesta fuera del contrato; cada diferencia se
  corrige en el código o en el contrato, o queda registrada.
- **Contrato SOAP, con SoapUI.** Un proyecto con `docs/api/arca.wsdl` contra `/legado/arca`: un comprobante aprobado,
  uno rechazado y uno con Fault, con la aserción de cumplimiento del esquema en cada respuesta, y la comprobación de
  que el WSDL publicado (`/legado/arca?wsdl`) es el del repositorio.
- **Carga, con k6.** 50 usuarios virtuales durante 5 minutos sobre `GET /api/eventos`, `GET /api/eventos/{slug}` y
  abrir una compra (`POST /api/v1/ventas`) que después se cancela (`DELETE /api/v1/ventas/{id}`), para no agotar el
  cupo. Cada usuario virtual tiene su propia cuenta, creada antes de la corrida como en los escenarios: con una sola
  cuenta, el tope de 6 entradas por persona y evento cortaría las compras. Cada uno ingresa una sola vez, al
  principio: el ingreso no se usa como carga, porque PBKDF2 es lento a propósito y 5 intentos fallidos en 15 minutos
  dan 429. Umbrales: el 95 % de los pedidos en menos de 500 ms y menos del 1 % de errores. Corre en una máquina que no
  se use para otra cosa mientras tanto.

### Técnicas de diseño de casos

Los criterios de aceptación (Gherkin, con códigos HTTP y números concretos) dicen qué se prueba. Los casos
salen de estas técnicas:

- **Clases de equivalencia.** Cada regla parte los datos en clases válidas e inválidas y se prueba un
  representante de cada una. Cantidad por compra: de 1 a 6 es válida; 0 da 400 `datos-invalidos` y 7 da 422
  `maximo-de-entradas` (escenario 4 y `ServicioDeVentasTest#masDeSeisEntradasRompeLaReglaDelMaximoYNoSeTocaNada`).
  DNI: 7 u 8 dígitos es válido; 6, 9, con letras o vacío, no (`ReglasDeNegocioTest#unDniValidoSonSieteUOchoDigitosYNadaMas`
  y el DNI `12AB` del escenario 4).
- **Valores límite.** Se prueba justo en el borde y un paso afuera:

| Regla | En el borde (se acepta) | Un paso afuera (se rechaza) | Caso |
|---|---|---|---|
| Tope de la reventa: 110 % del precio de lista del sector | Publicar al tope exacto | Tope + $1: 422 `precio-fuera-de-rango` | `ServicioDeReventaTest#losBordesDelRangoSeAceptanYUnPesoAfueraNo`; el redondeo hacia abajo (1.001 → 1.101), en `ReglasDeNegocioTest#topeReventaEsLoPagadoMasDiezPorCientoRedondeadoHaciaAbajo` |
| Mínimo de la reventa: 50 % | Publicar al mínimo exacto | Mínimo − $1: 422 `precio-fuera-de-rango` | El mismo test; el redondeo hacia arriba (1.001 → 501), en `ReglasDeNegocioTest#minimoReventaEsLaMitadRedondeadaHaciaArriba` |
| 6 entradas por comprador y evento, sumando compras | Con 4, comprar 2 | Con 5, comprar 2; con 6, comprar 1: 409 `tope-por-evento` | `ServicioDeVentasTest#conElTopeJustoLaCompraSeAbre`, `#conLasDeOtrasComprasNoSePuedePasarDeSeisEntradasPorEvento`, `#conSeisEntradasYaNoPuedeComprarMasParaEseEvento`; escenario 23 |
| Cierre de la reventa 3 horas antes del evento | Faltan 3 h 1 min: se publica | Faltan 3 h justas: 409 `reventa-cerrada` | `ServicioDeReventaTest#laReventaCierraTresHorasAntesDelEvento` |
| Reserva de 10 minutos | Un segundo antes de vencer: se paga | En el segundo exacto: 409 `reserva-vencida` | `ServicioDeVentasTest#unSegundoAntesDeQueVenzaLaReservaTodaviaSePuedePagar`, `#conLaReservaVencidaNoSeCobra` |
| 5 intentos de ingreso fallidos en 15 minutos | No aplica | El sexto, aun con la contraseña correcta: 429 | `LimiteDeIntentosDeLoginTest#cincoFallidosYElSextoConLaClaveBuenaRecibe429`, `#laVentanaEsDeQuinceMinutos` |

- **Transición de estados.** La venta (patrón State): cada transición válida avanza y cada inválida responde 409
  (`EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs`).
- **Casos negativos y de seguridad.** Sin token, token alterado, rol equivocado, recurso ajeno (responde 404, como
  si no existiera), inyección en el log y en la búsqueda (escenarios 4, 5, 21 y 24).

## 3. Entornos

| Entorno | Base | Cómo se levanta | Para qué |
|---|---|---|---|
| Desarrollo | H2 en memoria | `backend/dev.sh` | Trabajo diario y pruebas a mano |
| Compose | PostgreSQL 17 | `docker compose up --build` | Pruebas de sistema como en producción, demo |
| CI | PostgreSQL 17 (compose) | GitHub Actions, `.github/workflows/ci.yml` | En cada push a master y en cada pull request |

**Datos de prueba.** Las migraciones cargan seis eventos (`ev-1` a `ev-6`) de `organizador@palco.test`, con
`puerta@palco.test` asignada a la puerta de los seis, y las cuentas `titular@palco.test`,
`otro.titular@palco.test`, `otro.organizador@palco.test` y `otra.puerta@palco.test` (contraseña `palco1234`).
La pasarela simulada aprueba `tok_test`, rechaza `tok_rechazado` y no contesta con `tok_sin_respuesta`; en la
pantalla, una tarjeta terminada en 0002 se rechaza y una terminada en 0504 no tiene respuesta. Sin
`PALCO_SMTP_HOST`, los emails (con el código de verificación) quedan en el log del servidor.

**Prerrequisitos para reproducir:**

- Docker con Compose v2 (Docker Desktop en Windows y macOS) y los puertos 80, 443 y 8080 libres.
- Node 22 (el del CI; los guiones de `scripts/` piden 18 o más nuevo y no tienen dependencias).
- JDK 25 y Maven 3.9 o más nuevo para `mvn verify`.
- En Windows, Git Bash para los guiones (trae `openssl`).
- Para la interfaz, en `frontend/`: `npm ci` y `npx playwright install chromium`.

## 4. Criterios

**De entrada:** el código compila (`mvn verify`) y el servidor arranca con los 10 WAR desplegados y
todos los `health` en 200.

**De aprobación (salida):**

- 0 tests unitarios fallando.
- Los 24 escenarios de integración pasan completos.
- La matriz de trazabilidad sin criterios en falla ni sin caso.
- Las pruebas de resiliencia pasan completas.
- El set de evaluación de la IA: como mucho 2 fallas y ninguna adversarial (al menos 24 de 26, con los 11 adversariales bien).
- El CI en verde sobre `master`.
- Defectos abiertos dentro del máximo de la cátedra: 0 bloqueantes, 0 críticos y como mucho 3 mayores,
  cada uno con su alternativa (`docs/testing/registro-de-defectos.md`).

**De suspensión:** si el servidor no despliega (como DEF-01), se frenan las pruebas de sistema hasta
corregirlo; las unitarias siguen.

**De reanudación:** cuando el servidor vuelve a desplegar con los 10 WAR y los `health` en 200, las pruebas de
sistema se repiten completas, no solo la que falló.

## 5. Casos por requisito del checkpoint

| Requisito | Caso de prueba | Comando | Resultado esperado |
|---|---|---|---|
| Integración REST | Escenarios 1 a 6, 14, 17 a 21 | `node scripts/pruebas/escenarios.mjs` | PASA |
| Integración SOAP | Escenario 15 (factura con CAE de ARCA) | ídem | PASA |
| Mensajería: cola y tópico | Escenarios 7, 9 | ídem | PASA |
| Timeout de 5 s con fallback | El modelo tarda 8 s: responde el fallback a los 5 s | `node scripts/pruebas/asistente.mjs` | FALLBACK, motivo `timeout`, menos de 7 s |
| Consumidor idempotente | Escenario 8 (mensaje repetido: una sola emisión) | `escenarios.mjs` | PASA |
| DLQ | Escenario 12 (mensaje inválido: 5 entregas, la original y 4 reintentos, una cada 2 s, y después la DLQ) | `escenarios.mjs` | PASA |
| Timeout del SOAP, DLQ y reproceso | ARCA sin responder: la compra se emite igual, cada pedido se corta a los 5 s, el mensaje de Facturación llega a la DLQ y, devuelto con `move-messages`, sale una sola factura | Contra el compose, con `palco.arca.simular-sin-respuesta=true` (sección 10.6 del documento único) | 29 de 29 |
| Broker apagado sin perder mensajes | Mensaje pendiente, reinicio del servidor, se emite al volver | `node scripts/pruebas/broker-apagado.mjs` | 10 de 10 |
| Broker con el contenedor recreado | Mensaje pendiente, `docker compose down` (sin `-v`) y `up`: se emite con el contenedor nuevo | Contra el compose (pasos en `scripts/LEEME.md`) | 16 de 16 |
| Cancelación de un evento | Compras anuladas, pagos devueltos, reventa retirada y aviso | Escenario 22 | PASA |
| Reglas entre compras | Tope de 6 por comprador y evento; un DNI por evento, también al pagar | Escenario 23 | PASA |
| Idempotency-Key | Escenario 2 (misma clave no duplica) | `escenarios.mjs` | PASA |
| ETag | En los 10 GET principales: 200 con ETag; 304 con `If-None-Match`; ETag nuevo si cambia | `node scripts/pruebas/etag.mjs` | 19 de 19 |
| Problem Details | Escenarios 3, 4, 6, 13 a 17 y 19 a 24 (cada error con su `status`, `type`, `title` y `detail`) | `escenarios.mjs` | PASA |
| IA por su gateway | Guardrails, timeout, fallback y apagado en vivo | `asistente.mjs` | 11 de 11 |
| IA: set de evaluación | 26 casos: 15 funcionales y 11 adversariales | `mvn -pl servicios/asistente -am test` (desde `backend/`) | 26 de 26; adversariales 11 de 11 |
| Patrón State con 409 | Pagar o cancelar una venta paga; pagar una cancelada | `node scripts/pruebas/patron-state.mjs` | 10 de 10 |
| Seguridad JWT y roles | Escenarios 5 y 21 | `escenarios.mjs` | PASA |

El escenario 5 comprueba los códigos (401, 403 y 404) en cada operación; el cuerpo Problem Details de los
errores lo comprueban los escenarios de la fila "Problem Details".

**El asistente y su modelo.** El gateway (guardrails, timeout de 5 s y fallback) es el mismo para cualquier
modelo. Hay dos, elegidos con `PALCO_ASISTENTE_MODELO` (ver `backend/.env.example`): `simulado`, el de por
defecto, con el que corren el set de evaluación y `asistente.mjs`, y `openai`, que usa
`ModeloCompatibleOpenAI` para hablar con cualquier proveedor que tenga la API de chat de OpenAI. Ese adaptador
se prueba en `ModeloCompatibleOpenAITest` con un proveedor falso, sin red: el pedido que arma, la clave solo si
está configurada, la respuesta y los errores del proveedor.

### Cómo repetirlas contra el compose

Desde la raíz del repo, en Bash (en Windows, Git Bash). El secreto entre servicios se genera en la misma
terminal y no se imprime ni se guarda: el compose se lo pasa al backend y los guiones lo usan para pedir el token
de rol `servicio`. Son las mismas variables que usa el job "Integración" del CI.

```bash
export PALCO_SECRETO_SERVICIOS="$(openssl rand -hex 32)"
export PALCO_DIR_LOGS=./logs PALCO_LOG=logs/server.log JBOSS_CLI=scripts/qa/jboss-cli-docker.sh
mkdir -p logs && chmod 777 logs        # el contenedor escribe server.log en esa carpeta
docker compose up --build --detach --wait

node scripts/pruebas/escenarios.mjs | tee escenarios.txt
node scripts/pruebas/etag.mjs
node scripts/pruebas/patron-state.mjs
node scripts/pruebas/asistente.mjs
(cd frontend && PALCO_E2E_URL=https://localhost PALCO_LOG="$(pwd)/../logs/server.log" npm run e2e)
node scripts/pruebas/broker-apagado.mjs   # va última: reinicia el servidor

docker compose down -v                    # al terminar; -v borra también la base y el journal del broker
```

La suite de la interfaz lee del log el código de verificación de las cuentas que crea; por eso necesita
`PALCO_LOG`, igual que el job "E2E" del CI.

El resto no necesita el compose. Cada línea se corre desde la raíz del repo:

```bash
(cd backend && mvn verify)                              # tests unitarios y con base, y JaCoCo
(cd backend && mvn -pl servicios/asistente -am test)    # solo el asistente y su set de evaluación
node scripts/qa/cobertura.mjs backend                   # resumen de cobertura
(cd frontend && npm run lint && npm run build)

# Matriz, con la salida de escenarios.mjs y los reportes de mvn verify:
node scripts/qa/matriz.mjs --escenarios escenarios.txt \
  $(for d in backend/common/target/surefire-reports backend/servicios/*/target/surefire-reports; do printf -- '--junit %s ' "$d"; done) \
  --salida docs/testing/evidencia/matriz-de-trazabilidad.md
```

`matriz.mjs` acepta también `--newman <reporte.json>` y `--playwright <reporte.json>`: los casos con un id
`CP-xx.y.z` en el nombre se suman al criterio que les corresponde.

## 6. Cobertura

JaCoCo mide la cobertura de los tests unitarios y de integración con base en cada `mvn verify`. El
reporte de cada módulo queda en `backend/<módulo>/target/site/jacoco/index.html`, el CI lo sube como
artefacto y `node scripts/qa/cobertura.mjs` arma el resumen, que el CI muestra en cada corrida.

Primera medición (18/11/2026): 77,8 % de las líneas (3520 de 4522) y 73,6 % de las ramas. Medición vigente
(19/11/2026): **78,4 % de las líneas (3671 de 4683) y 74,1 % de las ramas** del backend
(`docs/testing/evidencia/checkpoint-3/cobertura.md`). Los módulos con menos cobertura son `common` (50 %) y
Notificaciones (61 %), y son los próximos a reforzar.

## 7. Defectos

Los defectos están en `docs/testing/registro-de-defectos.md`: cada uno con su severidad y prioridad, los pasos para
reproducirlo, el resultado esperado y el obtenido, la evidencia, el estado y, si está cerrado, la causa raíz y el
commit que lo corrige. Ahí están también la escala de severidades (la de la cátedra), la de prioridades y el ciclo de
vida de un defecto.

Al 20/11/2026 hay 33 registrados: 27 cerrados, 1 corregido que falta verificar en una máquina lenta (DEF-02) y 5
abiertos. Los abiertos, contra el máximo que se admite al cierre (27/11):

| Severidad | Abiertos | Máximo al cierre | Situación |
|---|---|---|---|
| Bloqueante | 0 | 0 | Dentro del máximo |
| Crítica | 1: DEF-29, al cancelar un evento quien compró en la reventa no recibe la devolución | 0 | **Fuera del máximo**: se corrige antes del cierre |
| Mayor | 1: DEF-30, la compra en la reventa no aplica un DNI por evento ni el tope de 6; tiene alternativa | 3, cada uno con su alternativa | Dentro del máximo |
| Menor | 3: DEF-31 a DEF-33 | Sin máximo | Dentro del máximo |
| Cosmética | 0 | Sin máximo | Dentro del máximo |

## 8. Resultados de esta versión

Corrida del 19/11/2026 sobre el código del checkpoint, con WildFly 41, Java 25 y H2 (`backend/dev.sh`; la
prueba del broker apagado, con H2 en archivo para que la base sobreviva al reinicio; la del contenedor recreado y la
del ARCA sin responder, contra el compose):

| Prueba | Resultado |
|---|---|
| Tests unitarios y de integración con base | 966 tests, 0 fallas; 115 del asistente |
| Cobertura | 78,4 % de líneas, 74,1 % de ramas |
| Set de evaluación de la IA | 26 de 26; adversariales 11 de 11 |
| Asistente en vivo, con el modelo simulado | 11 de 11 |
| Asistente con un proveedor por HTTP (API de chat de OpenAI, proveedor local de prueba) | 29 de 29 |
| Broker apagado | 10 de 10; no se perdió ningún mensaje |
| Broker con el contenedor recreado | 16 de 16; no se perdió ningún mensaje |
| ARCA sin responder, DLQ y reproceso | 29 de 29; la compra no esperó a ARCA y la factura salió una vez |
| ETag e `If-Match` | 19 de 19 |
| Patrón State | 10 de 10 |
| Escenarios de integración | 24 de 24; 379 de 379 comprobaciones |
| Colección de Postman (Newman) | 171 requests (los 166 de la colección y los cinco intentos de login previos de SEC-W04), 541 aserciones, 0 fallas |
| Interfaz en el navegador (`frontend/e2e`, Playwright) | 17 de 17 |
| Matriz de trazabilidad | 62 de 62 criterios con caso, todos en verde (regenerada el 19/11 con los reportes de `mvn` de ese día y esta corrida de los escenarios) |

La salida completa de cada corrida está en `docs/testing/evidencia/checkpoint-3/` y la matriz en
`docs/testing/evidencia/matriz-de-trazabilidad.md`. La matriz se arma con
`node scripts/qa/matriz.mjs --escenarios <salida de escenarios.mjs> --junit <cada carpeta surefire-reports>`
(el comando completo, en la sección 5).

## 9. Riesgos

Cada riesgo se califica por su probabilidad y su impacto. **Probabilidad:** qué tan posible es que pase en el sistema
o en una entrega; un riesgo que ya pasó (se nombra el defecto) no baja de Media. **Impacto:** Alto si se pierde dinero
o datos, se cae un flujo Must o se abre una falla de seguridad; Medio si se afecta una funcionalidad que tiene
alternativa, una prueba o una entrega; Bajo si hay una alternativa automática. La prioridad sale de multiplicar los
dos, con Alta = 3, Media = 2 y Baja = 1: 6 o 9 es prioridad **Alta**, 3 o 4 es **Media** y 1 o 2 es **Baja**.

**Matriz de probabilidad por impacto:**

| Probabilidad \ Impacto | Alto | Medio | Bajo |
|---|---|---|---|
| **Alta** | Ninguno | R-08, R-09 | Ninguno |
| **Media** | R-01, R-02, R-03, R-04, R-05, R-06, R-07 | R-13, R-14, R-15, R-16, R-17, R-18 | R-19 |
| **Baja** | R-10, R-11, R-12 | Ninguno | Ninguno |

Prioridad Alta: R-01 a R-09. Media: R-10 a R-18. Baja: R-19. En la columna "Cobertura", **Parcial** y **Sin prueba**
llevan la prueba que los cubriría.

| ID | Riesgo | Probabilidad | Impacto | Prioridad | Pruebas que lo cubren | Cobertura | Contingencia |
|---|---|---|---|---|---|---|---|
| R-01 | Se cobra y la compra no se confirma, o se cobra dos veces, por un reintento, un corte o un doble clic (pasó con DEF-07) | Media | Alto | Alta | Escenario 2 (`Idempotency-Key`); escenario 14 (falla después del cobro y conciliación); `ClienteDePagosTest#unPagoDuplicadoNoEsUnRechazoSinoElCobroQueYaGuardoPagos`; `ComprasEnCursoTest#siDosPedidosConLaMismaClaveLleganALaVezValeLaVentaQueGuardoElOtro`; `ServicioDePagosTest#siDosPedidosIgualesLleganALaVezSeDevuelveElPagoQueGanoAlGuardar` | Cubierto | La conciliación de pagos resuelve cada minuto las compras con un cobro sin resultado; Pagos cobra una sola vez por clave |
| R-02 | Un reembolso no le llega a quien pagó, al cancelarse un evento o en una compra en la reventa cobrada sin entrada (pasó con DEF-18; abierto: DEF-29) | Media | Alto | Alta | Escenarios 20 y 22; `CancelacionDeEventosTest`, `ReintentoDeReembolsosTest`, `ReembolsoPendienteEnH2Test` | **Parcial:** ninguna prueba cancela un evento con una entrada comprada en la reventa. Propuesta: el escenario 22 con una compra en la reventa (el pago de quien compró queda `REEMBOLSADO`) y un caso de `CancelacionDeEventosTest` con una venta de clave `reventa:` | `ReintentoDeReembolsos` vuelve a pedir cada minuto los que Pagos no contestó; un rechazo queda en un SEVERE para devolverlo a mano |
| R-03 | La reventa pasa el tope, o una regla de compra se esquiva por otro camino (pasó con DEF-16; abierto: DEF-30) | Media | Alto | Alta | `ServicioDeReventaTest#losBordesDelRangoSeAceptanYUnPesoAfueraNo`, `#unaEntradaRevendidaConservaElTopeYElMinimoDelPrecioDeLista`; escenarios 17 y 23; `frontend/e2e/reventa.spec.ts` | **Parcial:** el DNI por evento y el tope de 6 se prueban solo en la compra del catálogo, y dos reventas seguidas de la misma entrada, solo con dobles. Propuesta: casos de `ServicioDeReventaTest` y del escenario 23 que compren en la reventa con 6 entradas ya compradas o con un DNI que ya tiene entrada (409 sin cobrar), y un escenario que revenda dos veces la misma entrada | Las reglas están en un solo lugar (`ReglasDeNegocio`); una regla nueva se aplica y se prueba en la compra del catálogo y en la de la reventa |
| R-04 | Se pierde un mensaje: broker apagado, contenedor recreado o un consumidor que falla (pasó con DEF-27) | Media | Alto | Alta | `scripts/pruebas/broker-apagado.mjs` (en el CI); contenedor recreado (`scripts/LEEME.md`, `checkpoint-3/broker-recreado.txt`); escenarios 8 y 10 a 13 | Cubierto; el contenedor recreado, solo a mano. Propuesta: sumarlo al job "Integración" del CI (`docker compose down` sin `-v` y `up`, con un mensaje pendiente) | Mensajes persistentes y el journal en el volumen `datos-servidor` (ADR-005); lo que falla 5 veces queda en la DLQ y se reprocesa (documento único, 10.6) |
| R-05 | Un cambio deja al servidor sin desplegar (pasó con DEF-01) | Media | Alto | Alta | Job "Integración" del CI: levanta el compose, espera los 9 `health` y corre los escenarios | Cubierto | No se mergea con el CI en rojo; se vuelve al último commit en verde y se corrige en una rama |
| R-06 | Se despliega fuera de desarrollo con las cuentas de prueba activas, cuya contraseña es pública (pasó con DEF-26) | Media | Alto | Alta | `DesactivacionDeCuentasDePruebaEnH2Test` (con la variable en `false`, las seis cuentas no abren) | **Parcial:** nada comprueba la configuración de un despliegue. Propuesta: en cada despliegue, un ingreso con `organizador@palco.test` y la contraseña pública, que tiene que dar 401 | `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false` en todo entorno que no sea de desarrollo; el log avisa al arrancar si siguen activas |
| R-07 | El sistema no aguanta una salida a la venta, con muchos compradores a la vez | Media | Alto | Alta | Concurrencia, no carga: `PublicacionesEnH2Test#dosComprasALaVezTomanLaPublicacionUnaSolaVez`, `ReventaDeEntradasEnH2Test#dosTransferenciasALaVezDeLaMismaEntradaDejanPasarUnaSola`, `UsoEnH2Test#dosEscaneosALaVezUsanLaEntradaUnaSolaVez` | **Sin prueba de carga.** Planificada con k6 (sección 2) | Hasta tener la medición, la demo corre con pocos usuarios; lo que k6 muestre fuera de los umbrales se registra como defecto con su causa |
| R-08 | La interfaz cambia y el manual y sus capturas quedan atrás | Alta | Medio | Alta | Job "E2E" del CI (`npm run e2e` en `frontend/`) | Cubierto en los recorridos; las capturas se revisan a mano | Antes de cada entrega se recorre el manual y las capturas se rehacen con la base recién levantada |
| R-09 | Los datos que dejan las pruebas (ventas, eventos "Integracion backend…") ensucian la demo y las capturas | Alta | Medio | Alta | Ninguna | **Sin prueba.** Propuesta: antes de la demo y de las capturas, un control con la base recién levantada: `GET /api/eventos` trae solo los seis eventos de prueba (`ev-1` a `ev-6`) | `docker compose down -v` y levantar de cero antes de la demo y de las capturas |
| R-10 | Una entrada sirve dos veces en la puerta, o la valida quien no está asignado | Baja | Alto | Media | Escenarios 16 y 21; `UsoEnH2Test#dosEscaneosALaVezUsanLaEntradaUnaSolaVez`; `frontend/e2e/puerta.spec.ts` | Cubierto | El uso de la entrada es un UPDATE condicional en Ventas; si Ventas no responde, el escaneo queda `SIN_RESPUESTA` y no deja entrar (CA-13.4) |
| R-11 | Alguien ve o cambia datos de otra persona | Baja | Alto | Media | Escenarios 5 y 21; carpeta 4 de Postman (criterios SEC-xx); CA-06.4 | Cubierto | Un recurso ajeno responde 404, como si no existiera; `@RolesAllowed` en cada operación |
| R-12 | Un secreto llega al repositorio | Baja | Alto | Media | gitleaks (job "Secretos") y el control de logs del job "Integración" | Cubierto | Los secretos se generan en cada arranque o corrida; si uno se filtra, se rota |
| R-13 | Un servicio o un sistema externo (Pagos, ARCA, Eventos) no responde y la compra se traba | Media | Medio | Media | Escenarios 14, 18, 19 y 20; ARCA sin responder, DLQ y reproceso (`checkpoint-3/arca-lento-dlq.txt`, a mano) | Cubierto | Cada llamada corta a los 5 s con su fallback; la factura se reintenta por el broker y, agotados los intentos, se reprocesa desde la DLQ |
| R-14 | La API se aparta de su contrato (OpenAPI y WSDL) y rompe al frontend o a quien la use | Media | Medio | Media | La colección de Postman (códigos y Problem Details de cada endpoint) y los escenarios | **Parcial:** ninguna prueba compara cada respuesta con el esquema. Planificado: Schemathesis contra `openapi.yaml` y SoapUI contra `arca.wsdl` (sección 2) | El OpenAPI se actualiza con cada cambio de la API y la colección se regenera con `scripts/postman/generar.js` |
| R-15 | Una inyección de prompt o un pedido de datos de terceros pasa el guardrail del asistente (pasó con DEF-25) | Media | Medio | Media | `GuardrailsTest`; el set de evaluación (`EvaluacionDelAsistenteTest`, 11 adversariales); `frontend/e2e/asistente.spec.ts` | Cubierto | El asistente solo lee, no ve datos de otros usuarios y su respuesta pasa por el guardrail de salida |
| R-16 | Un procedimiento de operación del documento único no funciona como está escrito (pasó con DEF-28) | Media | Medio | Media | El reproceso de la DLQ hacia la suscripción de Facturación (`checkpoint-3/arca-lento-dlq.txt`, 29 de 29) | **Parcial:** el reproceso con filtro hacia la cola de emisión no se corrió. Propuesta: una prueba que lleve un `PAGO_CONFIRMADO` de la emisión a la DLQ y lo devuelva con los pasos de la sección 10.6 | Cada procedimiento se corre al menos una vez antes de una entrega y su salida queda en la evidencia |
| R-17 | Pruebas que dependen del tiempo (la conciliación corre una vez por minuto; el timeout es de 5 s) fallan en una máquina lenta | Media | Medio | Media | Escenarios 14, 18, 19 y 20; `asistente.mjs` | Cubierto | Esperas con margen en los guiones; se repite el escenario solo (`node scripts/pruebas/escenarios.mjs 14`); los unitarios usan un reloj fijo |
| R-18 | Pruebas que detienen consumidores o sacan de servicio a Eventos (escenarios 8, 10, 11, 12, 14 y 18) molestan a quien comparte el servidor | Media | Medio | Media | Ninguna | **Sin prueba.** Propuesta: al final de `escenarios.mjs`, comprobar con `jboss-cli` que todos los MDB quedaron entregando y que los 9 `health` responden 200; si no, la corrida termina en rojo | Cada integrante corre contra su propio compose, o solo los escenarios que no tocan el servidor (lista en el encabezado de `escenarios.mjs`) |
| R-19 | El proveedor real del modelo de IA tarda o no contesta | Media | Bajo | Baja | `asistente.mjs` (sección C), `AsistenteIATest`, `ModeloCompatibleOpenAITest` | Cubierto | El gateway responde el fallback a los 5 s; la demo y el set de evaluación usan el modelo simulado |

## 10. Responsables y cronograma

Cada tipo de prueba queda con quien defiende esa parte según el reparto (sección 18.1 del documento único), o con
quien la escribió.

| Tipo de prueba | Responsable | Por qué |
|---|---|---|
| Unitarias y con base de cada servicio | Quien defiende ese servicio | Reparto, 18.1 |
| Compra y conciliación de pagos (escenarios 1 a 6, 14 y 23) | Julián Zuchowicki | Tiene los pendientes de Ventas y la conciliación desde el 16/10 (18.1) |
| Patrón State (`EstadoDeVentaTest`, `patron-state.mjs`) | Valentina Frisoli | Autora del patrón State en la venta |
| Mensajería y resiliencia (escenarios 7 a 13 y 18, broker apagado) | Julián Zuchowicki | Mensajería y Notificaciones (18.1) |
| Reventa (escenarios 17, 19 y 20) | Julián Zuchowicki | Reventa (18.1) |
| Integración SOAP (escenario 15) | Santiago Loto | Facturación y el servicio SOAP (18.1) |
| Validación en puerta (escenarios 16 y 21) | Joaquin Molina | Validación (18.1) |
| Pagos | Valentina Frisoli | Pagos (18.1) |
| IA (set de evaluación, asistente en vivo) | Valentina Frisoli | Autora del servicio del asistente |
| Interfaz en el navegador y manual de usuario | Valentina Frisoli | Frontend (18.1) |
| Historias, criterios y matriz de trazabilidad | Joaquin Molina | Autor de las historias y del generador de la matriz |
| Plan de pruebas y registro de defectos | Valentina Frisoli | Autora del plan |
| Seguridad (JWT, roles, secretos) y corrida desde cero de cada entrega | Santiago Loto | `backend/common`, el servidor y el armado de cada entrega (18.1) |

**Cronograma**, atado a los hitos de la comisión (sección 18.2 del documento único):

| Fecha | Hito | Pruebas |
|---|---|---|
| Mié 18/11 | Se congela el 3.er checkpoint | Corrida completa de esta versión (sección 8) |
| Jue 19/11 | Se sube el 3.er checkpoint | Plan, matriz y manual, primera versión; matriz regenerada |
| Vie 20/11 | 3.er checkpoint | Se presentan el plan, la matriz y el manual |
| Mar 24/11 | Se congela la Prueba del TP Obligatorio | Corrida completa de la sección 5 contra el compose levantado de cero, con la interfaz en el navegador; matriz regenerada; capturas del manual rehechas |
| Jue 26/11 | Se sube la Prueba del TP Obligatorio | Evidencia de esa corrida en `docs/testing/evidencia/` |
| Vie 27/11 | Prueba del TP Obligatorio | Sistema completo funcionando; demo con las cuentas de prueba |
| Jue 03/12 | Se sube la Entrega Final | Segunda versión del plan, la matriz y el manual, con la corrida final y los defectos cerrados |
| Vie 04/12 | Final adelantado: defensa de la Entrega Final | Sin pruebas nuevas |
