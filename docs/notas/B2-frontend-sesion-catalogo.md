# B2 — Frontend conectado a Usuarios y Eventos

Nota para la defensa oral. Dueña de la tarea: Valentina Frisoli. Hecha el 09/10/2026.

## Qué se conectó

El frontend usa el backend real para ingresar, registrarse (con el código de verificación y el reenvío), leer el perfil, listar y buscar eventos, ver el detalle y crear un evento. `lib/api.ts` es el único archivo que llama a `fetch`; `servicios/usuarios.ts` y `servicios/eventos.ts` tienen una función por endpoint.

## Qué sigue con datos locales y por qué

El checkout, "Mis entradas", la reventa, la puerta y las estadísticas del organizador siguen en el navegador, porque Ventas, Reventa y Validación todavía no existen. Las compras y las publicaciones ahora quedan a nombre del usuario real (su id y su DNI vienen del backend). El checkout llama a una sola función, `confirmarCompra()` en `servicios/ventas.ts`: cuando exista Ventas se cambia su cuerpo y la página no se toca.

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **Dos contextos.** `SesionContext` guarda la sesión y `AppContext` el catálogo y los datos locales; la alternativa era uno solo. Son responsabilidades distintas. Los eventos quedan en memoria porque las pantallas locales los buscan por id y el backend solo busca por slug.
2. **Sin TanStack Query.** Se usa `fetch` con `useEffect`: son pocas llamadas, y la librería sumaba conceptos que habría que saber explicar.
3. **Token en `localStorage`**, junto con el usuario y el vencimiento. En memoria no sobrevive a recargar, y una cookie HttpOnly exigía cambiar el backend. Se lee de forma síncrona al iniciar, para que la ruta protegida no redirija de más, y después se confirma con `GET /yo`.
4. **Sesión perdida.** El backend rechaza un token inválido sin cabeceras CORS, y el navegador lo informa como error de red. Si falla un pedido con token, `api.ts` consulta el `health` de ese servicio: si responde, lo rechazado fue el token y se cierra la sesión con un aviso. La alternativa dejaba al usuario aparentemente logueado.
5. **Registro en dos pasos.** Se quitó "Identidad" porque el backend no recibe documentos. El paso sale del `state` del router y no de un `useState`: así recargar en el paso del código no vuelve al formulario vacío. Tiene una consecuencia que el grupo todavía no resolvió: ver "Decisión abierta".
6. **Sin respaldo local.** Se borraron los usuarios y los eventos de mentira. Mantenerlos escondía las fallas del backend y creaba datos que el servidor no conoce.
7. **Búsqueda.** Sin filtros se usa la lista ya cargada. Con filtros busca el servidor, con 300 ms de demora y descartando las respuestas viejas. Filtrar en el navegador no aprovechaba la búsqueda del backend.
8. **Crear evento.** Se usa la foto de la categoría porque el backend no tiene subida de imágenes. Los sectores se validan también en el navegador, porque el servidor no dice en qué fila está el error. Si el token cae entre abrir el formulario y enviarlo, se pierde lo escrito.
9. **Stock.** Se muestra el del servidor, que no baja al comprar hasta que exista Ventas. Sumarlo en el navegador daba números que cambiaban al recargar.
10. **Salir.** Desde una página privada termina en `/ingresar?next=<esa página>` y no en la portada, porque la ruta protegida redirige antes. Forzar la portada dependía del orden interno de React.
11. **Parámetro `next`.** Solo se aceptan rutas internas simples, para que un enlace armado por otro no mande a un sitio externo después de ingresar.
12. **Órdenes.** Una orden es del usuario si tiene una entrada suya comprada, no revendida: la entrada de reventa conserva el número de orden del vendedor.
13. **Detalle del evento en pantalla angosta.** El selector de entradas se ve en todos los anchos; en angosto queda debajo de la descripción, y el total con el botón "Continuar" van en la barra fija de abajo. Antes el selector se ocultaba por debajo de 768 px y desde un teléfono no se podía comprar. La alternativa era meter los botones "+" y "–" en la barra fija, pero con varios sectores no entran.

## Correcciones después de la prueba en navegador (09/10)

- **No se podía escribir en "Crear evento".** `Modal` movía el foco al diálogo en el mismo efecto que escucha la tecla Escape, y ese efecto se repetía en cada render porque depende de `onCerrar`, que es una función nueva cada vez. Cada tecla le sacaba el foco al campo. Ahora son dos efectos: el del foco depende solo de `abierto`. La prueba automática no lo había visto porque completa los campos de una vez; se comprobó tipeando tecla por tecla.
- **Textos de reventa.** Decían "titulares verificados" y "tu DNI verificado". Se cambiaron por "titulares registrados" y "tu DNI", porque las cuentas nuevas no pasan por ninguna verificación de identidad.

## Qué se verificó

El 09/10 a la tarde el backend corrigió la falla que hacía responder 500 en las altas (un `check` de la base H2 de desarrollo) y se repitió la prueba en navegador contra el backend real. Pasaron de punta a punta: registro, código incorrecto, reenvío antes y después de los 30 segundos, código correcto, ingreso de una cuenta sin verificar (409 con el enlace "Verificar mi email") y crear evento (aparece en "Mis eventos", en el catálogo con la foto de su categoría y en `GET /api/eventos`).

## Qué queda abierto

- **Aviso de CORS con un token inválido.** El comportamiento es el de la decisión 4 y funciona, pero en la consola del navegador queda un aviso "blocked by CORS policy" por cada pedido rechazado. Lo escribe el navegador y no se puede evitar desde el frontend. Se va cuando el backend mande las cabeceras CORS también en ese 401; ahí se puede sacar de `api.ts` la consulta al `health`.
- **Decisión abierta: identidad sin verificar.** Las cuentas que crea el registro quedan con `identidadVerificada` en falso y el backend no tiene ninguna operación que lo cambie. Hoy no molesta, porque la compra es local. Cuando el checkout se conecte a Ventas, el contrato dice que ese servicio responde 403 `identidad-no-verificada`: quien se registre en la demo no va a poder comprar. Hay que elegir antes de conectar el checkout entre dos caminos: que Usuarios ofrezca cómo verificar la identidad (y vuelva el paso al registro), o que esa regla no se aplique a las cuentas nuevas. También afecta al manual de usuario, que pide una captura del "paso de identidad".

## Cómo probarlo a mano

1. Levantar el backend (`cd backend && ./dev.sh`) y el frontend (`cd frontend && npm run dev`), y abrir `http://localhost:5173`.
2. Buscar "metodo" y elegir "Deportes": en la pestaña Red aparecen `GET /api/eventos?q=metodo` y `?categoria=Fútbol`.
3. Ingresar con `titular@palco.test` / `palco1234`, recargar en "Mis entradas" (no tiene que rebotar) y entrar a `/organizador` (tiene que volver a la portada).
4. En `/registro`, usar el email `titular@palco.test`: el error aparece debajo del campo. Con datos nuevos, el código queda en `backend/servidor/target/server/standalone/log/server.log`.
5. Ingresar con `organizador@palco.test` y crear un evento. Después, como titular, comprar una entrada de "Marea Baja + invitados" y verla en "Mis entradas".
