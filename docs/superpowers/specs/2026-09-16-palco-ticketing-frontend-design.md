# Palco — Plataforma de venta de entradas (frontend-only) — Design Spec

Status: Approved (spec provided in full detail by user; no open questions per explicit user instruction to build without clarification).

## Resumen

Aplicación web de venta de entradas para eventos, "Palco". Trabajo práctico académico
cuyo diferencial de producto es el anti-scalping: entradas nominativas ligadas a un DNI,
y un canal de reventa oficial con tope de precio de +10% sobre lo pagado. Sin backend:
todo el estado vive en memoria (Context + useReducer) persistido en localStorage, con
datos mock tipados en TypeScript. Operaciones async se simulan con `sleep()`.

## Stack

- React 18 + TypeScript + Vite
- React Router v6
- Tailwind CSS (tokens de diseño personalizados, border-radius 0 salvo indicadores de 6px)
- Context + useReducer (sin Redux/Zustand)
- localStorage bajo la clave `palco.state`
- framer-motion (única librería de animación)
- Validaciones de formulario escritas a mano (sin Formik/RHF)
- Fuentes: Archivo (headings/UI) + IBM Plex Mono (datos/metadatos), Google Fonts

## Alcance funcional (10 pantallas)

1. `/` Catálogo — hero destacado, filtros de categoría, buscador con debounce y
   normalización de acentos, grilla de eventos, estado vacío, bloque de reventa oficial.
2. `/evento/:slug` Detalle — mapa de sectores, selector de cantidad por sector con
   stepper, panel sticky de resumen y CTA a checkout.
3. `/checkout` Checkout protegido — stepper de 3 pasos, reserva con countdown de 10
   minutos, formulario de titulares con validación de DNI único, selección de medio de
   pago, resumen sticky, generación de orden y entradas.
4. `/mis-entradas` Mis entradas protegido — visor de entrada con QR determinístico
   dibujado a mano, formulario de publicación en reventa con validación de tope,
   historial.
5. `/ingresar` Login — dos columnas (ingresar / crear cuenta), validación, usuarios de
   prueba documentados en pantalla.
6. `/registro` Alta de cuenta — stepper de 3 pasos (datos, identidad, verificación de
   email por código de 6 dígitos).
7. `/reventa` Reventa oficial — filtros, tabla/­cards con tope, compra con modal de
   confirmación, publicación de entradas propias.
8. `/organizador` Panel del organizador protegido (rol `organizer`) — stats, gráfico de
   ventas, tabla de eventos, ocupación por sector, monitor de reventa, accesos en vivo,
   liquidación.
9. `/puerta` Validación en puerta protegido (rol `staff`) — visor de escaneo simulado,
   tres estados de resultado, contadores en vivo, últimos escaneos.
10. `/confirmacion/:ordenId` Confirmación de compra protegida — tilde animado, QR,
    comprobante, acciones (ver entradas, imprimir, agregar a calendario).

Todos los detalles de contenido, copy, datos mock exactos (6 eventos, 4 publicaciones de
reventa, métricas del organizador), modelo de datos, tokens visuales, reglas de
animación, responsive y accesibilidad están definidos íntegramente en el prompt original
del usuario (reproducido en el histórico de la conversación) y se toman como
especificación literal y vinculante — no se resumen aquí para evitar divergencias; se
implementan tal cual fueron descriptos, incluyendo:

- Modelo de datos en `src/types/index.ts` (Evento, Sector, Entrada, Publicacion,
  Usuario, Orden, Asistente) y constantes de negocio (`TOPE_REVENTA=0.10`,
  `TASA_SERVICIO=0.10`, `MAX_ENTRADAS_POR_ORDEN=6`, `HORAS_LIMITE_REVENTA=3`,
  `MINUTOS_RESERVA=10`).
- Datos mock exactos de 6 eventos con sus sectores/precios/cupos/vendidas, 4
  publicaciones de reventa iniciales, y métricas del panel del organizador.
- Design tokens de Tailwind (colores `paper/desk/ink/graphite/muted/faint/line/edge/
  wire/fill/slab/accent/accentDk/accentLt/onDark/darkLine`), tipografía y escalas.
- Reglas de uso del color `accent` (9 usos permitidos, todo lo demás `ink` sobre
  `paper`).
- Placeholders de imagen (patrón `repeating-linear-gradient`, sin imágenes reales).
- Catálogo completo de animaciones (tabla en la sección 7 del prompt original) con
  `cubic-bezier(0.16,1,0.3,1)`, soporte de `prefers-reduced-motion`.
- Reglas responsive (3 breakpoints) y de accesibilidad (foco visible, aria-live,
  labels, navegación por teclado, contraste 4.5:1).
- Rutas y protección de rutas (redirect a `/ingresar?next=`), tres roles de usuario
  (`user`, `organizer`, `staff`) con credenciales de prueba fijas.

## Orden de construcción (según el prompt original, sección 10)

1. Proyecto Vite + Tailwind con tokens y fuentes; página en blanco con header.
2. Tipos, datos mock, `lib/format.ts`, `lib/validate.ts`.
3. Context + reducer + persistencia en localStorage.
4. Componentes base (Button, Input, Badge, Stepper, StatCard, ProgressBar, Toast,
   Modal, Skeleton, QRCode).
5. Catálogo con filtros, búsqueda y estado vacío.
6. Detalle de evento con selección de sectores y cálculo de total.
7. Login y registro con validaciones.
8. Checkout de 3 pasos con reserva, validaciones y generación de orden.
9. Confirmación y mis entradas con QR.
10. Reventa: publicar con tope, listar, comprar.
11. Panel del organizador.
12. Validación en puerta.
13. Pasada final de animaciones, responsive y accesibilidad.

## Entregable final

`README.md` con credenciales de prueba, recorrido de demo sugerido (catálogo → evento →
checkout → confirmación → publicar en reventa → comprarla desde otra cuenta → validarla
en puerta) y decisiones de diseño tomadas.

## Decisión de proceso

Dado que el usuario proveyó una especificación completa, cerrada y explícitamente
diseñada para no requerir preguntas de aclaración ("está escrito para que el asistente
pueda construir la app sin hacer preguntas"), este spec se toma como aprobado sin ronda
de preguntas, conforme a la prioridad de instrucciones del usuario por sobre el flujo
estándar de brainstorming. Se procede directamente a la fase de plan de implementación.
