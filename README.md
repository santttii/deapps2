# Palco

Plataforma de venta de entradas para eventos. Trabajo práctico académico: el
diferencial de producto es el **anti-scalping** — entradas nominativas ligadas a un
DNI y un canal de reventa oficial con tope de precio de +10% sobre lo pagado.

Sin backend: todo el estado (sesión, entradas, publicaciones de reventa, órdenes)
vive en `localStorage` bajo la clave `palco.state`. Los datos de eventos son mock,
tipados en TypeScript. Todas las operaciones "async" simulan latencia con un
`sleep()`.

## Stack

- React 18 + TypeScript + Vite
- React Router v6
- Tailwind CSS 3 (tokens de diseño propios, `border-radius: 0` en todo salvo
  indicadores de 6px)
- Context + `useReducer` para estado global
- `framer-motion` para animaciones
- Validaciones de formulario escritas a mano

## Cómo correrlo

```bash
npm install
npm run dev
```

## Usuarios de prueba

| Email | Contraseña | Rol |
|---|---|---|
| `titular@palco.test` | `palco1234` | `user` (identidad verificada) |
| `organizador@palco.test` | `palco1234` | `organizer` |
| `puerta@palco.test` | `palco1234` | `staff` |

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
8. Volver a entrar como `puerta@palco.test`, ir a `/puerta` y probar los tres
   estados de escaneo (válida / usada / inválida) y "Marcar ingreso".
9. Entrar como `organizador@palco.test` y revisar `/organizador`: stats, ventas
   por día, ocupación por sector, monitor de reventa y liquidación.

## Decisiones de diseño

- **Sin backend real**: todo el negocio (stock, tope de reventa, unicidad de DNI
  por orden) se valida en el cliente, dentro del reducer de `AppContext`, para
  que el estado sea consistente incluso simulando múltiples cuentas en la misma
  sesión de `localStorage`.
- **QR determinístico dibujado a mano**: en vez de una librería, el componente
  `QRCode` genera una grilla 21×21 a partir de un hash simple del id de la
  entrada, con los tres cuadrados de orientación fijos en las esquinas — estable
  para un mismo id, distinto entre entradas, sin pretender ser escaneable.
- **Carrito efímero en el store**: la selección de sectores en el detalle de
  evento se guarda en `state.carrito` (no en localStorage de forma persistente
  entre sesiones distintas más que lo que ya persiste el store completo) y se
  limpia automáticamente al crear la orden.
- **Extensión interna `propietarioId`**: además de los campos del modelo de datos
  pedido, cada entrada guarda internamente a qué cuenta pertenece, para poder
  filtrar "Mis entradas" sin backend. No se expone en la UI como concepto nuevo.
- **Tailwind 3 en vez de 4**: se fijó la versión 3.x para poder declarar los
  tokens de color en `tailwind.config.js` con la sintaxis clásica pedida en la
  consigna.
