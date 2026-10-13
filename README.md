# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping** — entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre lo pagado.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. La sesión (ingreso, registro, perfil) y el
  catálogo de eventos (listado, búsqueda, detalle, crear evento) usan el
  backend. Lo que todavía no tiene backend (compra, mis entradas, reventa,
  puerta y estadísticas del organizador) sigue con datos locales en
  `localStorage`, asociados al usuario de la sesión.
- **`backend/`**: Jakarta EE sobre WildFly, en construcción. Son 8 servicios
  independientes (un WAR cada uno) desplegados en el mismo WildFly, con el
  broker JMS del taller de mensajería. `ServicioDeUsuarios` y
  `ServicioDeEventos` ya funcionan; el resto tiene solo el esqueleto. El plan completo está en [`TODO.md`](TODO.md).

## Índice

- [Requisitos](#requisitos)
- [Cómo correrlo](#cómo-correrlo)
- [Scripts del frontend](#scripts-del-frontend)
- [Usuarios de prueba](#usuarios-de-prueba)
- [Funcionalidades](#funcionalidades)
- [Reglas de negocio](#reglas-de-negocio)
- [Recorrido sugerido de demo](#recorrido-sugerido-de-demo)
- [Stack](#stack)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Arquitectura y estado](#arquitectura-y-estado)
- [Decisiones de diseño](#decisiones-de-diseño)
- [Limitaciones conocidas](#limitaciones-conocidas)

## Requisitos

- Frontend: Node.js `^20.19.0` o `>=22.12.0` (lo exige Vite 8) y npm
- Backend: Java 25 y Maven 3.9+ (en macOS: `brew install openjdk@25 maven`).
  WildFly no se instala: lo descarga Maven.

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
`http://localhost:8080`, con cada servicio en `/api/<servicio>`. Para comprobar
que anda: `curl localhost:8080/api/eventos/health`.

Sin configurar nada usa **H2 en memoria**: la base se borra al reiniciar y las
migraciones vuelven a cargar los datos de prueba. Para usar **Supabase**,
completar las variables `PALCO_DB_*` del `.env` y volver a correr `./dev.sh`:
las mismas migraciones crean las tablas allá.

| Comando (desde `backend/`) | Qué hace |
|---|---|
| `./dev.sh` | Compila todo, arma WildFly con los 8 servicios y lo levanta |
| `./redesplegar.sh eventos` | Recompila un servicio y lo redespliega sin reiniciar |
| `mvn test` | Corre los tests |
| `mvn clean install` | Arma todo; el server queda en `servidor/target/server`, listo para hostear |

### API disponible

| Servicio | Endpoint | Qué hace |
|---|---|---|
| todos | `GET /api/<servicio>/health` | Estado del servicio y de la base |
| usuarios | `POST /api/usuarios/registro` | Alta de cuenta; manda un código de 6 dígitos al email |
| usuarios | `POST /api/usuarios/verificacion` | `{"email", "codigo"}` → activa la cuenta y devuelve la sesión |
| usuarios | `POST /api/usuarios/verificacion/reenvio` | Código nuevo (uno cada 30 segundos) |
| usuarios | `POST /api/usuarios/login` | `{"email", "password"}` → `{"token", "usuario", ...}` |
| usuarios | `GET /api/usuarios/yo` | Usuario del token (requiere sesión) |
| usuarios | `GET /api/usuarios/jwks` | Clave pública para validar tokens (la usan los otros servicios) |
| eventos | `GET /api/eventos?categoria=Música&q=texto` | Catálogo (filtros opcionales, búsqueda sin acentos) |
| eventos | `GET /api/eventos/{slug}` | Detalle con sectores |
| eventos | `POST /api/eventos` | Crear evento con sectores (rol `organizer`) |
| eventos | `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo` | Sumar (`{"cantidad": 2}`) o liberar (`-2`) entradas vendidas, sin pasarse del cupo (solo para otros servicios) |

Los errores siempre vuelven como `{"estado": 400, "mensaje": "...", "campos": {...}}`.

**Sesión:** el login devuelve un token JWT que se manda en cada pedido como
`Authorization: Bearer <token>` y dura 8 horas. Cerrar sesión es descartarlo.
Sin token, los endpoints protegidos responden 401; con un rol que no
corresponde, 403. Las cuentas de prueba son las mismas de la tabla de abajo.

**Código de verificación:** hasta que exista `ServicioDeNotificaciones` no se
manda por email; aparece en el log del servidor como
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
| `/checkout` | Checkout en 3 pasos: reserva con cuenta regresiva, datos de cada titular, medio de pago (pasarela, tarjeta o transferencia) | Logueado | Locales (falta `ServicioDeVentas`) |
| `/confirmacion/:ordenId` | Confirmación de compra: QR, comprobante, imprimir, agregar al calendario | Logueado | Locales |
| `/mis-entradas` | Visor de entradas con QR, publicación en reventa con validación de tope, historial | Logueado | Locales |
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
4. Completar los datos del segundo titular (o marcar "asignar después por link"),
   elegir medio de pago y confirmar.
5. Ver la confirmación con el QR, ir a "Mis entradas".
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
├── servicios/            # Un WAR por servicio, cada uno con su API en /api/<servicio>
│   ├── usuarios/  eventos/  ventas/  pagos/
│   └── validacion/  reventa/  notificaciones/  facturacion/
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
│   └── ventas.ts         # Enganche del checkout (hoy arma la compra en el navegador)
├── store/
│   ├── SesionContext.tsx # Quién está logueado: ingresar, verificar email, salir
│   ├── AppContext.tsx    # Catálogo del servidor + datos locales (reducer y localStorage)
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
  se guarda; `entradas`, `ordenes`, `publicaciones` y `carrito` todavía no tienen
  backend y se guardan en `localStorage['palco.state']`.
- **Acciones del reducer**: `CATALOGO_CARGANDO`, `CATALOGO_CARGADO`,
  `CATALOGO_FALLO` y `EVENTO_RECIBIDO` para el catálogo; `SET_CARRITO`,
  `CREAR_ORDEN`, `PUBLICAR_REVENTA`, `RETIRAR_PUBLICACION`, `COMPRAR_REVENTA` y
  `MARCAR_ENTRADA_USADA` para los datos locales.
- **Validaciones**: los formularios validan en el navegador con las mismas
  reglas y textos que el backend, y además muestran en cada campo los errores que
  devuelve el servidor. Las reglas de lo que sigue local (tope, máximo por orden,
  DNI único, cierre de reventa) se validan en las páginas antes de despachar la
  acción; el reducer solo aplica los cambios.

## Decisiones de diseño

- **Integración por etapas**: el frontend usa el backend en todo lo que el
  backend ya ofrece (usuarios y eventos) y deja local lo demás. Compra y reventa
  siguen validándose en el cliente (tope de reventa, unicidad de DNI por orden)
  y viven en el `localStorage` del navegador, así que se pueden simular varios
  usuarios en el mismo navegador (publicar con una cuenta y comprar con otra).
  El porqué de cada decisión está en
  [`docs/notas/B2-frontend-sesion-catalogo.md`](docs/notas/B2-frontend-sesion-catalogo.md).
- **Stock**: las entradas disponibles que se muestran son las del servidor. Una
  compra hecha en el navegador no las descuenta hasta que exista
  `ServicioDeVentas`.
- **QR determinístico dibujado a mano**: en vez de una librería, el componente
  `QRCode` genera una grilla 21×21 a partir de un hash simple del id de la
  entrada, con los tres cuadrados de orientación fijos en las esquinas. Es estable
  para un mismo id y distinto entre entradas, pero no es escaneable.
- **Carrito en el store**: la selección de sectores del detalle de evento se
  guarda en `state.carrito` y se limpia automáticamente al crear la orden.
- **Extensión interna `propietarioId`**: además de los campos del modelo de
  datos, cada entrada guarda el id del usuario (el real, del backend) al que
  pertenece, para poder filtrar "Mis entradas" mientras no exista el servicio.
  No se muestra en la UI.
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
- **Compra y reventa sin backend**: las órdenes, las entradas y las
  publicaciones viven solo en el navegador donde se crearon, y sus reglas se
  controlan en el cliente.
- **`/puerta` es una simulación**: los estados de escaneo, los contadores y los
  últimos escaneos son locales a la pantalla y no leen ni modifican las entradas
  reales del store. La acción `MARCAR_ENTRADA_USADA` existe pero todavía no se usa.
- **Métricas del organizador**: las ventas por día, el monitor de reventa y la
  liquidación usan datos mock fijos. "Mis eventos" sale del backend, pero lista
  todos los eventos: la API todavía no dice de qué organizador es cada uno.
- **Registro**: la verificación de identidad (fotos del DNI) no tiene backend y
  se quitó del alta; las cuentas nuevas quedan con `identidadVerificada` en
  falso. Hoy pueden comprar porque la compra es local. Cuando el checkout se
  conecte a `ServicioDeVentas`, que exige identidad verificada, no van a poder:
  es una decisión pendiente del grupo (ver la nota de decisiones).
- **Pagos y emails simulados**: no se procesa ningún pago ni se envía ningún
  email. El código de verificación del registro es real, pero se lee del log del
  servidor.
- **Sin tests automatizados**.
