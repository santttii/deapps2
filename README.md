# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping**: entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre el precio original de la entrada.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. Todo lo que muestra sale del backend: el
  ingreso, el registro y la recuperación de la contraseña, el catálogo, la
  compra, Mis entradas, la reventa, la puerta, el panel del organizador y el
  asistente. En el navegador solo quedan la sesión (el token), el carrito de la
  compra en curso y el tema elegido.
- **`backend/`**: Jakarta EE sobre WildFly. Son 9 servicios independientes (un
  WAR cada uno) desplegados en el mismo WildFly, con el broker JMS (ActiveMQ
  Artemis) que trae el servidor: Usuarios; Eventos (también la edición y la
  cancelación); Ventas (compra con reserva de cupo, pago y emisión asincrónica
  de las entradas); Pagos (con una pasarela simulada); Reventa (publica y vende
  con tope, con su conciliación de pagos); Validación (el control de ingreso en
  la puerta); Notificaciones (emails y auditoría, por tópico); Facturación
  (factura cada compra confirmada pidiéndole el CAE por SOAP a un ARCA
  simulado, que se despliega aparte en `/legado/arca`), y el Asistente (la IA,
  detrás de su gateway). Los servicios se hablan
  solo por REST, SOAP o JMS. El estado de cada fase está en [`TODO.md`](TODO.md), y el detalle, en
  [`docs/documentacion/Palco_Documentacion_Completa.md`](docs/documentacion/Palco_Documentacion_Completa.md).

Los documentos de la Entrega Final:

- [Documentación final](docs/final/documentacion-final.md) y
  [declaración de uso de IA](docs/final/declaracion-uso-ia.md).
- [Manual de usuario](docs/manual/manual-de-usuario.md).
- Decisiones de arquitectura: [`docs/adr/`](docs/adr/).
- Pruebas: [plan](docs/testing/plan-de-pruebas.md), [especificación de casos](docs/testing/especificacion-de-casos.md),
  [matriz de trazabilidad](docs/testing/evidencia/matriz-de-trazabilidad.md),
  [informe de ejecución](docs/testing/informe-de-ejecucion.md), [registro de defectos](docs/testing/registro-de-defectos.md)
  e [informe de seguridad](docs/testing/informe-de-seguridad.md).
- API: [`docs/api/`](docs/api/), con la OpenAPI, el WSDL, los contratos de mensajería y la colección de Postman.
- Tablero del grupo en Notion: [Tablero de Tareas D Apps 2](https://www.notion.so/3caf71443ad4807d8749c3543f74c075).

## Levantar todo desde cero

Con Docker alcanza: el sistema completo (la app, la base PostgreSQL, el broker JMS embebido en WildFly, el ARCA
simulado por SOAP y la pasarela de pagos simulada) levanta con un solo comando, sin instalar Java, Maven ni Node y
sin configurar nada.

### Qué hace falta

- **Docker con Compose v2 o más nuevo.** El comando es `docker compose`, con espacio (el viejo `docker-compose`, la
  v1, no sirve); `docker compose version` dice cuál está instalada.
  - **Windows 10 u 11:** Docker Desktop con el motor WSL 2, que es el que propone al instalarse (puede pedir
    reiniciar y que la virtualización esté activada en el BIOS), y en modo contenedores Linux, que es el de fábrica.
  - **macOS** (Intel o Apple Silicon): Docker Desktop.
  - **Linux:** Docker Engine con el plugin de Compose (`docker-compose-plugin`), como lo instala la guía oficial
    de Docker.
  - Docker tiene que estar corriendo antes del comando: en Windows y macOS, con Docker Desktop abierto.
- **Git**, para clonar.
- **Memoria y disco:** conviene una máquina con 8 GB de RAM, para que Docker tenga unos 4, y unos 10 GB libres en
  disco: la primera vez baja las imágenes base y arma dos imágenes propias (la del backend ocupa unos 860 MB y la
  web, unos 110 MB).
- **Internet**, la primera vez: baja las imágenes de Docker Hub y las dependencias de Maven y de npm.
- **Los puertos 80, 443 y 8080 libres.** Si alguno está ocupado, ver [Si algo traba](#si-algo-traba).

### El comando

En cualquier terminal (PowerShell, cmd, la Terminal de macOS o Bash: los comandos son los mismos):

```
git clone https://github.com/santttii/deapps2.git palco
cd palco
docker compose up --build --wait
```

Con una copia ya descargada, alcanza con abrir la terminal en la carpeta donde está `docker-compose.yml` y correr
el último comando. Con `--wait`, el comando termina recién cuando los tres contenedores están sanos y deja la
terminal libre: los contenedores siguen andando de fondo. Sin `--wait` se queda mostrando el log, y `Ctrl+C` los
detiene.

No hay que crear ningún `.env` ni definir secretos. El secreto entre servicios y la clave que firma las sesiones no
están en el repo: el backend los genera en cada arranque, y el log lo avisa ("PALCO_SECRETO_SERVICIOS no vino en el
entorno: se generó uno nuevo para este arranque."). Por eso, después de reiniciar el backend, las sesiones abiertas
se cierran y hay que volver a entrar.

### El `.env` de la raíz (opcional)

`docker compose` toma estas variables del entorno o de un archivo `.env` en la carpeta de `docker-compose.yml`. Ese
archivo no se sube al repo (lo ignora `.gitignore`) y sin él todo anda con los valores de la segunda columna:

| Variable | Sin definirla | Para qué sirve |
|---|---|---|
| `PALCO_SECRETO_SERVICIOS` | El backend genera uno en cada arranque (`backend/docker/arrancar.sh`) | El secreto con el que los servicios piden el token de rol `servicio`. Hace falta definirlo solo para correr `scripts/pruebas` contra el compose (ver [Cómo correrlo](#todo-junto-con-un-solo-comando-docker)) |
| `PALCO_JWT_CLAVE_PRIVADA` | Usuarios genera una al arrancar, y al reiniciar se cierran las sesiones abiertas | La clave que firma las sesiones (cómo generarla, en `backend/.env.example`) |
| `PALCO_DB_PASSWORD` | `palco-local` | La contraseña de la base del compose, que no se publica fuera de su red |
| `PALCO_PUERTO_HTTP`, `PALCO_PUERTO_HTTPS`, `PALCO_URL_PUBLICA` y `PALCO_PUERTO_BACKEND` | `80`, `443`, `https://localhost` y `8080` | Otros puertos (ver [Si algo traba](#si-algo-traba)) |
| `PALCO_CORS_ORIGENES` | La URL pública (`https://localhost`) | Los orígenes que pueden llamar a la API. Para usar `npm run dev` contra el compose: `https://localhost,http://localhost:5173` (DEF-42) |
| `PALCO_CORREO_LOG_COMPLETO` | `false`: de cada email, el log tiene solo el destinatario enmascarado y el asunto | Con `true`, cada email entero en el log, con su código. El compose no tiene SMTP, así que hace falta para registrar una cuenta nueva |
| `PALCO_DIR_LOGS` | Un volumen de Docker | Con `./logs`, el `server.log` queda en esa carpeta (lo leen `scripts/pruebas` y las pruebas E2E) |
| `PALCO_ASISTENTE_MODELO`, `PALCO_ASISTENTE_OPENAI_URL`, `PALCO_ASISTENTE_OPENAI_MODELO` y `PALCO_ASISTENTE_OPENAI_CLAVE` | El modelo simulado del asistente | Un modelo real, de cualquier proveedor compatible con la API de chat de OpenAI (abajo) |

**El asistente con un modelo real.** Con estas líneas en el `.env` de la raíz y otro `docker compose up --build --wait`,
el asistente le pregunta al proveedor en lugar de al modelo simulado. La clave es secreta: va solo en ese `.env`, que
no se sube al repo, y el backend no la escribe en el log.

```
PALCO_ASISTENTE_MODELO=openai
PALCO_ASISTENTE_OPENAI_URL=https://api.openai.com/v1
PALCO_ASISTENTE_OPENAI_MODELO=gpt-4o-mini
PALCO_ASISTENTE_OPENAI_CLAVE=<la clave del proveedor>
```

Sin la URL ni el modelo, usa esos dos valores. Sirve cualquier proveedor con esa API (OpenAI, Gemini, Groq u Ollama)
cambiando la URL y el modelo. Si el proveedor no responde en 5 segundos o falla, el asistente contesta con las
preguntas frecuentes.

### Cuánto tarda

La primera vez baja las imágenes base (PostgreSQL, Maven con el JDK 25, el JRE 25, Node y nginx) y arma las dos
imágenes de Palco, con las dependencias de Maven y WildFly. Medido el 25/11/2026 sobre `93d34d2`, en un clon nuevo y
sin ninguna imagen ni caché de Docker (Windows 11 con Docker Desktop, 12 núcleos): `docker compose up --build --wait`
terminó en 172 s, con la descarga incluida, y los tres contenedores sanos. La descarga depende de la conexión. Las
siguientes veces las imágenes ya están armadas y es solo el arranque. El CI lo levanta igual, de cero, en cada corrida ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

### Cómo saber que está listo

El comando termina con una línea `Healthy` por contenedor (`palco-db-1`, `palco-backend-1` y `palco-web-1`), y
`docker compose ps` los muestra `(healthy)`. El backend queda sano recién cuando los 9 servicios responden 200 en su
`/health` ([`backend/docker/salud.sh`](backend/docker/salud.sh)). Si algo falla, el comando termina con un error
que nombra el contenedor, y `docker compose logs backend` (o `db`, o `web`) muestra por qué.

### Entrar

Abrir **https://localhost**, así, con `localhost`: con `127.0.0.1` la app no puede llamar a la API.
`http://localhost` redirige a HTTPS. El certificado es autofirmado y lo genera el contenedor `web` al arrancar, así
que el navegador avisa que la conexión no es privada y hay que aceptarlo una vez: en Chrome y Edge, "Configuración
avanzada" (o "Avanzado") y el enlace para seguir a localhost; en Firefox, "Avanzado…" y "Aceptar el riesgo y
continuar"; en Safari, "Mostrar detalles" y "visitar este sitio web". Si lo pide otra vez después de un
`docker compose down` y otro `up`, es porque el contenedor nuevo generó otro certificado.

Se ve el catálogo con los seis eventos de prueba. También quedan:

| Qué | Dónde |
|---|---|
| El estado de un servicio | https://localhost/api/eventos/health (cada servicio responde en `<su raíz>/health`, ver [Backend](#backend)) |
| La API directa, solo desde esta máquina (Postman) | http://localhost:8080 |
| El WSDL del ARCA simulado, solo desde esta máquina | http://localhost:8080/legado/arca?wsdl (nginx no lo publica: `https://localhost/legado/` responde 404, DEF-45) |

### Cuentas de prueba

Todas con la contraseña **`palco1234`** (con Docker, la pantalla de ingreso no la muestra: solo lo hace con
`npm run dev`):

| Email | Para probar |
|---|---|
| `titular@palco.test` | Comprar, Mis entradas, la reventa y el asistente |
| `otro.titular@palco.test` | Comprar en la reventa lo que publica `titular` |
| `organizador@palco.test` | El panel `/organizador` (es el dueño de los seis eventos) |
| `puerta@palco.test` | La validación en `/puerta` |

Las seis cuentas y qué puede hacer cada una están en [Usuarios de prueba](#usuarios-de-prueba), y un recorrido de
punta a punta, en [Recorrido sugerido de demo](#recorrido-sugerido-de-demo). Para pagar, cualquier tarjeta se
aprueba, salvo una terminada en `0002` (se rechaza) o en `0504` (la pasarela no responde).

### Bajarlo

| Comando | Qué hace |
|---|---|
| `docker compose down` | Baja los contenedores y guarda los datos (la base y los mensajes pendientes del broker) para el próximo `up` |
| `docker compose down -v` | Baja todo y borra los datos: el próximo `up` arranca de cero, con los datos de prueba |
| `docker compose down -v --rmi all` | Además borra las imágenes de Palco y la de PostgreSQL, para liberar el disco |

### Si algo traba

| Qué se ve | Qué hacer |
|---|---|
| `port is already allocated`, `ports are not available` o `address already in use` | El 80, el 443 o el 8080 está ocupado. Crear en la raíz del repo un archivo `.env` (no se sube al repo; en el Bloc de notas, con "Todos los archivos", para que no quede `.env.txt`) con las cuatro líneas de abajo y volver a correr `docker compose up --build --wait`. La app queda en https://localhost:8443 y la API directa, en http://localhost:8081. Sirve igual en cualquier sistema y terminal, y también en Linux con Docker sin root, que no puede abrir puertos por debajo del 1024 |
| `'compose' is not a docker command` o `unknown flag: --wait` | Falta el plugin de Compose o es una versión vieja: actualizar Docker Desktop o instalar el plugin (ver [Qué hace falta](#qué-hace-falta)) |
| `Cannot connect to the Docker daemon` o `error during connect` | Docker no está corriendo: abrir Docker Desktop (en Linux, `sudo systemctl start docker`), esperar a que diga que está andando y repetir |
| `permission denied` sobre `docker.sock` | En Linux: anteponer `sudo` o agregar el usuario al grupo `docker` y volver a iniciar sesión |
| `toomanyrequests` al bajar las imágenes | Es el límite de descargas anónimas de Docker Hub, que se nota en redes compartidas: `docker login` con una cuenta gratuita y repetir |
| Un contenedor `unhealthy` o `dependency failed to start` | `docker compose logs backend` (o el contenedor que nombre el error) muestra por qué |

```
PALCO_PUERTO_HTTP=8000
PALCO_PUERTO_HTTPS=8443
PALCO_URL_PUBLICA=https://localhost:8443
PALCO_PUERTO_BACKEND=8081
```

## Índice

- [Levantar todo desde cero](#levantar-todo-desde-cero)
- [Requisitos](#requisitos)
- [Cómo correrlo](#cómo-correrlo)
- [Scripts del frontend](#scripts-del-frontend)
- [Usuarios de prueba](#usuarios-de-prueba)
- [Funcionalidades](#funcionalidades)
- [Reglas de negocio](#reglas-de-negocio)
- [Recorrido sugerido de demo](#recorrido-sugerido-de-demo)
- [Tests](#tests)
- [Stack](#stack)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Arquitectura y estado](#arquitectura-y-estado)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Decisiones de alcance](#decisiones-de-alcance)

## Requisitos

Para levantar todo con Docker no hace falta nada de esto: ver
[Levantar todo desde cero](#levantar-todo-desde-cero). Lo de abajo es para desarrollar sin Docker.

- Frontend: Node.js `^20.19.0` o `>=22.12.0` (lo exige Vite 8) y npm
- Backend: JDK 25 y Maven 3.9+ (en macOS: `brew install openjdk@25 maven`; en
  Windows, cualquier JDK 25, por ejemplo Temurin, con `JAVA_HOME` apuntando a
  él). WildFly no se instala: lo descarga Maven.
- En Windows, los guiones (`backend/dev.sh`, `scripts/`, `docs/entrega3/evidencia/`)
  se corren desde **Git Bash**.

## Cómo correrlo

### Todo junto, con un solo comando (Docker)

Los requisitos, el comando, cuánto tarda, las cuentas de prueba y cómo bajarlo están en
[Levantar todo desde cero](#levantar-todo-desde-cero).

Levanta tres contenedores: `db` (PostgreSQL 17), `backend` (WildFly con los 9 servicios, el broker y el ARCA
simulado) y `web` (nginx con el frontend, TLS y las cabeceras de seguridad, que pasa `/api` al backend). El ARCA
simulado no se publica por nginx: Facturación lo llama por la red interna. La API también queda en
`http://localhost:8080`, solo para esta máquina (Postman, `scripts/pruebas` y el WSDL del ARCA simulado). Ningún
secreto está en el repo: el secreto entre servicios y la clave de los tokens se generan en cada arranque si no se
definen (ver [El `.env` de la raíz](#el-env-de-la-raíz-opcional)).

| Para | Comando |
|---|---|
| Bajar todo | `docker compose down` (con `-v` borra también la base y los mensajes que el broker tenía guardados) |
| Ver el log del servidor | `docker compose logs -f backend` |
| Usar otros puertos (si el 80, el 443 o el 8080 están ocupados) | Un `.env` en la raíz con `PALCO_PUERTO_HTTP`, `PALCO_PUERTO_HTTPS`, `PALCO_URL_PUBLICA` y `PALCO_PUERTO_BACKEND` (ver [Si algo traba](#si-algo-traba)) |
| Registrar cuentas nuevas sin SMTP (el código de verificación queda en el log) | `PALCO_CORREO_LOG_COMPLETO=true` en el `.env` de la raíz, o en el entorno, antes del `up` (ver "Emails" en [API disponible](#api-disponible)) |

Para correr la prueba de integración contra el compose (en Bash, o en Git Bash en Windows, y con Node 18 o más
nuevo), el secreto entre servicios se define antes del `up`: los guiones lo necesitan para pedir el token de rol
`servicio`, y el que genera el backend no se muestra. También va `PALCO_CORREO_LOG_COMPLETO=true`: los escenarios
leen del log el código de las cuentas nuevas y los avisos por email, y el compose, sin esa variable, escribe de cada
email solo el destinatario enmascarado y el asunto.

```bash
export PALCO_SECRETO_SERVICIOS="$(openssl rand -hex 32)"
export PALCO_DIR_LOGS=./logs PALCO_LOG=logs/server.log JBOSS_CLI=scripts/qa/jboss-cli-docker.sh
export PALCO_CORREO_LOG_COMPLETO=true  # cada email entero en el log, con su código
mkdir -p logs && chmod 777 logs        # el contenedor escribe server.log en esa carpeta
docker compose up --build --wait
node scripts/pruebas/escenarios.mjs
```

Los demás guiones y las pruebas de la interfaz, en "Cómo repetirlas contra el compose" de
[`docs/testing/plan-de-pruebas.md`](docs/testing/plan-de-pruebas.md).

Para desarrollar conviene lo de abajo (`npm run dev` y `./dev.sh`): recargan solos al cambiar el código.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

La app queda en `http://localhost:5173`. Para ingresar y ver el catálogo
necesita el backend levantado (ver abajo): sin backend, el catálogo muestra un
error con el botón "Reintentar".

- **URL del backend:** por defecto `http://localhost:8080`. Para cambiarla,
  copiar `frontend/.env.example` como `frontend/.env`, editar `VITE_API_URL` y
  reiniciar `npm run dev`.
- **Origen:** hay que abrir la app en `http://localhost:5173` tal cual. El
  backend solo acepta pedidos desde los orígenes de `PALCO_CORS_ORIGENES` (con
  `./dev.sh`, `http://localhost:5173` si no se define otra cosa), así que con
  `127.0.0.1:5173` o con `npm run preview` (puerto 4173) las llamadas fallan.
- **Contra el backend del compose:** el compose acepta solo la URL pública
  (DEF-42). Para usar `npm run dev` contra él, se lo levanta con
  `PALCO_CORS_ORIGENES=https://localhost,http://localhost:5173` en el `.env` de
  la raíz.

### Backend

```bash
cd backend
cp .env.example .env   # la primera vez
./dev.sh
```

La primera vez tarda unos minutos porque descarga WildFly 41. Queda en
`http://localhost:8080`. Usuarios, Eventos y Notificaciones van en
`/api/<servicio>`; Ventas, en `/api/v1`; Pagos, en `/api/v1/pagos`; Reventa, en
`/api/v1/reventas`; Validación, en `/api/v1/validacion`; Facturación, en
`/api/v1/facturas`, y el Asistente, en `/api/v1/asistente`. Para comprobar que anda: `curl localhost:8080/api/eventos/health`
(cada servicio responde `<su raíz>/health`: para Ventas, `/api/v1/health`; para
Pagos, `/api/v1/pagos/health`, y así). El ARCA simulado publica su WSDL en
`http://localhost:8080/legado/arca?wsdl` (copia en [`docs/api/arca.wsdl`](docs/api/arca.wsdl)).

En Windows, desde Git Bash y con un JDK 25:

```bash
export JAVA_HOME="/c/Users/<usuario>/.jdks/temurin-25"   # o donde esté el JDK 25
cd backend && ./dev.sh
```

**Si cambia la configuración del servidor** (las capas de WildFly en
`backend/servidor/pom.xml` o `backend/servidor/src/main/wildfly/configurar.cli`,
por ejemplo después de un `git pull`), hay que borrar `backend/servidor/target`
antes de correr `./dev.sh`, para que el servidor se vuelva a armar con esos
cambios:

```bash
cd backend && rm -rf servidor/target && ./dev.sh
```

Sin configurar nada usa **H2 en memoria**: la base se borra al reiniciar y las
migraciones vuelven a cargar los datos de prueba. Para usar un **PostgreSQL**
(uno propio o Supabase; el del compose no se publica fuera de su red),
completar las variables `PALCO_DB_*` del `.env` y volver a correr `./dev.sh`:
las mismas migraciones crean las tablas, y son las que corren contra el
PostgreSQL 17 del compose y del CI.

**Asistente con IA** (`/api/v1/asistente`). Sin configurar nada responde con el
modelo simulado. Para usar un modelo real basta un proveedor con la API de chat
de OpenAI (OpenAI, Gemini, Groq u Ollama local): en el `.env` del backend, o en
el `.env` de la raíz para Docker ([ejemplo](#el-env-de-la-raíz-opcional)), se
ponen `PALCO_ASISTENTE_MODELO=openai`, `PALCO_ASISTENTE_OPENAI_URL`,
`PALCO_ASISTENTE_OPENAI_MODELO` y `PALCO_ASISTENTE_OPENAI_CLAVE` (ver
`backend/.env.example`). Ninguno de los dos `.env` se sube al repo. El gateway le da
5 segundos al modelo; si no responde o falla, contesta con las preguntas
frecuentes.

| Comando (desde `backend/`) | Qué hace |
|---|---|
| `./dev.sh` | Compila todo, arma WildFly con los 9 servicios y lo levanta |
| `./redesplegar.sh eventos` | Recompila un servicio y lo redespliega sin reiniciar |
| `mvn test` | Corre los tests (ver [Tests](#tests)) |
| `mvn clean install` | Arma todo; el server queda en `servidor/target/server`, como el que arma la imagen del compose (`backend/Dockerfile`, que lo arma sin correr los tests) |

### API disponible

El contrato completo, con los cuerpos, los roles y cada error, está en
[`docs/api/openapi.yaml`](docs/api/openapi.yaml) (OpenAPI 3.1). Esta tabla es el
resumen. "Rol" es lo que pide el token: `user`, `organizer`, `staff` o
`servicio` (llamadas entre servicios); "pública" es sin token.

| Servicio | Endpoint | Rol | Qué hace |
|---|---|---|---|
| todos | `GET <raíz del servicio>/health` (por ejemplo `/api/eventos/health` o `/api/v1/health`) | pública | Estado del servicio y de la base |
| Usuarios | `POST /api/usuarios/registro` | pública | Alta de cuenta; manda un código de 6 dígitos |
| Usuarios | `POST /api/usuarios/verificacion` | pública | `{"email", "codigo"}` → activa la cuenta y devuelve la sesión |
| Usuarios | `POST /api/usuarios/verificacion/reenvio` | pública | Código nuevo (uno cada 30 segundos) |
| Usuarios | `POST /api/usuarios/password/olvide` | pública | `{"email"}` → manda un código para poner una contraseña nueva; responde 204 exista o no la cuenta |
| Usuarios | `POST /api/usuarios/password/reinicio` | pública | `{"email", "codigo", "password"}` → cambia la contraseña: 204 |
| Usuarios | `POST /api/usuarios/login` | pública | `{"email", "password"}` → `{"token", "usuario", ...}` |
| Usuarios | `GET /api/usuarios/yo` | `user`, `organizer`, `staff` | Usuario del token |
| Usuarios | `GET /api/usuarios/{usuarioId}` | `servicio` | Un usuario por su id (lo usa Notificaciones para el email del comprador) |
| Usuarios | `GET /api/usuarios/cuentas?email=` | `servicio` | El rol de una cuenta verificada (lo usa Validación: la puerta solo se asigna a cuentas `staff`) |
| Usuarios | `POST /api/usuarios/servicios/token` | pública, con secreto | `{"secreto"}` → token de rol `servicio` para llamar a otros servicios |
| Usuarios | `GET /api/usuarios/jwks` | pública | Clave pública para validar tokens (la usan los otros servicios) |
| Eventos | `GET /api/eventos?categoria=Música&q=texto` | pública | Catálogo sin los eventos cancelados (filtros opcionales, búsqueda sin acentos) |
| Eventos | `GET /api/eventos/mios` | `organizer` | Los eventos que creó quien pregunta, por fecha, también los cancelados (los usa el panel) |
| Eventos | `GET /api/eventos/{slug}` | pública | Detalle con sectores; de un evento cancelado, con `cancelado` y el motivo |
| Eventos | `POST /api/eventos` | `organizer` | Crear evento con sectores |
| Eventos | `PUT /api/eventos/{eventoId}` | `organizer` | Editar un evento propio (reemplazo completo; un sector con ventas no cambia de precio ni se quita) |
| Eventos | `POST /api/eventos/{eventoId}/cancelacion` | `organizer` | Cancelar un evento propio con `{"motivo"}` opcional: publica `EVENTO_CANCELADO`, y Ventas anula y reembolsa sus compras |
| Eventos | `POST /api/eventos/imagenes` | `organizer` | Sube la foto (JPG, PNG o WEBP, hasta 2 MB) y devuelve su URL: a Supabase Storage si está configurado y, si no, a la base de Palco (`PALCO_IMAGENES`: `supabase`, `base` o `ninguno`) |
| Eventos | `GET /api/eventos/imagenes/{id}` | pública | La foto de un evento guardada en la base, con caché de un año |
| Eventos | `GET /api/eventos/{eventoId}/organizador` | `servicio` | El dueño de un evento (lo usan Ventas y Validación) |
| Eventos | `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo` | `servicio` | Sumar (`{"cantidad": 2}`) o liberar (`-2`) entradas vendidas, sin pasarse del cupo |
| Ventas | `POST /api/v1/ventas` | `user` | Abre una compra y reserva el cupo por 10 minutos: 201, venta `EN_CHECKOUT`. Pide la cabecera `Idempotency-Key` (un UUID) |
| Ventas | `GET /api/v1/ventas` | `user` | Las compras de quien pregunta, de la más nueva a la más vieja, con sus entradas |
| Ventas | `GET /api/v1/ventas/{ventaId}` | `user` | Una venta, solo para quien la compró |
| Ventas | `POST /api/v1/ventas/{ventaId}/pagos` | `user` | Paga la compra (cobra en Pagos): 201, venta `CONFIRMADA` con las entradas `PENDIENTE_EMISION`; un rechazo responde 402. Pide `Idempotency-Key` |
| Ventas | `DELETE /api/v1/ventas/{ventaId}` | `user` | Cancela una compra sin pagar y devuelve el cupo: 204 |
| Ventas | `GET /api/v1/entradas/{entradaId}` | `user` | Una entrada: estado, titular y, ya emitida, `codigoQr` |
| Ventas | `GET /api/v1/resumenes?eventoId=` | `organizer` | Ventas por día y liquidación de un evento propio |
| Ventas | `POST /api/v1/entradas/usos` y `/api/v1/operaciones-de-reventa/*` | `servicio` | Usar una entrada en la puerta (la llama Validación) y publicarla, retirarla o transferirla (las llama Reventa) |
| Pagos | `POST /api/v1/pagos` | `servicio` | Cobra contra la pasarela y guarda el pago (lo llaman Ventas y Reventa) |
| Pagos | `GET /api/v1/pagos/{pagoId}` y `GET /api/v1/pagos?claveIdempotencia=` | `servicio` | Un pago guardado, por id o por la clave con que se pidió (la usan las conciliaciones) |
| Pagos | `POST /api/v1/pagos/{pagoId}/reembolso` | `servicio` | Devuelve un pago aprobado, una sola vez |
| Reventa | `GET /api/v1/reventas?eventoId=` | pública | Publicaciones activas de un evento |
| Reventa | `GET /api/v1/reventas/mias` | `user` | Las publicaciones de quien pregunta, en todos sus estados |
| Reventa | `POST /api/v1/reventas` | `user` | Publica una entrada propia `VALIDA`, con el precio entre el 50 % y el 110 % del precio de lista del sector |
| Reventa | `DELETE /api/v1/reventas/{publicacionId}` | `user` | Retira una publicación propia; la entrada vuelve a `VALIDA` |
| Reventa | `POST /api/v1/reventas/{publicacionId}/compra` | `user` | Compra una publicación: cobra y transfiere (entrada nueva para el comprador). Pide `Idempotency-Key` |
| Validación | `POST /api/v1/validacion/escaneos` | `staff` | Escanea un código en la puerta de un evento asignado: `VALIDA`, `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO` o `NO_HABILITADA` (504 y `SIN_RESPUESTA` si Ventas no contesta) |
| Validación | `GET /api/v1/validacion/escaneos?eventoId=` y `/escaneos/{escaneoId}` | `staff`, `organizer` | El registro de la puerta de un evento (el código, recortado) |
| Validación | `GET /api/v1/validacion/puertas/mias` | `staff` | Los eventos donde la cuenta puede escanear |
| Validación | `GET /api/v1/validacion/puertas?eventoId=`, `PUT` y `DELETE /api/v1/validacion/puertas/{eventoId}/{email}` | `organizer` | El equipo de puerta de un evento propio: verlo, asignar una cuenta y quitarla |
| Notificaciones | `GET /api/notificaciones/auditoria` | `servicio` | Los últimos registros de auditoría de compras y de compras anuladas |
| Notificaciones | `POST /api/notificaciones/pruebas/pago-confirmado` | `servicio` | Publica un `PAGO_CONFIRMADO` de prueba |
| Notificaciones | `POST /api/notificaciones/pruebas/pago-confirmado-con-falla` | `servicio` | Publica y falla: la transacción se deshace y el mensaje no sale (409) |
| Notificaciones | `POST /api/notificaciones/pruebas/mensaje-invalido` | `servicio` | Publica un texto inválido, para ver los reintentos y la DLQ |
| Facturación | `GET /api/v1/facturas/{ventaId}` | `user`, `servicio` | La factura de una compra (número de comprobante y CAE). El comprador ve solo la suya; ajena, inexistente o sin factura emitida: 404; rechazada por ARCA: 409 `comprobante-rechazado` |
| Asistente | `POST /api/v1/asistente/preguntas` | `user`, `organizer`, `staff` | Una pregunta sobre Palco, por el gateway de IA: 200 con la `respuesta` y su `origen` (`MODELO`, `FALLBACK` o `BLOQUEADA`) |
| ARCA simulado | `POST /legado/arca` (SOAP, `FECAESolicitar`); WSDL en `GET /legado/arca?wsdl` | pública | Sistema externo simulado: da número y CAE. No es parte de la API de Palco. Con el compose responde solo en el puerto del backend (`http://localhost:8080`, en esta máquina): nginx no lo publica. El WSDL está copiado en [`docs/api/arca.wsdl`](docs/api/arca.wsdl) |

La pasarela de Pagos es simulada: el token de pago `tok_test` aprueba,
`tok_rechazado` rechaza y `tok_sin_respuesta` simula que la pasarela no
contesta. Hay dos procesos asincrónicos por JMS:

- **La compra.** Al pagar, Ventas publica `PAGO_CONFIRMADO` en la cola
  `cola.emision-entradas` (emite las entradas) y en el tópico
  `topico.compra-confirmada` (aviso, auditoría y factura). Contrato:
  [`docs/api/mensajeria-pago-confirmado.md`](docs/api/mensajeria-pago-confirmado.md).
- **La cancelación de un evento.** Eventos publica `EVENTO_CANCELADO` en
  `topico.eventos-cancelados`; Ventas anula cada compra pagada, pide su
  reembolso y publica un `COMPRA_ANULADA`, que avisa al comprador y queda en la
  auditoría. Contrato:
  [`docs/api/mensajeria-cancelacion-de-eventos.md`](docs/api/mensajeria-cancelacion-de-eventos.md).

**Errores:** todos los servicios responden en formato Problem Details (RFC 9457),
con `Content-Type: application/problem+json`. Los errores por campo van en
`errores`, una extensión propia que solo aparece si hay alguno:

```json
{"type":"/problemas/datos-invalidos","title":"Datos inválidos","status":400,
 "detail":"Hay datos inválidos.","instance":"/api/usuarios/login",
 "errores":{"password":"Revisá el email y la contraseña."}}
```

**Sesión:** el login devuelve un token JWT que se manda en cada pedido como
`Authorization: Bearer <token>` y dura 15 minutos. Cerrar sesión es descartarlo.
Sin token, los endpoints protegidos responden 401; con un rol que no
corresponde, 403. Las cuentas de prueba son las mismas de la tabla de abajo.

**Emails:** con un servidor SMTP configurado (`PALCO_SMTP_HOST` y las demás
variables `PALCO_SMTP_*` de `backend/.env.example`) salen de verdad. Sin él
quedan en el log del servidor. `backend/dev.sh` prende el modo de desarrollo
(`PALCO_CORREO_LOG_COMPLETO=true`), que escribe cada email entero en un renglón con el
formato `Email para <email> (<asunto>): <cuerpo>`. El `docker-compose.yml`, no: sin esa
variable, como en un despliegue, el renglón solo dice a quién iba, enmascarado
(`a***@ejemplo.com`), y el asunto, porque el cuerpo lleva nombres, titulares y códigos, que
no van a un log. Para registrar cuentas nuevas con el compose y sin SMTP, se lo levanta con
`PALCO_CORREO_LOG_COMPLETO=true` en el `.env` de la raíz o en el entorno, como hacen los
jobs del CI que leen el código del log. En el modo de desarrollo se lee, por ejemplo, el
código de verificación del registro:

```
Email para ana@ejemplo.com (Tu código de Palco): Hola Ana: tu código para verificar el email es 123456. Vence en 15 minutos.
```

El código para poner una contraseña nueva llega con el asunto "Volvé a poner tu
contraseña de Palco"; el aviso de compra, con "Tu compra en Palco está
confirmada", y el de un evento cancelado, con "Se canceló <evento>".

## Scripts del frontend

Desde `frontend/`:

| Comando | Qué hace |
|---|---|
| `npm run dev` | Servidor de desarrollo con HMR |
| `npm run build` | Chequeo de tipos (`tsc -b`) y build de producción en `dist/` |
| `npm run preview` | Sirve el build de `dist/` localmente |
| `npm run lint` | Lint con oxlint |
| `npm run e2e` | Pruebas de punta a punta con Playwright (`frontend/e2e/`) contra la app levantada; ver [Tests](#tests) |

## Usuarios de prueba

Todas las cuentas usan la contraseña `palco1234`. Las seis se cargan con las
migraciones de Usuarios, con el email y la identidad verificados. Cada una
tiene un solo rol: el organizador y la puerta no compran, porque Ventas y
Reventa piden el rol `user`. En producción se desactivan con
`PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false` (ver `backend/.env.example`).

| Email | Rol | Qué puede hacer |
|---|---|---|
| `titular@palco.test` | `user` | Comprar, ver sus entradas, y publicar y comprar en la reventa |
| `otro.titular@palco.test` | `user` | Lo mismo, como segundo comprador: compra en la reventa lo que publica `titular` |
| `organizador@palco.test` | `organizer` | `/organizador`: es el dueño de los seis eventos de prueba (`ev-1` a `ev-6`). Crea, edita y cancela sus eventos, ve sus números, las ventas por día y la liquidación, y asigna el personal de puerta |
| `otro.organizador@palco.test` | `organizer` | Organizador sin eventos: no ve ni puede tocar los de `organizador` (responden 404) |
| `puerta@palco.test` | `staff` | `/puerta` en los seis eventos de prueba y en los que le asigne su organizador |
| `otra.puerta@palco.test` | `staff` | Cuenta de puerta sin eventos asignados: escanear responde 403 hasta que un organizador la asigne |

También se pueden crear compradores desde `/registro`: el código de 6 dígitos
llega por email o, sin SMTP, queda en el log del servidor (ver "Emails" más
arriba; con Docker, el compose tiene que estar levantado con `PALCO_CORREO_LOG_COMPLETO=true`
y se busca "Email para" en `docker compose logs backend`). Con el email verificado, la
cuenta ya puede comprar.

## Funcionalidades

La columna "Datos" dice de qué servicio sale la información de cada pantalla.
Todo sale de la API: en el navegador solo quedan la sesión, el tema elegido y el
carrito de la compra en curso, que permite recargar el checkout sin perder la
reserva. Cada pantalla, menos el catálogo, se baja recién la primera vez que se
abre (`React.lazy` en `App.tsx`, DEF-49).

| Ruta | Pantalla | Acceso | Datos |
|---|---|---|---|
| `/` | Catálogo: evento destacado, filtros por categoría, buscador con demora e insensible a acentos, grilla de eventos, estados de carga, error y vacío, y franja de la reventa oficial | Pública | API (Eventos; la franja, de Reventa) |
| `/evento/:slug` | Detalle del evento: mapa de sectores, selección de cantidad por sector, resumen sticky con total (en pantalla angosta, el total y el botón van en una barra fija abajo) y las publicaciones de la reventa de ese evento | Pública | API (Eventos; las publicaciones, de Reventa) |
| `/checkout` | Checkout en 3 pasos: datos de cada titular, pago con la reserva hecha y su cuenta regresiva (pasarela, tarjeta o transferencia; se puede cancelar) y confirmación | Rol `user` | API (Ventas, que cobra en Pagos); el carrito en curso, del navegador |
| `/confirmacion/:ordenId` | Confirmación de compra: "emitiendo tus entradas…" hasta que termina la emisión, un QR por entrada, comprobante, imprimir, agregar al calendario | Rol `user` | API (Ventas) |
| `/mis-entradas` | Entradas con su QR, publicación y retiro en la reventa con validación de tope y mínimo, historial | Rol `user` | API (Ventas: `GET /api/v1/ventas`; Reventa: `GET /api/v1/reventas/mias`) |
| `/ingresar` | Login (en desarrollo, con las credenciales de prueba a la vista) | Pública | API (Usuarios) |
| `/registro` | Alta de cuenta en 2 pasos: datos y verificación de email con código | Pública | API (Usuarios) |
| `/recuperar` | Recuperar la contraseña en 2 pasos: el email y el código de 6 dígitos con la contraseña nueva (desde "¿Olvidaste tu contraseña?" en el ingreso) | Pública | API (Usuarios) |
| `/asistente` | Asistente con IA: preguntas sobre Palco, con el origen de cada respuesta | Con sesión (cualquier rol) | API (Asistente) |
| `/reventa` | Reventa oficial: filtros por evento y sector, orden por precio, listado con el tope de cada publicación y compra con confirmación | Pública (comprar requiere login) | API (Reventa, que cobra en Pagos) |
| `/organizador` | Panel del organizador: entradas vendidas, recaudado sin el cargo de servicio, cupo restante, entradas en reventa, mis eventos y, de cada evento, ventas y liquidación (recaudado de los compradores, cargo de Palco, lo que se le liquida al organizador y la tabla de ventas por día), ocupación por sector, reventa activa, accesos en puerta y equipo de puerta. Además, **crear**, **editar** y **cancelar** un evento, con su foto | Rol `organizer` | API (Eventos, Ventas, Reventa y Validación) |
| `/puerta` | Validación en puerta: elige uno de sus eventos y carga el código (se tipea o se pega; el visor de cámara es ilustrativo). Muestra el resultado (válida, ya usada, inexistente, de otro evento o no habilitada, y "sin respuesta" si Ventas no contesta) con el nombre y el DNI del titular, contadores y últimos escaneos | Rol `staff` | API (Validación) |

Las ventas por día y la liquidación salen del resumen de Ventas
(`GET /api/v1/resumenes`): solo compras pagadas, sin las de la reventa oficial,
con los montos que calcula el servidor (DEF-48).

Si no hay sesión, las rutas protegidas redirigen a `/ingresar?next=<ruta>` y
vuelven a la ruta original después del login. Si la sesión no tiene el rol
requerido, la pantalla explica para qué cuentas es y ofrece volver al inicio.
Si el backend deja de aceptar el token (venció a los 15 minutos o se reinició el
servidor), la sesión se cierra con un aviso.

## Reglas de negocio

Las constantes viven en el backend, en [`ReglasDeNegocio.java`](backend/common/src/main/java/com/palco/common/ReglasDeNegocio.java), que decide. El frontend repite en [`frontend/src/types/index.ts`](frontend/src/types/index.ts) las que usa para avisar antes de mandar el pedido, con los porcentajes como fracción (`TOPE_REVENTA = 0.1`, `MINIMO_REVENTA = 0.5` y `TASA_SERVICIO = 0.1`); `MAX_ENTRADAS_POR_EVENTO` está solo en el backend:

| Constante (backend) | Valor | Regla |
|---|---|---|
| `TOPE_REVENTA_PORCENTAJE` y `MINIMO_REVENTA_PORCENTAJE` | `10` y `50` | Una entrada se puede revender como máximo al precio de lista de su sector +10% (redondeado hacia abajo). El mínimo es el 50% de ese precio (redondeado hacia arriba). Los dos salen del precio de lista y no de lo pagado: así cada reventa no sube el tope otro 10% (DEF-16). |
| `TASA_SERVICIO_PORCENTAJE` | `10` | Cargo de servicio sobre el subtotal en el checkout. |
| `MAX_ENTRADAS_POR_ORDEN` | `6` | Máximo de entradas por compra, sumando todos los sectores. Tampoco se puede superar el cupo disponible de un sector. |
| `MAX_ENTRADAS_POR_EVENTO` | `6` | Máximo de entradas de un mismo comprador para un evento, sumando todas sus compras, también las de la reventa (pagadas y en curso; no cuentan las canceladas, vencidas ni revendidas). 409 `tope-por-evento`. |
| `HORAS_LIMITE_REVENTA` | `3` | La reventa de un evento cierra 3 horas antes de que empiece. |
| `MINUTOS_RESERVA` | `10` | Tiempo de reserva de los lugares durante el checkout. Al vencer, se liberan. |

Otras reglas:

- **Entradas nominativas**: cada entrada lleva nombre, apellido y DNI del titular.
- **DNI único por orden**: dos entradas de una misma compra no pueden tener el mismo DNI.
- **Un DNI, una entrada por evento**: un DNI que ya es titular de una entrada en pie para el evento (en otra compra,
  de cualquier cuenta) no puede ser titular de otra: 409 `dni-con-entrada`. Se mira al abrir la compra y otra vez
  antes de cobrar, por si otra compra abierta con el mismo DNI se pagó primero; en ese caso no se cobra.
- **Dos compras a la vez**: el tope por evento y el DNI por evento valen también con dos compras simultáneas, del
  catálogo o de la reventa. Ventas toma un candado por comprador y evento y otro por DNI y evento
  (`ventas.candado_de_compra`, DEF-46). En la reventa, si otra compra se cruzó entre el control y la transferencia,
  el cobro ya hecho se devuelve, la publicación queda libre y la respuesta es el 409 de la regla.
- **DNI**: 7 u 8 dígitos. Tarjeta: 16 dígitos, vencimiento `MM/AA`, CVV de 3 dígitos.
- **Reventa**: al comprar una publicación, la entrada original pasa a `vendida` y
  se emite una entrada nueva para el comprador (origen `reventa`). Una publicación
  se puede retirar y la entrada vuelve a `valida`.

Estados posibles de una entrada: `pendiente_emision` (pagada, hasta que la emite la cola), `valida`, `usada`, `publicada`, `vendida`, `anulada`.

## Recorrido sugerido de demo

1. Entrar a `/` (catálogo), usar el buscador y los filtros de categoría.
2. Abrir el evento destacado (`Atlético Norte vs. Racing del Sur`), elegir un
   sector y sumar 2 entradas.
3. Ir a "Continuar al checkout" → loguearse como `titular@palco.test`.
4. Completar los datos del segundo titular y tocar "Continuar": ahí se reserva
   el cupo por 10 minutos y aparece la cuenta regresiva. Elegir "Tarjeta" y
   pagar (una tarjeta terminada en `0002` se rechaza, una terminada en `0504`
   simula que la pasarela no responde y cualquier otra se aprueba).
5. Ver "Emitiendo tus entradas…" y después la confirmación con un QR por
   entrada; ir a "Mis entradas".
6. Publicar una de las entradas en la reventa oficial (probar precios por encima
   del tope para ver el bloqueo).
7. Cerrar sesión y entrar como `otro.titular@palco.test` (o crear una cuenta en
   `/registro`: sin SMTP, el código sale del log, ver [Usuarios de prueba](#usuarios-de-prueba)),
   ir a `/reventa` y comprar esa publicación.
8. Entrar como `puerta@palco.test`, ir a `/puerta`, elegir el evento y validar
   el código de una entrada (su `codigoQr`, de `GET /api/v1/entradas/{entradaId}`):
   la primera vez da válida y la segunda, ya usada. Probar también un código
   inexistente, uno de otro evento y el de una entrada publicada en la reventa
   (no habilitada).
9. Entrar como `organizador@palco.test` y revisar `/organizador`: entradas
   vendidas, recaudado, las ventas por día y la liquidación del evento,
   ocupación por sector, reventa activa, accesos en puerta y equipo de puerta.
10. Desde `/organizador`, tocar "+ Crear evento": cargar título, lugar, fecha,
    descripción y sectores, y confirmar: el evento se crea en el backend y
    aparece en el catálogo y en "Mis eventos" con la foto de su categoría.

### Demo por consola, Postman y evidencia

Con el backend levantado, desde la raíz del repo y en Git Bash:

| Qué | Cómo | Detalle |
|---|---|---|
| Compra con mensajería, por la API | `node scripts/pruebas/escenarios.mjs 1 3 7 9` | Compra de punta a punta, pago rechazado, un mensaje a la cola y una copia por suscripción del tópico, y el aviso y la auditoría. Ver [`scripts/LEEME.md`](scripts/LEEME.md) |
| Colección de Postman | Importar `docs/api/palco-api.postman_collection.json`, o `npx newman run docs/api/palco-api.postman_collection.json` | Se corre entera, en orden, sin environment. Ver [`docs/api/GUIA_POSTMAN.md`](docs/api/GUIA_POSTMAN.md) |
| Evidencia de la Entrega Parcial N.º 2 | `bash docs/entrega3/evidencia/reproducir.sh` | Repite el proceso asincrónico completo. Ver [`docs/entrega3/evidencia/LEEME.txt`](docs/entrega3/evidencia/LEEME.txt) |

## Tests

```bash
cd backend && mvn -q test
```

Hay tests en `common`, en los nueve servicios y en el ARCA simulado, y corren
sin WildFly. `mvn verify` suma JaCoCo, PMD y las pruebas de integración
(`*IT`) contra PostgreSQL 17 y Artemis en contenedores, con Testcontainers:
necesitan Docker y, sin él, se saltean con un aviso. La prueba en vivo de pagos simultáneos
(`backend/servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs`) sí necesita
el servidor levantado.

El frontend tiene pruebas de punta a punta con Playwright en `frontend/e2e/`
(39 pruebas en 15 archivos, según `npx playwright test --list`): recorren en el
navegador el login de cada rol, el registro, recuperar la contraseña, el
catálogo (también sin conexión con el servidor), la compra, la reventa, el panel
del organizador (las ventas por día y la liquidación, la foto, la edición y la
cancelación), la puerta, el asistente, los avisos de una factura rechazada o
demorada, la carga diferida de las pantallas, los temas, el HTML guardado que se
muestra como texto y la accesibilidad (axe) a 360, 768 y 1280 px.
Necesitan el backend y la app levantados; cada prueba crea sus propios datos.
El CI las corre con el Chromium que baja Playwright; con
`PALCO_E2E_CANAL=msedge` usan el Edge instalado en la máquina.

```bash
cd frontend && npx playwright install chromium   # una sola vez (no hace falta con PALCO_E2E_CANAL=msedge)
cd frontend && npm run e2e                       # contra http://localhost:5173
PALCO_E2E_URL=https://localhost PALCO_LOG=../logs/server.log npm run e2e   # contra el compose (ver abajo)
PALCO_E2E_CANAL=msedge npm run e2e               # con Edge en lugar de Chromium
```

Para dar de alta cuentas nuevas leen el código de verificación del log del
servidor (`PALCO_LOG`; por defecto, el que escribe `dev.sh`). El compose lo escribe
solo con `PALCO_CORREO_LOG_COMPLETO=true`, así que para correrlas contra él se lo
levanta desde la raíz con el log en `./logs` y esa variable, como en el job E2E del CI:

```bash
export PALCO_DIR_LOGS=./logs PALCO_CORREO_LOG_COMPLETO=true
mkdir -p logs && chmod 777 logs        # el contenedor escribe server.log en esa carpeta
docker compose up --build --wait
```

Además, `npm run lint` y `npm run build` controlan el código del frontend.

### Integración continua

Los workflows de GitHub Actions están en [`.github/workflows/`](.github/workflows/). Cada job que prueba el sistema
levantado arma su propio compose, de cero, con `docker compose up --build --detach --wait`.

| Workflow | Cuándo corre | Qué corre |
|---|---|---|
| `ci.yml` (CI) | Cada push a `master`, cada pull request, los tags `v*` y a mano | gitleaks sobre todo el historial; `mvn verify` (tests, IT con Testcontainers, JaCoCo y PMD, con la tabla de cobertura en el resumen); el análisis de SonarCloud; lint y build del frontend; la integración contra el compose (`scripts/pruebas/escenarios.mjs` repartido en cuatro jobs, `etag.mjs`, `patron-state.mjs`, `asistente.mjs` y `broker-apagado.mjs`); las E2E con Playwright y Chromium por `https://localhost`, y la colección de Postman con Newman |
| `seguridad.yml` (Seguridad) | Como CI | Trivy sobre las dependencias del repo y sobre las tres imágenes del compose (backend, web y `postgres:17-alpine`, DEF-73), con cada excepción justificada en `.trivyignore` |
| `zap.yml` (ZAP) | Como CI | OWASP ZAP contra el compose: el baseline de la app y el escaneo de la API autenticada (`scripts/qa/zap.sh`), con cada alerta Medium justificada en `.zap/reglas.tsv` |
| `contrato.yml` (Contrato) | Como CI | Schemathesis contra `docs/api/openapi.yaml`, una vez por rol |
| `pruebas-seguridad.yml` (Pruebas de seguridad) | Como CI | `scripts/pruebas/seguridad.mjs` (tokens manipulados, mass assignment, XSS, XXE, cookies y cabeceras) y `frontend/e2e/xss.spec.ts` contra el compose |
| `mutacion.yml` (Mutación) | Como CI | PIT sobre el paquete `dominio` de cada servicio, con un mínimo de 60 % de mutantes muertos por módulo |
| `lighthouse.yml` (Lighthouse) | Cada push a `master`, los tags `v*` y a mano | Lighthouse CI sobre las pantallas públicas, en teléfono y en escritorio |
| `carga.yml` (Carga) | Los tags `v*` y a mano | k6 con 50 usuarios virtuales durante 5 minutos contra el compose |
| `estabilidad.yml` (Estabilidad) | Los tags `v*` y a mano | `mvn test` tres veces seguidas, comparadas test por test |

El job de integración de `ci.yml` y los de `contrato.yml`, `pruebas-seguridad.yml` y `zap.yml` tienen el paso
"Control de logs" ([`scripts/qa/control-de-logs.sh`](scripts/qa/control-de-logs.sh)): falla si el `server.log` tiene
un token, una contraseña de prueba, un DNI o, con los emails enmascarados, un email `@palco.test` entero (DEF-40).

## Stack

### Backend

- Java 25 + Jakarta EE 11 (EJB + CDI) sobre WildFly 41
- JAX-RS para la API REST
- JMS con el ActiveMQ Artemis embebido en WildFly
- MicroProfile JWT para las sesiones y `@RolesAllowed` en todos los servicios
- JPA + Hibernate; H2 en modo PostgreSQL para desarrollar y PostgreSQL 17 en el compose y en el CI
- Flyway para las migraciones (un esquema por servicio)
- JSON-B para el JSON de la API
- Maven multimódulo (un WAR por servicio); JUnit 6, Mockito y Testcontainers para los tests

### Frontend

- React 19 + TypeScript 6
- Vite 8
- React Router 7
- Tailwind CSS 3, con tokens de diseño propios (`border-radius: 0` en todo salvo
  indicadores de 6px)
- Context + `useReducer` para el estado global (sin Redux ni Zustand)
- `fetch` del navegador para hablar con el backend (sin axios ni librería de
  datos)
- `framer-motion` para animaciones (con `prefers-reduced-motion`, `MotionConfig
  reducedMotion="user"` en `main.tsx` las apaga y `index.css` reduce las transiciones CSS)
- `qrcode` para dibujar el código QR de cada entrada
- Validaciones de formulario escritas a mano (sin Formik ni React Hook Form)
- oxlint para lint
- Tipografías: Archivo (títulos y UI) e IBM Plex Mono (datos), servidas desde el propio sitio
  (`frontend/public/fuentes`, con su licencia SIL Open Font License), sin pedir nada a Google Fonts

## Estructura del proyecto

```
frontend/                 # SPA React (Vite)
docs/                     # Documento único, ADR, contratos de la API, pruebas y manual de usuario
scripts/                  # Pruebas contra el sistema levantado, carga y calidad (ver scripts/LEEME.md)
backend/
├── pom.xml               # Proyecto Maven padre (versiones de Java, Jakarta EE y WildFly)
├── dev.sh                # Arma y levanta WildFly con los 9 servicios
├── redesplegar.sh        # Redespliega un servicio sin reiniciar
├── common/               # Lo compartido: reglas de negocio, errores, JSON, CORS, health, migraciones
├── servicios/            # Un WAR por servicio: /api/<servicio>, o /api/v1 (Ventas) y /api/v1/pagos (Pagos)
│   ├── usuarios/  eventos/  ventas/  pagos/
│   ├── validacion/  reventa/  notificaciones/  facturacion/  asistente/
│   └── arca/        # ARCA simulado (sistema externo, SOAP): no es un servicio de Palco
└── servidor/             # Arma WildFly (base, broker JMS) y despliega los 10 WAR
```

Cada servicio sigue las mismas capas:

```
servicios/eventos/src/main/
├── java/com/palco/eventos/
│   ├── api/              # Presentación: recurso JAX-RS y DTOs
│   ├── negocio/          # Negocio: interfaz IEventos + Session Bean (en externos/, los clientes de otros sistemas)
│   ├── datos/            # Datos: un repositorio por entidad, el único que usa el EntityManager
│   └── dominio/          # Entidades JPA
└── resources/
    ├── META-INF/persistence.xml
    └── db/migracion/     # Scripts SQL de Flyway (V1__..., V2__...)
```

Reglas entre servicios: cada uno es dueño de su esquema de base (`eventos`,
`usuarios`…) y nunca lee tablas de otro; si necesita datos de otro servicio, se
los pide por REST o JMS.

Dentro de `frontend/src/`:

```
src/
├── main.tsx              # Punto de entrada: Router, ToastProvider, SesionProvider, AppProvider
├── App.tsx               # Definición de rutas y rutas protegidas
├── index.css             # Estilos base y fuentes
├── types/index.ts        # Modelo de datos y constantes de negocio
├── servicios/            # Una función por endpoint del backend
│   ├── usuarios.ts       # /api/usuarios: ingresar, registrar, verificar email, recuperar la contraseña, perfil
│   ├── eventos.ts        # /api/eventos: listar, detalle, mis eventos, crear, editar, cancelar, subir la foto
│   ├── ventas.ts         # /api/v1/ventas, /entradas y /resumenes: comprar, pagar, cancelar, liquidación
│   ├── reventa.ts        # /api/v1/reventas: listar, mis publicaciones, publicar, retirar, comprar
│   ├── validacion.ts     # /api/v1/validacion: escanear, registro de la puerta, equipo de puerta
│   ├── facturacion.ts    # /api/v1/facturas: la factura de una compra
│   └── asistente.ts      # /api/v1/asistente: preguntarle al asistente
├── store/
│   ├── SesionContext.tsx # Quién está logueado: ingresar, verificar email, salir
│   ├── AppContext.tsx    # Catálogo del servidor y carrito de la compra en curso (reducer y localStorage)
│   ├── useSesion.ts, useApp.ts  # Los hooks de esos dos contextos, en archivos propios (DEF-50)
│   └── actions.ts        # Tipos de acciones del reducer
├── data/
│   └── events.ts         # Fotos locales de los eventos (por id y por categoría)
├── lib/
│   ├── api.ts            # Cliente HTTP: URL base, JSON, token, errores, sesión perdida
│   ├── sesion.ts         # Lee y guarda la sesión en localStorage
│   ├── format.ts         # Formato de moneda y fechas; cálculo de tope, mínimo y cargo de servicio
│   ├── validate.ts       # Validaciones de formularios
│   ├── tema.ts           # Tema normal, claro u oscuro, elegido desde el encabezado
│   ├── useReventas.ts    # Publicaciones activas de uno o varios eventos, de Reventa
│   ├── useDebouncedValue.ts
│   └── useAnimatedNumber.ts
├── components/           # Componentes reutilizables (Button, Input, Modal, QRCode,
│                         # Stepper, ToastProvider y useToast, CreateEventModal,
│                         # CancelarEventoModal, ProtectedRoute, RequireCatalog, etc.)
└── pages/                # Una página por ruta
```

## Arquitectura y estado

- **Llamadas al backend**: [`lib/api.ts`](frontend/src/lib/api.ts) es el único
  lugar que usa `fetch`. Arma la URL (`VITE_API_URL`), manda y lee JSON, agrega
  `Authorization: Bearer` solo en los pedidos que lo necesitan y convierte el
  formato de error del backend (Problem Details: el mensaje llega en `detail` y
  los errores por campo en `errores`) en un `ErrorApi`.
  Encima, `servicios/*.ts` tiene una función por endpoint y es donde se traducen
  los tipos; las páginas no arman URLs.
- **Sesión** en [`SesionContext.tsx`](frontend/src/store/SesionContext.tsx): el
  token, su vencimiento y el usuario se guardan juntos en
  `localStorage['palco.sesion']` y se leen de forma síncrona al abrir la app, así
  las rutas protegidas saben quién es el usuario desde el primer render. Después
  se confirma con `GET /api/usuarios/yo`. Si el backend rechaza el token, la
  sesión se cierra con un aviso. Las páginas leen el usuario con
  `useApp().usuarioActual`.
- **Catálogo y carrito** en
  [`AppContext.tsx`](frontend/src/store/AppContext.tsx) con `useReducer`:
  `eventos` es una copia de `GET /api/eventos` que se pide al abrir la app y no
  se guarda, y `carrito` es la compra en curso, con el id de la venta cuando el
  cupo ya está reservado. Es lo único que se guarda, en
  `localStorage['palco.state.v3']`. Las compras, las entradas, las
  publicaciones de la reventa, los escaneos y los números del panel no se
  guardan: cada pantalla los pide al servicio dueño cuando se muestran.
- **Acciones del reducer**: `CATALOGO_CARGANDO`, `CATALOGO_CARGADO`,
  `CATALOGO_FALLO` y `EVENTO_RECIBIDO` para el catálogo, y `SET_CARRITO` para
  la compra en curso.
- **Validaciones**: los formularios validan en el navegador con las mismas
  reglas y textos que el backend, y además muestran en cada campo los errores que
  devuelve el servidor. Las reglas de negocio (tope y mínimo de la reventa,
  máximo por orden, DNI único, cierre de la reventa) se adelantan en la pantalla
  para avisar antes de mandar el pedido, pero las decide el backend.
- **Cómo se hablan los servicios del backend**: por REST, en el momento,
  Ventas le pregunta a Usuarios (identidad del comprador), a Eventos (cupo y
  dueño del evento) y a Pagos (cobro y reembolso); Reventa, a Ventas, a Pagos y
  a Eventos; Validación, a Ventas (usar la entrada), a Eventos (dueño del
  evento) y a Usuarios (el rol de la cuenta que se asigna a la puerta); Notificaciones, a Usuarios (el email del comprador), y Eventos sube
  las fotos a Supabase Storage cuando está configurado (si no, las guarda en su
  esquema de la base). Por SOAP, Facturación le pide el CAE al ARCA
  simulado. Por JMS van la cola `cola.emision-entradas` y los tópicos
  `topico.compra-confirmada` y `topico.eventos-cancelados` (ver
  [API disponible](#api-disponible)).

## Decisiones de diseño

- **Cada dato, de su servicio**: el frontend no guarda copias de lo que es del
  backend, así no hay nada que se desactualice ni que dependa del navegador
  (las compras se ven igual desde otro dispositivo). En el navegador quedan solo
  la sesión, el carrito y el tema. El porqué de cada decisión está en la sección 15 de
  [`docs/documentacion/Palco_Documentacion_Completa.md`](docs/documentacion/Palco_Documentacion_Completa.md).
- **Compra contra `ServicioDeVentas`**: la reserva del cupo se crea al terminar
  el paso "Titulares", porque el backend pide los titulares en ese pedido. Los
  dos pedidos que cambian algo (iniciar y pagar) viajan con una clave de
  idempotencia, así un doble clic o un reintento no compra ni cobra dos veces.
  Un corte de red no cierra la sesión: se avisa y el reintento viaja con la
  misma clave. La cuenta regresiva se calcula contra el vencimiento que
  devuelve el backend y corrige la diferencia entre el reloj del navegador y
  el del servidor.
- **Stock**: las entradas disponibles que se muestran son las del servidor. Al
  reservar bajan en el acto, y vuelven si la compra se cancela o vence.
- **Código QR**: el componente `QRCode` dibuja un QR estándar, con la librería
  `qrcode`, a partir del `codigoQr` que genera el backend al emitir la
  entrada. Solo se muestra para una entrada `VALIDA`: para las demás, la
  pantalla dice por qué no sirve para entrar.
- **Carrito en el store**: la selección de sectores del detalle de evento se
  guarda en `state.carrito`, junto con el id de la venta cuando ya hay reserva
  (por eso recargar el checkout retoma la misma compra). Lo vacía la pantalla
  de confirmación.
- **Tailwind 3 en vez de 4**: se fijó la versión 3.x para poder declarar los
  tokens de color en `tailwind.config.js` con la sintaxis clásica.
- **Imágenes de eventos**: los 6 eventos de prueba usan fotos reales de dominio
  público guardadas en `frontend/public/eventos/` (una por evento, más una
  genérica por categoría; fuentes en `CREDITOS.md` de esa carpeta). Para los
  eventos de prueba el backend devuelve URLs de relleno, así que
  `imagenDeEvento()` las reemplaza por la foto local. Al crear o editar un evento
  desde el panel se puede subir su foto (`POST /api/eventos/imagenes`); sin
  foto propia, el evento usa la de su categoría.
- **Crear evento**: el modal "+ Crear evento" en `/organizador` manda el evento
  a `POST /api/eventos` (el id, el slug y los ids de sector los genera el
  servidor) y agrega la respuesta al catálogo en memoria. Por eso aparece de
  inmediato en el catálogo y en "Mis eventos".
- **Login para comprar en reventa**: comprar una publicación sin sesión muestra
  un toast ("Iniciá sesión para comprar en la reventa oficial.") y redirige al
  ingreso, que después vuelve a la misma pantalla.

## Decisiones de alcance

Lo que el trabajo decidió simular o dejar afuera, y por qué. El alcance completo
está en la sección 1.5 de
[`docs/documentacion/Palco_Documentacion_Completa.md`](docs/documentacion/Palco_Documentacion_Completa.md),
y los defectos conocidos, con su estado, en
[`docs/testing/registro-de-defectos.md`](docs/testing/registro-de-defectos.md).

- **Token en `localStorage`, sin cookies** (riesgo aceptado, DEF-58): el token
  viaja en la cabecera `Authorization`, así no hay cookies que un sitio ajeno
  pueda mandar (CSRF). Las contraseñas no se guardan en el navegador y los roles
  los valida el backend. Un script inyectado podría leer el token: por eso el
  frontend no carga scripts de terceros, nginx manda una
  `Content-Security-Policy` con `script-src 'self'` y React muestra como texto lo
  que escribe la gente (`frontend/e2e/xss.spec.ts`).
- **Sesión de 15 minutos, sin revocación** (DEF-38, corregido el 25/11): el
  token es un JWT que cada servicio valida sin consultar a Usuarios, así que
  cerrar sesión lo descarta en el navegador y no hay lista de revocados; lo que
  acota una sesión robada es que dure 15 minutos. Alcanza para una compra entera
  (la reserva dura 10 minutos) y, al vencer, el frontend pide ingresar de nuevo.
  Reiniciar el backend sin `PALCO_JWT_CLAVE_PRIVADA` genera una clave nueva y
  cierra todas las sesiones.
- **Verificación de identidad por email**: verificar el email marca la
  identidad como verificada, y con eso la cuenta puede comprar. La validación
  documental del DNI (foto o un servicio externo) queda fuera del alcance.
- **Pagos simulados**: el cobro y el reembolso pasan por `ServicioDePagos`,
  con la pasarela simulada `PagosFake`, que la cátedra aceptó el 16/10. No se
  procesa ningún pago real: el formulario de tarjeta no manda sus datos, solo un
  token de prueba. Una pasarela real se suma como otra implementación de
  `PasarelaDePagos`, sin tocar a Ventas ni a Reventa.
- **ARCA simulado**: la factura se pide por SOAP a un ARCA propio, en un WAR
  aparte (`/legado/arca`), con la aceptación de la cátedra del 16/10. No pide
  autenticación, así que el nginx del compose no lo publica (DEF-45):
  Facturación lo llama por la red interna.
- **Emails según la configuración**: con `PALCO_SMTP_HOST` y las demás
  `PALCO_SMTP_*` en `backend/.env` (con `./dev.sh`) salen por SMTP; sin ellas
  quedan en el log del servidor (ver "Emails" en [API disponible](#api-disponible)),
  así el sistema se levanta y se prueba sin una cuenta de correo. El compose no
  le pasa esas variables al backend: con Docker, los emails quedan en el log.
- **Fotos según la configuración**: sin `PALCO_SUPABASE_URL` y
  `PALCO_SUPABASE_CLAVE`, las fotos de los eventos se guardan en la base de
  Palco (tabla `eventos.imagen`, hasta 2 MB cada una), que alcanza para el
  volumen del proyecto. Con esas variables (en `backend/.env`, con `./dev.sh`)
  van a Supabase Storage; `PALCO_IMAGENES` (`supabase`, `base` o `ninguno`)
  fuerza la elección. Con el compose, van a la base.
- **Un solo servidor**: el sistema corre en un WildFly local o en el compose.
  La consigna no pide desplegarlo en la nube.
