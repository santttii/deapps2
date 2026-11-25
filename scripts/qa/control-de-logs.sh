#!/usr/bin/env bash
# Control del log del servidor después de una corrida contra docker compose. Lo corren los pasos "Control de logs"
# de ci.yml (integración), pruebas-seguridad.yml, contrato.yml y zap.yml, y en una máquina se corre igual:
#
#   bash scripts/qa/control-de-logs.sh logs/server.log
#
# Falla si el log tiene algo que no tiene que estar nunca en un log:
#   - un token: un JWT (eyJ...eyJ...) o "Bearer" seguido del token;
#   - una contraseña de prueba: la de las cuentas de prueba (palco1234) y las que mandan scripts/pruebas,
#     frontend/e2e y la colección de Postman;
#   - un DNI: los de las seis cuentas de prueba (migraciones V2, V3 y V6 de Usuarios), con puntos o sin ellos, y
#     cualquier "dni" seguido de un número de 7 u 8 dígitos (como lo escribirían un JSON, un toString o el error de
#     la base por un DNI repetido). Los titulares de los guiones tienen DNI al azar: los alcanza esa segunda
#     búsqueda.
#
# Lo que depende del modo de los emails, que se lee de la misma variable que el compose
# (PALCO_CORREO_LOG_COMPLETO; true en mayúsculas o minúsculas, como CorreoAlLog):
#   - Modo por defecto (sin la variable o en false): CorreoAlLog escribe el destinatario enmascarado
#     (t***@palco.test) y el asunto. Además de lo de arriba, falla con cualquier email @palco.test entero, en
#     cualquier renglón: es lo que pasaría si el compose volviera a prender el modo completo (DEF-36).
#   - Modo completo (true): cada email se escribe entero, con el destinatario y los nombres de quien lo recibe y
#     de los titulares; es la bandeja de entrada de la corrida, de donde las pruebas leen el código. Esos
#     renglones (los de CorreoAlLog) no se miran para el DNI, y los emails no se controlan. Tokens y contraseñas
#     sí se buscan también ahí: un email no los lleva nunca.
#
# Lo que encuentra sale con el número de renglón y qué es, nunca el renglón: el log del CI es público y el
# renglón tendría justo lo que no tiene que salir. El log entero queda en el artifact de la corrida.
set -euo pipefail

log=${1:?Uso: bash scripts/qa/control-de-logs.sh <server.log>}
[ -f "$log" ] || { echo "No está $log" >&2; exit 2; }

TOKEN='Bearer [A-Za-z0-9._-]{20,}|eyJ[A-Za-z0-9_-]{10,}\.eyJ'
CONTRASENA='palco1234|Clave1234|Nueva12345|nueva12345|incorrecta1|mal-intento1|otra-cosa1'
DNI_DE_CUENTA='(^|[^0-9])(29\.?888\.?777|27\.?444\.?555|29\.?666\.?777|30\.?555\.?444|28\.?333\.?222|29\.?111\.?000)([^0-9]|$)'
DNI_CON_NOMBRE='[Dd][Nn][Ii][^0-9A-Za-z]{0,6}[0-9]{1,2}\.?[0-9]{3}\.?[0-9]{3}([^0-9]|$)'
EMAIL_DE_PRUEBA='[A-Za-z0-9._%+-]+@palco\.test'

completo=false
case "${PALCO_CORREO_LOG_COMPLETO:-}" in [Tt][Rr][Uu][Ee]) completo=true ;; esac

# El log con los renglones de CorreoAlLog vacíos (sigue teniendo los mismos números de renglón): es lo que se
# mira para el DNI en el modo completo.
sin_emails=$(mktemp)
trap 'rm -f "$sin_emails"' EXIT
awk '{ if (index($0, "[com.palco.common.email.CorreoAlLog]")) print ""; else print }' "$log" > "$sin_emails"

fallas=0
controlar() {
  local que=$1 expresion=$2 archivo=$3 cuantos renglones
  cuantos=$(grep -cE -- "$expresion" "$archivo" || true)
  if [ "$cuantos" -gt 0 ]; then
    renglones=$(grep -nE -- "$expresion" "$archivo" | cut -d: -f1 | head -n 20 | paste -sd' ' - || true)
    echo "FALLA: el log tiene $que en $cuantos renglones de $log (los primeros: $renglones)"
    fallas=$((fallas + 1))
  else
    echo "ok: sin $que"
  fi
}

controlar 'tokens' "$TOKEN" "$log"
controlar 'contraseñas de prueba' "$CONTRASENA" "$log"
if [ "$completo" = true ]; then
  echo "(PALCO_CORREO_LOG_COMPLETO=true: los DNI se buscan fuera de los emails del modo completo y los emails no se controlan)"
  controlar 'DNI de las cuentas de prueba' "$DNI_DE_CUENTA" "$sin_emails"
  controlar 'DNI junto a la palabra dni' "$DNI_CON_NOMBRE" "$sin_emails"
else
  controlar 'DNI de las cuentas de prueba' "$DNI_DE_CUENTA" "$log"
  controlar 'DNI junto a la palabra dni' "$DNI_CON_NOMBRE" "$log"
  controlar 'emails @palco.test enteros' "$EMAIL_DE_PRUEBA" "$log"
fi

[ "$fallas" -eq 0 ] || { echo "El control de logs encontró $fallas tipo(s) de dato que no tienen que estar en $log."; exit 1; }
echo "Control de logs: $log está limpio ($(wc -l < "$log" | tr -d ' ') renglones)."
