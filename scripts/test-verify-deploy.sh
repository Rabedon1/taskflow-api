#!/usr/bin/env bash
# Prueba de scripts/verify-deploy.sh contra la app real levantada con compose.
#   (a) SHA correcto            → exit 0
#   (b) SHA incorrecto          → exit 1 y mensaje "expected deadbeef"
#   (c) servidor que no responde → exit 1 al agotar el timeout, sin colgarse
# Uso (Git Bash, con Docker corriendo):  bash scripts/test-verify-deploy.sh
set -uo pipefail
cd "$(dirname "$0")/.."

VERIFY=scripts/verify-deploy.sh
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

if [ ! -f "$VERIFY" ]; then
  echo "FAIL: $VERIFY no existe"; exit 1
fi

echo "== Construyendo el JAR del commit actual y levantando compose"
./mvnw -B -q package -DskipTests || { echo "FAIL: build"; exit 1; }
docker compose up -d --build >/dev/null 2>&1 || { echo "FAIL: compose up"; exit 1; }
trap 'docker compose down >/dev/null 2>&1' EXIT

head_sha=$(git rev-parse HEAD)

# (a) el commit que corre es el esperado
if bash "$VERIFY" http://localhost:8080 "$head_sha" 120; then
  pass "(a) SHA correcto → exit 0"
else
  bad "(a) SHA correcto debería dar exit 0"
fi

# (b) el commit no coincide
out=$(bash "$VERIFY" http://localhost:8080 deadbeef 60 2>&1); code=$?
if [ "$code" -eq 1 ] && grep -q "expected deadbeef" <<<"$out"; then
  pass "(b) SHA incorrecto → exit 1 con 'expected deadbeef'"
else
  bad "(b) esperado exit 1 con 'expected deadbeef'; exit=$code salida: $out"
fi

# (c) nadie escucha en el puerto 9: debe rendirse al llegar al timeout
start=$(date +%s)
bash "$VERIFY" http://localhost:9 "$head_sha" 20 >/dev/null 2>&1; code=$?
elapsed=$(( $(date +%s) - start ))
if [ "$code" -eq 1 ] && [ "$elapsed" -ge 15 ] && [ "$elapsed" -le 60 ]; then
  pass "(c) sin servidor → exit 1 tras ${elapsed}s"
else
  bad "(c) esperado exit 1 en ~20s; exit=$code tras ${elapsed}s"
fi

exit "$fail"
