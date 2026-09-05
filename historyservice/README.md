# history service

Serviço de histórico do Tech Challenge FIAP - Fase 3 - Grupo 65.

Este documento descreve como subir o ambiente de **teste local**: PostgreSQL e RabbitMQ em Docker,
com a aplicação Spring Boot rodando na máquina e consumindo a fila.

## Pré-requisitos

| Ferramenta | Versão usada na validação |
|---|---|
| Docker Engine | 28.5 |
| Docker Compose | v2.40 (plugin `docker compose`) |
| JDK | 21 |
| Maven | via wrapper `./mvnw` (não precisa instalar) |

Portas que precisam estar livres: `5432` (Postgres), `5672` (AMQP), `15672` (console RabbitMQ) e `8080` (aplicação).

## 1. Configurar as variáveis de ambiente

O `.env` é lido tanto pelo Docker Compose quanto pela aplicação. Se você ainda não tem um:

```bash
cp .env.example .env
```

Os valores padrão já funcionam para desenvolvimento local:

| Variável | Padrão | Usada por |
|---|---|---|
| `COMPOSE_PROJECT_NAME` | `historyservice` | prefixo dos containers e volumes |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `mydatabase` / `postgres` / `postgres` | Postgres e datasource do Spring |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | datasource do Spring |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | `guest` / `guest` / `/` | RabbitMQ e Spring AMQP |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | conexão AMQP |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | console web do RabbitMQ |
| `RABBITMQ_EXCHANGE` / `RABBITMQ_QUEUE` / `RABBITMQ_ROUTING_KEY` | `history.exchange` / `history.queue` / `history.created` | topologia declarada pela aplicação |
| `SERVER_PORT` | `8080` | porta HTTP da aplicação |

> O `.env` contém credenciais — mantenha fora do controle de versão e versione apenas o `.env.example`.

## 2. Subir a infraestrutura

```bash
docker compose up -d
```

Isso cria dois containers na rede `app-network`:

- **`historyservice-postgres`** — `postgres:16-alpine`, dados no volume `postgres-data`
- **`historyservice-rabbitmq`** — `rabbitmq:4-management-alpine`, dados no volume `rabbitmq-data`

Ambos têm healthcheck. Aguarde até os dois aparecerem como `(healthy)`:

```bash
docker compose ps
```

```
NAME                      STATUS
historyservice-postgres   Up 30 seconds (healthy)
historyservice-rabbitmq   Up 30 seconds (healthy)
```

Console do RabbitMQ: <http://localhost:15672> (`guest` / `guest`).

## 3. Rodar a aplicação Spring

As propriedades leem do ambiente, então exporte o `.env` antes de subir a aplicação:

```bash
set -a; source .env; set +a
```

Depois escolha uma das formas:

```bash
# opção A — plugin do Spring Boot (bom para desenvolvimento)
./mvnw spring-boot:run

# opção B — empacotar e executar o jar
./mvnw clean package
java -jar target/historyservice-0.0.1-SNAPSHOT.jar
```

Sem exportar o `.env` a aplicação ainda sobe: todas as propriedades têm valores padrão iguais
aos do arquivo (`localhost`, `postgres/postgres`, `guest/guest`).

Na inicialização a aplicação declara sozinha a topologia no RabbitMQ (exchange, fila, binding e DLQ)
e o Flyway cria a tabela `flyway_schema_history` no Postgres.

## 4. Verificar se está tudo no ar

```bash
curl -s http://localhost:8080/actuator/health | jq
```

Esperado — `status`, `db` e `rabbit` todos `UP`:

```json
{
  "status": "UP",
  "components": {
    "db":     { "status": "UP", "details": { "database": "PostgreSQL" } },
    "rabbit": { "status": "UP", "details": { "version": "4.3.5" } }
  }
}
```

Filas e exchanges criadas:

```bash
docker exec historyservice-rabbitmq rabbitmqctl list_queues name messages consumers
docker exec historyservice-rabbitmq rabbitmqctl list_exchanges name type
```

`history.queue` deve aparecer com **1 consumer** (o listener da aplicação), ao lado de `history.queue.dlq`.

## 5. Testar a ingestão manualmente

O `history-service` é um log **append-only**: cada `AppointmentEvent` recebido do RabbitMQ vira uma
linha nova em `medical_history`. Nenhum registro é atualizado ou apagado, então o histórico de uma
consulta é o conjunto das suas linhas ordenado por `occurred_at`.

O contrato completo da mensagem está em
[`docs/messaging/appointment-event.md`](docs/messaging/appointment-event.md).

### Publicar um evento

Pela API de management, usando só `curl` e `python3`:

```bash
EVENT_ID=$(cat /proc/sys/kernel/random/uuid)

cat > /tmp/evento.json <<EOF
{
  "eventId": "$EVENT_ID",
  "eventStatus": "SCHEDULED",
  "occurredAt": "2026-08-30T14:00:00Z",
  "appointmentId": 42,
  "patientId": 10,
  "patientName": "Maria Souza",
  "doctorId": 7,
  "doctorName": "Dr. Joao Lima",
  "appointmentDate": "2026-09-05T09:00:00",
  "description": "Consulta de rotina"
}
EOF

python3 -c '
import json
payload = open("/tmp/evento.json").read()
print(json.dumps({
    "properties": {"content_type": "application/json", "delivery_mode": 2},
    "routing_key": "history.created",
    "payload": payload,
    "payload_encoding": "string",
}))
' > /tmp/publish.json

curl -s -u guest:guest -H 'content-type: application/json' \
  -X POST http://localhost:15672/api/exchanges/%2F/history.exchange/publish \
  -d @/tmp/publish.json
```

Resposta esperada: `{"routed":true}`.

Pelo console web dá para fazer o mesmo em **Exchanges → `history.exchange` → Publish message**, com
routing key `history.created`, a propriedade `content_type` = `application/json` e o JSON no campo
Payload.

> Não é preciso enviar o header `__TypeId__`. O Spring AMQP infere o tipo pelo parâmetro do
> listener, então um produtor não-Spring publica normalmente.

No log da aplicação aparece:

```
INFO ... b.c.t.c.h.m.AppointmentEventListener : AppointmentEvent recebido: eventId=... appointmentId=42 status=SCHEDULED
```

### Conferir a persistência

```bash
docker exec historyservice-postgres psql -U postgres -d mydatabase \
  -c "SELECT appointment_id, event_status, appointment_date, occurred_at FROM medical_history ORDER BY occurred_at;"
```

E a profundidade das filas:

```bash
curl -s -u guest:guest 'http://localhost:15672/api/queues/%2F?columns=name,messages' | python3 -m json.tool
```

Com apenas eventos válidos publicados, `history.queue` e `history.queue.dlq` ficam ambas em `0`.

### Cenários que valem testar

| Publique | Resultado esperado |
|---|---|
| Evento válido | 1 linha nova em `medical_history` |
| O **mesmo `eventId`** de novo | nenhuma linha nova; log `WARN "Evento ... ja processado"` |
| Outro `eventId` e `eventStatus: "RESCHEDULED"` com nova `appointmentDate` | 2ª linha — a data antiga continua na 1ª |
| `eventStatus: "COMPLETED"` | 3ª linha, fechando o ciclo de vida da consulta |
| Um campo fora do contrato (ex.: `"campoInesperado": true`) | nada persistido; +1 em `history.queue.dlq` |
| Sem `appointmentDate` ou sem `eventStatus` | nada persistido; +1 em `history.queue.dlq` |

Para inspecionar o que caiu na DLQ: **Queues** → `history.queue.dlq` → **Get messages**.

### Tabela `medical_history`

| Coluna | Origem |
|---|---|
| `event_id` | do evento — `UNIQUE`, deduplica reentregas do RabbitMQ |
| `event_status` | a transição declarada: `SCHEDULED`, `RESCHEDULED`, `CANCELLED` ou `COMPLETED` |
| `appointment_id`, `patient_id`, `doctor_id` | do evento — apenas IDs, sem relacionamento JPA entre serviços |
| `patient_name`, `doctor_name` | snapshot opcional do evento (podem ser `NULL`) |
| `appointment_date` | início da consulta no momento do evento — `NOT NULL`, inclusive em cancelamento |
| `occurred_at` | quando o evento ocorreu no produtor; ordena a trilha |
| `recorded_at` | quando o `history-service` gravou |

## 5b. Consultar o histórico por GraphQL

Com a aplicação no ar, o schema pode ser explorado em http://localhost:8080/graphiql.

Estado atual de cada consulta do paciente:

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate doctorName } }"}' \
  | python3 -m json.tool
```

Trilha completa de uma consulta:

```bash
curl -s -X POST http://localhost:8080/graphql \
  -H 'content-type: application/json' \
  -d '{"query":"{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate occurredAt } }"}' \
  | python3 -m json.tool
```

As duas queries, os campos disponíveis e os formatos de resposta estão em
[`docs/graphql/queries.md`](docs/graphql/queries.md).

> O endpoint `/graphql` está **aberto** nesta fase. A autorização por role entra quando a Pessoa 1
> publicar o formato do JWT.

## 6. Rodar os testes

Os testes sobem Postgres e RabbitMQ descartáveis via Testcontainers, então basta ter o **Docker em
execução** — não é preciso subir o `docker compose` antes, nem carregar o `.env`.

```bash
./mvnw test
```

## 7. Encerrar o ambiente

```bash
docker compose down       # para os containers, preserva os dados nos volumes
docker compose down -v    # remove também os volumes (banco e fila zerados)
```

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `Connection refused: localhost:5432` ou `:5672` | containers ainda subindo ou parados | `docker compose ps` e aguardar `(healthy)` |
| `port is already allocated` | outro serviço usando 5432/5672/15672 | mudar a porta no `.env` (o compose usa `${DB_PORT}` e `${RABBITMQ_PORT}`) |
| `Port 8080 was already in use` | outra aplicação na 8080 | `SERVER_PORT=8081` no `.env` |
| Fila com `0 consumers` | aplicação não conectou | conferir o log de inicialização e as credenciais AMQP |
| Mensagem publicada mas nada em `medical_history` | payload fora do contrato ou `content_type` ausente | conferir a `history.queue.dlq` e o log; comparar com `docs/messaging/appointment-event.md` |
| `variable is not set` no `docker compose up` | falta o `.env` | `cp .env.example .env` |
