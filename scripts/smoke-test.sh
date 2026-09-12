#!/usr/bin/env bash
# Teste ponta a ponta: appointment-service -> RabbitMQ -> history-service.
# Cria um agendamento e espera o registro correspondente aparecer no historico.
set -euo pipefail

APPOINTMENT_URL="${APPOINTMENT_URL:-http://localhost:8080}"
HISTORY_URL="${HISTORY_URL:-http://localhost:8081}"
TIMEOUT_SEGUNDOS="${TIMEOUT_SEGUNDOS:-60}"

# patientId aleatorio para o teste ser repetivel sem limpar o banco
PATIENT_ID=$(( (RANDOM % 900000) + 100000 ))
APPOINTMENT_DATE=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')

echo "==> 1/3 aguardando os servicos responderem"
for url in "$APPOINTMENT_URL/actuator/health" "$HISTORY_URL/actuator/health"; do
  fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
  until curl -sf "$url" | grep -q '"status":"UP"'; do
    if (( SECONDS >= fim )); then
      echo "FALHA: $url nao ficou UP em ${TIMEOUT_SEGUNDOS}s" >&2
      exit 1
    fi
    sleep 2
  done
  echo "    OK $url"
done

echo "==> 2/3 criando agendamento (patientId=$PATIENT_ID)"
resposta=$(curl -s -w '\n%{http_code}' -X POST "$APPOINTMENT_URL/appointments" \
  -H 'Content-Type: application/json' \
  -d "{\"patientId\":$PATIENT_ID,\"doctorId\":7,\"appointmentDate\":\"$APPOINTMENT_DATE\",\"description\":\"Smoke test\"}") \
  || { echo "FALHA: nao consegui falar com $APPOINTMENT_URL" >&2; exit 1; }
http_code=$(echo "$resposta" | tail -n1)
corpo=$(echo "$resposta" | sed '$d')
if [ "$http_code" != "201" ]; then
  echo "FALHA: POST /appointments retornou $http_code" >&2
  echo "Resposta: $corpo" >&2
  exit 1
fi
echo "    resposta: $corpo"

echo "==> 3/3 aguardando o evento chegar no history-service via RabbitMQ"
consulta="{\"query\":\"{ patientHistory(patientId: \\\"$PATIENT_ID\\\") { appointmentId eventStatus description } }\"}"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  historico=$(curl -sf -X POST "$HISTORY_URL/graphql" \
    -H 'Content-Type: application/json' -d "$consulta" || echo '')
  if echo "$historico" | grep -q '"eventStatus":"SCHEDULED"'; then
    echo "    OK evento recebido: $historico"
    echo
    echo "SUCESSO: a comunicacao via RabbitMQ esta funcionando."
    exit 0
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: o evento nao chegou ao history-service em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta do GraphQL: $historico" >&2
    echo "Investigue: docker compose logs history-app | grep -i rabbit" >&2
    exit 1
  fi
  sleep 2
done
