# Informe de seguridad de Palco

Versión 1.2, para la Entrega Final (viernes 27/11/2026). Es el entregable 6 de los Criterios de Testing de la cátedra
(sección 3): la matriz de criterios de seguridad de la clase 11 con su estado y su evidencia, los resultados de
OWASP ZAP, del análisis de dependencias (Trivy) y del escaneo de secretos (gitleaks), y cada hallazgo con su
tratamiento. Cubre las pruebas mínimas del eje D (sección 7 de los criterios); la sección 6 de este informe tiene el
comando para mostrar cada una en vivo.

**Control de versiones**

| Versión | Fecha | Cambios | Autor/a |
|---|---|---|---|
| 1.0 | 24/11/2026 | Matriz de 36 criterios con la evidencia de las corridas del CI sobre `fbd31dc`: pruebas de seguridad (`seguridad.mjs` y `xss.spec.ts`), ZAP, Trivy, gitleaks, contrato y SonarCloud, y con la del 24/11 en `docs/testing/evidencia/final/` (SonarCloud sobre todo el código y el registro con un nombre largo). Los ocho controles del eje D que hasta el 20/11 no tenían prueba ejecutada (JWT vencido y de otro emisor, mass assignment de estado, precio y dueño, XSS almacenado, cookies, XXE, ZAP y dependencias) ahora la tienen. Se suman SEC-W05f, SEC-W15, SEC-D06, SEC-E02b, SEC-E04 y SEC-E04b. Los hallazgos nuevos están en el registro como DEF-64 y DEF-68 a DEF-75; DEF-64 y DEF-70 se corrigieron el 24/11 y se verifican sobre el tag | Valentina Frisoli |
| 1.1 | 25/11/2026 | Matriz reevaluada sobre `93d34d2` con las corridas del CI del 25/11 y con las correcciones de DEF-38, DEF-69 y DEF-71, probadas ese día contra el compose. Pasan a CUMPLE SEC-W05c, SEC-W15, SEC-D03, SEC-D05 y SEC-E02b; DEF-69 y DEF-71 dejan de ser riesgos aceptados. Queda NO CUMPLE solo SEC-I02, como riesgo aceptado (DEF-39) | Valentina Frisoli |
| 1.2 | 26/11/2026 | Matriz reevaluada sobre `9caa865`, el código del tag `v1.0-final`, con sus corridas del CI (sección 1) y las comprobaciones contra el compose de `defectos-sobre-9caa865.txt`. Los defectos que cita quedan con el estado del registro: cerrados el 26/11 los corregidos después de `fbd31dc`; abiertos, solo los cinco aceptados. Resultados de ZAP, Trivy, gitleaks y SonarCloud de `9caa865` (sección 4). SEC-I02 sigue NO CUMPLE, como riesgo aceptado (DEF-39) | Valentina Frisoli |

**Código evaluado.** `master` en `9caa865` (26/11/2026), el último commit de código antes del tag `v1.0-final`: el
tag va en un commit de solo documentación encima, así que su código es el de esta evaluación. Las corridas de la
matriz son las de `9caa865` (sección 1). Los hallazgos se encontraron en las corridas sobre `fbd31dc` (21/11), y las
rutas y líneas de código que los describen son las de ese commit; la sección 7 nombra constantes y métodos. Todos los
workflows corren también con cada tag `v*`, así que el tag tiene su propia corrida de cada uno (sección 9). Los
estados de los defectos son los del registro: los corregidos que cita este informe (DEF-36 a DEF-38, DEF-40,
DEF-42 a DEF-45, DEF-56, DEF-59 a DEF-65, DEF-69 a DEF-74 y DEF-76 a DEF-81) se cerraron el 26/11 con la
corrida sobre `9caa865`; siguen abiertos solo los cinco aceptados (DEF-39, DEF-41, DEF-58, DEF-68 y DEF-75).

**Regla de estado.**

- **CUMPLE**: una prueba ejecutada sobre `9caa865` (test, escenario, guion o escaneo) con su salida, citada; para lo
  que no corre en ningún workflow, la comprobación contra el compose de `docs/testing/evidencia/final/` que se nombra.
- **NO CUMPLE**: la prueba o el código muestran que no se cumple. Cada uno tiene su defecto en
  `docs/testing/registro-de-defectos.md`.
- **NO VERIFICABLE**: no hay prueba ejecutada o no hay su salida; se dice cuál es la prueba que lo verifica.

Ningún criterio está como CUMPLE solo por revisión de código o por el veredicto de una herramienta sin su reporte.
Cada riesgo aceptado también tiene su defecto en el registro, con estado Aceptado.

**Referencias.** Criterios SEC del taller de la clase 11 (`docs/clases/clase-11-actividad-seguridad/` y documento único,
sección 14.9), con OWASP ASVS 5.0, OWASP API Security Top 10 2023, OWASP Top 10 2025 y la Ley 25.326; seguridad del
sistema en el documento único (`docs/documentacion/Palco_Documentacion_Completa.md`, secciones 14.1 a 14.10); plan de
pruebas (`docs/testing/plan-de-pruebas.md`, sección 2, "Pruebas de contrato, carga, seguridad y calidad: cómo se
corren"); registro de defectos.

---

## 1. Corridas que respaldan el informe

**Corridas sobre `9caa865` (26/11/2026).** Las dispara el push de ese commit a `master`; corrieron entre las 17:13 y
las 17:20 (hora de Argentina; 20:13 a 20:20 UTC en los logs) y terminaron en verde, con los 22 check runs del commit
en success. Cada una deja sus reportes como artefacto; se bajan con
`gh run download <run> -R santttii/deapps2 -n <artefacto>`. El resumen de cada una está en
`docs/testing/evidencia/final/` (`ci-9caa865.txt`, `seguridad.txt`, `zap.txt`, `trivy.txt`, `contrato.txt` y
`sonar.txt`).

| Workflow | Run | Qué corre | Resultado | Artefactos usados |
|---|---|---|---|---|
| CI (`.github/workflows/ci.yml`) | [45889485001](https://github.com/santttii/deapps2/actions/runs/45889485001) | Secretos (gitleaks); Backend (`mvn verify`); SonarCloud; Frontend; Integración (4 grupos de `escenarios.mjs`, `asistente.mjs`); E2E (Playwright); API (Newman) | 10 de 10 jobs en verde. `mvn verify`: 1277 tests y 61 `*IT` en 11 módulos, 0 fallas. Integración: los 24 escenarios en PASA. E2E: 39 de 39. Newman: 219 requests, 682 aserciones, 0 fallas | `gitleaks`, `reportes-backend`, `integracion-general`, `integracion-conciliacion-pagos`, `reporte-api` |
| Pruebas de seguridad (`pruebas-seguridad.yml`) | [45889484808](https://github.com/santttii/deapps2/actions/runs/45889484808) | `scripts/pruebas/seguridad.mjs` (bloques A a E) y `frontend/e2e/xss.spec.ts` | 64 de 64 comprobaciones ("TODO BIEN") y 2 de 2 pruebas del navegador | `pruebas-seguridad` (`resultados-seguridad/seguridad.txt`, `logs/server.log`) |
| ZAP (`zap.yml`, ZAP 2.17.0) | [45889484996](https://github.com/santttii/deapps2/actions/runs/45889484996) | Baseline (pasivo) de la app y escaneo pasivo y activo de la API autenticada | 0 High (1 instancia descontada como falso positivo verificado), 0 Medium, 2 Low justificadas | `reporte-zap` (`logs/zap/resumen.md`, `baseline.*`, `api.*`, `compose-zap.log`) |
| Seguridad (`seguridad.yml`, Trivy 0.76.2) | [45889484934](https://github.com/santttii/deapps2/actions/runs/45889484934) | `trivy fs` sobre el repo; `trivy image` sobre las imágenes backend, web y `postgres:17-alpine` | 0 CRITICAL y 0 HIGH sin justificar; las aceptadas, en `.trivyignore`. En el intento 1, "Dependencias del repo" falló porque Maven Central respondió 429; el intento 2 lo volvió a correr, en verde | `trivy-dependencias`, `trivy-imagen-backend`, `trivy-imagen-web`, `trivy-imagen-db` |
| Contrato (`contrato.yml`, Schemathesis 4.32.7) | [45889485045](https://github.com/santttii/deapps2/actions/runs/45889485045) | Las 52 operaciones de `docs/api/openapi.yaml` con el token de cada rol | 5633 casos generados, 5633 pasados (comprador 3009, organizador 1159, puerta 425, servicio 1040); ninguna respuesta 5xx | `reporte-contrato` |
| SonarCloud Code Analysis (check del commit) | [140150665852](https://github.com/santttii/deapps2/runs/140150665852) | Análisis publicado por el job SonarCloud de `ci.yml` | Quality Gate passed | Resumen del check; `sonar.txt` y `quality-gate-9caa865.png` |

**Contra el compose, el 26/11.** Lo que no corre en ningún workflow se comprobó contra el sistema levantado con el
compose, con la imagen backend armada con el código de `9caa865` y la imagen web de `9383028`, que ningún commit
posterior cambia:
`docs/testing/evidencia/final/defectos-sobre-9caa865.txt`, punto 13 (CORS con el origen
de Vite, `/legado/` por nginx, `id` del contenedor web, auditoría sin DNI, log de nginx sin emails y el carácter nulo
en un evento, en el resumen de ventas y en una factura),
`correo-en-el-log.txt`, punto 6 (el log sin la variable `PALCO_CORREO_LOG_COMPLETO`), y `registro-nombre-largo.txt`,
punto 7 (el registro con un nombre lleno de espacios).

**Corridas sobre `fbd31dc` (21/11/2026), donde se encontraron los hallazgos.** Corrieron entre las 01:39 y las 01:46
(hora de Argentina; 04:39 a 04:46 UTC) y terminaron en verde. Las descripciones de los hallazgos de la sección 3 citan
sus salidas.

| Workflow | Run | Qué corre | Resultado | Artefactos usados |
|---|---|---|---|---|
| CI (`.github/workflows/ci.yml`) | [44867187162](https://github.com/santttii/deapps2/actions/runs/44867187162) | Secretos (gitleaks); Backend (`mvn verify`); SonarCloud; Frontend; Integración (4 grupos de `escenarios.mjs`, `asistente.mjs`); E2E (Playwright); API (Newman) | 10 de 10 jobs en verde. `mvn verify`: 1186 tests en 11 módulos, 0 fallas. Integración: los 24 escenarios en PASA. E2E: 31 de 31. Newman: 216 requests, 673 aserciones, 0 fallas | `gitleaks`, `reportes-backend`, `integracion-general`, `integracion-conciliacion-pagos`, `reporte-api` |
| Pruebas de seguridad (`pruebas-seguridad.yml`) | [44867187123](https://github.com/santttii/deapps2/actions/runs/44867187123) | `scripts/pruebas/seguridad.mjs` (bloques A a E) y `frontend/e2e/xss.spec.ts` | 64 de 64 comprobaciones ("TODO BIEN") y 2 de 2 pruebas del navegador | `pruebas-seguridad` (`resultados-seguridad/seguridad.txt`, `logs/server.log`) |
| ZAP (`zap.yml`, ZAP 2.17.0) | [44867187257](https://github.com/santttii/deapps2/actions/runs/44867187257) | Baseline (pasivo) de la app y escaneo pasivo y activo de la API autenticada | 0 High (4 instancias High descontadas como falsos positivos verificados), 2 Medium justificadas | `reporte-zap` (`logs/zap/resumen.md`, `baseline.*`, `api.*`, `compose-zap.log`) |
| Seguridad (`seguridad.yml`, Trivy 0.76.2) | [44867187171](https://github.com/santttii/deapps2/actions/runs/44867187171) | `trivy fs` sobre el repo; `trivy image` sobre las imágenes backend y web | 0 CRITICAL y 0 HIGH sin justificar; 6 HIGH aceptadas en `.trivyignore` | `trivy-dependencias`, `trivy-imagen-backend`, `trivy-imagen-web` |
| Contrato (`contrato.yml`, Schemathesis 4.32.7) | [44867187179](https://github.com/santttii/deapps2/actions/runs/44867187179) | Las 52 operaciones de `docs/api/openapi.yaml` con el token de cada rol | 5574 casos generados, 5574 pasados (comprador 3003, organizador 1127, puerta 430, servicio 1014); ninguna respuesta 5xx | `reporte-contrato` |
| SonarCloud Code Analysis (check del commit) | [136734862149](https://github.com/santttii/deapps2/runs/136734862149) | Análisis publicado por el job SonarCloud de `ci.yml` | Quality Gate passed | Resumen del check |

Del 24/11, con el código de `fbd31dc`, `registro-nombre-largo.txt` (puntos 1 a 5): `POST /api/usuarios/registro` con
un nombre lleno de espacios, contra el compose de la máquina de QA (SEC-W15, DEF-64).

## 2. Resumen y matriz

| Estado | Cantidad |
|---|---|
| CUMPLE (prueba ejecutada, con su salida) | 35 |
| NO CUMPLE (cada uno con su defecto) | 1 |
| NO VERIFICABLE (sin prueba ejecutada o sin su salida) | 0 |
| **Total de criterios** | **36** |

**CUMPLE con las correcciones posteriores a `fbd31dc`:** SEC-W15 (DEF-64, **Crítica**: un registro sin sesión con un
nombre de 1500 espacios ocupaba un hilo del servidor 7 segundos), SEC-D03 (DEF-36 y DEF-72: datos personales y
códigos de un solo uso en los logs del sistema levantado con el compose), SEC-D05 (DEF-37: DNI completo en
`PAGO_CONFIRMADO` y en la auditoría), SEC-E02b (DEF-73: `seguridad.yml` no escaneaba `postgres:17-alpine`) y SEC-W05c
(DEF-38: la sesión duraba 8 horas, ahora 15 minutos). Esos defectos se cerraron el 26/11 con las corridas sobre
`9caa865` y las comprobaciones contra el compose de la sección 1.

**NO CUMPLE, riesgo aceptado:** SEC-I02 (broker sin permisos por destino; DEF-39). Darle a cada servicio su usuario y
sus permisos por destino cambia toda la mensajería, y el broker está embebido en el mismo WildFly, con los servicios
conectados por `in-vm` (sección 3.2).

Los IDs de la clase 11 se mantienen (SEC-W01 a W06, I01, I02, D01 y D02); los demás cubren las pruebas del eje D que esa
matriz no tenía.

| ID | Criterio | Prueba del eje D | Estado | Evidencia principal (corridas sobre `9caa865`) | Defecto |
|---|---|---|---|---|---|
| SEC-W01 | Un recurso ajeno no se puede ver ni tocar | BOLA / IDOR | CUMPLE | Escenarios 5 y 21 y Newman SEC-W01 y 404 de cada carpeta (run 45889485001); `seguridad.mjs` B (run 45889484808) | |
| SEC-W02 | El rol decide quién usa cada operación | BFLA | CUMPLE | Escenarios 5 y 21, Newman SEC-W02 y SEC-W02b y `SeguridadDeclarativaTest` en los 9 servicios (run 45889485001); Schemathesis (run 45889485045) | DEF-43 (cerrado el 26/11) |
| SEC-W03 | El login no revela si el email existe | (clase 11) | CUMPLE | Newman SEC-W03a y SEC-W03b y `LimiteDeIntentosDeLoginTest` (run 45889485001); `seguridad.mjs` E (run 45889484808) | DEF-75 (aceptado) |
| SEC-W04 | Fuerza bruta en el login: 429 | Fuerza bruta | CUMPLE | Newman SEC-W04, escenario 24 y `LimiteDeIntentosDeLoginTest` (run 45889485001) | DEF-41 (aceptado) |
| SEC-W05a | Token con la firma alterada: 401 | JWT | CUMPLE | `seguridad.mjs` A (run 45889484808); escenario 5 y Newman SEC-W05a (run 45889485001) | |
| SEC-W05b | Token con `alg` `none`: 401 | JWT | CUMPLE | `seguridad.mjs` A (run 45889484808); Newman SEC-W05b (run 45889485001) | |
| SEC-W05c | Vigencia corta o revocación del token | (clase 11) | CUMPLE | Newman «SEC-W05c Login: la sesión dura 15 minutos» (run 45889485001): `exp - iat` = 900 s | DEF-38 (cerrado el 26/11) |
| SEC-W05d | Token vencido: 401 | JWT | CUMPLE | `seguridad.mjs` A (run 45889484808) | |
| SEC-W05e | Token de otro emisor: 401 | JWT | CUMPLE | `seguridad.mjs` A (run 45889484808) | |
| SEC-W05f | Token de otra clave, con HS256 sobre la clave pública, con un rol agregado o sin `exp`: 401 | JWT | CUMPLE | `seguridad.mjs` A (run 45889484808) | |
| SEC-W06 | Entrada inválida: 400 limpio y sin persistir | (clase 11) | CUMPLE | Newman SEC-W06 y escenarios 4 y 24 (run 45889485001); Schemathesis, 5633 casos sin 5xx (run 45889485045) | DEF-77, DEF-80 y DEF-81 (cerrados el 26/11) |
| SEC-W07 | Mass assignment del rol en el registro | Mass assignment | CUMPLE | `seguridad.mjs` B (run 45889484808); escenario 24 (run 45889485001) | |
| SEC-W08 | Mass assignment de estado, precio, montos y dueño | Mass assignment | CUMPLE | `seguridad.mjs` B (run 45889484808) | |
| SEC-W09 | Inyección SQL y XSS reflejado en la búsqueda del catálogo | Inyección / XSS | CUMPLE | Escenario 24 (run 45889485001); `xss.spec.ts` (runs 45889484808 y 45889485001) | |
| SEC-W10 | Inyección y XSS almacenado en el resto de los campos de texto | Inyección / XSS | CUMPLE | `seguridad.mjs` C y `xss.spec.ts` (run 45889484808); ZAP activo (run 45889484996); Schemathesis (run 45889485045) | |
| SEC-W11 | Errores sin stack traces ni datos internos | Logs y errores | CUMPLE | Escenario 14 y `ErrorInesperadoTest` (run 45889485001); `seguridad.mjs` A, D y E (run 45889484808) | |
| SEC-W12 | Cabeceras CSP, HSTS y `nosniff` | Headers | CUMPLE | `seguridad.mjs` E (run 45889484808); `cabeceras.txt` del job Integración (run 45889485001); ZAP sin alertas de la CSP (run 45889484996) | DEF-69, DEF-70 y DEF-74 (cerrados el 26/11) |
| SEC-W13 | CORS con orígenes explícitos, sin comodín con credenciales | CORS | CUMPLE | `seguridad.mjs` E (run 45889484808); `CorsFilterTest` (run 45889485001); el origen de Vite contra el compose (`defectos-sobre-9caa865.txt`, punto 13) | DEF-42 (cerrado el 26/11) |
| SEC-W14 | Cookies `Secure`, `HttpOnly` y `SameSite`, o ninguna cookie | Cookies | CUMPLE | `seguridad.mjs` E y `xss.spec.ts` (run 45889484808) | DEF-58 (aceptado) |
| SEC-W15 | Una entrada pública no ocupa el servidor sin límite | (consumo de recursos) | CUMPLE | `NombreYApellidoTest` (run 45889485001); el pedido de 1500 espacios contra el compose, el 400 en 0,21 s (`registro-nombre-largo.txt`, punto 7) | DEF-64 (cerrado el 26/11) |
| SEC-I01 | SOAP con tiempo máximo y estado controlado ante una falla | (clase 11) | CUMPLE | Escenario 15, `ArcaSoapTest` y `FabricaDeArcaTest` (run 45889485001) | |
| SEC-I01b | XXE y entidades externas en el canal SOAP | XXE | CUMPLE | `seguridad.mjs` D (run 45889484808); `FabricaDeArcaTest` (run 45889485001); `/legado/` por nginx, 404 (`defectos-sobre-9caa865.txt`, punto 13) | DEF-45 (cerrado el 26/11) |
| SEC-I02 | Permisos por destino en el broker | (clase 11) | NO CUMPLE (riesgo aceptado) | `configurar.cli` sin usuarios ni `security-setting` | DEF-39 (aceptado) |
| SEC-D01 | Los logs no tienen DNI ni tokens | Logs | CUMPLE | "Control de logs" de los jobs Integración (run 45889485001), Pruebas de seguridad (45889484808), Contrato (45889485045) y ZAP (45889484996): limpio | DEF-40 (cerrado el 26/11) |
| SEC-D02 | Contraseñas con hash adaptativo y nunca en respuestas | Datos sensibles | CUMPLE | `ContrasenasTest`, `IteracionesAlIniciarSesionEnH2Test` y Newman SEC-D02 (run 45889485001); `seguridad.mjs` E (run 45889484808) | DEF-44 (cerrado el 26/11) |
| SEC-D03 | Los logs no tienen datos personales ni códigos de un solo uso | Logs | CUMPLE | "Control de logs" de Contrato (run 45889485045) y ZAP (run 45889484996) con el compose por defecto; contra el compose, `correo-en-el-log.txt` (punto 6) y el log de nginx sin emails (`defectos-sobre-9caa865.txt`, punto 13) | DEF-36 y DEF-72 (cerrados el 26/11) |
| SEC-D04 | Cifrado en tránsito (TLS) | TLS | CUMPLE | Humo por HTTPS del job Integración y E2E por HTTPS (run 45889485001); `seguridad.mjs` E (run 45889484808) | |
| SEC-D05 | Datos personales minimizados (DNI) | Datos sensibles | CUMPLE | `PagoConfirmadoMensajeTest`, `ServicioDeAuditoriaTest` y `MigracionesEnPostgresIT` de Notificaciones (run 45889485001); contra el compose, 0 filas de auditoría con DNI (`defectos-sobre-9caa865.txt`, punto 13) | DEF-37 (cerrado el 26/11) |
| SEC-D06 | Las respuestas no traen datos de otras personas ni secretos | Datos sensibles | CUMPLE | `seguridad.mjs` E (run 45889484808) | |
| SEC-E01 | ZAP baseline y activo autenticado: 0 High, Medium justificadas | ZAP | CUMPLE | Run 45889484996 (`resumen.md`): High 0, Medium 0 | DEF-62, DEF-69 y DEF-74 (cerrados el 26/11) |
| SEC-E02 | Dependencias e imágenes sin CVE críticas ni altas sin mitigación | Dependencias | CUMPLE | Run 45889484934 | DEF-68 (aceptado); DEF-78 (cerrado el 26/11) |
| SEC-E02b | La imagen de la base también escaneada | Dependencias | CUMPLE | Job "Imagen: db (trivy image)" del run 45889484934 | DEF-73 (cerrado el 26/11) |
| SEC-E03 | Sin secretos en el historial | Secretos (G6) | CUMPLE | Job Secretos (gitleaks) del run 45889485001, `gitleaks.sarif` | |
| SEC-E04 | SonarCloud: Quality Gate aprobado y sin security hotspots abiertos en el código nuevo | Análisis estático | CUMPLE | Check SonarCloud Code Analysis de `9caa865`: Quality Gate passed, 0 hotspots | DEF-71 y DEF-76 (cerrados el 26/11) |
| SEC-E04b | SonarCloud sobre todo el código: vulnerabilidades, rating de seguridad y hotspots revisados | Análisis estático | CUMPLE | `sonar.txt` y `quality-gate-9caa865.png` | DEF-71 (cerrado el 26/11) |
| SEC-AS01 | El asistente no ejecuta instrucciones ajenas, no revela su prompt ni datos de otros y no se cae con el proveedor | Eje E (8.3) | CUMPLE | `asistente.mjs`, Newman carpeta 9 y `RiesgosDelAsistenteTest` (run 45889485001) | DEF-63 (cerrado el 26/11) |

## 3. Detalle por criterio

Dónde está cada evidencia: los escenarios (`scripts/pruebas/escenarios.mjs`) corren en los jobs "Integración" del run
45889485001; Newman (`docs/api/palco-api.postman_collection.json`), en su job "API" (salida en
`reporte-api/newman/coleccion.txt`); los tests de JUnit, en el job "Backend" (reportes de surefire y failsafe en
`reportes-backend`); `seguridad.mjs` y `xss.spec.ts`, en el run 45889484808 (`seguridad.txt`). Las frases entre
comillas son la salida textual de cada prueba, la misma en las corridas sobre `fbd31dc` y sobre `9caa865`; lo que
describe un hallazgo cita la corrida de `fbd31dc`, donde se encontró, y lo dice.

### 3.1 Web y API

**SEC-W01 · Un recurso ajeno no se puede ver ni tocar (BOLA / IDOR) · CUMPLE**

- Criterio: con el token válido de otra cuenta, pedir o modificar una venta, entrada, factura, publicación, evento o
  registro de puerta ajenos cambiando el id da 404 Problem Details, igual que si no existiera, y el recurso no cambia.
- Escenario 5 (27 de 27): "con el token de otro usuario: ver, pagar y cancelar una venta ajena, y ver una entrada
  ajena, responden 404: 404 404 404 404 404" y "la venta ajena quedó como estaba: EN_CHECKOUT y sin pago".
- Escenario 21 (26 de 26): el otro organizador no ve los eventos ajenos, no asigna ni quita personal de puerta en un
  evento ajeno (404) y "un escaneo de un evento ajeno no se ve: 404".
- Newman: SEC-W01 (`GET /v1/entradas/{id}` de A con el token de B: 404 `no-encontrado` y "no filtra la entrada"),
  SEC-W01b, y en las demás carpetas: venta, factura y operación de reventa de otro comprador o vendedor (404),
  `DELETE /v1/reventas/{id}` ajena (404), `PUT /eventos/{id}` ajeno (404), resumen y cancelación de un evento ajeno
  (404), puerta y escaneos de un evento ajeno (404).
- `seguridad.mjs` B: una compra abierta mandando el `compradorId` de otra cuenta queda de quien la abrió ("la cuenta del
  compradorId mandado no la ve (404)"), y un evento editado con un `organizadorId` ajeno sigue siendo de su dueña.
- Tests: `ComprasEnCursoTest#unaVentaAjenaRespondeLoMismoQueUnaQueNoExiste`, `#laEntradaSoloLaVeQuienLaCompro`,
  `FacturasResourceTest#otroUsuarioNoVeUnaFacturaAjenaYRecibeElMismo404QueSiNoExistiera` y
  `ServicioDeValidacionTest#otroOrganizadorNoVeNiCambiaLaPuertaDeUnEventoAjeno`.

**SEC-W02 · El rol decide quién usa cada operación (BFLA) · CUMPLE**

- Criterio: con un token válido de un rol que no corresponde, 403 Problem Details; sin token, 401 con
  `WWW-Authenticate: Bearer`.
- Escenario 5: organizer, staff y servicio reciben 403 al abrir, listar y ver compras y entradas; user recibe 403 al
  crear un evento; servicio recibe 403 en `GET /api/usuarios/yo`; las 7 operaciones internas y de prueba (pagos, cupo,
  auditoría y las tres de prueba de Notificaciones) dan 401 sin token y 403 con user, organizer y staff, y "ninguno de
  esos pedidos rechazados publicó un mensaje en la cola"; un secreto de servicio incorrecto da 401.
- Escenario 21: `GET /api/eventos/mios` con rol user, 403.
- Newman: SEC-W02 (crear evento con rol user: 403 `sin-permiso`), SEC-W02b (sin token: 401 `no-autenticado` con
  `WWW-Authenticate: Bearer`) y los 403 por rol de cada carpeta (cupo con token de organizador, escaneo con rol user,
  transferencia de reventa con rol user, factura con token de staff, pagos con token de comprador, quitar personal de
  puerta con rol staff, editar y cancelar eventos con rol user).
- `seguridad.mjs` B: la cuenta registrada mandando `rol` y `groups` recibe 403 al crear un evento.
- Schemathesis: con el token de comprador, 27 operaciones respondieron solo 401 o 403 (las de otros roles); con el de
  organizador, 4, y con el de puerta, 3.
- Tests: `VentasResourceTest#todasLasOperacionesSonSoloParaCompradores`,
  `ReventaDeEntradasResourceTest#todasLasOperacionesSonSoloParaOtrosServicios`,
  `PagosResourceTest#todasLasOperacionesSonSoloParaOtrosServicios`, `EntradasResourceTest#laEntradaEsSoloParaCompradores`
  y `EscaneosResourceTest#escanearEsSoloParaElPersonalDePuerta`.
- Hallazgo: DEF-43 (Menor, cerrado el 26/11). En `fbd31dc`, en 7 de los 13 recursos
  ningún test unitario fallaba si una operación perdía su anotación de rol; ese cambio lo detectaban los escenarios 5
  y 21 solo en las operaciones que recorren. La corrección: `SeguridadDeclarativaTest` en el paquete `api` de cada
  servicio con recursos JAX-RS busca por reflexión cada operación y falla si alguna no tiene `@RolesAllowed`,
  `@PermitAll` ni `@DenyAll` (`#cadaOperacionDiceQuienLaPuedeLlamar`); sin el `@RolesAllowed("organizer")` del alta de
  eventos, falla y nombra `EventosResource#crear`. En el job "Backend" del run 45889485001, 9 de 9 en verde.

**SEC-W03 · El login no revela si el email existe · CUMPLE**

- Criterio: un email que no existe y uno que existe con la contraseña mal reciben la misma respuesta (401, mismo
  `detail`) y tardan lo mismo.
- Newman: SEC-W03a (email que no existe: 401 `no-autenticado`, 124 ms) y SEC-W03b (contraseña incorrecta: 401, "el
  mismo detail que con un email que no existe", 150 ms).
- `seguridad.mjs` E, por nginx y directo al backend: "el login fallido es el mismo 401 exista o no la cuenta, sin el
  email ni la contraseña: 401 "Revisá el email y la contraseña." / 401 "Revisá el email y la contraseña."".
- Escenario 24: "olvidé con un email que no existe: 204, igual que con uno que sí".
- Por qué tarda lo mismo: si el email no existe, se verifica igual contra `HASH_FALSO`, armado con el mismo costo que el
  hash de una cuenta (`ServicioDeUsuarios.java`, líneas 102 y 249). Tests:
  `LimiteDeIntentosDeLoginTest#elHashFalsoCuestaLoMismoQueElDeUnaCuentaNueva` y
  `#unEmailSinCuentaCuentaIgualYRecibeLaMismaRespuesta`.
- Hallazgo: DEF-75 (Menor, decisión de diseño). El registro sí lo dice: con un email o un DNI que ya tienen cuenta
  responde 400 con el campo (CA-01.2; escenario 24: "Ya hay una cuenta con ese email."), y el registro no cuenta
  intentos.

**SEC-W04 · Fuerza bruta en el login: 429 · CUMPLE**

- Criterio: con 5 logins fallidos de un email en 15 minutos, el sexto, aun con la contraseña correcta, recibe 429
  Problem Details `/problemas/demasiados-intentos` con `Retry-After`.
- Newman SEC-W04: cinco 401 (entre 120 y 129 ms) y el sexto 429 (6 ms) con `type` `demasiados-intentos` y
  `Retry-After`.
- Escenario 24: "después de 5 fallidos, aunque la contraseña sea la correcta: 429 con Retry-After: 429
  /problemas/demasiados-intentos Retry-After 900".
- Tests: `LimiteDeIntentosDeLoginTest` (10), entre ellos `#cincoFallidosYElSextoConLaClaveBuenaRecibe429`,
  `#laVentanaEsDeQuinceMinutos` y `#losIntentosDeUnEmailNoBloqueanAOtro`. Los límites son `MAX_INTENTOS_LOGIN = 5` y
  `VENTANA_INTENTOS_LOGIN` de 15 minutos (`ServicioDeUsuarios.java`, líneas 89 y 90), y el control va antes de verificar
  la contraseña (líneas 240 a 245): el 429 no calcula el hash, por eso tarda 6 ms.
- Hallazgo: DEF-41 (Menor, aceptado como decisión de diseño). Los intentos se cuentan por email y no por origen: quien
  conoce un email puede bloquearle el ingreso a esa persona 15 minutos. Es lo que pide CA-02.3; el bloqueo dura como
  mucho 15 minutos, `Retry-After` dice cuánto falta y "Olvidé mi contraseña" borra los fallidos de ese email
  (`ReinicioDePasswordTest#cambiarLaContrasenaOlvidaLosIntentosDeLoginFallidos`). El motivo completo, en el registro.

**SEC-W05a · Token con la firma alterada: 401 · CUMPLE**

- `seguridad.mjs` A: "token con la firma alterada (un byte cambiado): 401 token-invalido en Problem Details en los
  cinco, sin stack trace" (Usuarios, Eventos, Ventas, Reventa y Validación: cada WAR verifica el token por su cuenta).
- Escenario 5 ("con un token con la firma alterada (se espera 401): 401"), Newman SEC-W05a,
  `TokensTest#unTokenModificadoNoVerifica` y `ErrorDeAutenticacionServletTest#elDesafioEsBearerConElErrorDelEstandar`.

**SEC-W05b · Token con `alg` `none`: 401 · CUMPLE**

- `seguridad.mjs` A: "token con alg none y sin firma: 401 token-invalido en Problem Details en los cinco, sin stack
  trace". Newman SEC-W05b: `GET /yo` con un token sin firma, 401 Problem Details.

**SEC-W05c · Vigencia corta o revocación del token · CUMPLE (DEF-38, cerrado el 26/11)**

- El criterio pide una vigencia corta o que el token se pueda revocar. En `fbd31dc` la sesión duraba 8 horas, sin
  revocación (DEF-38, Mayor), y estaba aceptada como riesgo desde el 16/10.
- Corrección (25/11): la sesión dura 15 minutos (`ServicioDeUsuarios.DURACION_SESION = Duration.ofMinutes(15)`). El token es un
  JWT que cada servicio valida sin consultar a Usuarios, así que no hay revocación: el criterio se cumple con la vigencia
  corta. Quince minutos alcanzan para una compra entera (la reserva dura 10 minutos) y, al vencer, el frontend pide ingresar de
  nuevo. Salir de la sesión borra el token del navegador.
- Newman, en el job "API" del run 45889485001: «SEC-W05c Login: la sesión dura 15 minutos» comprueba `exp - iat` =
  900 s; la colección completa, 682 aserciones sin fallas. El token tampoco se puede alargar ni cambiar (SEC-W05a, b, d, e y f).
- Además, cambiar `PALCO_JWT_CLAVE_PRIVADA` (o, si no está definida, reiniciar el backend, que genera una clave nueva)
  invalida todas las sesiones abiertas.

**SEC-W05d · Token vencido: 401 · CUMPLE**

- `seguridad.mjs` A: "token vencido hace una hora, bien firmado: 401 token-invalido en Problem Details en los cinco, sin
  stack trace".
- Cómo se sabe que el 401 es por el vencimiento y no por la firma: el workflow genera la clave JWT de la corrida y se la
  pasa al compose y al guion; el guion comprueba antes que "la clave de PALCO_JWT_CLAVE_PRIVADA es la del servidor (su
  parte pública es la de /api/usuarios/jwks)" y que "el mismo token, vuelto a firmar por el guion: 200 en los cinco".

**SEC-W05e · Token de otro emisor: 401 · CUMPLE**

- `seguridad.mjs` A: "token de otro emisor (iss distinto de "palco"), bien firmado: 401 token-invalido en Problem
  Details en los cinco". El emisor se exige con `mp.jwt.verify.issuer=palco`
  (`backend/common/src/main/resources/META-INF/microprofile-config.properties`).

**SEC-W05f · Otra clave, confusión de algoritmo, rol agregado y token sin vencimiento: 401 · CUMPLE**

- `seguridad.mjs` A, cada uno con 401 `token-invalido` en Problem Details en los cinco servicios y sin stack trace:
  "token firmado con otra clave RSA (con el kid del servidor)", "token con HS256 y la clave pública del servidor como
  secreto (confusión de algoritmo)", "token con el rol servicio agregado y la firma original" y "token sin fecha de
  vencimiento (sin exp), bien firmado".

**SEC-W06 · Entrada inválida: 400 limpio y sin persistir · CUMPLE**

- Newman SEC-W06: compra sin `Idempotency-Key`, 400 `datos-invalidos` "sin información interna".
- Escenario 4 (23 de 23): cada regla de negocio con su código y su cuerpo Problem Details. Escenario 24: registro con
  email repetido, DNI de 6 dígitos o menor de 16 años, y alta de evento con fecha pasada, categoría inexistente o cupo
  0: todos 400 `datos-invalidos` con el campo.
- Schemathesis (run 45889485045): 5633 casos con datos generados en las 52 operaciones y los 4 roles, con los controles
  `not_a_server_error`, `status_code_conformance` y `response_schema_conformance`: ninguna respuesta 5xx ni fuera del
  contrato. Las tres entradas que daban 500 (el carácter nulo U+0000 en un email, un id o un código QR: DEF-59, DEF-60 y
  DEF-61) se corrigieron el 21/11, y ni la corrida de `fbd31dc` ni la de `9caa865` las encuentran.
- Sin persistir: "el registro no cambió" (escenario 21), "el cupo de sec-… quedó como al empezar" (escenario 5) y
  "precio 30000 con un precioMaximo inventado de 40000: la entrada no quedó publicada" (`seguridad.mjs` B).
- El 400 es el correcto también con un nombre lleno de espacios en el registro, pero en `fbd31dc` tarda segundos (7 s
  con 1500 espacios): eso es SEC-W15 (DEF-64, corregido el 24/11).
- Después de `fbd31dc`, la corrida del Contrato sobre `ff12b59` (run 45635998907, con el token de servicio) encontró un
  500 en `GET /v1/pagos` con el carácter nulo en la clave de idempotencia: DEF-77 (Menor). Pagos rechaza ahora con 400
  la clave, la venta, el medio de pago y el motivo con caracteres de control, y con 404 un id así
  (`ServicioDePagos.sinCaracteresDeControl`, `a33194d`); lo cubren
  `ServicioDePagosTest#buscarConLaClaveDelContratoEsUn400SinConsultarLaBase` y tres tests más, y el Contrato de
  `9caa865` (run 45889485045), con el token de servicio, pasó sin respuestas 5xx. DEF-77 se cerró el 26/11.
- La corrida del Contrato sobre `df7539b` encontró un 504 en `GET /v1/resumenes?eventoId=` con el carácter nulo:
  DEF-80 (Menor). `70242ce` lo corrige en `ResumenDeVentas.delEvento`, que responde 400 sin consultar a Eventos
  (`ResumenDeVentasTest#unEventoIdConUnCaracterDeControlEsUn400SinConsultarAEventos`, run 45880936756), y el Contrato
  de `70242ce` pasó en los cuatro roles. DEF-80 se cerró el 26/11. Contra el compose, el mismo pedido responde 400
  `datos-invalidos` directo al backend y por nginx (`defectos-sobre-9caa865.txt`, punto 13).
- La corrida del Contrato sobre `dfd89c4` (run 45887315864) encontró un 500 en `POST /api/eventos` con el carácter
  nulo en un texto, porque PostgreSQL no lo guarda: DEF-81 (Menor). `358ce5e` hace que los textos del evento lo
  rechacen con 400 (`MensajesDeValidacionTest#unTextoConElCaracterNuloSeRechazaCampoPorCampo` y
  `#laDescripcionYElMotivoAdmitenSaltosDeLineaYElTituloNo`), y `9caa865`, que una factura con un `ventaId` con
  caracteres de control sea 404 sin consultar la base
  (`ServicioDeFacturacionTest#unIdDeVentaConElCaracterNuloEsUn404SinConsultarLaBase`); los tres pasan en el run
  45889485001, y el Contrato de `9caa865` pasó en los cuatro roles. Contra el compose, el título y la descripción con
  el carácter nulo dan 400 y la factura, 404 (`defectos-sobre-9caa865.txt`, punto 13). DEF-81 se cerró el 26/11.

**SEC-W07 · Mass assignment del rol en el registro · CUMPLE**

- `seguridad.mjs` B: el registro con `id`, `rol`, `groups` y `emailVerificado` responde 201; "el emailVerificado mandado
  no cuenta: el login responde 409 conflicto (falta verificar el email)"; "al verificar el email, la cuenta es rol user
  y con el id que le dio el servidor"; "su token lleva solo el grupo user: ["user"]"; con ese token, crear un evento da
  403.
- Escenario 24: "mandar "rol": "organizer" al registrarse no sirve: la cuenta es user".

**SEC-W08 · Mass assignment de estado, precio, montos y dueño · CUMPLE**

- `seguridad.mjs` B (20 de 20 con SEC-W07), leyendo el recurso después de cada pedido:
  - Evento creado con `id`, `slug`, `organizadorId`, `destacado`, `cancelado` y `vendidas`: "id, slug, destacado,
    cancelado y vendidas los puso el servidor", y "el evento creado es del organizador del token, no del organizadorId
    mandado". Después de un `PUT` con esos campos, cambió solo el título.
  - Compra con `estado`, `montoTotal`, `compradorId`, `precioUnitario` y `pagoId`: "EN_CHECKOUT, sin pago y con los
    montos del servidor (2 × 20000 + 10 % = 44000)".
  - Publicación de reventa con `estado`, `vendedorId`, `precioMaximo`, `id` y `eventoId`: "ACTIVA, del evento de la
    entrada y con el tope del servidor (22000)"; un precio de 30000 con un `precioMaximo` inventado de 40000 da 422
    `precio-fuera-de-rango` y no se publica.
- Por qué: la API lee el cuerpo con JSON-B (`backend/common/.../rest/JsonProvider.java`), que descarta las propiedades
  que el DTO no tiene, y ningún DTO de entrada trae esos campos.

**SEC-W09 · Inyección SQL y XSS reflejado en la búsqueda del catálogo · CUMPLE**

- Escenario 24: `búsqueda con "' OR '1'='1": 200 o 400, nunca 500: 200` y
  `búsqueda con "<script>alert(1)</script>": 200 o 400, nunca 500: 200`.
- `xss.spec.ts` (segunda prueba, "XSS sin pasar por el formulario: un título con HTML guardado por la API y una
  búsqueda con HTML se ven como texto, sin sesión"): la pantalla muestra "Sin resultados para «…»" con el texto tal
  cual, sin ningún elemento inyectado ni `alert()`.
- Por qué no hay inyección: el texto de `q` no llega a la base; `ServicioDeEventos.listarEventos` filtra en Java
  (`ServicioDeEventos.java`, líneas 115 a 125) y la respuesta es JSON.

**SEC-W10 · Inyección y XSS almacenado en el resto de los campos de texto · CUMPLE**

- `seguridad.mjs` C (6 de 6): un evento con `<script>alert(1)</script> <img src=x onerror=alert(1)>` en el título y la
  descripción se guarda y vuelve tal cual, como texto en JSON (`application/json`), en el detalle y en la búsqueda; "el
  slug, que va en la URL, sale solo con letras, números y guiones"; una compra con ese HTML en el nombre del titular
  emite una entrada que lo devuelve como texto en JSON.
- `xss.spec.ts` (Chromium, por nginx y HTTPS, 2 de 2; también 39 de 39 en el job E2E del CI): el HTML cargado desde el
  formulario del organizador en cada texto del evento, y un título con HTML guardado por la API, se ven como texto en el
  panel del organizador, el catálogo y el detalle: "ningún elemento de las cargas", `ningún <script> con las cargas` y
  "ningún alert() se ejecutó".
- ZAP, escaneo activo de la API (sección 4.1): con todas las operaciones del OpenAPI, sobre `9caa865` la única alerta
  de inyección es la regla 40018 en la pregunta al asistente, un falso positivo verificado. Sobre `fbd31dc`, la única fue la regla 40018 en cuatro parámetros, y las cuatro se comprobaron
  como falsos positivos.
- Schemathesis: ningún texto generado en ningún campo dio una respuesta 5xx.
- Además, en el código: no hay consultas nativas ni consultas armadas concatenando texto (`createNativeQuery`,
  `Statement`, `executeQuery`, `prepareStatement` y `createQuery` con `+` no aparecen en `backend/common/src/main` ni en
  `backend/servicios/*/src/main`); `dangerouslySetInnerHTML`, `innerHTML` y `eval(` no aparecen en `frontend/src`; la
  CSP tiene `script-src 'self'`.
- Alcance de la prueba del navegador: recorre el panel, el catálogo, el detalle y la búsqueda. El nombre del titular
  (que se muestra en "Mis entradas" y en la puerta) está probado por la API, en el bloque C.

**SEC-W11 · Errores sin stack traces ni datos internos · CUMPLE**

- Escenario 14 (job Integración: conciliacion-pagos, 18 de 18): una falla provocada después del cobro responde "500
  error-inesperado en Problem Details" y "el 500 trae idError y nada interno".
- `seguridad.mjs`: las 40 respuestas 401 del bloque A (8 tokens manipulados en 5 servicios) y los SOAP Fault del
  bloque D se controlan sin stack trace, y en el bloque E "el 401 por un token alterado no repite el token ni su
  contenido".
- `ErrorInesperadoTest` (3): el id del 500 es el mismo que queda en el log con la traza, y los demás errores no lo
  llevan. Todos los errores salen de `ManejadoresDeErrores` (`backend/common`).
- ZAP, escaneo de la API: con 252 respuestas de error de cliente, ninguna alerta de divulgación de errores. Schemathesis:
  ninguna respuesta 5xx.

**SEC-W12 · Cabeceras CSP, HSTS y `nosniff` · CUMPLE**

- `seguridad.mjs` E, en las 17 respuestas por nginx: "Content-Security-Policy (default-src 'self' y script-src sin
  'unsafe-inline', 'unsafe-eval' ni comodines)", "Strict-Transport-Security (max-age de un año o más) en las 17
  respuestas: max-age=31536000; includeSubDomains" y "X-Content-Type-Options (nosniff)".
- `cabeceras.txt` del job Integración (paso "Humo por HTTPS"): además `Referrer-Policy: strict-origin-when-cross-origin`,
  `X-Frame-Options: DENY`, `Permissions-Policy` y `server: nginx` sin versión (`server_tokens off`). El paso falla si
  falta CSP, HSTS, `X-Content-Type-Options` o `Referrer-Policy`.
- Hallazgos:
  - DEF-69 (Menor, cerrado el 26/11): la CSP de `fbd31dc` permitía estilos
    en línea (`style-src 'unsafe-inline'`) e imágenes de cualquier origen `https:`, las dos alertas Medium de ZAP,
    justificadas en `.zap/reglas.tsv` (sección 4.1). Desde el 25/11, los dos bloques de cabeceras de
    `frontend/docker/nginx.conf` quedan con `style-src 'self'` e `img-src 'self' data: blob: https://picsum.photos`:
    los estilos están en los `.css` del build, y los de framer-motion y los `style` de React se aplican por JavaScript
    (CSSOM), que la CSP no frena; las fotos que se suben se sirven desde Palco y las de los eventos de ejemplo, desde
    picsum.photos. Contra el compose (25/11), recorriendo el catálogo, un evento, recuperar la contraseña, el panel del
    organizador, Mis entradas, la reventa y el asistente: 0 avisos de CSP en la consola; la E2E, 39 de 39, y
    `seguridad.mjs`, 64 de 64. Lo confirma el ZAP de `9caa865` (run 45889484996), sin esas dos Medium, y la CSP de
    `cabeceras.txt` del job Integración del run 45889485001.
  - DEF-70 (Menor, cerrado el 26/11): la CSP de `fbd31dc` todavía habilitaba
    `https://fonts.googleapis.com` en `style-src` y `https://fonts.gstatic.com` en `font-src`, aunque desde `c4ec165`
    las fuentes se sirven del propio sitio (`frontend/public/fuentes/`). Desde el 24/11, los dos bloques de cabeceras
    de `frontend/docker/nginx.conf` quedan con `font-src 'self'` y sin esos orígenes en `style-src`; lo confirman
    el bloque E de `seguridad.mjs` y `cabeceras.txt` sobre `9caa865`.
  - DEF-74 (Cosmética, cerrado el 26/11): en `fbd31dc` no estaban
    `Cross-Origin-Resource-Policy`, `Cross-Origin-Opener-Policy` ni `Cross-Origin-Embedder-Policy` (alertas Low de
    ZAP). Los dos bloques de cabeceras de `frontend/docker/nginx.conf` mandan ahora
    `Cross-Origin-Opener-Policy: same-origin` y `Cross-Origin-Resource-Policy: same-origin`. COEP no va: con
    `require-corp` el navegador bloquearía las fotos de los eventos que vienen de otro origen (las de los eventos de
    ejemplo, de picsum.photos); la justificación está en `.zap/reglas.tsv` (renglón 56). En el ZAP de `9caa865` (run
    45889484996) quedan solo dos Low, las dos justificadas: COEP y Unexpected Content-Type.

**SEC-W13 · CORS con orígenes explícitos, sin comodín con credenciales · CUMPLE**

- `seguridad.mjs` E, por nginx y directo al backend: "a un Origin ajeno no le responde Access-Control-Allow-Origin ni
  Allow-Credentials, ni en un GET ni en el preflight de una compra"; "al origen de la app (https://localhost) le responde
  ese origen exacto y no *, con Allow-Credentials y Vary: Origin"; "en ninguna de las 32 respuestas
  Access-Control-Allow-Origin es *".
- Tests: `CorsFilterTest#unOrigenPermitidoRecibeLasCabeceras`, `#sinOrigenOConOtroOrigenNoHayCabeceras` y
  `#laListaDeOrigenesSeLeeSeparadaPorComas`.
- Hallazgo: DEF-42 (Menor, cerrado el 26/11). El compose de `fbd31dc` sumaba
  `http://localhost:5173` (el servidor de desarrollo de Vite) a la URL pública en `PALCO_CORS_ORIGENES`. Ahora
  `docker-compose.yml` deja por defecto solo la URL pública
  (`${PALCO_CORS_ORIGENES:-${PALCO_URL_PUBLICA:-https://localhost}}`); para usar `npm run dev` contra ese backend se
  define `PALCO_CORS_ORIGENES` con los dos orígenes. Contra el compose, el 26/11, con ese origen la respuesta no trae
  ninguna cabecera `Access-Control-*` (`defectos-sobre-9caa865.txt`, punto 13).

**SEC-W14 · Cookies `Secure`, `HttpOnly` y `SameSite`, o ninguna cookie · CUMPLE (Palco no emite cookies)**

- `seguridad.mjs` E: "ninguna de las 17 respuestas del recorrido trae Set-Cookie" por nginx (la página, un archivo de
  `/assets`, los login, el perfil, el catálogo, una compra hasta la emisión y sus errores) y ninguna de las 15 directas
  al backend.
- `xss.spec.ts`: `context.cookies()` vacío "después de ingresar", "después de crear el evento con la sesión abierta" y
  "sin sesión, después del catálogo y el detalle".
- El token viaja en `Authorization: Bearer`: sin cookies no hay atributos que poner ni CSRF por cookies.
- Riesgo aceptado: DEF-58 (Menor, decisión de diseño). El frontend guarda el token en `localStorage`, donde un script
  inyectado podría leerlo; lo acotan la CSP (`script-src 'self'`) y el escapado de React, probado en SEC-W10.

**SEC-W15 · Una entrada pública no ocupa el servidor sin límite · CUMPLE (DEF-64, Crítica, cerrado el 26/11)**

- Criterio: un pedido sin sesión, de pocos KB, se responde en un tiempo acotado aunque sus datos sean inválidos (OWASP
  API Security Top 10 2023, API4: consumo de recursos sin límite).
- `docs/testing/evidencia/final/registro-nombre-largo.txt` (24/11, contra el compose de la máquina de QA, con
  `Dtos.java` igual al de `fbd31dc`): `POST /api/usuarios/registro` sin sesión responde el 400 esperado en 0,21 s con
  un nombre común, en 2,36 s con un nombre de 1000 espacios y un 1, y en 7,10 s con 1500 espacios (un cuerpo de
  1653 bytes). La expresión sola tarda 1204 ms con 1000 espacios y 10.338 ms con 2000: cada vez que el largo se
  duplica, el tiempo se multiplica por 7 a 9.
- Causa: la expresión de `nombre` y `apellido` en `RegistroDto` (`usuarios/api/Dtos.java`, líneas 44 y 48,
  `^\s*[A-Za-zÀ-ÿ' -]{2,}\s*$`) tiene el espacio en las tres partes, y esos campos no tienen `@Size`: con un texto
  de espacios que no termina bien, prueba cada forma de repartirlos. SonarCloud marca esas dos líneas con
  `java:S8786` ("super-linear performance due to backtracking"; `sonar.txt`).
- Por qué es Crítica (sección 9 de los Criterios: seguridad explotable): cualquiera, sin cuenta, ocupa un hilo del
  servidor con cada pedido. Es la misma clase de falla que DEF-63 en el asistente.
- Por qué no lo encontraron las demás pruebas: Schemathesis y ZAP probaron el registro con sus propios valores y
  ninguno manda cientos de espacios seguidos.
- Corrección (24/11, DEF-64): `nombre` y `apellido` usan la constante `Dtos.NOMBRE`, `^\s*+[A-Za-zÀ-ÿ' -]{2,}+\s*+$`,
  con cuantificadores posesivos: la expresión no vuelve atrás y el tiempo crece en forma lineal con el largo. Además,
  `@Size(max = 80)`, el largo de la columna. `NombreYApellidoTest` (4 tests) rechaza 100.000 espacios y un dígito en
  menos de un segundo, en el nombre y en el apellido; en el job Backend del run 45889485001, 4 de 4.
- Contra el compose, el 26/11 (`registro-nombre-largo.txt`, punto 7): el 400 en 0,21 a 0,22 s con 1000, 1500 y
  100.000 espacios, el mismo tiempo que con un nombre común. DEF-64 se cerró el 26/11 con esas dos pruebas.

### 3.2 Integración

**SEC-I01 · SOAP con tiempo máximo y estado controlado ante una falla · CUMPLE**

- Escenario 15 (10 de 10): la compra confirmada se factura una sola vez, con número y CAE del ARCA simulado.
- Tests (`mvn verify`): `ArcaSoapTest` (7), entre ellos `#siArcaTardaMasQueElTiempoDeLecturaFallaSinEsperarlo`,
  `#unSoapFaultEsSinRespuesta` y `#conArcaCaidoDespliegaIgualFallaAlPedirYSeRecuperaCuandoVuelve`;
  `FabricaDeArcaTest#unTiempoDeEsperaEnCeroSeRechazaPorqueSeriaEsperarParaSiempre`.
- Contra el servidor, el 19/11: con el ARCA sin responder, cada pedido se cortó a los 5 s, el mensaje pasó a la DLQ
  después de 5 entregas y, devuelto, salió una sola factura
  (`docs/testing/evidencia/checkpoint-3/arca-lento-dlq.txt`, 29 de 29).

**SEC-I01b · XXE y entidades externas en el canal SOAP · CUMPLE**

- `seguridad.mjs` D (6 de 6), contra `/legado/arca` en el puerto del backend (`PALCO_URL`, por defecto
  `http://localhost:8080`):
  - "el canal está arriba: GET /legado/arca?wsdl publica FECAESolicitar: 200" y "el mismo sobre sin DOCTYPE se atiende:
    200 con Resultado A".
  - Entidad externa a `file:///etc/passwd`, DTD externa por una entidad de parámetro (un pedido al propio servidor) y
    entidades internas anidadas: los tres, "500 con SOAP Fault, sin respuesta del servicio ni contenido expandido"
    (`faultcode` `soap:Client`, "Error reading XMLStreamReader: Received event DTD, instead of START_ELEMENT").
  - "en el server.log está el control y ningún comprobante de los sobres con DOCTYPE: control sí, de los ataques 0".
- El cliente SOAP de Facturación lee la respuesta con Apache CXF, que trae la DTD y las entidades externas
  deshabilitadas salvo que se active `org.apache.cxf.stax.allowInsecureParser`; `FabricaDeArca` no crea el cliente si
  está en `true` (`FabricaDeArca.java`, líneas 46 y 61; `FabricaDeArcaTest#conElParserInseguroDeCxfActivadoNoSeCreaElCliente`).
  Ese lado se prueba por la configuración, no con una respuesta que traiga una DTD.
- Hallazgo: DEF-45 (Menor, cerrado el 26/11). El ARCA simulado no pide autenticación, y el nginx
  del compose de `fbd31dc` lo publicaba. Ahora `location /legado/` de `frontend/docker/nginx.conf` responde 404:
  Facturación lo llama por la red interna (`PALCO_URL_INTERNA`) y esta prueba, por el puerto del backend, que el
  compose publica solo en `127.0.0.1`. Contra el compose, el 26/11, `https://localhost/legado/arca?wsdl` y un `POST`
  a `/legado/arca` por nginx responden 404 (`defectos-sobre-9caa865.txt`, punto 13).

**SEC-I02 · Permisos por destino en el broker · NO CUMPLE (riesgo aceptado, DEF-39)**

- `backend/servidor/src/main/wildfly/configurar.cli` crea la cola, los tópicos y la DLQ, sin usuarios del broker ni
  `security-setting` por destino: cualquier WAR desplegado puede publicar o consumir en cualquier destino.
- Riesgo aceptado el 16/10 (`docs/adr/ADR-005-broker-embebido.md`; documento único, 14.8): darle a cada servicio su
  usuario y sus permisos por destino cambia toda la mensajería, y el broker está embebido en el mismo WildFly, los
  servicios se conectan por `in-vm` y ningún sistema externo se conecta a él. Si el sistema se despliega en más de un
  servidor: un usuario por servicio y un `security-setting` por destino.
- Es el único criterio de la matriz que no se cumple.

### 3.3 Datos

**SEC-D01 · Los logs no tienen DNI ni tokens · CUMPLE**

- Tokens: 0 coincidencias de `Bearer [A-Za-z0-9._-]{20,}` o `eyJ[A-Za-z0-9_-]{10,}\.eyJ` en el `server.log` de
  Integración general (2803 renglones), API (1190), Pruebas de seguridad (1381, después de mandar los tokens
  manipulados) y Contrato (1227), en las corridas sobre `fbd31dc`. Sobre `9caa865`, los pasos "Control de logs"
  (`scripts/qa/control-de-logs.sh`, que falla con un token, una contraseña de prueba o un DNI) dieron "logs/server.log
  está limpio" en los cuatro jobs de Integración (2823, 1473, 1177 y 1162 renglones), Pruebas de seguridad (1392,
  después de mandar los tokens manipulados), Contrato (1213) y ZAP (1241).
- DNI: de los 7 DNI de titulares que imprimieron los escenarios del grupo general, ninguno aparece en el `server.log` de
  ese job (comprobado el 24/11 sobre el artefacto `integracion-general`, con `grep -F` de la lista). Tests:
  `ServicioDeNotificacionesTest#elAvisoNoLlevaElDniDeNingunTitular`, `AsistenteIATest#alModeloNoLlegaNingunDniNiTarjeta` y
  `GuardrailsTest#enmascaraDniTarjetaYEmail`.
- Renglones falsos en el log: `TextosTest#enUnaLineaCambiaPorUnEspacioTodoLoQueCortaElRenglon` y
  `LogEnUnSoloRenglonTest` (4).
- Hallazgo: DEF-40 (Menor, cerrado el 26/11). En `fbd31dc`, el paso del CI "Control de logs (sin
  tokens ni DNI de prueba)" buscaba solo tokens. Ahora los pasos "Control de logs" de `ci.yml`, `contrato.yml`,
  `pruebas-seguridad.yml` y `zap.yml` corren `scripts/qa/control-de-logs.sh`, que falla con un token, una contraseña de
  prueba, un DNI de las cuentas de prueba o un número de 7 u 8 dígitos junto a la palabra "dni"; en los jobs que corren
  con `PALCO_CORREO_LOG_COMPLETO` en su valor por defecto (`contrato.yml` y `zap.yml`), también con cualquier email
  `@palco.test` entero. Informa el número de renglón y no el renglón. Sobre `9caa865`, esos pasos en verde (arriba).

**SEC-D02 · Contraseñas con hash adaptativo y nunca en respuestas · CUMPLE**

- `ContrasenasTest` (7): `#losHashesNuevosUsan600MilIteracionesYLosViejosSiguenVerificando`,
  `#dosHashesDeLaMismaContrasenaSonDistintosPorLaSal`, `#laContrasenaCorrectaVerificaYLaIncorrectaNo`,
  `#unHashMalFormadoNoVerificaNiRompe`, `#verificaLosHashesDeLasCuentasDePrueba` y
  `#elHashInutilizableTieneLasIteracionesDeSiempreYNoVerificaConNinguna` y `#tieneMenosIteracionesSoloUnHashConMenosDe600Mil`.
- Newman SEC-D02 ("sin password ni hash") y `seguridad.mjs` E: "el login y GET /api/usuarios/yo traen solo los datos de
  la propia cuenta, sin contraseña ni hash: 200, campos apellido, dni, email, id, identidadVerificada, nombre, rol".
- Por qué PBKDF2 y no bcrypt o Argon2, con los parámetros del código: sección 7.
- Hallazgo: DEF-44 (Menor, cerrado el 26/11). En `fbd31dc`, una cuenta creada antes del
  16/10 conservaba su hash de 310.000 iteraciones hasta que se volviera a guardar. Ahora
  `ServicioDeUsuarios.iniciarSesion`, después de verificar la contraseña, guarda un hash nuevo de 600.000 iteraciones
  si `Contrasenas.tieneMenosIteraciones` dice que el guardado tiene menos
  (`IteracionesAlIniciarSesionEnH2Test#unaCuentaCon310MilIteracionesIngresaYQuedaCon600Mil`,
  `#conLaContrasenaEquivocadaElHashNoCambia` y `ContrasenasTest#tieneMenosIteracionesSoloUnHashConMenosDe600Mil`,
  en verde en el run 45889485001).

**SEC-D03 · Los logs no tienen datos personales ni códigos de un solo uso · CUMPLE (DEF-36 y DEF-72, cerrados el 26/11)**

- Lo que cumple, con prueba:
  - El asistente registra el id de la cuenta y no su email: en los `server.log` de las corridas, los renglones son
    `Asistente: usuario=user-titular modelo=simulado-1 origen=MODELO …`.
    Tests: `AsistenteIATest#laLineaDeRegistroLlevaElIdDeQuienPreguntaYNingunEmail` y
    `AsistenteResourceTest#elAsistenteRecibeElIdInternoYNoElEmailDeQuienPregunta`.
  - Sin la variable `PALCO_CORREO_LOG_COMPLETO`, `CorreoAlLog` escribe el destinatario enmascarado y el asunto, sin el
    cuerpo (`CorreoAlLogTest#porDefectoQuedanElDestinatarioEnmascaradoYElAsuntoPeroNiElCuerpoNiElCodigo` y
    `#sinLaVariableElModoEsElQueNoEscribeDatosPersonales`), y una falla del SMTP no escribe el email de nadie ni la
    traza (`CorreoSmtpTest#elWarningDeUnEnvioQueFallaNoTraeElEmailDeNadieNiLaTraza`).
- Lo que no cumple en `fbd31dc`:
  - El `docker-compose.yml` de ese commit define `PALCO_CORREO_LOG_COMPLETO` en `true` por defecto, así que el sistema
    levantado con el comando único escribe cada email entero. En el `server.log` del job Integración general hay 28
    renglones con direcciones `@palco.test`: los avisos de compra con el nombre del comprador y de los titulares, y 3
    renglones con un código de un solo uso, dos para verificar el email y uno para cambiar la contraseña ("Email para
    integracion.…@palco.test (Tu código de Palco): Hola Bruno: tu código para verificar el email es …"). Es DEF-36 (Mayor, cerrado el 26/11): la corrección del
    20/11 cambió el modo por defecto del código, y el compose de `fbd31dc` prende el modo completo.
  - nginx escribe en su log de accesos la ruta y la consulta de cada pedido. El email de la cuenta de puerta viaja en la
    ruta de `PUT` y `DELETE /api/v1/validacion/puertas/{eventoId}/{email}` (`frontend/src/servicios/validacion.ts`,
    línea 78) y en la consulta de `GET /api/usuarios/cuentas?email=` (operación interna, rol `servicio`). En el log del
    contenedor web de la corrida de ZAP quedó `"GET /api/usuarios/cuentas?email=puerta@palco.test …"`, y ZAP lo marca
    como "Information Disclosure - Sensitive Information in URL" (regla 10024, Info). Es DEF-72 (Menor).
- Corrección de DEF-36 (24/11): `docker-compose.yml` deja `PALCO_CORREO_LOG_COMPLETO` en `false` por defecto, así que
  el compose ya no prende el modo completo. Los workflows que leen el código del log lo prenden en su entorno; los que
  corren con el valor por defecto (`contrato.yml` y `zap.yml`) fallan si en el `server.log` queda un email
  `@palco.test` entero (`scripts/qa/control-de-logs.sh`, DEF-40).
- Corrección de DEF-72: `frontend/docker/nginx.conf` escribe el log de accesos con su propio formato (`palco`), que
  reemplaza por `<email>` el email de la ruta de las puertas y el de un `email=` en la consulta, y no escribe la ruta
  si todavía le queda un `@` o un `%40`; el `Referer` recibe el mismo control. La API no cambia: la URL sigue llevando el
  email, y por eso ZAP puede seguir informando la regla 10024 (Info).
- Cierre de DEF-36 (26/11): contra el compose sin definir la variable, un registro nuevo, una compra de
  `titular@palco.test` y una pregunta al asistente con ese email dejan en el `server.log` 0 emails enteros y 0
  códigos, solo renglones como
  `Email para d***@palco.test (Tu código de Palco), sin el cuerpo: lo escribe PALCO_CORREO_LOG_COMPLETO=true.`
  (`docs/testing/evidencia/final/correo-en-el-log.txt`, punto 6). En los runs de `9caa865` que corren el compose con
  el valor por defecto, el paso "Control de logs", que falla con un email `@palco.test` entero, dio limpio: Contrato
  (run 45889485045, 1213 renglones) y ZAP (run 45889484996, 1241).
- Cierre de DEF-72 (26/11): contra el compose, asignar y quitar una cuenta de puerta deja en el log de nginx la ruta
  con `<email>`, y en todo el log del contenedor web hay 0 pedidos con `@` o `%40` en la ruta
  (`defectos-sobre-9caa865.txt`, punto 13).

**SEC-D04 · Cifrado en tránsito (TLS) · CUMPLE**

- Job Integración, paso "Humo por HTTPS": `curl -skI https://localhost/` respondió `HTTP/2 200` con las cabeceras de
  seguridad (`cabeceras.txt`), `http://localhost/` respondió 301 (el paso lo exige) y los 9 `health` respondieron 200
  por `https://localhost/api/…`. La suite E2E, `xss.spec.ts` y el bloque E de `seguridad.mjs` corren por HTTPS.
- `frontend/docker/nginx.conf`: escucha en 443 con `ssl_protocols TLSv1.2 TLSv1.3`, el puerto 80 redirige con 301 y
  HSTS de un año.
- El certificado es autofirmado y se genera al arrancar el contenedor web (`frontend/docker/certificado.sh`): no está en
  el repo ni en la imagen. TLS termina en nginx; de nginx a WildFly el tráfico va por la red interna del compose, y el
  puerto 8080 del backend se publica solo en `127.0.0.1`.

**SEC-D05 · Datos personales minimizados (DNI) · CUMPLE (DEF-37, cerrado el 26/11)**

- En `fbd31dc`, el mensaje `PAGO_CONFIRMADO` llevaba el DNI completo de cada titular (el contrato de mensajería de ese
  commit lo dejaba abierto: "Hoy viaja completo"), y la auditoría de Notificaciones guardaba el cuerpo tal como llegó
  (`ServicioDeAuditoria`). Ni el aviso al comprador ni Facturación usan el DNI, y ninguna operación devuelve ese
  cuerpo (`ServicioDeNotificacionesTest#elAvisoNoLlevaElDniDeNingunTitular`).
- Corrección (24/11, DEF-37): el titular de `PAGO_CONFIRMADO` y de `REVENTA_CONFIRMADA` viaja solo con su nombre
  (`PagoConfirmadoMensaje.Titular`) y el DNI queda en Ventas, en la entrada que se presenta en la puerta. La auditoría
  guarda el cuerpo sin el `dni` de cada titular (`ServicioDeAuditoria.sinElDniDeLosTitulares`), y la migración
  `V3__sacar_el_dni_de_la_auditoria.sql` de Notificaciones se lo saca a las filas anteriores. Un mensaje publicado
  antes, con el DNI, se procesa igual. El contrato es la sección "Datos personales" de
  `docs/api/mensajeria-pago-confirmado.md`.
- Pruebas de la corrección: `PagoConfirmadoMensajeTest#elMensajeNoLlevaElDniDeNingunTitular`,
  `ServicioDeVentasTest#elMensajeQueSalePorLaColaYElTopicoNoLlevaElDniDeNingunTitular`,
  `ServicioDeAuditoriaTest#unPagoConfirmadoPublicadoConElDniSeGuardaSinElDniDeNingunTitular` y la V3 en
  `MigracionesEnH2Test#laV3LeSacaElDniALasFilasQueYaEstaban` y en `MigracionesEnPostgresIT` (PostgreSQL 17 en un
  contenedor).
- Sobre `9caa865`: `PagoConfirmadoMensajeTest`, `ServicioDeVentasTest`, `ServicioDeAuditoriaTest`,
  `MigracionesEnH2Test` y `MigracionesEnPostgresIT` de Notificaciones, en el job Backend del run 45889485001, en
  verde. Contra el compose, el 26/11, después de una compra: 0 de las 185 filas de `notificaciones.registro_auditoria`
  tienen `"dni"` en el cuerpo (`defectos-sobre-9caa865.txt`, punto 13). Con eso se cerró DEF-37.

**SEC-D06 · Las respuestas no traen datos de otras personas ni secretos · CUMPLE**

- `seguridad.mjs` E, por nginx y directo al backend: "el catálogo y el detalle (públicos) no traen datos de personas,
  ni el id, el email o el DNI del organizador dueño de los eventos: 200/200, 9 eventos; campos personales: ninguno; del
  organizador: nada"; el login y el perfil traen solo los datos de la propia cuenta, sin contraseña ni hash; los errores
  no repiten el email, la contraseña ni el token que se mandaron.
- Un recurso de otra cuenta responde 404 (SEC-W01).

### 3.4 Escaneos y análisis

**SEC-E01 · ZAP baseline y activo autenticado: 0 High, Medium justificadas · CUMPLE**

- Run 45889484996, sobre `9caa865`: "High: 0. Medium: 0 (0 justificadas). Low: 2 (0 sin justificar). Informativas:
  9. Instancias descontadas como falsos positivos verificados: 1." La descontada es la de la regla 40018 en la
  pregunta al asistente, que figura en `.zap/reglas.tsv` como falso positivo verificado (sección 4.1). y "Cumple el criterio: ninguna alerta High y todas
  las Medium justificadas." Detalle de cada alerta en la sección 4.1.
- Sobre `fbd31dc` (run 44867187257) el resumen fue "High: 0. Medium: 2 (2 justificadas). Low: 5 (5 sin justificar).
  Informativas: 9. Instancias descontadas como falsos positivos verificados: 4.". Las cuatro instancias High de
  inyección SQL se comprobaron una por una como falsos positivos; la de los tres campos del registro salía de DEF-62
  (Menor: el orden de los campos de `errores` cambiaba entre pedidos iguales). DEF-62 se cerró el 26/11:
  `ManejadoresDeErrores.Validacion` recorre las violaciones ordenadas por campo
  (`ErroresDeValidacionTest#losCamposSalenEnOrdenAlfabeticoAunqueLasViolacionesLleguenEnOtroOrden`, en verde en el run
  45889485001), y en el ZAP de `9caa865` no aparecen las tres instancias del registro.
- Hallazgos: DEF-69 (las dos Medium de la CSP, SEC-W12) y DEF-74 (las Low, sección 4.1), los dos cerrados el 26/11: en
  el ZAP de `9caa865` no hay Medium y las dos Low que quedan están justificadas.

**SEC-E02 · Dependencias e imágenes sin CVE críticas ni altas sin mitigación · CUMPLE**

- Run 45889484934, sobre `9caa865` (Trivy 0.76.2, severidades CRITICAL y HIGH, tengan arreglo o no):
  - `trivy fs`: los 14 `pom.xml` y `frontend/package-lock.json`, 0 vulnerabilidades.
  - Imagen backend (`palco-backend`, Ubuntu 26.04, WildFly con sus módulos y los 10 WAR: los 9 servicios y el ARCA
    simulado): 0 en el sistema y 0 sin justificar en los jar; 6 HIGH suprimidas por `.trivyignore`, ninguna CRITICAL.
  - Imagen web (`palco-web`, Alpine 3.24.4 con nginx 1.30): 0 (DEF-78, cerrado el 26/11).
- Las 6 aceptadas (DEF-68), con su mitigación y su vencimiento, en la sección 4.2.
- Sobre `fbd31dc` (run 44867187171), el mismo resultado en el repo y en las imágenes backend y web.

**SEC-E02b · La imagen de la base también escaneada · CUMPLE (DEF-73, cerrado el 26/11)**

- En `fbd31dc`, `seguridad.yml` escaneaba las dos imágenes que arma el compose (backend y web). La de la base,
  `postgres:17-alpine`, se usa tal como se publica y no se escaneaba.
- Corrección (DEF-73): la matriz del job de imágenes es `[backend, web, db]`. Para `db`, el job baja la imagen que
  nombra el compose (`docker compose pull`) en lugar de armarla, y Trivy la escanea con el mismo criterio. Las CVE que
  trae la última `postgres:17-alpine` quedan justificadas en `.trivyignore`, con vencimiento el 31/12/2026: la de
  libxml2 (el parser de XML, al que Palco no llega: ninguna columna `xml` ni la extensión `xml2`, y la base no publica
  su puerto) y las de la biblioteca estándar de Go en `gosu`, que corre una sola vez al arrancar el contenedor y no
  abre conexiones.
- Run 45889484934, sobre `9caa865`: el job "Imagen: db (trivy image)" escaneó `postgres:17-alpine` y terminó en verde
  ("Total: 0 (HIGH: 0, CRITICAL: 0)" en la imagen y en `gosu`), con 1 HIGH de libxml2 y 26 de la biblioteca estándar
  de Go en `gosu` suprimidas por `.trivyignore`. Con ese job se cerró DEF-73.

**SEC-E03 · Sin secretos en el historial · CUMPLE**

- Job "Secretos (gitleaks)" del run 45889485001, sobre `9caa865`: gitleaks 8.28.0 con
  `gitleaks git --log-opts="--all" --redact -f sarif -r gitleaks.sarif --exit-code 1 .`, sobre un clon con
  `fetch-depth: 0` (todas las ramas y tags): "304 commits scanned", "scanned ~9906106 bytes (9.91 MB) in 934ms", "no
  leaks found". `gitleaks.sarif`: 0 resultados con las 217 reglas por defecto. Sobre `fbd31dc` (run 44867187162):
  176 commits, 0 hallazgos. El repo no tiene
  configuración propia de gitleaks ni excepciones (`gitleaks:allow`).
- Corre en cada push y cada pull request y falla con un hallazgo. Las credenciales de prueba y los valores por defecto
  que sí están escritos en el repo se justifican en la sección 8.

**SEC-E04 · SonarCloud: Quality Gate aprobado y sin security hotspots abiertos en el código nuevo · CUMPLE**

- Check "SonarCloud Code Analysis" sobre `9caa865` (26/11, 20:19 UTC; el job SonarCloud del run 45889485001 espera el
  resultado: "QUALITY GATE STATUS: PASSED"): "Quality Gate passed"; "0 Security Hotspots"; "92.7% Coverage on New
  Code"; "0.0% Duplication on New Code"; "125 New issues" abiertas y "0 Accepted issues".
- Sobre `fbd31dc` (21/11) el check tenía "1 Accepted issue": `docker:S6471`, el proceso principal de nginx arrancaba
  con root en la imagen web (`frontend/Dockerfile`), DEF-71. Corrección (25/11): la imagen termina con `USER 101`, así
  que nginx entero corre sin root (desde el 26/11 parte de la oficial `nginx:1.30-alpine` con los ajustes de
  `nginx-unprivileged`, DEF-78); sigue escuchando en 80 y 443 dentro del contenedor (Docker lo permite sin root desde
  la versión 20.10), y `nginx.conf` y el compose no cambian. SonarCloud cerró `docker:S6471` como corregida el 26/11, y
  contra el compose `id` en el contenedor web da `uid=101(nginx)`, también para los workers
  (`defectos-sobre-9caa865.txt`, punto 13). DEF-71 se cerró el 26/11.
- Después de `fbd31dc`, el Quality Gate quedó en rojo en `ffded8f` y `5d3c784` por un bug Critical de confiabilidad
  (`java:S8696` en `AsistenteIA`, DEF-76, Menor). Se corrigió el 25/11 (`1bab37a`); SonarCloud cerró el bug como
  corregido ese día, y el Quality Gate de `9caa865` dio "passed". DEF-76 se cerró el 26/11.

**SEC-E04b · SonarCloud sobre todo el código: vulnerabilidades, rating de seguridad y hotspots revisados · CUMPLE**

- `docs/testing/evidencia/final/sonar.txt` (API pública de SonarCloud, consultada el 26/11 a las 20:26 UTC; el último
  análisis del proyecto `santttii_deapps2`, rama `master`, es el de `9caa865`) y la captura
  `quality-gate-9caa865.png` ("Overall Code"): 0 vulnerabilidades abiertas y 0 aceptadas, Security Rating A, 0
  security hotspots y 100 % revisados. Sobre `fbd31dc` la única vulnerabilidad era la aceptada `docker:S6471` (Minor,
  `frontend/Dockerfile`): DEF-71, cerrado el 26/11 (SEC-E04).
- Fuera de este criterio, en el eje B: 0 bugs y confiabilidad A en todo el código. Sobre `fbd31dc` era C, por el bug
  MAJOR `java:S5850` de `common/Textos.java`, línea 63 (DEF-65, cerrado el 26/11). SonarCloud marca con `java:S8786`
  (expresiones con retroceso superlineal), como code smells, la línea 77 de `usuarios/api/Dtos.java` (el email del
  registro), la 45 de `asistente/negocio/Guardrails.java` y la 63 de `common/Textos.java`; las de nombre y apellido de
  `Dtos.java` (líneas 44 y 48 en `fbd31dc`, DEF-64, SEC-W15) están cerradas.

### 3.5 Asistente

**SEC-AS01 · El asistente no ejecuta instrucciones ajenas, no revela su prompt ni datos de otros y no se cae con el proveedor · CUMPLE**

- `scripts/pruebas/asistente.mjs` (job Integración general, 11 de 11): "una inyección de prompt se bloquea", "un pedido
  de datos de terceros se bloquea", "el asistente no hace acciones: solo lee", "una tarjeta no se repite (se enmascara
  antes del modelo)", fallback a los 5 s con el modelo lento, fallback con el asistente apagado y 401 sin token.
- Newman (carpeta 9): inyección de prompt con origen `BLOQUEADA` y motivo `inyeccion`; sin token, 401.
- Tests (`mvn verify`): `RiesgosDelAsistenteTest` (60): el prompt del sistema no se muestra (7 casos), una orden
  escondida en un texto ajeno no se ejecuta (14), el dato de otro usuario no se entrega (10), una respuesta del modelo
  con datos de otro usuario no se muestra (4), un pedido fuera de dominio o peligroso tiene una respuesta acotada (12),
  con el proveedor caído o lento responde el fallback (también con 20 preguntas a la vez) y 40 personas a la vez
  reciben cada una su respuesta. `GuardrailsTest`: 48
  inyecciones detectadas, 9 pedidos de datos de terceros y 31 preguntas legítimas que no se bloquean.
  `EvaluacionDelAsistenteTest#elSetDeEvaluacionCumpleElCriterioParaHabilitarElAsistente`.
- Inyección indirecta por el catálogo, después de `fbd31dc`: desde el 24/11 el asistente le pasa al modelo los
  eventos a la venta del catálogo público (`CatalogoDeEventos`, `GET /api/eventos`: hasta 15, con título, categoría,
  lugar, fecha y precio, en una línea de hasta 80 caracteres), marcados como datos y no como instrucciones. El título y
  el lugar los escribe el organizador, así que es un texto ajeno: un evento cuyo título o lugar parece una orden
  (`Guardrails.conInyeccion`) o trae algo personal no llega al modelo. Lo cubren
  `CatalogoDeEventosTest#unEventoConUnaInyeccionEnElTituloOEnElLugarNoLlegaAlModelo`,
  `#unEventoConAlgoQueParecePersonalEnElTituloNoLlegaAlModelo`,
  `AsistenteIATest#unEventoConUnaOrdenEnElTituloNoLeLlegaAlModelo` y
  `RiesgosDelAsistenteTest#elGatewayLeeSoloElModeloYElCatalogoPublicoDeEventos`, que falla si el gateway suma otra
  dependencia o el catálogo le pasa al modelo otro campo. Si Eventos no responde, el asistente responde igual sin la
  lista (`AsistenteIATest#siEventosNoRespondeElAsistenteRespondeIgualSinLaLista`). Los cuatro pasan en el job Backend
  del run 45889485001 (`CatalogoDeEventosTest`, 8 de 8; `AsistenteIATest`, 18 de 18; `RiesgosDelAsistenteTest`, 60 de
  60).
- DEF-63 (Crítica, cerrado el 26/11): una pregunta de decenas de miles de caracteres dejaba colgado el pedido.
  `GuardrailsTest#unTextoEnormeSinArrobaSeEnmascaraEnMenosDeUnSegundo` pasa en el run 45889485001, y la corrida de
  Schemathesis sobre `9caa865` (run 45889485045), como la de `fbd31dc`, terminó sin cortes por tiempo.

## 4. Resultados de ZAP, Trivy, gitleaks y SonarCloud

### 4.1 OWASP ZAP (run 45889484996, sobre `9caa865`)

`scripts/qa/zap.sh`, con la imagen `ghcr.io/zaproxy/zaproxy:2.17.0` fijada por su hash, contra el compose del propio
workflow, por nginx (`https://web` dentro de la red del compose):

- **Baseline** (pasivo, spider de hasta 2 minutos sobre la app). ZAP: "FAIL-NEW: 0 … WARN-NEW: 4 … INFO: 1 … PASS:
  63".
- **API autenticada** (pasivo y activo): ZAP importa `docs/api/openapi.yaml` (52 operaciones), manda cada una con el
  token de `titular@palco.test` (rol `user`) y la ataca con la política API-Minimal, con un tope de 7 minutos. ZAP:
  "FAIL-NEW: 0 … WARN-NEW: 2 … PASS: 118". Las operaciones de organizador, puerta y servicio responden 403 a ese
  token, así que el escaneo activo no recorre su lógica; esas operaciones las cubren, con el token de cada rol, las
  corridas de Schemathesis (sección 1).
- `scripts/qa/zap-resumen.mjs` aplica el criterio con `.zap/reglas.tsv`: falla con cualquier High y con cada Medium sin
  justificar. Una instancia High solo se descuenta si figura en ese archivo como falso positivo verificado, con su regla,
  método, URL y parámetro exactos.

| Escaneo | Riesgo | Alerta (regla) | Instancias | Tratamiento |
|---|---|---|---|---|
| API | High | SQL Injection (40018), en `POST /api/v1/asistente/preguntas`, parámetro `pregunta` | 1 | Falso positivo verificado en `.zap/reglas.tsv` (abajo) |
| Baseline | Low | Cross-Origin-Embedder-Policy Header Missing or Invalid (90004) | 3 | Justificada en `.zap/reglas.tsv` (DEF-74, abajo) |
| API | Low | Unexpected Content-Type was returned (100001) | 14 | Justificada en `.zap/reglas.tsv` (DEF-74, abajo) |
| API | Info | Information Disclosure - Sensitive Information in URL (10024) | 1 | La URL de la puerta lleva el email; el log de nginx escribe `<email>` (DEF-72, cerrado el 26/11) |
| Baseline | Info | Information Disclosure - Suspicious Comments (10027) | 1 | Un comentario de una librería en el JavaScript empaquetado: no tiene datos |
| Ambos | Info | Modern Web Application, Re-examine Cache-control Directives, Storable and Cacheable Content, Non-Storable Content, Authentication Request Identified, A Client Error response code was returned (252) | | Informativas: describen la app y las respuestas de error esperadas |

Resumen: "High: 0. Medium: 0 (0 justificadas). Low: 2 (0 sin justificar). Informativas: 9. Instancias descontadas
como falsos positivos verificados: 1." De los falsos positivos de `.zap/reglas.tsv` apareció el del asistente, que no
usa base de datos (lo que cambia entre respuestas es el campo `milisegundos`); los del registro no aparecieron.

**Sobre `fbd31dc` (run 44867187257), donde se encontraron DEF-62, DEF-69 y DEF-74.** Baseline: "FAIL-NEW: 0 …
WARN-NEW: 5 … PASS: 62"; API: "FAIL-NEW: 0 … WARN-NEW: 3 … PASS: 118".

| Escaneo | Riesgo | Alerta (regla) | Instancias | Tratamiento |
|---|---|---|---|---|
| API | High | SQL Injection (40018) | 4 | Falsos positivos verificados (abajo) |
| Baseline | Medium | CSP: style-src unsafe-inline (10055) | 3 | Justificada; DEF-69, cerrado el 26/11 (abajo) |
| Baseline | Medium | CSP: Wildcard Directive (10055), por `img-src https:` | 3 | Justificada; DEF-69, cerrado el 26/11 (abajo) |
| Baseline | Low | Cross-Origin-Embedder-Policy Header Missing or Invalid (90004) | 2 | DEF-74: justificada en `.zap/reglas.tsv` (renglón 56) |
| Baseline | Low | Cross-Origin-Opener-Policy Header Missing or Invalid (90004) | 2 | DEF-74: la cabecera, en `nginx.conf`; no aparece sobre `9caa865` |
| Baseline y API | Low | Cross-Origin-Resource-Policy Header Missing or Invalid (90004) | 5 y 5 | DEF-74: la cabecera, en `nginx.conf`; no aparece sobre `9caa865` |
| API | Low | Unexpected Content-Type was returned (100001) | 14 | DEF-74: justificada en `.zap/reglas.tsv` (renglón 57) |
| API | Info | Information Disclosure - Sensitive Information in URL (10024) | 1 | DEF-72: el log de nginx escribe `<email>`; la URL no cambia |
| Baseline | Info | Information Disclosure - Suspicious Comments (10027) | 1 | Un comentario de una librería en el JavaScript empaquetado ("in response to some user interaction or stat…"): no tiene datos |
| Ambos | Info | Modern Web Application, Re-examine Cache-control Directives, Storable and Cacheable Content, Non-Storable Content, Authentication Request Identified, A Client Error response code was returned (252) | | Informativas: describen la app y las respuestas de error esperadas |

**Falsos positivos verificados (regla 40018, comparación booleana).** ZAP manda el valor original, el valor con
`AND 1=1` y con `AND 1=2`, y avisa si las dos primeras respuestas son iguales y la tercera distinta. En los dos
endpoints, lo que cambia entre respuestas no depende de ninguna consulta (cómo se comprobó, en el encabezado de
`.zap/reglas.tsv`; las cuatro instancias, en los renglones 58 a 61):

- `POST /api/usuarios/registro`, parámetros `apellido`, `dni` y `telefono`: con los datos que ZAP arma desde el OpenAPI
  el pedido ya respondía 400, la validación del DTO lo corta antes de la base, y en `fbd31dc` el 400 listaba los campos
  de `errores` en un orden que cambiaba entre pedidos iguales (DEF-62, corregido el 24/11). Usuarios consulta solo con
  JPQL y parámetros con nombre.
- `POST /api/v1/asistente/preguntas`, parámetro `pregunta`: el asistente no usa base de datos, y la respuesta trae el
  campo `milisegundos`, que cambia en cada pedido.

**Medium justificadas sobre `fbd31dc`** (`.zap/reglas.tsv`, renglones 54 y 55), con la CSP de ese commit:

- `style-src 'unsafe-inline'`: framer-motion anima con el atributo `style` y React escribe estilos en línea. Lo que
  ejecuta código sigue cerrado: `script-src 'self'`, sin `'unsafe-inline'` ni `'unsafe-eval'`, y `object-src 'none'`; un
  estilo inyectado no corre JavaScript.
- `img-src https:`: las fotos de los eventos se podían guardar en el almacenamiento de Supabase, cuyo dominio depende
  del proyecto. Una imagen no ejecuta código y `connect-src` queda en `'self'`.

Corrección de DEF-69 (25/11): la CSP queda con `style-src 'self'` e `img-src 'self' data: blob: https://picsum.photos`.
Los estilos de framer-motion y los `style` de React se aplican por JavaScript (CSSOM), y la CSP no los frena; las fotos
que se suben se sirven desde Palco y las de los eventos de ejemplo, desde picsum.photos. Contra el compose (25/11), 0
avisos de CSP en la consola en el recorrido de SEC-W12. El ZAP de `9caa865` corre con esa CSP y no da ninguna
Medium; DEF-69 se cerró el 26/11.

**Low.** El criterio no pide justificarlas y no cortan la corrida; están en DEF-74 (cerrado el 26/11). La página no se puede enmarcar (`frame-ancestors 'none'` y `X-Frame-Options: DENY`) y la app no usa APIs que
pidan aislamiento entre orígenes. Desde la corrección, nginx manda `Cross-Origin-Opener-Policy: same-origin` y
`Cross-Origin-Resource-Policy: same-origin` en sus dos bloques de cabeceras; `Cross-Origin-Embedder-Policy` no va,
porque con `require-corp` el navegador bloquearía las fotos de los eventos guardadas en otro origen, y queda
justificada en `.zap/reglas.tsv` (renglón 56), que no cubre las alertas de las otras dos cabeceras. Las 14 de
Content-Type son respuestas en `text/html` donde ZAP esperaba JSON (`api.json` y el log de nginx en
`compose-zap.log`): la raíz del sitio y dos rutas inventadas, que responden la página de la app (200); `/api` (301);
`/api/` y una ruta inventada bajo `/api/` (404 de WildFly); y siete `POST` a rutas de metadatos de nube
(`/latest/meta-data/`, `/computeMetadata/v1/` y otras), que nginx rechaza con 405. Ninguna devolvió datos, ninguna es
una operación de `openapi.yaml`, y quedan justificadas en `.zap/reglas.tsv` (renglón 57).

El escaneo manda el token en cada pedido: el paso "Control de logs" del workflow comprobó que el `server.log` no quedó
con ningún token. Desde la corrección de DEF-40, ese paso corre `scripts/qa/control-de-logs.sh` y busca también
contraseñas de prueba, DNI y emails `@palco.test` enteros; sobre `9caa865`: "logs/server.log está limpio (1241
renglones)".

### 4.2 Trivy (run 45889484934, sobre `9caa865`)

| Objetivo | Qué incluye | CRITICAL | HIGH sin justificar | HIGH aceptadas |
|---|---|---|---|---|
| `trivy fs` del repo | `backend/pom.xml`, `backend/common`, `backend/servidor`, `backend/servicios/pom.xml` y los 10 módulos de `backend/servicios/`; `frontend/package-lock.json` con las dependencias de desarrollo | 0 | 0 | 0 |
| Imagen `palco-backend` | Ubuntu 26.04, WildFly con sus módulos, el driver de PostgreSQL y los 10 WAR (los 9 servicios y el ARCA simulado) | 0 | 0 | 6 |
| Imagen `palco-web` | Alpine 3.24.4 con nginx 1.30 y el build del frontend | 0 | 0 | 0 |
| Imagen `postgres:17-alpine` | Alpine 3.24.4 con PostgreSQL 17 y `gosu` | 0 | 0 | 27: 1 de libxml2 y 26 de la biblioteca estándar de Go en `gosu` |

Las de la imagen de la base se justifican en SEC-E02b y en `.trivyignore`, con vencimiento el 31/12/2026. Las 6
aceptadas de la imagen backend vienen dentro de los módulos de WildFly 41.0.1, no de una dependencia de Palco, y vencen el 31/12/2026
en `.trivyignore` (después el job vuelve a fallar). Las arreglan jackson 2.21.6 y 2.21.7 y guava 33.7.2, que todavía
no trae ningún WildFly 41; subir a WildFly 42 a una semana de la entrega es un cambio de versión mayor del servidor.
Es DEF-68 (Menor, Aceptado).

| Librería | CVE | Por qué no se alcanza en Palco (comprobado sobre la imagen, `.trivyignore`) |
|---|---|---|
| jackson-core 2.21.5 | CVE-2026-89407, CVE-2026-89425 | Son denegaciones de servicio al deserializar un JSON del atacante con Jackson. Ningún código de Palco importa `com.fasterxml.jackson`; todo cuerpo JSON lo lee `JsonProvider` con JSON-B (Yasson), y RESTEasy prefiere el proveedor de la aplicación: un JSON mal formado en el login da el 400 de `JsonProvider`. Los recursos aceptan solo `application/json` (otro tipo `+json` da 415), no hay `@PATCH`, y los clientes entre servicios leen la respuesta como `String` y la convierten con JSON-B. CVE-2026-89425 además exige `JsonFactory.createParser(DataInput)` |
| jackson-databind 2.21.5 | CVE-2026-91776, CVE-2026-91777, CVE-2026-68497 | Lo mismo; además CVE-2026-91776 exige `@JsonTypeInfo` con `defaultImpl` y CVE-2026-91777, `@JsonIdentityInfo`, que Palco no usa |
| guava 33.0.0-jre | CVE-2026-102554 | Agotamiento de memoria al deserializar con `ObjectInputStream` ciertas colecciones armadas por el atacante. Ningún código de Palco importa `com.google.common`; la mensajería es solo `TextMessage` con JSON (no hay `ObjectMessage` ni `ObjectInputStream`), el broker va por `in-vm` y las entradas remotas de WildFly piden un usuario del ApplicationRealm, que la imagen no tiene |

La imagen web de `9caa865` parte de la oficial `nginx:1.30-alpine` (nginx 1.30.8), con los ajustes para correr sin
root. Sobre `fbd31dc` partía de la misma imagen, corriendo con root; el 25/11 pasó a
`nginxinc/nginx-unprivileged:1.30-alpine` (DEF-71), que traía nginx 1.30.6 con cuatro CVE críticas y dos altas y dejó
en rojo el job de la imagen web (DEF-78); desde el 26/11 vuelve a la oficial, y Trivy da 0 críticas y 0 altas sin
excepciones nuevas (DEF-71 y DEF-78, cerrados el 26/11). En todos los casos actualiza los paquetes de Alpine al
armarse (`apk upgrade`; DEF-51, cerrado el 21/11). Sobre `fbd31dc` la imagen de la base no se escaneaba; desde la
corrección de DEF-73, el job de imágenes escanea también `postgres:17-alpine` (SEC-E02b).

### 4.3 gitleaks (job "Secretos" del run 45889485001, sobre `9caa865`)

| Versión | Alcance | Commits | Hallazgos |
|---|---|---|---|
| 8.28.0 (binario de la release oficial, bajado con `curl --proto '=https'`) | Todo el historial de todas las ramas y tags (`--log-opts="--all"`, clon con `fetch-depth: 0`), reglas por defecto (217) | 304 | 0 ("no leaks found"; `gitleaks.sarif` sin resultados) |

### 4.4 SonarCloud (check del commit `9caa865`)

| Medida (código nuevo) | Valor |
|---|---|
| Quality Gate | Passed |
| Security Hotspots | 0 |
| Cobertura | 92,7 % |
| Duplicación | 0,0 % |
| Issues nuevas | 125 abiertas, todas code smells MAJOR; 0 aceptadas |

| Medida (todo el código, `sonar.txt` y `quality-gate-9caa865.png`, 26/11) | Valor |
|---|---|
| Vulnerabilidades | 0 abiertas y 0 aceptadas (la aceptada de `fbd31dc`, `docker:S6471`, se cerró como corregida: DEF-71) |
| Security Rating | A |
| Security hotspots | 0; 100 % revisados |
| Bugs y Reliability Rating | 0 bugs; confiabilidad A (sobre `fbd31dc`, C por `java:S5850` en `Textos.java:63`: DEF-65, cerrado el 26/11) |
| Hallazgos BLOCKER o CRITICAL | 0 (los `java:S1845` de DEF-67 y DEF-79, cerrados el 26/11) |
| Duplicación | 0,7 % |
| Cobertura | 81,3 % |
| `java:S8786` (expresión con retroceso superlineal) | 3: `Dtos.java` 77, `Guardrails.java` 45 y `Textos.java` 63; las de nombre y apellido (DEF-64) están cerradas |

## 5. Hallazgos y tratamiento

Severidad según la sección 9 de los Criterios de Testing. Ningún hallazgo de seguridad abierto es Bloqueante ni
Crítico. Los dos Críticos de seguridad se cerraron el 26/11 con la corrida sobre `9caa865`: DEF-63 (la pregunta
enorme al asistente) y DEF-64 (SEC-W15), confirmado contra el sistema el 24/11 y corregido ese mismo día. Los únicos
abiertos son los cinco riesgos aceptados del registro: DEF-39, DEF-41, DEF-58, DEF-68 y DEF-75.

Estado de cada defecto según el registro (`docs/testing/registro-de-defectos.md`), donde están también sus pasos, su
evidencia, su corrección con los archivos y las pruebas que la verifican.

| Defecto | Criterio | Hallazgo | Severidad | Estado | Tratamiento |
|---|---|---|---|---|---|
| DEF-36 | SEC-D03 | Emails enteros con nombres y códigos de un solo uso en el log del sistema levantado con el compose | Mayor | Cerrado el 26/11 | El modo por defecto del código ya no escribe datos personales (tests en SEC-D03) y, desde el 24/11, el compose tampoco prende el modo completo. Se cerró con el log sin esos datos contra el compose (`correo-en-el-log.txt`, punto 6) y el control de logs de Contrato y ZAP sobre `9caa865` |
| DEF-37 | SEC-D05 | DNI completo en `PAGO_CONFIRMADO` y en el cuerpo guardado por la auditoría | Mayor | Cerrado el 26/11 | El titular viaja solo con su nombre, la auditoría guarda el cuerpo sin el DNI y la migración V3 de Notificaciones se lo saca a las filas anteriores (contrato: "Datos personales" de `mensajeria-pago-confirmado.md`; tests en SEC-D05). Se cerró con los tests en el run 45889485001 y 0 filas de `registro_auditoria` con DNI contra el compose |
| DEF-38 | SEC-W05c | Token de 8 horas sin revocación | Mayor | Cerrado el 26/11 | Desde el 25/11 la sesión dura 15 minutos (`ServicioDeUsuarios.DURACION_SESION`): el criterio se cumple con la vigencia corta, porque el token es un JWT que cada servicio valida sin consultar a Usuarios y no se revoca; al vencer, el frontend pide ingresar de nuevo. Newman SEC-W05c en el run 45889485001, `exp - iat` = 900 s |
| DEF-39 | SEC-I02 | Broker sin usuarios ni permisos por destino | Menor | Aceptado (16/10) | Darle a cada servicio su usuario y sus permisos por destino cambia toda la mensajería; el broker está embebido y los servicios entran por `in-vm` (ADR-005). Con más de un servidor, un usuario por servicio y `security-setting` por destino |
| DEF-40 | SEC-D01 | El control de logs del CI no buscaba DNI | Menor | Cerrado el 26/11 | `scripts/qa/control-de-logs.sh`, en los pasos "Control de logs" de `ci.yml`, `contrato.yml`, `pruebas-seguridad.yml` y `zap.yml`: tokens, contraseñas de prueba y DNI y, en `contrato.yml` y `zap.yml`, emails `@palco.test` enteros. Esos pasos, en verde sobre `9caa865` |
| DEF-41 | SEC-W04 | El límite de ingresos cuenta por email | Menor | Aceptado (decisión de diseño, CA-02.3) | CA-02.3 pide el 429 por email aunque la contraseña sea la correcta, y contar por IP pide una dirección del cliente que hoy no se tiene de forma segura. Mitigan: el bloqueo dura como mucho 15 minutos con `Retry-After`, y "Olvidé mi contraseña" borra los fallidos (registro, DEF-41) |
| DEF-42 | SEC-W13 | El compose aceptaba `http://localhost:5173` en CORS | Menor | Cerrado el 26/11 | El compose deja por defecto solo la URL pública; el origen de Vite se define a mano en `PALCO_CORS_ORIGENES` (SEC-W13). Contra el compose, ese origen no recibe cabeceras CORS (`defectos-sobre-9caa865.txt`, punto 13) |
| DEF-43 | SEC-W02 | Ningún test unitario fallaba si una operación de 7 recursos perdía su anotación de rol | Menor | Cerrado el 26/11 | `SeguridadDeclarativaTest` en cada servicio con recursos JAX-RS exige `@RolesAllowed`, `@PermitAll` o `@DenyAll` en cada operación (SEC-W02); 9 de 9 en el run 45889485001 |
| DEF-44 | SEC-D02 | Los hashes de 310.000 iteraciones no pasaban a 600.000 al iniciar sesión | Menor | Cerrado el 26/11 | `ServicioDeUsuarios.iniciarSesion` guarda un hash de 600.000 iteraciones si el guardado tiene menos (`IteracionesAlIniciarSesionEnH2Test`, SEC-D02; en verde en el run 45889485001) |
| DEF-45 | SEC-I01b | El ARCA simulado no pide autenticación y el nginx del compose lo publicaba | Menor | Cerrado el 26/11 | `location /legado/` de `nginx.conf` responde 404; Facturación lo llama por la red interna y `seguridad.mjs` D, por el puerto del backend (SEC-I01b). Contra el compose, `https://localhost/legado/arca?wsdl` responde 404 (`defectos-sobre-9caa865.txt`, punto 13) |
| DEF-58 | SEC-W14 | Token en `localStorage` | Menor | Aceptado (decisión de diseño) | Lo acotan la CSP y el escapado de React (SEC-W10); corrección: cookie `HttpOnly`, `Secure` y `SameSite=Strict` con el token corto de DEF-38 |
| DEF-62 | SEC-E01 | El 400 de validación listaba los campos en un orden que cambia (causa del falso positivo de ZAP en el registro) | Menor | Cerrado el 26/11 | `ManejadoresDeErrores.Validacion` ordena las violaciones por campo y por mensaje (`ErroresDeValidacionTest`, en el run 45889485001); el ZAP de `9caa865` no tiene alertas de inyección |
| DEF-64 | SEC-W15 | El registro tardaba con el cubo del largo de un nombre o un apellido lleno de espacios: un pedido sin sesión de 1,6 KB ocupaba un hilo del servidor 7 segundos | Crítica | Cerrado el 26/11 (confirmado contra el sistema y corregido el 24/11) | Expresión con cuantificadores posesivos y `@Size(max = 80)`; `NombreYApellidoTest`, 4 de 4 en el run 45889485001; contra el compose, el pedido de 1500 espacios responde el 400 en 0,21 s (`registro-nombre-largo.txt`, punto 7) |
| DEF-68 | SEC-E02 | 6 HIGH de Jackson y Guava dentro de WildFly 41.0.1 | Menor | Aceptado (vence el 31/12/2026) | Mitigación comprobada en la sección 4.2; corrección: la versión de WildFly que traiga las librerías arregladas |
| DEF-69 | SEC-W12, SEC-E01 | CSP con `style-src 'unsafe-inline'` e `img-src https:` (2 Medium de ZAP) | Menor | Cerrado el 26/11 | Desde el 25/11, `style-src 'self'` e `img-src 'self' data: blob: https://picsum.photos` en los dos bloques de cabeceras de `nginx.conf`. Contra el compose (25/11): 0 avisos de CSP en la consola, la E2E 39 de 39 y `seguridad.mjs` 64 de 64 (SEC-W12). El ZAP de `9caa865` no tiene Medium |
| DEF-70 | SEC-W12 | La CSP habilitaba los orígenes de Google Fonts, que la app ya no usa | Menor | Cerrado el 26/11 | Desde el 24/11, los dos bloques de cabeceras de `nginx.conf` quedan sin `https://fonts.googleapis.com` ni `https://fonts.gstatic.com`; lo confirman sobre `9caa865` el bloque E de `seguridad.mjs` y `cabeceras.txt` |
| DEF-71 | SEC-E04 | El proceso principal de nginx corría con root en la imagen web | Menor | Cerrado el 26/11 | Desde el 25/11, la imagen web termina con `USER 101` (desde el 26/11, sobre la oficial `nginx:1.30-alpine` con los ajustes de `nginx-unprivileged`, DEF-78): nginx entero corre sin root (contra el compose, `id` en el contenedor web da `uid=101(nginx)`). Sigue en 80 y 443 dentro del contenedor (Docker lo permite sin root desde la 20.10); `nginx.conf` y el compose no cambian. SonarCloud cerró `docker:S6471` y el Trivy de la imagen web sobre `9caa865` da 0 |
| DEF-72 | SEC-D03 | El email de la cuenta de puerta quedaba en el log de accesos de nginx (va en la ruta o en la consulta) | Menor | Cerrado el 26/11 | El formato de log `palco` de `nginx.conf` escribe `<email>` en su lugar, y no escribe la ruta si le queda un `@` o un `%40` (SEC-D03). Contra el compose, 0 pedidos con `@` o `%40` en la ruta en el log del contenedor web (`defectos-sobre-9caa865.txt`, punto 13) |
| DEF-73 | SEC-E02b | Trivy no escaneaba `postgres:17-alpine` | Menor | Cerrado el 26/11 | La matriz del job de imágenes de `seguridad.yml` suma `db`, con sus excepciones justificadas en `.trivyignore` hasta el 31/12/2026 (SEC-E02b); el job "Imagen: db (trivy image)" del run 45889484934 (`9caa865`) terminó en verde |
| DEF-74 | SEC-W12, SEC-E01 | Alertas Low de ZAP: sin `Cross-Origin-Resource-Policy`, `Cross-Origin-Opener-Policy` ni `Cross-Origin-Embedder-Policy`; 14 respuestas en `text/html` donde ZAP esperaba JSON (sección 4.1) | Cosmética | Cerrado el 26/11 | nginx manda `Cross-Origin-Resource-Policy` y `Cross-Origin-Opener-Policy` en `same-origin`; COEP y las 14 de Content-Type quedan justificadas en `.zap/reglas.tsv` (renglones 56 y 57). El ZAP de `9caa865`: 2 Low, las dos justificadas |
| DEF-75 | SEC-W03 | El registro dice si un email o un DNI ya tienen cuenta, sin límite de intentos | Menor | Aceptado (decisión de diseño, CA-01.2) | El login y "olvidé mi contraseña" no lo revelan; si se cambia, cambia CA-01.2 |
| DEF-78 | SEC-E02 | La imagen web sobre `nginxinc/nginx-unprivileged:1.30-alpine` traía nginx 1.30.6, con cuatro CVE críticas y dos altas | Menor | Cerrado el 26/11 | Desde el 26/11, la imagen parte de la oficial `nginx:1.30-alpine` (nginx 1.30.8) con los ajustes para correr sin root; Trivy 0.76.2 sobre la imagen da 0 críticas y 0 altas, sin las seis excepciones temporales de `.trivyignore`; el Trivy de la imagen web sobre `9caa865` (run 45889484934) da 0 |
| DEF-81 | SEC-W06 | `POST /api/eventos` con el carácter nulo en un texto respondía 500 (PostgreSQL no guarda ese carácter); lo encontró el Contrato de `dfd89c4` | Menor | Cerrado el 26/11 | `358ce5e`: los textos del evento rechazan con 400 los caracteres de control (`MensajesDeValidacionTest`); `9caa865`: una factura con un `ventaId` con caracteres de control es 404 sin consultar la base (`ServicioDeFacturacionTest`). Los tres tests pasan en el run 45889485001, el Contrato de `9caa865` (run 45889485045) no tiene fallas y, contra el compose, el título y la descripción con el carácter nulo dan 400 y la factura, 404 (`defectos-sobre-9caa865.txt`, punto 13) |

Defectos de seguridad ya corregidos: DEF-12 (el 401 por token inválido salía sin CORS ni Problem Details), DEF-25
(guardrail del asistente), DEF-26 (cuentas de prueba con contraseña pública en cualquier entorno) y DEF-51 (imagen web
sobre `nginx:1.27-alpine`), cerrados; DEF-59, DEF-60, DEF-61 y DEF-77 (500 ante el carácter nulo; el último, en Pagos,
sección 3.1, SEC-W06), DEF-80 (504 ante el carácter nulo en el resumen de ventas, SEC-W06), DEF-81 (500 ante el
carácter nulo en un evento, en la tabla de arriba) y DEF-63 (pregunta enorme al asistente), cerrados el 26/11, como
los de la tabla de arriba con ese estado. Su causa raíz y su corrección están en el registro.

## 6. Pruebas en vivo (sección 7 de los Criterios)

**Preparación, una vez.** Desde la raíz del repo, en Bash (en Windows, Git Bash), como en el plan de pruebas (sección
5, "Cómo repetirlas contra el compose"). La clave JWT de la corrida se genera antes del `up` para que `seguridad.mjs`
firme tokens como el servidor; `PALCO_CORREO_LOG_COMPLETO=true` lo necesitan los guiones que leen del log el código de
verificación de las cuentas que crean.

```bash
export PALCO_SECRETO_SERVICIOS="$(openssl rand -hex 32)"
export PALCO_JWT_CLAVE_PRIVADA="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 -w0)"
export PALCO_DIR_LOGS=./logs PALCO_LOG=logs/server.log PALCO_CORREO_LOG_COMPLETO=true
export JBOSS_CLI=scripts/qa/jboss-cli-docker.sh
mkdir -p logs && chmod 777 logs
docker compose up --build --detach --wait

API=http://localhost:8080
login() { curl -s -X POST "$API/api/usuarios/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"palco1234\"}" | sed -nE 's/.*"token" *: *"([^"]+)".*/\1/p'; }
A=$(login titular@palco.test); B=$(login otro.titular@palco.test)
```

**Todo el eje D de una vez:** `node scripts/pruebas/seguridad.mjs` (unos 8 segundos en el CI; termina en "64 de 64
comprobaciones" y "TODO BIEN") y
`(cd frontend && PALCO_E2E_URL=https://localhost PALCO_LOG="$(pwd)/../logs/server.log" npx playwright test e2e/xss.spec.ts)`.

| Prueba de la cátedra | Comando en vivo | Qué tiene que verse | Resultado de referencia (CI sobre `9caa865`) |
|---|---|---|---|
| BOLA / IDOR | `node scripts/pruebas/escenarios.mjs 5 21`. A mano, con una compra de A: `V=$(curl -s "$API/api/v1/ventas" -H "Authorization: Bearer $A" \| grep -oE '"id":"V-[^"]+"' \| head -1 \| cut -d'"' -f4)` y `curl -s -w '\nHTTP %{http_code}\n' "$API/api/v1/ventas/$V" -H "Authorization: Bearer $B"` | 404 `/problemas/no-encontrado`, sin datos de la venta | Escenarios 5 (27 de 27) y 21 (26 de 26); SEC-W01 |
| BFLA | `curl -s -o /dev/null -w '%{http_code}\n' "$API/api/eventos/mios" -H "Authorization: Bearer $A"`; sin token: `curl -s -i "$API/api/v1/ventas" \| grep -iE '^HTTP\|^www-authenticate'` | 403; sin token, 401 con `WWW-Authenticate: Bearer` | Escenarios 5 y 21; SEC-W02 |
| JWT vencido, firma alterada, `alg=none`, otro emisor | Bloque A de `seguridad.mjs`. A mano, firma alterada: `curl -s -w '\nHTTP %{http_code}\n' "$API/api/usuarios/yo" -H "Authorization: Bearer ${A%?????}AAAAA"`; `alg` `none`: `b64() { printf '%s' "$1" \| base64 -w0 \| tr '+/' '-_' \| tr -d '='; }` y `curl -s -w '\nHTTP %{http_code}\n' "$API/api/usuarios/yo" -H "Authorization: Bearer $(b64 '{"alg":"none","typ":"JWT"}').$(b64 '{"iss":"palco","sub":"user-titular","groups":["organizer"],"exp":4102444800}')."` | 401 `/problemas/token-invalido` en cada caso, sin traza | Bloque A, 11 de 11, en los cinco servicios; SEC-W05a a f |
| Mass assignment (rol, estado, precio) | Bloque B de `seguridad.mjs` | Cada pedido responde como uno común y, al leer el recurso, rol `user`, compra `EN_CHECKOUT` con los montos del servidor, publicación `ACTIVA` con el tope del servidor, dueño sin cambiar | Bloque B, 20 de 20; SEC-W07 y SEC-W08 |
| Inyección SQL y XSS | `curl -s -w '\nHTTP %{http_code}\n' "$API/api/eventos?q=%27%20OR%20%271%27%3D%271"`; `curl -s -w '\nHTTP %{http_code}\n' "$API/api/eventos?q=%3Cscript%3Ealert(1)%3C%2Fscript%3E"`; bloque C de `seguridad.mjs` y `xss.spec.ts` | 200 (nunca 500); el HTML se guarda y se ve como texto, sin `alert()` | Escenario 24; bloque C, 6 de 6; `xss.spec.ts`, 2 de 2; SEC-W09 y SEC-W10 |
| XXE en SOAP | Bloque D de `seguridad.mjs`. A mano: `curl -s -w '\nHTTP %{http_code}\n' -X POST "$API/legado/arca" -H 'Content-Type: text/xml; charset=utf-8' --data-binary '<?xml version="1.0"?><!DOCTYPE S:Envelope [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/"><S:Body>&xxe;</S:Body></S:Envelope>'` | SOAP Fault `soap:Client` ("Received event DTD"), HTTP 500, sin el contenido del archivo; el servidor sigue respondiendo | Bloque D, 6 de 6; SEC-I01b |
| Fuerza bruta en el login: 429 | `for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code} ' -X POST "$API/api/usuarios/login" -H 'Content-Type: application/json' -d '{"email":"fuerza.bruta@palco.test","password":"incorrecta1"}'; done; echo` y, para ver la cabecera, el mismo pedido con `-D - -o /dev/null \| grep -i '^retry-after'`. Un email que no existe cuenta igual y no bloquea ninguna cuenta de la demo | `401 401 401 401 401 429`, con `Retry-After` | Newman SEC-W04; escenario 24; SEC-W04 |
| ZAP baseline y activo autenticado | `bash scripts/qa/zap.sh` (poco más de 2 minutos en el CI, con la descarga de la imagen; resumen en `logs/zap/resumen.md`) | "High: 0" y "Cumple el criterio" | Run 45889484996: High 0 (1 falso positivo verificado), Medium 0, Low 2 justificadas; SEC-E01 |
| Dependencias | `gh run view 45889484934 -R santttii/deapps2` o `gh workflow run seguridad.yml -R santttii/deapps2 --ref <tag>`; local, para una imagen: `MSYS_NO_PATHCONV=1 docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v "$(pwd -W):/repo" aquasec/trivy:0.76.2 image --scanners vuln --severity CRITICAL,HIGH --ignorefile /repo/.trivyignore palco-web` (la primera vez baja la base de vulnerabilidades); lo mismo con `palco-backend` y con `postgres:17-alpine` | 0 CRITICAL y 0 HIGH sin justificar; en backend, las 6 suprimidas en su sección; en `postgres:17-alpine`, las suprimidas por `.trivyignore` (DEF-73) | Run 45889484934 (repo, backend, web y `postgres:17-alpine`); SEC-E02 y SEC-E02b |
| Secretos (G6) | `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/repo" ghcr.io/gitleaks/gitleaks:v8.28.0 git /repo --log-opts="--all" --redact`, o el job "Secretos (gitleaks)" del CI | "no leaks found" | 304 commits, 0 hallazgos; SEC-E03 |
| Cabeceras, CORS y cookies | `curl -skI https://localhost/ \| grep -iE 'content-security\|strict-transport\|x-content-type\|cross-origin\|set-cookie'`; `curl -sk -o /dev/null -D - -H 'Origin: https://sitio-ajeno.example' https://localhost/api/eventos \| grep -i '^access-control' \|\| echo "sin CORS para un origen ajeno"`; lo mismo con `Origin: http://localhost:5173`; `curl -sk -o /dev/null -D - -H 'Origin: https://localhost' https://localhost/api/eventos \| grep -iE '^access-control\|^vary'` | CSP con `style-src 'self'` e `img-src 'self' data: blob: https://picsum.photos` (DEF-69), HSTS y `nosniff`, sin `Set-Cookie`; con las correcciones de DEF-74, `Cross-Origin-Opener-Policy` y `Cross-Origin-Resource-Policy` en `same-origin`; nada de CORS para el origen ajeno ni, con la de DEF-42, para el Vite de desarrollo; para el propio, ese origen exacto con `Allow-Credentials: true` y `Vary: Origin` | Bloque E, 21 de 21; el origen de Vite, sin CORS (`defectos-sobre-9caa865.txt`, punto 13); SEC-W12 a W14 |
| Logs | `bash scripts/qa/control-de-logs.sh logs/server.log`; `grep -o 'Asistente: usuario=[^ ]*' logs/server.log \| sort \| uniq -c`; `grep 'CorreoAlLog' logs/server.log \| tail -3`; `docker compose logs web \| grep -cE '/puertas/[^ ]+(@\|%40)\|email=[^<& ]'` | "Control de logs: logs/server.log está limpio", sin tokens, contraseñas de prueba ni DNI; el asistente registra ids; con `PALCO_CORREO_LOG_COMPLETO=true` (la preparación de arriba lo prende para los guiones) los emails salen enteros, y sin la variable, solo el destinatario enmascarado (DEF-36); con la corrección de DEF-72, 0 renglones de nginx con un email | SEC-D01 y SEC-D03 |
| Sin stack traces al cliente | `curl -s -w '\nHTTP %{http_code}\n' -X POST "$API/api/usuarios/login" -H 'Content-Type: application/json' -d '{malo'`; el 500 con `idError`: `node scripts/pruebas/escenarios.mjs 14` (espera hasta 4 minutos y medio a la conciliación) | 400 "El cuerpo de la solicitud no es un JSON válido." en Problem Details; 500 `error-inesperado` con `idError` y nada interno | Escenario 14, 18 de 18; SEC-W11 |
| TLS | `curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' http://localhost/`; `echo \| openssl s_client -connect localhost:443 -servername localhost -brief 2>&1 \| grep -E 'Protocol version\|Ciphersuite'` (el `curl` de Git Bash usa Schannel y no muestra la versión de TLS) | 301 a `https://localhost/`; `Protocol version: TLSv1.3` (con `-tls1_2`, TLSv1.2) | Humo por HTTPS del job Integración; SEC-D04 |
| Contraseñas y datos sensibles | `curl -s "$API/api/usuarios/yo" -H "Authorization: Bearer $A"`; `(cd backend && mvn -q -pl servicios/usuarios -am test -Dtest=ContrasenasTest -Dsurefire.failIfNoSpecifiedTests=false)`; el código: `backend/servicios/usuarios/src/main/java/com/palco/usuarios/seguridad/Contrasenas.java` | El perfil sin contraseña ni hash; los 7 tests en verde; PBKDF2-HMAC-SHA256 con 600.000 iteraciones (sección 7) | Newman SEC-D02; bloque E; SEC-D02 y SEC-D06 |
| Asistente (eje E, 8.3) | `node scripts/pruebas/asistente.mjs` | "11 de 11 comprobaciones" | SEC-AS01 |
| Consumo de recursos en el registro (SEC-W15, DEF-64) | `printf '{"nombre":"%s1","apellido":"Prueba","dni":"30111222","fechaNacimiento":"1990-01-01","email":"lento@palco.test","password":"clave1234"}' "$(printf '%1000s')" > cuerpo.json` y `curl -s -o /dev/null -w 'HTTP %{http_code} en %{time_total} s\n' -H 'Content-Type: application/json' --data-binary @cuerpo.json "$API/api/usuarios/registro"`. Contra un sistema armado con `fbd31dc`, no pasar de 1000 espacios: con 1500 son 7 segundos de un hilo del servidor | Con la corrección de DEF-64 (`9caa865`): el 400 enseguida, también con 1500 espacios. Con `fbd31dc`: el 400 en unos 2 segundos con 1000 espacios (con un nombre común, 0,2 s) | `registro-nombre-largo.txt`, puntos 3 (`fbd31dc`) y 7 (el 400 en 0,21 s con 1500 espacios); SEC-W15 |

## 7. Contraseñas: PBKDF2-HMAC-SHA256 con 600.000 iteraciones

El eje D pide "contraseñas con bcrypt/Argon2". Usuarios usa PBKDF2-HMAC-SHA256
(`backend/servicios/usuarios/src/main/java/com/palco/usuarios/seguridad/Contrasenas.java`), que es una función de hash
adaptativa como esas dos: el costo es un parámetro que se sube con el tiempo.

**Qué recomienda OWASP** (Password Storage Cheat Sheet): Argon2id como primera opción (mínimo 19 MiB de memoria, 2
iteraciones y paralelismo 1), scrypt si no hay Argon2id, bcrypt para sistemas existentes (factor de trabajo 10 o más)
y PBKDF2 cuando hace falta un algoritmo aprobado por FIPS-140, con 600.000 iteraciones para PBKDF2-HMAC-SHA256.

**Los parámetros del código.**

| Parámetro | En Palco | Dónde | Test |
|---|---|---|---|
| Algoritmo | `PBKDF2WithHmacSHA256`, del proveedor de la JVM | `Contrasenas.pbkdf2` | `ContrasenasTest#laContrasenaCorrectaVerificaYLaIncorrectaNo` |
| Iteraciones | 600.000 (`ITERACIONES`), el valor de OWASP para PBKDF2-HMAC-SHA256 | `Contrasenas.ITERACIONES` | `#losHashesNuevosUsan600MilIteracionesYLosViejosSiguenVerificando` |
| Sal | 16 bytes de `SecureRandom`, una por contraseña | `BYTES_SAL` y `AZAR`, en `Contrasenas.hashear` | `#dosHashesDeLaMismaContrasenaSonDistintosPorLaSal` |
| Largo del hash | 256 bits | `BITS_HASH` | |
| Comparación | `MessageDigest.isEqual`, en tiempo constante | `Contrasenas.verificar` | `#laContrasenaCorrectaVerificaYLaIncorrectaNo` |
| Formato guardado | `pbkdf2-sha256$<iteraciones>$<sal>$<hash>`: cada hash lleva sus iteraciones, así el costo se sube sin migrar datos (el 16/10 pasó de 310.000 a 600.000) | `PREFIJO`, en `Contrasenas.hashear` | `#losHashesNuevosUsan600MilIteracionesYLosViejosSiguenVerificando`, `#unHashMalFormadoNoVerificaNiRompe` |
| Hash con menos iteraciones | Se recalcula con 600.000 al iniciar sesión, con la contraseña que se acaba de verificar (DEF-44, cerrado el 26/11) | `Contrasenas.tieneMenosIteraciones` y `ServicioDeUsuarios.iniciarSesion` | `IteracionesAlIniciarSesionEnH2Test#unaCuentaCon310MilIteracionesIngresaYQuedaCon600Mil` |
| Contraseña en memoria | `PBEKeySpec.clearPassword()` después de calcular | El `finally` de `Contrasenas.pbkdf2` | |
| Cuenta que no existe | Se verifica contra `HASH_FALSO`, del mismo costo: el tiempo no delata qué emails tienen cuenta | `ServicioDeUsuarios.java`, líneas 102 y 249 | `LimiteDeIntentosDeLoginTest#elHashFalsoCuestaLoMismoQueElDeUnaCuentaNueva` |
| Cuenta desactivada | `INUTILIZABLE`: el formato y las iteraciones de siempre, con un resultado que ninguna contraseña conocida da | `Contrasenas.INUTILIZABLE` | `ContrasenasTest#elHashInutilizableTieneLasIteracionesDeSiempreYNoVerificaConNinguna` |
| Política de la contraseña | 8 caracteres o más, con letras y números | `RegistroDto.password`, en `usuarios/api/Dtos.java` | Escenario 24 y CA-01 |

**Por qué PBKDF2 y no Argon2id o bcrypt.**

1. Viene en la JVM: no suma una librería nativa ni otra dependencia a Usuarios y al análisis de dependencias
   (sección 4.2).
2. Con 600.000 iteraciones está en el valor que OWASP recomienda para esta función, y el costo se vuelve a subir
   cambiando una constante.
3. Hace caro probar contraseñas desde afuera: cada ingreso calcula el hash (unos 130 ms en el runner del CI, Newman
   SEC-W03 y SEC-W04) y, sumado al límite de 5 intentos en 15 minutos (SEC-W04), deja unos 20 intentos por hora por
   email.

**Qué se resigna.** PBKDF2 no usa memoria: si alguien robara la tabla de usuarios, con GPU probaría más contraseñas
por segundo que contra Argon2id, y por eso OWASP pone primero a Argon2id. El formato con prefijo deja el camino
abierto: agregar `argon2id$…` y rehashear al iniciar sesión, sin una migración masiva (la misma idea que DEF-44 para
las iteraciones).

## 8. Credenciales de prueba y valores por defecto escritos en el repo

gitleaks no los marca (sección 4.3), pero están a la vista y se justifican:

- **`palco1234`, la contraseña de las seis cuentas de prueba** (`@palco.test`, un dominio reservado para pruebas,
  RFC 2606: no son de ninguna persona). Las cargan migraciones de Usuarios que Flyway no deja cambiar, y el README las
  documenta para la demo. Fuera de desarrollo se desactivan con `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false`: al arrancar,
  Usuarios les pone el hash `INUTILIZABLE` (DEF-26, cerrado;
  `DesactivacionDeCuentasDePruebaEnH2Test#conLaPropiedadEnFalseLaCuentaDePruebaNoEntraYUnaNormalSi`,
  `#conLaPropiedadEnTrueNoCambiaNada` y `#correrloDosVecesNoRompeNada`). Con el valor por defecto, el del compose y la
  demo, siguen activas y el log lo avisa al arrancar.
- **El secreto entre servicios no tiene valor fijo.** Si falta `PALCO_SECRETO_SERVICIOS`, `TokenDeServicio` usa uno al
  azar distinto en cada arranque y lo avisa en el log (`TokenDeServicioTest#sinLaVariableNoHayUnValorFijoSinoUnoAlAzarDistintoCadaVez`);
  el contenedor genera uno de 32 bytes si no viene (`backend/docker/arrancar.sh`) y el CI genera otro en cada corrida y
  lo enmascara. El valor fijo que existía hasta el 19/11 el servidor ya no lo acepta, y desde el 25/11 tampoco lo usan
  por defecto los guiones de `docs/entrega3/evidencia/`: buscan el secreto como `scripts/pruebas/lib.mjs` y, si no lo
  encuentran, avisan y terminan (DEF-56, cerrado el 26/11).
- **`palco-local`, la contraseña por defecto de PostgreSQL en el compose.** La base no publica su puerto fuera de la red
  del compose; se cambia con `PALCO_DB_PASSWORD`.
- **Lo que no está en el repo:** la clave JWT (si falta, Usuarios genera una al arrancar), el certificado TLS (se genera
  al arrancar el contenedor web), las credenciales de SMTP y de Supabase y la clave del proveedor del asistente: van en
  el entorno o en un `.env`, que está en `.gitignore`. Los `.env.example` solo traen nombres de variables y valores de
  ejemplo.

## 9. Cómo repetir las corridas

Cada workflow corre con cada push a `master` o `main`, cada pull request y cada tag `v*`, y se puede lanzar a mano:

```bash
gh workflow run pruebas-seguridad.yml -R santttii/deapps2 --ref <tag o rama>
gh workflow run zap.yml               -R santttii/deapps2 --ref <tag o rama>
gh workflow run seguridad.yml         -R santttii/deapps2 --ref <tag o rama>
gh workflow run contrato.yml          -R santttii/deapps2 --ref <tag o rama>
gh run download <run> -R santttii/deapps2 -n <artefacto>   # pruebas-seguridad, reporte-zap, trivy-*, gitleaks...
```

Contra un compose levantado en una máquina, con la preparación de la sección 6:

```bash
node scripts/pruebas/seguridad.mjs | tee seguridad.txt
(cd frontend && PALCO_E2E_URL=https://localhost PALCO_LOG="$(pwd)/../logs/server.log" npx playwright test e2e/xss.spec.ts)
node scripts/pruebas/escenarios.mjs 4 5 14 15 21 24 | tee escenarios-seguridad.txt
node scripts/pruebas/asistente.mjs
npx newman@6.2.3 run docs/api/palco-api.postman_collection.json --env-var "secretoServicios=$PALCO_SECRETO_SERVICIOS"
bash scripts/qa/zap.sh
bash scripts/qa/control-de-logs.sh logs/server.log     # tokens, contraseñas de prueba y DNI (DEF-40)
docker compose down -v
```

La colección de Postman completa va antes que su carpeta 4: SEC-W01 toma una compra de `titular@palco.test`, como la
que deja la carpeta 3 (`docs/api/GUIA_POSTMAN.md`, sección 13).
