#!/usr/bin/env bash
# Arma WildFly con los 8 servicios y lo levanta en http://localhost:8080.
# Cada servicio queda en /api/<servicio> (ej. /api/eventos).
# Para actualizar un servicio sin reiniciar: ./redesplegar.sh <servicio>
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
mvn -q -B install
exec servidor/target/server/bin/standalone.sh
