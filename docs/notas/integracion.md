# Integración Ventas → Pagos (tarea B1)

Nota para la defensa oral. A cargo de Julian Zuchowicki. Cerrada el 13/10/2026 y corregida ese mismo día después de la revisión: lo que figura como probado se corrió ese día en el WildFly de desarrollo.

## Qué se conectó y cómo

Ventas cobraba con un simulador propio. Ahora, al pagar una compra, llama a `ServicioDePagos`, que cobra y guarda el pago: el `pagoId` que muestra la venta es el que guardó Pagos.

- La compra (`ServicioDeVentas`) sigue pidiendo `@Inject Cobros`, una interfaz con un solo método, `cobrar`. Lo nuevo está en `ventas/negocio/externos`: `ClienteDePagos`, `FabricaDeCobros`, la excepción `CobroNoRealizado` y 19 tests sin servidor (el módulo tiene 179).
- La revisión del 13/10 sumó tres cambios chicos fuera de ese paquete, para el reintento después de un 504 (más abajo): `ComprasEnCurso.pagar`, el `UPDATE` de `guardarClaveDePago` y la validación de `ServicioDeVentas.confirmarCompra`. `IVentas` no cambió.
- `ClienteDePagos` hace `POST /api/v1/pagos` con el token de rol `servicio` (`TokenDeServicio`) y reenvía en `Idempotency-Key` la clave que mandó el comprador. Espera 2 segundos para conectar y 10 para leer.

**Por qué es una integración sincrónica por REST entre componentes.** Ventas y Pagos son dos WAR, cada uno con su esquema: Ventas no lee la tabla `pago` ni usa clases de Pagos, solo su API. Es sincrónica porque la venta no se puede confirmar sin saber si el pago pasó, y el comprador espera esa respuesta. La emisión de entradas puede esperar: por eso va por mensajería. El costo: si Pagos no contesta, no se puede pagar.

**Qué patrón es.** `ClienteDePagos` es un Adapter: Pagos habla con códigos HTTP y JSON, la compra espera un `ResultadoDeCobro` o un error de negocio, y esa clase traduce. `FabricaDeCobros` es una Factory, como `FabricaDePasarela` en Pagos. `CobrosFake` no adapta nada: es un simulador. Que las dos se intercambien sin tocar la compra tiene forma de Strategy, pero se elige una vez por configuración, no en cada pedido. `@Typed` en las dos hace que CDI no vea dos `Cobros`: el único inyectable es el que produce la fábrica.

## Qué pasa en cada falla

Entre los dos servicios no hay transacción: el pago queda guardado en Pagos apenas responde y un rollback de Ventas no lo deshace.

| Pagos responde | El comprador recibe | Qué queda |
|---|---|---|
| 201 `APROBADO` | 201; la venta pasa a `CONFIRMADA` y a `EMITIDA` | Un pago `APROBADO` en Pagos con ese `pagoId` |
| 201 `RECHAZADO` | 402 `pago-rechazado` | Venta `EN_CHECKOUT`, sin entradas ni mensaje. Se paga con una clave nueva: con la misma, Pagos devuelve el rechazo guardado |
| 409 `clave-reutilizada` (clave usada en otra venta u otro monto) o 400 | 402 `pago-rechazado`, motivo `clave_reutilizada` o `datos_invalidos` | No se cobró. La venta sigue abierta y se puede cancelar |
| 409 `pago-duplicado` (dos pedidos con la misma clave chocaron al guardarse en Pagos; un 409 sin `type` legible se trata igual) | Nada: se repite el pedido una vez con la misma clave y Pagos devuelve el pago que guardó, sin cobrar de nuevo. Con un `APROBADO`, 201 y la venta se confirma con ese pago | Lo que responda el reintento. Si vuelve a chocar, 504 `sin-respuesta`, como la última fila |
| 401 | Nada: se renueva el token y se reintenta una vez | Lo que responda el reintento |
| Otro 401, 403 o 404 (Pagos sin desplegar), o no se consiguió el token de servicio | 504 `sin-respuesta` | No se cobró: el pedido se frenó antes. `clave_pago` se borra y la venta se puede cancelar o vence como cualquiera |
| 504, otro código, timeout o sin conexión | 504 `sin-respuesta` | No se sabe si se cobró. La venta queda `EN_CHECKOUT` con su `clave_pago`: no vence ni se cancela. Reintentar con la misma clave no cobra dos veces |

Las dos últimas filas le llegan igual al comprador, porque la falla es nuestra y no de su tarjeta. Las distingue `CobroNoRealizado`, que extiende `SinRespuesta`: con ella `ComprasEnCurso` sabe que puede quitar la marca. No sale como 402 porque el comprador leería "pago rechazado" por una caída interna.

**Después de un 504, el reintento con la misma clave.** Es la única forma de cerrar una venta que Pagos pudo haber cobrado.

- Vale aunque la reserva haya vencido. La marca `clave_pago` impidió que alguien cancelara o venciera la venta, así que su cupo sigue reservado. Si se exigiera la reserva vigente, un cobro hecho quedaría sin venta apenas pasan los 10 minutos.
- Necesita la instancia de la compra, que tiene los titulares. Vive 10 minutos desde el último intento y se pierde al redesplegar Ventas.
- El `UPDATE` de la marca se hace igual. Ahora pasa si no hay clave y la reserva está vigente, o si la clave anotada es esa misma: es la base la que confirma que la marca sigue ahí, no una lectura anterior.
- La instancia no puede leer la base antes de cobrar (retendría una conexión). Por eso recuerda la clave del último intento que mandó a cobrar y, si llega esa misma, no exige la reserva vigente.

Lo que no se resuelve solo:

- Si la venta quedó con `clave_pago` y ya no tiene instancia, no hay recuperación hasta la conciliación de C3 (13/11): no se puede pagar, cancelar ni vence, y si Pagos cobró, ese cobro se devuelve a mano. Pagar responde 409 `operacion-en-curso` con el texto "No pagues de nuevo ni empieces otra compra", nunca `reserva-vencida`, que invita a comprar otra vez y pagar dos veces. En el log queda un `WARNING` con la venta y la clave. Pasa tras redesplegar Ventas, si falla la transacción de Ventas (el servidor descarta la instancia) o si el reintento llega más de 10 minutos después.
- Si Pagos no contesta (timeout o conexión cortada), cada intento deja una venta con `clave_pago` y su cupo retenido. Con Pagos sin desplegar ya no: es la fila del 404.
- El 409 `clave-reutilizada` sale como 402, y no como 409: así `clave_pago` se borra y la venta no queda trabada. La sección 5.4 de los contratos ya lo dice, junto con el reintento del 409 `pago-duplicado` y el 504 para cualquier otro código; la regla 3 de la sección 7.3 aclara que una venta con `clave_pago` responde `operacion-en-curso`.

**Muchos pagos a la vez.** El cobro no espera a Pagos con una conexión de la base tomada: son 10 para los 8 servicios, y Pagos necesita una para contestar. En el primer WAR de B1 la venta se leía antes de cobrar: 16 pagos en el mismo instante dieron 10 veces 504 a los 10 segundos, la base quedó congelada esos 10 segundos para todos los servicios y Pagos cobró después. Con el cobro antes de la primera lectura de la base, 16 y 32 pagos simultáneos dieron todos 201, en menos de un cuarto de segundo. La prueba quedó en `backend/servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs`: los tests con Mockito no ven este problema, así que hay que repetirla en la integración final.

## Cómo se elige quién cobra

Propiedad `palco.ventas.cobros` (variable `PALCO_VENTAS_COBROS`): `pagos`, que es el valor por defecto, o `fake`. Se decide en el primer cobro y vale hasta redesplegar Ventas; el log dice cuál quedó.

El defecto es Pagos porque corre en el mismo WildFly y así la demo muestra la llamada real sin configurar nada. Con el simulador por defecto, olvidarse la variable mostraría un cobro de mentira sin que nadie lo note. Si Pagos falla no se pasa solo al simulador, porque confirmaría ventas sin pago: el comprador recibe 504 y en el log queda un `WARNING` con el motivo. Un valor mal escrito tampoco activa el simulador: se usa Pagos y se avisa.

## Cómo probarlo a mano

En Git Bash, desde `backend/`, con WildFly levantado; cada comentario dice lo que tiene que salir.

```bash
H=localhost:8080; V=$H/api/v1; J='Content-Type: application/json'; L=servidor/target/server/standalone/log/server.log
tok() { sed -E 's/.*"token":"([^"]+)".*/\1/'; }; uuid() { node -e "console.log(crypto.randomUUID())"; }
T=$(curl -s -X POST -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' $H/api/usuarios/login | tok)
TS=$(curl -s -X POST -H "$J" -d '{"secreto":"palco-desarrollo"}' $H/api/usuarios/servicios/token | tok)   # rol servicio; vence a los 5 minutos
A=(-H "Authorization: Bearer $T" -H "$J"); P='{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":1}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"}]}'
abrir() { curl -s -i -X POST $V/ventas "${A[@]}" -H "Idempotency-Key: $(uuid)" -d "$P" | tr -d '\r' | grep -i '^location:' | sed 's#.*/##'; }
pagar() { curl -s -i -X POST $V/ventas/$1/pagos "${A[@]}" -H "Idempotency-Key: $3" -d "{\"medioDePago\":\"tarjeta\",\"token\":\"$2\"}" | grep -oE 'HTTP/1.1 [0-9]+|"pagoId":"[^"]*"|problemas/[a-z-]+|\([a-z_]+\)' | tr '\n' ' '; echo; }
ver() { curl -s $V/ventas/$1 "${A[@]}" | grep -oE '"estado":"[A-Z_]+"|"pagoId":[^,]+' | tail -2 | tr '\n' ' '; echo; }
C=$(abrir); K=$(uuid); pagar $C tok_test $K       # 1) Aprobada: 201 y "pagoId":"P-…"
sleep 2; ver $C                                   # "estado":"EMITIDA" y el mismo pagoId
curl -s -H "Authorization: Bearer $TS" $V/pagos/$(ver $C | grep -oE 'P-[0-9a-f]+'); echo   # ese pago, guardado en Pagos: APROBADO, con la venta y el monto
pagar $C tok_test $K; grep -c "de la venta $C: APROBADO" $L   # 2) Misma clave: 201 con el mismo pagoId, y Pagos cobró una sola vez (1)
D=$(abrir); pagar $D tok_rechazado $(uuid)        # 3) Rechazada: 402 pago-rechazado (fondos_insuficientes)
ver $D; grep -c "$D confirmada" $L                # "estado":"EN_CHECKOUT" "pagoId":null y 0: no se confirmó ni se publicó el mensaje
pagar $D tok_test $K                              # 4) 402 (clave_reutilizada): esa clave ya pagó otra venta
K=$(uuid); pagar $D tok_sin_respuesta $K; pagar $D tok_test $K   # 5) 504 sin-respuesta y, con la misma clave, 201
node servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs 16   # 6) Muchos pagos a la vez: "BIEN: los 16 pagos dieron 201 y la base no se congeló"
grep FabricaDeCobros $L | tail -1                 # 7) Quién cobra: "Ventas cobra llamando al servicio de Pagos"
E=$(abrir); sleep 120; K=$(uuid); pagar $E tok_sin_respuesta $K   # 8) Reintento con la reserva vencida (tarda 11 minutos). A los 2 minutos de abrirla: 504
sleep 550; ver $E; pagar $E tok_test $(uuid)      # la reserva venció y el barrido pasó: sigue "EN_CHECKOUT"; con otra clave, 409 operacion-en-curso
pagar $E tok_test $K                              # con la misma clave, 201: la instancia vive 10 minutos desde el 504
grep -c "$E quedó con un intento de pago sin resultado" $L   # 1: el WARNING del pago con otra clave
# Pasar al simulador sin reiniciar (se vuelve con :remove en lugar de :add). Con el simulador, el pagoId no existe en Pagos (404):
NOPAUSE=true servidor/target/server/bin/jboss-cli.bat --connect --command="/system-property=palco.ventas.cobros:add(value=fake)"
cp servicios/ventas/target/ventas.war servidor/target/server/standalone/deployments/palco-ventas.war   # redespliega Ventas
```

**Sin probar en vivo:** el 401, el 400, el 403 y Pagos sin desplegar (exigen redesplegar Usuarios o bajar Pagos; tienen test). Por eso `CobroNoRealizado` se vio solo en los tests: falta ver en el servidor que sale como 504 y que la venta queda sin marca. La llamada real se corrió fuera del servidor contra un puerto cerrado (falla en 0,2 segundos) y contra uno que no contesta (corta a los 10): las dos dan `sin-respuesta`. Tampoco se probó la variable de entorno, que exige reiniciar el servidor; sí la propiedad de sistema de arriba.

## Usuarios → Ventas: para comprar alcanza con el registro (14/10)

**Qué cambió.** Una cuenta queda con `identidadVerificada` en verdadero cuando completa el registro: al verificar el email con el código (`ServicioDeUsuarios.verificarEmail`), que es también lo que habilita el login. Se quitó la operación simulada `POST /api/usuarios/yo/identidad`, que la marcaba a pedido sin revisar nada: ahora responde 404. Ventas no cambió: sigue consultando `GET /api/usuarios/yo` y responde 403 `identidad-no-verificada` si el dato es falso.

**Por qué.** Es una decisión del grupo. El registro (nombre, apellido, DNI único y email verificado) es la verificación que se pide en esta etapa, y un paso aparte que no verifica nada no le suma control al sistema. Validar el DNI contra un servicio externo queda como trabajo futuro: por eso el dato sigue existiendo y Ventas lo sigue controlando, y cuando exista esa validación cambia solo el lugar donde Usuarios lo marca. No hizo falta tocar la base: la columna `identidad_verificada` ya estaba.

**Cómo se probó (14/10).** Test `RegistroCompletoTest` (una cuenta que completa el registro sale con la identidad verificada). En el servidor: cuenta nueva registrada y verificada con el código del log, login, `GET /yo` con `identidadVerificada: true`, y `scripts/demo-entrega3.sh aprobado` con `COMPRADOR_EMAIL` de esa cuenta hasta la venta `EMITIDA`. La colección de Postman (sin los dos requests de la operación quitada) corrió con 0 fallados, y su compra usa `titular@palco.test`.

**Un efecto de redesplegar Usuarios en desarrollo.** Sin `PALCO_JWT_CLAVE_PRIVADA`, Usuarios genera una clave nueva en cada despliegue. Durante unos minutos después del redespliegue, `POST /api/v1/ventas` respondió 504: Eventos devolvía 500 al cambio de cupo, probablemente porque Ventas seguía usando un token de servicio firmado con la clave anterior (dura 5 minutos). Se normalizó solo cuando ese token venció.

## Eventos: un evento que ya empezó no se crea ni se vende (14/10)

**Qué cambió.** `POST /api/eventos` con una `fechaISO` que no es futura responde 400 `datos-invalidos` con `errores.fechaISO`. El cambio de cupo (`POST /api/eventos/{id}/sectores/{id}/cupo`) con cantidad positiva sobre un evento que ya empezó responde 409 `evento-finalizado`, un tipo nuevo declarado en `eventos/negocio/TiposDeProblema`. Con cantidad negativa se acepta siempre. Antes se podía crear un evento del 01/01/2020 y reservarlo y pagarlo.

**Por qué así.**
- Un evento empieza a la hora de su fecha: desde ese momento ya no se reserva. Una reserva hecha antes se puede pagar en sus 10 minutos, porque el cupo ya se tomó.
- La devolución no mira la fecha porque la usan la cancelación, el vencimiento de la reserva y la compra que falla a mitad de camino. Si fallara, el lugar quedaría tomado sin venta.
- La fecha del evento es hora local de Argentina, sin zona, y el servidor puede correr en UTC. Por eso "ahora" sale de un `Clock` en `America/Argentina/Buenos_Aires` y la regla de alta está en `ServicioDeEventos` y no como `@Future` en el DTO: `@Future` compara con la zona del servidor, y en UTC cerraría un evento de las 21 desde las 18.
- `backend/common` no cambió: `ErroresDeNegocio.Conflicto` ya acepta un tipo.

**Cómo se probó (14/10).** `ServicioDeEventosTest`, sin servidor ni base, con el reloj fijo: fecha pasada y fecha igual a "ahora" se rechazan en el alta, una fecha posterior en hora argentina se guarda, la reserva sobre un evento empezado da `evento-finalizado` sin tocar el cupo, y la devolución y la reserva de un evento futuro siguen andando. Sin probar en el servidor: el cambio no se desplegó.

**Pendiente en Ventas.** `ClienteDeEventos` traduce cualquier 409 de Eventos a `cupo-agotado`, así que hoy el comprador recibe 409 `cupo-agotado` con el texto "No quedan suficientes entradas en ese sector." La venta no se crea, pero el motivo que ve no es el real.
