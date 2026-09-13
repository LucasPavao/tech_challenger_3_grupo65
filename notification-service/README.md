# notification-service

Serviço de notificações do Tech Challenge FIAP - Fase 3 - Grupo 65.
Responsável: Pessoa 5 (Mayara).

## O que este serviço faz

Consome eventos de agendamento (`AppointmentEvent`) publicados pelo
`appointment-service` via RabbitMQ, gera uma notificação/lembrete para o
paciente, persiste essa notificação no banco e simula o envio (hoje via
log; a interface `NotificationSender` permite trocar por e-mail/SMS depois
sem alterar o resto do serviço).

Fluxo:

```
appointment-service --publica AppointmentEvent--> RabbitMQ (notification.queue)
                                                        |
                                                        v
                                        NotificationMessageListener
                                                        |
                                                        v
                                            NotificationService
                                       (cria Notification PENDING -> salva
                                        -> "envia" -> marca como SENT)
                                                        |
                                                        v
                                            NotificationRepository (Postgres)
```

## Endpoints REST

| Método | Rota | Descrição |
|---|---|---|
| GET | `/notifications/patient/{patientId}` | Lista as notificações de um paciente |

## Formato do evento consumido (AppointmentEvent)

É o mesmo contrato consumido pelo history-service, documentado em
[`history-service/docs/messaging/appointment-event.md`](../history-service/docs/messaging/appointment-event.md):

```json
{
  "eventId": "8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b",
  "eventStatus": "SCHEDULED",
  "occurredAt": "2026-09-12T14:00:00Z",
  "appointmentId": 1,
  "patientId": 10,
  "patientName": null,
  "doctorId": 5,
  "doctorName": null,
  "appointmentDate": "2030-09-10T14:30:00",
  "description": "Consulta de rotina"
}
```

`eventStatus` é `SCHEDULED`, `RESCHEDULED`, `CANCELLED` ou `COMPLETED`, e define a mensagem
enviada ao paciente. Um campo fora do contrato faz a mensagem ir para a `notification.queue.dlq`
(`spring.jackson.deserialization.fail-on-unknown-properties=true`).

## Topologia RabbitMQ

| Item | Valor padrão | Variável |
|---|---|---|
| Exchange (topic) | `appointment.exchange` | `RABBITMQ_EXCHANGE` |
| Fila | `notification.queue` | `RABBITMQ_QUEUE` |
| Routing key | `notification.created` | `RABBITMQ_ROUTING_KEY` |
| Dead letter | `appointment.exchange.dlx` → `notification.queue.dlq` | derivada |

## Processamento e idempotência

1. **Validação.** O evento é validado contra o contrato (`@NotNull` no `AppointmentEvent`). Um
   evento inválido — por exemplo, sem `appointmentDate` — é rejeitado e vai para a
   `notification.queue.dlq`, sem gravar nada.
2. **Idempotência pelo `eventId`.** Cada evento gera no máximo uma notificação. Se o mesmo
   `eventId` chegar de novo (reentrega do RabbitMQ):
   - notificação já `SENT`: é devolvida sem reenviar;
   - notificação `PENDING`, de um envio que falhou: o envio é tentado de novo sobre a mesma linha.
   A garantia final é a constraint `uk_notifications_event_id UNIQUE (event_id)` no banco, que
   barra dois consumidores gravando o mesmo evento ao mesmo tempo. A garantia é de uma linha por
   evento; o envio é *pelo menos uma vez*: um reprocessamento depois de uma falha ao gravar `SENT`
   envia de novo.
3. **Envio.** A notificação nasce `PENDING`, é enviada por log (`LEMBRETE ENVIADO`) e fica `SENT`.
   Se o envio falhar, continua `PENDING` e a mensagem vai para a DLQ.

O schema é criado pela migration Flyway `V1__create_notifications.sql`; o Hibernate só valida
(`ddl-auto=validate`).

## Rodando junto com os outros serviços

Da raiz do monorepo — é o caminho normal:

```bash
make setup
make up
curl -s http://localhost:8082/actuator/health   # status, db e rabbit devem estar UP
```

O fluxo completo de teste está no [README da raiz](../README.md#fluxo-de-teste).

## Rodando pela IDE

Requisitos: Docker, JDK 21. Maven vem no wrapper.

```bash
cp .env.example .env                      # .env deste serviço
cp ../infra/.env.example ../infra/.env    # .env do RabbitMQ compartilhado — sem ele o compose falha
COMPOSE_PROFILES= docker compose up -d    # só Postgres + RabbitMQ, sem o container da app
./mvnw spring-boot:run
```

Os dois `cp` sobrescrevem arquivos que já existam. Para criar só os que faltam, rode `make setup`
na raiz do monorepo.

Não rode `docker compose up -d` sem `COMPOSE_PROFILES=`: o `.env` já traz `COMPOSE_PROFILES=apps`,
então o comando também sobe o container `notification-app`, que ocupa a 8082 — e o
`./mvnw spring-boot:run` seguinte morre com `Port already in use`.

**Cuidado:** como todos os serviços compartilham o mesmo projeto Compose (`name: grupo65`),
`docker compose down` de dentro desta pasta derruba o projeto **inteiro**. Para parar apenas este
serviço, use `docker compose stop notification-app notification-postgres`.

## Testar publicando um evento manualmente

Normalmente os eventos vêm do appointment-service: criar um agendamento em
`POST http://localhost:8080/appointments` já gera a notificação. Para testar este serviço
isoladamente, publique direto no broker pelo console (<http://localhost:15672>, `guest`/`guest`),
em **Exchanges → `appointment.exchange` → Publish message**, ou via curl:

```bash
curl -u guest:guest -H "content-type:application/json" -X POST \
  -d '{"properties":{"content_type":"application/json"},
       "routing_key":"notification.created",
       "payload":"{\"eventId\":\"8f14e45f-ceea-467a-9f4b-1d2c3e4f5a6b\",\"eventStatus\":\"SCHEDULED\",\"occurredAt\":\"2026-09-12T14:00:00Z\",\"appointmentId\":1,\"patientId\":10,\"doctorId\":5,\"appointmentDate\":\"2030-09-10T14:30:00\",\"description\":\"Consulta de rotina\"}",
       "payload_encoding":"string"}' \
  http://localhost:15672/api/exchanges/%2F/appointment.exchange/publish
```

No log da aplicação deve aparecer `Evento de appointment recebido: appointmentId=1,
eventStatus=SCHEDULED`, seguido de `LEMBRETE ENVIADO - paciente=10, ...`. Depois, confirme pela API:

```bash
curl http://localhost:8082/notifications/patient/10
```

Deve retornar a notificação com `status: SENT`.

## Rodar os testes

```bash
./mvnw test
```

Requer Docker: os testes de integração usam Testcontainers.

| Teste | O que prova |
|---|---|
| `NotificationMessageListenerIT` | evento publicado na exchange vira notificação `SENT`; o mesmo evento duas vezes gera uma única linha; payload fora do contrato vai para a DLQ sem gravar nada |
| `NotificationServiceTest` | mensagem para cada `eventStatus`, `PENDING` → `SENT`, falha no envio, evento nulo e inválido rejeitados, reentrega ignorada, `PENDING` reenviado na mesma linha, colisão entre consumidores |
| `NotificationServicePersistenceTest` | colisão de `event_id` contra o Postgres real resulta em uma única linha |
| `NotificationSchemaTest` | a migration cria todas as colunas e a constraint única |
| `NotificationControllerTest` | `GET /notifications/patient/{patientId}` |
| `NotificationApplicationTests` | o contexto sobe com Postgres e RabbitMQ em containers |

## Segurança

Este serviço ainda não exige autenticação. Na integração do auth-service, deve receber o mesmo
`SecurityConfig` de resource server JWT que a branch do auth-service adiciona ao appointment-service
e ao history-service: health público e demais rotas autenticadas.

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `stat .../.env: no such file or directory` | faltam os `.env` | `make setup` na raiz |
| `address already in use` na 5434 ou na 8082 | outro processo usando a porta | parar o processo, ou mudar `DB_PORT`/`SERVER_PORT` no `.env` |
| agendamento criado, mas nenhuma notificação | `.env` apontando para outra exchange, ou o serviço subiu depois da publicação | conferir `RABBITMQ_EXCHANGE=appointment.exchange` e ver [Atualizando de uma versão anterior](../README.md#atualizando-de-uma-versão-anterior) |
| mensagem na `notification.queue.dlq` | payload fora do contrato | comparar com o formato acima |
| serviço não sobe e o log mostra `relation "notifications" already exists` | tabela criada pelo Hibernate numa versão anterior | ver [Atualizando de uma versão anterior](../README.md#atualizando-de-uma-versão-anterior) |
