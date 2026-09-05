# Queries GraphQL do history-service

Endpoint: `POST /graphql`
IDE web para explorar o schema: http://localhost:8080/graphiql

O `history-service` é um log **append-only** — cada evento do `appointment-service` vira uma linha
nova. As duas queries oferecem as duas leituras úteis desse log.

## `patientHistory(patientId: ID!)`

Estado **atual** de cada consulta do paciente: uma entrada por consulta, com o evento mais recente.
Ordenadas da consulta com atividade mais recente para a mais antiga.

```graphql
query {
  patientHistory(patientId: 10) {
    appointmentId
    doctorName
    appointmentDate
    eventStatus
    description
  }
}
```

```json
{
  "data": {
    "patientHistory": [
      {
        "appointmentId": "42",
        "doctorName": "Dr. João Lima",
        "appointmentDate": "2026-09-12T14:00:00",
        "eventStatus": "COMPLETED",
        "description": "Atendimento realizado"
      }
    ]
  }
}
```

Paciente sem histórico devolve `[]`, não erro: o `history-service` não conhece o cadastro de
pacientes e não pode afirmar que o paciente não existe.

## `appointmentTimeline(appointmentId: ID!)`

Trilha **completa** de uma consulta, do evento mais antigo ao mais recente.

```graphql
query {
  appointmentTimeline(appointmentId: 42) {
    eventStatus
    appointmentDate
    occurredAt
  }
}
```

```json
{
  "data": {
    "appointmentTimeline": [
      { "eventStatus": "SCHEDULED",   "appointmentDate": "2026-09-05T09:00:00", "occurredAt": "2026-08-30T14:00:00Z" },
      { "eventStatus": "RESCHEDULED", "appointmentDate": "2026-09-12T14:00:00", "occurredAt": "2026-08-31T10:00:00Z" },
      { "eventStatus": "COMPLETED",   "appointmentDate": "2026-09-12T14:00:00", "occurredAt": "2026-09-12T15:00:00Z" }
    ]
  }
}
```

Repare que a data original (5/set) continua visível na primeira linha depois da remarcação para
12/set. É isso que o log append-only entrega e uma tabela de estado não entregaria.

## Campos de `MedicalRecord`

| Campo | Tipo | Observação |
|---|---|---|
| `id` | `ID!` | identificador da **linha do histórico**, não da consulta |
| `appointmentId` | `ID!` | agrupa a trilha |
| `patientId` | `ID!` | |
| `patientName` | `String` | snapshot do evento; pode ser `null` |
| `doctorId` | `ID!` | |
| `doctorName` | `String` | snapshot do evento; pode ser `null` |
| `description` | `String` | pode ser `null` |
| `appointmentDate` | `String!` | início da consulta neste evento, ISO-8601 sem timezone |
| `eventStatus` | `AppointmentEventStatus!` | `SCHEDULED`, `RESCHEDULED`, `CANCELLED` ou `COMPLETED` |
| `occurredAt` | `String!` | quando o evento ocorreu no produtor, ISO-8601 UTC |

Datas são `String` ISO-8601, não um scalar customizado: `graphql-java-extended-scalars` só existe
até a 22.0 enquanto o Boot 4.1 gerencia graphql-java 25.0.

## Testar por `curl`

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

## Erros

| Situação | Resposta |
|---|---|
| Paciente ou consulta sem registros | `data` com lista vazia, sem `errors` |
| Argumento ausente ou de tipo errado | `errors[0].extensions.classification` = `ValidationError` (validação do próprio graphql-java) |
| Campo inexistente na query | `errors[0].extensions.classification` = `ValidationError` |
| Argumento rejeitado pelo service | `errors[0].extensions.classification` = `BAD_REQUEST` |

## Ainda não implementado

Autorização por role (`PATIENT` só acessa o próprio `patientId`; `DOCTOR`/`NURSE` acessam
qualquer um) entra quando a Pessoa 1 publicar o formato do JWT. Hoje o endpoint está aberto —
apropriado só para ambiente local.
