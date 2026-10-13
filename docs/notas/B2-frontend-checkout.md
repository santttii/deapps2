# B2 — La compra del frontend conectada a Ventas

Nota para la defensa oral. Dueña de la tarea: Valentina Frisoli. Hecha el 13/10/2026. Continúa a `B2-frontend-sesion-catalogo.md`.

## Qué se conectó

La compra usa `ServicioDeVentas` (`/api/v1`) de punta a punta: iniciar la compra (reserva el cupo 10 minutos), pagar, cancelar, esperar la emisión y mostrar las entradas en la confirmación y en "Mis entradas". `servicios/ventas.ts` tiene una función por operación y es el único lugar que traduce una venta del backend a los tipos del frontend. Ante un error, las páginas deciden por su tipo (`cupo-agotado`, `pago-rechazado`…) y muestran el texto del backend.

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **La reserva se crea al terminar "Titulares".** El backend pide los titulares en el mismo pedido que reserva y no deja cambiarlos después. Reservar al entrar al checkout obligaba a mandar titulares de relleno. Por eso la cuenta regresiva aparece en "Pago" y, para corregir un titular, se cancela y se empieza de nuevo.
2. **Dos claves de idempotencia.** Iniciar y pagar viajan con la cabecera `Idempotency-Key`: si el pedido llega dos veces con la misma clave, el backend lo hace una sola vez. Deshabilitar el botón no alcanzaba: no cubre un corte de red, donde el pedido puede haber llegado aunque la respuesta se pierda.
   - *Clave de la compra:* es la misma mientras el pedido no cambie (doble clic, reintento). Si cambia un titular o una cantidad se genera otra, porque el backend no compara el cuerpo y devolvería la venta anterior.
   - *Clave del pago:* nace con la reserva y se guarda en el carrito, así sobrevive a recargar. Cambia solo después de un rechazo (402): el backend confirmó que no cobró. Sin respuesta (504, sin conexión) no se sabe si se cobró: se reintenta con la misma, que además es lo único que destraba esa venta.
3. **Cuenta regresiva recalculada.** Cada segundo se calcula "vencimiento que mandó el backend menos la hora actual". Restarle uno a un contador se atrasa con la pestaña en segundo plano y se reinicia al recargar. En cero se cancela la compra para devolver el cupo. Manda el servidor: si el pago responde `reserva-vencida`, se muestra lo mismo.
4. **Espera de la emisión: una consulta por segundo, hasta 30.** Al pagar, la venta queda `CONFIRMADA` y las entradas se emiten después, en un proceso asincrónico (hoy tarda menos de un segundo). La confirmación muestra "Emitiendo tus entradas…" y consulta la venta hasta que pasa a `EMITIDA`. El backend no ofrece WebSockets, y TanStack Query sumaba una librería para lo que resuelve un `setTimeout`. Si se agotan las 30 se avisa que el pago está aprobado y se ofrece "Volver a consultar". Como la confirmación se lee del backend, recargarla la vuelve a mostrar.
5. **"Mis entradas" recuerda las compras en el navegador.** El backend no tiene un listado de ventas por usuario y agregarlo no era de esta tarea. El navegador guarda qué compró cada cuenta (`palco.state.v2`), pero los datos son los que devolvió el backend. Si se cerró la pestaña durante la emisión, al abrir "Mis entradas" se vuelve a consultar esa compra y las entradas aparecen solas. Límite: en otro dispositivo las compras no se ven.
6. **Una cuenta sin identidad verificada se entera al entrar al checkout.** Ventas solo le vende a una cuenta con `identidadVerificada` en verdadero (si no, responde 403 `identidad-no-verificada`), y el registro deja las cuentas nuevas en falso. Antes se podían cargar todos los titulares para recién fallar en "Continuar", sin nada más para hacer. El usuario de la sesión ya trae ese dato, así que el checkout muestra "Falta verificar tu identidad" en lugar del formulario, con "Volver al evento", y no llama a Ventas. No se ofrece un paso para verificarla porque el backend no lo tiene. La regla sigue siendo del backend: su 403 se muestra como cualquier otro error. Si el dato cambiara en el servidor, alcanza con recargar: la sesión se confirma con `GET /api/usuarios/yo` al abrir la aplicación. La pantalla de ingreso aclara que para comprar hay que usar `titular@palco.test`.

## Qué sigue con datos locales y por qué

La reventa, la puerta y las estadísticas del organizador, porque sus servicios todavía no existen. Una entrada comprada en el backend se puede publicar, retirar y vender en la reventa, pero ese estado vive solo en el navegador; por eso, al volver a guardar una compra, no se pisan las entradas ya guardadas.

## Qué se le pide al backend

Un listado de las ventas del usuario; la fecha de la venta (hoy se calcula como vencimiento menos 10 minutos); nombre y apellido del titular por separado; y cómo verificar la identidad de una cuenta nueva: Usuarios no tiene ninguna operación que deje `identidadVerificada` en verdadero, así que hoy solo puede comprar la cuenta sembrada `titular@palco.test` (decisión 6). Cuando exista, el aviso del checkout pasa a ofrecer ese paso.

## Cómo probarlo a mano

1. Ingresar con `titular@palco.test` / `palco1234`, abrir "El método Grönholm", sumar 2 entradas y continuar al checkout.
2. Completar el segundo titular y tocar "Continuar": aparece la cuenta regresiva. Recargar: sigue en "Pago" con el mismo tiempo.
3. Elegir "Tarjeta" y pagar con una terminada en `0002` (se rechaza) y después con una terminada en `0504` ("No pudimos confirmar el pago"; el botón pasa a "Reintentar pago").
4. Pagar con cualquier otra: se ve "Emitiendo tus 2 entradas…" y enseguida la confirmación con dos QR, los titulares y el total. Recargar: se vuelve a ver.
5. En "Mis entradas" están las dos entradas (`T-…`) y la compra en el historial. Publicar una: figura en `/reventa` como "tu publicación" y se puede retirar.
6. Registrar una cuenta nueva (el código queda en el log del servidor), elegir una entrada y continuar al checkout: en lugar de los titulares aparece "Falta verificar tu identidad", con "Volver al evento".
