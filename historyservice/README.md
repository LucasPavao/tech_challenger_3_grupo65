# history service

Serviço de histórico médico do Tech Challenge FIAP — Fase 3, Grupo 65.

Consome eventos de consulta publicados pelo `appointment-service` no RabbitMQ e expõe o histórico
por GraphQL. É um **log append-only**: cada evento vira uma linha nova, nada é atualizado ou
apagado. Uma consulta agendada, remarcada e concluída tem três linhas — e a data original continua
visível na primeira.

## Início rápido

Requisitos: Docker, JDK 21. Maven vem no wrapper.

```bash
cp .env.example .env          # valores padrão já servem para desenvolvimento
docker compose up -d          # Postgres + RabbitMQ
set -a; source .env; set +a
./mvnw spring-boot:run
```

Pronto quando aparecer `Started HistoryApplication`. A aplicação cria sozinha a topologia do
RabbitMQ (exchange, fila, binding e DLQ) e o Flyway cria a tabela.

| Endereço | O quê |
|---|---|
| <http://localhost:8080/graphql> | endpoint GraphQL |
| <http://localhost:8080/graphiql> | IDE web para explorar o schema |
| <http://localhost:15672> | console do RabbitMQ (`guest` / `guest`) |
| <http://localhost:8080/actuator/health> | `db` e `rabbit` devem estar `UP` |

## Testar o serviço

### 1. Publicar eventos

O script monta o payload e publica na exchange:

```bash
./scripts/publicar-evento.sh SCHEDULED   42 10 2026-09-05T09:00:00
./scripts/publicar-evento.sh RESCHEDULED 42 10 2026-09-12T14:00:00
./scripts/publicar-evento.sh COMPLETED   42 10 2026-09-12T14:00:00
```

Argumentos: `<eventStatus> [appointmentId] [patientId] [appointmentDate]`. Cada chamada gera um
`eventId` novo. Para testar a deduplicação, repita o último evento:

```bash
./scripts/publicar-evento.sh --repetir     # o serviço ignora, log WARN, nenhuma linha nova
```

Dá para publicar pelo console também: **Exchanges → `history.exchange` → Publish message**, routing
key `history.created`, propriedade `content_type` = `application/json`. O contrato completo da
mensagem está em [`docs/messaging/appointment-event.md`](docs/messaging/appointment-event.md).

> Não é preciso o header `__TypeId__` — o Spring AMQP infere o tipo pelo parâmetro do listener,
> então um produtor não-Spring publica normalmente.

### 2. Consultar por GraphQL

**Estado atual de cada consulta do paciente** — uma entrada por consulta:

```bash
curl -s -X POST http://localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ patientHistory(patientId: 10) { appointmentId eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

```json
{ "data": { "patientHistory": [
  { "appointmentId": "42", "eventStatus": "COMPLETED", "appointmentDate": "2026-09-12T14:00:00" }
] } }
```

**Trilha completa de uma consulta** — todo o histórico, com a data original preservada:

```bash
curl -s -X POST http://localhost:8080/graphql -H 'content-type: application/json' \
  -d '{"query":"{ appointmentTimeline(appointmentId: 42) { eventStatus appointmentDate } }"}' \
  | python3 -m json.tool
```

```json
{ "data": { "appointmentTimeline": [
  { "eventStatus": "SCHEDULED",   "appointmentDate": "2026-09-05T09:00:00" },
  { "eventStatus": "RESCHEDULED", "appointmentDate": "2026-09-12T14:00:00" },
  { "eventStatus": "COMPLETED",   "appointmentDate": "2026-09-12T14:00:00" }
] } }
```

Campos disponíveis e formatos em [`docs/graphql/queries.md`](docs/graphql/queries.md).

> O endpoint está **aberto** nesta fase. A autorização por role entra quando a Pessoa 1 publicar o
> formato do JWT.

### 3. Conferir o que foi gravado

```bash
docker exec historyservice-postgres psql -U postgres -d mydatabase \
  -c "SELECT appointment_id, event_status, appointment_date, occurred_at FROM medical_history ORDER BY occurred_at;"

curl -s -u guest:guest 'http://localhost:15672/api/queues/%2F?columns=name,messages' | python3 -m json.tool
```

Com eventos válidos, `history.queue` e `history.queue.dlq` ficam ambas em `0`.

### Cenários que valem exercitar

| Faça | Resultado esperado |
|---|---|
| Publicar um evento válido | 1 linha nova em `medical_history` |
| `./scripts/publicar-evento.sh --repetir` | nenhuma linha nova; log `WARN "Evento ... ja processado"` |
| `RESCHEDULED` com nova data | 2ª linha; `patientHistory` mostra só a nova, `appointmentTimeline` mantém a antiga |
| `CANCELLED` sem `appointmentDate` | nada persistido; +1 na `history.queue.dlq` |
| Campo fora do contrato (`"campoInesperado": true`) | nada persistido; +1 na DLQ |
| `patientHistory(patientId: "abc")` | erro `BAD_REQUEST`, não `INTERNAL_ERROR` |
| Paciente sem histórico | lista vazia, sem erro |

Para ver o que caiu na DLQ: **Queues → `history.queue.dlq` → Get messages**.

## Rodar os testes automatizados

Basta ter o **Docker em execução** — os testes sobem Postgres e RabbitMQ descartáveis via
Testcontainers, sem precisar do `docker compose` nem do `.env`.

```bash
./mvnw test
```

57 testes: contrato da mensagem, persistência, idempotência, resolvers GraphQL, tratamento de erro
e dois testes de integração ponta a ponta (RabbitMQ real → Postgres real → resposta GraphQL).

## Tabela `medical_history`

| Coluna | Origem |
|---|---|
| `event_id` | do evento — `UNIQUE`, deduplica reentregas do RabbitMQ |
| `event_status` | a transição: `SCHEDULED`, `RESCHEDULED`, `CANCELLED` ou `COMPLETED` |
| `appointment_id`, `patient_id`, `doctor_id` | do evento — só IDs, sem relacionamento JPA entre serviços |
| `patient_name`, `doctor_name` | snapshot opcional do evento (podem ser `NULL`) |
| `appointment_date` | início da consulta neste evento — `NOT NULL`, inclusive em cancelamento |
| `occurred_at` | quando o evento ocorreu no produtor; ordena a trilha |
| `recorded_at` | quando o `history-service` gravou |

## Encerrar

```bash
docker compose down       # preserva os dados nos volumes
docker compose down -v    # zera banco e fila
```

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `Connection refused` na 5432 ou 5672 | containers ainda subindo | `docker compose ps` e aguardar `(healthy)` |
| `port is already allocated` | porta ocupada por outro serviço | mudar `DB_PORT` / `RABBITMQ_PORT` no `.env` |
| `Port 8080 was already in use` | outra aplicação na 8080 | `SERVER_PORT=8081` no `.env` |
| Fila com `0 consumers` | aplicação não conectou | conferir o log de inicialização e as credenciais AMQP |
| Publiquei mas nada em `medical_history` | payload fora do contrato, ou `content_type` ausente | ver a `history.queue.dlq` e o log; comparar com o contrato |
| `variable is not set` no `docker compose up` | falta o `.env` | `cp .env.example .env` |

## Configuração

Todas as propriedades têm padrão igual ao `.env.example`, então a aplicação sobe mesmo sem exportar
nada. Para mudar portas, credenciais ou os nomes da topologia do RabbitMQ, edite o `.env` — ele é
lido tanto pelo Docker Compose quanto pela aplicação, e **não deve ser versionado**.

| Variável | Padrão |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `mydatabase` / `postgres` / `postgres` |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_MANAGEMENT_PORT` | `localhost` / `5672` / `15672` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | `guest` / `guest` / `/` |
| `RABBITMQ_EXCHANGE` / `RABBITMQ_QUEUE` / `RABBITMQ_ROUTING_KEY` | `history.exchange` / `history.queue` / `history.created` |
| `SERVER_PORT` | `8080` |
| `GRAPHIQL_ENABLED` | `true` |
