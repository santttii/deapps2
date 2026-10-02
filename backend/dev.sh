#!/usr/bin/env bash
# Levanta el backend en modo desarrollo: WildFly en http://localhost:8080
# y redespliegue automático al guardar cambios en app/.
# Si cambiás algo en common/, cortá (Ctrl+C) y volvé a correr este script.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
mvn -q -B install -pl common -am -DskipTests
cd app
exec mvn clean wildfly:dev
