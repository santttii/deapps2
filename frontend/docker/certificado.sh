#!/bin/sh
# Genera el certificado autofirmado de nginx la primera vez que arranca el contenedor.
# No se guarda en el repo ni en la imagen: cada instalación tiene el suyo.
set -eu
dir=/etc/nginx/tls
if [ ! -f "$dir/palco.crt" ]; then
  mkdir -p "$dir"
  openssl req -x509 -nodes -newkey rsa:2048 -days 30 \
    -keyout "$dir/palco.key" -out "$dir/palco.crt" \
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  chmod 600 "$dir/palco.key"
  echo "Certificado autofirmado generado en $dir"
fi
