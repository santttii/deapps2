#!/usr/bin/env bash
#
# Demo de Palco: una compra de punta a punta contra el servidor ya levantado,
# con la mensajería asincrónica a la vista. Se armó para la Entrega Parcial
# N.º 2 de la consigna; el plan viejo la llamaba "Entrega 3", y de ahí viene el
# nombre del archivo.
#
#   scripts/demo-entrega3.sh              compra con pago aprobado
#   scripts/demo-entrega3.sh rechazado    compra con pago rechazado
#
# Qué muestra el modo aprobado: login, cupo antes, inicio de la compra (reserva
# de cupo), pago (la venta queda CONFIRMADA y las entradas PENDIENTE_EMISION),
# espera, estado final (EMITIDA, entradas VALIDA), una entrada, cupo después y
# la fila de auditoría. Al pagar, Ventas publica PAGO_CONFIRMADO en la cola
# cola.emision-entradas (la consume la emisión de entradas) y en el tópico
# topico.compra-confirmada (lo reciben el aviso y la auditoría).
#
# Qué muestra el modo rechazado: el 402 del pago, que la venta sigue EN_CHECKOUT
# sin entradas y que no salió ningún mensaje. Al final cancela la compra.
#
# Cada paso comprueba lo que muestra. Si algo no da lo esperado, el guion corta
# ahí con un mensaje y sale con código 1. Código 2: argumento desconocido o
# falta curl.
#
# Variables de entorno (todas opcionales):
#   PALCO_URL         dirección del servidor            (http://localhost:8080)
#   EVENTO_SLUG       evento del que se compra          (kiroshi-tour-continental)
#   SECTOR_ID         sector del que se compra          (sec-2-2)
#   COMPRADOR_EMAIL   cuenta del comprador              (titular@palco.test)
#   COMPRADOR_CLAVE   su contraseña                     (palco1234)
#   SECRETO_SERVICIO  secreto para pedir el rol servicio (PALCO_SECRETO_SERVICIOS, el de backend/.env o palco-desarrollo)
#   ESPERA_MAXIMA     segundos que se espera la emisión (15)
#   DETALLE=1         imprime además el JSON completo de cada respuesta
#   JBOSS_CLI         ruta de jboss-cli, si no está en la del proyecto
#   NO_COLOR=1        salida sin colores
#
# Necesita bash, curl, grep, sed y od. No usa jq.

set -u

# ----------------------------------------------------------------- configuración

MODO=${1:-aprobado}
case "$MODO" in
  aprobado | rechazado) ;;
  *)
    echo "Uso: $0 [aprobado|rechazado]" >&2
    echo "Sin argumento corre la compra con pago aprobado. Las variables de entorno están al principio del archivo." >&2
    exit 2
    ;;
esac

command -v curl > /dev/null 2>&1 || { echo "Falta curl en el PATH." >&2; exit 2; }

BASE=${PALCO_URL:-http://localhost:8080}
BASE=${BASE%/}
EVENTO_SLUG=${EVENTO_SLUG:-kiroshi-tour-continental}
SECTOR_ID=${SECTOR_ID:-sec-2-2}
COMPRADOR_EMAIL=${COMPRADOR_EMAIL:-titular@palco.test}
COMPRADOR_CLAVE=${COMPRADOR_CLAVE:-palco1234}
SECRETO_SERVICIO=${SECRETO_SERVICIO:-${PALCO_SECRETO_SERVICIOS:-$(sed -n 's/^PALCO_SECRETO_SERVICIOS=//p' backend/.env 2>/dev/null)}}
SECRETO_SERVICIO=${SECRETO_SERVICIO:-palco-desarrollo}
ESPERA_MAXIMA=${ESPERA_MAXIMA:-15}
DETALLE=${DETALLE:-0}

COLA_JMS=cola.emision-entradas
TOPICO_JMS=topico.compra-confirmada

# Dos entradas, cada una a nombre de un titular distinto.
CANTIDAD=2
TITULARES='[{"nombre":"Ana Gomez","dni":"30123456"},{"nombre":"Luis Perez","dni":"31234567"}]'

# El simulador de cobro decide por el token: tok_rechazado rechaza y cualquier otro aprueba.
TOKEN_APROBADO=tok_test
TOKEN_RECHAZADO=tok_rechazado

RAIZ=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
CLI=${JBOSS_CLI:-}
if [ -z "$CLI" ]; then
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) CLI="$RAIZ/backend/servidor/target/server/bin/jboss-cli.bat" ;;
    *) CLI="$RAIZ/backend/servidor/target/server/bin/jboss-cli.sh" ;;
  esac
fi

# En Git Bash, jboss-cli.bat necesita JAVA_HOME escrito al modo de Windows (C:\...). Si viene al modo de
# Git Bash (/c/...), no encuentra Java y el guion seguiría sin contadores: se lo convierte acá.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    case "${JAVA_HOME:-}" in
      /*) command -v cygpath > /dev/null 2>&1 && export JAVA_HOME="$(cygpath -w "$JAVA_HOME")" ;;
    esac
    ;;
esac

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  NEGRITA=$'\033[1m' VERDE=$'\033[32m' ROJO=$'\033[31m' TENUE=$'\033[2m' FIN=$'\033[0m'
else
  NEGRITA='' VERDE='' ROJO='' TENUE='' FIN=''
fi

# --------------------------------------------------------------------- pantalla

PASO=0
TITULO=''
HTTP=''
CUERPO=''
TOKEN=''
VENTA_ABIERTA='' # la venta sin pagar que hay que cancelar si el guion corta

paso() {
  PASO=$((PASO + 1))
  TITULO=$1
  printf '\n%s== Paso %d. %s ==%s\n' "$NEGRITA" "$PASO" "$1" "$FIN"
}
dato() { printf '    %-16s %s\n' "$1" "$2"; }
nota() { printf '    %s%s%s\n' "$TENUE" "$1" "$FIN"; }
bien() { printf '    %s[OK]%s %s\n' "$VERDE" "$FIN" "$1"; }

# Corta el guion: dice en qué paso y por qué, y sale con código 1.
fallar() {
  printf '\n%s[FALLO] Paso %d (%s): %s%s\n' "$ROJO" "$PASO" "$TITULO" "$1" "$FIN" >&2
  if [ -n "$HTTP" ]; then
    printf '        Última respuesta: HTTP %s %s\n' "$HTTP" "$(printf '%s' "$CUERPO" | sin_token | cut -c1-400)" >&2
  fi
  if [ -n "$VENTA_ABIERTA" ]; then
    curl -s -m 20 -o /dev/null -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/ventas/$VENTA_ABIERTA"
    printf '        Se pidió cancelar la venta %s para devolver su cupo.\n' "$VENTA_ABIERTA" >&2
  fi
  exit 1
}

# ------------------------------------------------------------------------- HTTP

# llamar METODO RUTA [TOKEN] [JSON] [CLAVE_DE_IDEMPOTENCIA]
# Deja el código en HTTP y el cuerpo en CUERPO. Si el servidor no contesta
# (por ejemplo, porque se está redesplegando un servicio) espera y reintenta:
# repetir un POST no repite su efecto, porque lleva la misma Idempotency-Key.
llamar() {
  local metodo=$1 ruta=$2 token=${3:-} json=${4:-} clave=${5:-} salida intento
  local opciones=(-s -m 20 -w '\n%{http_code}' -X "$metodo" -H 'Accept: application/json')
  [ -n "$token" ] && opciones+=(-H "Authorization: Bearer $token")
  [ -n "$json" ] && opciones+=(-H 'Content-Type: application/json' -d "$json")
  [ -n "$clave" ] && opciones+=(-H "Idempotency-Key: $clave")

  for intento in 1 2 3 4; do
    salida=$(curl "${opciones[@]}" "$BASE$ruta" 2> /dev/null)
    HTTP=${salida##*$'\n'}
    CUERPO=${salida%$'\n'*}
    case "$HTTP" in
      000 | 502 | 503)
        [ "$intento" -lt 4 ] || break
        nota "$metodo $ruta no respondió (HTTP $HTTP). Se espera 3 s y se reintenta."
        sleep 3
        ;;
      *) break ;;
    esac
  done

  if [ -z "${CALLADO:-}" ]; then
    printf '    %s %s  ->  HTTP %s\n' "$metodo" "$ruta" "$HTTP"
    if [ "$DETALLE" = 1 ] && [ -n "$CUERPO" ]; then
      printf '%s\n' "$CUERPO" | sin_token | sed 's/^/      /'
    fi
  fi
  return 0
}

# Igual que llamar, sin imprimir la línea del pedido.
llamar_callado() {
  local CALLADO=1
  llamar "$@"
}

exigir_http() {
  [ "$HTTP" = "$1" ] || fallar "se esperaba HTTP $1 y llegó HTTP $HTTP."
}

# exigir QUÉ OBTENIDO ESPERADO
exigir() {
  [ "$2" = "$3" ] || fallar "$1: se esperaba '$3' y llegó '$2'."
}

# ------------------------------------------------------------------------- JSON
# El servidor responde JSON en una sola línea. Alcanza con grep y sed.

# texto CAMPO JSON: el primer valor de texto de ese campo (vacío si es null o no está).
texto() { printf '%s' "$2" | grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | cut -d'"' -f4; }

# numero CAMPO JSON: el primer valor numérico de ese campo.
numero() { printf '%s' "$2" | grep -o "\"$1\":[0-9][0-9]*" | head -n 1 | cut -d: -f2; }

# contar PATRON TEXTO: cuántas veces aparece.
contar() { printf '%s' "$2" | grep -o "$1" | wc -l | tr -d ' '; }

# La lista de entradas de una venta, y la venta sin esa lista (cada entrada
# tiene su propio "id" y su propio "estado", que no son los de la venta).
entradas_de() { printf '%s' "$1" | grep -o '"entradas":\[[^]]*\]'; }
venta_sin_entradas() { printf '%s' "$1" | sed 's/"entradas":\[[^]]*\]//'; }

# No se muestran tokens enteros en pantalla.
sin_token() { sed -E 's/"token":"([^"]{12})[^"]*"/"token":"\1..."/'; }

# Un UUID nuevo, para la cabecera Idempotency-Key.
uuid() {
  if command -v uuidgen > /dev/null 2>&1; then
    uuidgen | tr 'A-Z' 'a-z'
  elif [ -r /proc/sys/kernel/random/uuid ]; then
    cat /proc/sys/kernel/random/uuid
  else
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n' \
      | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'
  fi
}

# Una línea por entrada de la venta: id, titular y estado.
mostrar_entradas() {
  local bloque ifs_anterior=$IFS i=0
  bloque=$(entradas_de "$1")
  IFS=$'\n'
  set -f
  local ids=($(printf '%s' "$bloque" | grep -o '"id":"[^"]*"' | cut -d'"' -f4))
  local nombres=($(printf '%s' "$bloque" | grep -o '"nombre":"[^"]*"' | cut -d'"' -f4))
  local estados=($(printf '%s' "$bloque" | grep -o '"estado":"[^"]*"' | cut -d'"' -f4))
  set +f
  IFS=$ifs_anterior
  while [ "$i" -lt "${#ids[@]}" ]; do
    printf '      %s  %-14s %s\n' "${ids[$i]}" "${nombres[$i]:-}" "${estados[$i]:-}"
    i=$((i + 1))
  done
}

# -------------------------------------------------------------- partes del flujo

# Deja en CUPO y VENDIDAS los números del sector, y en EVENTO_ID el id del evento.
leer_sector() {
  local sector
  llamar GET "/api/eventos/$EVENTO_SLUG"
  exigir_http 200
  sector=$(printf '%s' "$CUERPO" | grep -o "{[^{}]*\"id\":\"$SECTOR_ID\"[^{}]*}" | head -n 1)
  [ -n "$sector" ] || fallar "el evento $EVENTO_SLUG no tiene el sector $SECTOR_ID."
  EVENTO_ID=$(texto id "$(printf '%s' "$CUERPO" | sed 's/"sectores":\[[^]]*\]//')")
  SECTOR_NOMBRE=$(texto nombre "$sector")
  CUPO=$(numero cupo "$sector")
  VENDIDAS=$(numero vendidas "$sector")
  dato sector "$SECTOR_NOMBRE ($SECTOR_ID) del evento $EVENTO_ID"
  dato cupo "$CUPO"
  dato vendidas "$VENDIDAS (quedan $((CUPO - VENDIDAS)))"
}

# Deja en TOKEN_SERVICIO un token con rol servicio. Dura 5 minutos.
pedir_token_de_servicio() {
  llamar POST /api/usuarios/servicios/token '' "{\"secreto\":\"$SECRETO_SERVICIO\"}"
  exigir_http 200
  TOKEN_SERVICIO=$(texto token "$CUERPO")
  [ -n "$TOKEN_SERVICIO" ] || fallar "la respuesta no trae el token de servicio."
}

# Deja en CUERPO las últimas 50 filas de auditoría.
leer_auditoria() {
  llamar GET /api/notificaciones/auditoria "$TOKEN_SERVICIO"
  exigir_http 200
}

# La fila de auditoría de la venta, si está en CUERPO.
fila_de_la_venta() { printf '%s' "$CUERPO" | grep -o "{[^{}]*\"ventaId\":\"$VENTA\"[^{}]*}" | head -n 1; }

# Las ventas de otros que auditoría registró desde la foto del principio.
ventas_ajenas_nuevas() {
  local fila id
  printf '%s' "$CUERPO" | grep -o '{[^{}]*}' | while IFS= read -r fila; do
    id=$(texto mensajeId "$fila")
    case "$AUDITORIA_ANTES" in *"\"$id\""*) continue ;; esac
    [ "$(texto ventaId "$fila")" = "$VENTA" ] || printf '%s ' "$(texto ventaId "$fila")"
  done
}

# Deja en COLA y TOPICO cuántos mensajes se agregaron a cada destino desde que
# arrancó el servidor, y en SUSCRIPCIONES cuántas suscripciones tiene el tópico.
# El tópico cuenta una copia por suscripción: con dos suscriptores, cada
# publicación le suma 2. Los tres datos se piden en una sola llamada a
# jboss-cli. Devuelve 1 si no los pudo leer.
BROKER='' # queda en "si" cuando los contadores se pudieron leer al principio
leer_broker() {
  local servidor=/subsystem=messaging-activemq/server=default salida
  local cola="$servidor/jms-queue=$COLA_JMS" topico="$servidor/jms-topic=$TOPICO_JMS"
  # NOPAUSE: sin eso, jboss-cli.bat espera una tecla al terminar. Las otras dos
  # variables evitan que Git Bash convierta la barra inicial en una ruta de Windows.
  salida=$(cd "$(dirname "$CLI")" && NOPAUSE=true MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    "./$(basename "$CLI")" --connect \
    --commands="$cola:read-attribute(name=messages-added),$topico:read-attribute(name=messages-added),$topico:read-attribute(name=subscription-count)" \
    < /dev/null 2>&1)
  set -- $(printf '%s\n' "$salida" | grep '"result"' | tr -cd '0-9\n')
  [ $# -eq 3 ] || return 1
  COLA=$1
  TOPICO=$2
  SUSCRIPCIONES=$3
}
contador() { printf '    %-25s %s\n' "$1" "$2"; }

broker_antes() {
  paso "Contadores del broker antes de la compra"
  case "$BASE" in
    *//localhost* | *//127.0.0.1*) ;;
    *)
      nota "El servidor no es local: jboss-cli leería los contadores de otro WildFly. Se sigue sin contadores."
      return
      ;;
  esac
  if [ ! -f "$CLI" ]; then
    nota "No está jboss-cli en $CLI. Se sigue sin contadores."
    return
  fi
  nota "Leyendo con jboss-cli (tarda unos segundos)..."
  if ! leer_broker; then
    nota "jboss-cli no devolvió los contadores (¿falta Java o el servidor no acepta la conexión de gestión?). Se sigue sin contadores."
    return
  fi
  BROKER=si
  COLA_ANTES=$COLA
  TOPICO_ANTES=$TOPICO
  COPIAS=$SUSCRIPCIONES
  contador "$COLA_JMS" "$COLA mensajes agregados"
  contador "$TOPICO_JMS" "$TOPICO mensajes agregados (cuenta una copia por suscripción: tiene $SUSCRIPCIONES)"
}

# broker_despues PUBLICADOS: 1 si la compra tuvo que publicar su mensaje, 0 si no.
broker_despues() {
  local publicados=$1 de_cola de_topico ajenas
  paso "Contadores del broker después"
  if [ -z "$BROKER" ]; then
    nota "Sin jboss-cli no hay contadores. La prueba de este paso es la auditoría del paso anterior."
    return
  fi
  nota "Leyendo con jboss-cli (tarda unos segundos)..."
  leer_broker || fallar "jboss-cli leyó los contadores al principio y ahora no."
  de_cola=$((COLA - COLA_ANTES))
  de_topico=$((TOPICO - TOPICO_ANTES))
  contador "$COLA_JMS" "$COLA_ANTES -> $COLA (+$de_cola)"
  contador "$TOPICO_JMS" "$TOPICO_ANTES -> $TOPICO (+$de_topico)"

  # Los contadores son de todo el servidor. Si subieron de más, auditoría dice de qué ventas son.
  ajenas=''
  if [ "$de_cola" -ne "$publicados" ] || [ "$de_topico" -ne $((publicados * COPIAS)) ]; then
    CALLADO=1 leer_auditoria
    ajenas=$(ventas_ajenas_nuevas)
    ajenas=${ajenas% }
    [ -n "$ajenas" ] && nota "Mientras corría el guion, auditoría registró otras compras: $ajenas"
  fi

  if [ "$publicados" -eq 1 ]; then
    [ "$de_cola" -ge 1 ] || fallar "la cola $COLA_JMS no recibió ningún mensaje."
    [ "$de_topico" -ge "$COPIAS" ] || fallar "el tópico $TOPICO_JMS recibió $de_topico copias y tiene $COPIAS suscripciones."
    bien "Esta compra sumó 1 mensaje a la cola y $COPIAS al tópico, una copia por suscripción."
  elif [ "$de_cola" -eq 0 ] && [ "$de_topico" -eq 0 ]; then
    bien "Los contadores no cambiaron: no salió ningún mensaje."
  else
    # Subieron: tiene que haber otras compras que lo expliquen, una por cada mensaje de la cola.
    set -- $ajenas
    if [ "$de_cola" -le $# ] && [ "$de_topico" -le $(($# * COPIAS)) ]; then
      bien "Los mensajes nuevos son de esas otras compras. Esta venta no publicó ninguno."
    else
      fallar "los contadores subieron y las compras que registró auditoría no alcanzan para explicarlo."
    fi
  fi
}

# ---------------------------------------------------------------- pasos comunes

printf '%sPalco - demo de una compra de punta a punta%s\n' "$NEGRITA" "$FIN"
printf 'Servidor: %s    Modo: pago %s\n' "$BASE" "$MODO"

paso "Login del comprador"
llamar POST /api/usuarios/login '' "{\"email\":\"$COMPRADOR_EMAIL\",\"password\":\"$COMPRADOR_CLAVE\"}"
[ "$HTTP" != 000 ] || fallar "el servidor no responde en $BASE. ¿Está levantado WildFly?"
exigir_http 200
TOKEN=$(texto token "$CUERPO")
[ -n "$TOKEN" ] || fallar "la respuesta no trae el token."
dato usuario "$(texto email "$CUERPO") (id $(texto id "$CUERPO"), rol $(texto rol "$CUERPO"))"
dato token "$(printf '%s' "$TOKEN" | cut -c1-12)... (JWT, vale $(numero expiraEnSegundos "$CUERPO") s)"

broker_antes
# Foto de la auditoría, para saber después qué filas son nuevas. No se muestra.
CALLADO=1 pedir_token_de_servicio
CALLADO=1 leer_auditoria
AUDITORIA_ANTES=$CUERPO

paso "Cupo del sector antes de la compra"
leer_sector
VENDIDAS_ANTES=$VENDIDAS
[ $((CUPO - VENDIDAS)) -ge "$CANTIDAD" ] || fallar "al sector $SECTOR_ID le quedan menos de $CANTIDAD lugares. Elegí otro con SECTOR_ID."

paso "Inicio de la compra: reserva de $CANTIDAD lugares"
CLAVE_COMPRA=$(uuid)
dato Idempotency-Key "$CLAVE_COMPRA"
llamar POST /api/v1/ventas "$TOKEN" \
  "{\"eventoId\":\"$EVENTO_ID\",\"items\":[{\"sectorId\":\"$SECTOR_ID\",\"cantidad\":$CANTIDAD}],\"titulares\":$TITULARES}" \
  "$CLAVE_COMPRA"
exigir_http 201
VENTA=$(texto id "$(venta_sin_entradas "$CUERPO")")
[ -n "$VENTA" ] || fallar "la respuesta no trae el id de la venta."
VENTA_ABIERTA=$VENTA
MONTO=$(numero montoTotal "$CUERPO")
dato venta "$VENTA"
dato estado "$(texto estado "$CUERPO")"
dato montoTotal "$MONTO (subtotal $(numero subtotal "$CUERPO") + cargo de servicio $(numero cargoServicio "$CUERPO"))"
dato venceReserva "$(texto venceReserva "$CUERPO")"
exigir "estado de la venta" "$(texto estado "$CUERPO")" EN_CHECKOUT
[ -n "$(texto venceReserva "$CUERPO")" ] || fallar "la venta no trae el vencimiento de la reserva."
nota "El cupo ya está reservado en Eventos:"
leer_sector
exigir "vendidas del sector" "$VENDIDAS" "$((VENDIDAS_ANTES + CANTIDAD))"
bien "Venta abierta: $CANTIDAD lugares reservados hasta el vencimiento."

# ------------------------------------------------------------- pago aprobado

if [ "$MODO" = aprobado ]; then

  paso "Pago de la compra"
  CLAVE_PAGO=$(uuid)
  dato Idempotency-Key "$CLAVE_PAGO"
  llamar POST "/api/v1/ventas/$VENTA/pagos" "$TOKEN" \
    "{\"medioDePago\":\"tarjeta\",\"token\":\"$TOKEN_APROBADO\"}" "$CLAVE_PAGO"
  exigir_http 201
  VENTA_ABIERTA=''
  RESPUESTA_DEL_PAGO=$CUERPO
  PAGO=$(texto pagoId "$CUERPO")
  dato pagoId "$PAGO"
  [ -n "$PAGO" ] || fallar "la respuesta no trae el id del pago."
  bien "Pago aprobado. Los pasos que siguen muestran qué pasó con el mensaje PAGO_CONFIRMADO."

  paso "Estado de la venta al volver el pago"
  nota "Es lo que trae la respuesta del pago: vuelve sin esperar a la emisión."
  ESTADO=$(texto estado "$(venta_sin_entradas "$RESPUESTA_DEL_PAGO")")
  ENTRADAS=$(entradas_de "$RESPUESTA_DEL_PAGO")
  dato estado "$ESTADO"
  dato entradas "$(contar '"id":"' "$ENTRADAS")"
  mostrar_entradas "$RESPUESTA_DEL_PAGO"
  exigir "estado de la venta" "$ESTADO" CONFIRMADA
  exigir "cantidad de entradas" "$(contar '"id":"' "$ENTRADAS")" "$CANTIDAD"
  exigir "entradas pendientes de emisión" "$(contar '"estado":"PENDIENTE_EMISION"' "$ENTRADAS")" "$CANTIDAD"
  bien "Venta CONFIRMADA con sus entradas creadas y todavía sin emitir."

  paso "Espera: el consumidor de la cola emite las entradas"
  nota "Se consulta la venta una vez por segundo hasta que pase a EMITIDA (máximo $ESPERA_MAXIMA s)."
  CONSULTA=0
  while :; do
    sleep 1
    CONSULTA=$((CONSULTA + 1))
    llamar GET "/api/v1/ventas/$VENTA" "$TOKEN"
    exigir_http 200
    ESTADO=$(texto estado "$(venta_sin_entradas "$CUERPO")")
    dato "consulta $CONSULTA" "$ESTADO"
    [ "$ESTADO" = EMITIDA ] && break
    exigir "estado de la venta" "$ESTADO" CONFIRMADA
    [ "$CONSULTA" -lt "$ESPERA_MAXIMA" ] || fallar "pasaron $CONSULTA s y la venta sigue CONFIRMADA: nadie consumió el mensaje de la cola $COLA_JMS."
  done

  paso "Estado final de la venta"
  ENTRADAS=$(entradas_de "$CUERPO")
  dato estado "$ESTADO"
  dato entradas "$(contar '"id":"' "$ENTRADAS")"
  mostrar_entradas "$CUERPO"
  exigir "entradas válidas" "$(contar '"estado":"VALIDA"' "$ENTRADAS")" "$CANTIDAD"
  ENTRADA=$(texto id "$ENTRADAS")
  bien "Venta EMITIDA: la emisión corrió sola, sin otro pedido del comprador."

  paso "Consulta de una entrada"
  llamar GET "/api/v1/entradas/$ENTRADA" "$TOKEN"
  exigir_http 200
  dato entrada "$(texto id "$CUERPO") (venta $(texto ventaId "$CUERPO"))"
  dato titular "$(texto nombre "$CUERPO"), DNI $(texto dni "$CUERPO")"
  dato sector "$(texto sector "$CUERPO"), precio pagado $(numero precioPagado "$CUERPO")"
  dato estado "$(texto estado "$CUERPO")"
  dato codigoQr "$(texto codigoQr "$CUERPO")"
  dato emitida "$(texto emitida "$CUERPO")"
  exigir "estado de la entrada" "$(texto estado "$CUERPO")" VALIDA
  exigir "venta de la entrada" "$(texto ventaId "$CUERPO")" "$VENTA"
  [ -n "$(texto codigoQr "$CUERPO")" ] || fallar "la entrada está VALIDA y no tiene código."
  [ -n "$(texto emitida "$CUERPO")" ] || fallar "la entrada está VALIDA y no tiene fecha de emisión."
  bien "Entrada VALIDA, con su código y su fecha de emisión."

  paso "Cupo del sector después de la compra"
  leer_sector
  exigir "vendidas del sector" "$VENDIDAS" "$((VENDIDAS_ANTES + CANTIDAD))"
  bien "Vendidas pasó de $VENDIDAS_ANTES a $VENDIDAS: los $CANTIDAD lugares de esta compra."

  paso "Auditoría: la fila de esta venta (rol servicio)"
  pedir_token_de_servicio
  FILA=''
  for INTENTO in 1 2 3 4 5; do
    leer_auditoria
    FILA=$(fila_de_la_venta)
    [ -n "$FILA" ] && break
    nota "Todavía no está la fila. Se espera 1 s."
    sleep 1
  done
  [ -n "$FILA" ] || fallar "auditoría no registró la venta $VENTA: el suscriptor del tópico no recibió el mensaje."
  dato mensajeId "$(texto mensajeId "$FILA")"
  dato tipoEvento "$(texto tipoEvento "$FILA")"
  dato ventaId "$(texto ventaId "$FILA")"
  dato compradorId "$(texto compradorId "$FILA")"
  dato eventoId "$(texto eventoId "$FILA")"
  dato montoTotal "$(numero montoTotal "$FILA")"
  dato recibidoEn "$(texto recibidoEn "$FILA")"
  exigir "tipo del mensaje" "$(texto tipoEvento "$FILA")" PAGO_CONFIRMADO
  exigir "monto del mensaje" "$(numero montoTotal "$FILA")" "$MONTO"
  bien "El suscriptor durable del tópico guardó el mensaje de esta venta."

  broker_despues 1

  printf '\n%s[OK] Compra %s: pagada, emitida por el consumidor de la cola y registrada por auditoría desde el tópico.%s\n' \
    "$VERDE" "$VENTA" "$FIN"
  exit 0
fi

# ------------------------------------------------------------ pago rechazado

paso "Pago de la compra con una tarjeta que la pasarela rechaza"
CLAVE_PAGO=$(uuid)
dato Idempotency-Key "$CLAVE_PAGO"
llamar POST "/api/v1/ventas/$VENTA/pagos" "$TOKEN" \
  "{\"medioDePago\":\"tarjeta\",\"token\":\"$TOKEN_RECHAZADO\"}" "$CLAVE_PAGO"
dato type "$(texto type "$CUERPO")"
dato title "$(texto title "$CUERPO")"
dato detail "$(texto detail "$CUERPO")"
exigir_http 402
exigir "tipo del error" "$(texto type "$CUERPO")" /problemas/pago-rechazado
bien "El pago respondió 402 pago-rechazado."

paso "Estado de la venta después del rechazo"
nota "Se esperan 2 s: si hubiera salido un mensaje, los consumidores ya lo habrían procesado."
sleep 2
llamar GET "/api/v1/ventas/$VENTA" "$TOKEN"
exigir_http 200
ESTADO=$(texto estado "$(venta_sin_entradas "$CUERPO")")
dato estado "$ESTADO"
PAGO=$(texto pagoId "$CUERPO")
dato pagoId "${PAGO:-null}"
dato entradas "$(contar '"id":"' "$(entradas_de "$CUERPO")")"
exigir "estado de la venta" "$ESTADO" EN_CHECKOUT
exigir "id del pago" "$(texto pagoId "$CUERPO")" ''
exigir "cantidad de entradas" "$(contar '"id":"' "$(entradas_de "$CUERPO")")" 0
bien "La venta no quedó confirmada: sigue EN_CHECKOUT, sin pago y sin entradas."

paso "Auditoría: no hay fila de esta venta (rol servicio)"
pedir_token_de_servicio
leer_auditoria
dato filas "$(contar "\"ventaId\":\"$VENTA\"" "$CUERPO") de la venta $VENTA, entre las últimas $(contar '"mensajeId":"' "$CUERPO") que guardó auditoría"
[ -z "$(fila_de_la_venta)" ] || fallar "auditoría registró un mensaje de la venta $VENTA, que no se pagó."
bien "Al tópico no llegó ningún mensaje de esta venta."

broker_despues 0

paso "Cierre: se cancela la compra y vuelve el cupo"
llamar DELETE "/api/v1/ventas/$VENTA" "$TOKEN"
exigir_http 204
VENTA_ABIERTA=''
llamar GET "/api/v1/ventas/$VENTA" "$TOKEN"
exigir_http 200
dato estado "$(texto estado "$(venta_sin_entradas "$CUERPO")")"
exigir "estado de la venta" "$(texto estado "$(venta_sin_entradas "$CUERPO")")" CANCELADA
leer_sector
exigir "vendidas del sector" "$VENDIDAS" "$VENDIDAS_ANTES"
bien "Vendidas volvió a $VENDIDAS_ANTES."

printf '\n%s[OK] Compra %s: el pago se rechazó, la venta no se confirmó y no salió ningún mensaje.%s\n' \
  "$VERDE" "$VENTA" "$FIN"
