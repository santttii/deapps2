#!/usr/bin/env bash
# Arma WildFly con los 9 servicios y lo levanta en http://localhost:8080.
# Cada servicio queda en /api/<servicio> (ej. /api/eventos).
# Para actualizar un servicio sin reiniciar: ./redesplegar.sh <servicio>
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
mvn -q -B install

# El secreto entre servicios nunca está en el repo: si no vino en el entorno ni en .env, se genera uno para
# este arranque, igual que en docker/arrancar.sh. El que usa el servidor queda en servidor/target (git lo
# ignora), para que los guiones de scripts/pruebas puedan pedir el token de rol servicio.
if [ -z "${PALCO_SECRETO_SERVICIOS:-}" ]; then
  PALCO_SECRETO_SERVICIOS="$(openssl rand -hex 32)"
  export PALCO_SECRETO_SERVICIOS
  echo "PALCO_SECRETO_SERVICIOS no está en el entorno ni en .env: se generó uno nuevo para este arranque."
fi
(umask 077 && printf '%s\n' "$PALCO_SECRETO_SERVICIOS" > servidor/target/secreto-servicios)

exec servidor/target/server/bin/standalone.sh
