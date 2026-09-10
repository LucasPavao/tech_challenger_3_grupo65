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

```json
{
  "appointmentId": 1,
  "patientId": 10,
  "doctorId": 5,
  "dateTime": "2026-09-10T14:30:00",
  "description": "Consulta de rotina",
  "eventType": "CREATED"
}
```

`eventType` é `CREATED` ou `UPDATED`. Este contrato precisa ser idêntico
ao publicado pelo appointment-service — qualquer mudança de nome de campo
lá precisa ser replicada aqui.

## Pré-requisitos

| Ferramenta | Versão usada na validação |
|---|---|
| Docker Engine | 28.5 |
| Docker Compose | v2.40 (plugin `docker compose`) |
| JDK | 21 |
| Maven | via wrapper `./mvnw` (não precisa instalar) |

## 1. Configurar as variáveis de ambiente

```bash
cp .env.example .env
```

Por padrão as portas ficam **diferentes** das do `history-service`
(Postgres em `5433`, RabbitMQ em `5673`/`15673`) para dar pra rodar os
dois serviços ao mesmo tempo na sua máquina sem conflito de porta.

## 2. Subir a infraestrutura (modo isolado)

```bash
docker compose up -d
docker compose ps   # aguardar (healthy) nos dois containers
```

> Isso sobe um RabbitMQ só para você testar sozinha. Quando for integrar
> de verdade com o `appointment-service`, aponte `RABBITMQ_HOST` (e as
> portas) para o broker compartilhado do grupo, e não suba este RabbitMQ
> junto — para não ter dois brokers desencontrados.

## 3. Rodar a aplicação

```bash
set -a; source .env; set +a
./mvnw spring-boot:run
```

## 4. Verificar se subiu

```bash
curl -s http://localhost:8081/actuator/health
```

Espera-se `status`, `db` e `rabbit` como `UP`.

## 5. Testar o fluxo publicando um evento manualmente

Enquanto o `appointment-service` não estiver publicando de verdade, dá
pra simular pelo console do RabbitMQ (`http://localhost:15673`, usuário
`guest`/`guest`) em **Exchanges → appointment.exchange → Publish message**,
ou via curl:

```bash
curl -u guest:guest -H "content-type:application/json" -X POST \
  -d '{"properties":{"content_type":"application/json"},
       "routing_key":"notification.created",
       "payload":"{\"appointmentId\":1,\"patientId\":10,\"doctorId\":5,\"dateTime\":\"2026-09-10T14:30:00\",\"description\":\"Consulta de rotina\",\"eventType\":\"CREATED\"}",
       "payload_encoding":"string"}' \
  http://localhost:15673/api/exchanges/%2F/appointment.exchange/publish
```

No log da aplicação deve aparecer `Evento recebido: appointmentId=1,
eventType=CREATED`, seguido de `LEMBRETE ENVIADO - paciente=10, ...`.

Depois, confirme via API:

```bash
curl http://localhost:8081/notifications/patient/10
```

Deve retornar a notificação criada com `status: SENT`.

## 6. Rodar os testes

```bash
./mvnw test
```

- `NotificationApplicationTests` — sobe o contexto Spring.
- `NotificationServiceTest` — testes unitários (Mockito) cobrindo:
  criação da notificação como `PENDING`, marcação como `SENT` após o
  envio, mensagem diferente para `CREATED`/`UPDATED`, e busca por
  paciente.

## 7. Encerrar o ambiente

```bash
docker compose down       # preserva os dados nos volumes
docker compose down -v    # remove também os volumes
```

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `Connection refused: localhost:5433` ou `:5673` | containers ainda subindo ou parados | `docker compose ps` e aguardar `(healthy)` |
| `port is already allocated` | outro serviço usando a porta | mudar a porta no `.env` |
| Mensagem publicada mas nada acontece | routing key ou nome do exchange não batem com os deste serviço | conferir `app.rabbitmq.exchange`/`routing-key` no `.env` e no publish |
| Notificação não aparece na consulta | evento com `patientId` diferente do consultado, ou mensagem foi para a DLQ | checar `notification.queue.dlq` no console do RabbitMQ |
| `variable is not set` no `docker compose up` | falta o `.env` | `cp .env.example .env` |
