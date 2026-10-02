# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping** — entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre lo pagado.

El repo tiene dos partes:

- **`frontend/`**: SPA en React. Hoy funciona sola: todo el estado (sesión,
  usuarios, eventos, entradas, órdenes y publicaciones de reventa) vive en
  `localStorage` bajo la clave `palco.state`, con datos mock y latencia simulada
  con `sleep()`.
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

La app queda en `http://localhost:5173`.

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

También se pueden crear cuentas nuevas (rol `user`) desde `/registro`. En el paso
de verificación de email se acepta cualquier código de 6 dígitos.

## Funcionalidades

| Ruta | Pantalla | Acceso |
|---|---|---|
| `/` | Catálogo: evento destacado, filtros por categoría, buscador con debounce e insensible a acentos, grilla de eventos, estado vacío y bloque de reventa oficial | Pública |
| `/evento/:slug` | Detalle del evento: mapa de sectores, selección de cantidad por sector, resumen sticky con total | Pública |
| `/checkout` | Checkout en 3 pasos: reserva con cuenta regresiva, datos de cada titular, medio de pago (pasarela, tarjeta o transferencia) | Logueado |
| `/confirmacion/:ordenId` | Confirmación de compra: QR, comprobante, imprimir, agregar al calendario | Logueado |
| `/mis-entradas` | Visor de entradas con QR, publicación en reventa con validación de tope, historial | Logueado |
| `/ingresar` | Login (con las credenciales de prueba visibles en pantalla) | Pública |
| `/registro` | Alta de cuenta en 3 pasos: datos, identidad, verificación de email | Pública |
| `/reventa` | Reventa oficial: filtros, listado con tope, compra con confirmación | Pública (comprar requiere login) |
| `/organizador` | Panel del organizador: stats, ventas por día, mis eventos, ocupación por sector, monitor de reventa, liquidación, **crear evento con imagen** | Rol `organizer` |
| `/puerta` | Validación en puerta: visor de escaneo simulado, tres estados (válida / usada / inválida), contadores y últimos escaneos | Rol `staff` |

Si no hay sesión, las rutas protegidas redirigen a `/ingresar?next=<ruta>` y
vuelven a la ruta original después del login. Si la sesión no tiene el rol
requerido, redirigen al catálogo.

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
10. Desde `/organizador`, tocar "+ Crear evento": subir una imagen (drag & drop o
    click), cargar título/venue/fecha/sectores y confirmar — el evento nuevo
    aparece al toque en el catálogo y en "Mis eventos".

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
├── main.tsx              # Punto de entrada: Router, AppProvider, ToastProvider
├── App.tsx               # Definición de rutas y rutas protegidas
├── index.css             # Estilos base y fuentes
├── types/index.ts        # Modelo de datos y constantes de negocio
├── store/
│   ├── AppContext.tsx    # Estado global, reducer y persistencia en localStorage
│   └── actions.ts        # Tipos de acciones del reducer
├── data/
│   ├── events.ts         # 6 eventos semilla
│   ├── listings.ts       # 4 publicaciones de reventa iniciales
│   └── organizer.ts      # Métricas mock del panel del organizador
├── lib/
│   ├── format.ts         # Formato de moneda/fechas/DNI, cálculo de tope, sleep()
│   ├── validate.ts       # Validaciones de formularios
│   └── useAnimatedNumber.ts
├── components/           # Componentes reutilizables (Button, Input, Modal, QRCode,
│                         # Stepper, Toast, CreateEventModal, ProtectedRoute, etc.)
└── pages/                # Una página por ruta
```

## Arquitectura y estado

- **Un solo store** en [`AppContext.tsx`](frontend/src/store/AppContext.tsx) con
  `useReducer`. Guarda `usuarioActualId`, `usuarios`, `eventos`, `entradas`,
  `ordenes`, `publicaciones` y el `carrito`.
- **Persistencia**: cada cambio de estado se serializa completo en
  `localStorage['palco.state']`. Al cargar, el estado guardado se combina con los
  datos semilla, así que si falta alguna clave se usa el valor por defecto.
- **Acciones del reducer**: `LOGIN`, `LOGOUT`, `REGISTER`, `SET_CARRITO`,
  `CREAR_ORDEN`, `PUBLICAR_REVENTA`, `RETIRAR_PUBLICACION`, `COMPRAR_REVENTA`,
  `MARCAR_ENTRADA_USADA` y `CREAR_EVENTO`.
- **Validaciones**: las reglas de negocio (tope, cupo, máximo por orden, DNI
  único, cierre de reventa) se validan en las páginas antes de despachar la
  acción. El reducer solo aplica los cambios.

## Decisiones de diseño

- **Sin backend real**: como no hay servidor, todo el negocio (stock, tope de
  reventa, unicidad de DNI por orden) se valida en el cliente. Como todas las
  cuentas comparten el mismo `localStorage`, se pueden simular varios usuarios en
  el mismo navegador (por ejemplo, publicar con una cuenta y comprar con otra).
- **QR determinístico dibujado a mano**: en vez de una librería, el componente
  `QRCode` genera una grilla 21×21 a partir de un hash simple del id de la
  entrada, con los tres cuadrados de orientación fijos en las esquinas. Es estable
  para un mismo id y distinto entre entradas, pero no es escaneable.
- **Carrito en el store**: la selección de sectores del detalle de evento se
  guarda en `state.carrito` y se limpia automáticamente al crear la orden.
- **Extensión interna `propietarioId`**: además de los campos del modelo de
  datos, cada entrada guarda a qué cuenta pertenece, para poder filtrar "Mis
  entradas" sin backend. No se muestra en la UI.
- **Tailwind 3 en vez de 4**: se fijó la versión 3.x para poder declarar los
  tokens de color en `tailwind.config.js` con la sintaxis clásica.
- **Imágenes de eventos**: los 6 eventos semilla usan fotos determinísticas de
  `picsum.photos/seed/<slug>` (mismo slug, misma foto). Los eventos creados desde
  el panel del organizador guardan la imagen subida como data URL (base64) dentro
  del objeto del evento, porque sin backend no hay otro lugar donde alojarla.
- **Crear evento**: el modal "+ Crear evento" en `/organizador` genera un `Evento`
  completo (slug único, sectores con cupo y precio, imagen) y lo agrega a
  `state.eventos`. Por eso aparece de inmediato en el catálogo y en "Mis eventos",
  que se calcula en vivo desde el store.
- **Login para comprar en reventa**: comprar una publicación sin sesión muestra
  un toast ("Iniciá sesión para comprar…") y redirige a `/ingresar?next=/reventa`.

## Limitaciones conocidas

La mayoría se resuelven con el backend. El plan de trabajo está en
[`TODO.md`](TODO.md).

- **No hay seguridad real**: las contraseñas se guardan en texto plano en
  `localStorage` y los roles se controlan solo en el cliente. Es una demo.
- **`/puerta` es una simulación**: los estados de escaneo, los contadores y los
  últimos escaneos son locales a la pantalla y no leen ni modifican las entradas
  reales del store. La acción `MARCAR_ENTRADA_USADA` existe pero todavía no se usa.
- **Métricas del organizador**: las ventas por día, el monitor de reventa y la
  liquidación usan datos mock fijos. Solo "Mis eventos" se calcula desde el store.
- **Tamaño de `localStorage`**: las imágenes subidas se guardan en base64 y el
  navegador suele limitar `localStorage` a unos 5 MB, así que crear muchos
  eventos con imágenes grandes puede llenar el almacenamiento.
- **Pagos y emails simulados**: no se procesa ningún pago ni se envía ningún
  email. El código de verificación del registro acepta cualquier valor de 6 dígitos.
- **Sin tests automatizados**.
