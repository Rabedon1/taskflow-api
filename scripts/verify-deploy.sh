#!/usr/bin/env bash
# Verificación post-despliegue: ¿la app desplegada está sana y corre EL commit esperado?
#
# Uso: verify-deploy.sh <base_url> <expected_sha> [timeout_seconds=600]
#
# Cada 10 s, hasta agotar el timeout:
#   1. GET <base_url>/actuator/health      → debe responder status "UP"
#   2. GET <base_url>/actuator/info        → .git.commit.id.full debe ser <expected_sha>
# Si el commit no coincide se sigue esperando: durante un despliegue sin
# interrupción, la versión ANTERIOR sigue respondiendo hasta que la nueva
# está lista. Además, en el plan free de Render puede haber un arranque en
# frío (el servicio dormido + el nuevo arrancando), por eso el timeout es largo.
#
# Exit 0: sano y con el commit correcto. Exit 1: timeout, indicando esperado y recibido.
# Sin "set -x" a propósito: nunca imprimir comandos (podrían incluir datos sensibles).
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Uso: $0 <base_url> <expected_sha> [timeout_seconds]" >&2
  exit 2
fi

base_url="${1%/}"
expected="$2"
timeout="${3:-600}"
interval=10
deadline=$(( $(date +%s) + timeout ))
last_status="sin respuesta"
last_sha="sin respuesta"

while :; do
  health=$(curl -fsS --max-time 30 "$base_url/actuator/health" 2>/dev/null || true)
  status=$(jq -r '.status // empty' <<<"$health" 2>/dev/null || true)
  last_status="${status:-sin respuesta}"

  if [ "$status" = "UP" ]; then
    info=$(curl -fsS --max-time 30 "$base_url/actuator/info" 2>/dev/null || true)
    actual=$(jq -r '.git.commit.id.full // empty' <<<"$info" 2>/dev/null || true)
    last_sha="${actual:-sin dato}"
    if [ "$actual" = "$expected" ]; then
      echo "OK: $base_url está UP y corre el commit $expected"
      exit 0
    fi
    echo "Esperando: status UP, commit $last_sha (expected $expected)"
  else
    echo "Esperando: health = $last_status"
  fi

  now=$(date +%s)
  if [ $(( now + interval )) -gt "$deadline" ]; then
    break
  fi
  sleep "$interval"
done

echo "ERROR: timeout de ${timeout}s. expected $expected, got $last_sha (último health: $last_status)" >&2
exit 1
