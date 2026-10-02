#!/usr/bin/env bash
# Recompila un servicio y lo redespliega en el WildFly que levantó dev.sh.
# Uso: ./redesplegar.sh eventos
set -euo pipefail
cd "$(dirname "$0")"
servicio="${1:?Uso: ./redesplegar.sh <servicio> (usuarios, eventos, ventas, pagos, validacion, reventa, notificaciones, facturacion)}"
mvn -q -B package -pl "servicios/$servicio" -am
# WildFly detecta el archivo nuevo en deployments/ y lo redespliega solo.
cp "servicios/$servicio/target/$servicio.war" "servidor/target/server/standalone/deployments/palco-$servicio.war"
echo "Redesplegando ${servicio}… (tarda unos segundos)"
