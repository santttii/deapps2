#!/usr/bin/env bash
# OWASP ZAP contra Palco levantado con docker compose. Lo corre .github/workflows/zap.yml, y en una
# máquina se corre igual, con el sistema ya levantado:
#
#   docker compose up --build --detach --wait
#   bash scripts/qa/zap.sh
#
# Hace dos escaneos con la imagen oficial de ZAP, en una versión fija:
#   1. Baseline (pasivo) contra la app por nginx: el spider recorre lo que encuentra y ZAP revisa cada
#      respuesta (cabeceras, CSP, cookies, datos expuestos) sin atacar.
#   2. La API autenticada (pasivo y activo), también por nginx: ZAP importa docs/api/openapi.yaml, manda
#      cada operación con el token de una cuenta de prueba y después la ataca con la política API-Minimal
#      (inyección SQL y de comandos, path traversal, XSS, XXE, entre otras). Solo contra un sistema de
#      prueba: crea datos y manda entradas inválidas a propósito.
#
# ZAP corre en un contenedor conectado a la red del compose y le habla a nginx por su nombre de servicio
# (https://web): así anda igual en el CI y en Docker Desktop, sin depender de los puertos publicados. El
# login para conseguir el token sí se hace desde esta máquina, por el puerto publicado de nginx.
#
# Los reportes (HTML, JSON y Markdown de cada escaneo) quedan en logs/zap, que git ignora. Al final,
# scripts/qa/zap-resumen.mjs junta las alertas de los dos y aplica el criterio: falla con cualquier High y
# con cada Medium que no esté justificada en .zap/reglas.tsv. Una instancia High solo se descuenta si figura
# en ese archivo como falso positivo verificado, con su regla, método, URL y parámetro exactos; el resumen
# la muestra aparte. El resumen queda en logs/zap/resumen.md.
#
# Variables, todas opcionales:
#   PALCO_URL                 dónde pedir el login desde esta máquina. Sirve nginx o el backend, como en
#                             scripts/pruebas; por defecto, nginx en https://localhost (con el puerto de
#                             PALCO_PUERTO_HTTPS si el compose lo publica en otro)
#   ZAP_EMAIL, ZAP_PASSWORD   la cuenta del escaneo autenticado (titular@palco.test, palco1234)
#   ZAP_MINUTOS_ACTIVO        tope del escaneo activo, en minutos (7)
#   ZAP_SALIDA                carpeta de los reportes (logs/zap)
set -uo pipefail
cd "$(dirname "$0")/../.."

# 2.17.0 es la primera versión cuyo complemento de OpenAPI lee definiciones 3.1, como docs/api/openapi.yaml.
# Va fijada por el hash además del tag: un tag se puede mover, el hash no.
IMAGEN=ghcr.io/zaproxy/zaproxy:2.17.0@sha256:9624669db72f54d86c4b46a7064518bd9bf79d8c61a4c31befa9923489f328af

PUERTO_HTTPS=${PALCO_PUERTO_HTTPS:-443}
if [ "$PUERTO_HTTPS" = 443 ]; then PALCO_URL=${PALCO_URL:-https://localhost}; else PALCO_URL=${PALCO_URL:-https://localhost:$PUERTO_HTTPS}; fi
EMAIL=${ZAP_EMAIL:-titular@palco.test}
PASSWORD=${ZAP_PASSWORD:-palco1234}
MINUTOS_ACTIVO=${ZAP_MINUTOS_ACTIVO:-7}
SALIDA=${ZAP_SALIDA:-logs/zap}

# En Git Bash (Windows), que no convierta las rutas del contenedor (/zap/wrk) en rutas de Windows.
export MSYS_NO_PATHCONV=1

# Si se corta el guion (Ctrl+C o el tope de tiempo del CI), que no quede ZAP corriendo atrás.
contenedor=""
trap '[ -z "$contenedor" ] || docker rm -f "$contenedor" >/dev/null 2>&1' EXIT
trap 'exit 130' INT TERM

# Corre un comando y escribe una línea por minuto mientras sigue: el escaneo activo pasa varios minutos
# sin escribir nada, y así se ve que no quedó colgado.
con_aviso_por_minuto() {
  "$@" &
  local pid=$!
  ( while sleep 60; do kill -0 "$pid" 2>/dev/null || break; echo "  ... sigue ($(date +%H:%M))"; done ) &
  local aviso=$!
  wait "$pid"
  local codigo=$?
  kill "$aviso" 2>/dev/null
  wait "$aviso" 2>/dev/null
  return "$codigo"
}

# --- Lo que necesitan los dos escaneos ----------------------------------------------------------------

web=$(docker compose ps -q web | tr -d '\r')
[ -n "$web" ] || { echo "No está corriendo el contenedor web de docker compose: levantá el sistema primero." >&2; exit 2; }
red=$(docker inspect -f '{{range $nombre, $datos := .NetworkSettings.Networks}}{{$nombre}} {{end}}' "$web" \
  | tr -d '\r' | awk '{print $1}')

if ! docker image inspect "$IMAGEN" >/dev/null 2>&1; then
  for intento in 1 2 3 4; do
    docker pull -q "$IMAGEN" >/dev/null && break
    [ "$intento" -lt 4 ] || { echo "No se pudo bajar la imagen de ZAP." >&2; exit 2; }
    echo "Falló la descarga de la imagen de ZAP (intento $intento de 4)"
    sleep $((intento * 30))
  done
fi

mkdir -p "$SALIDA"
rm -f "$SALIDA"/*.json "$SALIDA"/*.html "$SALIDA"/*.md
chmod 777 "$SALIDA" 2>/dev/null || true   # ZAP escribe con el usuario zap del contenedor
# La carpeta como la entiende docker: en Git Bash, con la letra de la unidad.
carpeta=$(cd "$SALIDA" && (pwd -W 2>/dev/null || pwd))

# ZAP solo ve la carpeta montada: las reglas y la definición de la API van ahí. La copia de las reglas
# va sin los falsos positivos verificados (segunda columna FALSO_POSITIVO): ZAP no conoce ese valor y
# esos renglones los aplica solo el resumen, instancia por instancia. En la copia de la definición, el
# único servidor es nginx dentro de la red del compose (el original nombra localhost:8080 y un ejemplo de
# producción, que ZAP también probaría).
tr -d '\r' < .zap/reglas.tsv | awk -F'\t' '$2 != "FALSO_POSITIVO"' > "$SALIDA/reglas.tsv"
tr -d '\r' < docs/api/openapi.yaml | awk '
  /^servers:/ { print; print "  - url: https://web/api"; saltar = 1; next }
  saltar && /^[^ ]/ { saltar = 0 }
  !saltar { print }
' > "$SALIDA/openapi.yaml"

errores=0

# --- 1. Baseline -------------------------------------------------------------------------------------

echo "== 1. Baseline (pasivo) contra la app por nginx"
contenedor="palco-zap-baseline-$$"
con_aviso_por_minuto docker run --rm --name "$contenedor" --network "$red" -v "$carpeta:/zap/wrk:rw" \
  -e LC_ALL=C.UTF-8 "$IMAGEN" \
  zap-baseline.py -t https://web -m 2 -T 5 -I -s -c reglas.tsv \
  -r baseline.html -J baseline.json -w baseline.md
codigo=$?
# 0, 1 o 2 es que terminó (las alertas las evalúa el resumen); 3 o más, que no pudo.
[ "$codigo" -lt 3 ] || { echo "ZAP no pudo terminar el baseline (código $codigo)"; errores=1; }

# --- 2. API autenticada ------------------------------------------------------------------------------

echo "== 2. API autenticada (pasivo y activo, hasta $MINUTOS_ACTIVO minutos de escaneo activo)"
respuesta=$(curl -sk --max-time 30 -X POST "$PALCO_URL/api/usuarios/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
token=$(printf '%s' "$respuesta" | sed -nE 's/.*"token" *: *"([^"]+)".*/\1/p')
if [ -z "$token" ]; then
  echo "No se pudo iniciar sesión con $EMAIL en $PALCO_URL: el escaneo de la API no corre." >&2
  errores=1
else
  [ -z "${GITHUB_ACTIONS:-}" ] || echo "::add-mask::$token"
  # El token va por el entorno y no en la línea de comandos. ZAP lo agrega como cabecera Authorization en
  # cada pedido. No se le pasa reglas.tsv con -c: con un archivo de reglas, zap-api-scan.py cambia la
  # política API-Minimal por todas las reglas activas y el escaneo deja de entrar en el tiempo.
  export ZAP_AUTH_HEADER_VALUE="Bearer $token"
  contenedor="palco-zap-api-$$"
  con_aviso_por_minuto docker run --rm --name "$contenedor" --network "$red" -v "$carpeta:/zap/wrk:rw" \
    -e LC_ALL=C.UTF-8 -e ZAP_AUTH_HEADER_VALUE "$IMAGEN" \
    zap-api-scan.py -t openapi.yaml -f openapi -T 5 -I -s \
    -r api.html -J api.json -w api.md \
    -z "-config scanner.maxScanDurationInMins=$MINUTOS_ACTIVO -config scanner.maxRuleDurationInMins=2 -config scanner.threadPerHost=4"
  codigo=$?
  unset ZAP_AUTH_HEADER_VALUE
  [ "$codigo" -lt 3 ] || { echo "ZAP no pudo terminar el escaneo de la API (código $codigo)"; errores=1; }
  # Los reportes no llevan las cabeceras de los pedidos; igual, si el token apareciera en alguno, se tapa:
  # los reportes se suben como artifact.
  grep -l -F "$token" "$SALIDA"/* 2>/dev/null | while IFS= read -r archivo; do
    sed -i "s|$token|<token>|g" "$archivo"
  done
fi
contenedor=""

# --- Resumen y criterio ------------------------------------------------------------------------------

echo
node scripts/qa/zap-resumen.mjs "$SALIDA" .zap/reglas.tsv | tee "$SALIDA/resumen.md"
resultado=${PIPESTATUS[0]}
if [ "$errores" -ne 0 ]; then
  echo "Algún escaneo de ZAP no terminó: el resumen está incompleto." >&2
  exit 2
fi
exit "$resultado"
