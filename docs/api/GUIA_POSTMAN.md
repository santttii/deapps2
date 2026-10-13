# Guía Postman — API de Palco

## 1. Instalar y preparar
1. Descargá Postman (postman.com/downloads) y abrilo. Podés usarlo sin cuenta ("Skip and go to the app").
2. Levantá el backend: `cd backend && ./dev.sh` y comprobá `http://localhost:8080/api/eventos` en el navegador.

## 2. Importar la colección (ya trae todos los requests)
`Import` → arrastrá `docs/api/palco-api.postman_collection.json`. Quedan 3 carpetas:
- **1 Usuarios**: registro, registro inválido, verificación, reenvío, login, login incorrecto, perfil, perfil sin token, JWKS.
- **2 Eventos**: listar, filtrar, detalle, 404, crear sin token, crear organizer, crear inválido, cambiar cupo.
- **3 Diseño v1**: ventas, pagos, entradas, reventas (incluye el 422), DELETE y escaneo. Son los del PDF; **todavía no existen en el backend**, van a dar 404 hasta implementarlos.

Las URLs base son variables de la colección (`baseUsuarios`, `baseEventos`, `baseApi`, `token`, `slug`); se editan en la colección → pestaña **Variables**.

## 3. Orden para correrlos
1. Usuarios → *Login* (guarda el token solo en `{{token}}`). Hace falta un usuario ya verificado. Si no, *Registro* → *Verificar* con el código que genere el servicio.
2. Eventos → *Listar* (guarda el `slug` del primero) → *Detalle*.
3. Para *Crear evento* y *Cambiar cupo* el usuario debe tener rol organizer / servicio.

## 4. Cómo crear un request a mano (los 4 tipos)
`New` → `HTTP`, elegí método y pegá la URL.

| Tipo | Qué cargar |
|---|---|
| **GET** | Solo URL; filtros en `Params` (`categoria`, `q`). |
| **POST** | `Body` → `raw` → `JSON`; header `Content-Type: application/json`. En compras sumar `Idempotency-Key: {{$guid}}`. |
| **PUT / PATCH** | Igual que POST. PATCH con `If-Match: <etag>` si el recurso usa ETag. |
| **DELETE** | Solo URL; espera 204 sin cuerpo. |

Autenticación: pestaña `Authorization` → `Bearer Token` → `{{token}}`.

## 5. Tests (pestaña Scripts → Post-response)
```js
pm.test("201 Created", () => pm.response.to.have.status(201));
pm.test("trae Location", () => pm.response.to.have.header("Location"));
pm.collectionVariables.set("token", pm.response.json().token);
```

## 6. Correr todo y evidenciar
Click derecho en la colección → `Run` → `Run Palco API` → captura del resultado con los tests en verde. Pegala en el PDF para el bonus.
Exportar: `...` → `Export` (Collection v2.1).
