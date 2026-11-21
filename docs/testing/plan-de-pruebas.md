# Plan de pruebas de Palco

Versión 0.3, para la Entrega Final (viernes 27/11/2026, 08:40; el tag `v1.0-final`, antes de las 08:00). Describe qué
se prueba, cómo, dónde y con qué criterio se da por aprobado. Los resultados de la sección 8 salen de corridas reales
del 19/11 y del 20/11; la corrida completa sobre el tag `v1.0-final` es parte del plan (sección 8) y su salida va a
`docs/testing/evidencia/final/`.

**Control de versiones**

| Versión | Fecha | Cambios | Autor/a |
|---|---|---|---|
| 0.1 | 18/11/2026 | Primera versión: alcance, niveles, entornos, criterios, casos por requisito, cobertura, defectos y resultados | Valentina Frisoli |
| 0.2 | 19/11/2026 | Correcciones (cobertura, DLQ, Problem Details, interfaz, asistente) y secciones nuevas: referencias, técnicas de diseño, prerrequisitos, cómo repetir las pruebas contra el compose, severidades y ciclo de vida de los defectos, riesgos, responsables y cronograma | Santiago Loto (revisión: Valentina Frisoli) |
| 0.3 | 20/11/2026 | Las pruebas de contrato (Schemathesis y `ContratoDelWsdlTest`), carga (k6), seguridad (`seguridad.mjs` y `xss.spec.ts`), ZAP, dependencias (Trivy), mutación (PIT), estabilidad, accesibilidad (axe) y Lighthouse, con su comando; el análisis estático (PMD y SonarCloud); criterios de entrada y salida de la Entrega Final; defectos alineados con el registro (58); riesgos R-20 a R-26; cronograma de la Entrega Final | Joaquin Molina |

**Referencias**

- Consigna del TP: `docs/consigna/` (sección 6, requisitos técnicos obligatorios).
- Criterios de Testing y Aseguramiento de Calidad de la cátedra (Clase 15): las condiciones G1 a G9, los ejes A a E y
  la sección 9 (severidades y salida); y el mensaje de la cátedra del 12/11 con las fechas de la Entrega Final.
- Historias de usuario y criterios de aceptación en Gherkin: `docs/documentacion/historias-de-usuario.md`; casos de
  prueba: `docs/testing/especificacion-de-casos.md`.
- Documento único del proyecto: `docs/documentacion/Palco_Documentacion_Completa.md` (reparto en 18.1, calendario
  en 18.2).
- Contratos: `docs/api/openapi.yaml`, `docs/api/arca.wsdl`, `docs/api/mensajeria-pago-confirmado.md` y
  `docs/api/mensajeria-cancelacion-de-eventos.md`.
- Colección de Postman: `docs/api/palco-api.postman_collection.json` (guía en `docs/api/GUIA_POSTMAN.md`).
- Matriz de trazabilidad: `docs/testing/evidencia/matriz-de-trazabilidad.md`; evidencia de las corridas:
  `docs/testing/evidencia/checkpoint-3/` y `docs/testing/evidencia/final/`.
- Registro de defectos: `docs/testing/registro-de-defectos.md`.
- Manual de usuario: `docs/manual/manual-de-usuario.md`.

## 1. Objetivo y alcance

Comprobar que Palco cumple sus requisitos funcionales (las historias de usuario HU-01 a HU-16) y los de calidad que
piden el 3.er checkpoint y la Entrega Final: integraciones funcionando (REST, SOAP y mensajería), resiliencia
(timeouts con fallback, consumidores idempotentes, DLQ, mensajes que sobreviven a la caída del broker,
`Idempotency-Key`, `ETag` y errores en Problem Details), la IA por su gateway, la seguridad, el contrato de la API, la
carga, la accesibilidad y la calidad de las propias pruebas (cobertura, mutación y estabilidad).

**Dentro del alcance:** los 9 servicios del backend (8 de negocio y el asistente), el ARCA simulado,
la API REST, la mensajería JMS y el frontend web.

**Fuera del alcance:** la pasarela de pago real y el ARCA real (los dos simulados, aceptado por la cátedra el 16/10)
y los navegadores móviles reales: el responsive se prueba en Chromium a 360, 768 y 1280 px y con Lighthouse en perfil
de teléfono.

## 2. Niveles de prueba

| Nivel | Qué prueba | Herramienta | Dónde está | Cuándo corre |
|---|---|---|---|---|
| Unitarias | La lógica de cada Session Bean, con los otros servicios como dobles | JUnit 5 + Mockito | `backend/**/src/test` | Cada `mvn verify` y en el CI |
| Integración con base | Repositorios y migraciones contra una base real (H2 en modo PostgreSQL), incluida la concurrencia | JUnit 5 + H2 | Tests `*EnH2Test` | Cada `mvn verify` y en el CI |
| Integración de sistema | Recorridos completos por la API contra WildFly, la base y el broker, sin dobles | Node (`escenarios.mjs`) + jboss-cli | `scripts/pruebas/escenarios.mjs` (24 escenarios) | A mano y en el CI (job "Integración") |
| Resiliencia | Timeout de 5 s con fallback, caída del broker, ETag | Node | `scripts/pruebas/asistente.mjs`, `broker-apagado.mjs`, `etag.mjs` | En el CI (job "Integración") y a mano |
| IA | Set de evaluación de 26 casos (15 funcionales, 11 adversariales) con el modelo simulado; los guardrails, con intentos de inyección y preguntas legítimas que no se tienen que bloquear; el adaptador del modelo real, sin red | JUnit 5 | `EvaluacionDelAsistenteTest`, `GuardrailsTest`, `ModeloCompatibleOpenAITest` | Cada `mvn verify` |
| API | Los endpoints de la API, en carpetas: casos felices y de error (validaciones, 401, 403, 404, 409, entre otros) | Postman / Newman | `docs/api/palco-api.postman_collection.json` (la genera `scripts/postman/generar.js`) | En el CI (job "API") y a mano |
| Seguridad | Los criterios SEC-xx del informe de seguridad. Por la API: tokens JWT manipulados (vencido, de otro emisor, sin vencimiento, `alg=none`, firma alterada, otra clave, HS256), mass assignment, XSS almacenado, XXE contra `/legado/arca` y cookies, cabeceras y datos personales (bloques A a E); en el navegador, el HTML guardado se ve como texto; en la colección, BOLA, BFLA y fuerza bruta | Node + Playwright + Postman (carpeta 4, con el id SEC-… de cada criterio) | `scripts/pruebas/seguridad.mjs`, `frontend/e2e/xss.spec.ts`, `.github/workflows/pruebas-seguridad.yml` | En el CI (workflow "Pruebas de seguridad": push a master, pull requests y tags) y a mano |
| Patrón State | Transiciones válidas e inválidas (409) de la venta | JUnit + Node | `EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs` | `mvn verify` y en el CI |
| Frontend | Lint y compilación (tipos) | oxlint + tsc + Vite | `frontend/` | En el CI |
| Interfaz (E2E) | Recorridos en el navegador contra el sistema levantado | Playwright | `frontend/e2e/` (`npm run e2e` en `frontend/`) | En el CI (job "E2E") y a mano antes de cada entrega |
| Accesibilidad y responsive | Las pantallas principales a 360, 768 y 1280 px: sin violaciones serias ni críticas de WCAG 2.2 AA y sin desborde horizontal | Playwright + axe-core | `frontend/e2e/accesibilidad.spec.ts` | En el CI (job "E2E", que corre todo `frontend/e2e/`) y a mano |
| Lighthouse | Accesibilidad (90 o más) y buenas prácticas de las pantallas públicas principales, en perfil de teléfono (360 px) y de escritorio (1280 px) | Lighthouse CI (`@lhci/cli` 0.16.0) | `.lighthouserc.cjs`, `.github/workflows/lighthouse.yml` | En el CI (workflow "Lighthouse": push a master y tags) y a mano |
| Secretos | Que no haya credenciales en el historial | gitleaks | CI, job "Secretos" | En el CI |
| Análisis estático | Las reglas de calidad y de seguridad del código: PMD (conjunto `quickstart`) sobre el Java de cada módulo; SonarCloud sobre el backend, el frontend, las pruebas y la infraestructura, con la cobertura de JaCoCo | maven-pmd-plugin 3.28.0 (PMD 7.17.0) y SonarCloud | `backend/pom.xml`, `scripts/qa/analisis-estatico.mjs`, `sonar-project.properties` | En el CI (jobs "Backend" y "SonarCloud" de `ci.yml`) |
| Contrato REST | Que cada respuesta cumpla `docs/api/openapi.yaml`: ninguna respuesta 5xx, cada código documentado en su operación y cada cuerpo con su esquema, con pedidos válidos e inválidos que la herramienta genera desde el contrato, una corrida por rol (comprador, organizador, puerta y servicio) | Schemathesis 4.32.7 | `.github/workflows/contrato.yml` | En el CI (workflow "Contrato", con los disparadores de `ci.yml`) y a mano |
| Contrato SOAP | Que el WSDL que publica el ARCA simulado sea el de `docs/api/arca.wsdl`, y que el pedido de Facturación y las respuestas aprobada y rechazada cumplan su esquema | JUnit 5 | `backend/servicios/arca/src/test/java/com/palco/arca/ContratoDelWsdlTest.java` (7 tests) | Cada `mvn verify` y en el CI |
| Carga | Que el catálogo, el detalle de un evento y abrir una compra aguanten una salida a la venta: 50 usuarios virtuales durante 5 minutos | k6 (`grafana/k6:2.3.0`) | `scripts/carga/palco.js` y `cuentas.mjs`, `.github/workflows/carga.yml` | Con cada tag `v*` y a mano (workflow "Carga") |
| Escaneo de seguridad | La app por nginx (baseline, pasivo) y la API autenticada (pasivo y activo, política API-Minimal) | OWASP ZAP 2.17.0 | `scripts/qa/zap.sh`, `scripts/qa/zap-resumen.mjs`, `.zap/reglas.tsv`, `.github/workflows/zap.yml` | En el CI (workflow "ZAP": push a master, pull requests y tags) y a mano |
| Dependencias | CVE críticas y altas en los `pom.xml`, en `frontend/package-lock.json` y en las imágenes backend y web | Trivy v0.76.2 | `.github/workflows/seguridad.yml`, `.trivyignore` | En el CI (workflow "Seguridad": push a master, pull requests y tags) |
| Mutación | Que los tests detecten un cambio del código: PIT muta el dominio de cada servicio (`com.palco.*.dominio.*`) | PIT 1.30.2 | `backend/pom.xml` (pitest-maven), `scripts/qa/mutacion.mjs`, `.github/workflows/mutacion.yml` | En el CI (workflow "Mutación": push a master, pull requests y tags) y a mano |
| Estabilidad | La suite del backend 3 veces seguidas con el mismo resultado, test por test; y cuánto tardó cada workflow del commit | Maven + Node | `.github/workflows/estabilidad.yml`, `scripts/qa/estabilidad.mjs` | Con cada tag `v*` y a mano (workflow "Estabilidad") |

### Pruebas de contrato, carga, seguridad y calidad: cómo se corren

Desde la raíz del repo, en Bash (en Windows, Git Bash), con el sistema levantado como en la sección 5 ("Cómo
repetirlas contra el compose") cuando la prueba lo necesita. Lo que no cumpla su criterio se registra en
`docs/testing/registro-de-defectos.md`.

- **Contrato REST, con Schemathesis.** En el CI, el workflow "Contrato" levanta su propio compose y hace las cuatro
  corridas, cada una con el token de su rol. A mano, con el compose levantado y el token de la cuenta en `$TOKEN`:
  `pip install schemathesis==4.32.7` y
  `schemathesis run docs/api/openapi.yaml --url http://localhost:8080/api --checks not_a_server_error,status_code_conformance,response_schema_conformance --mode all --max-examples 25 -H "Authorization: Bearer $TOKEN"`.
  Las corridas de organizador, puerta y servicio suman los filtros `--include-tag` e `--include-path` de
  `contrato.yml`. En Git Bash, con `MSYS_NO_PATHCONV=1` y `PYTHONUTF8=1`. Criterio: ninguna respuesta fuera del
  contrato; cada diferencia se corrige en el código o en el contrato, o queda registrada.
- **Contrato SOAP, con `ContratoDelWsdlTest`.** Publica el servicio con `Endpoint.publish` y compara su WSDL con
  `docs/api/arca.wsdl`; valida contra el XSD de la copia el pedido que manda Facturación, una respuesta aprobada y una
  rechazada, y comprueba que el XSD rechaza una respuesta mal formada. Corre en cada `mvn verify`; solo, desde
  `backend/`: `mvn -pl servicios/arca test -Dtest=ContratoDelWsdlTest`.
- **Carga, con k6.** 50 usuarios virtuales durante 5 minutos, con 30 segundos de calentamiento, sobre
  `GET /api/eventos`, `GET /api/eventos/{slug}` y abrir una compra (`POST /api/v1/ventas`) que después se cancela
  (`DELETE /api/v1/ventas/{id}`), por nginx y HTTPS. Cada usuario virtual tiene su propia cuenta, creada antes de la
  corrida: con una sola cuenta, el tope de 6 entradas por persona y evento cortaría las compras. Cada uno ingresa una
  sola vez, al principio: el ingreso no se usa como carga, porque PBKDF2 es lento a propósito y 5 intentos fallidos en
  15 minutos dan 429. Umbrales, como thresholds de k6 en cada endpoint: p95 menor a 500 ms y menos del 1 % de errores;
  si uno no se cumple, k6 termina con error. Corre en una máquina que no se use para otra cosa. A mano:
  `PALCO_LOG=logs/server.log node scripts/carga/cuentas.mjs 50` y
  `docker run --rm --network host -v "$PWD:/trabajo" -w /trabajo -e PALCO_URL=https://localhost grafana/k6:2.3.0 run --no-usage-report scripts/carga/palco.js`
  (en Docker Desktop, `PALCO_URL=https://host.docker.internal`, `-v "$(pwd -W):/trabajo"` y `MSYS_NO_PATHCONV=1`). El
  reporte queda en `scripts/carga/resultados/`.
- **Seguridad: `seguridad.mjs` y `xss.spec.ts`.** Necesitan la clave JWT de la corrida, generada antes de levantar el
  compose, para firmar tokens como el servidor:
  `export PALCO_JWT_CLAVE_PRIVADA="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0)"`.
  Después, `node scripts/pruebas/seguridad.mjs` y
  `(cd frontend && PALCO_E2E_URL=https://localhost PALCO_LOG=../logs/server.log npx playwright test e2e/xss.spec.ts)`.
  Criterio: TODO BIEN en el guion y las dos pruebas del navegador en verde.
- **ZAP.** Con el compose levantado: `bash scripts/qa/zap.sh`. Hace el baseline contra la app y el escaneo de la API
  con el token de `titular@palco.test`; `zap-resumen.mjs` aplica el criterio: ninguna alerta High y cada Medium
  justificada en `.zap/reglas.tsv`. Los reportes quedan en `logs/zap/`.
- **Dependencias, con Trivy.** El workflow "Seguridad" corre `trivy fs` sobre el repo y `trivy image` sobre las
  imágenes backend y web que arma el compose. Criterio: ninguna CVE crítica o alta, tenga arreglo o no, que no esté en
  `.trivyignore` con su motivo y su fecha de vencimiento.
- **Mutación, con PIT.** Desde `backend/`, con `palco-common` instalado:
  `mvn test-compile org.pitest:pitest-maven:mutationCoverage -pl servicios/<servicio>` y, desde la raíz,
  `node scripts/qa/mutacion.mjs backend` para la tabla por módulo. El umbral es `mutacion.minimo` (60) en
  `backend/pom.xml`: debajo, la corrida queda en rojo. En Windows, la carpeta del repo no puede tener tildes ni eñes en
  la ruta (comentario de PIT en `backend/pom.xml`); el workflow corre en Linux.
- **Estabilidad.** El workflow "Estabilidad" compila una vez, corre `mvn test` 3 veces seguidas y
  `scripts/qa/estabilidad.mjs` compara los reportes de surefire test por test; queda en rojo si un test da distinto
  en alguna corrida. Además deja en `pipeline.md` cuánto tardó cada workflow del commit. A mano, los comandos están en
  el encabezado de `estabilidad.yml`.
- **Análisis estático.** PMD corre en cada `mvn verify` y deja `target/pmd.xml` en cada módulo; desde la raíz,
  `node scripts/qa/analisis-estatico.mjs backend` arma el resumen por módulo y por prioridad. El job "SonarCloud" de
  `ci.yml` corre `mvn verify` y publica el análisis con la configuración de `sonar-project.properties`; el Quality Gate
  se ve en el proyecto `santttii_deapps2` de SonarCloud.
- **Accesibilidad y responsive, con axe.** Con el compose levantado, desde `frontend/`:
  `PALCO_E2E_URL=https://localhost PALCO_LOG=../logs/server.log npx playwright test e2e/accesibilidad.spec.ts`.
  Cada pantalla se revisa en los tres anchos con las reglas WCAG 2.0, 2.1 y 2.2 A y AA; una violación seria o
  crítica, o un desborde horizontal, hace fallar la prueba, y cada paso deja su captura.
- **Lighthouse.** Con el compose levantado, un perfil por vez:
  `mkdir -p logs/lighthouse/mobile && cd logs/lighthouse/mobile` y
  `PALCO_LIGHTHOUSE_PERFIL=mobile npx --yes @lhci/cli@0.16.0 autorun --config=../../../.lighthouserc.cjs` (lo mismo
  con `desktop`). Criterio: accesibilidad de 90 o más en cada pantalla.

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
| 6 entradas por comprador y evento, sumando compras | Con 4, comprar 2 | Con 5, comprar 2; con 6, comprar 1: 409 `tope-por-evento` | `ServicioDeVentasTest#conElTopeJustoLaCompraSeAbre`, `#conLasDeOtrasComprasNoSePuedePasarDeSeisEntradasPorEvento`, `#conSeisEntradasYaNoPuedeComprarMasParaEseEvento`; escenario 23; por la reventa, `ReventaDeEntradasEnH2Test#conCincoEntradasParaElEventoLaDeLaReventaEsLaSextaYPasa` y `#conSeisEntradasParaElEventoNoCompraOtraEnLaReventa` |
| Cierre de la reventa 3 horas antes del evento | Faltan 3 h 1 min: se publica | Faltan 3 h justas: 409 `reventa-cerrada` | `ServicioDeReventaTest#laReventaCierraTresHorasAntesDelEvento` |
| Reserva de 10 minutos | Un segundo antes de vencer: se paga | En el segundo exacto: 409 `reserva-vencida` | `ServicioDeVentasTest#unSegundoAntesDeQueVenzaLaReservaTodaviaSePuedePagar`, `#conLaReservaVencidaNoSeCobra` |
| 5 intentos de ingreso fallidos en 15 minutos | No aplica | El sexto, aun con la contraseña correcta: 429 | `LimiteDeIntentosDeLoginTest#cincoFallidosYElSextoConLaClaveBuenaRecibe429`, `#laVentanaEsDeQuinceMinutos` |

- **Transición de estados.** La venta (patrón State): cada transición válida avanza y cada inválida responde 409
  (`EstadoDeVentaTest`, `scripts/pruebas/patron-state.mjs`).
- **Casos negativos y de seguridad.** Sin token, token alterado, rol equivocado, recurso ajeno (responde 404, como
  si no existiera), inyección en el log y en la búsqueda (escenarios 4, 5, 21 y 24); tokens manipulados, campos de más
  en el cuerpo, HTML en los textos y sobres SOAP con DTD (`seguridad.mjs`).

## 3. Entornos

| Entorno | Base | Cómo se levanta | Para qué |
|---|---|---|---|
| Desarrollo | H2 en memoria | `backend/dev.sh` | Trabajo diario y pruebas a mano |
| Compose | PostgreSQL 17 | `docker compose up --build` | Pruebas de sistema como en producción, demo |
| CI | PostgreSQL 17 (compose) | GitHub Actions: `.github/workflows/ci.yml` y los workflows aparte de la misma carpeta (sección 2) | En cada push a master, en cada pull request y en los tags `v*`; Lighthouse, sin los pull requests; Carga y Estabilidad, solo con los tags y a mano |

**Datos de prueba.** Las migraciones cargan seis eventos (`ev-1` a `ev-6`) de `organizador@palco.test`, con
`puerta@palco.test` asignada a la puerta de los seis, y las cuentas `titular@palco.test`,
`otro.titular@palco.test`, `otro.organizador@palco.test` y `otra.puerta@palco.test` (contraseña `palco1234`).
La pasarela simulada aprueba `tok_test`, rechaza `tok_rechazado` y no contesta con `tok_sin_respuesta`; en la
pantalla, una tarjeta terminada en 0002 se rechaza y una terminada en 0504 no tiene respuesta. Sin
`PALCO_SMTP_HOST`, los emails quedan en el log del servidor; con `PALCO_CORREO_LOG_COMPLETO=true`, que prenden
`backend/dev.sh` y el compose, el renglón lleva el email entero, con el código de verificación que leen las pruebas.
La prueba de carga crea antes sus 50 cuentas (`scripts/carga/cuentas.mjs`).

**Prerrequisitos para reproducir:**

- Docker con Compose v2 (Docker Desktop en Windows y macOS) y los puertos 80, 443 y 8080 libres.
- Node 22 (el del CI; los guiones de `scripts/` piden 18 o más nuevo y no tienen dependencias).
- JDK 25 y Maven 3.9 o más nuevo para `mvn verify`.
- En Windows, Git Bash para los guiones (trae `openssl`).
- Para la interfaz, en `frontend/`: `npm ci` y `npx playwright install chromium`.
- Para el contrato a mano, Python 3.12 y Schemathesis 4.32.7; k6, ZAP y Trivy corren desde sus imágenes de Docker, en
  la versión fija de cada guion o workflow.
- Para PIT en Windows, el repo en una carpeta sin tildes ni eñes en la ruta.

## 4. Criterios

Los de salida son los umbrales de los Criterios de Testing de la cátedra; los de defectos, su sección 9.

**De entrada:**

- Para cualquier corrida: el código compila (`mvn verify`) y el servidor arranca con los 10 WAR desplegados y todos
  los `health` en 200.
- Para la corrida de la Entrega Final, además: el commit del tag `v1.0-final`, el sistema levantado de cero con el
  compose (`docker compose down -v` y `docker compose up --build --wait`), las cuentas de prueba activas (el valor
  por defecto) y la base sin datos de corridas anteriores (R-09).

**De aprobación (salida):**

- 0 tests unitarios fallando, y la suite del backend 3 veces seguidas con el mismo resultado (workflow
  "Estabilidad").
- Los 24 escenarios de integración, las pruebas de resiliencia, el patrón State y la colección de Postman completos,
  sin fallas; en la colección, cada endpoint con al menos un caso de éxito y uno de error.
- Al menos 5 recorridos críticos E2E en el pipeline, en verde.
- La matriz de trazabilidad: todas las HU ejecutadas, al menos el 95 % de los criterios en verde, ningún criterio de
  una HU Must en falla y cada criterio en falla con su defecto en el registro.
- Cobertura de la capa de negocio y dominio: al menos 80 % de líneas y 70 % de ramas (JaCoCo). Mutation score de 60 %
  o más en el dominio de cada servicio (PIT).
- Análisis estático: el Quality Gate de SonarCloud aprobado, con 0 bugs Blocker o Critical, 0 vulnerabilidades,
  duplicación de 3 % o menos, rating A en confiabilidad y en seguridad y el 100 % de los security hotspots revisados.
  Los hallazgos de PMD se informan en el resumen del CI y no cortan el build.
- Contrato: ninguna respuesta fuera de `openapi.yaml` (Schemathesis) y el WSDL publicado igual al del repositorio
  (`ContratoDelWsdlTest`).
- Carga: con 50 usuarios durante 5 minutos, p95 menor a 500 ms y menos del 1 % de errores en cada endpoint crítico.
- Seguridad: `seguridad.mjs` y `xss.spec.ts` en verde; ZAP sin alertas High y cada Medium justificada en
  `.zap/reglas.tsv`; Trivy sin CVE críticas ni altas fuera de `.trivyignore`, cada excepción con su motivo; gitleaks
  sin hallazgos en todo el historial.
- Accesibilidad: axe sin violaciones serias ni críticas (WCAG 2.2 AA) a 360, 768 y 1280 px, y Lighthouse con
  accesibilidad de 90 o más.
- El set de evaluación de la IA: como mucho 2 fallas y ninguna adversarial (al menos 24 de 26, con los 11 adversariales bien).
- El CI en verde sobre el commit del tag, y el pipeline en 15 minutos o menos (lo que tardó cada workflow queda en
  `pipeline.md`, del workflow "Estabilidad").
- Defectos (sección 9 de los Criterios de Testing): 0 bloqueantes y 0 críticos abiertos; como mucho 3 mayores, cada
  uno con su alternativa escrita; menores y cosméticos sin límite, todos registrados. Todo defecto conocido está en
  `docs/testing/registro-de-defectos.md` con su estado real (condición G9).

**De suspensión:** si el servidor no despliega (como DEF-01) o aparece otro defecto bloqueante, se frenan las pruebas
de sistema hasta corregirlo; las unitarias siguen. Un crítico abierto no frena las pruebas, pero sí la entrega.

**De reanudación:** cuando el servidor vuelve a desplegar con los 10 WAR y los `health` en 200, las pruebas de
sistema se repiten completas, no solo la que falló.

## 5. Casos por requisito

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
| Cancelación de un evento | Compras anuladas, pagos devueltos a quien corresponde (también a quien compró en la reventa), reventa retirada y aviso | Escenario 22 | PASA |
| Reglas entre compras | Tope de 6 por comprador y evento; un DNI por evento, también al pagar | Escenario 23 (por el catálogo); por la reventa, los pasos de DEF-30 | PASA |
| Idempotency-Key | Escenario 2 (misma clave no duplica) | `escenarios.mjs` | PASA |
| ETag | En los 10 GET principales: 200 con ETag; 304 con `If-None-Match`; ETag nuevo si cambia | `node scripts/pruebas/etag.mjs` | 19 de 19 |
| Problem Details | Escenarios 3, 4, 6, 13 a 17 y 19 a 24 (cada error con su `status`, `type`, `title` y `detail`) | `escenarios.mjs` | PASA |
| IA por su gateway | Guardrails, timeout, fallback y apagado en vivo | `asistente.mjs` | 11 de 11 |
| IA: set de evaluación | 26 casos: 15 funcionales y 11 adversariales | `mvn -pl servicios/asistente -am test` (desde `backend/`) | 26 de 26; adversariales 11 de 11 |
| Patrón State con 409 | Pagar o cancelar una venta paga; pagar una cancelada | `node scripts/pruebas/patron-state.mjs` | 10 de 10 |
| Seguridad JWT y roles | Escenarios 5 y 21 | `escenarios.mjs` | PASA |
| JWT manipulados, mass assignment, XSS almacenado, XXE, cookies y cabeceras | Bloques A a E por la API; el HTML guardado en el navegador | `node scripts/pruebas/seguridad.mjs` y `npx playwright test e2e/xss.spec.ts` (sección 2) | TODO BIEN; las pruebas del navegador en verde |
| Contrato REST | Cuatro corridas, una por rol, contra `openapi.yaml` | Workflow "Contrato" (a mano, sección 2) | Ninguna respuesta fuera del contrato |
| Contrato SOAP | WSDL publicado frente a `docs/api/arca.wsdl`; pedido y respuestas contra su XSD | `mvn -pl servicios/arca test -Dtest=ContratoDelWsdlTest` (desde `backend/`) | 7 de 7 |
| Carga | 50 usuarios durante 5 minutos sobre los tres endpoints críticos | Workflow "Carga" (a mano, sección 2) | p95 menor a 500 ms y menos del 1 % de errores por endpoint |
| Escaneo de seguridad | Baseline y API autenticada | `bash scripts/qa/zap.sh` | 0 High; cada Medium en `.zap/reglas.tsv` |
| Dependencias | Repo e imágenes backend y web | Workflow "Seguridad" | 0 CRITICAL o HIGH fuera de `.trivyignore` |
| Mutación | Dominio de cada servicio | `mvn test-compile org.pitest:pitest-maven:mutationCoverage` y `node scripts/qa/mutacion.mjs backend` | 60 % o más en cada módulo |
| Estabilidad | `mvn test` 3 veces seguidas | Workflow "Estabilidad" | El mismo resultado en las 3, test por test |
| Accesibilidad y responsive | Las pantallas principales a 360, 768 y 1280 px | `npx playwright test e2e/accesibilidad.spec.ts` (en `frontend/`) | Sin violaciones serias ni críticas y sin desborde horizontal |
| Lighthouse | Catálogo, evento, ingresar, registro y recuperar la contraseña, en teléfono y escritorio | `npx --yes @lhci/cli@0.16.0 autorun` (sección 2) | Accesibilidad de 90 o más |

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
de rol `servicio`. Son las mismas variables que usa el job "Integración" del CI. Para las pruebas de seguridad, la
clave JWT de la corrida se exporta antes del `up` (sección 2).

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

La meta de la Entrega Final es la de la sección 4: 80 % de líneas y 70 % de ramas en negocio y dominio, medida sobre
el tag. La mutación completa la medición: una línea cubierta por un test que no controla lo que hace no mata al
mutante. PIT muta el dominio de cada servicio y exige 60 % por módulo (`mutacion.minimo` en `backend/pom.xml`).

## 7. Defectos

Los defectos están en `docs/testing/registro-de-defectos.md`: cada uno con su severidad y prioridad, los pasos para
reproducirlo, el resultado esperado y el obtenido, la evidencia, el estado, su dueño y, si está cerrado, la causa raíz
y el commit que lo corrige. Ahí están también la escala de severidades (la de la cátedra, sección 9 de los Criterios
de Testing), la de prioridades y el ciclo de vida de un defecto, con los estados *Corregido, a verificar* y
*Aceptado*.

Al 20/11/2026 hay 58 registrados: 28 cerrados, 12 corregidos que falta verificar sobre el tag, 1 en corrección, 3
aceptados (los dos riesgos aceptados el 16/10 y el token en `localStorage`) y 14 nuevos abiertos. Contra el máximo que
se admite al cierre (27/11):

| Severidad | Abiertos | Corregidos, a verificar sobre el tag | Máximo al cierre | Situación |
|---|---|---|---|---|
| Bloqueante | 0 | 0 | 0 | Dentro del máximo |
| Crítica | 0 (DEF-29 se cerró el 20/11) | 0 | 0 | Dentro del máximo |
| Mayor | 3: DEF-37 (el DNI completo en el mensaje y la auditoría), DEF-38 (la sesión de 8 horas, aceptado) y DEF-46 (dos compras a la vez pasan el tope), cada uno con su alternativa | 3: DEF-30, DEF-36 y DEF-47 | 3, cada uno con su alternativa | En el máximo, si las tres correcciones pasan sobre el tag |
| Menor | 12 | 6 | Sin máximo | Dentro del máximo |
| Cosmética | 3 | 3 | Sin máximo | Dentro del máximo |

Si DEF-30, DEF-36 o DEF-47 no pasa su prueba sobre el tag, vuelve a abrirse y los mayores pasan el máximo: para tener
margen hay que cerrar uno de los tres mayores abiertos antes del tag. Cada falla de la corrida sobre el tag (sección 8)
se registra con su DEF antes de la entrega.

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

Corrida del 20/11/2026 contra el compose levantado desde una copia limpia de `master` en `6361574`, con la corrección
de DEF-29 y DEF-31 (`da928f6`): los escenarios 17, 19, 20 y 22 en PASA, 82 de 82 comprobaciones, y 0 líneas SEVERE o
ERROR en el `server.log` (`docs/testing/evidencia/final/cancelacion-con-reventa.txt`).

### Corrida sobre el tag `v1.0-final`

Es la que da los resultados de la Entrega Final. Se hace sobre el commit del tag, con el sistema levantado de cero
(criterios de entrada, sección 4): la sección 5 completa contra el compose, y los workflows del commit (CI, Contrato,
Pruebas de seguridad, ZAP, Seguridad, Mutación, Lighthouse, Carga y Estabilidad), con el enlace a cada corrida. La
salida de cada prueba va a `docs/testing/evidencia/final/`, la matriz se regenera con esos reportes y cada falla entra
al registro de defectos con su DEF. Los corregidos que falta verificar (registro, sección 7) se cierran o se vuelven a
abrir con esta corrida.

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
| **Media** | R-01, R-02, R-03, R-04, R-05, R-06, R-07, R-21 | R-13, R-14, R-15, R-16, R-17, R-18, R-20, R-23, R-24, R-25, R-26 | R-19 |
| **Baja** | R-10, R-11, R-12, R-22 | Ninguno | Ninguno |

Prioridad Alta: R-01 a R-09 y R-21. Media: R-10 a R-18, R-20 y R-22 a R-26. Baja: R-19. En la columna "Cobertura",
**Parcial** y **Sin prueba** llevan la prueba que los cubriría.

| ID | Riesgo | Probabilidad | Impacto | Prioridad | Pruebas que lo cubren | Cobertura | Contingencia |
|---|---|---|---|---|---|---|---|
| R-01 | Se cobra y la compra no se confirma, o se cobra dos veces, por un reintento, un corte o un doble clic (pasó con DEF-07) | Media | Alto | Alta | Escenario 2 (`Idempotency-Key`); escenario 14 (falla después del cobro y conciliación); `ClienteDePagosTest#unPagoDuplicadoNoEsUnRechazoSinoElCobroQueYaGuardoPagos`; `ComprasEnCursoTest#siDosPedidosConLaMismaClaveLleganALaVezValeLaVentaQueGuardoElOtro`; `ServicioDePagosTest#siDosPedidosIgualesLleganALaVezSeDevuelveElPagoQueGanoAlGuardar` | Cubierto | La conciliación de pagos resuelve cada minuto las compras con un cobro sin resultado; Pagos cobra una sola vez por clave |
| R-02 | Un reembolso no le llega a quien pagó, al cancelarse un evento o en una compra en la reventa cobrada sin entrada (pasó con DEF-18 y DEF-29) | Media | Alto | Alta | Escenarios 20 y 22 (el 22, también con una entrada comprada en la reventa); `CancelacionDeEventosTest`, `CancelacionConReventaEnH2Test`, `ReintentoDeReembolsosTest`, `ReembolsoPendienteEnH2Test` | Cubierto (`docs/testing/evidencia/final/cancelacion-con-reventa.txt`) | `ReintentoDeReembolsos` vuelve a pedir cada minuto los que Pagos no contestó; un rechazo queda en un SEVERE para devolverlo a mano |
| R-03 | La reventa pasa el tope, o una regla de compra se esquiva por otro camino (pasó con DEF-16 y DEF-30; abierto: DEF-46) | Media | Alto | Alta | `ServicioDeReventaTest#losBordesDelRangoSeAceptanYUnPesoAfueraNo`, `#unaEntradaRevendidaConservaElTopeYElMinimoDelPrecioDeLista`, `#unCompradorConSeisEntradasParaElEventoNoCompraOtraEnLaReventa`, `#unTitularQueYaTieneEntradaParaElEventoNoCompraEnLaReventa`; `ReventaDeEntradasEnH2Test`; escenarios 17 y 23; `frontend/e2e/reventa.spec.ts` | **Parcial:** el escenario 23 recorre la compra del catálogo y no la de la reventa; dos reventas seguidas de la misma entrada se prueban solo con dobles; dos compras a la vez del mismo comprador no las prueba ningún test (DEF-46). Propuesta: los dos pasos de DEF-30 en el escenario 23, un escenario que revenda dos veces la misma entrada y un test con base con dos compras a la vez | Las reglas están en un solo lugar (`ReglasDeNegocio`, y las reglas entre compras de `ServicioDeVentas`, que usa también la reventa); una regla nueva se aplica y se prueba en la compra del catálogo y en la de la reventa |
| R-04 | Se pierde un mensaje: broker apagado, contenedor recreado o un consumidor que falla (pasó con DEF-27) | Media | Alto | Alta | `scripts/pruebas/broker-apagado.mjs` (en el CI); contenedor recreado (`scripts/LEEME.md`, `checkpoint-3/broker-recreado.txt`); escenarios 8 y 10 a 13 | Cubierto; el contenedor recreado, solo a mano. Propuesta: sumarlo al job "Integración" del CI (`docker compose down` sin `-v` y `up`, con un mensaje pendiente) | Mensajes persistentes y el journal en el volumen `datos-servidor` (ADR-005); lo que falla 5 veces queda en la DLQ y se reprocesa (documento único, 10.6) |
| R-05 | Un cambio deja al servidor sin desplegar (pasó con DEF-01) | Media | Alto | Alta | Job "Integración" del CI: levanta el compose, espera los 9 `health` y corre los escenarios | Cubierto | No se mergea con el CI en rojo; se vuelve al último commit en verde y se corrige en una rama |
| R-06 | Se despliega fuera de desarrollo con las cuentas de prueba activas, cuya contraseña es pública (pasó con DEF-26) | Media | Alto | Alta | `DesactivacionDeCuentasDePruebaEnH2Test` (con la variable en `false`, las seis cuentas no abren) | **Parcial:** nada comprueba la configuración de un despliegue. Propuesta: en cada despliegue, un ingreso con `organizador@palco.test` y la contraseña pública, que tiene que dar 401 | `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false` en todo entorno que no sea de desarrollo; el log avisa al arrancar si siguen activas |
| R-07 | El sistema no aguanta una salida a la venta, con muchos compradores a la vez | Media | Alto | Alta | Carga: `scripts/carga/palco.js` (k6, 50 usuarios durante 5 minutos, workflow "Carga"). Concurrencia: `PublicacionesEnH2Test#dosComprasALaVezTomanLaPublicacionUnaSolaVez`, `ReventaDeEntradasEnH2Test#dosTransferenciasALaVezDeLaMismaEntradaDejanPasarUnaSola`, `UsoEnH2Test#dosEscaneosALaVezUsanLaEntradaUnaSolaVez` | Cubierto: la corrida de carga de la Entrega Final es la del tag (`carga.yml`) | Lo que k6 muestre fuera de los umbrales se registra como defecto con su causa; el reporte trae las estadísticas del pool de conexiones de la base para encontrarla |
| R-08 | La interfaz cambia y el manual y sus capturas quedan atrás | Alta | Medio | Alta | Job "E2E" del CI (`npm run e2e` en `frontend/`) | Cubierto en los recorridos; las capturas se revisan a mano | Antes de cada entrega se recorre el manual y las capturas se rehacen con la base recién levantada |
| R-09 | Los datos que dejan las pruebas (ventas, eventos "Integracion backend…") ensucian la demo y las capturas | Alta | Medio | Alta | Ninguna | **Sin prueba.** Propuesta: antes de la demo y de las capturas, un control con la base recién levantada: `GET /api/eventos` trae solo los seis eventos de prueba (`ev-1` a `ev-6`) | `docker compose down -v` y levantar de cero antes de la demo y de las capturas |
| R-10 | Una entrada sirve dos veces en la puerta, o la valida quien no está asignado | Baja | Alto | Media | Escenarios 16 y 21; `UsoEnH2Test#dosEscaneosALaVezUsanLaEntradaUnaSolaVez`; `frontend/e2e/puerta.spec.ts` | Cubierto | El uso de la entrada es un UPDATE condicional en Ventas; si Ventas no responde, el escaneo queda `SIN_RESPUESTA` y no deja entrar (CA-13.4) |
| R-11 | Alguien ve o cambia datos de otra persona | Baja | Alto | Media | Escenarios 5 y 21; carpeta 4 de Postman (criterios SEC-xx); `seguridad.mjs` (bloque B, mass assignment de dueño, estado y precio); CA-06.4 | Cubierto | Un recurso ajeno responde 404, como si no existiera; `@RolesAllowed` en cada operación |
| R-12 | Un secreto llega al repositorio | Baja | Alto | Media | gitleaks (job "Secretos") y el control de logs del job "Integración" | Cubierto | Los secretos se generan en cada arranque o corrida; si uno se filtra, se rota |
| R-13 | Un servicio o un sistema externo (Pagos, ARCA, Eventos) no responde y la compra se traba | Media | Medio | Media | Escenarios 14, 18, 19 y 20; ARCA sin responder, DLQ y reproceso (`checkpoint-3/arca-lento-dlq.txt`, a mano) | Cubierto | Cada llamada corta a los 5 s con su fallback; la factura se reintenta por el broker y, agotados los intentos, se reprocesa desde la DLQ |
| R-14 | La API se aparta de su contrato (OpenAPI y WSDL) y rompe al frontend o a quien la use (pasó con DEF-35 y DEF-57) | Media | Medio | Media | Schemathesis contra `openapi.yaml`, una corrida por rol (workflow "Contrato"); `ContratoDelWsdlTest` contra `arca.wsdl`; la colección de Postman y los escenarios | Cubierto | El OpenAPI se actualiza con cada cambio de la API y la colección se regenera con `scripts/postman/generar.js` |
| R-15 | Una inyección de prompt o un pedido de datos de terceros pasa el guardrail del asistente (pasó con DEF-25) | Media | Medio | Media | `GuardrailsTest`; el set de evaluación (`EvaluacionDelAsistenteTest`, 11 adversariales); `frontend/e2e/asistente.spec.ts` | Cubierto | El asistente solo lee, no ve datos de otros usuarios y su respuesta pasa por el guardrail de salida |
| R-16 | Un procedimiento de operación del documento único no funciona como está escrito (pasó con DEF-28) | Media | Medio | Media | El reproceso de la DLQ hacia la suscripción de Facturación (`checkpoint-3/arca-lento-dlq.txt`, 29 de 29) | **Parcial:** el reproceso con filtro hacia la cola de emisión no se corrió. Propuesta: una prueba que lleve un `PAGO_CONFIRMADO` de la emisión a la DLQ y lo devuelva con los pasos de la sección 10.6 | Cada procedimiento se corre al menos una vez antes de una entrega y su salida queda en la evidencia |
| R-17 | Pruebas que dependen del tiempo (la conciliación corre una vez por minuto; el timeout es de 5 s) fallan en una máquina lenta | Media | Medio | Media | Escenarios 14, 18, 19 y 20; `asistente.mjs` | Cubierto | Esperas con margen en los guiones; se repite el escenario solo (`node scripts/pruebas/escenarios.mjs 14`); los unitarios usan un reloj fijo |
| R-18 | Pruebas que detienen consumidores o sacan de servicio a Eventos (escenarios 8, 10, 11, 12, 14 y 18) molestan a quien comparte el servidor | Media | Medio | Media | Ninguna | **Sin prueba.** Propuesta: al final de `escenarios.mjs`, comprobar con `jboss-cli` que todos los MDB quedaron entregando y que los 9 `health` responden 200; si no, la corrida termina en rojo | Cada integrante corre contra su propio compose, o solo los escenarios que no tocan el servidor (lista en el encabezado de `escenarios.mjs`) |
| R-19 | El proveedor real del modelo de IA tarda o no contesta | Media | Bajo | Baja | `asistente.mjs` (sección C), `AsistenteIATest`, `ModeloCompatibleOpenAITest` | Cubierto | El gateway responde el fallback a los 5 s; la demo y el set de evaluación usan el modelo simulado |
| R-20 | Datos personales de más en el log, en un mensaje o en una tabla (pasó con DEF-36; abierto: DEF-37) | Media | Medio | Media | `CorreoAlLogTest`, `CorreoSmtpTest`, `AsistenteIATest#laLineaDeRegistroLlevaElIdDeQuienPreguntaYNingunEmail`, `ServicioDeNotificacionesTest#elAvisoNoLlevaElDniDeNingunTitular`; el control de logs del job "Integración" (tokens); `seguridad.mjs` (bloque E) | **Parcial:** el control de logs del CI no busca DNI ni emails (DEF-40) y nada controla el cuerpo que guarda la auditoría (DEF-37). Propuesta: sumar al control los DNI y emails de prueba, en una corrida con `PALCO_CORREO_LOG_COMPLETO=false` | Fuera de desarrollo, `PALCO_CORREO_LOG_COMPLETO` no se define; la API de Notificaciones no devuelve el cuerpo de la auditoría |
| R-21 | Una dependencia o una imagen base trae una CVE crítica o alta (abierto: DEF-51, la imagen web sobre `nginx:1.27-alpine`) | Media | Alto | Alta | Trivy (workflow "Seguridad"): `trivy fs` sobre los `pom.xml` y el `package-lock.json`, y `trivy image` sobre las imágenes backend y web | **Parcial:** la imagen de la base (`postgres:17-alpine`) no se escanea. Propuesta: sumarla a la matriz del job de imágenes | Se sube la versión que trae el arreglo; si no hay arreglo, la excepción va a `.trivyignore` con su motivo y su vencimiento |
| R-22 | Una vulnerabilidad web: un token manipulado que pasa, un XSS almacenado, un XXE en el SOAP o una alerta High de ZAP | Baja | Alto | Media | `seguridad.mjs` (bloques A, C y D), `frontend/e2e/xss.spec.ts`, ZAP (workflow "ZAP"), `ErrorDeAutenticacionServletTest`, `FabricaDeArcaTest#conElParserInseguroDeCxfActivadoNoSeCreaElCliente` | Cubierto | Cada respuesta inesperada de esas pruebas se registra como defecto; MicroProfile JWT valida la firma, el emisor y el vencimiento; React escapa el texto y la CSP solo deja correr scripts propios. Un XSS podría leer el token, que queda en `localStorage` (riesgo aceptado, DEF-58) |
| R-23 | Los tests no detectan un cambio del código: la cátedra invierte una condición, cambia un límite o borra una validación en vivo (abierto: DEF-43) | Media | Medio | Media | PIT sobre el dominio de cada servicio (workflow "Mutación", 60 % o más por módulo); los tests de borde de la sección 2 | **Parcial:** PIT muta solo `com.palco.*.dominio.*`: las reglas de `ServicioDeVentas`, `ServicioDeReventa` y `common/ReglasDeNegocio` quedan afuera, y sacar la anotación de rol en 7 de los 13 recursos no hace fallar ningún test unitario (DEF-43). Propuesta: correr PIT también sobre el negocio de Ventas y Reventa (`-Dmutacion.clases=com.palco.*.negocio.*`, ver `backend/pom.xml`) | Antes de la entrega, cada dueño invierte a mano una condición de su módulo y comprueba qué test falla |
| R-24 | Una prueba inestable da resultados distintos entre corridas | Media | Medio | Media | Workflow "Estabilidad": la suite del backend 3 veces seguidas, comparada test por test (`scripts/qa/estabilidad.mjs`) | **Parcial:** las suites de sistema (escenarios, E2E y Newman) no se comparan entre corridas, y Playwright reintenta una vez en el CI (`retries: 1` en `frontend/playwright.config.ts`), lo que puede tapar una prueba inestable. Propuesta: revisar en el reporte del job "E2E" del tag que ninguna figure como *flaky* | Una prueba inestable se registra como defecto y se corrige |
| R-25 | Una pantalla no es accesible o se rompe en un teléfono | Media | Medio | Media | `frontend/e2e/accesibilidad.spec.ts` (axe, WCAG 2.2 AA, 360, 768 y 1280 px, en el job "E2E"); Lighthouse (workflow "Lighthouse", accesibilidad de 90 o más) | Cubierto | Una violación seria o crítica deja el job "E2E" en rojo y se registra como defecto |
| R-26 | El pipeline del tag pasa de 15 minutos, o un workflow que corre solo con el tag (Carga, Estabilidad) queda en rojo el día de la entrega | Media | Medio | Media | `pipeline.md` del workflow "Estabilidad", con lo que tardó cada workflow del commit | **Parcial:** Carga tarda más de 15 minutos por diseño (levanta el sistema, crea 50 cuentas y corre 6 minutos de k6) y corre con el tag. Propuesta: correr Carga y Estabilidad a mano sobre el commit candidato antes de crear el tag | El tag va sobre un commit con todas las corridas en verde; los workflows largos se corren antes, a mano, sobre ese mismo commit |

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
| Plan de pruebas | Valentina Frisoli | Autora del plan |
| Registro de defectos y corrida sobre el tag | Joaquin Molina | QA del grupo: conduce la prueba de la Entrega Final; autor del registro |
| Seguridad (JWT, roles, secretos) y corrida desde cero de cada entrega | Santiago Loto | `backend/common`, el servidor y el armado de cada entrega (18.1) |
| Pruebas de seguridad (`seguridad.mjs`, `xss.spec.ts`) y ZAP | Valentina Frisoli | Eje D: pruebas de seguridad en vivo |
| Contrato REST y SOAP (Schemathesis, `ContratoDelWsdlTest`) | Sebastian Deya | Autor de las pruebas de contrato |
| Carga (k6), accesibilidad (axe) y estabilidad | Sebastian Deya | Autor de `scripts/carga/` y de `accesibilidad.spec.ts`; eje B |
| Dependencias (Trivy) | Joaquin Molina | Autor de `seguridad.yml` |
| Mutación (PIT) | Julián Zuchowicki | Autor de la configuración de PIT y de `mutacion.yml` |
| Lighthouse | Valentina Frisoli | Frontend (18.1) |

**Cronograma**, atado a los hitos de la comisión (sección 18.2 del documento único):

| Fecha | Hito | Pruebas |
|---|---|---|
| Mié 18/11 | Se congela el 3.er checkpoint | Corrida completa de la versión 0.2 (sección 8) |
| Jue 19/11 | Se sube el 3.er checkpoint | Plan, matriz y manual, primera versión; matriz regenerada |
| Vie 20/11 | 3.er checkpoint | Se presentan el plan, la matriz y el manual; cancelación con reventa contra el compose (DEF-29 y DEF-31) |
| Mar 24/11 | Se congela la Entrega Final | Corrida completa sobre el commit candidato, contra el compose levantado de cero, con los workflows de la sección 2; matriz regenerada; capturas del manual rehechas |
| Jue 26/11 | Se sube la Entrega Final | Evidencia de esa corrida en `docs/testing/evidencia/final/`; registro de defectos con el estado de esa corrida |
| Vie 27/11 | Prueba del TP Obligatorio y defensa de la Entrega Final (08:40); tag `v1.0-final` antes de las 08:00 | Ejecución en vivo de lo que pida la cátedra, sobre el tag |
| Vie 04/12 | Final adelantado y recuperatorio | Si hace falta, con las correcciones marcadas |
