#!/usr/bin/env bash
# Palco - Entrega Parcial N.º 2 - guion que vuelve a tomar "la compra de la toma".
#
# Qué hace: hace una compra de prueba y escribe, en esta misma carpeta, los siete archivos que la muestran
# paso por paso: broker-antes.txt, compra.txt, venta.txt, entrada.txt, broker-despues.txt,
# server-log-extracto.txt y auditoria.txt. Cada archivo dice qué muestra y, por cada paso, la fecha, el
# comando exacto y su salida sin retocar. Pisa los archivos de la toma anterior.
#
# Uso, desde la raíz del repo, en Git Bash, con WildFly levantado y los 8 servicios desplegados:
#     bash docs/entrega3/evidencia/tomar-evidencia.sh
#
# Necesita curl, node y un JDK. Se corrió solo en Windows con Git Bash. Deja una venta EMITIDA de 2 entradas
# de Platea B. No mueve plata: la pasarela de pagos es simulada. Si otra persona compra al mismo tiempo, las
# diferencias de los contadores salen más grandes: se repite el guion.

# Secreto para pedir el token de rol servicio: PALCO_SECRETO_SERVICIOS, el de backend/.env o el de desarrollo.
SECRETO=${PALCO_SECRETO_SERVICIOS:-$(sed -n 's/^PALCO_SECRETO_SERVICIOS=//p' backend/.env 2>/dev/null)}
SECRETO=${SECRETO:-palco-desarrollo}
export NOPAUSE=true                                   # sin esto, jboss-cli.bat espera una tecla al terminar
# El JDK de jboss-cli: el JAVA_HOME del entorno; si no hay, ~/.jdks/temurin-25 si esa carpeta existe; si no,
# el java del PATH (como scripts/pruebas/escenarios.mjs).
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.jdks/temurin-25" ]; then export JAVA_HOME="$HOME/.jdks/temurin-25"; fi
B="/subsystem=messaging-activemq/server=default"
CLI=backend/servidor/target/server/bin/jboss-cli.bat
D=docs/entrega3/evidencia
J="Content-Type: application/json"

# Las tres líneas que cada archivo muestra como preparación. Los tokens no se guardan en ningún archivo.
PREPARACION='    export NOPAUSE=true'"${JAVA_HOME:+ JAVA_HOME=\"$JAVA_HOME\"}"'; B="/subsystem=messaging-activemq/server=default"; CLI=backend/servidor/target/server/bin/jboss-cli.bat
    T=$(curl -s -X POST localhost:8080/api/usuarios/login -H "Content-Type: application/json" -d "{\"email\":\"titular@palco.test\",\"password\":\"palco1234\"}" | sed -E "s/.*\"token\":\"([^\"]+)\".*/\1/")
    S=$(curl -s -X POST localhost:8080/api/usuarios/servicios/token -H "Content-Type: application/json" -d "{\"secreto\":\"$SECRETO\"}" | sed -E "s/.*\"token\":\"([^\"]+)\".*/\1/")'
T=$(curl -s -X POST localhost:8080/api/usuarios/login -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
S=$(curl -s -X POST localhost:8080/api/usuarios/servicios/token -H "$J" -d "{\"secreto\":\"$SECRETO\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')

# Empieza un archivo: qué muestra y cómo se prepararon las variables.
cabecera() {
  {
    echo "Palco - Entrega Parcial N.º 2 - evidencia del proceso asincrónico"
    echo "Qué muestra: $2"
    echo "Dónde se corrió: raíz del repo, en Git Bash, con WildFly levantado y los 8 servicios desplegados. Lo escribió docs/entrega3/evidencia/tomar-evidencia.sh."
    echo
    echo "Antes de los comandos de este archivo se definieron estas variables (los tokens no se guardan acá):"
    echo "$PREPARACION"
  } > "$D/$1"
}

# Agrega un paso a un archivo: corre el comando tal como está escrito y guarda su salida. La devuelve también
# por la salida estándar, para sacar de ahí los ids.
paso() {
  local archivo=$1 titulo=$2 comando=$3 salida
  salida=$(eval "$comando" 2>&1 | tr -d '\r')
  {
    echo
    echo "----------------------------------------------------------------------"
    echo "$titulo"
    echo "Fecha: $(date '+%F %T %z')"
    echo "Comando:"
    echo "    $comando"
    echo "Salida:"
    echo "$salida"
  } >> "$D/$archivo"
  echo "$salida"
}

BROKER='"$CLI" --connect --commands="$B/jms-queue=cola.emision-entradas:read-resource(include-runtime=true),$B/jms-topic=topico.compra-confirmada:read-resource(include-runtime=true),$B/jms-queue=DLQ:read-attribute(name=message-count)"'
BONITO='node -p "JSON.stringify(JSON.parse(require(\"fs\").readFileSync(0,\"utf8\")),null,2)"'
agregados() { echo "$1" | grep -o '"messages-added" => [0-9]*' | grep -o '[0-9]*$' | tr '\n' ' '; }

# 1. El broker antes.
cabecera broker-antes.txt "el estado de la cola cola.emision-entradas, del tópico topico.compra-confirmada y de la DLQ justo ANTES de la compra de compra.txt. Se compara con broker-despues.txt."
ANTES=$(paso broker-antes.txt "Cola, tópico y DLQ antes de la compra" "$BROKER")

# 2. La compra: abrir y pagar.
cabecera compra.txt "la compra que dispara el proceso: abrir la compra (reserva el cupo) y pagarla. El pago responde 201 con la venta CONFIRMADA y las entradas todavía PENDIENTE_EMISION: la emisión es asincrónica y llega después, por la cola. Ventas cobra llamando a ServicioDePagos, cuya pasarela es simulada (tok_test aprueba)."
ABRIR=$(paso compra.txt "1. Abrir la compra: 2 entradas de Platea B (sec-1-4) del evento ev-1" \
  'K1=$(node -e "console.log(crypto.randomUUID())"); curl -s -i -X POST localhost:8080/api/v1/ventas -H "Authorization: Bearer $T" -H "Content-Type: application/json" -H "Idempotency-Key: $K1" -d "{\"eventoId\":\"ev-1\",\"items\":[{\"sectorId\":\"sec-1-4\",\"cantidad\":2}],\"titulares\":[{\"nombre\":\"Ana Gomez\",\"dni\":\"30123456\"},{\"nombre\":\"Luis Perez\",\"dni\":\"31234567\"}]}"')
V=$(echo "$ABRIR" | tail -1 | grep -o 'V-[0-9a-f]\{8\}' | head -1)
PAGAR=$(paso compra.txt "2. Pagar la compra $V (pasarela simulada: tok_test aprueba)" \
  'K2=$(node -e "console.log(crypto.randomUUID())"); curl -s -i -X POST localhost:8080/api/v1/ventas/'"$V"'/pagos -H "Authorization: Bearer $T" -H "Content-Type: application/json" -H "Idempotency-Key: $K2" -d "{\"medioDePago\":\"tarjeta\",\"token\":\"tok_test\"}"')
E=$(echo "$PAGAR" | tail -1 | grep -o 'T-[0-9a-f]\{8\}' | head -1)
P=$(echo "$PAGAR" | grep -o 'P-[0-9a-f]\{8\}' | head -1)

sleep 4

# 3 y 4. La venta y la entrada, unos segundos después.
cabecera venta.txt "la venta $V unos segundos después del pago: pasó de CONFIRMADA a EMITIDA y sus dos entradas están VALIDA, cada una con su código. Lo hizo EmisionDeEntradasMDB al consumir el mensaje de la cola."
VENTA=$(paso venta.txt "La venta, consultada por su comprador" 'curl -s -H "Authorization: Bearer $T" localhost:8080/api/v1/ventas/'"$V"' | '"$BONITO")
cabecera entrada.txt "la entrada $E de la venta $V después de la emisión: estado VALIDA, con su código QR (codigoQr) y su fecha de emisión en UTC (emitida). Al volver el pago estaba PENDIENTE_EMISION y sin código (ver compra.txt)."
paso entrada.txt "La entrada, consultada por su comprador" 'curl -s -H "Authorization: Bearer $T" localhost:8080/api/v1/entradas/'"$E"' | '"$BONITO" > /dev/null

# 5. El broker después.
cabecera broker-despues.txt "el estado de la cola, del tópico y de la DLQ DESPUÉS de la compra $V. Contra broker-antes.txt: en la cola messages-added sube 1 y message-count sigue en 0 (el consumidor ya lo procesó); en el tópico messages-added sube 2 (una copia por suscriptor: aviso y auditoría); la DLQ no cambia."
DESPUES=$(paso broker-despues.txt "Cola, tópico y DLQ después de la compra" "$BROKER")

# 6. El log del servidor.
{
  echo "Palco - Entrega Parcial N.º 2 - evidencia del proceso asincrónico"
  echo "Qué muestra: las líneas del log del servidor que nombran a la venta $V, en orden: la compra con estado (ServicioDeVentas), el pago que guardó ServicioDePagos, y los tres consumidores del mensaje PAGO_CONFIRMADO: AvisoDeCompra y AuditoriaDeCompras (tópico, en Notificaciones) y EmisionDeEntradas (cola, en Ventas). El servidor escribe la hora local (UTC-3). Entre paréntesis va el hilo: el pago corre en un hilo del servidor web y los consumidores, en hilos del broker."
  echo "Dónde se corrió: raíz del repo, en Git Bash, con WildFly levantado y los 8 servicios desplegados. Lo escribió docs/entrega3/evidencia/tomar-evidencia.sh."
} > "$D/server-log-extracto.txt"
paso server-log-extracto.txt "Líneas de server.log con el id de la venta" 'grep -n "'"$V"'" backend/servidor/target/server/standalone/log/server.log' > /dev/null

# 7. La auditoría.
cabecera auditoria.txt "la fila que guardó AuditoriaDeComprasMDB (suscriptor durable del tópico) por el mensaje de la venta $V. El endpoint devuelve los últimos 50 registros; acá se muestra solo el de esta venta."
paso auditoria.txt "Registro de auditoría de la venta (rol servicio)" \
  'curl -s -H "Authorization: Bearer $S" localhost:8080/api/notificaciones/auditoria | node -p "JSON.stringify(JSON.parse(require(\"fs\").readFileSync(0,\"utf8\")).filter(f => f.ventaId === \"'"$V"'\"), null, 2)"' > /dev/null

# Resumen por pantalla: los ids de la toma y si dio lo esperado.
read -r COLA0 TOPICO0 <<< "$(agregados "$ANTES")"
read -r COLA1 TOPICO1 <<< "$(agregados "$DESPUES")"
ESTADO=$(echo "$VENTA" | grep -o '"estado": "[A-Z_]*"' | tail -1)
echo "Toma del $(date '+%F %T %z'): venta $V, pago $P, entrada $E"
echo "venta: $ESTADO (se espera EMITIDA) | cola agregados +$((COLA1 - COLA0)) (se espera +1) | tópico agregados +$((TOPICO1 - TOPICO0)) (se espera +2)"
echo "Archivos escritos en $D: broker-antes.txt compra.txt venta.txt entrada.txt broker-despues.txt server-log-extracto.txt auditoria.txt"
[ "$ESTADO" = '"estado": "EMITIDA"' ] && [ $((COLA1 - COLA0)) -eq 1 ] && [ $((TOPICO1 - TOPICO0)) -eq 2 ]
