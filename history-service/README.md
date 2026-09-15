# history service

Serviço de histórico médico do Tech Challenge FIAP — Fase 3, Grupo 65.

Consome eventos de consulta publicados pelo `appointment-service` no RabbitMQ e expõe o histórico
por GraphQL. É um **log append-only**: cada evento vira uma linha nova, nada é atualizado ou
apagado. Uma consulta agendada, remarcada e concluída tem três linhas — e a data original continua
visível na primeira.

## Início rápido

Requisitos: Docker, JDK 21. Maven vem no wrapper.

```bash
cp .env.example .env                      # .env deste serviço
cp ../infra/.env.example ../infra/.env    # .env do RabbitMQ compartilhado — sem ele o compose falha
COMPOSE_PROFILES= docker compose up -d    # só Postgres + RabbitMQ, sem o container da app
./mvnw spring-boot:run
```

Os dois `cp` sobrescrevem arquivos que já existam. Para criar só os que faltam, rode `make setup` na raiz do monorepo.

Não rode `docker compose up -d` sem `COMPOSE_PROFILES=`: o `.env` já traz
`COMPOSE_PROFILES=apps`, então o comando também sobe o container `history-app`, que
ocupa a 8081 — e o `./mvnw spring-boot:run` seguinte morre com `Port already in use`.
(Alternativa, de dentro da raiz do monorepo: `make infra`.)

**Cuidado:** como todos os serviços compartilham o mesmo projeto Compose (`name:
grupo65`), `docker compose down` de dentro desta pasta derruba o projeto **inteiro**,
não só o history-service. Para parar apenas este serviço, use
`docker compose stop history-app history-postgres`.

Pronto quando aparecer `Started HistoryApplication`. A aplicação cria sozinha a topologia do
RabbitMQ (exchange, fila, binding e DLQ) e o Flyway cria a tabela.

| Endereço | O quê |
|---|---|
| <http://localhost:8081/graphql> | endpoint GraphQL |
| <http://localhost:8081/graphiql> | IDE web para explorar o schema |
| <http://localhost:15672> | console do RabbitMQ (`guest` / `guest`) |
| <http://localhost:8081/actuator/health> | `db` e `rabbit` devem estar `UP` |

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

Dá para publicar pelo console também: **Exchanges → `appointment.exchange` → Publish message**, routing
key `history.created`, propriedade `content_type` = `application/json`. O contrato completo da
mensagem está em [`docs/messaging/appointment-event.md`](docs/messaging/appointment-event.md).

> Não é preciso o header `__TypeId__` — o Spring AMQP infere o tipo pelo parâmetro do listener,
> então um produtor não-Spring publica normalmente.

### 2. Consultar por GraphQL

**Estado atual de cada consulta do paciente** — uma entrada por consulta:

```bash
curl -s -X POST http://localhost:8081/graphql -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
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
curl -s -X POST http://localhost:8081/graphql -H 'content-type: application/json' -H "Authorization: Bearer $TOKEN" \
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

> O endpoint exige um JWT do auth-service. Os exemplos acima precisam do header
> `-H "Authorization: Bearer $TOKEN"`, com o token obtido em
> `TOKEN=$(curl -s -X POST http://localhost:8083/auth/login -u maria.santos@hospital.com:Enfermeira@123 | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')`.
> `patientHistory` aceita DOCTOR, NURSE e PATIENT (só o próprio `patientId`); `appointmentTimeline`
> aceita DOCTOR e NURSE. A chave pública vem de `security.jwt.public-key` — pela IDE, o padrão é
> `file:../.jwt-keys/app.sub`, criada ao subir o ambiente pela raiz.

### 3. Conferir o que foi gravado

```bash
docker exec grupo65-history-postgres-1 psql -U postgres -d history_db \
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

### Collection do Postman

O roteiro com login, agendamento, histórico e notificações está na collection única da raiz:
[`docs/postman/tech-challenge-grupo65.postman_collection.json`](../docs/postman/tech-challenge-grupo65.postman_collection.json).

## Rodar os testes automatizados

Basta ter o **Docker em execução** — os testes sobem Postgres e RabbitMQ descartáveis via
Testcontainers, sem precisar do `docker compose` nem do `.env`.

```bash
./mvnw test
```

60 testes: contrato da mensagem, regra de acesso do paciente, persistência, idempotência, resolvers GraphQL, tratamento de erro
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
| `port is already allocated` | porta ocupada por outro serviço | mudar `DB_PORT` no `.env` deste serviço (a porta do RabbitMQ é `RABBITMQ_PORT` em `infra/.env`) |
| `Port 8081 was already in use` | o container `history-app` já está rodando (subiu com `docker compose up -d` sem `COMPOSE_PROFILES=`) e você está tentando rodar `./mvnw spring-boot:run` por cima | `docker compose stop history-app` antes de rodar pela IDE/`mvnw` |
| Fila com `0 consumers` | aplicação não conectou | conferir o log de inicialização e as credenciais AMQP |
| Publiquei mas nada em `medical_history` | payload fora do contrato, ou `content_type` ausente | ver a `history.queue.dlq` e o log; comparar com o contrato |
| `variable is not set` no `docker compose up` | falta o `.env` | `cp .env.example .env` |

## Configuração

Todas as propriedades têm padrão igual ao `.env.example`, então a aplicação sobe mesmo sem exportar
nada. Para mudar portas, credenciais ou os nomes da topologia do RabbitMQ, edite o `.env` — ele é
lido tanto pelo Docker Compose quanto pela aplicação, e **não deve ser versionado**.

Variáveis deste serviço (`history-service/.env`):

| Variável | Padrão |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `history_db` / `postgres` / `postgres` |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` |
| `RABBITMQ_EXCHANGE` / `RABBITMQ_QUEUE` / `RABBITMQ_ROUTING_KEY` | `appointment.exchange` / `history.queue` / `history.created` |
| `SERVER_PORT` | `8081` |
| `GRAPHIQL_ENABLED` | `true` |

Variáveis do broker compartilhado, em `infra/.env` (não neste `.env`):

| Variável | Padrão |
|---|---|
| `RABBITMQ_PORT` / `RABBITMQ_MANAGEMENT_PORT` | `5672` / `15672` |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` / `RABBITMQ_VHOST` | `guest` / `guest` / `/` |

`RABBITMQ_HOST` não está em nenhum `.env`: o padrão é `localhost` (para rodar a app pela
IDE/`mvnw` contra a infra publicada), e o Compose sobrescreve para `rabbitmq` via
`environment:` quando quem sobe é o container `history-app`.
