# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping**: entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre el precio original de la entrada.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. Todo lo que muestra sale del backend: el
  ingreso y el registro, el catálogo, la compra, Mis entradas, la reventa, la
  puerta y el panel del organizador. En el navegador solo quedan la sesión (el
  token) y el carrito de la compra en curso.
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
  solo por REST, SOAP o JMS. El plan completo está en [`TODO.md`](TODO.md).

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
  disco: la primera vez baja 1,9 GB de imágenes base y arma dos imágenes propias.
- **Internet**, la primera vez: baja las imágenes de Docker Hub y las dependencias de Maven y de npm.
- **Los puertos 80, 443 y 8080 libres.** Si alguno está ocupado, ver [Si algo traba](#si-algo-traba).

### El comando

En cualquier terminal (PowerShell, cmd, la Terminal de macOS o Bash: los comandos son los mismos):

```
git clone <dirección del repositorio> palco
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

### Cuánto tarda

La primera vez baja unos 1,9 GB de imágenes base (PostgreSQL, Maven con el JDK 25, el JRE 25, Node y nginx) y arma
las dos imágenes de Palco, con las dependencias de Maven y WildFly. Medido el 30/10/2026 en un entorno Linux de 4
núcleos, con las imágenes base ya bajadas y sin caché de Maven: 112 s la imagen del backend, 30 s la del frontend y
28 s hasta que los tres contenedores quedaron sanos, unos 3 minutos en total. La descarga depende de la conexión:
con 50 Mbps, la cuenta da unos 5 minutos más. Las siguientes veces las imágenes ya están armadas y es solo el
arranque. El CI lo levanta igual, de cero, en cada corrida ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

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
| El WSDL del ARCA simulado | https://localhost/legado/arca?wsdl |
| La API directa, solo desde esta máquina (Postman) | http://localhost:8080 |

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
- [Limitaciones conocidas](#limitaciones-conocidas)

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

Levanta tres contenedores: `db` (PostgreSQL), `backend` (WildFly con los 9 servicios, el broker y el ARCA simulado)
y `web` (nginx con el frontend, TLS y las cabeceras de seguridad, que pasa `/api` y `/legado` al backend). La API
también queda en `http://localhost:8080`, solo para esta máquina (Postman y `scripts/pruebas`). Ningún secreto está
en el repo: el secreto entre servicios y la clave de los tokens se generan en cada arranque si no se definen.

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
  backend solo acepta pedidos desde los orígenes de `PALCO_CORS_ORIGENES`, así
  que con `127.0.0.1:5173` o con `npm run preview` (puerto 4173) las llamadas
  fallan.

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
migraciones vuelven a cargar los datos de prueba. Para usar **Supabase**,
completar las variables `PALCO_DB_*` del `.env` y volver a correr `./dev.sh`:
las mismas migraciones crean las tablas allá.

**Asistente con IA** (`/api/v1/asistente`). Sin configurar nada responde con el
modelo simulado. Para usar un modelo real basta un proveedor con la API de chat
de OpenAI (OpenAI, Gemini, Groq u Ollama local): en el `.env` del backend, o en
el `.env` de la raíz para Docker, se ponen `PALCO_ASISTENTE_MODELO=openai`,
`PALCO_ASISTENTE_OPENAI_URL`, `PALCO_ASISTENTE_OPENAI_MODELO` y
`PALCO_ASISTENTE_OPENAI_CLAVE` (ver `backend/.env.example`). El gateway le da
5 segundos al modelo; si no responde o falla, contesta con las preguntas
frecuentes.

| Comando (desde `backend/`) | Qué hace |
|---|---|
| `./dev.sh` | Compila todo, arma WildFly con los 9 servicios y lo levanta |
| `./redesplegar.sh eventos` | Recompila un servicio y lo redespliega sin reiniciar |
| `mvn test` | Corre los tests (ver [Tests](#tests)) |
| `mvn clean install` | Arma todo; el server queda en `servidor/target/server`, listo para hostear |

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
| Usuarios | `POST /api/usuarios/servicios/token` | pública, con secreto | `{"secreto"}` → token de rol `servicio` para llamar a otros servicios |
| Usuarios | `GET /api/usuarios/jwks` | pública | Clave pública para validar tokens (la usan los otros servicios) |
| Eventos | `GET /api/eventos?categoria=Música&q=texto` | pública | Catálogo sin los eventos cancelados (filtros opcionales, búsqueda sin acentos) |
| Eventos | `GET /api/eventos/mios` | `organizer` | Los eventos que creó quien pregunta, por fecha, también los cancelados (los usa el panel) |
| Eventos | `GET /api/eventos/{slug}` | pública | Detalle con sectores; de un evento cancelado, con `cancelado` y el motivo |
| Eventos | `POST /api/eventos` | `organizer` | Crear evento con sectores |
| Eventos | `PUT /api/eventos/{eventoId}` | `organizer` | Editar un evento propio (reemplazo completo; un sector con ventas no cambia de precio ni se quita) |
| Eventos | `POST /api/eventos/{eventoId}/cancelacion` | `organizer` | Cancelar un evento propio con `{"motivo"}` opcional: publica `EVENTO_CANCELADO`, y Ventas anula y reembolsa sus compras |
| Eventos | `POST /api/eventos/imagenes` | `organizer` | Sube la foto (JPG, PNG o WEBP, hasta 2 MB) y devuelve su URL: a Supabase Storage si está configurado y, si no, a la base de Palco (`PALCO_IMAGENES`: `supabase`, `base` o `ninguno`) |
| Eventos | `GET /api/eventos/imagenes/{id}` | público | La foto de un evento guardada en la base, con caché de un año |
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
| Facturación | `GET /api/v1/facturas/{ventaId}` | `user`, `servicio` | La factura de una compra (número de comprobante y CAE). El comprador ve solo la suya; ajena o todavía sin facturar: 404 |
| ARCA simulado | `POST /legado/arca` (SOAP, `FECAESolicitar`); WSDL en `GET /legado/arca?wsdl` | pública | Sistema externo simulado: da número y CAE. No es parte de la API de Palco. El WSDL está copiado en [`docs/api/arca.wsdl`](docs/api/arca.wsdl) |

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
`Authorization: Bearer <token>` y dura 8 horas. Cerrar sesión es descartarlo.
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
| `organizador@palco.test` | `organizer` | `/organizador`: es el dueño de los seis eventos de prueba (`ev-1` a `ev-6`). Crea eventos, ve sus números, asigna el personal de puerta y, por la API, edita y cancela los suyos |
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
Todo sale de la API: en el navegador solo quedan la sesión y el carrito de la
compra en curso, que permite recargar el checkout sin perder la reserva.

| Ruta | Pantalla | Acceso | Datos |
|---|---|---|---|
| `/` | Catálogo: evento destacado, filtros por categoría, buscador con demora e insensible a acentos, grilla de eventos, estados de carga, error y vacío, y franja de la reventa oficial | Pública | API (Eventos; la franja, de Reventa) |
| `/evento/:slug` | Detalle del evento: mapa de sectores, selección de cantidad por sector, resumen sticky con total (en pantalla angosta, el total y el botón van en una barra fija abajo) y las publicaciones de la reventa de ese evento | Pública | API (Eventos; las publicaciones, de Reventa) |
| `/checkout` | Checkout en 3 pasos: datos de cada titular, pago con la reserva hecha y su cuenta regresiva (pasarela, tarjeta o transferencia; se puede cancelar) y confirmación | Rol `user` | API (Ventas, que cobra en Pagos); el carrito en curso, del navegador |
| `/confirmacion/:ordenId` | Confirmación de compra: "emitiendo tus entradas…" hasta que termina la emisión, un QR por entrada, comprobante, imprimir, agregar al calendario | Rol `user` | API (Ventas) |
| `/mis-entradas` | Entradas con su QR, publicación y retiro en la reventa con validación de tope y mínimo, historial | Rol `user` | API (Ventas: `GET /api/v1/ventas`; Reventa: `GET /api/v1/reventas/mias`) |
| `/ingresar` | Login (en desarrollo, con las credenciales de prueba a la vista) | Pública | API (Usuarios) |
| `/registro` | Alta de cuenta en 2 pasos: datos y verificación de email con código | Pública | API (Usuarios) |
| `/reventa` | Reventa oficial: filtros por evento y sector, orden por precio, listado con el tope de cada publicación y compra con confirmación | Pública (comprar requiere login) | API (Reventa, que cobra en Pagos) |
| `/organizador` | Panel del organizador: entradas vendidas, recaudado sin el cargo de servicio, cupo restante, entradas en reventa, mis eventos, ocupación por sector, reventa activa, accesos en puerta, equipo de puerta y **crear evento** | Rol `organizer` | API (Eventos, Ventas, Reventa y Validación) |
| `/puerta` | Validación en puerta: elige uno de sus eventos y carga el código (se tipea o se pega; el visor de cámara es ilustrativo). Muestra el resultado (válida, ya usada, inexistente, de otro evento o no habilitada, y "sin respuesta" si Ventas no contesta) con el nombre y el DNI del titular, contadores y últimos escaneos | Rol `staff` | API (Validación) |

Recuperar la contraseña y ver las ventas por día existen en la API y todavía no
tienen pantalla. Editar y cancelar un evento y subir su foto se hacen desde el
panel del organizador.

Si no hay sesión, las rutas protegidas redirigen a `/ingresar?next=<ruta>` y
vuelven a la ruta original después del login. Si la sesión no tiene el rol
requerido, la pantalla explica para qué cuentas es y ofrece volver al inicio.
Si el backend deja de aceptar el token (venció a las 8 horas o se reinició el
servidor), la sesión se cierra con un aviso.

## Reglas de negocio

Las constantes viven en [`frontend/src/types/index.ts`](frontend/src/types/index.ts) (y en el backend, en [`ReglasDeNegocio.java`](backend/common/src/main/java/com/palco/common/ReglasDeNegocio.java)):

| Constante | Valor | Regla |
|---|---|---|
| `TOPE_REVENTA` | `0.10` | Una entrada se puede revender como máximo al precio de lista de su sector +10% (redondeado hacia abajo). El mínimo es el 50% de ese precio. |
| `TASA_SERVICIO` | `0.10` | Cargo de servicio sobre el subtotal en el checkout. |
| `MAX_ENTRADAS_POR_ORDEN` | `6` | Máximo de entradas por compra, sumando todos los sectores. Tampoco se puede superar el cupo disponible de un sector. |
| `MAX_ENTRADAS_POR_EVENTO` | `6` | Máximo de entradas de un mismo comprador para un evento, sumando todas sus compras (pagadas y en curso; no cuentan las canceladas, vencidas ni revendidas). 409 `tope-por-evento`. |
| `HORAS_LIMITE_REVENTA` | `3` | La reventa de un evento cierra 3 horas antes de que empiece. |
| `MINUTOS_RESERVA` | `10` | Tiempo de reserva de los lugares durante el checkout. Al vencer, se liberan. |

Otras reglas:

- **Entradas nominativas**: cada entrada lleva nombre, apellido y DNI del titular.
- **DNI único por orden**: dos entradas de una misma compra no pueden tener el mismo DNI.
- **Un DNI, una entrada por evento**: un DNI que ya es titular de una entrada en pie para el evento (en otra compra,
  de cualquier cuenta) no puede ser titular de otra: 409 `dni-con-entrada`. Se mira al abrir la compra y otra vez
  antes de cobrar, por si otra compra abierta con el mismo DNI se pagó primero; en ese caso no se cobra.
- **DNI**: 7 u 8 dígitos. Tarjeta: 16 dígitos, vencimiento `MM/AA`, CVV de 3 dígitos.
- **Reventa**: al comprar una publicación, la entrada original pasa a `vendida` y
  se emite una entrada nueva para el comprador (origen `reventa`). Una publicación
  se puede retirar y la entrada vuelve a `valida`.

Estados posibles de una entrada: `valida`, `usada`, `publicada`, `vendida`, `anulada`.

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
   vendidas, recaudado, ocupación por sector, reventa activa, accesos en puerta
   y equipo de puerta.
10. Desde `/organizador`, tocar "+ Crear evento": cargar título, lugar, fecha,
    descripción y sectores, y confirmar: el evento se crea en el backend y
    aparece en el catálogo y en "Mis eventos" con la foto de su categoría.

### Demo por consola, Postman y evidencia

Con el backend levantado, desde la raíz del repo y en Git Bash:

| Qué | Cómo | Detalle |
|---|---|---|
| Demo de la compra con mensajería | `bash scripts/demo-entrega3.sh` (o `bash scripts/demo-entrega3.sh rechazado`) | Compra de punta a punta con `curl`, con los contadores del broker y la fila de auditoría. Ver [`scripts/LEEME.md`](scripts/LEEME.md) |
| Colección de Postman | Importar `docs/api/palco-api.postman_collection.json`, o `npx newman run docs/api/palco-api.postman_collection.json` | Se corre entera, en orden, sin environment. Ver [`docs/api/GUIA_POSTMAN.md`](docs/api/GUIA_POSTMAN.md) |
| Evidencia de la Entrega Parcial N.º 2 | `bash docs/entrega3/evidencia/reproducir.sh` | Repite el proceso asincrónico completo. Ver [`docs/entrega3/evidencia/LEEME.txt`](docs/entrega3/evidencia/LEEME.txt) |

## Tests

```bash
cd backend && mvn -q test
```

Hay tests en `common`, en los nueve servicios y en el ARCA simulado, y corren
sin WildFly. La prueba en vivo de pagos simultáneos
(`backend/servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs`) sí necesita
el servidor levantado.

El frontend tiene pruebas de punta a punta con Playwright en `frontend/e2e/`:
recorren en el navegador el login de cada rol, el catálogo, la compra, la
reventa, el panel del organizador, la puerta, el asistente, el registro y los
avisos de una factura rechazada o demorada.
Necesitan el backend y la app levantados; cada prueba crea sus propios datos.

```bash
cd frontend && npx playwright install chromium   # una sola vez
cd frontend && npm run e2e                       # contra http://localhost:5173
PALCO_E2E_URL=https://localhost PALCO_LOG=../logs/server.log npm run e2e   # contra el compose (ver abajo)
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

## Stack

### Backend

- Java 25 + Jakarta EE 11 (EJB + CDI) sobre WildFly 41
- JAX-RS para la API REST
- JMS con el ActiveMQ Artemis embebido en WildFly
- MicroProfile JWT para las sesiones y `@RolesAllowed` en todos los servicios
- JPA + Hibernate; H2 en desarrollo y PostgreSQL en Supabase
- Flyway para las migraciones (un esquema por servicio)
- JSON-B para el JSON de la API
- Maven multimódulo (un WAR por servicio), JUnit para tests

### Frontend

- React 19 + TypeScript 6
- Vite 8
- React Router 7
- Tailwind CSS 3, con tokens de diseño propios (`border-radius: 0` en todo salvo
  indicadores de 6px)
- Context + `useReducer` para el estado global (sin Redux ni Zustand)
- `fetch` del navegador para hablar con el backend (sin axios ni librería de
  datos)
- `framer-motion` para animaciones (`index.css` reduce las transiciones CSS con
  `prefers-reduced-motion`)
- `qrcode` para dibujar el código QR de cada entrada
- Validaciones de formulario escritas a mano (sin Formik ni React Hook Form)
- oxlint para lint
- Tipografías: Archivo (títulos y UI) e IBM Plex Mono (datos), servidas desde el propio sitio
  (`frontend/public/fuentes`, con su licencia SIL Open Font License), sin pedir nada a Google Fonts

## Estructura del proyecto

```
frontend/                 # SPA React (Vite)
docs/                     # Spec de diseño original
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

Cada servicio sigue las tres capas de la Entrega 1:

```
servicios/eventos/src/main/
├── java/com/palco/eventos/
│   ├── api/              # Presentación: recurso JAX-RS y DTOs
│   ├── negocio/          # Negocio: interfaz IEventos + Session Bean
│   └── dominio/          # Datos: entidades JPA
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
│   ├── usuarios.ts       # /api/usuarios: ingresar, registrar, verificar email, perfil
│   ├── eventos.ts        # /api/eventos: listar, detalle, mis eventos, crear
│   ├── ventas.ts         # /api/v1/ventas, /entradas y /resumenes: comprar, pagar, cancelar, liquidación
│   ├── reventa.ts        # /api/v1/reventas: listar, mis publicaciones, publicar, retirar, comprar
│   └── validacion.ts     # /api/v1/validacion: escanear, registro de la puerta, equipo de puerta
├── store/
│   ├── SesionContext.tsx # Quién está logueado: ingresar, verificar email, salir
│   ├── AppContext.tsx    # Catálogo del servidor y carrito de la compra en curso (reducer y localStorage)
│   └── actions.ts        # Tipos de acciones del reducer
├── data/
│   └── events.ts         # Fotos locales de los eventos (por id y por categoría)
├── lib/
│   ├── api.ts            # Cliente HTTP: URL base, JSON, token, errores, sesión perdida
│   ├── sesion.ts         # Lee y guarda la sesión en localStorage
│   ├── format.ts         # Formato de moneda y fechas; cálculo de tope, mínimo y cargo de servicio
│   ├── validate.ts       # Validaciones de formularios
│   ├── useReventas.ts    # Publicaciones activas de uno o varios eventos, de Reventa
│   ├── useDebouncedValue.ts
│   └── useAnimatedNumber.ts
├── components/           # Componentes reutilizables (Button, Input, Modal, QRCode,
│                         # Stepper, Toast, CreateEventModal, ProtectedRoute,
│                         # RequireCatalog, StatusMessage, etc.)
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
  a Eventos; Validación, a Ventas (usar la entrada) y a Eventos (dueño del
  evento); Notificaciones, a Usuarios (el email del comprador), y Eventos sube
  las fotos a Supabase Storage cuando está configurado (si no, las guarda en su
  esquema de la base). Por SOAP, Facturación le pide el CAE al ARCA
  simulado. Por JMS van la cola `cola.emision-entradas` y los tópicos
  `topico.compra-confirmada` y `topico.eventos-cancelados` (ver
  [API disponible](#api-disponible)).

## Decisiones de diseño

- **Cada dato, de su servicio**: el frontend no guarda copias de lo que es del
  backend, así no hay nada que se desactualice ni que dependa del navegador
  (las compras se ven igual desde otro dispositivo). En el navegador quedan solo
  la sesión y el carrito. El porqué de cada decisión está en la sección 15 de
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

## Limitaciones conocidas

El plan de trabajo está en [`TODO.md`](TODO.md).

- **Token en `localStorage`**: las contraseñas no se guardan en el navegador
  y los roles los valida el backend. El token de sesión queda en `localStorage`,
  donde un script inyectado en la página podría leerlo; hoy el frontend no carga
  scripts de terceros.
- **Pantallas pendientes**: recuperar la contraseña y ver las ventas por día
  están en la API (ver [API disponible](#api-disponible)) y todavía no tienen
  pantalla. Editar y cancelar un evento y subir su foto se hacen desde el panel
  del organizador.
- **Verificación de identidad**: no hay control con foto del DNI. Verificar el
  email marca la identidad como verificada, y con eso la cuenta puede comprar.
- **Pagos simulados**: el cobro y el reembolso pasan por `ServicioDePagos`,
  pero su pasarela es simulada (no se procesa ningún pago real; el formulario de
  tarjeta no manda sus datos, solo un token de prueba).
- **Emails sin proveedor configurado**: sin `PALCO_SMTP_HOST` los emails quedan
  en el log del servidor (ver "Emails" en [API disponible](#api-disponible)).
- **Fotos en la base**: sin `PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`, las
  fotos de los eventos se guardan en la base de Palco (tabla `eventos.imagen`,
  hasta 2 MB cada una). Alcanza para el volumen del proyecto; con muchas fotos
  conviene Supabase Storage, que se usa solo con configurar esas variables.
