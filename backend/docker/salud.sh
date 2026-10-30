#!/usr/bin/env bash
# Healthcheck del contenedor: los 8 servicios tienen que contestar 200 en su /health.
# La imagen no trae curl: el pedido HTTP se hace con /dev/tcp de bash.
set -u

rutas=(
  /api/usuarios/health
  /api/eventos/health
  /api/v1/health
  /api/v1/pagos/health
  /api/v1/validacion/health
  /api/v1/reventas/health
  /api/notificaciones/health
  /api/v1/facturas/health
)

for ruta in "${rutas[@]}"; do
  exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
  printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' "$ruta" >&3
  read -r -t 5 estado <&3 || { exec 3>&-; exit 1; }
  exec 3>&-
  case "$estado" in
    *" 200 "*) ;;
    *) echo "$ruta: $estado"; exit 1 ;;
  esac
done
