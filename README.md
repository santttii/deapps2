# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping** — entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre lo pagado.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. Todo lo que muestra sale del backend: el
  ingreso y el registro, el catálogo, la compra, Mis entradas, la reventa, la
  puerta y el panel del organizador. En el navegador solo quedan la sesión (el
  token) y el carrito de la compra en curso.
- **`backend/`**: Jakarta EE sobre WildFly. Son 8 servicios independientes (un
  WAR cada uno) desplegados en el mismo WildFly, con el broker JMS (ActiveMQ
  Artemis) que trae el servidor: Usuarios; Eventos (también la edición y la
  cancelación); Ventas (compra con reserva de cupo, pago y emisión asincrónica
  de las entradas); Pagos (con una pasarela simulada); Reventa (publica y vende
  con tope, con su conciliación de pagos); Validación (el control de ingreso en
  la puerta); Notificaciones (emails y auditoría, por tópico), y Facturación
  (factura cada compra confirmada pidiéndole el CAE por SOAP a un ARCA
  simulado, que se despliega aparte en `/legado/arca`). Los servicios se hablan
  solo por REST, SOAP o JMS. El plan completo está en [`TODO.md`](TODO.md).

## Índice

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

- Frontend: Node.js `^20.19.0` o `>=22.12.0` (lo exige Vite 8) y npm
- Backend: JDK 25 y Maven 3.9+ (en macOS: `brew install openjdk@25 maven`; en
  Windows, cualquier JDK 25, por ejemplo Temurin, con `JAVA_HOME` apuntando a
  él). WildFly no se instala: lo descarga Maven.
- En Windows, los guiones (`backend/dev.sh`, `scripts/`, `docs/entrega3/evidencia/`)
  se corren desde **Git Bash**.

## Cómo correrlo

### Todo junto, con un solo comando (Docker)

Solo hace falta Docker (Docker Desktop en Windows y macOS) con Compose v2. Desde la raíz del repo:

```bash
docker compose up --build
```

Queda andando en **https://localhost** (el certificado es autofirmado y se genera al arrancar: el navegador pide
aceptarlo una vez; `http://localhost` redirige a HTTPS). Se entra con los [usuarios de prueba](#usuarios-de-prueba).
La primera vez baja unos 1,9 GB de imágenes base y después arma el backend (dependencias de Maven y WildFly): medido
el 30/10, con las imágenes ya bajadas, el armado tardó unos 2,5 minutos y el arranque 28 segundos. Las siguientes veces
es solo el arranque. Está listo cuando `docker compose ps` muestra los tres contenedores `healthy` (con `docker compose
up --build --wait` el comando termina recién ahí).

Levanta tres contenedores: `db` (PostgreSQL), `backend` (WildFly con los 8 servicios, el broker y el ARCA simulado)
y `web` (nginx con el frontend, TLS y las cabeceras de seguridad, que pasa `/api` y `/legado` al backend). La API
también queda en `http://localhost:8080`, solo para esta máquina (Postman y `scripts/pruebas`). Ningún secreto está
en el repo: el secreto entre servicios y la clave de los tokens se generan en cada arranque si no se definen.

| Para | Comando |
|---|---|
| Bajar todo | `docker compose down` (con `-v` borra también la base) |
| Ver el log del servidor | `docker compose logs -f backend` |
| Correr la prueba de integración contra el compose | `PALCO_DIR_LOGS=./logs docker compose up -d --wait` y después `JBOSS_CLI=scripts/qa/jboss-cli-docker.sh PALCO_LOG=logs/server.log node scripts/pruebas/escenarios.mjs` |
| Usar otros puertos (si el 80 o el 443 están ocupados) | `PALCO_PUERTO_HTTP=8000 PALCO_PUERTO_HTTPS=8443 PALCO_URL_PUBLICA=https://localhost:8443 docker compose up --build` |

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
`/api/v1/reventas`; Validación, en `/api/v1/validacion`, y Facturación, en
`/api/v1/facturas`. Para comprobar que anda: `curl localhost:8080/api/eventos/health`
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

| Comando (desde `backend/`) | Qué hace |
|---|---|
| `./dev.sh` | Compila todo, arma WildFly con los 8 servicios y lo levanta |
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
| Eventos | `POST /api/eventos/imagenes` | `organizer` | Sube la foto (JPG, PNG o WEBP, hasta 2 MB) a Supabase Storage y devuelve su URL; sin almacén configurado, 504 |
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
| Reventa | `POST /api/v1/reventas` | `user` | Publica una entrada propia `VALIDA`, con el precio entre el 50 % y el 110 % de lo pagado |
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
quedan en el log del servidor, en un renglón con el formato
`Email para <email> — <asunto>: <cuerpo>`. Así se lee, por ejemplo, el código de
verificación del registro:

```
Email para ana@ejemplo.com — Tu código de Palco: Hola Ana: tu código para verificar el email es 123456. Vence en 15 minutos.
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

## Usuarios de prueba

Todas las cuentas usan la contraseña `palco1234`. Las seis se cargan con las
migraciones de Usuarios, con el email y la identidad verificados. Cada una
tiene un solo rol: el organizador y la puerta no compran, porque Ventas y
Reventa piden el rol `user`.

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
arriba). Con el email verificado, la cuenta ya puede comprar.

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

Editar y cancelar un evento, subir su foto, recuperar la contraseña y ver las
ventas por día existen en la API y todavía no tienen pantalla.

Si no hay sesión, las rutas protegidas redirigen a `/ingresar?next=<ruta>` y
vuelven a la ruta original después del login. Si la sesión no tiene el rol
requerido, la pantalla explica para qué cuentas es y ofrece volver al inicio.
Si el backend deja de aceptar el token (venció a las 8 horas o se reinició el
servidor), la sesión se cierra con un aviso.

## Reglas de negocio

Las constantes viven en [`frontend/src/types/index.ts`](frontend/src/types/index.ts) (y en el backend, en [`ReglasDeNegocio.java`](backend/common/src/main/java/com/palco/common/ReglasDeNegocio.java)):

| Constante | Valor | Regla |
|---|---|---|
| `TOPE_REVENTA` | `0.10` | Una entrada se puede revender como máximo a lo pagado +10% (redondeado hacia abajo). El mínimo es el 50% de lo pagado. |
| `TASA_SERVICIO` | `0.10` | Cargo de servicio sobre el subtotal en el checkout. |
| `MAX_ENTRADAS_POR_ORDEN` | `6` | Máximo de entradas por compra, sumando todos los sectores. Tampoco se puede superar el cupo disponible de un sector. |
| `HORAS_LIMITE_REVENTA` | `3` | La reventa de un evento cierra 3 horas antes de que empiece. |
| `MINUTOS_RESERVA` | `10` | Tiempo de reserva de los lugares durante el checkout. Al vencer, se liberan. |

Otras reglas:

- **Entradas nominativas**: cada entrada lleva nombre, apellido y DNI del titular.
- **DNI único por orden**: dos entradas de una misma compra no pueden tener el mismo DNI.
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
   `/registro`), ir a `/reventa` y comprar esa publicación.
8. Entrar como `puerta@palco.test`, ir a `/puerta`, elegir el evento y validar
   el código de una entrada (su `codigoQr`, de `GET /api/v1/entradas/{entradaId}`):
   la primera vez da válida y la segunda, ya usada. Probar también un código
   inexistente, uno de otro evento y el de una entrada publicada en la reventa
   (no habilitada).
9. Entrar como `organizador@palco.test` y revisar `/organizador`: entradas
   vendidas, recaudado, ocupación por sector, reventa activa, accesos en puerta
   y equipo de puerta.
10. Desde `/organizador`, tocar "+ Crear evento": cargar título, lugar, fecha,
    descripción y sectores, y confirmar — el evento se crea en el backend y
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

Hay tests en `common`, en los ocho servicios y en el ARCA simulado, y corren
sin WildFly. La prueba en vivo de pagos simultáneos
(`backend/servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs`) sí necesita
el servidor levantado. El frontend no tiene tests: se controla con
`npm run lint` y `npm run build`.

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
- Tipografías: Archivo (títulos y UI) e IBM Plex Mono (datos), desde Google Fonts

## Estructura del proyecto

```
frontend/                 # SPA React (Vite)
docs/                     # Spec de diseño original
backend/
├── pom.xml               # Proyecto Maven padre (versiones de Java, Jakarta EE y WildFly)
├── dev.sh                # Arma y levanta WildFly con los 8 servicios
├── redesplegar.sh        # Redespliega un servicio sin reiniciar
├── common/               # Lo compartido: reglas de negocio, errores, JSON, CORS, health, migraciones
├── servicios/            # Un WAR por servicio: /api/<servicio>, o /api/v1 (Ventas) y /api/v1/pagos (Pagos)
│   ├── usuarios/  eventos/  ventas/  pagos/
│   ├── validacion/  reventa/  notificaciones/  facturacion/
│   └── arca/        # ARCA simulado (sistema externo, SOAP): no es un servicio de Palco
└── servidor/             # Arma WildFly (base, broker JMS) y despliega los 8 WAR
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
  las fotos a Supabase Storage. Por SOAP, Facturación le pide el CAE al ARCA
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
  `imagenDeEvento()` las reemplaza por la foto local. Los eventos creados desde
  el panel usan la foto de su categoría: el backend ya sube imágenes
  (`POST /api/eventos/imagenes`), pero el alta del frontend todavía no la usa.
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
- **Pantallas pendientes**: editar y cancelar un evento, subir su foto,
  recuperar la contraseña y ver las ventas por día están en la API (ver
  [API disponible](#api-disponible)) y todavía no tienen pantalla.
- **Verificación de identidad**: no hay control con foto del DNI. Verificar el
  email marca la identidad como verificada, y con eso la cuenta puede comprar.
- **Pagos simulados**: el cobro y el reembolso pasan por `ServicioDePagos`,
  pero su pasarela es simulada (no se procesa ningún pago real; el formulario de
  tarjeta no manda sus datos, solo un token de prueba).
- **Emails sin proveedor configurado**: sin `PALCO_SMTP_HOST` los emails quedan
  en el log del servidor (ver "Emails" en [API disponible](#api-disponible)).
- **Fotos sin almacén**: sin `PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`,
  `POST /api/eventos/imagenes` responde 504 y los eventos usan la foto de su
  categoría.
- **Sin tests en el frontend**: los tests automatizados son del backend (ver
  [Tests](#tests)).
