# Declaración de uso de inteligencia artificial

**Proyecto:** Palco, plataforma de venta de entradas nominativas con reventa oficial (tope +10 %).  
**Instancia:** Entrega Final, viernes 27/11/2026 (sobre el repositorio en `master`, commit `70242ce` y tag `v1.0-final`).  
**Integrantes:** Santiago Loto, Valentina Frisoli, Sebastian Deya, Julián Zuchowicki y Joaquin Molina.  
**Alcance:** Responde al criterio G8 y a la sección 8 de los Criterios de Testing (8.1 uso en el desarrollo, 8.2 plan de uso futuro y 8.3 funcionalidad de IA del producto), contrastado con el historial de Git, las corridas del CI, SonarCloud y las evidencias de prueba.

## 1. Criterio de uso y controles del equipo

* **Revisión y defensa compartida:** Ninguna salida generada con IA (código, pruebas, scripts, configuración, diagnóstico o documentación) se da por buena sin revisión, ejecución y aceptación por parte del equipo; cada integrante responde por lo incorporado y puede explicarlo en la defensa oral.
* **Validación técnica con evidencia:** Todo cambio pasa por los controles del proyecto (`mvn verify`, `scripts/pruebas/escenarios.mjs`, Newman, Playwright, análisis estático/seguridad como PMD, SonarCloud, Trivy y ZAP, y el CI en cada push); un dictamen de IA sin prueba ejecutada y evidencia no se reporta como verificado.
* **Control de accesos y datos:** No se envían a herramientas de IA secretos (`backend/.env`, claves JWT, secreto entre servicios, credenciales SMTP/Supabase ni claves de proveedores de IA) ni datos personales reales; los datos de prueba son ficticios y usan `@palco.test`.
* **Alcance y distinción con el producto:** Las herramientas de IA no deciden arquitectura, alcance ni aceptación; asimismo, el asistente de Palco (incorporado desde `48e072c` el 18/11) es una funcionalidad del producto y no se confunde con el uso de IA como apoyo durante el desarrollo.

## 2. Herramientas utilizadas (8.1)

| Herramienta | Versión / Modelo | Uso principal en el equipo | Evidencia / Ubicación |
|---|---|---|---|
| Claude Code en la web (`claude.ai/code`, Anthropic) | Modelo Claude Opus 5.5 | Generación de cambios y pruebas en sesiones puntuales (como el PR #1 del 23/10 en `d7a4241` y `5c465ec`, y los commits del 30/10 `75c03d0`, `8ff9b63`, `bb42185` y `896e111`) | Commits con autor `Claude <noreply@anthropic.com>` o tráiler `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` |
| Claude Code, extensión para VS Code (`anthropic.claude-code`, Anthropic) | Extensiones 2.1.330 y 2.1.331; modelo Claude Opus 5.5 | Apoyo en código, pruebas, configuración de CI/análisis, documentación y tareas de trabajo | `CLAUDE.md`, `docs/PLAN_AGENTES.md` (versionados del 13/10 en `e7c8b6f` al 16/10 en `4e2f7fa`), commits del repositorio y evidencias |
| `claude.ai` (artifacts, Anthropic) | Claude Sonnet 4.5 (septiembre de 2026) | Apoyo en el prototipo navegable inicial del diseño ("Página de venta de tickets") | Prototipo navegable compartido con la cátedra |
| GitHub Copilot (VS Code) | Copilot Chat | Autocompletado y armado inicial de casos de prueba unitarios | Suites de prueba en los módulos backend |

*(Nota sobre entregas anteriores: en la actividad de la clase 11 se dejó preparado un prompt para Claude Fable 5.1, pero no llegó a ejecutarse y todos los criterios de seguridad se verificaron mediante pruebas ejecutadas; por eso no se lista como herramienta utilizada. Asimismo, la colección de Postman se genera íntegramente desde `scripts/postman/generar.js` sin uso de Postbot.)*

## 3. Usos registrados y cómo se verificaron (8.1)

A lo largo del cuatrimestre se emplearon herramientas de IA como apoyo de productividad para redactar borradores, estructurar pruebas y configuraciones, y acelerar tareas acotadas. Todo lo incorporado se sometió a revisión humana, ejecución de pruebas locales y del CI, y contraste contra las evidencias del repositorio:

* **Cambios del PR #1 (23/10) y ajustes posteriores:** Se incorporaron mejoras vinculadas a eventos por organizador (`/api/eventos/mios`, migración `V6__eventos_de_la_cuenta_organizadora.sql`), validaciones, pruebas y documentación (`d7a4241`, `5c465ec`, merge en `a7d3d54`), y luego se refinó la integración de Validación hacia Eventos (`f655139` el 30/10 hacia `GET /api/eventos/{eventoId}/organizador` con rol `servicio`) y se verificó con el escenario 21 y las corridas de CI.
* **Infraestructura, CI y documentación de seguimiento (30/10):** Se generaron bases para `docker-compose.yml`, `frontend/docker/nginx.conf`, `.github/workflows/ci.yml`, `docs/documentacion/historias-de-usuario.md`, `scripts/qa/matriz.mjs` y `docs/testing/informe-condiciones-eliminatorias.md` (`75c03d0`, `8ff9b63`, `bb42185`, `896e111`), ampliadas luego con JaCoCo, Playwright, PMD, SonarCloud y Newman, y validadas en las ejecuciones de CI.
* **Commits del 02/10 con autor `Improvement <Improvement@mesucan.com.ar>`:** Los cinco commits del 02/10 con autor `Improvement <Improvement@mesucan.com.ar>` corresponden al arranque del backend y la estructura inicial del proyecto (*"Update README and add TODO with backend roadmap"*, *"Align TODO with course stack: Jakarta EE on WildFly"*, *"Move frontend to frontend/ and scaffold Jakarta EE backend"*, *"Primeros pasos"* y *"Backend: 8 servicios como WAR independientes y ServicioDeEventos funcionando"*), realizados desde un equipo con otra identidad de Git configurada; como el pipeline de CI se incorporó recién el 30/10, esos cambios se verificaron en su momento con las pruebas locales y el armado de los servicios del proyecto.
* **Trabajo asistido con extensión y revisión de evidencias:** Se trabajó con reglas de verificación antes de cerrar cambios (correr pruebas, levantar el servicio, validar `health` y compilar/analizar el frontend) y se contrastaron los datos de esta declaración contra commits, corridas del CI, SonarCloud y pruebas del asistente.

## 4. Propuestas incorrectas detectadas y cómo se resolvieron (8.1)

* **Imagen `nginx-unprivileged` marcada por Trivy (DEF-78, cerrado):** Para que nginx corriera sin root (DEF-71), la IA propuso pasar a `nginxinc/nginx-unprivileged:1.30-alpine` y entró a `master` el 25/11 (`6f19183`). Esa imagen traía nginx 1.30.6, con cuatro vulnerabilidades críticas y dos altas, y el Trivy del workflow Seguridad la dejó en rojo (DEF-78). Lo detectó Santiago en el CI y lo corrigió Valentina en `f561105` volviendo a la imagen oficial `nginx:1.30-alpine` (nginx 1.30.8) con los mismos ajustes para correr sin root (`USER 101`, sin directiva `user` y con el `pid` y los temporales en `/tmp`); Trivy dio 0 críticas y 0 altas.
* **Configuración de cobertura en SonarCloud (`4bf0711` -> `949373b`, 21/11):** El uso de `sonar.sources` y `sonar.tests` no surtía efecto bajo el alcance nuevo de SonarCloud y hacía que las pruebas se midieran como código sin cubrir (reportando 22,3 % de cobertura de líneas en el análisis de `9b92294`, corrida 44860203640). Se corrigió en `949373b` usando `sonar.exclusions` (`**/src/test/**`, `frontend/e2e/**`, `frontend/playwright.config.ts`, `scripts/**`, `docs/**`, `node_modules`, `target`), pasando luego a 81,5 % de cobertura de líneas en `c4ec165` y Quality Gate en verde en `62ecfca`.
* **Navegación en prueba E2E de recuperación de contraseña (`d6e8915` -> `b387445`, 21/11):** Volver con `page.goto('/recuperar')` entre resoluciones (360, 768 y 1280 px) mantenía el paso del historial y provocaba timeout al buscar el campo de email; se corrigió en `b387445` navegando a `/recuperar?ancho=${ancho}` y se verificó en la corrida 44867187162.
* **Sincronización de colores de temas con Tailwind (`27e1dc9` y `23fc72f`, 21/11):** Dejar sin actualizar `frontend/tailwind.config.js` impedía que clases como `text-ink` o `bg-paper` reflejaran las variables de tema; se ajustó la configuración con `rgb(var(--color-...) / <alpha-value>)` antes del commit y se verificó con `frontend/e2e/temas.spec.ts`.
* **Paso de control de logs en el CI (DEF-40, cerrado el 26/11):** El nombre del paso del job de integración mencionaba el control de DNI de prueba en logs cuando el filtro inicial solo cubría tokens; se actualizó el control correspondiente y quedó cerrado el 26/11, además de contar con el respaldo de `ServicioDeNotificacionesTest#elAvisoNoLlevaElDniDeNingunTitular`.
* **Imagen base de nginx en el frontend (`75c03d0` -> `64288b2`, DEF-51 cerrado):** Partir de `nginx:1.27-alpine` arrastraba una rama desactualizada; se actualizó en `64288b2` a `nginx:1.30-alpine` con `apk upgrade`, reduciendo los hallazgos del escaneo local y verificándose en cero vulnerabilidades críticas/altas en Trivy (corrida 44867187171 sobre `palco-web` en Alpine 3.24.4).
* **Expresión regular en utilidades comunes (`4cd350b`, DEF-65 cerrado):** En `Textos.java` (`Textos.slug`) se detectó y resolvió la discrepancia asociada a la regla `java:S5850` en SonarCloud, quedando el defecto cerrado y sin hallazgos abiertos de `java:S5850` en SonarCloud.

## 5. Pruebas generadas con IA: revisión y firma (8.1)

Toda prueba con asistencia de IA fue revisada y ejecutada por el equipo, quedando asociada a su autor en Git y a la explicación del integrante responsable en la defensa oral. En particular, los tests con autor Claude en `d7a4241` (`CupoEnH2Test.losEventosDelOrganizadorSonSoloLosSuyosOrdenadosPorFecha`, `ServicioDeEventosTest.losEventosDelOrganizadorSalenDelRepositorioPorSuId`, `EscaneosResourceTest`, `ServicioDeValidacionTest`, `ClienteDeEventosTest` y la versión inicial del escenario 21 de `scripts/pruebas/escenarios.mjs`) **los revisó y mergeó Julián en `a7d3d54`** (reescribiendo además la integración de `ClienteDeEventos` en `f655139`). Asimismo, los pasos/scripts de prueba y E2E (`ci.yml`, `matriz.mjs`, `recuperar.spec.ts`, `temas.spec.ts`) y las pruebas de integración y E2E incorporadas hacia el cierre quedan revisados y respaldados por sus respectivos autores.

## 6. Uso por integrante (8.1)

* **Santiago Loto:**
  1. **Herramienta y versión:** Claude Code, extensión para VS Code (`anthropic.claude-code` 2.1.330 y 2.1.331, modelo Claude Opus 5.5) y Claude Code web (`claude.ai/code`).
  2. **Para qué la usó:** Desarrollo y ajuste de servicios backend, guardrails y adaptador del asistente de IA, pruebas unitarias/integración/E2E, configuración de análisis estático y documentación técnica.
  3. **Prompt representativo:** *"Implementá el adaptador ModeloCompatibleOpenAI detrás de ModeloDeLenguaje con timeout de 5 segundos, enmascaramiento previo de DNI/tarjetas/emails y pruebas unitarias sin depender de red."*
  4. **Qué aceptó:** La estructura del adaptador `ModeloCompatibleOpenAI` y sus pruebas (`90e1f65`), la ampliación de guardrails contra inyecciones reformuladas (`d862d0e`), la suite base de Playwright (`729e605`) y los borradores de documentación técnica.
  5. **Qué corrigió o descartó, y por qué:** Ajustó el filtrado de secretos en logs (`1a27b49`), refinó las expresiones de detección de inyección indirecta en `GuardrailsTest` y cerró el seguimiento de DEF-40 el 26/11 respaldando el control de DNI en logs y pruebas unitarias.
  6. **Cómo lo verificó:** Mediante `mvn verify`, `mvn -pl servicios/asistente -am test` (207 tests en verde), `scripts/pruebas/asistente-proveedor.mjs`, `escenarios.mjs` y las corridas completas de GitHub Actions.
  7. **Explicación de pruebas:** Confirma que revisó y puede explicar las pruebas de backend, seguridad, asistente, escenarios de integración y E2E firmadas en sus commits.

* **Valentina Frisoli:**
  1. **Herramienta y versión:** Claude Code para VS Code (`anthropic.claude-code` 2.1.330, modelo Claude Opus 5.5).
  2. **Para qué la usó:** Implementación del servicio Asistente con IA (Facade, guardrails, timeout y fallback), pruebas de riesgos OWASP LLM, patrón State en Ventas, pruebas de seguridad (ZAP, XSS, JWT/XXE) y resiliencia.
  3. **Prompt representativo:** *"Escribí pruebas en RiesgosDelAsistenteTest para verificar que el gateway AsistenteIA bloquee intentos de extracción del prompt del sistema (LLM07) y fugas de datos entre usuarios concurrentes (LLM02)."*
  4. **Qué aceptó:** La estructura de `AsistenteIA`, `GuardrailsTest` y `EvaluacionDelAsistenteTest` (`48e072c`), la batería de 60 casos en `RiesgosDelAsistenteTest` (`231ad45`), las pruebas de `EstadoDeVentaTest` (`e92c125`) y los scripts de `seguridad.mjs` (`23780b0`) y `xss.spec.ts` (`befe84e`).
  5. **Qué corrigió o descartó, y por qué:** Corrigió DEF-78 (`f561105`) volviendo a la imagen oficial `nginx:1.30-alpine` (nginx 1.30.8) con ejecución sin root tras la alerta de Trivy sobre `nginx-unprivileged` (`6f19183`) detectada por Santiago en el CI, ajustó el algoritmo de enmascaramiento de emails en `Guardrails` (`fbd31dc`, DEF-63) tras detectar mediante Schemathesis degradación cuadrática ante entradas de decenas de miles de caracteres, y filtró falsos positivos verificados en `.zap/reglas.tsv` (`4421d98`).
  6. **Cómo lo verificó:** Ejecutando las pruebas unitarias del asistente, `scripts/pruebas/asistente.mjs` (11/11), `patron-state.mjs`, `broker-apagado.mjs`, `xss.spec.ts` y los workflows `zap.yml`, `pruebas-seguridad.yml` y `seguridad.yml` (Trivy).
  7. **Explicación de pruebas:** Confirma que revisó y puede explicar los tests del asistente, patrón State, resiliencia, XSS y seguridad automatizada.

* **Sebastian Deya:**
  1. **Herramienta y versión:** Claude Code para VS Code (`anthropic.claude-code` 2.1.330, modelo Claude Opus 5.5).
  2. **Para qué la usó:** Especificación inicial de diseño del frontend, configuración de SonarCloud, pruebas de contrato OpenAPI/WSDL, accesibilidad con axe, pruebas de carga con k6 y ampliación de `scripts/postman/generar.js`.
  3. **Prompt representativo:** *"Generá un script de carga con k6 que simule 50 usuarios virtuales durante 5 minutos consultando el catálogo y flujos de compra, verificando p95 menor a 500 ms y tasa de error menor al 1 %."*
  4. **Qué aceptó:** El guion de carga en `scripts/carga/palco.js` (`f726b05`), las pruebas de contrato en `ContratoDelWsdlTest.java` (`38ee24b`), los tests de accesibilidad en `frontend/e2e/accesibilidad.spec.ts` (`93fa059`) y los casos negativos agregados en `scripts/postman/generar.js` (`76d7dc2`).
  5. **Qué corrigió o descartó, y por qué:** Corrigió `sonar-project.properties` en `949373b` eliminando `sonar.sources` y `sonar.tests` (que bajo el alcance nuevo de SonarCloud hacían computar los tests como código sin cubrir, bajando la cobertura a 22,3 %) y configurando `sonar.exclusions`; además, dejó resuelto y cerrado DEF-65 (`Textos.slug` / regla `java:S5850` en SonarCloud).
  6. **Cómo lo verificó:** Verificando la subida de cobertura a 81,5 % y el Quality Gate en verde en SonarCloud (`62ecfca`), sin hallazgos abiertos de `java:S5850`, junto con las ejecuciones de Schemathesis, k6, Newman y Playwright/axe en el CI.
  7. **Explicación de pruebas:** Confirma que revisó y puede explicar las pruebas de contrato, WSDL, accesibilidad, carga con k6 y el generador `scripts/postman/generar.js`.

* **Julián Zuchowicki:**
  1. **Herramienta y versión:** GitHub Copilot Chat (VS Code) y Claude Code (`claude.ai/code`, modelo Claude Opus 5.5 en revisión del PR #1).
  2. **Para qué la usó:** Revisión e integración de cambios de validación y propiedad de eventos, además de apoyo en estructura de tests de conciliación, reventa y devolución de cupos.
  3. **Prompt representativo:** *"Escribí casos de prueba JUnit 6 para validar la devolución idempotente de cupo en Eventos cuando se repite la misma clave de venta y sector."*
  4. **Qué aceptó:** Los tests de repositorio y servicio del endpoint `/api/eventos/mios` tras revisarlos y mergearlos en el PR #1 (`a7d3d54`), junto con las plantillas de aserciones para `CupoEnH2Test` (`4e38ea5`) y `ConciliacionDeReventasTest` (`18a48f2`).
  5. **Qué corrigió o descartó, y por qué:** Descartó en `f655139` la implementación original de `ClienteDeEventos` en Validación porque consultaba `/api/eventos/mios` reenviando el token del organizador; la reemplazó por `GET /api/eventos/{eventoId}/organizador` con token de rol `servicio` para desacoplar el escaneo en puerta.
  6. **Cómo lo verificó:** Corriendo `mvn test` en los módulos `eventos`, `validacion` y `reventa`, y ejecutando los escenarios de integración 17, 18, 19, 20 y 21 en `scripts/pruebas/escenarios.mjs`.
  7. **Explicación de pruebas:** Confirma que revisó y puede explicar los tests del PR #1 (`a7d3d54`) y los tests de `Validación`, `Reventa`, `Eventos` y escenarios asociados a sus commits.

* **Joaquin Molina:**
  1. **Herramienta y versión:** Claude Code (`claude.ai/code`, modelo Claude Opus 5.5) y Claude Code para VS Code (`anthropic.claude-code` 2.1.330).
  2. **Para qué la usó:** Infraestructura con Docker Compose y nginx con TLS, pipeline inicial de CI, historias de usuario con criterios Gherkin, generador de matriz de trazabilidad y pruebas E2E de recuperación de contraseña y temas.
  3. **Prompt representativo:** *"Armá el docker-compose.yml para levantar PostgreSQL 17, WildFly con los WAR del backend y nginx con TLS como reverse proxy hacia /api, más el workflow inicial de GitHub Actions con gitleaks y mvn verify."*
  4. **Qué aceptó:** La estructura base de `docker-compose.yml` y `nginx.conf` (`75c03d0`), el pipeline inicial en `.github/workflows/ci.yml` (`8ff9b63`), las historias de usuario y `scripts/qa/matriz.mjs` (`bb42185`), y la implementación de temas y pruebas E2E (`27e1dc9`, `23fc72f`).
  5. **Qué corrigió o descartó, y por qué:** Corrigió la navegación en `frontend/e2e/recuperar.spec.ts` (`b387445`) pasando `?ancho=${ancho}` porque `page.goto('/recuperar')` conservaba el paso del historial y causaba timeout; también actualizó la imagen base a `nginx:1.30-alpine` (`64288b2`) para eliminar vulnerabilidades de paquetes desactualizados.
  6. **Cómo lo verificó:** Ejecutando el levantamiento completo con `docker compose up --build --wait`, las suites de Playwright en local y las corridas del CI en GitHub Actions (incluyendo el run 44867187162 y el escaneo de Trivy en 44867187171).
  7. **Explicación de pruebas:** Confirma que revisó y puede explicar `recuperar.spec.ts`, `temas.spec.ts`, `matriz.mjs` y los pasos de verificación del `ci.yml`.

## 7. Plan de uso futuro de IA (8.2)

| Herramienta y uso previsto | Objetivo medible | Riesgo que controla | Control humano |
|---|---|---|---|
| Claude Code: generación de pruebas para mutantes sobrevivientes en PIT (`mutacion.yml`) | Elevar el porcentaje de mutantes eliminados por encima del umbral vigente y sostener el mínimo por módulo | Pruebas que pasan sin validar la lógica (tests anulados) | Solo entra lo que mate el mutante en PIT y sea revisado por el equipo |
| Claude Code: generación de casos negativos y de borde desde criterios Gherkin | Cubrir cada criterio relevante con al menos un caso negativo o de borde automatizado | Pruebas limitadas al camino feliz | Cada prueba se verifica fallando al invertir la condición y se revisa antes de commitear |
| Claude Code: revisión de diffs y triage de hallazgos (SonarCloud, ZAP, Trivy) | Quality Gate en verde y 100 % de hallazgos críticos/altos con decisión documentada | Defectos o falsos positivos cerrados sin comprobación real | Aprobación final, reproducción de falsos positivos y cierre a cargo del equipo |
| Evaluación del asistente contra un proveedor real (`ModeloCompatibleOpenAI`) | Alcanzar al menos 24/26 en evaluación funcional (11/11 adversariales), 60/60 en riesgos y p95 menor a 5 s | Divergencia del modelo real frente al simulado (OWASP LLM01, LLM02, LLM07, LLM09) | No se habilita el proveedor real sin superar la evaluación y las pruebas |
| Contraste documental contra evidencias de ejecución | Cero métricas sin fuente verificable en los documentos de entrega | Cifras desactualizadas o afirmaciones sin evidencia | Verificación manual de comandos y métricas antes de etiquetar la entrega |

## 8. La IA dentro del producto: el asistente (8.3)

El asistente opera mediante `POST /api/v1/asistente/preguntas` (roles `user`, `organizer` y `staff`) y la pantalla `/asistente`, centralizando el acceso a través de `AsistenteIA` (`Facade`) como componente de solo lectura (sin base propia ni escritura sobre otros servicios). Incluye guardrails de entrada y salida, enmascaramiento de DNI, tarjetas y correos antes del modelo y en logs (registrando el id del usuario y nunca su correo), timeout máximo de 5 segundos con fallback a respuestas fijas basadas en `ReglasDeNegocio`, y soporte para `ModeloSimulado` por defecto o `ModeloCompatibleOpenAI` mediante configuración.

Su validación comprende 26 casos en `EvaluacionDelAsistenteTest` (15 funcionales y 11 adversariales), 95 casos en `GuardrailsTest`, 60 casos en `RiesgosDelAsistenteTest` (OWASP Top 10 para LLM: LLM01, LLM02, LLM06, LLM07, LLM09 y LLM10), pruebas unitarias y del adaptador (207 tests en total en el módulo del asistente), `scripts/pruebas/asistente.mjs` (11/11), `scripts/pruebas/asistente-proveedor.mjs` (que probó el adaptador con proveedor compatible), pruebas E2E en `frontend/e2e/asistente.spec.ts` y la carpeta 9 de Postman. DEF-25, DEF-36 y DEF-63 se encuentran cerrados, y cualquier habilitación de un proveedor externo en producción continúa sujeta a la verificación previa de métricas y casos de prueba.

## 9. Verificación y conformidad final

Las métricas, pruebas y referencias de este documento se respaldan en las ejecuciones de pruebas, el pipeline de CI, SonarCloud y las evidencias del repositorio. Cada integrante valida su sección y firma su conformidad en la tabla siguiente:

| Integrante | Herramientas declaradas | Revisó su sección y las pruebas asociadas | Conformidad |
|---|---|---|---|
| Santiago Loto | Claude Code (VS Code 2.1.330/2.1.331 y web, Claude Opus 5.5) | Sí | Confirmado |
| Valentina Frisoli | Claude Code (VS Code, Claude Opus 5.5) | Sí | Confirmado |
| Sebastian Deya | Claude Code (VS Code, Claude Opus 5.5) | Sí | Confirmado |
| Julián Zuchowicki | GitHub Copilot Chat, Claude Code (revisión PR #1) | Sí | Confirmado |
| Joaquin Molina | Claude Code (web y VS Code, Claude Opus 5.5) | Sí | Confirmado |