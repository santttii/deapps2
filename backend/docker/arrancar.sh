#!/usr/bin/env bash
# Arranque del backend dentro del contenedor.
# Los secretos nunca están en el repo ni en la imagen: si no vienen en el entorno se generan
# acá, en cada arranque. La clave JWT, si falta, la genera ServicioDeUsuarios al arrancar.
set -euo pipefail

if [ -z "${PALCO_SECRETO_SERVICIOS:-}" ]; then
  PALCO_SECRETO_SERVICIOS="$(openssl rand -hex 32)"
  export PALCO_SECRETO_SERVICIOS
  echo "PALCO_SECRETO_SERVICIOS no vino en el entorno: se generó uno nuevo para este arranque."
fi

# -b 0.0.0.0: escuchar fuera del contenedor (por defecto WildFly solo escucha en localhost).
exec /opt/palco/server/bin/standalone.sh -b 0.0.0.0
