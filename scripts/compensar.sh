#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"

CAMUNDA="${CAMUNDA:-http://localhost:8188}"
ID="${1:?uso: compensar.sh <propostaId> [CompensarCancelada|CompensarExpirada]}"
DESTINO="${2:-CompensarCancelada}"

KEY=$(curl -s "$APP/api/propostas/$ID" | jq -r .processInstanceKey)
ATIVOS="[]"
for _ in $(seq 20); do
  ATIVOS=$(curl -s -X POST "$CAMUNDA/v2/element-instances/search" -H 'Content-Type: application/json' \
    -d "{\"filter\":{\"processInstanceKey\":\"$KEY\",\"state\":\"ACTIVE\"},\"page\":{\"limit\":50}}" \
    | jq -c "[.items[] | select(.type != \"PROCESS\") | {elementInstanceKey}]")
  [ "$ATIVOS" != "[]" ] && break
  sleep 0.5
done

if [ "$ATIVOS" = "[]" ]; then
  echo "instância $KEY não tem nada ativo (já terminou ou foi cancelada)"
  exit 1
fi

passo "instância $KEY: encerrando $ATIVOS e ativando $DESTINO"
curl -s -o /dev/null -w '   modificação: HTTP %{http_code}\n' -X POST "$CAMUNDA/v2/process-instances/$KEY/modification" \
  -H 'Content-Type: application/json' \
  -d "{\"activateInstructions\":[{\"elementId\":\"$DESTINO\"}],\"terminateInstructions\":$ATIVOS}"

esperar_desfecho "$ID" 20
mostrar "$ID"
