#!/usr/bin/env bash
# Publica um AppointmentEvent na exchange do history-service.
#
#   ./scripts/publicar-evento.sh <eventStatus> [appointmentId] [patientId] [appointmentDate] [occurredAt]
#
# Exemplos:
#   ./scripts/publicar-evento.sh SCHEDULED
#   ./scripts/publicar-evento.sh RESCHEDULED 42 10 2026-09-12T14:00:00
#   ./scripts/publicar-evento.sh COMPLETED   42 10 2026-09-12T14:00:00 2026-09-12T15:00:00Z
#
# Sem argumentos extras usa consulta 42 / paciente 10. O eventId e sempre novo, entao
# republicar o mesmo comando gera uma linha nova -- para testar deduplicacao, use --repetir.
set -euo pipefail

REPETIR=""
if [ "${1:-}" = "--repetir" ]; then REPETIR="sim"; shift; fi

EVENT_STATUS="${1:-SCHEDULED}"
APPOINTMENT_ID="${2:-42}"
PATIENT_ID="${3:-10}"
APPOINTMENT_DATE="${4:-2026-09-05T09:00:00}"
OCCURRED_AT="${5:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

HOST="${RABBITMQ_HOST:-localhost}"
PORT="${RABBITMQ_MANAGEMENT_PORT:-15672}"
USER="${RABBITMQ_USER:-guest}"
PASS="${RABBITMQ_PASSWORD:-guest}"
EXCHANGE="${RABBITMQ_EXCHANGE:-history.exchange}"
ROUTING_KEY="${RABBITMQ_ROUTING_KEY:-history.created}"

# --repetir reusa o ultimo eventId gravado em /tmp, para exercitar a idempotencia.
ULTIMO=/tmp/history-ultimo-event-id
if [ -n "$REPETIR" ] && [ -f "$ULTIMO" ]; then
    EVENT_ID=$(cat "$ULTIMO")
    echo "repetindo eventId $EVENT_ID (deve ser ignorado pelo servico)"
else
    EVENT_ID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen)
    echo "$EVENT_ID" > "$ULTIMO"
fi

RESPOSTA=$(EVENT_ID="$EVENT_ID" EVENT_STATUS="$EVENT_STATUS" APPOINTMENT_ID="$APPOINTMENT_ID" \
  PATIENT_ID="$PATIENT_ID" APPOINTMENT_DATE="$APPOINTMENT_DATE" OCCURRED_AT="$OCCURRED_AT" \
  ROUTING_KEY="$ROUTING_KEY" \
  python3 -c '
import json, os
evento = {
    "eventId": os.environ["EVENT_ID"],
    "eventStatus": os.environ["EVENT_STATUS"],
    "occurredAt": os.environ["OCCURRED_AT"],
    "appointmentId": int(os.environ["APPOINTMENT_ID"]),
    "patientId": int(os.environ["PATIENT_ID"]),
    "patientName": "Maria Souza",
    "doctorId": 7,
    "doctorName": "Dr. Joao Lima",
    "appointmentDate": os.environ["APPOINTMENT_DATE"],
    "description": "Consulta de rotina",
}
print(json.dumps({
    "properties": {"content_type": "application/json", "delivery_mode": 2},
    "routing_key": os.environ.get("ROUTING_KEY", "history.created"),
    "payload": json.dumps(evento),
    "payload_encoding": "string",
}))
' | curl -s -u "$USER:$PASS" -H 'content-type: application/json' \
       -X POST "http://$HOST:$PORT/api/exchanges/%2F/$EXCHANGE/publish" -d @-)

echo "$EVENT_STATUS consulta=$APPOINTMENT_ID paciente=$PATIENT_ID data=$APPOINTMENT_DATE -> $RESPOSTA"
