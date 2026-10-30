# Informe: avance de las condiciones eliminatorias (Prueba del TP)

- **Fecha:** 30/10/2026
- **Rama:** `testing/condiciones-eliminatorias`, creada desde `master` en `f655139`
- **Autor:** Joaquin Molina (QA lead), con asistencia de Claude Code
- **Base:** "Plan de implementación — Prueba del TP Obligatorio (testing)", tareas P0 de los frentes F1, F5 y F6

## 1. Resumen

| Tarea del plan | Condición | Estado | Evidencia |
|---|---|---|---|
| INF-01 gitleaks sobre el historial | Sin secretos en el repo | **Hecho**: 0 hallazgos | Sección 3 |
| INF-02 Dockerfile del backend | Un comando | **Hecho** | `backend/Dockerfile` |
| INF-03 `docker-compose.yml` | Un comando | **Hecho**: db, backend y web, con healthchecks | `docker-compose.yml`, sección 4 |
| INF-04 nginx con TLS y cabeceras | (P1, adelantada: la necesita el compose) | **Hecho** | `frontend/Dockerfile`, `frontend/docker/` |
| INF-05 README "Levantar todo" | Un comando | **Hecho** | README, "Todo junto, con un solo comando (Docker)" |
| INF-06 Prueba de máquina limpia | Un comando, 15 min o menos | **Parcial**: medido en un contenedor limpio, falta en la máquina de alguien del grupo (2 veces) | Sección 4 |
| INF-07 Pipeline de CI | CI en verde | **Hecho el pipeline**; va a quedar en rojo hasta que se corrija DEF-01 | `.github/workflows/ci.yml`, sección 5 |
| ATR-01 Historias de usuario | Documentación de pruebas | **Hecho**: 14 HU, 61 criterios en Gherkin | `docs/documentacion/historias-de-usuario.md` |
| ATR-02 Convención de trazabilidad | Documentación de pruebas | **Hecho** | Mismo documento, primera sección |
| DOC-02 Matriz de trazabilidad | Documentación de pruebas | **Hecho el generador**: hoy 22 de 61 criterios con caso | `scripts/qa/matriz.mjs`, `docs/testing/evidencia/` |
| DOC-05 Registro de defectos | Defectos conocidos registrados | **Abierto**: DEF-01 a DEF-04 hay que cargarlos en Notion | Sección 2 |
| INF-09 Tag `v1.0-testing` | Tag a tiempo | Pendiente (va al final) | — |

**Lo más importante:** el `master` de hoy **no levanta**. Ningún servicio despliega (DEF-01), con `./dev.sh` y con
Docker por igual. Los 761 tests de `mvn verify` pasan igual, porque ninguno arranca el servidor. El job
`integracion` del CI nuevo lo detecta. La corrección está probada (sección 2) y es de quien está desarrollando
esa parte. No se aplicó en esta rama para no pisar trabajo en curso. Con esa corrección, la prueba de integración
muestra dos defectos más: el script quedó desactualizado con los emails nuevos (DEF-03) y hay una posible regresión
en el orden del registro de la puerta (DEF-04).

## 2. Defectos encontrados

### DEF-01 · Bloqueante · El master no despliega ningún servicio

- **Dónde:** `master` en `f655139` (el push del 30/10 con emails, imágenes de eventos y cancelación).
- **Pasos:** `docker compose up --build` en esta rama, o `cd backend && ./dev.sh` en `master`.
- **Esperado:** los 8 servicios despliegan y sus `/health` responden 200.
- **Obtenido:** los 8 WAR quedan `.failed` y solo despliega el ARCA simulado. Cada `/health` responde 404.
- **Causa:** son tres problemas.
  1. `jakarta.mail` no está en el WildFly armado, porque falta la capa `mail` de Galleon. `CorreoSmtp` no carga
     (`WELD-001474 ... jakarta.mail.Authenticator`) y los 7 servicios que no lo eligen fallan con
     `WELD-001408 Unsatisfied dependencies for type CorreoSmtp`.
  2. `CorreoAlLog` es un bean de tipo `Correos` y, además, `FabricaDeCorreos` produce un `Correos`. Usuarios falla
     con `WELD-001409 Ambiguous dependencies for type Correos`.
  3. Pasa lo mismo con `AlmacenEnSupabase` y `AlmacenNoConfigurado` frente a `FabricaDeAlmacen` en Eventos
     (`Ambiguous dependencies for type AlmacenDeImagenes`).
- **Corrección propuesta (probada):** la misma que ya usa `FabricaDeCobros` con `ClienteDePagos` y `CobrosFake`.
  Cada implementación lleva `@Typed(SuPropiaClase.class)` y se agrega `<layer>mail</layer>` en
  `backend/servidor/pom.xml`. Con esos 5 cambios los 8 servicios despliegan. Resultado de la prueba de integración
  sobre esa corrección: sección 4.

```diff
--- a/backend/common/src/main/java/com/palco/common/email/CorreoAlLog.java
+import jakarta.enterprise.inject.Typed;
 @ApplicationScoped
+@Typed(CorreoAlLog.class)
 public class CorreoAlLog implements Correos {
--- a/backend/common/src/main/java/com/palco/common/email/CorreoSmtp.java
+import jakarta.enterprise.inject.Typed;
 @ApplicationScoped
+@Typed(CorreoSmtp.class)
 public class CorreoSmtp implements Correos {
--- a/backend/servicios/eventos/.../negocio/externos/AlmacenEnSupabase.java
+import jakarta.enterprise.inject.Typed;
 @ApplicationScoped
+@Typed(AlmacenEnSupabase.class)
 public class AlmacenEnSupabase implements AlmacenDeImagenes {
--- a/backend/servicios/eventos/.../negocio/externos/AlmacenNoConfigurado.java
+import jakarta.enterprise.inject.Typed;
 @ApplicationScoped
+@Typed(AlmacenNoConfigurado.class)
 public class AlmacenNoConfigurado implements AlmacenDeImagenes {
--- a/backend/servidor/pom.xml
                         <layer>webservices</layer>
+                        <layer>mail</layer>
```

  `<layer>mail</layer>` cambia cómo se arma WildFly. Con `./dev.sh` hay que borrar `backend/servidor/target` para
  que se vuelva a armar (lo explica `configurar.cli`). Con Docker se rearma solo.
- **Lección para DOC-07:** 761 tests unitarios en verde y el sistema no arranca. Una prueba que despliegue de
  verdad (el job `integracion`) lo habría frenado antes de llegar a `master`. Este es un buen candidato para las
  "lecciones aprendidas".

### DEF-02 · Menor · Al arrancar, las migraciones sobre H2 se pueden trabar

- **Dónde:** `./dev.sh` con H2 en memoria, en una máquina lenta (medido en un contenedor de 4 núcleos, con 224 s de
  arranque), sobre `a7d3d54`.
- **Obtenido:** 6 de 8 servicios fallaron con `Timeout trying to lock table "SYS"` (H2, código 50200).
- **Estado:** el push `f655139` dice que las migraciones ahora corren de a una (`BaseDeDatos.migrar`), lo que
  debería resolverlo. No se pudo comprobar sobre `master` por DEF-01. Con el compose no pasa, porque usa
  PostgreSQL. Hay que volver a probar `./dev.sh` cuando se corrija DEF-01 y cerrarlo o reabrirlo.

### DEF-03 · Mayor · La prueba de integración quedó desactualizada con los emails nuevos

- **Dónde:** `scripts/pruebas/escenarios.mjs` contra `f655139` con la corrección de DEF-01.
- **Obtenido:** fallan los escenarios 5, 19 y 20 (`cuentaNueva` no encuentra el código de verificación en el log)
  y una comprobación de los escenarios 7 y 9 (no encuentran la línea del aviso al comprador).
- **Causa:** el script busca en el log los textos de antes, "Código de verificación para …" y "Aviso al comprador
  …". Desde el push de emails, `CorreoAlLog` escribe "Email para <email> — Tu código de Palco: … tu código para
  verificar el email es 034359. …".
- **Corrección propuesta:** en `cuentaNueva`, buscar `'Email para', email` y sacar el código con
  `/es (\d{6})\./`; en los escenarios 7 y 9, buscar la línea nueva del aviso. Es el script de quien desarrolla los
  emails, así que se deja anotado y no se cambió acá.

### DEF-04 · Menor (a confirmar) · El registro de la puerta sale en el orden inverso

- **Dónde:** escenario 16, sobre `f655139` con la corrección de DEF-01 y PostgreSQL.
- **Esperado:** `GET /api/v1/validacion/escaneos?eventoId=` del más nuevo al más viejo: `YA_USADA`, `VALIDA`.
- **Obtenido:** `VALIDA`, `YA_USADA`.
- **Contexto:** el mismo escenario pasa sobre `a7d3d54` con la misma base, así que es nuevo en `f655139` (el push
  cambió Validación con las puertas asignadas). Puede ser un orden por un campo que empata cuando los dos
  escaneos caen en el mismo milisegundo. Hay que revisarlo.

## 3. gitleaks (INF-01)

- `gitleaks 8.28.0`, `gitleaks git --log-opts="--all"` sobre un clon completo (no superficial): **56 commits de
  todas las ramas, 0 hallazgos**.
- El job `secretos` del CI repite el control en cada push y cada pull request, sobre todo el historial
  (`fetch-depth: 0`).
- Antes de commitear la evidencia de esta rama se revisó con `gitleaks dir docs/testing`: 0 hallazgos.

## 4. Un solo comando (INF-02 a INF-06)

### Qué se armó

| Archivo | Qué hace |
|---|---|
| `docker-compose.yml` | `db` (PostgreSQL 17), `backend` y `web`. Cada uno arranca cuando el anterior está sano |
| `backend/Dockerfile` | Etapa Maven con JDK 25 (`mvn install -DskipTests`, con caché de `~/.m2`) y una imagen final con JRE 25 y usuario sin privilegios |
| `backend/docker/arrancar.sh` | Genera `PALCO_SECRETO_SERVICIOS` si no viene definido y arranca WildFly escuchando fuera del contenedor |
| `backend/docker/salud.sh` | Healthcheck: los 8 `/health` tienen que responder 200 |
| `frontend/Dockerfile` | Build de Vite y nginx |
| `frontend/docker/nginx.conf` | 80 redirige con 301 a 443, TLS 1.2 y 1.3, CSP, HSTS, `nosniff`, `Referrer-Policy`, `X-Frame-Options` y `frame-ancestors 'none'`; proxy de `/api` y `/legado` |
| `frontend/docker/certificado.sh` | Certificado autofirmado generado al arrancar (no está en el repo ni en la imagen) |
| `scripts/qa/jboss-cli-docker.sh` | `jboss-cli` dentro del contenedor, para que `escenarios.mjs` corra contra el compose |

Ningún secreto queda en el repo. La contraseña de la base tiene un valor por defecto solo para uso local, y la base
no se publica fuera de la red de compose. El secreto entre servicios y la clave JWT se generan en cada arranque.

### Mediciones (30/10, contenedor Linux de 4 núcleos, sin caché de Maven)

| Paso | Tiempo |
|---|---|
| Construir la imagen del backend (Maven baja todo y arma WildFly) | 112 s |
| Construir la imagen web (`npm ci` y build) | 30 s |
| `docker compose up --wait` hasta que los 3 contenedores están sanos | 28 s |
| **Total, sin contar la descarga de las imágenes base (alrededor de 1,9 GB)** | **unos 3 min** |

Falta INF-06 tal como lo pide el plan: clonar en una máquina de alguien del grupo, sin nada del proyecto, cronometrar
`docker compose up --build` dos veces y anotar el tiempo. Con una conexión de 50 Mbps, la descarga de las imágenes
agrega unos 5 minutos: hay margen para los 15.

### Prueba de humo por HTTPS (con el compose levantado)

- `https://localhost/` responde 200 por HTTP/2 con las 6 cabeceras de seguridad.
- `http://localhost/mis-entradas` responde 301 y redirige a `https://localhost/mis-entradas`.
- Los 8 `/health` responden 200 a través de nginx, y el WSDL del ARCA (`/legado/arca?wsdl`) también.
- En el navegador (Playwright): el login de `titular@palco.test` entra al catálogo, sin errores en la consola ni
  violaciones de la CSP.

### Prueba de integración contra el compose

`scripts/pruebas/escenarios.mjs` corrió completo contra el compose (PostgreSQL, nginx y el broker embebido), sin
cambios en el script:

| Código | Resultado | Tiempo |
|---|---|---|
| `a7d3d54` (último master que despliega) con la infraestructura de esta rama | **21 de 21 escenarios, 293 de 293 comprobaciones** | 714 s |
| `f655139` (master de hoy) con la corrección de DEF-01 | 15 de 21 escenarios; fallan 6 comprobaciones (DEF-03 y DEF-04) | 442 s |

Control de logs después de la corrida: 0 tokens JWT ni `Bearer` y 0 contraseñas en las 2.589 líneas de
`server.log`.

La salida completa está en `docs/testing/evidencia/`.

## 5. Pipeline de CI (INF-07)

`.github/workflows/ci.yml` corre en cada push a `master`, en cada pull request y en los tags `v*`:

| Job | Qué hace | Corta si… | Medido localmente |
|---|---|---|---|
| `secretos` | gitleaks sobre todo el historial | Hay un hallazgo | menos de 5 s |
| `backend` | `mvn -B verify` con JDK 25; sube los reportes de surefire | Falla un test | 148 s, 761 tests en verde sobre `f655139` |
| `frontend` | `npm ci`, lint y build | Hay un error | menos de 30 s; 0 errores y 3 warnings de lint |
| `integracion` | `docker compose up --build --wait`, humo por HTTPS con las cabeceras, los 8 health, `escenarios.mjs` y el control de logs | Falla cualquier paso | 3 min de levantar y 12 min de escenarios |

- Los secretos del CI se generan en cada corrida. No hace falta configurar nada en GitHub.
- Los jobs de los otros frentes (JaCoCo, PIT, Sonar, Trivy, Playwright, ZAP) se agregan como jobs nuevos en el mismo
  archivo, sin tocar estos.
- **Hasta que se corrija DEF-01, `integracion` va a quedar en rojo, y está bien que así sea:** es la condición
  que tiene que estar en verde para el tag.
- El total de reloj está cerca de los 15 minutos por el job `integracion` (la prueba espera a la conciliación de
  pagos). Si se pasa, conviene partirla en dos jobs paralelos con `node scripts/pruebas/escenarios.mjs 1 2 3 …`.

## 6. Historias de usuario y trazabilidad (ATR-01, ATR-02 y DOC-02)

- `docs/documentacion/historias-de-usuario.md`: 14 historias (8 Must, 5 Should y 1 Could) y 61 criterios en
  Gherkin con códigos, estados y números. Cada historia que integra con otro sistema tiene su criterio de falla
  externa (Pagos, Eventos, ARCA y Ventas). Won't: identidad con foto del DNI y pasarela real.
- Convención `HU-xx` / `CA-xx.y` / `CP-xx.y.z` con ejemplos para JUnit, Postman y Playwright.
- `scripts/qa/matriz.mjs` arma la matriz a partir de los reportes (JUnit XML, Newman, Playwright y la salida de
  `escenarios.mjs`). Hoy: **22 de 61 criterios pasan, 0 fallan y 39 no tienen caso**. Esos 39 son el trabajo de
  casos que queda (sobre todo registro, login, catálogo y los criterios de seguridad de F4).

```bash
node scripts/qa/matriz.mjs --escenarios salida-escenarios.txt --junit backend --salida matriz.md
```

## 7. Cómo se cuidó no pisar el trabajo en curso

- La rama sale de `master` después del push del 30/10.
- Casi todo son **archivos nuevos**: `docker-compose.yml`, los Dockerfiles, `backend/docker/`, `frontend/docker/`,
  `.github/workflows/ci.yml`, `scripts/qa/`, `docs/testing/` y `docs/documentacion/historias-de-usuario.md`.
- Hay un solo archivo existente modificado: `README.md`, con una sección nueva agregada dentro de "Cómo correrlo".
  No toca ninguna línea existente.
- No se tocó código del backend ni del frontend, ni `escenarios.mjs`. La corrección de DEF-01 queda propuesta
  para que la aplique quien está trabajando en esa parte.

## 8. Qué sigue

1. Aplicar la corrección de DEF-01 en `master`, actualizar `escenarios.mjs` (DEF-03), revisar DEF-04, y cargar los
   cuatro defectos en la base "Defectos" de Notion (G9).
2. Mergear esta rama con el CI en verde y guardar el link de esa corrida.
3. INF-06: medir `docker compose up --build` dos veces en una máquina limpia y anotar los tiempos acá.
4. Escribir los casos que faltan (los 39 criterios sin caso) con la convención `CP-xx.y.z`.
5. Decidir D-2 (broker en su propio contenedor). El compose ya tiene lugar para un servicio `broker`.
