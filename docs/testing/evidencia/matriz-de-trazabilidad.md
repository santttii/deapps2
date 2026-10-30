# Matriz de trazabilidad

Generada por `scripts/qa/matriz.mjs` el 2026-10-30 13:08 UTC.

| HU | Prioridad | Criterio | Casos | Resultado |
|---|---|---|---|---|
| HU-01 Registrarme y verificar mi email | Must | CA-01.1 Registro y verificación correctos | — | SIN CASO |
| HU-01 Registrarme y verificar mi email | Must | CA-01.2 Email o DNI ya registrados | — | SIN CASO |
| HU-01 Registrarme y verificar mi email | Must | CA-01.3 Datos inválidos | — | SIN CASO |
| HU-01 Registrarme y verificar mi email | Must | CA-01.4 Código incorrecto | — | SIN CASO |
| HU-01 Registrarme y verificar mi email | Must | CA-01.5 Campos que no se pueden elegir (mass assignment) | — | SIN CASO |
| HU-02 Iniciar sesión | Must | CA-02.1 Login correcto | — | SIN CASO |
| HU-02 Iniciar sesión | Must | CA-02.2 Contraseña incorrecta | — | SIN CASO |
| HU-02 Iniciar sesión | Must | CA-02.3 Fuerza bruta | — | SIN CASO |
| HU-02 Iniciar sesión | Must | CA-02.4 Token inválido | escenario 5 (escenarios.mjs: pasa) | PASA |
| HU-02 Iniciar sesión | Must | CA-02.5 Email sin verificar | — | SIN CASO |
| HU-03 Recuperar mi contraseña | Should | CA-03.1 Reinicio correcto | — | SIN CASO |
| HU-03 Recuperar mi contraseña | Should | CA-03.2 Email que no existe | — | SIN CASO |
| HU-03 Recuperar mi contraseña | Should | CA-03.3 Código incorrecto | — | SIN CASO |
| HU-04 Consultar el catálogo | Must | CA-04.1 Listado y detalle | — | SIN CASO |
| HU-04 Consultar el catálogo | Must | CA-04.2 Búsqueda sin resultados | — | SIN CASO |
| HU-04 Consultar el catálogo | Must | CA-04.3 Evento inexistente | — | SIN CASO |
| HU-04 Consultar el catálogo | Must | CA-04.4 Entrada maliciosa en la búsqueda | — | SIN CASO |
| HU-05 Comprar entradas | Must | CA-05.1 Compra completa | escenario 1 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.2 Pago rechazado | escenario 3 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.3 Reglas de la compra | escenario 4 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.4 Idempotencia | escenario 2 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.5 Falla externa: Pagos no responde | escenario 14 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.6 Falla externa: Eventos no responde al reservar | escenario 18 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.7 Reserva vencida o cancelada | escenario 6 (escenarios.mjs: pasa) | PASA |
| HU-05 Comprar entradas | Must | CA-05.8 Identidad sin verificar | — | SIN CASO |
| HU-05 Comprar entradas | Must | CA-05.9 Mensajería: emisión una sola vez | escenario 7 (escenarios.mjs: pasa)<br>escenario 8 (escenarios.mjs: pasa)<br>escenario 9 (escenarios.mjs: pasa)<br>escenario 10 (escenarios.mjs: pasa)<br>escenario 11 (escenarios.mjs: pasa)<br>escenario 12 (escenarios.mjs: pasa)<br>escenario 13 (escenarios.mjs: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.1 Factura emitida | escenario 15 (escenarios.mjs: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.2 Una sola factura por compra | escenario 15 (escenarios.mjs: pasa) | PASA |
| HU-06 Recibir la factura de mi compra | Should | CA-06.3 Falla externa: ARCA no responde o rechaza | — | SIN CASO |
| HU-06 Recibir la factura de mi compra | Should | CA-06.4 Factura de otra persona | — | SIN CASO |
| HU-07 Ver mis entradas | Must | CA-07.1 Mis compras | — | SIN CASO |
| HU-07 Ver mis entradas | Must | CA-07.2 Sin compras | — | SIN CASO |
| HU-07 Ver mis entradas | Must | CA-07.3 Entrada de otra persona (BOLA) | escenario 5 (escenarios.mjs: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.1 Publicación dentro del rango | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.2 Precio fuera de rango | — | SIN CASO |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.3 Entrada que no es mía o no está disponible | — | SIN CASO |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.4 Retirar la publicación | — | SIN CASO |
| HU-08 Publicar una entrada en la reventa | Must | CA-08.5 Entrada publicada en la puerta | — | SIN CASO |
| HU-09 Comprar en la reventa | Must | CA-09.1 Compra en la reventa | escenario 17 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.2 Pago rechazado | — | SIN CASO |
| HU-09 Comprar en la reventa | Must | CA-09.3 Publicación propia o ya vendida | — | SIN CASO |
| HU-09 Comprar en la reventa | Must | CA-09.4 Falla externa: Pagos no responde | escenario 19 (escenarios.mjs: pasa) | PASA |
| HU-09 Comprar en la reventa | Must | CA-09.5 Cobrado y sin entrada | escenario 20 (escenarios.mjs: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.1 Alta de evento | — | SIN CASO |
| HU-10 Crear y editar un evento | Must | CA-10.2 Datos inválidos | — | SIN CASO |
| HU-10 Crear y editar un evento | Must | CA-10.3 Permisos (BFLA y BOLA) | escenario 5 (escenarios.mjs: pasa) | PASA |
| HU-10 Crear y editar un evento | Must | CA-10.4 Panel con mis eventos | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-11 Cancelar un evento | Should | CA-11.1 Cancelación con reembolso | — | SIN CASO |
| HU-11 Cancelar un evento | Should | CA-11.2 Evento de otro organizador | — | SIN CASO |
| HU-11 Cancelar un evento | Should | CA-11.3 Comprar un evento cancelado | — | SIN CASO |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.1 Resumen propio | — | SIN CASO |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.2 Eventos ajenos | — | SIN CASO |
| HU-12 Ver el resumen de ventas de mis eventos | Could | CA-12.3 Rol sin permiso | — | SIN CASO |
| HU-13 Validar una entrada en la puerta | Must | CA-13.1 Primer escaneo | escenario 16 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.2 Segundo escaneo | escenario 16 (escenarios.mjs: pasa) | PASA |
| HU-13 Validar una entrada en la puerta | Must | CA-13.3 Código inexistente o de otro evento | — | SIN CASO |
| HU-13 Validar una entrada en la puerta | Must | CA-13.4 Falla externa: Ventas no responde | — | SIN CASO |
| HU-13 Validar una entrada en la puerta | Must | CA-13.5 Personal no asignado | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.1 Asignar y quitar | escenario 21 (escenarios.mjs: pasa) | PASA |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.2 Evento de otro organizador | — | SIN CASO |
| HU-14 Asignar personal a la puerta de mi evento | Should | CA-14.3 Cuenta que no es de puerta | — | SIN CASO |

61 criterios de 14 historias: 22 pasan, 0 fallan, 39 sin caso.
