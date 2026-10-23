# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping** — entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre lo pagado.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. La sesión (ingreso, registro, perfil), el
  catálogo de eventos (listado, búsqueda, detalle, crear evento) y la compra
  (reserva, pago, emisión de entradas, cancelación) usan el backend. La
  reventa, la puerta y las estadísticas del organizador siguen con datos
  locales. "Mis entradas" muestra las compras que este navegador recuerda en
  `localStorage`, con los datos que devolvió el backend.
- **`backend/`**: Jakarta EE sobre WildFly, en construcción. Son 8 servicios
  independientes (un WAR cada uno) desplegados en el mismo WildFly, con el
  broker JMS (ActiveMQ Artemis) que trae el servidor. Funcionan Usuarios,
  Eventos, Ventas (compra con reserva de cupo, pago y emisión asincrónica de
  las entradas), Pagos (con una pasarela simulada) y Notificaciones (aviso y
  auditoría de cada compra, por tópico). Validación tiene el backend del
  escaneo en puerta (la pantalla todavía usa datos locales). Facturación
  factura cada compra confirmada (suscriptor del tópico) pidiéndole el CAE por
  SOAP a un ARCA simulado, que se despliega aparte en `/legado/arca`. Reventa
  solo responde su `health`. El plan completo está en [`TODO.md`](TODO.md).

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
`/api/<servicio>`; Ventas, en `/api/v1`; Pagos, en `/api/v1/pagos`, y
Validación, en `/api/v1/validacion`, y Facturación, en `/api/v1/facturas`. Para
comprobar que anda: `curl localhost:8080/api/eventos/health` (para Ventas,
`/api/v1/health`; para Pagos, `/api/v1/pagos/health`; para Validación,
`/api/v1/validacion/health`; para Facturación, `/api/v1/facturas/health`). El
ARCA simulado publica su WSDL en `http://localhost:8080/legado/arca?wsdl`.

En Windows, desde Git Bash y con un JDK 25:

```bash
export JAVA_HOME="/c/Users/<usuario>/.jdks/temurin-25"   # o donde esté el JDK 25
cd backend && ./dev.sh
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

Rutas comprobadas contra el código y contra el servidor en marcha el 14/10/2026.
"Rol" es lo que pide el token: `user`, `organizer`, `staff` o `servicio`
(llamadas entre servicios); "pública" es sin token.

| Servicio | Endpoint | Rol | Qué hace |
|---|---|---|---|
| todos | `GET /api/<servicio>/health` (Ventas: `/api/v1/health`; Pagos: `/api/v1/pagos/health`; Validación: `/api/v1/validacion/health`; Facturación: `/api/v1/facturas/health`) | pública | Estado del servicio y de la base |
| Usuarios | `POST /api/usuarios/registro` | pública | Alta de cuenta; genera un código de 6 dígitos |
| Usuarios | `POST /api/usuarios/verificacion` | pública | `{"email", "codigo"}` → activa la cuenta y devuelve la sesión |
| Usuarios | `POST /api/usuarios/verificacion/reenvio` | pública | Código nuevo (uno cada 30 segundos) |
| Usuarios | `POST /api/usuarios/login` | pública | `{"email", "password"}` → `{"token", "usuario", ...}` |
| Usuarios | `GET /api/usuarios/yo` | `user`, `organizer`, `staff` | Usuario del token |
| Usuarios | `POST /api/usuarios/servicios/token` | pública, con secreto | `{"secreto"}` → token de rol `servicio` para llamar a otros servicios |
| Usuarios | `GET /api/usuarios/jwks` | pública | Clave pública para validar tokens (la usan los otros servicios) |
| Eventos | `GET /api/eventos?categoria=Música&q=texto` | pública | Catálogo (filtros opcionales, búsqueda sin acentos) |
| Eventos | `GET /api/eventos/mios` | `organizer` | Los eventos que creó quien pregunta, por fecha (los usa el panel) |
| Eventos | `GET /api/eventos/{slug}` | pública | Detalle con sectores |
| Eventos | `POST /api/eventos` | `organizer` | Crear evento con sectores |
| Eventos | `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo` | `servicio` | Sumar (`{"cantidad": 2}`) o liberar (`-2`) entradas vendidas, sin pasarse del cupo |
| Ventas | `POST /api/v1/ventas` | `user` | Abre una compra y reserva el cupo por 10 minutos: 201, venta `EN_CHECKOUT`. Pide la cabecera `Idempotency-Key` (un UUID) |
| Ventas | `GET /api/v1/ventas` | `user` | Las compras de quien pregunta, de la más nueva a la más vieja, con sus entradas |
| Ventas | `GET /api/v1/ventas/{ventaId}` | `user` | Una venta, solo para quien la compró |
| Ventas | `POST /api/v1/ventas/{ventaId}/pagos` | `user` | Paga la compra (cobra en Pagos): 201, venta `CONFIRMADA` con las entradas `PENDIENTE_EMISION`; un rechazo responde 402. Pide `Idempotency-Key` |
| Ventas | `DELETE /api/v1/ventas/{ventaId}` | `user` | Cancela una compra sin pagar y devuelve el cupo: 204 |
| Ventas | `GET /api/v1/entradas/{entradaId}` | `user` | Una entrada: estado, titular y, ya emitida, `codigoQr` |
| Pagos | `POST /api/v1/pagos` | `servicio` | Cobra contra la pasarela y guarda el pago (lo llama Ventas) |
| Pagos | `GET /api/v1/pagos/{pagoId}` | `servicio` | Un pago guardado |
| Notificaciones | `GET /api/notificaciones/auditoria` | `servicio` | Los últimos registros de auditoría de compras |
| Notificaciones | `POST /api/notificaciones/pruebas/pago-confirmado` | `servicio` | Publica un `PAGO_CONFIRMADO` de prueba |
| Notificaciones | `POST /api/notificaciones/pruebas/pago-confirmado-con-falla` | `servicio` | Publica y falla: la transacción se deshace y el mensaje no sale (409) |
| Notificaciones | `POST /api/notificaciones/pruebas/mensaje-invalido` | `servicio` | Publica un texto inválido, para ver los reintentos y la DLQ |
| Facturación | `GET /api/v1/facturas/{ventaId}` | `user`, `servicio` | La factura de una compra (número de comprobante y CAE). El comprador ve solo la suya; ajena o todavía sin facturar: 404 |
| ARCA simulado | `POST /legado/arca` (SOAP, `FECAESolicitar`); WSDL en `GET /legado/arca?wsdl` | pública | Sistema externo simulado: da número y CAE. No es parte de la API de Palco |

La pasarela de Pagos es simulada: el token de pago `tok_test` aprueba,
`tok_rechazado` rechaza y `tok_sin_respuesta` simula que la pasarela no
contesta. Al pagar, Ventas publica `PAGO_CONFIRMADO` en la cola
`cola.emision-entradas` (emite las entradas) y en el tópico
`topico.compra-confirmada` (aviso y auditoría). El contrato del mensaje está en
[`docs/api/mensajeria-pago-confirmado.md`](docs/api/mensajeria-pago-confirmado.md).

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

**Código de verificación:** no se manda por email (todavía no hay proveedor de
correo); aparece en el log del servidor como
`Código de verificación para <email>: 123456`.

## Scripts del frontend

Desde `frontend/`:

| Comando | Qué hace |
|---|---|
| `npm run dev` | Servidor de desarrollo con HMR |
| `npm run build` | Chequeo de tipos (`tsc -b`) y build de producción en `dist/` |
| `npm run preview` | Sirve el build de `dist/` localmente |
| `npm run lint` | Lint con oxlint |

## Usuarios de prueba

Todas las cuentas usan la contraseña `palco1234`.

| Email | Rol | Acceso |
|---|---|---|
| `titular@palco.test` | `user` (identidad verificada) | Compra, mis entradas, reventa |
| `organizador@palco.test` | `organizer` | Todo lo anterior + `/organizador` |
| `puerta@palco.test` | `staff` | Todo lo anterior + `/puerta` |

Las tres cuentas existen en el backend (se cargan con las migraciones). También
se pueden crear cuentas nuevas (rol `user`) desde `/registro`: el backend genera
un código de 6 dígitos que, hasta que exista el envío de emails, queda escrito
en el log del servidor (ver "Código de verificación" más arriba).

## Funcionalidades

La columna "Datos" dice de dónde sale la información de cada pantalla: del
backend (API) o del navegador (locales, hasta que exista el servicio).

| Ruta | Pantalla | Acceso | Datos |
|---|---|---|---|
| `/` | Catálogo: evento destacado, filtros por categoría, buscador con demora e insensible a acentos, grilla de eventos, estados de carga, error y vacío, y bloque de reventa oficial | Pública | API (eventos); la franja de reventa es local |
| `/evento/:slug` | Detalle del evento: mapa de sectores, selección de cantidad por sector, resumen sticky con total (en pantalla angosta, el total y el botón van en una barra fija abajo) | Pública | API |
| `/checkout` | Checkout en 3 pasos: datos de cada titular, pago con la reserva hecha y su cuenta regresiva (pasarela, tarjeta o transferencia; se puede cancelar) y confirmación | Logueado | API (`ServicioDeVentas`) |
| `/confirmacion/:ordenId` | Confirmación de compra: "emitiendo tus entradas…" hasta que termina la emisión, un QR por entrada, comprobante, imprimir, agregar al calendario | Logueado | API |
| `/mis-entradas` | Visor de entradas con QR, publicación en reventa con validación de tope, historial | Logueado | Copia en el navegador de lo que devolvió la API (el backend ya tiene `GET /api/v1/ventas`, pero esta pantalla todavía no lo usa); la reventa es local |
| `/ingresar` | Login (con las credenciales de prueba visibles en pantalla) | Pública | API |
| `/registro` | Alta de cuenta en 2 pasos: datos y verificación de email con código | Pública | API |
| `/reventa` | Reventa oficial: filtros, listado con tope, compra con confirmación | Pública (comprar requiere login) | Locales (falta `ServicioDeReventa`) |
| `/organizador` | Panel del organizador: stats, ventas por día, mis eventos, ocupación por sector, monitor de reventa, liquidación, **crear evento** | Rol `organizer` | API para "Mis eventos" y crear evento; el resto, datos fijos |
| `/puerta` | Validación en puerta: visor de escaneo simulado, tres estados (válida / usada / inválida), contadores y últimos escaneos | Rol `staff` | Datos fijos (falta `ServicioDeValidación`) |

Si no hay sesión, las rutas protegidas redirigen a `/ingresar?next=<ruta>` y
vuelven a la ruta original después del login. Si la sesión no tiene el rol
requerido, redirigen al catálogo. Si el backend deja de aceptar el token (venció
a las 8 horas o se reinició el servidor), la sesión se cierra con un aviso.

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
7. Cerrar sesión y volver a entrar con otra cuenta (o abrir `/registro` para crear
   una nueva), ir a `/reventa` y comprar esa publicación.
8. Entrar como `puerta@palco.test`, ir a `/puerta` y probar los tres estados de
   escaneo (válida / usada / inválida) y "Marcar ingreso".
9. Entrar como `organizador@palco.test` y revisar `/organizador`: stats, ventas
   por día, ocupación por sector, monitor de reventa y liquidación.
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

Hay tests en `common` y en los servicios de Usuarios, Ventas, Pagos y
Notificaciones, y corren sin WildFly. La prueba en vivo de pagos simultáneos
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
│   ├── eventos.ts        # /api/eventos: listar, detalle, crear
│   └── ventas.ts         # /api/v1/ventas y /entradas: iniciar, consultar, pagar, cancelar
├── store/
│   ├── SesionContext.tsx # Quién está logueado: ingresar, verificar email, salir
│   ├── AppContext.tsx    # Catálogo del servidor + compras guardadas y reventa local (reducer y localStorage)
│   └── actions.ts        # Tipos de acciones del reducer
├── data/
│   ├── events.ts         # Fotos locales de los eventos (por id y por categoría)
│   ├── listings.ts       # 4 publicaciones de reventa iniciales
│   └── organizer.ts      # Métricas mock del panel del organizador
├── lib/
│   ├── api.ts            # Cliente HTTP: URL base, JSON, token, errores, sesión perdida
│   ├── sesion.ts         # Lee y guarda la sesión en localStorage
│   ├── format.ts         # Formato de moneda/fechas/DNI, cálculo de tope y servicio, sleep()
│   ├── validate.ts       # Validaciones de formularios
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
- **Catálogo y datos locales** en
  [`AppContext.tsx`](frontend/src/store/AppContext.tsx) con `useReducer`:
  `eventos` es una copia de `GET /api/eventos` que se pide al abrir la app y no
  se guarda; `ordenes` y `entradas` son copias de lo que devolvió
  `ServicioDeVentas` (el backend ya tiene `GET /api/v1/ventas`, pero el frontend
  todavía no lo usa);
  `publicaciones` es local y `carrito` es la compra en curso, con el id de la
  venta cuando el cupo ya está reservado. Todo eso se guarda en
  `localStorage['palco.state.v2']`.
- **Acciones del reducer**: `CATALOGO_CARGANDO`, `CATALOGO_CARGADO`,
  `CATALOGO_FALLO` y `EVENTO_RECIBIDO` para el catálogo; `SET_CARRITO` y
  `GUARDAR_COMPRA` para la compra; `PUBLICAR_REVENTA`, `RETIRAR_PUBLICACION`,
  `COMPRAR_REVENTA` y `MARCAR_ENTRADA_USADA` para los datos locales.
- **Validaciones**: los formularios validan en el navegador con las mismas
  reglas y textos que el backend, y además muestran en cada campo los errores que
  devuelve el servidor. Las reglas de lo que sigue local (tope, máximo por orden,
  DNI único, cierre de reventa) se validan en las páginas antes de despachar la
  acción; el reducer solo aplica los cambios.

## Decisiones de diseño

- **Integración por etapas**: el frontend usa el backend en todo lo que el
  backend ya ofrece (usuarios, eventos y ventas) y deja local lo demás. La
  reventa sigue validándose en el cliente (tope de precio) y vive en el
  `localStorage` del navegador, así que se pueden simular varios usuarios en el
  mismo navegador (publicar con una cuenta y comprar con otra). El porqué de
  cada decisión está en la sección 15 de
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
- **QR determinístico dibujado a mano**: en vez de una librería, el componente
  `QRCode` genera una grilla 21×21 a partir de un hash simple del código de la
  entrada (el `codigoQr` que genera el backend al emitirla), con los tres
  cuadrados de orientación fijos en las esquinas. Es estable para un mismo
  código y distinto entre entradas, pero no es escaneable.
- **Carrito en el store**: la selección de sectores del detalle de evento se
  guarda en `state.carrito`, junto con el id de la venta cuando ya hay reserva
  (por eso recargar el checkout retoma la misma compra). Lo vacía la pantalla
  de confirmación.
- **Extensiones internas `propietarioId` y `compradorId`**: cada entrada y cada
  orden guardadas en el navegador llevan el id del usuario (el real, del
  backend) al que pertenecen, para filtrar "Mis entradas" y el historial cuando
  varias cuentas usan el mismo navegador. No se muestran en la UI.
- **Tailwind 3 en vez de 4**: se fijó la versión 3.x para poder declarar los
  tokens de color en `tailwind.config.js` con la sintaxis clásica.
- **Imágenes de eventos**: los 6 eventos de prueba usan fotos reales de dominio
  público guardadas en `frontend/public/eventos/` (una por evento, más una
  genérica por categoría; fuentes en `CREDITOS.md` de esa carpeta). El backend
  todavía devuelve URLs de relleno, así que `imagenDeEvento()` las reemplaza por
  la foto local. Los eventos creados desde el panel del organizador usan la foto
  de su categoría: el backend no tiene todavía dónde subir imágenes.
- **Crear evento**: el modal "+ Crear evento" en `/organizador` manda el evento
  a `POST /api/eventos` (el id, el slug y los ids de sector los genera el
  servidor) y agrega la respuesta al catálogo en memoria. Por eso aparece de
  inmediato en el catálogo y en "Mis eventos".
- **Login para comprar en reventa**: comprar una publicación sin sesión muestra
  un toast ("Iniciá sesión para comprar…") y redirige a `/ingresar?next=/reventa`.

## Limitaciones conocidas

La mayoría se resuelven con el backend. El plan de trabajo está en
[`TODO.md`](TODO.md).

- **Token en `localStorage`**: las contraseñas ya no se guardan en el navegador
  y los roles los valida el backend. El token de sesión queda en `localStorage`,
  donde un script inyectado en la página podría leerlo; hoy el frontend no carga
  scripts de terceros.
- **Aviso de CORS con un token inválido**: si el backend deja de aceptar el
  token, la sesión se cierra bien, pero en la consola del navegador queda un
  aviso "blocked by CORS policy" por cada pedido rechazado. Ese 401 sale del
  servidor sin cabeceras CORS; se corrige en el backend, no en el frontend.
- **"Mis entradas" depende del navegador**: el frontend recuerda en
  `localStorage` qué compró cada cuenta. El backend ya ofrece el listado
  (`GET /api/v1/ventas`), pero la pantalla todavía no lo usa. En otro dispositivo, o si se borra el
  almacenamiento, las compras no aparecen (siguen existiendo en el backend).
- **Reventa sin backend**: las publicaciones viven solo en el navegador donde
  se crearon y sus reglas se controlan en el cliente. El backend no se entera
  de que una entrada se publicó o se vendió.
- **`/puerta` es una simulación**: los estados de escaneo, los contadores y los
  últimos escaneos son locales a la pantalla y no leen ni modifican las entradas
  reales del store. La acción `MARCAR_ENTRADA_USADA` existe pero todavía no se usa.
- **Métricas del organizador**: las ventas por día, el monitor de reventa y la
  liquidación usan datos mock fijos. "Mis eventos" sale del backend y lista solo
  los del organizador (`GET /api/eventos/mios`); los seis de prueba son de
  `organizador@palco.test`.
- **Registro**: la verificación de identidad (fotos del DNI) no tiene backend y
  se quitó del alta; las cuentas nuevas quedan con `identidadVerificada` en
  falso. Como `ServicioDeVentas` exige identidad verificada, hoy no pueden
  comprar: el checkout lo avisa al entrar ("Falta verificar tu identidad") en
  lugar de pedir los titulares, y la pantalla de ingreso aclara que para
  comprar hay que usar `titular@palco.test`. Falta que el backend ofrezca cómo
  verificar la identidad: es una decisión pendiente del grupo.
- **Pagos y emails simulados**: el cobro pasa por `ServicioDePagos`, pero su
  pasarela es simulada (no se procesa ningún pago real; el formulario de tarjeta no manda sus datos, solo un token
  de prueba) y no se envía ningún email. El código de verificación del registro
  es real, pero se lee del log del servidor.
- **Sin tests en el frontend**: los tests automatizados son del backend (ver
  [Tests](#tests)).
