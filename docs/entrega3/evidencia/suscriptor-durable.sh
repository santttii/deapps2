#!/usr/bin/env bash
# Palco - Entrega Parcial N.º 2 - guion que muestra la suscripción durable de auditoría.
#
# Qué hace: detiene el suscriptor de auditoría (AuditoriaDeComprasMDB) con stop-delivery, hace una compra y
# publica un mensaje de prueba, y muestra que el broker guarda las dos copias en la suscripción durable
# mientras el otro suscriptor del tópico (el aviso, que no es durable) y la emisión de entradas siguen
# trabajando. Después reanuda auditoría con start-delivery y muestra que registra las dos filas que no
# había recibido.
#
# Uso, desde la raíz del repo, en Git Bash, con WildFly levantado y los 8 servicios desplegados:
#     bash docs/entrega3/evidencia/suscriptor-durable.sh
# Para guardar la salida:
#     bash docs/entrega3/evidencia/suscriptor-durable.sh > docs/entrega3/evidencia/suscriptor-durable.txt 2>&1
#
# Necesita curl, node (para generar las claves de idempotencia) y un JDK. Se corrió solo en Windows con Git Bash.
# Auditoría queda detenida unos 20 segundos; si el guion se corta, igual la reanuda al salir.
# Deja una venta EMITIDA de 1 entrada de Platea B y un mensaje de prueba (venta "prueba-..."), los dos auditados.
# Si otra persona compra mientras auditoría está detenida, sus mensajes también quedan pendientes: el guion
# los cuenta aparte (mira en el log qué otras ventas se confirmaron en ese rato) y los nombra. Tampoco se
# pierden: auditoría los registra al reanudar.
# Sale con código 0 si todo dio lo esperado y con 1 si no.

# Secreto para pedir el token de rol servicio: PALCO_SECRETO_SERVICIOS, el de backend/.env o el de desarrollo.
SECRETO=${PALCO_SECRETO_SERVICIOS:-$(sed -n 's/^PALCO_SECRETO_SERVICIOS=//p' backend/.env 2>/dev/null)}
SECRETO=${SECRETO:-palco-desarrollo}
export NOPAUSE=true                                   # sin esto, jboss-cli.bat espera una tecla al terminar
# El JDK de jboss-cli: el JAVA_HOME del entorno; si no hay, ~/.jdks/temurin-25 si esa carpeta existe; si no,
# el java del PATH (como scripts/pruebas/escenarios.mjs).
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.jdks/temurin-25" ]; then export JAVA_HOME="$HOME/.jdks/temurin-25"; fi
H=localhost:8080
J="Content-Type: application/json"
B="/subsystem=messaging-activemq/server=default"
MDB="/deployment=palco-notificaciones.war/subsystem=ejb3/message-driven-bean=AuditoriaDeCompras"
CLI=backend/servidor/target/server/bin/jboss-cli.bat
LOG=backend/servidor/target/server/standalone/log/server.log
FALLAS=0

titulo() { echo; echo "----------------------------------------------------------------------"; echo "$1"; echo "Fecha: $(date '+%F %T %z')"; }
token() { sed -E 's/.*"token":"([^"]+)".*/\1/'; }
uuid() { node -e "console.log(crypto.randomUUID())"; }
numero() { grep -o '"result" => [0-9]*' | grep -o '[0-9]*$'; }
# Compara lo que se vio con lo que se esperaba y lo deja escrito.
comprobar() { if [ "$2" = "$3" ]; then echo "  ok     $1: $2"; else echo "  FALLA  $1: $2 (se esperaba $3)"; FALLAS=$((FALLAS + 1)); fi; }

# Mensajes que el broker tiene guardados para el suscriptor durable y todavía no le entregó.
pendientes() { "$CLI" --connect --command="$B/jms-topic=topico.compra-confirmada:count-messages-for-subscription(client-id=palco-auditoria,subscription-name=compras-confirmadas)" | numero; }
entrega_activa() { "$CLI" --connect --command="$MDB:read-attribute(name=delivery-active)" | grep -o '"result" => [a-z]*' | grep -o '[a-z]*$'; }
# Cuántas filas tiene auditoría para una venta.
filas() { curl -s -H "Authorization: Bearer $S" $H/api/notificaciones/auditoria | grep -o "{[^{}]*\"ventaId\":\"$1\"[^{}]*}"; }
contar_filas() { filas "$1" | grep -c .; }
# Cuántas líneas escribió un consumidor por una venta.
lineas() { grep "$1" "$LOG" | grep -c "$2"; }
# Ventas de otra persona que se confirmaron (y publicaron su mensaje) desde la línea $1 del log.
otras_ventas() { tail -n +"$1" "$LOG" | grep 'PAGO_CONFIRMADO publicado en la misma' | grep -v "$V" | grep -o 'V-[0-9a-f]\{8\}' | tr '\n' ' '; }

# Pase lo que pase, auditoría no queda detenida.
trap '"$CLI" --connect --command="$MDB:start-delivery" > /dev/null 2>&1' EXIT

echo "Palco - Entrega Parcial N.º 2 - suscripción durable de auditoría"
echo "Salida de docs/entrega3/evidencia/suscriptor-durable.sh, corrido desde la raíz del repo en Git Bash."

T=$(curl -s -X POST $H/api/usuarios/login -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' | token)
S=$(curl -s -X POST $H/api/usuarios/servicios/token -H "$J" -d "{\"secreto\":\"$SECRETO\"}" | token)

titulo "0. Las suscripciones del tópico topico.compra-confirmada (sin la lista de conexiones de cada una)"
echo "La de auditoría es durable y tiene nombre fijo (clientID más name): el broker le guarda los mensajes aunque"
echo "nadie la esté escuchando. La del aviso no es durable: recibe solo lo que se publica mientras escucha."
"$CLI" --connect --command="$B/jms-topic=topico.compra-confirmada:list-all-subscriptions" | grep -e '"queueName"' -e '"durable"' -e '"clientID"' -e '"selector"' -e '"name"'

titulo "1. Antes: mensajes pendientes de la suscripción durable y entrega de AuditoriaDeComprasMDB"
P0=$(pendientes)
echo "pendientes de la suscripción durable: $P0"
comprobar "AuditoriaDeComprasMDB está recibiendo (delivery-active)" "$(entrega_activa)" "true"

titulo "2. Detener el suscriptor de auditoría: $MDB:stop-delivery"
"$CLI" --connect --command="$MDB:stop-delivery"
DESDE=$(( $(wc -l < "$LOG") + 1 ))                    # lo que el log escriba de acá en adelante es de este rato
comprobar "AuditoriaDeComprasMDB está recibiendo (delivery-active)" "$(entrega_activa)" "false"

titulo "3. Con auditoría detenida se publican dos mensajes: una compra real y un mensaje de prueba"
V=$(curl -s -X POST $H/api/v1/ventas -H "Authorization: Bearer $T" -H "$J" -H "Idempotency-Key: $(uuid)" \
  -d '{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":1}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"}]}' | grep -o 'V-[0-9a-f]\{8\}' | head -1)
echo "POST /api/v1/ventas: venta $V"
echo "POST /api/v1/ventas/$V/pagos: $(curl -s -o /dev/null -w '%{http_code}' -X POST $H/api/v1/ventas/$V/pagos -H "Authorization: Bearer $T" -H "$J" -H "Idempotency-Key: $(uuid)" -d '{"medioDePago":"tarjeta","token":"tok_test"}') (se espera 201)"
PRUEBA=$(curl -s -X POST -H "Authorization: Bearer $S" $H/api/notificaciones/pruebas/pago-confirmado | grep -o '"ventaId":"prueba-[0-9a-f]*"' | grep -o 'prueba-[0-9a-f]*')
echo "POST /api/notificaciones/pruebas/pago-confirmado: mensaje de la venta de prueba $PRUEBA"

sleep 4
titulo "4. Cuatro segundos después, todavía con auditoría detenida"
P1=$(pendientes)
OTRAS=$(otras_ventas "$DESDE")
if [ -n "$OTRAS" ]; then echo "Mientras auditoría estaba detenida, otra persona también compró: $OTRAS(un mensaje pendiente más por cada una)"; fi
comprobar "pendientes de la suscripción durable (esperan los dos mensajes de este guion$([ -n "$OTRAS" ] && echo ' y los de esas ventas'))" "$P1" "$((P0 + 2 + $(echo $OTRAS | wc -w)))"
comprobar "filas de auditoría de $V" "$(contar_filas "$V")" "0"
comprobar "filas de auditoría de $PRUEBA" "$(contar_filas "$PRUEBA")" "0"
comprobar "líneas de AuditoriaDeCompras en el log por esas dos ventas" "$(( $(lineas "$V" AuditoriaDeComprasMDB) + $(lineas "$PRUEBA" AuditoriaDeComprasMDB) ))" "0"
echo "Mientras tanto, lo demás sigue funcionando:"
comprobar "el aviso (suscriptor no durable) recibió su copia de $V" "$(lineas "$V" AvisoDeCompraMDB)" "1"
comprobar "el aviso recibió su copia de $PRUEBA" "$(lineas "$PRUEBA" AvisoDeCompraMDB)" "1"
comprobar "estado de la venta $V (la emisión va por la cola, no depende de auditoría)" \
  "$(curl -s -H "Authorization: Bearer $T" $H/api/v1/ventas/$V | grep -o '"estado":"[A-Z_]*"' | tail -1)" '"estado":"EMITIDA"'

titulo "5. Reanudar el suscriptor de auditoría: $MDB:start-delivery"
"$CLI" --connect --command="$MDB:start-delivery"
comprobar "AuditoriaDeComprasMDB está recibiendo (delivery-active)" "$(entrega_activa)" "true"

sleep 3
titulo "6. Tres segundos después de reanudar: auditoría recibió lo que el broker le había guardado"
comprobar "pendientes de la suscripción durable" "$(pendientes)" "$P0"
comprobar "filas de auditoría de $V" "$(contar_filas "$V")" "1"
comprobar "filas de auditoría de $PRUEBA" "$(contar_filas "$PRUEBA")" "1"
echo "Las dos filas (GET /api/notificaciones/auditoria, rol servicio):"
filas "$V"; filas "$PRUEBA"
echo "Líneas del log de los dos suscriptores por esas ventas (el aviso las recibió al publicarse; auditoría, al reanudar):"
grep -n -e "$V" -e "$PRUEBA" "$LOG" | grep -e 'AvisoDeCompraMDB' -e 'AuditoriaDeComprasMDB'

titulo "Resultado"
if [ "$FALLAS" -eq 0 ]; then
  echo "TODO DIO LO ESPERADO: con el suscriptor durable detenido el broker guardó los 2 mensajes y se los entregó al reanudarlo."
else
  echo "HAY $FALLAS COMPROBACIONES QUE NO DIERON LO ESPERADO."
fi
[ "$FALLAS" -eq 0 ]
