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
| IA | Set de evaluación de 26 casos (15 funcionales, 11 adversariales) con el modelo simulado; los guardrails, con intentos de inyección y preguntas legítimas que no se tienen que bloquear; el adaptador del modelo real, sin red | JUnit 5 | `EvaluacionDelAsistenteTest`, `GuardrailsTest`, `ModeloCompatibleOpenAITest` | Cada `mvn verify` |
| API | Los endpoints de la API, en carpetas: casos felices y de error (validaciones, 401, 403, 404, 409, entre otros) | Postman / Newman | `docs/api/palco-api.postman_collection.json` (la genera `scripts/postman/generar.js`) | A mano |
| Seguridad | Los 10 criterios SEC-xx (Dado / Cuando / Entonces) | Postman (carpeta 4, con el id SEC-… de cada criterio) + tests | Notion, etiqueta "seguridad" | A mano y con los tests |
| Patrón State | Transiciones válidas e inválidas (409) de la venta | JUnit + Node | `EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs` | `mvn verify` y en el CI |
| Frontend | Lint y compilación (tipos) | oxlint + tsc + Vite | `frontend/` | En el CI |
| Interfaz (E2E) | Recorridos en el navegador contra el sistema levantado | Playwright | `frontend/e2e/` (`npm run e2e` en `frontend/`) | En el CI (job "E2E") y a mano antes de cada entrega |
| Secretos | Que no haya credenciales en el historial | gitleaks | CI, job "Secretos" | En el CI |

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
- Ningún defecto bloqueante abierto.

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

Se registran en el tablero con su ID `DEF-xx`, severidad, pasos para reproducirlos y estado.

| ID | Defecto | Severidad | Estado |
|---|---|---|---|
| DEF-01 | El master del 30/10 no desplegaba ningún servicio | Bloqueante | Corregido el 05/11 |
| DEF-02 | Migraciones sobre H2 que se trababan en máquinas lentas | Menor | A verificar con `dev.sh` |
| DEF-03 | La prueba de integración desactualizada con los emails nuevos | Mayor | Corregido el 05/11 |
| DEF-04 | El registro de la puerta salía en orden inverso | Menor | Corregido el 11/11 (PR #4) |

**Severidades:**

| Severidad | Cuándo | Efecto en las pruebas |
|---|---|---|
| Bloqueante | El servidor no despliega o un flujo Must no se puede completar | Suspende las pruebas de sistema (sección 4) y frena la entrega |
| Mayor | Falla un criterio de aceptación o una prueba automática queda en rojo, y hay forma de seguir | Se corrige antes de la entrega siguiente |
| Menor | No rompe ningún criterio: un orden, un texto, una demora | Se agenda; no frena la entrega |

**Ciclo de vida de un defecto:** *Nuevo* (quien lo encuentra lo carga con pasos, esperado, obtenido y
evidencia) → *Confirmado* (alguien más lo reproduce con esos pasos; si no se reproduce, queda *Descartado* con el
motivo) → *En corrección* (la persona dueña del módulo, en su rama) → *Corregido* (el cambio entra con un test que
lo reproduce) → *Verificado* (se vuelve a correr la prueba que lo encontró y pasa) → *Cerrado*. Si la prueba
vuelve a fallar, pasa a *Reabierto* y sigue desde *En corrección*.

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

| Riesgo | Probabilidad | Impacto | Prueba que lo detecta | Contingencia |
|---|---|---|---|---|
| Un cambio deja al servidor sin desplegar (pasó con DEF-01) | Media | Alto | Job "Integración" del CI: levanta el compose, espera los 9 `health` y corre los escenarios | No se mergea con el CI en rojo; se vuelve al último commit en verde y se corrige en una rama |
| La interfaz cambia y el manual y sus capturas quedan atrás | Alta | Medio | Job "E2E" del CI (`npm run e2e` en `frontend/`) | Antes de cada entrega se recorre el manual y las capturas se rehacen con la base recién levantada |
| Los datos que dejan las pruebas (ventas, eventos "Integracion backend…") ensucian la demo y las capturas | Alta | Medio | Ninguna | `docker compose down -v` y levantar de cero antes de la demo y de las capturas |
| Pruebas que dependen del tiempo (la conciliación corre una vez por minuto; el timeout es de 5 s) fallan en una máquina lenta | Media | Medio | Escenarios 14, 18, 19 y 20; `asistente.mjs` | Esperas con margen en los guiones; se repite el escenario solo (`node scripts/pruebas/escenarios.mjs 14`); los unitarios usan un reloj fijo |
| Pruebas que detienen consumidores o sacan de servicio a Eventos (escenarios 8, 10, 11, 12, 14 y 18) molestan a quien comparte el servidor | Media | Medio | Ninguna | Cada integrante corre contra su propio compose, o solo los escenarios que no tocan el servidor (lista en el encabezado de `escenarios.mjs`) |
| El proveedor real del modelo de IA tarda o no contesta | Media | Bajo | `asistente.mjs` (sección C), `AsistenteIATest`, `ModeloCompatibleOpenAITest` | El gateway responde el fallback a los 5 s; la demo y el set de evaluación usan el modelo simulado |
| Un secreto llega al repositorio | Baja | Alto | gitleaks (job "Secretos") y el control de logs del job "Integración" | Los secretos se generan en cada arranque o corrida; si uno se filtra, se rota |

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
