#!/usr/bin/env bash
# jboss-cli del WildFly que corre en docker compose, para scripts/pruebas/escenarios.mjs:
#   JBOSS_CLI=scripts/qa/jboss-cli-docker.sh node scripts/pruebas/escenarios.mjs
# escenarios.mjs lo llama con "--connect --file=<archivo>": el archivo está en esta máquina, así
# que se manda al contenedor por la entrada estándar y se corre allá.
set -euo pipefail
cd "$(dirname "$0")/../.."

archivo=""
for arg in "$@"; do
  case "$arg" in
    --file=*) archivo="${arg#--file=}" ;;
  esac
done
[ -n "$archivo" ] || { echo "Uso: $0 --connect --file=<archivo.cli>" >&2; exit 2; }

docker compose exec -T backend bash -c '
  f=$(mktemp)
  cat > "$f"
  /opt/palco/server/bin/jboss-cli.sh --connect --file="$f"
  r=$?
  rm -f "$f"
  exit $r' < "$archivo"
