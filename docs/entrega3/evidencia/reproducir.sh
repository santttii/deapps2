#!/usr/bin/env bash
# Palco - Entrega Parcial N.º 2 - guion que reproduce el proceso asincrónico de punta a punta.
#
# Qué hace: lee la configuración del broker, hace una compra de prueba (abrir y pagar), muestra la venta y la
# entrada unos segundos después, compara los contadores del broker antes y después, busca la venta en el log del
# servidor y en la auditoría, y prueba que un mensaje publicado dentro de una transacción que se deshace no llega
# a ningún consumidor. Los dos últimos pasos muestran el control por rol (401, 403 y 200) y la reserva de cupo
# (vendidas del sector antes de abrir una compra, con la compra abierta y después de cancelarla).
#
# Uso, desde la raíz del repo, en Git Bash, con WildFly levantado y los 8 servicios desplegados:
#     bash docs/entrega3/evidencia/reproducir.sh
# Para guardar la salida:
#     bash docs/entrega3/evidencia/reproducir.sh > docs/entrega3/evidencia/reproduccion.txt 2>&1
#
# Necesita curl, node (para generar las claves de idempotencia) y un JDK. Se corrió solo en Windows con Git Bash.
# No detiene nada ni vacía colas. Deja una venta EMITIDA de 2 entradas de Platea B y una venta CANCELADA de 1 entrada.
# No mueve plata: la pasarela de pagos es simulada. Quién cobra (ServicioDePagos o el simulador local de Ventas) lo
# dice el paso 6, con la última línea que FabricaDeCobros escribió en el log.
# Si otra persona compra al mismo tiempo, las diferencias de los contadores salen más grandes: se repite el guion.

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
CLI=backend/servidor/target/server/bin/jboss-cli.bat
LOG=backend/servidor/target/server/standalone/log/server.log

titulo() { echo; echo "----------------------------------------------------------------------"; echo "$1"; echo "Fecha: $(date '+%F %T %z')"; }
token() { sed -E 's/.*"token":"([^"]+)".*/\1/'; }
uuid() { node -e "console.log(crypto.randomUUID())"; }

# Siete números, en este orden: cola (mensajes agregados, pendientes, consumidores), tópico (mensajes agregados,
# suscripciones, suscripciones durables) y DLQ (pendientes).
contadores() {
  "$CLI" --connect --commands="$B/jms-queue=cola.emision-entradas:read-attribute(name=messages-added),$B/jms-queue=cola.emision-entradas:read-attribute(name=message-count),$B/jms-queue=cola.emision-entradas:read-attribute(name=consumer-count),$B/jms-topic=topico.compra-confirmada:read-attribute(name=messages-added),$B/jms-topic=topico.compra-confirmada:read-attribute(name=subscription-count),$B/jms-topic=topico.compra-confirmada:read-attribute(name=durable-subscription-count),$B/jms-queue=DLQ:read-attribute(name=message-count)" \
    | grep -o '"result" => [0-9]*' | grep -o '[0-9]*$' | tr '\n' ' '
}
mostrar() { echo "cola: agregados=$1 pendientes=$2 consumidores=$3 | tópico: agregados=$4 suscripciones=$5 durables=$6 | DLQ: pendientes=$7"; }

echo "Palco - Entrega Parcial N.º 2 - reproducción del proceso asincrónico"
echo "Salida de docs/entrega3/evidencia/reproducir.sh, corrido desde la raíz del repo en Git Bash."

titulo "0. Configuración del broker, leída del servidor en marcha"
echo "Valores, en orden: versión de WildFly; versión de ActiveMQ Artemis; el broker guarda los mensajes en disco"
echo "(persistence-enabled); entregas antes de la DLQ (max-delivery-attempts); espera entre entregas en milisegundos"
echo "(redelivery-delay); cola de mensajes fallidos (dead-letter-address); modo de la fábrica de conexiones que"
echo "publica java:/JmsXA (transaction); nombres JNDI de esa fábrica (entries)."
"$CLI" --connect --commands=":read-attribute(name=product-version),$B:read-attribute(name=version),$B:read-attribute(name=persistence-enabled),$B/address-setting=#:read-attribute(name=max-delivery-attempts),$B/address-setting=#:read-attribute(name=redelivery-delay),$B/address-setting=#:read-attribute(name=dead-letter-address),$B/pooled-connection-factory=activemq-ra:read-attribute(name=transaction),$B/pooled-connection-factory=activemq-ra:read-attribute(name=entries)" \
  | grep -v -e '^{' -e '^}' -e '"outcome"'
echo "Suscripciones del tópico (sin la lista de conexiones de cada una):"
"$CLI" --connect --command="$B/jms-topic=topico.compra-confirmada:list-all-subscriptions" | grep -e '"queueName"' -e '"durable"' -e '"clientID"' -e '"selector"' -e '"name"'

T=$(curl -s -X POST $H/api/usuarios/login -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' | token)
S=$(curl -s -X POST $H/api/usuarios/servicios/token -H "$J" -d "{\"secreto\":\"$SECRETO\"}" | token)

titulo "1. Contadores del broker antes de la compra"
read -r A1 A2 A3 A4 A5 A6 A7 <<< "$(contadores)"
mostrar "$A1" "$A2" "$A3" "$A4" "$A5" "$A6" "$A7"

titulo "2. Abrir la compra: POST /api/v1/ventas (2 entradas de Platea B, sec-1-4, del evento ev-1)"
ABRIR=$(curl -s -i -X POST $H/api/v1/ventas -H "Authorization: Bearer $T" -H "$J" -H "Idempotency-Key: $(uuid)" \
  -d '{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":2}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"},{"nombre":"Luis Perez","dni":"31234567"}]}' | tr -d '\r')
echo "$ABRIR" | grep -i -e '^HTTP' -e '^location:'; echo "$ABRIR" | tail -1
V=$(echo "$ABRIR" | tail -1 | grep -o 'V-[0-9a-f]\{8\}' | head -1)

titulo "3. Pagar la compra $V: POST /api/v1/ventas/$V/pagos (pasarela simulada: tok_test aprueba; quién cobra, en el paso 6)"
PAGAR=$(curl -s -i -X POST $H/api/v1/ventas/$V/pagos -H "Authorization: Bearer $T" -H "$J" -H "Idempotency-Key: $(uuid)" \
  -d '{"medioDePago":"tarjeta","token":"tok_test"}' | tr -d '\r')
echo "$PAGAR" | grep -i -e '^HTTP' -e '^location:'; echo "$PAGAR" | tail -1
E=$(echo "$PAGAR" | tail -1 | grep -o 'T-[0-9a-f]\{8\}' | head -1)

sleep 3
titulo "4. Tres segundos después: GET /api/v1/ventas/$V y GET /api/v1/entradas/$E"
curl -s -H "Authorization: Bearer $T" $H/api/v1/ventas/$V; echo
curl -s -H "Authorization: Bearer $T" $H/api/v1/entradas/$E; echo

titulo "5. Contadores del broker después de la compra, y la diferencia"
read -r D1 D2 D3 D4 D5 D6 D7 <<< "$(contadores)"
mostrar "$D1" "$D2" "$D3" "$D4" "$D5" "$D6" "$D7"
echo "diferencia: cola agregados +$((D1 - A1)) (se espera +1) | tópico agregados +$((D4 - A4)) (se espera una copia por suscripción del tópico) | DLQ +$((D7 - A7)) (se espera +0)"

titulo "6. Líneas del log del servidor con el id de la venta: grep -n $V $LOG"
grep -n "$V" "$LOG"
echo "Quién cobra: última línea de FabricaDeCobros en el log (se escribe en el primer cobro después de cada despliegue de Ventas)"
grep -n 'FabricaDeCobros' "$LOG" | tail -1

titulo "7. Fila de auditoría de esa venta: GET /api/notificaciones/auditoria (rol servicio), filtrada por ventaId"
curl -s -H "Authorization: Bearer $S" $H/api/notificaciones/auditoria | grep -o "{[^{}]*\"ventaId\":\"$V\"[^{}]*}"

titulo "8. Transacción deshecha: POST /api/notificaciones/pruebas/pago-confirmado-con-falla (publica y después lanza un error)"
read -r A1 A2 A3 A4 A5 A6 A7 <<< "$(contadores)"
echo "antes:   $(mostrar "$A1" "$A2" "$A3" "$A4" "$A5" "$A6" "$A7")"
LINEAS=$(grep -c -e 'EmisionDeEntradas' -e 'AvisoDeCompra' -e 'AuditoriaDeCompras' "$LOG")
curl -s -i -X POST -H "Authorization: Bearer $S" $H/api/notificaciones/pruebas/pago-confirmado-con-falla | tr -d '\r' | grep -v -i -e '^connection:' -e '^transfer-encoding:' -e '^date:' -e '^$'; echo
sleep 3
read -r D1 D2 D3 D4 D5 D6 D7 <<< "$(contadores)"
echo "después: $(mostrar "$D1" "$D2" "$D3" "$D4" "$D5" "$D6" "$D7")"
echo "diferencia: cola agregados +$((D1 - A1)) | tópico agregados +$((D4 - A4)) | DLQ +$((D7 - A7)) (se espera +0 en los tres)"
echo "líneas nuevas de los tres consumidores en el log: $(( $(grep -c -e 'EmisionDeEntradas' -e 'AvisoDeCompra' -e 'AuditoriaDeCompras' "$LOG") - LINEAS )) (se espera 0)"

codigo() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
titulo "9. Control por rol: la misma operación según quién llama (sin token, comprador y rol servicio)"
echo "GET  /api/notificaciones/auditoria, sin token:              $(codigo $H/api/notificaciones/auditoria) (se espera 401)"
echo "GET  /api/notificaciones/auditoria, token de comprador:     $(codigo -H "Authorization: Bearer $T" $H/api/notificaciones/auditoria) (se espera 403)"
echo "GET  /api/notificaciones/auditoria, token de rol servicio:  $(codigo -H "Authorization: Bearer $S" $H/api/notificaciones/auditoria) (se espera 200)"
echo "POST /api/v1/pagos, sin token:                              $(codigo -X POST -H "$J" -d '{}' $H/api/v1/pagos) (se espera 401)"
echo "POST /api/v1/pagos, token de comprador:                     $(codigo -X POST -H "Authorization: Bearer $T" -H "$J" -d '{}' $H/api/v1/pagos) (se espera 403)"

vendidas() { curl -s $H/api/eventos | grep -o '"id":"sec-1-4"[^}]*' | grep -o '"vendidas":[0-9]*' | grep -o '[0-9]*$'; }
titulo "10. Reserva de cupo: vendidas de Platea B (sec-1-4) en GET /api/eventos, al abrir y al cancelar una compra de 1 entrada"
C0=$(vendidas)
echo "antes de abrir la compra:        vendidas=$C0"
V2=$(curl -s -X POST $H/api/v1/ventas -H "Authorization: Bearer $T" -H "$J" -H "Idempotency-Key: $(uuid)"   -d '{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":1}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"}]}' | grep -o 'V-[0-9a-f]\{8\}' | head -1)
C1=$(vendidas)
echo "con la compra $V2 abierta: vendidas=$C1 (se espera $((C0 + 1)))"
echo "DELETE /api/v1/ventas/$V2:   $(codigo -X DELETE -H "Authorization: Bearer $T" $H/api/v1/ventas/$V2)"
C2=$(vendidas)
echo "después de cancelarla:           vendidas=$C2 (se espera $C0)"
echo "Líneas del log con el id de esa venta: grep -n $V2 $LOG"
grep -n "$V2" "$LOG"
