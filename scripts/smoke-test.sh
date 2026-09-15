#!/usr/bin/env bash
# Teste ponta a ponta: login no auth-service -> appointment-service -> RabbitMQ ->
# history-service e notification-service. Cria um agendamento autenticado como enfermeira e
# espera o evento aparecer no historico e virar uma notificacao enviada.
set -euo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:8083}"
APPOINTMENT_URL="${APPOINTMENT_URL:-http://localhost:8080}"
HISTORY_URL="${HISTORY_URL:-http://localhost:8081}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8082}"
TIMEOUT_SEGUNDOS="${TIMEOUT_SEGUNDOS:-60}"
LOGIN_EMAIL="${LOGIN_EMAIL:-maria.santos@hospital.com}"
LOGIN_SENHA="${LOGIN_SENHA:-Enfermeira@123}"
# 4 e o user_id do paciente de exemplo (lucas.oliveira@hospital.com)
PATIENT_ID="${PATIENT_ID:-4}"
APPOINTMENT_DATE=$(date -u -d '+30 days' '+%Y-%m-%dT%H:%M:%S' 2>/dev/null || date -u -v+30d '+%Y-%m-%dT%H:%M:%S')

echo "==> 1/5 aguardando os servicos responderem"
for url in "$AUTH_URL/actuator/health" "$APPOINTMENT_URL/actuator/health" \
           "$HISTORY_URL/actuator/health" "$NOTIFICATION_URL/actuator/health"; do
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

echo "==> 2/5 autenticando como $LOGIN_EMAIL"
login=$(curl -s -X POST "$AUTH_URL/auth/login" -u "$LOGIN_EMAIL:$LOGIN_SENHA" || echo '')
TOKEN=$(echo "$login" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "FALHA: login nao devolveu access_token" >&2
  echo "Resposta: $login" >&2
  echo "Investigue: docker compose logs auth-app | grep -iE 'flyway|seed|key'" >&2
  exit 1
fi
echo "    OK token recebido"
AUTH_HEADER="Authorization: Bearer $TOKEN"

echo "==> 3/5 criando agendamento (patientId=$PATIENT_ID)"
resposta=$(curl -s -w '\n%{http_code}' -X POST "$APPOINTMENT_URL/appointments" \
  -H 'Content-Type: application/json' -H "$AUTH_HEADER" \
  -d "{\"patientId\":$PATIENT_ID,\"doctorId\":2,\"appointmentDate\":\"$APPOINTMENT_DATE\",\"description\":\"Smoke test\"}") \
  || { echo "FALHA: nao consegui falar com $APPOINTMENT_URL" >&2; exit 1; }
http_code=$(echo "$resposta" | tail -n1)
corpo=$(echo "$resposta" | sed '$d')
if [ "$http_code" != "201" ]; then
  echo "FALHA: POST /appointments retornou $http_code" >&2
  echo "Resposta: $corpo" >&2
  exit 1
fi
APPOINTMENT_ID=$(echo "$corpo" | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
if [ -z "$APPOINTMENT_ID" ]; then
  echo "FALHA: nao consegui ler o id do agendamento criado" >&2
  echo "Resposta: $corpo" >&2
  exit 1
fi
echo "    resposta: $corpo"

echo "==> 4/5 aguardando o evento chegar no history-service via RabbitMQ"
consulta="{\"query\":\"{ appointmentTimeline(appointmentId: \\\"$APPOINTMENT_ID\\\") { appointmentId eventStatus description } }\"}"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  historico=$(curl -sf -X POST "$HISTORY_URL/graphql" \
    -H 'Content-Type: application/json' -H "$AUTH_HEADER" -d "$consulta" || echo '')
  if echo "$historico" | grep -q '"eventStatus":"SCHEDULED"'; then
    echo "    OK evento recebido: $historico"
    break
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: o evento nao chegou ao history-service em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta do GraphQL: $historico" >&2
    echo "Investigue: docker compose logs history-app | grep -iE 'rabbit|jwt|key'" >&2
    exit 1
  fi
  sleep 2
done

echo "==> 5/5 aguardando a notificacao ser enviada pelo notification-service"
fim=$(( SECONDS + TIMEOUT_SEGUNDOS ))
while true; do
  notificacoes=$(curl -sf -H "$AUTH_HEADER" "$NOTIFICATION_URL/notifications/patient/$PATIENT_ID" || echo '')
  if echo "$notificacoes" | grep -q "\"appointmentId\":$APPOINTMENT_ID,[^}]*\"status\":\"SENT\""; then
    echo "    OK notificacao enviada para o agendamento $APPOINTMENT_ID"
    echo
    echo "SUCESSO: login -> appointment -> RabbitMQ -> history e notification funcionando."
    exit 0
  fi
  if (( SECONDS >= fim )); then
    echo "FALHA: a notificacao nao foi enviada em ${TIMEOUT_SEGUNDOS}s" >&2
    echo "Ultima resposta: $notificacoes" >&2
    echo "Investigue: docker compose logs notification-app | grep -iE 'rabbit|notifica|jwt'" >&2
    exit 1
  fi
  sleep 2
done
